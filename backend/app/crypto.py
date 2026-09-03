from __future__ import annotations

import base64
import json
import os
import secrets
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .config import get_settings


@dataclass(frozen=True)
class SigningMaterial:
    private_key: ec.EllipticCurvePrivateKey
    public_key_b64: str


@lru_cache(maxsize=1)
def get_signing_material() -> SigningMaterial:
    settings = get_settings()
    path = Path(settings.signing_private_key_path)
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        generated = ec.generate_private_key(ec.SECP256R1())
        encoded = generated.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            # Another startup process created the same persistent key first.
            pass
        except OSError as exc:
            raise RuntimeError(f"Cannot create signing key at {path}: {exc}") from exc
        else:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(encoded)
    try:
        key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    except OSError as exc:
        raise RuntimeError(f"Cannot read signing key at {path}: {exc}") from exc
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise RuntimeError("Signing key must be an ECDSA P-256 private key")
    public_der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return SigningMaterial(key, base64.b64encode(public_der).decode("ascii"))


def parse_client_rsa_public_key(encoded: str) -> rsa.RSAPublicKey:
    try:
        der = base64.b64decode(encoded, validate=True)
        key = serialization.load_der_public_key(der)
    except (ValueError, TypeError) as exc:
        raise ValueError("Invalid client RSA public key") from exc
    if (
        not isinstance(key, rsa.RSAPublicKey)
        or key.key_size != 2048
        or key.public_numbers().e != 65537
    ):
        raise ValueError("Client key must be RSA-2048")
    return key


def parse_client_signing_public_key(encoded: str) -> ec.EllipticCurvePublicKey:
    try:
        der = base64.b64decode(encoded, validate=True)
        key = serialization.load_der_public_key(der)
    except (ValueError, TypeError) as exc:
        raise ValueError("Invalid client signing public key") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise ValueError("Client signing key must be ECDSA P-256")
    return key


def public_key_der(key: rsa.RSAPublicKey | ec.EllipticCurvePublicKey) -> bytes:
    return key.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def sha256_hex(value: bytes) -> str:
    digest = hashes.Hash(hashes.SHA256())
    digest.update(value)
    return digest.finalize().hex()


def session_request_bytes(
    *,
    installation_id: str,
    package_name: str,
    version_code: int,
    version_name: str,
    device_credential: str,
    client_encryption_public_key: str,
    client_signing_public_key: str,
) -> bytes:
    return "\n".join(
        (
            "TORNADO-SESSION-REQUEST-V2",
            "2",
            installation_id,
            package_name,
            str(version_code),
            version_name,
            device_credential,
            client_encryption_public_key,
            client_signing_public_key,
        )
    ).encode("utf-8")


def session_response_bytes(
    *,
    session_id: str,
    challenge_nonce: str,
    request_hash: str,
    client_encryption_key_sha256: str,
    client_signing_key_sha256: str,
    issued_at: int,
    expires_at: int,
    attestation_required: bool,
    key_id: str,
) -> bytes:
    return "\n".join(
        (
            "TORNADO-SESSION-RESPONSE-V2",
            "2",
            session_id,
            challenge_nonce,
            request_hash,
            client_encryption_key_sha256,
            client_signing_key_sha256,
            str(issued_at),
            str(expires_at),
            "1" if attestation_required else "0",
            key_id,
        )
    ).encode("utf-8")


def bootstrap_request_bytes(
    *,
    session_id: str,
    challenge_nonce: str,
    request_hash: str,
    client_timestamp: int,
) -> bytes:
    return "\n".join(
        (
            "TORNADO-BOOTSTRAP-REQUEST-V2",
            "2",
            session_id,
            challenge_nonce,
            request_hash,
            str(client_timestamp),
        )
    ).encode("utf-8")


def sign_ecdsa_sha256(message: bytes) -> str:
    signature = get_signing_material().private_key.sign(
        message, ec.ECDSA(hashes.SHA256())
    )
    return base64.b64encode(signature).decode("ascii")


