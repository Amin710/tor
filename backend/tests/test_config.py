from __future__ import annotations

import base64

import pytest
from pydantic import ValidationError

from app.config import Settings


def test_image_channel_is_the_safe_default():
    assert Settings.model_fields["image_bootstrap_enabled"].default is True
    assert Settings.model_fields["secure_bootstrap_enabled"].default is False
    assert Settings.model_fields["legacy_bootstrap_enabled"].default is False


def test_invalid_field_key_is_rejected_before_server_create():
    with pytest.raises(ValidationError, match="FIELD_ENCRYPTION_KEY_B64"):
        Settings(
            environment="development",
            field_encryption_key_b64="not-valid-base64",
        )


def test_production_secure_bootstrap_pins_exact_firebase_app_id():
    with pytest.raises(ValidationError, match="must match Tornado Android app"):
        Settings(
            environment="production",
            secret_key="S" * 48,
            field_encryption_key_b64=base64.b64encode(b"K" * 32).decode(),
            public_base_url="https://bartarindl.ir",
            redis_url="redis://redis:6379/0",
            firebase_project_id="tornado-cc249",
            firebase_app_id="1:596541536411:android:wrong",
            firebase_credentials_path="/run/secrets/firebase_admin",
            secure_bootstrap_enabled=True,
        )


def test_production_image_bootstrap_does_not_require_firebase_or_redis():
    settings = Settings(
        environment="production",
        secret_key="S" * 48,
        field_encryption_key_b64=base64.b64encode(b"K" * 32).decode(),
        public_base_url="https://bartarindl.ir",
        secure_bootstrap_enabled=False,
        image_bootstrap_enabled=True,
        firebase_project_id="",
        firebase_app_id="",
        firebase_credentials_path="",
        redis_url="",
    )
    assert settings.image_bootstrap_enabled is True
    assert settings.secure_bootstrap_enabled is False
