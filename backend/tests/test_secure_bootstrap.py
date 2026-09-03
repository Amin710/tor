from __future__ import annotations

import base64
import hashlib
import json
import time
from datetime import UTC, timedelta

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient
from sqlalchemy import select

import app.secure_bootstrap as secure_module
import app.secure_store as store_module
from app.config import get_settings
from app.crypto import (
    bootstrap_request_bytes,
    get_signing_material,
    secure_envelope_header_v2,
    session_request_bytes,
    session_response_bytes,
    sha256_hex,
)
from app.database import SessionLocal
from app.main import app
from app.models import BootstrapEvent, Installation, VpnServer, utcnow
from app.security import FieldCipher


class FakePipeline:
    def __init__(self, redis):
        self.redis = redis
        self.commands = []

    def incr(self, key):
        self.commands.append(("incr", key))
        return self

    def expire(self, key, _ttl):
        self.commands.append(("expire", key))
        return self

    def execute(self):
        result = []
        for command, key in self.commands:
            if command == "incr":
                value = int(self.redis.values.get(key, "0")) + 1
                self.redis.values[key] = str(value)
                result.append(value)
            else:
                result.append(True)
        return result


class FakeRedis:
    def __init__(self):
        self.values: dict[str, str] = {}

    def ping(self):
        return True

    def pipeline(self, transaction=True):
        assert transaction is True
        return FakePipeline(self)

    def set(self, key, value, ex=None, nx=False):
        assert ex is not None and ex > 0
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def get(self, key):
        return self.values.get(key)

    def getdel(self, key):
        return self.values.pop(key, None)


@pytest.fixture
def secure_environment(monkeypatch):
    fake = FakeRedis()
    settings = get_settings().model_copy(
        update={
            "redis_url": "redis://fake/0",
            "secure_bootstrap_enabled": True,
        }
    )
    monkeypatch.setattr(store_module, "redis_client", lambda _url: fake)
    app.dependency_overrides[get_settings] = lambda: settings
    yield fake, settings
    app.dependency_overrides.pop(get_settings, None)


def add_server():
    with SessionLocal() as db:
        db.add(
            VpnServer(
                public_id="secure-de-01",
                config_encrypted=FieldCipher(get_settings()).encrypt(
                    "vless://secure-secret@1.2.3.4:443#DE"
                ),
                protocol="vless",
                tag="Germany",
                host="1.2.3.4",
                port=443,
                resolved_ip="1.2.3.4",
                country_code="DE",
                country_name="Germany",
                priority=1,
                enabled=True,
            )
        )
        db.commit()


