from __future__ import annotations

import time
import uuid
from datetime import timedelta
from functools import lru_cache

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import JSONResponse
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .config import Settings, get_settings
from .database import get_db
from .models import (
    AdPlacement,
    AdSettings,
    AdVpnServer,
    AppSettings,
    BootstrapEvent,
    Installation,
    UpdatePolicy,
    VpnServer,
    utcnow,
)
from .schemas import BootstrapRequest
from .security import FieldCipher, request_ip, stable_hash

router = APIRouter(prefix="/v1/android", tags=["android"])


class NoServersAvailable(RuntimeError):
    pass


def record_event_values(
    db: Session,
    settings: Settings,
    request: Request,
    *,
    installation_hash: str,
    version_code: int,
    accepted: bool,
    reason: str,
) -> None:
    db.add(
        BootstrapEvent(
            request_id=str(uuid.uuid4()),
            installation_hash=installation_hash,
            ip_hash=stable_hash(request_ip(request), settings.secret_key),
            version_code=version_code,
            accepted=accepted,
            reason=reason[:80],
        )
    )
    db.commit()


def _record_event(
    db: Session,
    settings: Settings,
    request: Request,
    body: BootstrapRequest,
    accepted: bool,
    reason: str,
) -> None:
    record_event_values(
        db,
        settings,
        request,
        installation_hash=stable_hash(body.installationId, settings.secret_key),
        version_code=body.versionCode,
        accepted=accepted,
        reason=reason,
    )


def _reject(
    db: Session,
    settings: Settings,
    request: Request,
    body: BootstrapRequest,
    status_code: int,
    reason: str,
    public_message: str,
) -> None:
    _record_event(db, settings, request, body, False, reason)
    raise HTTPException(status_code=status_code, detail=public_message)


@lru_cache(maxsize=2)
def _redis_client(url: str):
    import redis

    return redis.Redis.from_url(
        url, socket_connect_timeout=1, socket_timeout=1, decode_responses=True
    )


def _rate_limited(db: Session, settings: Settings, installation_hash: str) -> bool:
    if settings.redis_url:
        try:
            minute = int(time.time()) // 60
            client = _redis_client(settings.redis_url)
            key = f"tornado:rl:i:{installation_hash}:{minute}"
            pipe = client.pipeline(transaction=True)
            pipe.incr(key)
            pipe.expire(key, 120)
            values = pipe.execute()
            return int(values[0]) > settings.bootstrap_rate_limit_per_minute
        except Exception:
            # Legacy stays available during rollout; v2 intentionally fails closed.
            pass
    cutoff = utcnow() - timedelta(minutes=1)
    count = (
        db.scalar(
            select(func.count(BootstrapEvent.id)).where(
                BootstrapEvent.created_at >= cutoff,
                BootstrapEvent.installation_hash == installation_hash,
            )
        )
        or 0
    )
    return count >= settings.bootstrap_rate_limit_per_minute


def _update_installation(
    db: Session, body: BootstrapRequest, settings: Settings
) -> None:
    installation_hash = stable_hash(body.installationId, settings.secret_key)
    installation = db.scalar(
        select(Installation).where(Installation.installation_hash == installation_hash)
    )
    if installation is None:
        db.add(
            Installation(
                installation_hash=installation_hash,
                public_key_hash="",
                version_code=body.versionCode,
                version_name=body.versionName,
            )
        )
    else:
        installation.version_code = body.versionCode
        installation.version_name = body.versionName
        installation.last_seen_at = utcnow()


def _placement_payload(
    item: AdPlacement | None,
    *,
    ads_enabled: bool,
    test_mode: bool,
) -> dict:
    if item is None:
        return {"enabled": False, "unitId": ""}
    has_effective_unit = bool(item.unit_id) or test_mode
    every_n_actions = item.every_n_actions
    if item.ad_format == "interstitial" and ads_enabled and not test_mode:
        every_n_actions = max(2, every_n_actions)
    return {
        "enabled": ads_enabled and item.enabled and has_effective_unit,
        "format": item.ad_format,
        "unitId": item.unit_id if ads_enabled else "",
        "everyNActions": every_n_actions,
        "cooldownSeconds": item.cooldown_seconds,
        "timeoutMs": item.timeout_ms,
        "maxPerDay": item.max_per_day,
    }


def update_required_content(update: UpdatePolicy) -> dict:
    return {
        "code": "UPDATE_REQUIRED",
        "title": update.title,
        "message": update.message,
        "minVersionCode": update.min_version_code,
        "maxVersionCode": update.max_version_code,
        "directUrl": update.direct_url,
        "playStoreUrl": update.play_store_url,
    }


def is_update_required(update: UpdatePolicy, version_code: int) -> bool:
    return update.enabled and update.force_update and version_code < update.min_version_code


