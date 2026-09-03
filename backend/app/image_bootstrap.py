from __future__ import annotations

import hashlib
import ipaddress
import json
import logging
import os
import re
import struct
import tempfile
import threading
import time
import zlib
from contextlib import contextmanager
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from urllib.parse import urlsplit

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.serialization import load_pem_private_key
from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import Response
from PIL import Image, UnidentifiedImageError
from sqlalchemy.orm import Session

from .bootstrap import NoServersAvailable, build_bootstrap_payload
from .config import Settings, get_settings
from .crypto import get_signing_material
from .database import get_db
from .models import AppSettings


router = APIRouter(tags=["android-image-bootstrap"])

IMAGE_BOOTSTRAP_PATH = "/assets/tornado-config.png"
MAGIC = b"TCI1"
FORMAT_VERSION = 1
SIGNATURE_DOMAIN = b"TORNADO_IMAGE_CONFIG_V1\x00"
BUCKET_SECONDS = 300
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_CARRIER_DIMENSION = 2048
MAX_CARRIER_PIXELS = 2_000_000
MAX_ENCODED_PNG_BYTES = 2 * 1024 * 1024
AD_UNIT_ID = re.compile(r"ca-app-pub-\d{16}/\d{10}\Z")
KEY_ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,64}\Z")
SUPPORTED_IMAGE_PROTOCOLS = frozenset(
    {
        "vmess",
        "vless",
        "ss",
        "socks",
        "socks4",
        "socks5",
        "trojan",
        "wireguard",
        "hysteria2",
        "hy2",
        "v2rayn",
    }
)

_generation_lock = threading.Lock()
logger = logging.getLogger(__name__)


class ImageBootstrapError(RuntimeError):
    pass


class ImagePayloadTooLarge(ImageBootstrapError):
    pass


@dataclass(frozen=True)
class ParsedHiddenPayload:
    key_id: str
    payload: bytes
    signature: bytes


def canonical_payload_bytes(payload: dict) -> bytes:
    return json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def signature_input(key_id: str, payload: bytes) -> bytes:
    return SIGNATURE_DOMAIN + key_id.encode("utf-8") + b"\x00" + payload


