from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import time
from datetime import UTC, timedelta
from typing import NoReturn

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .bootstrap import (
    NoServersAvailable,
    build_bootstrap_payload,
    is_update_required,
    record_event_values,
    update_required_content,
)
from .config import Settings, get_settings
from .crypto import (
    bootstrap_request_bytes,
    create_secure_envelope_v2,
    parse_client_rsa_public_key,
    parse_client_signing_public_key,
    public_key_der,
    session_request_bytes,
    session_response_bytes,
    sha256_hex,
    sign_ecdsa_sha256,
    verify_client_signature,
)
from .database import get_db
from .firebase_service import verify_app_check
from .models import Installation, UpdatePolicy, utcnow
from .schemas import SecureBootstrapRequest, SecureSessionRequest
from .secure_store import (
    SecureStoreUnavailable,
    consume_app_check_token,
    consume_session,
    get_session,
    put_session,
    rate_limited,
)
from .security import SessionCredentialCipher, request_ip, stable_hash

router = APIRouter(prefix="/v1/android", tags=["android-security-v2"])


def _credential_hash(value: str, settings: Settings) -> str:
    return stable_hash(f"device-credential-v2:{value}", settings.secret_key)


def _ip_hash(request: Request, settings: Settings) -> str:
    return stable_hash(f"secure-ip-v2:{request_ip(request)}", settings.secret_key)


def _canonical_b64(encoded: str, decoded: bytes) -> bool:
    return base64.b64encode(decoded).decode("ascii") == encoded


def _epoch(value) -> int:
    if value is None:
        return 0
    return int(value.replace(tzinfo=UTC).timestamp())


def _device_state(
    installation: Installation | None,
    *,
    credential_hash: str,
    signing_key_hash: str,
    signing_key_b64: str,
    now,
) -> tuple[bool, bool, bool]:
    credential_matches = bool(
        installation
        and credential_hash
        and installation.credential_hash
        and hmac.compare_digest(
            installation.credential_hash, credential_hash
        )
    )
    key_matches = bool(
        installation
        and installation.public_key_hash
        and hmac.compare_digest(installation.public_key_hash, signing_key_hash)
        and hmac.compare_digest(
            installation.signing_public_key_b64, signing_key_b64
        )
    )
    enrolled = bool(
        installation and credential_matches and key_matches and not installation.revoked
    )
    attestation_fresh = bool(
        enrolled
        and installation.attestation_expires_at
        and now < installation.attestation_expires_at
    )
    grace_eligible = bool(
        enrolled
        and not attestation_fresh
        and installation.attestation_grace_expires_at
        and now < installation.attestation_grace_expires_at
    )
    return not attestation_fresh, grace_eligible, enrolled


def _record_secure(
    db: Session,
    settings: Settings,
    request: Request,
    session: dict,
    accepted: bool,
    reason: str,
) -> None:
    record_event_values(
        db,
        settings,
        request,
        installation_hash=str(session.get("installationHash") or "unknown")[:64],
        version_code=int(session.get("versionCode") or 0),
        accepted=accepted,
        reason=reason,
    )


def _reject_secure(
    db: Session,
    settings: Settings,
    request: Request,
    session: dict,
    status_code: int,
    reason: str,
    message: str,
) -> NoReturn:
    _record_secure(db, settings, request, session, False, reason)
    raise HTTPException(status_code=status_code, detail=message)


def _store_error() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail="سرویس امنیت موقتاً در دسترس نیست؛ دوباره تلاش کنید.",
    )


