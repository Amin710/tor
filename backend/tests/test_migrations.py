from __future__ import annotations

import os
import sqlite3
import subprocess
import sys
from pathlib import Path


def test_fresh_upgrade_is_repeatable_and_seeds_splash(tmp_path: Path):
    database_path = tmp_path / "migration.db"
    environment = os.environ.copy()
    environment["DATABASE_URL"] = f"sqlite:///{database_path}"
    project_root = Path(__file__).resolve().parents[1]
    command = [sys.executable, "-m", "alembic", "upgrade", "head"]

    first = subprocess.run(
        command,
        cwd=project_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert first.returncode == 0, first.stdout + first.stderr
    second = subprocess.run(
        command,
        cwd=project_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert second.returncode == 0, second.stdout + second.stderr

    with sqlite3.connect(database_path) as connection:
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            )
        }
        splash_count = connection.execute(
            "SELECT COUNT(*) FROM ad_placements WHERE key = 'splash'"
        ).fetchone()[0]
        revision = connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchone()[0]
        installation_columns = {
            row[1] for row in connection.execute("PRAGMA table_info(installations)")
        }

    assert "ad_vpn_servers" in tables
    assert splash_count == 1
    assert "credential_hash" in installation_columns
    assert revision == "20260830_0003"


def test_upgrade_from_legacy_revision_creates_missing_ad_server_table(
    tmp_path: Path,
):
    database_path = tmp_path / "legacy.db"
    environment = os.environ.copy()
    environment["DATABASE_URL"] = f"sqlite:///{database_path}"
    project_root = Path(__file__).resolve().parents[1]

    initial = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "20260824_0001"],
        cwd=project_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert initial.returncode == 0, initial.stdout + initial.stderr
    # The historical production schema at revision 0001 did not contain this
    # table. Current metadata is broader, so remove it to faithfully reproduce
    # an in-place upgrade from that release.
    with sqlite3.connect(database_path) as connection:
        connection.execute("DROP TABLE ad_vpn_servers")

    upgraded = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        cwd=project_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
    with sqlite3.connect(database_path) as connection:
        table_count = connection.execute(
            "SELECT COUNT(*) FROM sqlite_master "
            "WHERE type = 'table' AND name = 'ad_vpn_servers'"
        ).fetchone()[0]
        splash_count = connection.execute(
            "SELECT COUNT(*) FROM ad_placements WHERE key = 'splash'"
        ).fetchone()[0]

    assert table_count == 1
    assert splash_count == 1


def test_0003_upgrades_real_legacy_installation_without_losing_it(tmp_path: Path):
    database_path = tmp_path / "legacy-security.db"
    environment = os.environ.copy()
    environment["DATABASE_URL"] = f"sqlite:///{database_path}"
    project_root = Path(__file__).resolve().parents[1]
    initial = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "20260829_0002"],
        cwd=project_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert initial.returncode == 0, initial.stdout + initial.stderr
    # Revision 0001 historically reflected the then-current metadata. Remove the
    # v2 fields to faithfully reproduce the deployed 0002 schema.
    with sqlite3.connect(database_path) as connection:
        for index in (
            "ix_installations_credential_hash",
            "ix_installations_attestation_expires_at",
            "ix_installations_revoked",
        ):
            connection.execute(f"DROP INDEX IF EXISTS {index}")
        for column in (
            "revoked",
            "attestation_grace_expires_at",
            "attestation_expires_at",
            "attested_at",
            "credential_hash",
            "signing_public_key_b64",
        ):
            connection.execute(f"ALTER TABLE installations DROP COLUMN {column}")
        connection.execute(
            "INSERT INTO installations "
            "(installation_hash, public_key_hash, version_code, version_name, "
            "first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            ("a" * 64, "", 1000019, "1000019"),
        )

    upgraded = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        cwd=project_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )
    assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
    with sqlite3.connect(database_path) as connection:
        row = connection.execute(
            "SELECT installation_hash, credential_hash, signing_public_key_b64, revoked "
            "FROM installations"
        ).fetchone()
        revision = connection.execute(
            "SELECT version_num FROM alembic_version"
        ).fetchone()[0]
    assert row == ("a" * 64, "", "", 0)
    assert revision == "20260830_0003"