def build_hidden_payload(
    payload: bytes,
    *,
    key_id: str,
    private_key: ec.EllipticCurvePrivateKey,
    max_payload_bytes: int,
) -> bytes:
    if len(payload) > max_payload_bytes:
        raise ImagePayloadTooLarge(
            f"JSON payload is {len(payload)} bytes; limit is {max_payload_bytes}"
        )
    if KEY_ID_PATTERN.fullmatch(key_id) is None:
        raise ImageBootstrapError(
            "Signing key id must match [A-Za-z0-9._-]{1,64}"
        )
    key_id_bytes = key_id.encode("ascii")
    if len(payload) > 0xFFFFFFFF:
        raise ImagePayloadTooLarge("JSON payload exceeds the binary format limit")
    signature = private_key.sign(
        signature_input(key_id, payload), ec.ECDSA(hashes.SHA256())
    )
    if not 64 <= len(signature) <= 80:
        raise ImageBootstrapError("ECDSA signature is unexpectedly large")
    body = b"".join(
        (
            MAGIC,
            bytes((FORMAT_VERSION, len(key_id_bytes))),
            key_id_bytes,
            struct.pack(">I", len(payload)),
            payload,
            struct.pack(">H", len(signature)),
            signature,
        )
    )
    return body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def parse_hidden_payload(
    hidden: bytes,
    *,
    public_key: ec.EllipticCurvePublicKey,
    max_payload_bytes: int,
) -> ParsedHiddenPayload:
    minimum_size = len(MAGIC) + 1 + 1 + 1 + 4 + 2 + 1 + 4
    if len(hidden) < minimum_size or not hidden.startswith(MAGIC):
        raise ImageBootstrapError("Hidden payload magic is invalid")
    offset = len(MAGIC)
    version = hidden[offset]
    offset += 1
    if version != FORMAT_VERSION:
        raise ImageBootstrapError("Hidden payload version is unsupported")
    key_id_length = hidden[offset]
    offset += 1
    if key_id_length == 0 or offset + key_id_length > len(hidden):
        raise ImageBootstrapError("Hidden payload key id is invalid")
    try:
        key_id = hidden[offset : offset + key_id_length].decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ImageBootstrapError("Hidden payload key id is not UTF-8") from exc
    offset += key_id_length
    if KEY_ID_PATTERN.fullmatch(key_id) is None:
        raise ImageBootstrapError("Hidden payload key id is invalid")
    if offset + 4 > len(hidden):
        raise ImageBootstrapError("Hidden payload length is missing")
    payload_length = struct.unpack_from(">I", hidden, offset)[0]
    offset += 4
    if payload_length > max_payload_bytes:
        raise ImagePayloadTooLarge("Declared JSON payload exceeds the configured limit")
    if offset + payload_length + 2 + 4 > len(hidden):
        raise ImageBootstrapError("Hidden JSON payload is truncated")
    payload = hidden[offset : offset + payload_length]
    offset += payload_length
    signature_length = struct.unpack_from(">H", hidden, offset)[0]
    offset += 2
    if not 64 <= signature_length <= 80:
        raise ImageBootstrapError("Hidden signature length is invalid")
    expected_size = offset + signature_length + 4
    if expected_size != len(hidden):
        raise ImageBootstrapError("Hidden payload has trailing or truncated bytes")
    signature = hidden[offset : offset + signature_length]
    expected_crc = struct.unpack_from(">I", hidden, offset + signature_length)[0]
    actual_crc = zlib.crc32(hidden[:-4]) & 0xFFFFFFFF
    if actual_crc != expected_crc:
        raise ImageBootstrapError("Hidden payload CRC32 does not match")
    try:
        public_key.verify(
            signature,
            signature_input(key_id, payload),
            ec.ECDSA(hashes.SHA256()),
        )
    except InvalidSignature as exc:
        raise ImageBootstrapError("Hidden payload signature is invalid") from exc
    return ParsedHiddenPayload(key_id=key_id, payload=payload, signature=signature)


