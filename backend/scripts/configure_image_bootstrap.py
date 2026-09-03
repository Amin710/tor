#!/usr/bin/env python3
"""Atomically enable Tornado's PNG image bootstrap channel.

The rollout mode keeps the legacy JSON endpoint available for installed older
clients. Lockdown closes it after the image-channel client has been verified.
No secret value is printed or replaced.
"""

from __future__ import annotations

import argparse
import os
import shutil
import tempfile
from datetime import UTC, datetime
from pathlib import Path


COMMON = {
    "IMAGE_BOOTSTRAP_ENABLED": "true",
    "IMAGE_BOOTSTRAP_BASE_PATH": "./app/static/tornado-config-base.png",
    "IMAGE_BOOTSTRAP_CACHE_PATH": "./data/tornado-config.png",
    "IMAGE_BOOTSTRAP_MAX_PAYLOAD_BYTES": "36864",
    "SECURE_BOOTSTRAP_ENABLED": "false",
}


def desired_values(phase: str) -> dict[str, str]:
    values = dict(COMMON)
    values["LEGACY_BOOTSTRAP_ENABLED"] = (
        "false" if phase == "lockdown" else "true"
    )
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
        output.extend(("", "# Tornado image bootstrap"))
        output.extend(f"{key}={value}" for key, value in remaining.items())

    stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
    backup = path.with_name(f"{path.name}.image-bootstrap-backup-{stamp}")
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
    print(f"Image bootstrap configured; legacy bootstrap is {legacy}.")
    print(f"Backup: {backup}")


if __name__ == "__main__":
    main()
