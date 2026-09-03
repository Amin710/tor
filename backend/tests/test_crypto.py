from __future__ import annotations

import base64
import json

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.config import get_settings
from app.crypto import (
    bootstrap_request_bytes,
    create_secure_envelope,
    get_signing_material,
    session_request_bytes,
    session_response_bytes,
    sha256_hex,
)
from app.security import SessionCredentialCipher


def test_secure_envelope_matches_android_contract():
    client_private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    payload = {
        "schemaVersion": 1,
        "servers": [{"id": "de-1", "config": "vless://secret"}],
    }
    envelope = create_secure_envelope(
        payload,
        client_private.public_key(),
        "bm9uY2Utbm9uY2Utbm9uY2Utbm9uY2U=",
        1000,
        1900,
    )

    header = "|".join(
        str(envelope[key])
        for key in (
            "version",
            "keyId",
            "issuedAtEpochSeconds",
            "expiresAtEpochSeconds",
            "requestNonce",
            "wrappedKey",
            "iv",
        )
    )
    public_key = serialization.load_der_public_key(
        base64.b64decode(get_signing_material().public_key_b64)
    )
    public_key.verify(
        base64.b64decode(envelope["signature"]),
        f"{header}|{envelope['ciphertext']}".encode(),
        ec.ECDSA(hashes.SHA256()),
    )
    content_key = client_private.decrypt(
        base64.b64decode(envelope["wrappedKey"]),
        padding.OAEP(
            mgf=padding.MGF1(hashes.SHA1()), algorithm=hashes.SHA256(), label=None
        ),
    )
    plaintext = AESGCM(content_key).decrypt(
        base64.b64decode(envelope["iv"]),
        base64.b64decode(envelope["ciphertext"]),
        header.encode(),
    )
    assert json.loads(plaintext) == payload


def test_v2_canonical_inputs_have_exact_lf_and_no_final_newline():
    request = session_request_bytes(
        installation_id="install-1",
        package_name="com.vpn.tornadovpn",
        version_code=1000020,
        version_name="1000020",
        device_credential="",
        client_encryption_public_key="RSA",
        client_signing_public_key="EC",
    )
    assert request == (
        b"TORNADO-SESSION-REQUEST-V2\n2\ninstall-1\ncom.vpn.tornadovpn\n"
        b"1000020\n1000020\n\nRSA\nEC"
    )
    assert not request.endswith(b"\n")
    assert sha256_hex(request) == (
        "317ea65060dbb9cef7aa1ffddf86a5222dab651a256d8e339aa277cbdd777104"
    )

    response = session_response_bytes(
        session_id="session",
        challenge_nonce="nonce",
        request_hash="hash",
        client_encryption_key_sha256="rsa-hash",
        client_signing_key_sha256="ec-hash",
        issued_at=100,
        expires_at=200,
        attestation_required=True,
        key_id="key-1",
    )
    assert response == (
        b"TORNADO-SESSION-RESPONSE-V2\n2\nsession\nnonce\nhash\nrsa-hash\n"
        b"ec-hash\n100\n200\n1\nkey-1"
    )
    bootstrap = bootstrap_request_bytes(
        session_id="session",
        challenge_nonce="nonce",
        request_hash="hash",
        client_timestamp=150,
    )
    assert bootstrap == (
        b"TORNADO-BOOTSTRAP-REQUEST-V2\n2\nsession\nnonce\nhash\n150"
    )


def test_transient_credential_cipher_is_bound_to_session_and_installation():
    cipher = SessionCredentialCipher(get_settings())
    encrypted = cipher.encrypt(
        "credential-value-which-is-never-raw-in-redis",
        session_id="session-a",
        installation_hash="a" * 64,
    )
    assert (
        cipher.decrypt(
            encrypted, session_id="session-a", installation_hash="a" * 64
        )
        == "credential-value-which-is-never-raw-in-redis"
    )
    with pytest.raises(Exception):
        cipher.decrypt(
            encrypted, session_id="session-b", installation_hash="a" * 64
        )
    with pytest.raises(Exception):
        cipher.decrypt(
            encrypted, session_id="session-a", installation_hash="b" * 64
        )
