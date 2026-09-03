from __future__ import annotations

import base64
import binascii
import ipaddress
import json
import re
import socket
from dataclasses import dataclass
from datetime import UTC, datetime
from urllib.parse import unquote, urlsplit

import httpx

from .config import Settings

SUPPORTED_PROTOCOLS = {
    "vmess",
    "vless",
    "trojan",
    "ss",
    "socks",
    "http",
    "https",
    "hysteria2",
    "hy2",
    "tuic",
}


@dataclass(frozen=True)
class ParsedConfig:
    protocol: str
    host: str
    port: int | None
    tag: str


@dataclass(frozen=True)
class ServerLocation:
    ip: str = ""
    country_code: str = ""
    country_name: str = ""
    resolved_at: datetime | None = None


def _decode_base64(value: str) -> bytes:
    value = value.strip()
    value += "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value)


def _valid_port(value: object) -> int | None:
    if value in (None, ""):
        return None
    port = int(value)
    if not 1 <= port <= 65535:
        raise ValueError("پورت باید بین ۱ تا ۶۵۵۳۵ باشد.")
    return port


def parse_config(config: str) -> ParsedConfig:
    value = config.strip()
    if not value or len(value) > 16384 or "\n" in value or "\r" in value:
        raise ValueError("لینک کانفیگ نامعتبر یا بیش از حد بزرگ است.")
    if "://" not in value:
        raise ValueError("لینک کانفیگ باید شامل پروتکل باشد.")
    protocol = value.split("://", 1)[0].lower()
    if protocol not in SUPPORTED_PROTOCOLS:
        raise ValueError(f"پروتکل {protocol} پشتیبانی نمی‌شود.")

    if protocol == "vmess":
        try:
            raw = _decode_base64(value.split("://", 1)[1])
            data = json.loads(raw.decode("utf-8"))
            host = str(data.get("add") or data.get("address") or "").strip()
            tag = str(data.get("ps") or data.get("remark") or "").strip()
            port = _valid_port(data.get("port"))
        except (
            ValueError,
            UnicodeDecodeError,
            json.JSONDecodeError,
            binascii.Error,
        ) as exc:
            raise ValueError("ساختار لینک VMess معتبر نیست.") from exc
    elif protocol == "ss":
        return _parse_shadowsocks(value)
    else:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").strip()
        try:
            port = _valid_port(parsed.port)
        except ValueError as exc:
            raise ValueError("پورت لینک معتبر نیست.") from exc
        tag = unquote(parsed.fragment or "").strip()

    if not host:
        raise ValueError("آدرس سرور از کانفیگ قابل استخراج نیست.")
    return ParsedConfig(protocol=protocol, host=host, port=port, tag=tag)


def _parse_shadowsocks(value: str) -> ParsedConfig:
    body, _, fragment = value.split("://", 1)[1].partition("#")
    body = body.split("?", 1)[0]
    tag = unquote(fragment).strip()
    host = ""
    port: int | None = None
    try:
        parsed = urlsplit(f"ss://{body}")
        if parsed.hostname and parsed.port:
            host, port = parsed.hostname, parsed.port
        else:
            decoded = _decode_base64(body).decode("utf-8")
            parsed = urlsplit(f"ss://{decoded}")
            host, port = parsed.hostname or "", parsed.port
    except (ValueError, UnicodeDecodeError, binascii.Error):
        # SIP002 may encode only the method/password section before @.
        try:
            encoded_user, endpoint = body.rsplit("@", 1)
            _decode_base64(encoded_user)
            parsed = urlsplit(f"ss://x@{endpoint}")
            host, port = parsed.hostname or "", parsed.port
        except (ValueError, UnicodeDecodeError, binascii.Error) as exc:
            raise ValueError("ساختار لینک Shadowsocks معتبر نیست.") from exc
    if not host:
        raise ValueError("آدرس سرور از کانفیگ Shadowsocks قابل استخراج نیست.")
    return ParsedConfig(protocol="ss", host=host, port=_valid_port(port), tag=tag)


def resolve_ip(host: str) -> str:
    try:
        ipaddress.ip_address(host)
        return host
    except ValueError:
        pass
    try:
        results = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except OSError:
        return ""
    addresses = list(dict.fromkeys(item[4][0] for item in results))
    for address in addresses:
        try:
            if isinstance(ipaddress.ip_address(address), ipaddress.IPv4Address):
                return address
        except ValueError:
            continue
    return addresses[0] if addresses else ""


def locate_server(host: str, settings: Settings) -> ServerLocation:
    ip = resolve_ip(host)
    if not ip:
        return ServerLocation()
    location = ServerLocation(ip=ip, resolved_at=datetime.now(UTC).replace(tzinfo=None))
    if not settings.geolocation_enabled:
        return location
    try:
        response = httpx.get(
            f"{settings.geolocation_base_url.rstrip('/')}/{ip}",
            timeout=settings.geolocation_timeout_seconds,
            follow_redirects=False,
        )
        response.raise_for_status()
        data = response.json()
        if data.get("success", True) is False:
            return location
        return ServerLocation(
            ip=ip,
            country_code=str(
                data.get("country_code") or data.get("countryCode") or ""
            ).upper()[:8],
            country_name=str(data.get("country") or "")[:100],
            resolved_at=datetime.now(UTC).replace(tzinfo=None),
        )
    except (httpx.HTTPError, ValueError, TypeError):
        return location


def slugify_public_id(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9_-]+", "-", value.strip()).strip("-").lower()
    return slug[:60] or "server"