def key_material():
    encryption_private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    signing_private = ec.generate_private_key(ec.SECP256R1())
    encryption_public = base64.b64encode(
        encryption_private.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")
    signing_public = base64.b64encode(
        signing_private.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode("ascii")
    return encryption_private, signing_private, encryption_public, signing_public


def session_body(encryption_public, signing_public, credential=""):
    return {
        "protocolVersion": 2,
        "installationId": "secure-installation-00000001",
        "packageName": "com.vpn.tornadovpn",
        "versionCode": 1000020,
        "versionName": "1000020",
        "deviceCredential": credential,
        "clientEncryptionPublicKey": encryption_public,
        "clientSigningPublicKey": signing_public,
    }


def signed_bootstrap_body(session, signing_private, timestamp=None):
    timestamp = timestamp or int(time.time())
    message = bootstrap_request_bytes(
        session_id=session["sessionId"],
        challenge_nonce=session["challengeNonce"],
        request_hash=session["requestHash"],
        client_timestamp=timestamp,
    )
    signature = signing_private.sign(message, ec.ECDSA(hashes.SHA256()))
    return {
        "protocolVersion": 2,
        "sessionId": session["sessionId"],
        "clientTimestampEpochSeconds": timestamp,
        "requestSignature": base64.b64encode(signature).decode("ascii"),
    }


def decrypt_envelope(envelope, encryption_private):
    header = secure_envelope_header_v2(
        key_id=envelope["keyId"],
        session_id=envelope["sessionId"],
        request_hash=envelope["requestHash"],
        issued_at=envelope["issuedAtEpochSeconds"],
        expires_at=envelope["expiresAtEpochSeconds"],
        request_nonce=envelope["requestNonce"],
        wrapped_key=envelope["wrappedKey"],
        iv=envelope["iv"],
    )
    signing_public = serialization.load_der_public_key(
        base64.b64decode(get_signing_material().public_key_b64)
    )
    signing_public.verify(
        base64.b64decode(envelope["signature"]),
        header + b"\n" + envelope["ciphertext"].encode("ascii"),
        ec.ECDSA(hashes.SHA256()),
    )
    content_key = encryption_private.decrypt(
        base64.b64decode(envelope["wrappedKey"]),
        padding.OAEP(
            mgf=padding.MGF1(hashes.SHA1()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    plaintext = AESGCM(content_key).decrypt(
        base64.b64decode(envelope["iv"]),
        base64.b64decode(envelope["ciphertext"]),
        header,
    )
    return json.loads(plaintext)


def assert_session_signature(session):
    message = session_response_bytes(
        session_id=session["sessionId"],
        challenge_nonce=session["challengeNonce"],
        request_hash=session["requestHash"],
        client_encryption_key_sha256=session["clientEncryptionKeySha256"],
        client_signing_key_sha256=session["clientSigningKeySha256"],
        issued_at=session["issuedAtEpochSeconds"],
        expires_at=session["expiresAtEpochSeconds"],
        attestation_required=session["attestationRequired"],
        key_id=session["keyId"],
    )
    public_key = serialization.load_der_public_key(
        base64.b64decode(get_signing_material().public_key_b64)
    )
    public_key.verify(
        base64.b64decode(session["signature"]),
        message,
        ec.ECDSA(hashes.SHA256()),
    )


def enroll(client, monkeypatch, encryption_private, signing_private, body):
    session_response = client.post("/v1/android/session", json=body)
    assert session_response.status_code == 200
    session = session_response.json()
    monkeypatch.setattr(
        secure_module,
        "verify_app_check",
        lambda token, settings: {
            "app_id": settings.firebase_app_id,
            "exp": int(time.time()) + 1800,
        },
    )
    response = client.post(
        "/v1/android/bootstrap/secure",
        json=signed_bootstrap_body(session, signing_private),
        headers={"X-Firebase-AppCheck": "limited-use-token-one"},
    )
    assert response.status_code == 200, response.text
    return session, response.json(), decrypt_envelope(response.json(), encryption_private)


def test_first_enrollment_is_signed_encrypted_and_app_check_is_not_preflighted(
    secure_environment, monkeypatch
):
    fake, _ = secure_environment
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    body = session_body(encryption_public, signing_public)
    with TestClient(app) as client:
        session_response = client.post("/v1/android/session", json=body)
        assert session_response.status_code == 200
        assert "vless://" not in session_response.text
        session = session_response.json()
        assert session["attestationRequired"] is True
        assert_session_signature(session)
        expected_request_hash = sha256_hex(
            session_request_bytes(
                installation_id=body["installationId"],
                package_name=body["packageName"],
                version_code=body["versionCode"],
                version_name=body["versionName"],
                device_credential=body["deviceCredential"],
                client_encryption_public_key=body["clientEncryptionPublicKey"],
                client_signing_public_key=body["clientSigningPublicKey"],
            )
        )
        assert session["requestHash"] == expected_request_hash

        signed = signed_bootstrap_body(session, signing_private)
        missing = client.post("/v1/android/bootstrap/secure", json=signed)
        assert missing.status_code == 401
        assert any(session["sessionId"] in key for key in fake.values)

        monkeypatch.setattr(
            secure_module,
            "verify_app_check",
            lambda token, settings: {
                "app_id": settings.firebase_app_id,
                "exp": int(time.time()) + 1800,
            },
        )
        accepted = client.post(
            "/v1/android/bootstrap/secure",
            json=signed,
            headers={"X-Firebase-AppCheck": "limited-use-token-first"},
        )
        assert accepted.status_code == 200, accepted.text
        assert "vless://" not in accepted.text
        payload = decrypt_envelope(accepted.json(), encryption_private)
        assert payload["servers"][0]["config"].startswith("vless://secure-secret")
        credential = payload["security"]["deviceCredential"]
        assert credential and credential not in repr(fake.values)

    with SessionLocal() as db:
        installation = db.scalar(select(Installation))
        assert installation is not None
        assert installation.credential_hash
        assert credential not in installation.credential_hash
        assert installation.signing_public_key_b64 == signing_public
        event = db.scalar(
            select(BootstrapEvent).where(BootstrapEvent.reason == "accepted_v2")
        )
        assert event is not None


def test_returning_enrolled_device_skips_app_check_and_session_is_one_time(
    secure_environment, monkeypatch
):
    fake, _ = secure_environment
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    with TestClient(app) as client:
        _, _, first_payload = enroll(
            client,
            monkeypatch,
            encryption_private,
            signing_private,
            session_body(encryption_public, signing_public),
        )
        credential = first_payload["security"]["deviceCredential"]
        second_session_response = client.post(
            "/v1/android/session",
            json=session_body(encryption_public, signing_public, credential),
        )
        assert second_session_response.status_code == 200
        second_session = second_session_response.json()
        assert second_session["attestationRequired"] is False
        assert credential not in repr(fake.values)
        assert "deviceCredentialEncrypted" in repr(fake.values)
        request_body = signed_bootstrap_body(second_session, signing_private)
        response = client.post("/v1/android/bootstrap/secure", json=request_body)
        assert response.status_code == 200
        payload = decrypt_envelope(response.json(), encryption_private)
        assert payload["security"]["deviceCredential"] == credential
        replay = client.post("/v1/android/bootstrap/secure", json=request_body)
        assert replay.status_code == 409


def test_copied_credential_without_keystore_private_key_is_rejected(
    secure_environment, monkeypatch
):
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    with TestClient(app) as client:
        _, _, first_payload = enroll(
            client,
            monkeypatch,
            encryption_private,
            signing_private,
            session_body(encryption_public, signing_public),
        )
        credential = first_payload["security"]["deviceCredential"]
        session = client.post(
            "/v1/android/session",
            json=session_body(encryption_public, signing_public, credential),
        ).json()
        attacker_private = ec.generate_private_key(ec.SECP256R1())
        response = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(session, attacker_private),
        )
        assert response.status_code == 403


def test_attestation_grace_is_narrow_and_does_not_extend_expiry(
    secure_environment, monkeypatch
):
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    with TestClient(app) as client:
        _, _, first_payload = enroll(
            client,
            monkeypatch,
            encryption_private,
            signing_private,
            session_body(encryption_public, signing_public),
        )
        credential = first_payload["security"]["deviceCredential"]
        with SessionLocal() as db:
            installation = db.scalar(select(Installation))
            installation.attestation_expires_at = utcnow() - timedelta(minutes=1)
            expired_epoch = int(
                installation.attestation_expires_at.replace(tzinfo=UTC).timestamp()
            )
            installation.attestation_grace_expires_at = utcnow() + timedelta(hours=1)
            db.commit()
        session = client.post(
            "/v1/android/session",
            json=session_body(encryption_public, signing_public, credential),
        ).json()
        assert session["attestationRequired"] is True
        grace_response = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(session, signing_private),
        )
        assert grace_response.status_code == 200
        grace_payload = decrypt_envelope(grace_response.json(), encryption_private)
        assert grace_payload["security"]["attestationExpiresAtEpochSeconds"] == expired_epoch

        with SessionLocal() as db:
            installation = db.scalar(select(Installation))
            installation.attestation_grace_expires_at = utcnow() - timedelta(seconds=1)
            db.commit()
        expired_session = client.post(
            "/v1/android/session",
            json=session_body(encryption_public, signing_public, credential),
        ).json()
        denied = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(expired_session, signing_private),
        )
        assert denied.status_code == 401


def test_app_check_token_hash_is_one_time_and_raw_token_is_never_stored(
    secure_environment, monkeypatch
):
    fake, _ = secure_environment
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    token = "limited-use-sensitive-token"
    monkeypatch.setattr(
        secure_module,
        "verify_app_check",
        lambda _token, settings: {
            "app_id": settings.firebase_app_id,
            "exp": int(time.time()) + 1800,
        },
    )
    with TestClient(app) as client:
        first = client.post(
            "/v1/android/session", json=session_body(encryption_public, signing_public)
        ).json()
        second = client.post(
            "/v1/android/session", json=session_body(encryption_public, signing_public)
        ).json()
        accepted = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(first, signing_private),
            headers={"X-Firebase-AppCheck": token},
        )
        assert accepted.status_code == 200
        replay = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(second, signing_private),
            headers={"X-Firebase-AppCheck": token},
        )
        assert replay.status_code == 409
    assert token not in repr(fake.values)
    assert hashlib.sha256(token.encode()).hexdigest() in repr(fake.values)