def verify_client_signature(
    key: ec.EllipticCurvePublicKey, signature_b64: str, message: bytes
) -> None:
    try:
        signature = base64.b64decode(signature_b64, validate=True)
        if base64.b64encode(signature).decode("ascii") != signature_b64:
            raise ValueError("Device signature is not canonical Base64")
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
    except Exception as exc:
        raise ValueError("Invalid device request signature") from exc


def secure_envelope_header_v2(
    *,
    key_id: str,
    session_id: str,
    request_hash: str,
    issued_at: int,
    expires_at: int,
    request_nonce: str,
    wrapped_key: str,
    iv: str,
) -> bytes:
    return "\n".join(
        (
            "TORNADO-BOOTSTRAP-ENVELOPE-V2",
            "2",
            key_id,
            session_id,
            request_hash,
            str(issued_at),
            str(expires_at),
            request_nonce,
            wrapped_key,
            iv,
        )
    ).encode("utf-8")


def create_secure_envelope_v2(
    payload: dict[str, Any],
    client_public_key: rsa.RSAPublicKey,
    *,
    session_id: str,
    request_hash: str,
    request_nonce: str,
    issued_at: int,
    expires_at: int,
) -> dict[str, Any]:
    settings = get_settings()
    content_key = secrets.token_bytes(32)
    iv_bytes = secrets.token_bytes(12)
    wrapped_bytes = client_public_key.encrypt(
        content_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA1()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    wrapped_key = base64.b64encode(wrapped_bytes).decode("ascii")
    iv = base64.b64encode(iv_bytes).decode("ascii")
    header = secure_envelope_header_v2(
        key_id=settings.signing_key_id,
        session_id=session_id,
        request_hash=request_hash,
        issued_at=issued_at,
        expires_at=expires_at,
        request_nonce=request_nonce,
        wrapped_key=wrapped_key,
        iv=iv,
    )
    plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )
    encrypted = AESGCM(content_key).encrypt(iv_bytes, plaintext, header)
    ciphertext = base64.b64encode(encrypted).decode("ascii")
    return {
        "version": 2,
        "keyId": settings.signing_key_id,
        "sessionId": session_id,
        "requestHash": request_hash,
        "issuedAtEpochSeconds": issued_at,
        "expiresAtEpochSeconds": expires_at,
        "requestNonce": request_nonce,
        "wrappedKey": wrapped_key,
        "iv": iv,
        "ciphertext": ciphertext,
        "signature": sign_ecdsa_sha256(header + b"\n" + ciphertext.encode("ascii")),
    }


def create_secure_envelope(
    payload: dict[str, Any],
    client_public_key: rsa.RSAPublicKey,
    request_nonce: str,
    issued_at: int,
    expires_at: int,
) -> dict[str, Any]:
    settings = get_settings()
    signing = get_signing_material()
    content_key = secrets.token_bytes(32)
    iv = secrets.token_bytes(12)
    wrapped = client_public_key.encrypt(
        content_key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA1()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    wrapped_b64 = base64.b64encode(wrapped).decode("ascii")
    iv_b64 = base64.b64encode(iv).decode("ascii")
    header = (
        f"1|{settings.signing_key_id}|{issued_at}|{expires_at}|"
        f"{request_nonce}|{wrapped_b64}|{iv_b64}"
    )
    plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )
    encrypted = AESGCM(content_key).encrypt(iv, plaintext, header.encode("utf-8"))
    ciphertext_b64 = base64.b64encode(encrypted).decode("ascii")
    signature = signing.private_key.sign(
        f"{header}|{ciphertext_b64}".encode("utf-8"),
        ec.ECDSA(hashes.SHA256()),
    )
    return {
        "version": 1,
        "keyId": settings.signing_key_id,
        "issuedAtEpochSeconds": issued_at,
        "expiresAtEpochSeconds": expires_at,
        "requestNonce": request_nonce,
        "wrappedKey": wrapped_b64,
        "iv": iv_b64,
        "ciphertext": ciphertext_b64,
        "signature": base64.b64encode(signature).decode("ascii"),
    }
