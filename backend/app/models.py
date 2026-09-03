from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import Boolean, DateTime, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from .database import Base


def utcnow() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


class AdminUser(Base):
    __tablename__ = "admin_users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class VpnServer(Base):
    __tablename__ = "vpn_servers"

    id: Mapped[int] = mapped_column(primary_key=True)
    public_id: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    config_encrypted: Mapped[str] = mapped_column(Text)
    protocol: Mapped[str] = mapped_column(String(32))
    tag: Mapped[str] = mapped_column(String(160), default="")
    host: Mapped[str] = mapped_column(String(255))
    port: Mapped[int | None] = mapped_column(Integer, nullable=True)
    resolved_ip: Mapped[str] = mapped_column(String(64), default="")
    country_code: Mapped[str] = mapped_column(String(8), default="")
    country_name: Mapped[str] = mapped_column(String(100), default="")
    priority: Mapped[int] = mapped_column(Integer, default=100)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class AdVpnServer(Base):
    """A VPN endpoint reserved for loading ads before the main UI opens.

    Ad servers deliberately live in their own table.  This prevents an expensive
    advertising route from ever being returned in the normal server pool and
    lets operators rotate/disable it independently.
    """

    __tablename__ = "ad_vpn_servers"

    id: Mapped[int] = mapped_column(primary_key=True)
    public_id: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    config_encrypted: Mapped[str] = mapped_column(Text)
    protocol: Mapped[str] = mapped_column(String(32))
    tag: Mapped[str] = mapped_column(String(160), default="")
    host: Mapped[str] = mapped_column(String(255))
    port: Mapped[int | None] = mapped_column(Integer, nullable=True)
    resolved_ip: Mapped[str] = mapped_column(String(64), default="")
    country_code: Mapped[str] = mapped_column(String(8), default="")
    country_name: Mapped[str] = mapped_column(String(100), default="")
    priority: Mapped[int] = mapped_column(Integer, default=100)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow
    )
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class AdSettings(Base):
    __tablename__ = "ad_settings"

    id: Mapped[int] = mapped_column(primary_key=True, default=1)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    admob_app_id: Mapped[str] = mapped_column(String(180), default="")
    legacy_banner_unit_id: Mapped[str] = mapped_column(String(180), default="")
    request_timeout_ms: Mapped[int] = mapped_column(Integer, default=8000)
    load_timeout_ms: Mapped[int] = mapped_column(Integer, default=12000)
    interstitial_every_connections: Mapped[int] = mapped_column(Integer, default=3)
    ump_required: Mapped[bool] = mapped_column(Boolean, default=True)
    test_mode: Mapped[bool] = mapped_column(Boolean, default=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow
    )


class AdPlacement(Base):
    __tablename__ = "ad_placements"

    id: Mapped[int] = mapped_column(primary_key=True)
    key: Mapped[str] = mapped_column(String(32), unique=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    ad_format: Mapped[str] = mapped_column(String(32), default="interstitial")
    unit_id: Mapped[str] = mapped_column(String(180), default="")
    every_n_actions: Mapped[int] = mapped_column(Integer, default=1)
    cooldown_seconds: Mapped[int] = mapped_column(Integer, default=60)
    timeout_ms: Mapped[int] = mapped_column(Integer, default=12000)
    max_per_day: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow
    )


class UpdatePolicy(Base):
    __tablename__ = "update_policy"

    id: Mapped[int] = mapped_column(primary_key=True, default=1)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    force_update: Mapped[bool] = mapped_column(Boolean, default=True)
    min_version_code: Mapped[int] = mapped_column(Integer, default=1000008)
    max_version_code: Mapped[int] = mapped_column(Integer, default=1000008)
    title: Mapped[str] = mapped_column(String(140), default="به‌روزرسانی ضروری")
    message: Mapped[str] = mapped_column(
        Text, default="برای ادامه استفاده، برنامه را به‌روزرسانی کنید."
    )
    direct_url: Mapped[str] = mapped_column(String(500), default="")
    play_store_url: Mapped[str] = mapped_column(
        String(500),
        default="https://play.google.com/store/apps/details?id=com.vpn.tornadovpn",
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow
    )


class AppSettings(Base):
    __tablename__ = "app_settings"

    id: Mapped[int] = mapped_column(primary_key=True, default=1)
    privacy_policy_url: Mapped[str] = mapped_column(String(500), default="")
    terms_url: Mapped[str] = mapped_column(String(500), default="")
    support_url: Mapped[str] = mapped_column(String(500), default="")
    share_url: Mapped[str] = mapped_column(
        String(500),
        default="https://play.google.com/store/apps/details?id=com.vpn.tornadovpn",
    )
    website_url: Mapped[str] = mapped_column(
        String(500), default="https://bartarindl.ir"
    )
    maintenance_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    maintenance_message: Mapped[str] = mapped_column(Text, default="")
    payload_ttl_seconds: Mapped[int] = mapped_column(Integer, default=900)
    fail_closed_on_integrity_error: Mapped[bool] = mapped_column(Boolean, default=True)
    config_revision: Mapped[int] = mapped_column(Integer, default=1)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow
    )


class Installation(Base):
    __tablename__ = "installations"

    id: Mapped[int] = mapped_column(primary_key=True)
    installation_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    public_key_hash: Mapped[str] = mapped_column(String(64), default="")
    signing_public_key_b64: Mapped[str] = mapped_column(Text, default="")
    credential_hash: Mapped[str] = mapped_column(String(64), default="", index=True)
    attested_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    attestation_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, index=True
    )
    attestation_grace_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True
    )
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    version_code: Mapped[int] = mapped_column(Integer, index=True)
    version_name: Mapped[str] = mapped_column(String(40), default="")
    first_seen_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_seen_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)


class SeenNonce(Base):
    __tablename__ = "seen_nonces"

    id: Mapped[int] = mapped_column(primary_key=True)
    nonce_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    installation_hash: Mapped[str] = mapped_column(String(64), index=True)
    seen_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)


class BootstrapEvent(Base):
    __tablename__ = "bootstrap_events"
    __table_args__ = (UniqueConstraint("request_id", name="uq_bootstrap_request_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    request_id: Mapped[str] = mapped_column(String(36))
    installation_hash: Mapped[str] = mapped_column(String(64), index=True)
    ip_hash: Mapped[str] = mapped_column(String(64), default="")
    version_code: Mapped[int] = mapped_column(Integer, default=0, index=True)
    accepted: Mapped[bool] = mapped_column(Boolean, index=True)
    reason: Mapped[str] = mapped_column(String(80), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)


class AuditLog(Base):
    __tablename__ = "audit_logs"

    id: Mapped[int] = mapped_column(primary_key=True)
    admin_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    username: Mapped[str] = mapped_column(String(64), default="system")
    action: Mapped[str] = mapped_column(String(80))
    target_type: Mapped[str] = mapped_column(String(40), default="")
    target_id: Mapped[str] = mapped_column(String(80), default="")
    details: Mapped[str] = mapped_column(Text, default="")
    ip_hash: Mapped[str] = mapped_column(String(64), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, index=True)