@router.post("/session")
def create_session(
    request: Request,
    body: SecureSessionRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    if not settings.secure_bootstrap_enabled:
        raise HTTPException(status_code=503, detail="Secure bootstrap is disabled")
    if body.packageName != settings.expected_package_name:
        raise HTTPException(status_code=403, detail="برنامه معتبر نیست.")

    installation_hash = stable_hash(body.installationId, settings.secret_key)
    try:
        if rate_limited(
            settings.redis_url,
            "session-ip",
            _ip_hash(request, settings),
            settings.secure_session_ip_rate_limit_per_minute,
        ) or rate_limited(
            settings.redis_url,
            "session-installation",
            installation_hash,
            settings.secure_session_rate_limit_per_minute,
        ):
            raise HTTPException(status_code=429, detail="درخواست‌های بیش از حد مجاز.")
    except SecureStoreUnavailable as exc:
        raise _store_error() from exc

    update = db.get(UpdatePolicy, 1) or UpdatePolicy(id=1)
    if is_update_required(update, body.versionCode):
        return JSONResponse(
            status_code=status.HTTP_426_UPGRADE_REQUIRED,
            content=update_required_content(update),
        )

    try:
        encryption_key = parse_client_rsa_public_key(body.clientEncryptionPublicKey)
        signing_key = parse_client_signing_public_key(body.clientSigningPublicKey)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail="کلید عمومی دستگاه نامعتبر است.") from exc
    encryption_der = public_key_der(encryption_key)
    signing_der = public_key_der(signing_key)
    if not _canonical_b64(body.clientEncryptionPublicKey, encryption_der) or not _canonical_b64(
        body.clientSigningPublicKey, signing_der
    ):
        raise HTTPException(status_code=422, detail="قالب کلید عمومی canonical نیست.")

    request_hash = sha256_hex(
        session_request_bytes(
            installation_id=body.installationId,
            package_name=body.packageName,
            version_code=body.versionCode,
            version_name=body.versionName,
            device_credential=body.deviceCredential,
            client_encryption_public_key=body.clientEncryptionPublicKey,
            client_signing_public_key=body.clientSigningPublicKey,
        )
    )
    encryption_key_hash = sha256_hex(encryption_der)
    signing_key_hash = sha256_hex(signing_der)
    installation = db.scalar(
        select(Installation).where(Installation.installation_hash == installation_hash)
    )
    now_dt = utcnow()
    submitted_credential_hash = (
        _credential_hash(body.deviceCredential, settings) if body.deviceCredential else ""
    )
    attestation_required, grace_eligible, enrolled = _device_state(
        installation,
        credential_hash=submitted_credential_hash,
        signing_key_hash=signing_key_hash,
        signing_key_b64=body.clientSigningPublicKey,
        now=now_dt,
    )
    issued_at = int(time.time())
    expires_at = issued_at + settings.secure_session_ttl_seconds
    challenge_nonce = base64.b64encode(secrets.token_bytes(32)).decode("ascii")

    session_id = ""
    session_data = {}
    for _ in range(3):
        session_id = secrets.token_urlsafe(32)
        session_data = {
            "protocolVersion": 2,
            "sessionId": session_id,
            "challengeNonce": challenge_nonce,
            "requestHash": request_hash,
            "installationHash": installation_hash,
            "versionCode": body.versionCode,
            "versionName": body.versionName,
            "deviceCredentialHash": submitted_credential_hash,
            "deviceCredentialEncrypted": SessionCredentialCipher(settings).encrypt(
                body.deviceCredential,
                session_id=session_id,
                installation_hash=installation_hash,
            )
            if body.deviceCredential
            else "",
            "clientEncryptionPublicKey": body.clientEncryptionPublicKey,
            "clientSigningPublicKey": body.clientSigningPublicKey,
            "clientEncryptionKeySha256": encryption_key_hash,
            "clientSigningKeySha256": signing_key_hash,
            "issuedAtEpochSeconds": issued_at,
            "expiresAtEpochSeconds": expires_at,
            "attestationRequired": attestation_required,
            "graceEligible": grace_eligible,
            "enrolled": enrolled,
        }
        try:
            if put_session(
                settings.redis_url,
                session_id,
                session_data,
                settings.secure_session_ttl_seconds,
            ):
                break
        except SecureStoreUnavailable as exc:
            raise _store_error() from exc
    else:
        raise HTTPException(status_code=503, detail="ساخت نشست امنیتی ناموفق بود.")

    signature_input = session_response_bytes(
        session_id=session_id,
        challenge_nonce=challenge_nonce,
        request_hash=request_hash,
        client_encryption_key_sha256=encryption_key_hash,
        client_signing_key_sha256=signing_key_hash,
        issued_at=issued_at,
        expires_at=expires_at,
        attestation_required=attestation_required,
        key_id=settings.signing_key_id,
    )
    return {
        "protocolVersion": 2,
        "sessionId": session_id,
        "challengeNonce": challenge_nonce,
        "requestHash": request_hash,
        "clientEncryptionKeySha256": encryption_key_hash,
        "clientSigningKeySha256": signing_key_hash,
        "issuedAtEpochSeconds": issued_at,
        "expiresAtEpochSeconds": expires_at,
        "attestationRequired": attestation_required,
        "keyId": settings.signing_key_id,
        "signature": sign_ecdsa_sha256(signature_input),
    }


