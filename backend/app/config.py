from __future__ import annotations

import base64
import binascii
import re
from functools import lru_cache
from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

TORNADO_FIREBASE_APP_ID = "1:596541536411:android:dac16d9e842a9a99f82e3e"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    environment: str = "development"
    app_name: str = "Tornado VPN Management"
    public_base_url: str = "https://bartarindl.ir"
    database_url: str = "sqlite:///./data/tornado.db"
    redis_url: str = ""

    secret_key: str = "development-only-change-me"
    field_encryption_key_b64: str = ""
    signing_private_key_path: Path = Path("./data/signing_private.pem")
    signing_key_id: str = "tornado-signing-2026-01"

    admin_username: str = "admin"
    admin_password: str = ""
    secure_cookies: bool = False

    expected_package_name: str = "com.vpn.tornadovpn"
    allowed_signing_certificate_sha256: str = ""
    firebase_credentials_path: str = ""
    firebase_project_id: str = ""
    firebase_app_id: str = ""
    allow_insecure_dev_attestation: bool = False
    dev_app_check_token: str = "local-development-token"
    ga4_property_id: str = ""

    secure_bootstrap_enabled: bool = False
    legacy_bootstrap_enabled: bool = False
    image_bootstrap_enabled: bool = True
    image_bootstrap_base_path: Path = Path("./app/static/tornado-config-base.png")
    image_bootstrap_cache_path: Path = Path("./data/tornado-config.png")
    image_bootstrap_max_payload_bytes: int = Field(
        default=36 * 1024, ge=1024, le=36 * 1024
    )
    secure_session_ttl_seconds: int = Field(default=120, ge=30, le=300)
    device_attestation_ttl_seconds: int = Field(
        default=30 * 24 * 60 * 60, ge=24 * 60 * 60, le=90 * 24 * 60 * 60
    )
    device_attestation_grace_seconds: int = Field(
        default=72 * 60 * 60, ge=0, le=14 * 24 * 60 * 60
    )
    secure_session_rate_limit_per_minute: int = Field(default=12, ge=1, le=600)
    secure_session_ip_rate_limit_per_minute: int = Field(
        default=600, ge=60, le=20_000
    )
    secure_bootstrap_rate_limit_per_minute: int = Field(
        default=30, ge=1, le=600
    )
    secure_bootstrap_ip_rate_limit_per_minute: int = Field(
        default=1200, ge=120, le=50_000
    )
    app_check_token_replay_ttl_seconds: int = Field(default=3600, ge=300, le=7200)

    geolocation_enabled: bool = True
    geolocation_base_url: str = "https://ipwho.is"
    geolocation_timeout_seconds: float = Field(default=4.0, ge=1, le=15)
    max_request_clock_skew_seconds: int = Field(default=300, ge=30, le=900)
    bootstrap_rate_limit_per_minute: int = Field(default=20, ge=1, le=600)

    @property
    def is_production(self) -> bool:
        return self.environment.lower() == "production"

    @property
    def allowed_certificates(self) -> set[str]:
        return {
            value.replace(":", "").strip().upper()
            for value in self.allowed_signing_certificate_sha256.split(",")
            if value.strip()
        }

    @model_validator(mode="after")
    def validate_production_secrets(self) -> Settings:
        errors: list[str] = []
        if self.field_encryption_key_b64:
            try:
                field_key = base64.b64decode(
                    self.field_encryption_key_b64, validate=True
                )
            except (ValueError, binascii.Error):
                field_key = b""
            if len(field_key) != 32:
                errors.append("FIELD_ENCRYPTION_KEY_B64 (must be base64 for 32 bytes)")
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", self.signing_key_id):
            errors.append("SIGNING_KEY_ID")
        if not self.is_production:
            if errors:
                raise ValueError("Configuration is invalid: " + ", ".join(errors))
            return self
        if len(self.secret_key) < 32 or "CHANGE_ME" in self.secret_key:
            errors.append("SECRET_KEY")
        if not self.field_encryption_key_b64:
            errors.append("FIELD_ENCRYPTION_KEY_B64")
        if not self.public_base_url.startswith("https://"):
            errors.append("PUBLIC_BASE_URL (must use https)")
        if self.secure_bootstrap_enabled:
            if not self.redis_url:
                errors.append("REDIS_URL")
            if not self.firebase_project_id:
                errors.append("FIREBASE_PROJECT_ID")
            if not self.firebase_app_id:
                errors.append("FIREBASE_APP_ID")
            elif self.firebase_app_id != TORNADO_FIREBASE_APP_ID:
                errors.append("FIREBASE_APP_ID (must match Tornado Android app)")
            if not self.firebase_credentials_path:
                errors.append("FIREBASE_CREDENTIALS_PATH")
        if errors:
            raise ValueError(
                "Production configuration is incomplete: " + ", ".join(errors)
            )
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
