from __future__ import annotations

import base64
import hashlib
import json
import struct
import zlib
from io import BytesIO
from pathlib import Path

import pytest
from PIL import Image
from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient
from sqlalchemy import select

import app.image_bootstrap as image_module
import app.main as main_module
from app.admin import ADMOB_APP_ID, ADMOB_UNIT_ID, _admob_id, _url
from app.config import get_settings
from app.crypto import get_signing_material
from app.database import SessionLocal
from app.image_bootstrap import (
    IMAGE_BOOTSTRAP_PATH,
    ImageBootstrapError,
    ImagePayloadTooLarge,
    build_hidden_payload,
    decode_lsb_png,
    encode_lsb_png,
    parse_hidden_payload,
    practical_capacity,
)
from app.main import app
from app.models import (
    AdPlacement,
    AdSettings,
    AdVpnServer,
    AppSettings,
    UpdatePolicy,
    VpnServer,
)
from app.security import FieldCipher


BASE_PATH = Path(__file__).resolve().parents[1] / "app/static/tornado-config-base.png"
GOLDEN_PUBLIC_KEY_DER_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEkt5PQFpkkoXsOwE6zgA79hbZpTgF"
    "ywqZcnF2FSKjA5ZXoeT5BQqbeUboRGiVq9bNu0LeB1D2OOB7Kz09zytiJQ=="
)
GOLDEN_HIDDEN_B64 = (
    "VENJMQEVdG9ybmFkby1maXh0dXJlLWtleS0xAAAA6XsiYXVkaWVuY2VQYWNrYWdl"
    "TmFtZSI6ImNvbS52cG4udG9ybmFkb3ZwbiIsImV4cGlyZXNBdEVwb2NoU2Vjb25k"
    "cyI6MjAwMDAwMDAwMCwiaXNzdWVkQXRFcG9jaFNlY29uZHMiOjE5OTk5OTk3MDAs"
    "InNjaGVtYVZlcnNpb24iOjEsInNlcnZlcnMiOlt7ImNvbmZpZyI6InZsZXNzOi8v"
    "Zml4dHVyZUBleGFtcGxlLmNvbTo0NDMiLCJlbmFibGVkIjp0cnVlLCJpZCI6ImZp"
    "eHR1cmUtMSIsInByaW9yaXR5IjoxfV19AEcwRQIgc978lrfjXjTdo/WgGpNhTbTr"
    "BrlhfbKCHYdeYURrkaYCIQD8Ajf71ZQmCNThLk/3uW4cqu9F27W2bQCeSRcwIxMH"
    "ADL+kWo="
)


def _add_server(
    config: str = "vless://uuid@1.2.3.4:443#Image",
    *,
    public_id: str = "image-de-01",
) -> None:
    with SessionLocal() as db:
        db.add(
            VpnServer(
                public_id=public_id,
                config_encrypted=FieldCipher(get_settings()).encrypt(config),
                protocol="vless",
                tag="Image",
                host="1.2.3.4",
                port=443,
                resolved_ip="1.2.3.4",
                country_code="DE",
                country_name="Germany",
                priority=10,
                enabled=True,
            )
        )
        db.commit()


def _add_ad_server() -> None:
    with SessionLocal() as db:
        db.add(
            AdVpnServer(
                public_id="image-ad-01",
                config_encrypted=FieldCipher(get_settings()).encrypt(
                    "vless://ad-uuid@9.8.7.6:443#Ads"
                ),
                protocol="vless",
                tag="Ads",
                host="9.8.7.6",
                port=443,
                resolved_ip="9.8.7.6",
                country_code="DE",
                country_name="Germany",
                priority=10,
                enabled=True,
            )
        )
        db.commit()