def build_bootstrap_payload(db: Session, settings: Settings) -> dict:
    server_rows = db.scalars(
        select(VpnServer)
        .where(VpnServer.enabled.is_(True))
        .order_by(VpnServer.priority, VpnServer.id)
        .limit(100)
    ).all()
    if not server_rows:
        raise NoServersAvailable

    ad_server_rows = db.scalars(
        select(AdVpnServer)
        .where(AdVpnServer.enabled.is_(True))
        .order_by(AdVpnServer.priority, AdVpnServer.id)
        .limit(50)
    ).all()
    app = db.get(AppSettings, 1) or AppSettings(id=1)
    ads = db.get(AdSettings, 1) or AdSettings(id=1)
    update = db.get(UpdatePolicy, 1) or UpdatePolicy(id=1)
    placements = {item.key: item for item in db.scalars(select(AdPlacement)).all()}
    after = placements.get("after_connect")
    before = placements.get("before_connect")
    legacy_interstitial = after if after and after.enabled else before
    now = int(time.time())
    ttl = min(max(app.payload_ttl_seconds, 60), 86400)
    cipher = FieldCipher(settings)
    return {
        "schemaVersion": 1,
        "expiresAtEpochSeconds": now + ttl,
        "servers": [
            {
                "id": item.public_id,
                "config": cipher.decrypt(item.config_encrypted),
                "priority": item.priority,
                "enabled": item.enabled,
            }
            for item in server_rows
        ],
        "adServers": [
            {
                "id": item.public_id,
                "config": cipher.decrypt(item.config_encrypted),
                "priority": item.priority,
                "enabled": item.enabled,
            }
            for item in ad_server_rows
        ]
        if (
            ads.enabled
            and (splash := placements.get("splash")) is not None
            and splash.enabled
            and (bool(splash.unit_id) or ads.test_mode)
        )
        else [],
        "ads": {
            "enabled": ads.enabled,
            "bannerUnitId": ads.legacy_banner_unit_id if ads.enabled else "",
            "interstitialUnitId": legacy_interstitial.unit_id
            if ads.enabled and legacy_interstitial
            else "",
            "rewardedUnitId": "",
            "interstitialEveryConnections": (
                ads.interstitial_every_connections
                if ads.test_mode or not ads.enabled
                else max(2, ads.interstitial_every_connections)
            ),
            "requestTimeoutMs": ads.request_timeout_ms,
            "loadTimeoutMs": ads.load_timeout_ms,
            "umpRequired": ads.ump_required,
            "testMode": ads.test_mode,
            "placements": {
                "beforeConnect": _placement_payload(
                    before, ads_enabled=ads.enabled, test_mode=ads.test_mode
                ),
                "afterConnect": _placement_payload(
                    after, ads_enabled=ads.enabled, test_mode=ads.test_mode
                ),
                "appOpen": _placement_payload(
                    placements.get("app_open"),
                    ads_enabled=ads.enabled,
                    test_mode=ads.test_mode,
                ),
                "splash": _placement_payload(
                    placements.get("splash"),
                    ads_enabled=ads.enabled,
                    test_mode=ads.test_mode,
                ),
            },
        },
        "app": {
            "privacyPolicyUrl": app.privacy_policy_url,
            "shareUrl": app.share_url or update.play_store_url,
            "supportUrl": app.support_url,
            "maintenanceMessage": app.maintenance_message
            if app.maintenance_enabled
            else "",
            "forceUpdateMinVersionCode": update.min_version_code
            if update.enabled and update.force_update
            else 0,
            "failClosedOnIntegrityError": app.fail_closed_on_integrity_error,
            "termsUrl": app.terms_url,
            "websiteUrl": app.website_url,
            "configRevision": app.config_revision,
        },
        "updatePolicy": {
            "enabled": update.enabled,
            "force": update.force_update,
            "minVersionCode": update.min_version_code,
            "maxVersionCode": update.max_version_code,
            "title": update.title,
            "message": update.message,
            "directUrl": update.direct_url,
            "playStoreUrl": update.play_store_url,
        },
    }


@router.post("/bootstrap")
def bootstrap(
    request: Request,
    body: BootstrapRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    installation_hash = stable_hash(body.installationId, settings.secret_key)
    update = db.get(UpdatePolicy, 1) or UpdatePolicy(id=1)
    if not settings.legacy_bootstrap_enabled:
        _record_event(db, settings, request, body, False, "legacy_disabled")
        content = update_required_content(update)
        content["code"] = "SECURE_BOOTSTRAP_REQUIRED"
        return JSONResponse(status_code=status.HTTP_426_UPGRADE_REQUIRED, content=content)

    if _rate_limited(db, settings, installation_hash):
        _reject(
            db,
            settings,
            request,
            body,
            429,
            "rate_limited",
            "تعداد درخواست‌ها بیش از حد مجاز است.",
        )
    if is_update_required(update, body.versionCode):
        _record_event(db, settings, request, body, False, "update_required")
        return JSONResponse(
            status_code=status.HTTP_426_UPGRADE_REQUIRED,
            content=update_required_content(update),
        )

    try:
        payload = build_bootstrap_payload(db, settings)
    except NoServersAvailable:
        _reject(
            db,
            settings,
            request,
            body,
            503,
            "no_servers",
            "در حال حاضر سرور فعالی وجود ندارد.",
        )
    _update_installation(db, body, settings)
    _record_event(db, settings, request, body, True, "accepted")
    return payload
