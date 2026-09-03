from __future__ import annotations

import subprocess
import sys
from pathlib import Path

from fastapi.testclient import TestClient

import app.main as main_module
from app.main import app
from app.secure_store import SecureStoreUnavailable


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def test_redis_outage_keeps_health_up_and_marks_readiness_unavailable(monkeypatch):
    def unavailable(_url):
        raise SecureStoreUnavailable("offline")

    monkeypatch.setattr(main_module, "secure_store_ping", unavailable)
    monkeypatch.setattr(
        main_module,
        "settings",
        main_module.settings.model_copy(update={"secure_bootstrap_enabled": True}),
    )
    with TestClient(app) as client:
        assert client.get("/healthz").status_code == 200
        ready = client.get("/readyz")
        assert ready.status_code == 503


def test_default_compose_has_no_redis_or_firebase_runtime_dependency():
    compose = (PROJECT_ROOT / "docker-compose.yml").read_text(encoding="utf-8")
    backend_section = compose.split("  backend:\n", 1)[1].split("\n  postgres:", 1)[0]
    assert "redis:" not in compose
    assert "REDIS_URL" not in compose
    assert "firebase_admin" not in compose
    assert "FIREBASE_CREDENTIALS_PATH" not in compose
    assert 'SECURE_BOOTSTRAP_ENABLED: "false"' in backend_section
    assert 'IMAGE_BOOTSTRAP_ENABLED: "true"' in backend_section
    assert "condition: service_healthy" in backend_section
    dockerfile = (PROJECT_ROOT / "Dockerfile").read_text(encoding="utf-8")
    assert "127.0.0.1:8000/readyz" in dockerfile


def test_reverse_proxy_overwrites_xff_and_uvicorn_does_not_trust_everyone():
    nginx = (PROJECT_ROOT / "deploy" / "nginx-bartarindl.ir.conf").read_text(
        encoding="utf-8"
    )
    dockerfile = (PROJECT_ROOT / "Dockerfile").read_text(encoding="utf-8")
    example_env = (PROJECT_ROOT / ".env.example").read_text(encoding="utf-8")
    assert "proxy_set_header X-Forwarded-For $remote_addr;" in nginx
    assert "$proxy_add_x_forwarded_for" not in nginx
    assert "FORWARDED_ALLOW_IPS:-*" not in dockerfile
    assert "FORWARDED_ALLOW_IPS=*" not in example_env
    assert "172.16.0.0/12" in dockerfile


def test_rollout_helper_updates_only_security_flags_and_keeps_backup(tmp_path: Path):
    assert ".env.security-v2-backup-*" in (
        PROJECT_ROOT / ".gitignore"
    ).read_text(encoding="utf-8")
    assert ".env.security-v2-backup-*" in (
        PROJECT_ROOT / ".dockerignore"
    ).read_text(encoding="utf-8")
    env_file = tmp_path / ".env"
    env_file.write_text(
        "SECRET_KEY=do-not-print-or-change\nLEGACY_BOOTSTRAP_ENABLED=false\n",
        encoding="utf-8",
    )
    result = subprocess.run(
        [
            sys.executable,
            str(PROJECT_ROOT / "scripts" / "configure_security_v2.py"),
            "rollout",
            "--env-file",
            str(env_file),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    updated = env_file.read_text(encoding="utf-8")
    assert "SECRET_KEY=do-not-print-or-change" in updated
    assert "SECURE_BOOTSTRAP_ENABLED=true" in updated
    assert "LEGACY_BOOTSTRAP_ENABLED=true" in updated
    assert "do-not-print-or-change" not in result.stdout
    backups = list(tmp_path.glob(".env.security-v2-backup-*"))
    assert len(backups) == 1
    assert "LEGACY_BOOTSTRAP_ENABLED=false" in backups[0].read_text(
        encoding="utf-8"
    )

    lockdown = subprocess.run(
        [
            sys.executable,
            str(PROJECT_ROOT / "scripts" / "configure_security_v2.py"),
            "lockdown",
            "--env-file",
            str(env_file),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    assert lockdown.returncode == 0
    assert "LEGACY_BOOTSTRAP_ENABLED=false" in env_file.read_text(encoding="utf-8")


def test_image_rollout_helper_disables_app_check_but_keeps_legacy_during_rollout(
    tmp_path: Path,
):
    assert ".env.image-bootstrap-backup-*" in (
        PROJECT_ROOT / ".gitignore"
    ).read_text(encoding="utf-8")
    assert ".env.image-bootstrap-backup-*" in (
        PROJECT_ROOT / ".dockerignore"
    ).read_text(encoding="utf-8")
    env_file = tmp_path / ".env"
    env_file.write_text(
        "SECRET_KEY=do-not-print-or-change\n"
        "SECURE_BOOTSTRAP_ENABLED=true\n"
        "LEGACY_BOOTSTRAP_ENABLED=false\n",
        encoding="utf-8",
    )
    command = [
        sys.executable,
        str(PROJECT_ROOT / "scripts" / "configure_image_bootstrap.py"),
        "rollout",
        "--env-file",
        str(env_file),
    ]
    result = subprocess.run(command, check=False, capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    updated = env_file.read_text(encoding="utf-8")
    assert "SECRET_KEY=do-not-print-or-change" in updated
    assert "IMAGE_BOOTSTRAP_ENABLED=true" in updated
    assert "IMAGE_BOOTSTRAP_MAX_PAYLOAD_BYTES=36864" in updated
    assert "SECURE_BOOTSTRAP_ENABLED=false" in updated
    assert "LEGACY_BOOTSTRAP_ENABLED=true" in updated
    assert "do-not-print-or-change" not in result.stdout
    assert len(list(tmp_path.glob(".env.image-bootstrap-backup-*"))) == 1