def _independent_auyer_decode(encoded_png: bytes) -> bytes:
    """Small independent decoder for the supplied Go tool's exact bit order."""

    with Image.open(BytesIO(encoded_png)) as source:
        image = source.convert("RGBA")
    bits: list[int] = []
    pixels = image.load()
    for x in range(image.width):
        for y in range(image.height):
            red, green, blue, _ = pixels[x, y]
            bits.extend((red & 1, green & 1, blue & 1))

    def read_bytes(start_bit: int, length: int) -> bytes:
        output = bytearray()
        for byte_offset in range(length):
            value = 0
            for bit_offset in range(8):
                value = (
                    value << 1
                    | bits[start_bit + byte_offset * 8 + bit_offset]
                )
            output.append(value)
        return bytes(output)

    hidden_length = struct.unpack(">I", read_bytes(0, 4))[0]
    return read_bytes(32, hidden_length)


def _decode_response(response) -> tuple[dict, bytes]:
    hidden = _independent_auyer_decode(response.content)
    signing = get_signing_material()
    parsed = parse_hidden_payload(
        hidden,
        public_key=signing.private_key.public_key(),
        max_payload_bytes=36 * 1024,
    )
    return json.loads(parsed.payload), hidden


def test_codec_matches_auyer_layout_and_base_capacity():
    with Image.open(BASE_PATH) as base:
        assert base.size == (325, 325)
        assert practical_capacity(*base.size) == 39601
    payload = '{"hello":"سلام","revision":7}'.encode()
    signing = get_signing_material()
    hidden = build_hidden_payload(
        payload,
        key_id=get_settings().signing_key_id,
        private_key=signing.private_key,
        max_payload_bytes=36 * 1024,
    )
    image = encode_lsb_png(BASE_PATH.read_bytes(), hidden)
    assert image.startswith(b"\x89PNG\r\n\x1a\n")
    assert _independent_auyer_decode(image) == hidden
    assert decode_lsb_png(image, max_hidden_bytes=39601) == hidden
    parsed = parse_hidden_payload(
        hidden,
        public_key=signing.private_key.public_key(),
        max_payload_bytes=36 * 1024,
    )
    assert parsed.key_id == "tornado-signing-2026-01"
    assert parsed.payload == payload


def test_cross_language_tci1_golden_fixture_is_stable():
    hidden = base64.b64decode(GOLDEN_HIDDEN_B64, validate=True)
    assert len(hidden) == 341
    assert hashlib.sha256(hidden).hexdigest() == (
        "ae8c495cf06c4e74d660445b4274070dc4df4bc4b556e3832f0a3c2a0153b498"
    )
    public_key = serialization.load_der_public_key(
        base64.b64decode(GOLDEN_PUBLIC_KEY_DER_B64, validate=True)
    )
    parsed = parse_hidden_payload(
        hidden,
        public_key=public_key,
        max_payload_bytes=36 * 1024,
    )
    assert parsed.key_id == "tornado-fixture-key-1"
    assert hashlib.sha256(parsed.payload).hexdigest() == (
        "fe6e42544401e9adfa6edacfae7d1b83287f2563ad00cc36ac9fae0dee58ede6"
    )


def test_hidden_payload_rejects_json_over_36_kib_and_tampering():
    signing = get_signing_material()
    with pytest.raises(ImagePayloadTooLarge):
        build_hidden_payload(
            b"x" * (36 * 1024 + 1),
            key_id=get_settings().signing_key_id,
            private_key=signing.private_key,
            max_payload_bytes=36 * 1024,
        )
    for invalid_key_id in ("", "کلید", "space key", "a" * 65, "slash/key"):
        with pytest.raises(ImageBootstrapError, match="key id"):
            build_hidden_payload(
                b'{}',
                key_id=invalid_key_id,
                private_key=signing.private_key,
                max_payload_bytes=36 * 1024,
            )

    hidden = bytearray(
        build_hidden_payload(
            b'{"safe":true}',
            key_id=get_settings().signing_key_id,
            private_key=signing.private_key,
            max_payload_bytes=36 * 1024,
        )
    )
    key_length = hidden[5]
    payload_start = 6 + key_length + 4
    hidden[payload_start] ^= 1
    hidden[-4:] = struct.pack(">I", zlib.crc32(hidden[:-4]) & 0xFFFFFFFF)
    with pytest.raises(ImageBootstrapError, match="signature"):
        parse_hidden_payload(
            bytes(hidden),
            public_key=signing.private_key.public_key(),
            max_payload_bytes=36 * 1024,
        )


