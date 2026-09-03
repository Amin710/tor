from __future__ import annotations

import base64
import json

import pytest

from app.server_parser import parse_config


def test_parse_vmess_config():
    payload = (
        base64.urlsafe_b64encode(
            json.dumps(
                {"add": "de.example.com", "port": "443", "ps": "Germany Premium"}
            ).encode()
        )
        .decode()
        .rstrip("=")
    )
    parsed = parse_config(f"vmess://{payload}")
    assert parsed.protocol == "vmess"
    assert parsed.host == "de.example.com"
    assert parsed.port == 443
    assert parsed.tag == "Germany Premium"


def test_parse_vless_and_shadowsocks():
    vless = parse_config("vless://uuid@1.2.3.4:8443?security=tls#Netherlands%2001")
    assert (vless.host, vless.port, vless.tag) == ("1.2.3.4", 8443, "Netherlands 01")

    ss = parse_config("ss://aes-256-gcm:password@8.8.8.8:8388#Google")
    assert (ss.protocol, ss.host, ss.port, ss.tag) == ("ss", "8.8.8.8", 8388, "Google")


def test_rejects_unsupported_or_multiline_config():
    with pytest.raises(ValueError):
        parse_config("wireguard://example")
    with pytest.raises(ValueError):
        parse_config("vless://uuid@example.com:443#ok\nsecond")