def test_secure_schema_rejects_coercion_newlines_and_wrong_package(
    secure_environment,
):
    _, _, encryption_public, signing_public = key_material()
    body = session_body(encryption_public, signing_public)
    with TestClient(app) as client:
        coerced = dict(body, versionCode="1000020")
        assert client.post("/v1/android/session", json=coerced).status_code == 422
        missing_protocol = dict(body)
        missing_protocol.pop("protocolVersion")
        assert client.post("/v1/android/session", json=missing_protocol).status_code == 422
        newline = dict(body, versionName="1000020\nforged")
        assert client.post("/v1/android/session", json=newline).status_code == 422
        wrong = dict(body, packageName="com.attacker.copy")
        assert client.post("/v1/android/session", json=wrong).status_code == 403


def test_legacy_gate_returns_426_without_configs(secure_environment):
    _, settings = secure_environment
    settings.legacy_bootstrap_enabled = False
    add_server()
    with TestClient(app) as client:
        response = client.post(
            "/v1/android/bootstrap",
            json={
                "installationId": "legacy-installation-test",
                "packageName": "com.vpn.tornadovpn",
                "versionCode": 2_000_000,
                "versionName": "2000000",
            },
        )
    assert response.status_code == 426
    assert response.json()["code"] == "SECURE_BOOTSTRAP_REQUIRED"
    assert "vless://" not in response.text
    assert response.headers["cache-control"] == "no-store"