def test_image_endpoint_returns_signed_full_payload_with_cache_headers():
    _add_server()
    with TestClient(app) as client:
        response = client.get(IMAGE_BOOTSTRAP_PATH)
        assert response.status_code == 200
        assert response.headers["content-type"] == "image/png"
        assert response.headers["cache-control"] == (
            "public, max-age=60, must-revalidate, no-transform"
        )
        assert response.headers["x-content-type-options"] == "nosniff"
        assert response.headers["etag"].startswith('"')
        payload, _ = _decode_response(response)
        assert payload["audiencePackageName"] == "com.vpn.tornadovpn"
        assert payload["issuedAtEpochSeconds"] % 300 == 0
        assert payload["expiresAtEpochSeconds"] == (
            payload["issuedAtEpochSeconds"] + 900
        )
        assert payload["servers"][0]["config"].startswith("vless://")
        assert "security" not in payload
        not_modified = client.get(
            IMAGE_BOOTSTRAP_PATH,
            headers={"If-None-Match": f'W/{response.headers["etag"]}'},
        )
        assert not_modified.status_code == 304
        assert not not_modified.content
        assert not_modified.headers["etag"] == response.headers["etag"]


def test_config_revision_change_regenerates_image_and_corrupt_cache_is_repaired():
    _add_server()
    settings = get_settings()
    cache_path = Path(settings.image_bootstrap_cache_path)
    with TestClient(app) as client:
        first = client.get(IMAGE_BOOTSTRAP_PATH)
        assert first.status_code == 200
        cache_path.write_bytes(b"not-a-png")
        repaired = client.get(IMAGE_BOOTSTRAP_PATH)
        assert repaired.status_code == 200
        _decode_response(repaired)
        assert cache_path.read_bytes() == repaired.content

        with SessionLocal() as db:
            app_settings = db.get(AppSettings, 1)
            app_settings.config_revision += 1
            db.commit()
        changed = client.get(IMAGE_BOOTSTRAP_PATH)
        assert changed.status_code == 200
        changed_payload, _ = _decode_response(changed)
        assert changed_payload["app"]["configRevision"] == 2
        assert changed.headers["etag"] != repaired.headers["etag"]


def test_cache_hit_skips_full_payload_build_and_repairs_non_object_state(monkeypatch):
    _add_server()
    calls = 0
    original = image_module.build_bootstrap_payload

    def counted(*args, **kwargs):
        nonlocal calls
        calls += 1
        return original(*args, **kwargs)

    monkeypatch.setattr(image_module, "build_bootstrap_payload", counted)
    settings = get_settings()
    state_path = Path(settings.image_bootstrap_cache_path).with_suffix(".png.json")
    with TestClient(app) as client:
        first = client.get(IMAGE_BOOTSTRAP_PATH)
        assert first.status_code == 200
        assert calls == 1
        cached = client.get(
            IMAGE_BOOTSTRAP_PATH,
            headers={"If-None-Match": first.headers["etag"]},
        )
        assert cached.status_code == 304
        assert calls == 1

        state_path.write_text("[]", encoding="utf-8")
        repaired = client.get(IMAGE_BOOTSTRAP_PATH)
        assert repaired.status_code == 200
        assert calls == 2
        assert isinstance(json.loads(state_path.read_text(encoding="utf-8")), dict)


def test_cache_identity_binds_key_base_and_format():
    settings = get_settings()
    base_sha = hashlib.sha256(BASE_PATH.read_bytes()).hexdigest()
    first = image_module._cache_identity(settings, base_sha)
    assert first != image_module._cache_identity(
        settings.model_copy(update={"signing_key_id": "rotated-key"}), base_sha
    )
    assert first != image_module._cache_identity(settings, "0" * 64)


