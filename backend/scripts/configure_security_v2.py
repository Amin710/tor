#!/usr/bin/env python3
"""Atomically configure the two-phase Tornado secure-bootstrap rollout.

The helper changes only documented security flags, creates a timestamped backup,
and never prints or rewrites secret values.
"""

from __future__ import annotations

import argparse
import os
import shutil
import tempfile
from datetime import UTC, datetime
from pathlib import Path


COMMON = {
    "SECURE_BOOTSTRAP_ENABLED": "true",
    "SECURE_SESSION_TTL_SECONDS": "120",
    "DEVICE_ATTESTATION_TTL_SECONDS": "2592000",
    "DEVICE_ATTESTATION_GRACE_SECONDS": "259200",
    "SECURE_SESSION_RATE_LIMIT_PER_MINUTE": "12",
    "SECURE_SESSION_IP_RATE_LIMIT_PER_MINUTE": "600",
    "SECURE_BOOTSTRAP_RATE_LIMIT_PER_MINUTE": "30",
    "SECURE_BOOTSTRAP_IP_RATE_LIMIT_PER_MINUTE": "1200",
    "APP_CHECK_TOKEN_REPLAY_TTL_SECONDS": "3600",
    "FORWARDED_ALLOW_IPS": "127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16",
}


def desired_values(phase: str) -> dict[str, str]:
    values = dict(COMMON)
    values["LEGACY_BOOTSTRAP_ENABLED"] = "false" if phase == "lockdown" else "true"
    return values


def update_env(path: Path, values: dict[str, str]) -> Path:
    if not path.is_file():
        raise SystemExit(f"Environment file not found: {path}")
    original = path.read_text(encoding="utf-8").splitlines()
    remaining = dict(values)
    output: list[str] = []
    seen: set[str] = set()
    for line in original:
        stripped = line.lstrip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            output.append(line)
            continue
        key = stripped.split("=", 1)[0].strip()
        if key not in values:
            output.append(line)
            continue
        if key in seen:
            continue
        output.append(f"{key}={values[key]}")
        seen.add(key)
        remaining.pop(key, None)
    if remaining:
        output.extend(("", "# Tornado secure bootstrap v2"))
        output.extend(f"{key}={value}" for key, value in remaining.items())

    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    backup = path.with_name(f"{path.name}.security-v2-backup-{stamp}")
    shutil.copy2(path, backup)
    mode = path.stat().st_mode & 0o777
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", dir=path.parent, text=True
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(output).rstrip() + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary_name, mode)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return backup


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("rollout", "lockdown", "compat"))
    parser.add_argument("--env-file", default=".env")
    arguments = parser.parse_args()
    phase = "rollout" if arguments.phase == "compat" else arguments.phase
    backup = update_env(Path(arguments.env_file), desired_values(phase))
    legacy = "disabled" if phase == "lockdown" else "enabled"
    print(f"Security v2 configured; legacy bootstrap is {legacy}.")
    print(f"Backup: {backup}")


if __name__ == "__main__":
    main()