def practical_capacity(width: int, height: int) -> int:
    if width <= 0 or height <= 0:
        return 0
    # The supplied Go tool reserves four bytes twice: once in MaxEncodeSize and
    # once in its preflight check. Match that practical limit exactly.
    return max(0, (width * height * 3) // 8 - 8)


def _validate_carrier_dimensions(width: int, height: int) -> None:
    if (
        width <= 0
        or height <= 0
        or width > MAX_CARRIER_DIMENSION
        or height > MAX_CARRIER_DIMENSION
        or width * height > MAX_CARRIER_PIXELS
    ):
        raise ImageBootstrapError("Carrier PNG dimensions are outside safe limits")


def _open_carrier(base_png: bytes) -> Image.Image:
    if len(base_png) > MAX_ENCODED_PNG_BYTES or not base_png.startswith(PNG_SIGNATURE):
        raise ImageBootstrapError("Carrier PNG file is outside safe limits")
    try:
        with Image.open(BytesIO(base_png)) as source:
            if source.format != "PNG":
                raise ImageBootstrapError("Carrier image must be PNG")
            _validate_carrier_dimensions(*source.size)
            image = source.convert("RGBA")
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
        raise ImageBootstrapError("Carrier PNG cannot be decoded") from exc
    return image


def encode_lsb_png(base_png: bytes, hidden: bytes) -> bytes:
    image = _open_carrier(base_png)
    width, height = image.size
    capacity = practical_capacity(width, height)
    if len(hidden) > capacity:
        raise ImagePayloadTooLarge(
            f"Hidden payload is {len(hidden)} bytes; carrier limit is {capacity}"
        )
    framed = struct.pack(">I", len(hidden)) + hidden
    pixels = image.load()
    bit_offset = 0
    total_bits = len(framed) * 8
    for x in range(width):
        for y in range(height):
            channels = list(pixels[x, y])
            for channel in range(3):
                if bit_offset >= total_bits:
                    break
                byte_value = framed[bit_offset // 8]
                bit = (byte_value >> (7 - (bit_offset % 8))) & 1
                channels[channel] = (channels[channel] & 0xFE) | bit
                bit_offset += 1
            pixels[x, y] = tuple(channels)
            if bit_offset >= total_bits:
                break
        if bit_offset >= total_bits:
            break
    output = BytesIO()
    image.save(output, format="PNG", compress_level=6, optimize=False)
    encoded = output.getvalue()
    if len(encoded) > MAX_ENCODED_PNG_BYTES:
        raise ImageBootstrapError("Encoded carrier exceeds the 2 MiB response limit")
    return encoded


def decode_lsb_png(encoded_png: bytes, *, max_hidden_bytes: int) -> bytes:
    image = _open_carrier(encoded_png)
    width, height = image.size
    physical_limit = (width * height * 3) // 8 - 4
    if physical_limit < 0:
        raise ImageBootstrapError("Encoded carrier is too small")
    pixels = image.load()
    result = bytearray()
    current = 0
    bits_in_current = 0
    required_bytes: int | None = None
    for x in range(width):
        for y in range(height):
            channels = pixels[x, y]
            for channel in range(3):
                current = (current << 1) | (channels[channel] & 1)
                bits_in_current += 1
                if bits_in_current != 8:
                    continue
                result.append(current)
                current = 0
                bits_in_current = 0
                if len(result) == 4:
                    declared_length = struct.unpack(">I", result)[0]
                    if declared_length > physical_limit or declared_length > max_hidden_bytes:
                        raise ImagePayloadTooLarge(
                            "Encoded carrier declares an unsafe hidden payload length"
                        )
                    required_bytes = 4 + declared_length
                if required_bytes is not None and len(result) == required_bytes:
                    return bytes(result[4:])
    raise ImageBootstrapError("Encoded carrier payload is truncated")


def _atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
        try:
            directory_descriptor = os.open(path.parent, os.O_RDONLY)
        except OSError:
            directory_descriptor = None
        if directory_descriptor is not None:
            try:
                os.fsync(directory_descriptor)
            finally:
                os.close(directory_descriptor)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def _cache_state_path(image_path: Path) -> Path:
    return image_path.with_suffix(image_path.suffix + ".json")


def validate_image_bootstrap_readiness(settings: Settings) -> None:
    if not settings.image_bootstrap_enabled:
        return
    base_png, base_sha256 = _read_base_png(settings)
    _ = base_png
    try:
        disk_key = load_pem_private_key(
            Path(settings.signing_private_key_path).read_bytes(), password=None
        )
    except (OSError, ValueError, TypeError) as exc:
        raise ImageBootstrapError("Signing key file is unavailable") from exc
    if not isinstance(disk_key, ec.EllipticCurvePrivateKey) or not isinstance(
        disk_key.curve, ec.SECP256R1
    ):
        raise ImageBootstrapError("Signing key must be ECDSA P-256")
    active_public = get_signing_material().private_key.public_key().public_bytes(
        Encoding.DER, PublicFormat.SubjectPublicKeyInfo
    )
    disk_public = disk_key.public_key().public_bytes(
        Encoding.DER, PublicFormat.SubjectPublicKeyInfo
    )
    if disk_public != active_public:
        raise ImageBootstrapError("Signing key file changed while the process is running")
    _cache_identity(settings, base_sha256)
    cache_path = Path(settings.image_bootstrap_cache_path)
    try:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, probe_name = tempfile.mkstemp(
            prefix=".tornado-config-ready.", dir=cache_path.parent
        )
        os.close(descriptor)
        os.unlink(probe_name)
    except OSError as exc:
        raise ImageBootstrapError("Carrier cache directory is not writable") from exc


@contextmanager
def _cache_file_lock(image_path: Path):
    import fcntl

    image_path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = image_path.with_suffix(image_path.suffix + ".lock")
    descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def _load_cached_image(
    image_path: Path,
    *,
    revision: int,
    bucket: int,
    expires_at: int,
    cache_identity: str,
) -> tuple[bytes, str, int] | None:
    state_path = _cache_state_path(image_path)
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
        if not isinstance(state, dict):
            return None
        if (
            state.get("revision") != revision
            or state.get("bucket") != bucket
            or state.get("expiresAtEpochSeconds") != expires_at
            or state.get("cacheIdentity") != cache_identity
            or state.get("formatVersion") != FORMAT_VERSION
        ):
            return None
        content = image_path.read_bytes()
        if (
            len(content) > MAX_ENCODED_PNG_BYTES
            or not content.startswith(PNG_SIGNATURE)
        ):
            return None
        digest = hashlib.sha256(content).hexdigest()
        if state.get("imageSha256") != digest:
            return None
        return content, f'"{digest}"', expires_at
    except (OSError, ValueError, TypeError, AttributeError):
        return None


def _store_cached_image(
    image_path: Path,
    content: bytes,
    *,
    payload_sha256: str,
    revision: int,
    bucket: int,
    expires_at: int,
    cache_identity: str,
) -> str:
    image_digest = hashlib.sha256(content).hexdigest()
    state = canonical_payload_bytes(
        {
            "bucket": bucket,
            "cacheIdentity": cache_identity,
            "expiresAtEpochSeconds": expires_at,
            "formatVersion": FORMAT_VERSION,
            "imageSha256": image_digest,
            "payloadSha256": payload_sha256,
            "revision": revision,
        }
    )
    _atomic_write(image_path, content)
    _atomic_write(_cache_state_path(image_path), state)
    return f'"{image_digest}"'


def _safe_https_url(value: object) -> str:
    if not isinstance(value, str) or not value or len(value) > 500:
        return ""
    if any(character.isspace() or ord(character) < 32 for character in value):
        return ""
    try:
        parsed = urlsplit(value)
        _ = parsed.port
    except ValueError:
        return ""
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or bool(parsed.fragment)
    ):
        return ""
    try:
        ipaddress.ip_address(parsed.hostname)
    except ValueError:
        labels = parsed.hostname.split(".")
        if (
            len(parsed.hostname) > 253
            or any(
                not label
                or len(label) > 63
                or re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?", label)
                is None
                for label in labels
            )
        ):
            return ""
    return value


def _valid_server_item(item: object) -> bool:
    if not isinstance(item, dict):
        return False
    config = item.get("config")
    scheme, separator, _ = (
        config.partition("://") if isinstance(config, str) else ("", "", "")
    )
    return (
        isinstance(config, str)
        and 1 <= len(config.encode("utf-8")) <= 16384
        and "\r" not in config
        and "\n" not in config
        and separator == "://"
        and scheme in SUPPORTED_IMAGE_PROTOCOLS
    )


def _normalize_image_payload(payload: dict) -> dict:
    payload["servers"] = [
        item for item in payload.get("servers", []) if _valid_server_item(item)
    ]
    if not payload["servers"]:
        raise NoServersAvailable
    payload["adServers"] = [
        item for item in payload.get("adServers", []) if _valid_server_item(item)
    ]

    app_payload = payload.get("app")
    if isinstance(app_payload, dict):
        for key in (
            "privacyPolicyUrl",
            "shareUrl",
            "supportUrl",
            "termsUrl",
            "websiteUrl",
        ):
            app_payload[key] = _safe_https_url(app_payload.get(key))
    update_payload = payload.get("updatePolicy")
    if isinstance(update_payload, dict):
        for key in ("directUrl", "playStoreUrl"):
            update_payload[key] = _safe_https_url(update_payload.get(key))
        if (
            update_payload.get("enabled") is True
            and update_payload.get("force") is True
            and not update_payload.get("directUrl")
            and not update_payload.get("playStoreUrl")
        ):
            update_payload["enabled"] = False
            update_payload["force"] = False
            if isinstance(app_payload, dict):
                app_payload["forceUpdateMinVersionCode"] = 0

    ads_payload = payload.get("ads")
    if isinstance(ads_payload, dict):
        for key in ("bannerUnitId", "interstitialUnitId", "rewardedUnitId"):
            unit_id = ads_payload.get(key)
            ads_payload[key] = (
                unit_id
                if isinstance(unit_id, str) and AD_UNIT_ID.fullmatch(unit_id)
                else ""
            )
        placements = ads_payload.get("placements")
        if isinstance(placements, dict):
            for placement in placements.values():
                if not isinstance(placement, dict):
                    continue
                unit_id = placement.get("unitId")
                valid_unit = isinstance(unit_id, str) and AD_UNIT_ID.fullmatch(unit_id)
                placement["unitId"] = unit_id if valid_unit else ""
                if placement.get("enabled") is True and not valid_unit:
                    placement["enabled"] = False
        splash = placements.get("splash") if isinstance(placements, dict) else None
        if (
            ads_payload.get("enabled") is not True
            or not isinstance(splash, dict)
            or splash.get("enabled") is not True
        ):
            payload["adServers"] = []
    return payload


def _read_base_png(settings: Settings) -> tuple[bytes, str]:
    try:
        content = Path(settings.image_bootstrap_base_path).read_bytes()
    except OSError as exc:
        raise ImageBootstrapError("Carrier base image is unavailable") from exc
    # Validate the operator-configurable carrier before it participates in the
    # cache identity. Full RGBA conversion only happens on cache misses.
    if len(content) > MAX_ENCODED_PNG_BYTES or not content.startswith(PNG_SIGNATURE):
        raise ImageBootstrapError("Carrier PNG file is outside safe limits")
    try:
        with Image.open(BytesIO(content)) as image:
            if image.format != "PNG":
                raise ImageBootstrapError("Carrier image must be PNG")
            _validate_carrier_dimensions(*image.size)
            if practical_capacity(*image.size) < 1024:
                raise ImageBootstrapError("Carrier PNG capacity is too small")
            image.verify()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
        raise ImageBootstrapError("Carrier PNG cannot be decoded") from exc
    return content, hashlib.sha256(content).hexdigest()


def _cache_identity(settings: Settings, base_sha256: str) -> str:
    public_der = get_signing_material().private_key.public_key().public_bytes(
        Encoding.DER,
        PublicFormat.SubjectPublicKeyInfo,
    )
    identity = canonical_payload_bytes(
        {
            "baseSha256": base_sha256,
            "encoder": "auyer-lsb-rgb-x-major-v1",
            "formatVersion": FORMAT_VERSION,
            "keyId": settings.signing_key_id,
            "publicKeySha256": hashlib.sha256(public_der).hexdigest(),
        }
    )
    return hashlib.sha256(identity).hexdigest()


def _generation_values(db: Session, now: int) -> tuple[int, int, int, int]:
    app_settings = db.get(AppSettings, 1) or AppSettings(id=1)
    revision = max(1, int(app_settings.config_revision))
    ttl = min(max(app_settings.payload_ttl_seconds, BUCKET_SECONDS), 86400)
    bucket = now // BUCKET_SECONDS
    issued_at = bucket * BUCKET_SECONDS
    return revision, bucket, issued_at, issued_at + ttl


def _build_payload(
    db: Session,
    settings: Settings,
    *,
    issued_at: int,
    expires_at: int,
) -> bytes:
    payload = _normalize_image_payload(build_bootstrap_payload(db, settings))
    payload.pop("security", None)
    payload["issuedAtEpochSeconds"] = issued_at
    payload["expiresAtEpochSeconds"] = expires_at
    payload["audiencePackageName"] = settings.expected_package_name
    return canonical_payload_bytes(payload)


def _generate_image(
    db: Session,
    settings: Settings,
    *,
    base_png: bytes,
    issued_at: int,
    expires_at: int,
) -> tuple[bytes, str, str]:
    payload = _build_payload(
        db,
        settings,
        issued_at=issued_at,
        expires_at=expires_at,
    )
    if len(payload) > settings.image_bootstrap_max_payload_bytes:
        raise ImagePayloadTooLarge(
            f"JSON payload is {len(payload)} bytes; configured limit is "
            f"{settings.image_bootstrap_max_payload_bytes}"
        )
    payload_sha256 = hashlib.sha256(payload).hexdigest()
    signing = get_signing_material()
    hidden = build_hidden_payload(
        payload,
        key_id=settings.signing_key_id,
        private_key=signing.private_key,
        max_payload_bytes=settings.image_bootstrap_max_payload_bytes,
    )
    image = encode_lsb_png(base_png, hidden)
    decoded = decode_lsb_png(image, max_hidden_bytes=39601)
    if decoded != hidden:
        raise ImageBootstrapError("Carrier self-check failed")
    return image, f'"{hashlib.sha256(image).hexdigest()}"', payload_sha256


def _start_consistent_read(db: Session) -> None:
    bind = db.get_bind()
    if bind.dialect.name == "postgresql":
        db.connection(execution_options={"isolation_level": "REPEATABLE READ"})


def _build_uncached(
    db: Session, settings: Settings, now: int
) -> tuple[bytes, str, int]:
    _start_consistent_read(db)
    revision, _, issued_at, expires_at = _generation_values(db, now)
    base_png, _ = _read_base_png(settings)
    image, etag, _ = _generate_image(
        db,
        settings,
        base_png=base_png,
        issued_at=issued_at,
        expires_at=expires_at,
    )
    # Keep the read alive so the revision used in the signed payload cannot be
    # optimized away as an apparently unused consistency check.
    if revision < 1:
        raise ImageBootstrapError("Configuration revision is invalid")
    return image, etag, expires_at


def _build_or_get_image(db: Session, settings: Settings) -> tuple[bytes, str, int]:
    image_path = Path(settings.image_bootstrap_cache_path)
    with _generation_lock:
        try:
            with _cache_file_lock(image_path):
                # Resolve time only after both locks are held. A request that waited across a
                # five-minute boundary must never overwrite a newer cache bucket with a stale
                # carrier computed from its arrival time.
                now = int(time.time())
                _start_consistent_read(db)
                revision, bucket, issued_at, expires_at = _generation_values(db, now)
                base_png, base_sha256 = _read_base_png(settings)
                cache_identity = _cache_identity(settings, base_sha256)
                cached = _load_cached_image(
                    image_path,
                    revision=revision,
                    bucket=bucket,
                    expires_at=expires_at,
                    cache_identity=cache_identity,
                )
                if cached is not None:
                    return cached
                image, etag, payload_sha256 = _generate_image(
                    db,
                    settings,
                    base_png=base_png,
                    issued_at=issued_at,
                    expires_at=expires_at,
                )
                try:
                    etag = _store_cached_image(
                        image_path,
                        image,
                        payload_sha256=payload_sha256,
                        revision=revision,
                        bucket=bucket,
                        expires_at=expires_at,
                        cache_identity=cache_identity,
                    )
                except OSError:
                    logger.warning("Image bootstrap cache write failed; serving memory copy")
                return image, etag, expires_at
        except OSError as exc:
            logger.warning(
                "Image bootstrap file lock is unavailable; serving uncached: %s",
                type(exc).__name__,
            )
            return _build_uncached(db, settings, int(time.time()))


def _etag_matches(value: str, expected: str) -> bool:
    expected_value = expected.removeprefix("W/")
    return any(
        item.strip() == "*"
        or item.strip().removeprefix("W/") == expected_value
        for item in value.split(",")
    )


@router.get(IMAGE_BOOTSTRAP_PATH)
def image_bootstrap(
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    if not settings.image_bootstrap_enabled:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND)
    try:
        content, etag, expires_at = _build_or_get_image(db, settings)
    except NoServersAvailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="در حال حاضر سرور فعالی وجود ندارد.",
        ) from exc
    except ImagePayloadTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="حجم تنظیمات از ظرفیت تصویر بیشتر است.",
        ) from exc
    except ImageBootstrapError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="تصویر تنظیمات در دسترس نیست.",
        ) from exc
    max_age = max(0, min(60, expires_at - int(time.time())))
    headers = {
        "Cache-Control": (
            f"public, max-age={max_age}, must-revalidate, no-transform"
        ),
        "ETag": etag,
        "X-Content-Type-Options": "nosniff",
    }
    if _etag_matches(request.headers.get("if-none-match", ""), etag):
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)
    return Response(content=content, media_type="image/png", headers=headers)