def test_cache_write_failure_serves_verified_memory_copy(monkeypatch):
    _add_server()

    def unavailable(*_args, **_kwargs):
        raise OSError("read-only cache")

    monkeypatch.setattr(image_module, "_store_cached_image", unavailable)
    with TestClient(app) as client:
        response = client.get(IMAGE_BOOTSTRAP_PATH)
    assert response.status_code == 200
    payload, _ = _decode_response(response)
    assert payload["servers"][0]["id"] == "image-de-01"


def test_cache_freshness_never_outlives_signed_expiry(monkeypatch):
    _add_server()
    with SessionLocal() as db:
        settings_row = db.get(AppSettings, 1)
        settings_row.payload_ttl_seconds = 300
        db.commit()
    monkeypatch.setattr(image_module.time, "time", lambda: 1199)
    with TestClient(app) as client:
        response = client.get(IMAGE_BOOTSTRAP_PATH)
    assert response.status_code == 200
    assert response.headers["cache-control"] == (
        "public, max-age=1, must-revalidate, no-transform"
    )
    payload, _ = _decode_response(response)
    assert payload["issuedAtEpochSeconds"] == 900
    assert payload["expiresAtEpochSeconds"] == 1200


def test_existing_invalid_urls_and_ad_ids_are_safely_normalized():
    _add_server()
    _add_ad_server()
    with SessionLocal() as db:
        app_row = db.get(AppSettings, 1)
        app_row.privacy_policy_url = "http://example.com/privacy"
        app_row.share_url = "https://user:pass@example.com/share"
        app_row.website_url = "https://example.com/path#fragment"
        app_row.support_url = "https://bad_host.example/support"
        ads = db.get(AdSettings, 1)
        ads.enabled = True
        ads.test_mode = True
        ads.legacy_banner_unit_id = "not-an-ad-unit"
        splash = db.scalar(
            select(AdPlacement).where(AdPlacement.key == "splash")
        )
        splash.enabled = True
        splash.unit_id = "broken"
        update = db.get(UpdatePolicy, 1)
        update.enabled = True
        update.force_update = True
        update.direct_url = "http://example.com/app.apk"
        update.play_store_url = "https://bad host/app"
        app_row.config_revision += 1
        db.commit()

    with TestClient(app) as client:
        response = client.get(IMAGE_BOOTSTRAP_PATH)
    assert response.status_code == 200
    payload, _ = _decode_response(response)
    assert payload["app"]["privacyPolicyUrl"] == ""
    assert payload["app"]["shareUrl"] == ""
    assert payload["app"]["websiteUrl"] == ""
    assert payload["app"]["supportUrl"] == ""
    assert payload["app"]["forceUpdateMinVersionCode"] == 0
    assert payload["updatePolicy"]["enabled"] is False
    assert payload["updatePolicy"]["force"] is False
    assert payload["updatePolicy"]["directUrl"] == ""
    assert payload["updatePolicy"]["playStoreUrl"] == ""
    assert payload["ads"]["bannerUnitId"] == ""
    assert payload["ads"]["placements"]["splash"]["enabled"] is False
    assert payload["ads"]["placements"]["splash"]["unitId"] == ""
    assert payload["adServers"] == []


def test_unsupported_server_protocols_are_filtered_and_empty_pool_is_503():
    _add_server("tuic://uuid:password@example.com:443", public_id="unsupported")
    with TestClient(app) as client:
        response = client.get(IMAGE_BOOTSTRAP_PATH)
    assert response.status_code == 503
    assert response.headers["cache-control"] == "no-store"

    _add_server("VLESS://uuid@1.2.3.4:443", public_id="uppercase")
    with TestClient(app) as client:
        uppercase = client.get(IMAGE_BOOTSTRAP_PATH)
    assert uppercase.status_code == 503


