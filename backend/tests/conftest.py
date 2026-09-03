from __future__ import annotations

import base64
import os
from pathlib import Path

TEST_ROOT = Path("/tmp/tornado-backend-tests")
TEST_ROOT.mkdir(parents=True, exist_ok=True)
TEST_RUN_ID = str(os.getpid())
os.environ.update(
    {
        "ENVIRONMENT": "development",
        "DATABASE_URL": f"sqlite:///{TEST_ROOT / f'test-{TEST_RUN_ID}.db'}",
        "SECRET_KEY": "test-secret-key-with-more-than-thirty-two-characters",
        "FIELD_ENCRYPTION_KEY_B64": base64.b64encode(b"T" * 32).decode("ascii"),
        "SIGNING_PRIVATE_KEY_PATH": str(TEST_ROOT / f"signing-{TEST_RUN_ID}.pem"),
        "IMAGE_BOOTSTRAP_CACHE_PATH": str(
            TEST_ROOT / f"tornado-config-{TEST_RUN_ID}.png"
        ),
        # Legacy-only tests opt in explicitly; production defaults stay image-only.
        "LEGACY_BOOTSTRAP_ENABLED": "true",
        "SECURE_BOOTSTRAP_ENABLED": "false",
        "ALLOW_INSECURE_DEV_ATTESTATION": "true",
        "DEV_APP_CHECK_TOKEN": "test-app-check-token",
        "GEOLOCATION_ENABLED": "false",
        "ADMIN_USERNAME": "admin",
        "ADMIN_PASSWORD": "testing-password-123",
        "PUBLIC_BASE_URL": "https://bartarindl.ir",
        "EXPECTED_PACKAGE_NAME": "com.vpn.tornadovpn",
        "FIREBASE_APP_ID": "1:596541536411:android:dac16d9e842a9a99f82e3e",
    }
)

import pytest

from app.database import Base, SessionLocal, engine
from app.main import seed_defaults


@pytest.fixture(autouse=True)
def clean_database():
    cache_path = Path(os.environ["IMAGE_BOOTSTRAP_CACHE_PATH"])
    for path in (
        cache_path,
        cache_path.with_suffix(cache_path.suffix + ".json"),
        cache_path.with_suffix(cache_path.suffix + ".lock"),
    ):
        path.unlink(missing_ok=True)
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    seed_defaults()
    yield
    SessionLocal.remove() if hasattr(SessionLocal, "remove") else None
    for path in (
        cache_path,
        cache_path.with_suffix(cache_path.suffix + ".json"),
        cache_path.with_suffix(cache_path.suffix + ".lock"),
    ):
        path.unlink(missing_ok=True)