@router.post("/bootstrap/secure")
def secure_bootstrap(
    request: Request,
    body: SecureBootstrapRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
    app_check_token: str = Header(default="", alias="X-Firebase-AppCheck"),
):
    if not settings.secure_bootstrap_enabled:
        raise HTTPException(status_code=503, detail="Secure bootstrap is disabled")
    if len(app_check_token) > 8192:
        raise HTTPException(status_code=400, detail="توکن امنیتی نامعتبر است.")
    try:
        if rate_limited(
            settings.redis_url,
            "bootstrap-ip",
            _ip_hash(request, settings),
            settings.secure_bootstrap_ip_rate_limit_per_minute,
        ):
            raise HTTPException(status_code=429, detail="درخواست‌های بیش از حد مجاز.")
        session = get_session(settings.redis_url, body.sessionId)
    except SecureStoreUnavailable as exc:
        raise _store_error() from exc
    if session is None:
        raise HTTPException(status_code=409, detail="نشست منقضی یا قبلاً استفاده شده است.")

    try:
        if rate_limited(
            settings.redis_url,
            "bootstrap-installation",
            str(session["installationHash"]),
            settings.secure_bootstrap_rate_limit_per_minute,
        ):
            _reject_secure(
                db, settings, request, session, 429, "secure_rate_limited", "تلاش مجدد بیش از حد مجاز است."
            )
    except SecureStoreUnavailable as exc:
        raise _store_error() from exc

    now_epoch = int(time.time())
    issued_at = int(session.get("issuedAtEpochSeconds") or 0)
    expires_at = int(session.get("expiresAtEpochSeconds") or 0)
    if issued_at <= 0 or expires_at <= issued_at or now_epoch >= expires_at:
        _reject_secure(
            db, settings, request, session, 409, "session_expired", "نشست منقضی شده است."
        )
    if abs(body.clientTimestampEpochSeconds - now_epoch) > settings.max_request_clock_skew_seconds:
        _reject_secure(
            db, settings, request, session, 400, "clock_skew", "ساعت دستگاه صحیح نیست."
        )

    try:
        signing_key = parse_client_signing_public_key(
            str(session["clientSigningPublicKey"])
        )
        verify_client_signature(
            signing_key,
            body.requestSignature,
            bootstrap_request_bytes(
                session_id=body.sessionId,
                challenge_nonce=str(session["challengeNonce"]),
                request_hash=str(session["requestHash"]),
                client_timestamp=body.clientTimestampEpochSeconds,
            ),
        )
    except (KeyError, ValueError):
        _reject_secure(
            db,
            settings,
            request,
            session,
            403,
            "device_signature_failed",
            "امضای امنیتی دستگاه معتبر نیست.",
        )

    installation = db.scalar(
        select(Installation)
        .where(Installation.installation_hash == session["installationHash"])
        .with_for_update()
    )
    now_dt = utcnow()
    attestation_required, grace_eligible, enrolled = _device_state(
        installation,
        credential_hash=str(session.get("deviceCredentialHash") or ""),
        signing_key_hash=str(session["clientSigningKeySha256"]),
        signing_key_b64=str(session["clientSigningPublicKey"]),
        now=now_dt,
    )
    app_check_verified = False
    used_grace = False
    if attestation_required:
        if not app_check_token:
            if grace_eligible:
                used_grace = True
            else:
                _reject_secure(
                    db,
                    settings,
                    request,
                    session,
                    401,
                    "app_check_missing",
                    "تأیید امنیتی Google Play لازم است.",
                )
        else:
            try:
                claims = verify_app_check(app_check_token, settings)
            except Exception:
                _reject_secure(
                    db,
                    settings,
                    request,
                    session,
                    401,
                    "app_check_failed",
                    "تأیید امنیتی Google Play نامعتبر است.",
                )
            token_hash = hashlib.sha256(app_check_token.encode("utf-8")).hexdigest()
            token_ttl = settings.app_check_token_replay_ttl_seconds
            claim_expiry = int(claims.get("exp") or 0)
            if claim_expiry:
                token_ttl = max(1, min(token_ttl, claim_expiry - now_epoch))
            try:
                if not consume_app_check_token(settings.redis_url, token_hash, token_ttl):
                    _reject_secure(
                        db,
                        settings,
                        request,
                        session,
                        409,
                        "app_check_replay",
                        "توکن امنیتی قبلاً استفاده شده است.",
                    )
            except SecureStoreUnavailable as exc:
                raise _store_error() from exc
            app_check_verified = True

    encrypted_credential = str(session.get("deviceCredentialEncrypted") or "")
    try:
        submitted_credential = (
            SessionCredentialCipher(settings).decrypt(
                encrypted_credential,
                session_id=body.sessionId,
                installation_hash=str(session["installationHash"]),
            )
            if encrypted_credential
            else ""
        )
    except (ValueError, UnicodeDecodeError):
        _reject_secure(
            db,
            settings,
            request,
            session,
            409,
            "session_credential_invalid",
            "نشست امنیتی نامعتبر است.",
        )
    expected_credential_hash = str(session.get("deviceCredentialHash") or "")
    actual_credential_hash = (
        _credential_hash(submitted_credential, settings)
        if submitted_credential
        else ""
    )
    if not hmac.compare_digest(expected_credential_hash, actual_credential_hash):
        _reject_secure(
            db,
            settings,
            request,
            session,
            409,
            "session_credential_mismatch",
            "نشست امنیتی نامعتبر است.",
        )

    try:
        payload = build_bootstrap_payload(db, settings)
    except NoServersAvailable:
        _reject_secure(
            db,
            settings,
            request,
            session,
            503,
            "no_servers",
            "در حال حاضر سرور فعالی وجود ندارد.",
        )

    try:
        consumed = consume_session(settings.redis_url, body.sessionId)
    except SecureStoreUnavailable as exc:
        raise _store_error() from exc
    if consumed is None or consumed != session:
        _reject_secure(
            db,
            settings,
            request,
            session,
            409,
            "session_replayed",
            "نشست قبلاً استفاده شده است.",
        )

    device_credential = submitted_credential
    if installation is None:
        installation = Installation(
            installation_hash=str(session["installationHash"]),
            public_key_hash="",
            version_code=int(session["versionCode"]),
            version_name=str(session["versionName"]),
        )
        db.add(installation)
    if app_check_verified:
        if not enrolled or installation.revoked or not device_credential:
            device_credential = secrets.token_urlsafe(32)
        installation.credential_hash = _credential_hash(device_credential, settings)
        installation.public_key_hash = str(session["clientSigningKeySha256"])
        installation.signing_public_key_b64 = str(session["clientSigningPublicKey"])
        installation.attested_at = now_dt
        installation.attestation_expires_at = now_dt + timedelta(
            seconds=settings.device_attestation_ttl_seconds
        )
        installation.attestation_grace_expires_at = (
            installation.attestation_expires_at
            + timedelta(seconds=settings.device_attestation_grace_seconds)
        )
        installation.revoked = False
    elif not enrolled:
        # This can only be reached if logic above regresses; keep it fail-closed.
        _reject_secure(
            db, settings, request, session, 403, "device_not_enrolled", "دستگاه ثبت نشده است."
        )

    installation.version_code = int(session["versionCode"])
    installation.version_name = str(session["versionName"])
    installation.last_seen_at = now_dt
    payload["security"] = {
        "protocolVersion": 2,
        "deviceCredential": device_credential,
        "attestationExpiresAtEpochSeconds": _epoch(
            installation.attestation_expires_at
        ),
    }
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        _reject_secure(
            db,
            settings,
            request,
            session,
            409,
            "enrollment_conflict",
            "ثبت هم‌زمان دستگاه انجام شد؛ دوباره تلاش کنید.",
        )
    payload_expires = int(payload["expiresAtEpochSeconds"])
    try:
        encryption_key = parse_client_rsa_public_key(
            str(session["clientEncryptionPublicKey"])
        )
        envelope = create_secure_envelope_v2(
            payload,
            encryption_key,
            session_id=body.sessionId,
            request_hash=str(session["requestHash"]),
            request_nonce=str(session["challengeNonce"]),
            issued_at=now_epoch,
            expires_at=payload_expires,
        )
    except ValueError as exc:
        db.rollback()
        raise HTTPException(status_code=500, detail="ساخت پاسخ امن ناموفق بود.") from exc

    reason = "accepted_v2_grace" if used_grace else "accepted_v2"
    _record_secure(db, settings, request, session, True, reason)
    return envelope