def test_admin_contract_validation_rejects_http_urls_and_malformed_admob_ids():
    assert _url("https://example.com/path?q=1", "URL") == (
        "https://example.com/path?q=1"
    )
    with pytest.raises(ValueError):
        _url("http://example.com", "URL")
    with pytest.raises(ValueError):
        _url("https://user:pass@example.com", "URL")
    with pytest.raises(ValueError):
        _url("https://example.com/has space", "URL")
    with pytest.raises(ValueError):
        _url("https://example.com/path#fragment", "URL")
    with pytest.raises(ValueError):
        _url("https://bad_host.example/path", "URL")
    assert _admob_id(
        "ca-app-pub-2787280142638189~1677521046", "App ID", ADMOB_APP_ID
    )
    assert _admob_id(
        "ca-app-pub-2787280142638189/5811724673", "Unit ID", ADMOB_UNIT_ID
    )
    with pytest.raises(ValueError):
        _admob_id("ca-app-pub-broken", "Unit ID", ADMOB_UNIT_ID)


def test_carrier_dimension_and_final_size_limits(monkeypatch):
    oversized = BytesIO()
    Image.new("RGBA", (2049, 1), (0, 0, 0, 255)).save(oversized, format="PNG")
    with pytest.raises(ImageBootstrapError, match="dimensions"):
        encode_lsb_png(oversized.getvalue(), b"x")

    monkeypatch.setattr(
        image_module,
        "_open_carrier",
        lambda _content: Image.new("RGBA", (32, 32), (1, 2, 3, 255)),
    )
    monkeypatch.setattr(image_module, "MAX_ENCODED_PNG_BYTES", 10)
    with pytest.raises(ImageBootstrapError, match="2 MiB"):
        encode_lsb_png(b"ignored", b"x")


def test_image_endpoint_is_503_without_servers_and_404_when_disabled():
    with TestClient(app) as client:
        missing = client.get(IMAGE_BOOTSTRAP_PATH)
        assert missing.status_code == 503
        assert missing.headers["cache-control"] == "no-store"

        disabled_settings = get_settings().model_copy(
            update={"image_bootstrap_enabled": False}
        )
        app.dependency_overrides[get_settings] = lambda: disabled_settings
        try:
            disabled = client.get(IMAGE_BOOTSTRAP_PATH)
        finally:
            app.dependency_overrides.pop(get_settings, None)
        assert disabled.status_code == 404
        assert disabled.headers["cache-control"] == "no-store"


def test_readiness_fails_for_missing_carrier_or_unusable_cache_parent(
    monkeypatch, tmp_path: Path
):
    missing = get_settings().model_copy(
        update={"image_bootstrap_base_path": tmp_path / "missing.png"}
    )
    monkeypatch.setattr(main_module, "settings", missing)
    with TestClient(app) as client:
        assert client.get("/healthz").status_code == 200
        assert client.get("/readyz").status_code == 503

    parent_file = tmp_path / "not-a-directory"
    parent_file.write_text("x", encoding="utf-8")
    invalid_cache = get_settings().model_copy(
        update={"image_bootstrap_cache_path": parent_file / "cache.png"}
    )
    monkeypatch.setattr(main_module, "settings", invalid_cache)
    with TestClient(app) as client:
        assert client.get("/readyz").status_code == 503


def test_public_info_advertises_image_channel():
    with TestClient(app) as client:
        response = client.get("/v1/public-info")
    assert response.status_code == 200
    body = response.json()
    assert body["bootstrapPath"] == IMAGE_BOOTSTRAP_PATH
    assert body["bootstrapTransport"] == "signed-tci1-lsb-rgb-png"
    assert body["imageBootstrapPath"] == IMAGE_BOOTSTRAP_PATH
    assert body["imageBootstrapFormat"] == "TCI1-LSB-RGB-PNG"
    assert "secureSessionPath" not in body
