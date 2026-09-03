from __future__ import annotations

import base64
import hashlib
import hmac
import secrets

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import Settings
from .models import AdminUser, AuditLog

password_hasher = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2)


def hash_password(password: str) -> str:
    return password_hasher.hash(password)


def verify_password(password: str, encoded: str) -> bool:
    try:
        return password_hasher.verify(encoded, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


def stable_hash(value: str, secret: str) -> str:
    return hmac.new(
        secret.encode("utf-8"), value.encode("utf-8"), hashlib.sha256
    ).hexdigest()


def request_ip(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _field_encryption_key(settings: Settings) -> bytes:
    if settings.field_encryption_key_b64:
        try:
            key = base64.b64decode(settings.field_encryption_key_b64, validate=True)
        except ValueError as exc:
            raise RuntimeError("FIELD_ENCRYPTION_KEY_B64 is not valid base64") from exc
    elif settings.is_production:
        raise RuntimeError("FIELD_ENCRYPTION_KEY_B64 is required in production")
    else:
        key = hashlib.sha256(settings.secret_key.encode("utf-8")).digest()
    if len(key) != 32:
        raise RuntimeError("FIELD_ENCRYPTION_KEY_B64 must decode to exactly 32 bytes")
    return key


class FieldCipher:
    """AES-256-GCM encryption for VPN configs stored in the database."""

    def __init__(self, settings: Settings):
        key = _field_encryption_key(settings)
        self._aes = AESGCM(key)

    def encrypt(self, plaintext: str) -> str:
        nonce = secrets.token_bytes(12)
        ciphertext = self._aes.encrypt(
            nonce, plaintext.encode("utf-8"), b"tornado-server-config-v1"
        )
        return base64.b64encode(nonce + ciphertext).decode("ascii")

    def decrypt(self, encoded: str) -> str:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) < 29:
            raise ValueError("Encrypted field is too short")
        return self._aes.decrypt(
            raw[:12], raw[12:], b"tornado-server-config-v1"
        ).decode(
            "utf-8"
        )


class SessionCredentialCipher:
    """Domain-separated encryption for a credential's short Redis lifetime."""

    _domain = b"tornado-device-credential-session-v2"

    def __init__(self, settings: Settings):
        master_key = _field_encryption_key(settings)
        key = hmac.new(master_key, self._domain, hashlib.sha256).digest()
        self._aes = AESGCM(key)

    def _aad(self, session_id: str, installation_hash: str) -> bytes:
        return b"\n".join(
            (self._domain, session_id.encode("ascii"), installation_hash.encode("ascii"))
        )

    def encrypt(
        self, credential: str, *, session_id: str, installation_hash: str
    ) -> str:
        nonce = secrets.token_bytes(12)
        encrypted = self._aes.encrypt(
            nonce,
            credential.encode("utf-8"),
            self._aad(session_id, installation_hash),
        )
        return base64.b64encode(nonce + encrypted).decode("ascii")

    def decrypt(
        self, encoded: str, *, session_id: str, installation_hash: str
    ) -> str:
        raw = base64.b64decode(encoded, validate=True)
        if len(raw) < 29:
            raise ValueError("Encrypted credential is too short")
        return self._aes.decrypt(
            raw[:12], raw[12:], self._aad(session_id, installation_hash)
        ).decode("utf-8")


def get_or_create_csrf(request: Request) -> str:
    token = request.session.get("csrf_token")
    if not token:
        token = secrets.token_urlsafe(32)
        request.session["csrf_token"] = token
    return token


def validate_csrf(request: Request, submitted: str) -> None:
    expected = request.session.get("csrf_token", "")
    if not expected or not hmac.compare_digest(expected, submitted or ""):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="درخواست نامعتبر است؛ صفحه را تازه کنید.",
        )


def current_admin(request: Request, db: Session) -> AdminUser:
    admin_id = request.session.get("admin_id")
    admin = db.get(AdminUser, admin_id) if admin_id else None
    if not admin or not admin.is_active:
        raise HTTPException(status_code=303, headers={"Location": "/admin/login"})
    return admin


def add_flash(request: Request, message: str, level: str = "success") -> None:
    flashes = request.session.get("flashes", [])
    flashes.append({"message": message, "level": level})
    request.session["flashes"] = flashes[-5:]


def pop_flashes(request: Request) -> list[dict[str, str]]:
    return request.session.pop("flashes", [])


def audit(
    db: Session,
    request: Request,
    admin: AdminUser | None,
    action: str,
    target_type: str = "",
    target_id: str = "",
    details: str = "",
) -> None:
    from .config import get_settings

    settings = get_settings()
    db.add(
        AuditLog(
            admin_id=admin.id if admin else None,
            username=admin.username if admin else "system",
            action=action,
            target_type=target_type,
            target_id=target_id,
            details=details[:1000],
            ip_hash=stable_hash(request_ip(request), settings.secret_key),
        )
    )


def seed_admin(db: Session, settings: Settings) -> None:
    existing = db.scalar(
        select(AdminUser).where(AdminUser.username == settings.admin_username)
    )
    if existing or not settings.admin_password:
        return
    db.add(
        AdminUser(
            username=settings.admin_username,
            password_hash=hash_password(settings.admin_password),
        )
    )
    db.commit()