def test_changed_signing_key_requires_fresh_attestation(
    secure_environment, monkeypatch
):
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    with TestClient(app) as client:
        _, _, first_payload = enroll(
            client,
            monkeypatch,
            encryption_private,
            signing_private,
            session_body(encryption_public, signing_public),
        )
        credential = first_payload["security"]["deviceCredential"]
        _, changed_private, _, changed_public = key_material()
        session_response = client.post(
            "/v1/android/session",
            json=session_body(encryption_public, changed_public, credential),
        )
        assert session_response.status_code == 200
        session = session_response.json()
        assert session["attestationRequired"] is True
        denied = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(session, changed_private),
        )
        assert denied.status_code == 401


def test_envelope_tampering_and_wrong_rsa_key_fail(
    secure_environment, monkeypatch
):
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    with TestClient(app) as client:
        _, envelope, _ = enroll(
            client,
            monkeypatch,
            encryption_private,
            signing_private,
            session_body(encryption_public, signing_public),
        )
    tampered = dict(envelope)
    replacement = "A" if tampered["ciphertext"][0] != "A" else "B"
    tampered["ciphertext"] = replacement + tampered["ciphertext"][1:]
    with pytest.raises(Exception):
        decrypt_envelope(tampered, encryption_private)
    wrong_private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    with pytest.raises(Exception):
        decrypt_envelope(envelope, wrong_private)


def test_clock_skew_and_revocation_are_fail_closed(
    secure_environment, monkeypatch
):
    _, settings = secure_environment
    add_server()
    encryption_private, signing_private, encryption_public, signing_public = key_material()
    with TestClient(app) as client:
        session = client.post(
            "/v1/android/session", json=session_body(encryption_public, signing_public)
        ).json()
        future = int(time.time()) + settings.max_request_clock_skew_seconds + 1
        skewed = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(session, signing_private, timestamp=future),
        )
        assert skewed.status_code == 400
        monkeypatch.setattr(
            secure_module,
            "verify_app_check",
            lambda token, configured: {
                "app_id": configured.firebase_app_id,
                "exp": int(time.time()) + 1800,
            },
        )
        accepted = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(session, signing_private),
            headers={"X-Firebase-AppCheck": "limited-use-token-after-skew"},
        )
        assert accepted.status_code == 200
        credential = decrypt_envelope(accepted.json(), encryption_private)["security"][
            "deviceCredential"
        ]
        with SessionLocal() as db:
            installation = db.scalar(select(Installation))
            installation.revoked = True
            db.commit()
        revoked_session = client.post(
            "/v1/android/session",
            json=session_body(encryption_public, signing_public, credential),
        ).json()
        assert revoked_session["attestationRequired"] is True
        revoked = client.post(
            "/v1/android/bootstrap/secure",
            json=signed_bootstrap_body(revoked_session, signing_private),
        )
        assert revoked.status_code == 401
