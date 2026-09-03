#!/usr/bin/env python3
"""Verify the live direct and translate.goog image bootstrap responses."""

from __future__ import annotations

import json
import time
from urllib.parse import urlsplit

import httpx

from app.config import get_settings
from app.crypto import get_signing_material
from app.image_bootstrap import (
    BUCKET_SECONDS,
    IMAGE_BOOTSTRAP_PATH,
    decode_lsb_png,
    parse_hidden_payload,
)


def translated_base_url(public_base_url: str) -> str:
    parsed = urlsplit(public_base_url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SystemExit("PUBLIC_BASE_URL must be a valid HTTPS URL")
    translated_host = parsed.hostname.replace("-", "--").replace(".", "-")
    return f"https://{translated_host}.translate.goog"


def download(url: str) -> bytes:
    response = httpx.get(url, follow_redirects=True, timeout=30)
    response.raise_for_status()
    content_type = response.headers.get("content-type", "").split(";", 1)[0]
    if content_type != "image/png":
        raise SystemExit(f"Unexpected Content-Type from {url}: {content_type}")
    if len(response.content) > 2 * 1024 * 1024:
        raise SystemExit(f"Carrier from {url} is unexpectedly large")
    return response.content


def decode_and_verify(content: bytes) -> dict:
    settings = get_settings()
    hidden = decode_lsb_png(content, max_hidden_bytes=39601)
    parsed = parse_hidden_payload(
        hidden,
        public_key=get_signing_material().private_key.public_key(),
        max_payload_bytes=settings.image_bootstrap_max_payload_bytes,
    )
    if parsed.key_id != settings.signing_key_id:
        raise SystemExit("Carrier signing key id does not match this backend")
    payload = json.loads(parsed.payload)
    if payload.get("audiencePackageName") != settings.expected_package_name:
        raise SystemExit("Carrier package audience is invalid")
    if int(payload.get("expiresAtEpochSeconds", 0)) <= int(time.time()):
        raise SystemExit("Carrier payload is expired")
    return payload


def main() -> None:
    settings = get_settings()
    bucket = int(time.time()) // BUCKET_SECONDS
    primary = (
        settings.public_base_url.rstrip("/")
        + IMAGE_BOOTSTRAP_PATH
        + f"?b={bucket}"
    )
    fallback = (
        translated_base_url(settings.public_base_url)
        + IMAGE_BOOTSTRAP_PATH
        + "?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en"
        + f"&b={bucket}"
    )
    direct_image = download(primary)
    fallback_image = download(fallback)
    direct_payload = decode_and_verify(direct_image)
    fallback_payload = decode_and_verify(fallback_image)
    if direct_payload != fallback_payload:
        raise SystemExit("Direct and translate.goog payloads differ")
    print("IMAGE_BOOTSTRAP_OK")
    print(f"direct_bytes={len(direct_image)}")
    print(f"fallback_bytes={len(fallback_image)}")
    print(f"images_identical={direct_image == fallback_image}")
    print(f"config_revision={direct_payload['app']['configRevision']}")
    print(f"expires_at={direct_payload['expiresAtEpochSeconds']}")


if __name__ == "__main__":
    main()
