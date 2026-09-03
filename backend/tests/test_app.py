from __future__ import annotations

import re
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.config import get_settings
from app.database import SessionLocal
from app.main import app
from app.models import (
    AdPlacement,
    AdSettings,
    AdVpnServer,
    AppSettings,
    AuditLog,
    UpdatePolicy,
    VpnServer,
)
from app.security import FieldCipher


def request_body(version_code: int = 1000008) -> dict:
    return {
        "installationId": "7f7d8ff2-9d5c-42e4-a601-a43e2fa766ae",
        "packageName": "com.vpn.tornadovpn",
        "versionCode": version_code,
        "versionName": "1000008",
    }


def add_server():
    with SessionLocal() as db:
        db.add(
            VpnServer(
                public_id="de-01",
                config_encrypted=FieldCipher(get_settings()).encrypt(
                    "vless://uuid@1.2.3.4:443#DE"
                ),
                protocol="vless",
                tag="Germany",
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


def add_ad_server(
    public_id: str = "ad-de-01", priority: int = 10, enabled: bool = True
):
    config = "vless://ad-uuid@9.8.7.6:443#Ads"
    with SessionLocal() as db:
        db.add(
            AdVpnServer(
                public_id=public_id,
                config_encrypted=FieldCipher(get_settings()).encrypt(config),
                protocol="vless",
                tag="Ads Germany",
                host="9.8.7.6",
                port=443,
                resolved_ip="9.8.7.6",
                country_code="DE",
                country_name="Germany",
                priority=priority,
                enabled=enabled,
            )
        )
        db.commit()


def test_bootstrap_returns_plain_payload_and_records_request():
    add_server()
    body = request_body()
    with TestClient(app) as client:
        response = client.post("/v1/android/bootstrap", json=body)
        assert response.status_code == 200
        payload = response.json()
        assert payload["schemaVersion"] == 1
        assert payload["servers"][0]["config"].startswith("vless://")
        # Schema v1 is deliberately retained. Legacy clients ignore these new
        # optional members and keep consuming the original server list.
        assert payload["adServers"] == []
        assert payload["ads"]["placements"]["splash"]["enabled"] is False

    with SessionLocal() as db:
        from app.models import BootstrapEvent

        event = db.query(BootstrapEvent).order_by(BootstrapEvent.id.desc()).first()
        assert event.accepted is True
        assert event.reason == "accepted"


def test_bootstrap_returns_only_enabled_ad_servers_in_priority_order():
    add_server()
    add_ad_server("ad-second", priority=20)
    add_ad_server("ad-first", priority=5)
    add_ad_server("ad-disabled", priority=1, enabled=False)
    with SessionLocal() as db:
        ads = db.get(AdSettings, 1)
        ads.enabled = True
        splash = db.scalar(select(AdPlacement).where(AdPlacement.key == "splash"))
        splash.enabled = True
        splash.unit_id = "ca-app-pub-3940256099942544/9257395921"
        splash.timeout_ms = 15000
        db.commit()

    with TestClient(app) as client:
        response = client.post("/v1/android/bootstrap", json=request_body())

    assert response.status_code == 200
    payload = response.json()
    assert [item["id"] for item in payload["adServers"]] == [
        "ad-first",
        "ad-second",
    ]
    assert payload["adServers"][0]["config"].startswith("vless://ad-uuid@")
    assert set(payload["adServers"][0]) == {"id", "config", "priority", "enabled"}
    assert payload["ads"]["placements"]["splash"] == {
        "enabled": True,
        "format": "app_open",
        "unitId": "ca-app-pub-3940256099942544/9257395921",
        "everyNActions": 1,
        "cooldownSeconds": 0,
        "timeoutMs": 15000,
        "maxPerDay": 0,
    }
    # The expensive ad-only route must never leak into the normal VPN pool.
    assert [item["id"] for item in payload["servers"]] == ["de-01"]


def test_bootstrap_withholds_ad_routes_while_ads_or_splash_are_disabled():
    add_server()
    add_ad_server()

    with TestClient(app) as client:
        disabled_ads = client.post("/v1/android/bootstrap", json=request_body())

    assert disabled_ads.status_code == 200
    disabled_payload = disabled_ads.json()
    assert disabled_payload["adServers"] == []
    assert disabled_payload["ads"]["placements"]["splash"]["enabled"] is False
    assert disabled_payload["ads"]["placements"]["splash"]["unitId"] == ""

    with SessionLocal() as db:
        ads = db.get(AdSettings, 1)
        ads.enabled = True
        splash = db.scalar(select(AdPlacement).where(AdPlacement.key == "splash"))
        splash.enabled = False
        splash.unit_id = "ca-app-pub-3940256099942544/9257395921"
        db.commit()

    with TestClient(app) as client:
        disabled_splash = client.post("/v1/android/bootstrap", json=request_body())

    assert disabled_splash.status_code == 200
    assert disabled_splash.json()["adServers"] == []


def test_remote_test_mode_uses_demo_units_without_placeholder_ids():
    add_server()
    add_ad_server()
    with SessionLocal() as db:
        ads = db.get(AdSettings, 1)
        ads.enabled = True
        ads.test_mode = True
        splash = db.scalar(select(AdPlacement).where(AdPlacement.key == "splash"))
        splash.enabled = True
        splash.unit_id = ""
        db.commit()

    with TestClient(app) as client:
        response = client.post("/v1/android/bootstrap", json=request_body())

    assert response.status_code == 200
    payload = response.json()
    assert len(payload["adServers"]) == 1
    assert payload["ads"]["testMode"] is True
    assert payload["ads"]["placements"]["splash"]["enabled"] is True
    assert payload["ads"]["placements"]["splash"]["unitId"] == ""


def test_bootstrap_defensively_clamps_legacy_production_interstitial_frequency():
    add_server()
    with SessionLocal() as db:
        ads = db.get(AdSettings, 1)
        ads.enabled = True
        ads.test_mode = False
        ads.interstitial_every_connections = 1
        before = db.scalar(
            select(AdPlacement).where(AdPlacement.key == "before_connect")
        )
        before.enabled = True
        before.unit_id = "ca-app-pub-example/interstitial"
        before.every_n_actions = 1
        db.commit()

    with TestClient(app) as client:
        response = client.post("/v1/android/bootstrap", json=request_body())

    assert response.status_code == 200
    payload = response.json()
    assert payload["ads"]["interstitialEveryConnections"] == 2
    assert payload["ads"]["placements"]["beforeConnect"]["everyNActions"] == 2


def test_forced_update_blocks_outside_range():
    add_server()
    with SessionLocal() as db:
        policy = db.get(UpdatePolicy, 1)
        policy.enabled = True
        policy.force_update = True
        policy.min_version_code = 1000008
        policy.max_version_code = 1000010
        policy.direct_url = "https://example.com/app.apk"
        db.commit()
    with TestClient(app) as client:
        response = client.post(
            "/v1/android/bootstrap", json=request_body(version_code=1000007)
        )
    assert response.status_code == 426
    assert response.json()["code"] == "UPDATE_REQUIRED"


def test_admin_login_and_dashboard_render():
    with TestClient(app) as client:
        login_page = client.get("/admin/login")
        assert login_page.status_code == 200
        csrf = re.search(r'name="csrf_token" value="([^"]+)"', login_page.text).group(1)
        response = client.post(
            "/admin/login",
            data={
                "username": "admin",
                "password": "testing-password-123",
                "csrf_token": csrf,
            },
            follow_redirects=True,
        )
        assert response.status_code == 200
        assert "کاربران فعال واقعی" in response.text
        assert "Tornado VPN" in response.text
        csrf = re.search(r'name="csrf_token" value="([^"]+)"', response.text).group(1)
        created = client.post(
            "/admin/servers/new",
            data={
                "csrf_token": csrf,
                "config": "vless://uuid@1.2.3.4:443#Germany%20Test",
                "public_id": "",
                "tag": "",
                "priority": "10",
                "country_name": "Germany",
                "country_code": "DE",
                "enabled": "on",
            },
            follow_redirects=True,
        )
        assert created.status_code == 200
        assert "Germany Test" in created.text
        for path in (
            "/admin/ad-servers",
            "/admin/admob",
            "/admin/updates",
            "/admin/app-settings",
            "/admin/audit",
        ):
            page = client.get(path)
            assert page.status_code == 200
            assert "Tornado VPN" in page.text
            if path == "/admin/admob":
                assert "اسپلش با سرور تبلیغ" in page.text
                assert 'name="splash_unit_id"' in page.text


def test_admin_can_create_ad_server_and_config_stays_encrypted():
    with TestClient(app) as client:
        login_page = client.get("/admin/login")
        csrf = re.search(
            r'name="csrf_token" value="([^"]+)"', login_page.text
        ).group(1)
        dashboard = client.post(
            "/admin/login",
            data={
                "username": "admin",
                "password": "testing-password-123",
                "csrf_token": csrf,
            },
            follow_redirects=True,
        )
        csrf = re.search(
            r'name="csrf_token" value="([^"]+)"', dashboard.text
        ).group(1)
        raw_config = "vless://ad-secret@9.8.7.6:443#Splash%20Premium"
        created = client.post(
            "/admin/ad-servers/new",
            data={
                "csrf_token": csrf,
                "config": raw_config,
                "public_id": "ad-premium-01",
                "tag": "Splash Premium",
                "priority": "7",
                "country_name": "Germany",
                "country_code": "DE",
                "enabled": "on",
            },
            follow_redirects=True,
        )
        assert created.status_code == 200
        assert "Splash Premium" in created.text
        assert raw_config not in created.text
        duplicate = client.post(
            "/admin/ad-servers/new",
            data={
                "csrf_token": csrf,
                "config": raw_config,
                "public_id": "ad-premium-01",
                "tag": "Duplicate",
                "priority": "8",
                "country_name": "Germany",
                "country_code": "DE",
                "enabled": "on",
            },
        )
        assert duplicate.status_code == 422
        assert "شناسه عمومی تکراری است" in duplicate.text

    with SessionLocal() as db:
        server = db.scalar(
            select(AdVpnServer).where(AdVpnServer.public_id == "ad-premium-01")
        )
        assert server is not None
        assert raw_config not in server.config_encrypted
        assert FieldCipher(get_settings()).decrypt(server.config_encrypted) == raw_config
        assert db.get(AppSettings, 1).config_revision == 2
        audit_row = db.scalar(
            select(AuditLog).where(AuditLog.action == "ad_server.create")
        )
        assert audit_row is not None
        assert raw_config not in audit_row.details


def test_admin_can_save_splash_placement_and_warn_without_ad_server():
    with TestClient(app) as client:
        login_page = client.get("/admin/login")
        csrf = re.search(
            r'name="csrf_token" value="([^"]+)"', login_page.text
        ).group(1)
        client.post(
            "/admin/login",
            data={
                "username": "admin",
                "password": "testing-password-123",
                "csrf_token": csrf,
            },
        )
        admob_page = client.get("/admin/admob")
        csrf = re.search(
            r'name="csrf_token" value="([^"]+)"', admob_page.text
        ).group(1)
        form = {
            "csrf_token": csrf,
            "enabled": "on",
            "test_mode": "on",
            "request_timeout_ms": "8000",
            "load_timeout_ms": "12000",
            "interstitial_every_connections": "3",
            "splash_enabled": "on",
            "splash_unit_id": "",
        }
        for key in ("before_connect", "after_connect", "app_open", "splash"):
            form[f"{key}_every_n_actions"] = "1"
            form[f"{key}_cooldown_seconds"] = "0"
            form[f"{key}_timeout_ms"] = "12000"
            form[f"{key}_max_per_day"] = "0"
        saved = client.post("/admin/admob", data=form, follow_redirects=True)
        production_form = dict(form)
        production_form.pop("test_mode")
        production_form["before_connect_enabled"] = "on"
        production_form["before_connect_unit_id"] = (
            "ca-app-pub-3940256099942544/1033173712"
        )
        production_form["splash_unit_id"] = (
            "ca-app-pub-3940256099942544/9257395921"
        )
        production_form["interstitial_every_connections"] = "1"
        production_form["before_connect_every_n_actions"] = "1"
        rejected_frequency = client.post(
            "/admin/admob", data=production_form, follow_redirects=True
        )

    assert saved.status_code == 200
    assert "سرور تبلیغ فعالی ثبت نشده" in saved.text
    assert "حداقل هر ۲ اتصال" in rejected_frequency.text
    with SessionLocal() as db:
        splash = db.scalar(select(AdPlacement).where(AdPlacement.key == "splash"))
        assert splash.enabled is True
        assert splash.ad_format == "app_open"
        assert db.get(AdSettings, 1).test_mode is True


def test_public_info_matches_tornado_release():
    with TestClient(app) as client:
        response = client.get("/v1/public-info")
    assert response.status_code == 200
    body = response.json()
    assert body["packageName"] == "com.vpn.tornadovpn"
    assert body["baseUrl"] == "https://bartarindl.ir"
    assert body["signingKeyId"] == "tornado-signing-2026-01"
