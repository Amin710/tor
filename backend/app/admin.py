from __future__ import annotations

import re
import secrets
import ipaddress
from datetime import timedelta
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from .config import Settings, get_settings
from .database import get_db
from .firebase_service import firebase_active_users
from .models import (
    AdminUser,
    AdPlacement,
    AdSettings,
    AdVpnServer,
    AppSettings,
    AuditLog,
    BootstrapEvent,
    Installation,
    UpdatePolicy,
    VpnServer,
    utcnow,
)
from .security import (
    FieldCipher,
    add_flash,
    audit,
    current_admin,
    get_or_create_csrf,
    pop_flashes,
    validate_csrf,
    verify_password,
)
from .server_parser import locate_server, parse_config, slugify_public_id

router = APIRouter(prefix="/admin", include_in_schema=False)
templates = Jinja2Templates(directory=Path(__file__).parent / "templates")
ADMOB_APP_ID = re.compile(r"ca-app-pub-\d{16}~\d{10}\Z")
ADMOB_UNIT_ID = re.compile(r"ca-app-pub-\d{16}/\d{10}\Z")


def country_flag(code: str) -> str:
    normalized = (code or "").strip().upper()
    if len(normalized) != 2 or not normalized.isalpha():
        return "◌"
    return "".join(chr(127397 + ord(letter)) for letter in normalized)


templates.env.filters["country_flag"] = country_flag


def render(
    request: Request, name: str, context: dict | None = None, status_code: int = 200
) -> HTMLResponse:
    data = {
        "request": request,
        "csrf_token": get_or_create_csrf(request),
        "flashes": pop_flashes(request),
        "path": request.url.path,
        **(context or {}),
    }
    return templates.TemplateResponse(
        name=name, request=request, context=data, status_code=status_code
    )


def _int(value: str, minimum: int, maximum: int, label: str) -> int:
    try:
        number = int(value)
    except ValueError as exc:
        raise ValueError(f"{label} باید عدد باشد.") from exc
    if not minimum <= number <= maximum:
        raise ValueError(f"{label} باید بین {minimum} و {maximum} باشد.")
    return number


def _url(value: str, label: str, required: bool = False) -> str:
    value = value.strip()
    if not value and not required:
        return ""
    try:
        parsed = urlsplit(value)
        _ = parsed.port
    except ValueError as exc:
        raise ValueError(f"{label} باید یک لینک معتبر HTTPS باشد.") from exc
    if (
        len(value) > 500
        or any(character.isspace() or ord(character) < 32 for character in value)
        or parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or bool(parsed.fragment)
    ):
        raise ValueError(f"{label} باید یک لینک معتبر HTTPS باشد.")
    assert parsed.hostname is not None
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
            raise ValueError(f"{label} باید یک لینک معتبر HTTPS باشد.")
    return value


def _admob_id(value: str, label: str, pattern: re.Pattern[str]) -> str:
    value = value.strip()
    if value and pattern.fullmatch(value) is None:
        raise ValueError(f"{label} معتبر نیست.")
    return value


def _bump_revision(db: Session) -> None:
    app = db.get(AppSettings, 1)
    if app:
        app.config_revision += 1


@router.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    if request.session.get("admin_id"):
        return RedirectResponse("/admin", status_code=303)
    return render(request, "login.html", {"title": "ورود به پنل"})


@router.post("/login")
def login(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
):
    validate_csrf(request, csrf_token)
    admin = db.scalar(select(AdminUser).where(AdminUser.username == username.strip()))
    if (
        not admin
        or not admin.is_active
        or not verify_password(password, admin.password_hash)
    ):
        return render(
            request,
            "login.html",
            {
                "title": "ورود به پنل",
                "error": "نام کاربری یا رمز عبور اشتباه است.",
                "username": username,
            },
            status_code=401,
        )
    request.session.clear()
    request.session["admin_id"] = admin.id
    request.session["csrf_token"] = secrets.token_urlsafe(32)
    admin.last_login_at = utcnow()
    audit(db, request, admin, "login")
    db.commit()
    return RedirectResponse("/admin", status_code=303)


@router.post("/logout")
def logout(
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
):
    validate_csrf(request, csrf_token)
    admin_id = request.session.get("admin_id")
    admin = db.get(AdminUser, admin_id) if admin_id else None
    if admin:
        audit(db, request, admin, "logout")
        db.commit()
    request.session.clear()
    return RedirectResponse("/admin/login", status_code=303)


@router.get("", response_class=HTMLResponse)
def dashboard(
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    now = utcnow()
    active_servers = (
        db.scalar(select(func.count(VpnServer.id)).where(VpnServer.enabled.is_(True)))
        or 0
    )
    total_servers = db.scalar(select(func.count(VpnServer.id))) or 0
    active_ad_servers = (
        db.scalar(
            select(func.count(AdVpnServer.id)).where(AdVpnServer.enabled.is_(True))
        )
        or 0
    )
    total_ad_servers = db.scalar(select(func.count(AdVpnServer.id))) or 0
    active_1d = (
        db.scalar(
            select(func.count(Installation.id)).where(
                Installation.last_seen_at >= now - timedelta(days=1)
            )
        )
        or 0
    )
    active_7d = (
        db.scalar(
            select(func.count(Installation.id)).where(
                Installation.last_seen_at >= now - timedelta(days=7)
            )
        )
        or 0
    )
    active_30d = (
        db.scalar(
            select(func.count(Installation.id)).where(
                Installation.last_seen_at >= now - timedelta(days=30)
            )
        )
        or 0
    )
    accepted_24h = (
        db.scalar(
            select(func.count(BootstrapEvent.id)).where(
                BootstrapEvent.created_at >= now - timedelta(days=1),
                BootstrapEvent.accepted.is_(True),
            )
        )
        or 0
    )
    rejected_24h = (
        db.scalar(
            select(func.count(BootstrapEvent.id)).where(
                BootstrapEvent.created_at >= now - timedelta(days=1),
                BootstrapEvent.accepted.is_(False),
            )
        )
        or 0
    )
    versions = db.execute(
        select(Installation.version_code, func.count(Installation.id))
        .group_by(Installation.version_code)
        .order_by(desc(func.count(Installation.id)))
        .limit(6)
    ).all()
    ads = db.get(AdSettings, 1)
    placements = db.scalars(select(AdPlacement).order_by(AdPlacement.id)).all()
    update = db.get(UpdatePolicy, 1)
    app = db.get(AppSettings, 1)
    recent_audits = db.scalars(
        select(AuditLog).order_by(desc(AuditLog.created_at)).limit(8)
    ).all()
    firebase_stats = firebase_active_users(settings)
    return render(
        request,
        "dashboard.html",
        {
            "title": "داشبورد",
            "admin": admin,
            "active_servers": active_servers,
            "total_servers": total_servers,
            "active_ad_servers": active_ad_servers,
            "total_ad_servers": total_ad_servers,
            "api_active": (active_1d, active_7d, active_30d),
            "accepted_24h": accepted_24h,
            "rejected_24h": rejected_24h,
            "versions": versions,
            "ads": ads,
            "placements": placements,
            "update": update,
            "app": app,
            "recent_audits": recent_audits,
            "firebase": firebase_stats,
            "dev_attestation": settings.allow_insecure_dev_attestation,
            "production": settings.is_production,
        },
    )


@router.get("/servers", response_class=HTMLResponse)
def servers(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    rows = db.scalars(
        select(VpnServer).order_by(VpnServer.priority, VpnServer.id)
    ).all()
    return render(
        request, "servers.html", {"title": "سرورها", "admin": admin, "servers": rows}
    )


@router.get("/servers/new", response_class=HTMLResponse)
def server_new_page(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    return render(
        request,
        "server_form.html",
        {"title": "افزودن سرور", "admin": admin, "server": None},
    )


@router.post("/servers/new")
def server_new(
    request: Request,
    config: str = Form(...),
    public_id: str = Form(""),
    tag: str = Form(""),
    priority: str = Form("100"),
    country_name: str = Form(""),
    country_code: str = Form(""),
    enabled: str | None = Form(None),
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    try:
        parsed = parse_config(config)
        location = locate_server(parsed.host, settings)
        base_id = slugify_public_id(
            public_id or tag or parsed.tag or f"{parsed.protocol}-{parsed.host}"
        )
        candidate = base_id
        suffix = 2
        while db.scalar(select(VpnServer.id).where(VpnServer.public_id == candidate)):
            candidate = f"{base_id[:55]}-{suffix}"
            suffix += 1
        server = VpnServer(
            public_id=candidate,
            config_encrypted=FieldCipher(settings).encrypt(config.strip()),
            protocol=parsed.protocol,
            tag=(tag.strip() or parsed.tag or candidate)[:160],
            host=parsed.host[:255],
            port=parsed.port,
            resolved_ip=location.ip,
            country_code=(country_code.strip().upper() or location.country_code)[:8],
            country_name=(country_name.strip() or location.country_name)[:100],
            priority=_int(priority, 0, 100000, "اولویت"),
            enabled=enabled is not None,
            resolved_at=location.resolved_at,
        )
        db.add(server)
        db.flush()
        _bump_revision(db)
        audit(
            db,
            request,
            admin,
            "server.create",
            "server",
            server.public_id,
            f"{server.protocol} {server.host}",
        )
        db.commit()
        add_flash(request, "سرور با موفقیت اضافه شد.")
        return RedirectResponse("/admin/servers", status_code=303)
    except ValueError as exc:
        return render(
            request,
            "server_form.html",
            {
                "title": "افزودن سرور",
                "admin": admin,
                "server": None,
                "error": str(exc),
                "form": {
                    "public_id": public_id,
                    "tag": tag,
                    "priority": priority,
                    "country_name": country_name,
                    "country_code": country_code,
                },
            },
            status_code=422,
        )


@router.get("/servers/{server_id}/edit", response_class=HTMLResponse)
def server_edit_page(server_id: int, request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    server = db.get(VpnServer, server_id)
    if not server:
        raise HTTPException(404)
    return render(
        request,
        "server_form.html",
        {"title": "ویرایش سرور", "admin": admin, "server": server},
    )


@router.post("/servers/{server_id}/edit")
def server_edit(
    server_id: int,
    request: Request,
    config: str = Form(""),
    public_id: str = Form(...),
    tag: str = Form(""),
    priority: str = Form("100"),
    country_name: str = Form(""),
    country_code: str = Form(""),
    enabled: str | None = Form(None),
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(VpnServer, server_id)
    if not server:
        raise HTTPException(404)
    try:
        new_public_id = slugify_public_id(public_id)
        duplicate = db.scalar(
            select(VpnServer.id).where(
                VpnServer.public_id == new_public_id, VpnServer.id != server.id
            )
        )
        if duplicate:
            raise ValueError("شناسه عمومی تکراری است.")
        if config.strip():
            parsed = parse_config(config)
            location = locate_server(parsed.host, settings)
            server.config_encrypted = FieldCipher(settings).encrypt(config.strip())
            server.protocol = parsed.protocol
            server.host = parsed.host[:255]
            server.port = parsed.port
            server.resolved_ip = location.ip
            server.resolved_at = location.resolved_at
            if not tag.strip():
                tag = parsed.tag
            if not country_name.strip():
                country_name = location.country_name
            if not country_code.strip():
                country_code = location.country_code
        server.public_id = new_public_id
        server.tag = (tag.strip() or server.tag or new_public_id)[:160]
        server.priority = _int(priority, 0, 100000, "اولویت")
        server.country_name = country_name.strip()[:100]
        server.country_code = country_code.strip().upper()[:8]
        server.enabled = enabled is not None
        _bump_revision(db)
        audit(
            db,
            request,
            admin,
            "server.update",
            "server",
            server.public_id,
            f"{server.protocol} {server.host}",
        )
        db.commit()
        add_flash(request, "تغییرات سرور ذخیره شد.")
        return RedirectResponse("/admin/servers", status_code=303)
    except ValueError as exc:
        return render(
            request,
            "server_form.html",
            {
                "title": "ویرایش سرور",
                "admin": admin,
                "server": server,
                "error": str(exc),
            },
            status_code=422,
        )


@router.post("/servers/{server_id}/refresh")
def server_refresh(
    server_id: int,
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(VpnServer, server_id)
    if not server:
        raise HTTPException(404)
    location = locate_server(server.host, settings)
    server.resolved_ip = location.ip
    server.country_code = location.country_code or server.country_code
    server.country_name = location.country_name or server.country_name
    server.resolved_at = location.resolved_at
    audit(
        db,
        request,
        admin,
        "server.resolve",
        "server",
        server.public_id,
        server.resolved_ip,
    )
    db.commit()
    add_flash(request, "IP و موقعیت سرور دوباره بررسی شد.")
    return RedirectResponse("/admin/servers", status_code=303)


@router.post("/servers/{server_id}/toggle")
def server_toggle(
    server_id: int,
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(VpnServer, server_id)
    if not server:
        raise HTTPException(404)
    server.enabled = not server.enabled
    _bump_revision(db)
    audit(
        db,
        request,
        admin,
        "server.toggle",
        "server",
        server.public_id,
        "enabled" if server.enabled else "disabled",
    )
    db.commit()
    add_flash(request, "وضعیت سرور تغییر کرد.")
    return RedirectResponse("/admin/servers", status_code=303)


@router.post("/servers/{server_id}/delete")
def server_delete(
    server_id: int,
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(VpnServer, server_id)
    if not server:
        raise HTTPException(404)
    public_id = server.public_id
    db.delete(server)
    _bump_revision(db)
    audit(db, request, admin, "server.delete", "server", public_id)
    db.commit()
    add_flash(request, "سرور حذف شد.", "warning")
    return RedirectResponse("/admin/servers", status_code=303)


@router.get("/ad-servers", response_class=HTMLResponse)
def ad_servers(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    rows = db.scalars(
        select(AdVpnServer).order_by(AdVpnServer.priority, AdVpnServer.id)
    ).all()
    return render(
        request,
        "ad_servers.html",
        {"title": "سرورهای تبلیغات", "admin": admin, "servers": rows},
    )


@router.get("/ad-servers/new", response_class=HTMLResponse)
def ad_server_new_page(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    return render(
        request,
        "ad_server_form.html",
        {"title": "افزودن سرور تبلیغ", "admin": admin, "server": None},
    )


@router.post("/ad-servers/new")
def ad_server_new(
    request: Request,
    config: str = Form(...),
    public_id: str = Form(""),
    tag: str = Form(""),
    priority: str = Form("100"),
    country_name: str = Form(""),
    country_code: str = Form(""),
    enabled: str | None = Form(None),
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    try:
        total = db.scalar(select(func.count(AdVpnServer.id))) or 0
        if total >= 50:
            raise ValueError("حداکثر ۵۰ سرور تبلیغ قابل ثبت است.")
        requested_id = slugify_public_id(public_id) if public_id.strip() else ""
        if requested_id and db.scalar(
            select(AdVpnServer.id).where(AdVpnServer.public_id == requested_id)
        ):
            raise ValueError("شناسه عمومی تکراری است.")
        parsed = parse_config(config)
        location = locate_server(parsed.host, settings)
        base_id = slugify_public_id(
            requested_id or tag or parsed.tag or f"ad-{parsed.protocol}-{parsed.host}"
        )
        candidate = base_id
        suffix = 2
        while db.scalar(
            select(AdVpnServer.id).where(AdVpnServer.public_id == candidate)
        ):
            candidate = f"{base_id[:55]}-{suffix}"
            suffix += 1
        server = AdVpnServer(
            public_id=candidate,
            config_encrypted=FieldCipher(settings).encrypt(config.strip()),
            protocol=parsed.protocol,
            tag=(tag.strip() or parsed.tag or candidate)[:160],
            host=parsed.host[:255],
            port=parsed.port,
            resolved_ip=location.ip,
            country_code=(country_code.strip().upper() or location.country_code)[:8],
            country_name=(country_name.strip() or location.country_name)[:100],
            priority=_int(priority, 0, 100000, "اولویت"),
            enabled=enabled is not None,
            resolved_at=location.resolved_at,
        )
        db.add(server)
        db.flush()
        _bump_revision(db)
        audit(
            db,
            request,
            admin,
            "ad_server.create",
            "ad_server",
            server.public_id,
            f"{server.protocol} {server.host}",
        )
        db.commit()
        add_flash(request, "سرور تبلیغ با موفقیت اضافه شد.")
        return RedirectResponse("/admin/ad-servers", status_code=303)
    except ValueError as exc:
        db.rollback()
        return render(
            request,
            "ad_server_form.html",
            {
                "title": "افزودن سرور تبلیغ",
                "admin": admin,
                "server": None,
                "error": str(exc),
                "form": {
                    "public_id": public_id,
                    "tag": tag,
                    "priority": priority,
                    "country_name": country_name,
                    "country_code": country_code,
                    "enabled": enabled is not None,
                },
            },
            status_code=422,
        )


@router.get("/ad-servers/{server_id}/edit", response_class=HTMLResponse)
def ad_server_edit_page(
    server_id: int, request: Request, db: Session = Depends(get_db)
):
    admin = current_admin(request, db)
    server = db.get(AdVpnServer, server_id)
    if not server:
        raise HTTPException(404)
    return render(
        request,
        "ad_server_form.html",
        {"title": "ویرایش سرور تبلیغ", "admin": admin, "server": server},
    )


@router.post("/ad-servers/{server_id}/edit")
def ad_server_edit(
    server_id: int,
    request: Request,
    config: str = Form(""),
    public_id: str = Form(...),
    tag: str = Form(""),
    priority: str = Form("100"),
    country_name: str = Form(""),
    country_code: str = Form(""),
    enabled: str | None = Form(None),
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(AdVpnServer, server_id)
    if not server:
        raise HTTPException(404)
    try:
        new_public_id = slugify_public_id(public_id)
        duplicate = db.scalar(
            select(AdVpnServer.id).where(
                AdVpnServer.public_id == new_public_id,
                AdVpnServer.id != server.id,
            )
        )
        if duplicate:
            raise ValueError("شناسه عمومی تکراری است.")
        if config.strip():
            parsed = parse_config(config)
            location = locate_server(parsed.host, settings)
            server.config_encrypted = FieldCipher(settings).encrypt(config.strip())
            server.protocol = parsed.protocol
            server.host = parsed.host[:255]
            server.port = parsed.port
            server.resolved_ip = location.ip
            server.resolved_at = location.resolved_at
            if not tag.strip():
                tag = parsed.tag
            if not country_name.strip():
                country_name = location.country_name
            if not country_code.strip():
                country_code = location.country_code
        server.public_id = new_public_id
        server.tag = (tag.strip() or server.tag or new_public_id)[:160]
        server.priority = _int(priority, 0, 100000, "اولویت")
        server.country_name = country_name.strip()[:100]
        server.country_code = country_code.strip().upper()[:8]
        server.enabled = enabled is not None
        _bump_revision(db)
        audit(
            db,
            request,
            admin,
            "ad_server.update",
            "ad_server",
            server.public_id,
            f"{server.protocol} {server.host}",
        )
        db.commit()
        add_flash(request, "تغییرات سرور تبلیغ ذخیره شد.")
        return RedirectResponse("/admin/ad-servers", status_code=303)
    except ValueError as exc:
        db.rollback()
        return render(
            request,
            "ad_server_form.html",
            {
                "title": "ویرایش سرور تبلیغ",
                "admin": admin,
                "server": server,
                "error": str(exc),
            },
            status_code=422,
        )


@router.post("/ad-servers/{server_id}/refresh")
def ad_server_refresh(
    server_id: int,
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_settings),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(AdVpnServer, server_id)
    if not server:
        raise HTTPException(404)
    location = locate_server(server.host, settings)
    server.resolved_ip = location.ip
    server.country_code = location.country_code or server.country_code
    server.country_name = location.country_name or server.country_name
    server.resolved_at = location.resolved_at
    audit(
        db,
        request,
        admin,
        "ad_server.resolve",
        "ad_server",
        server.public_id,
        server.resolved_ip,
    )
    db.commit()
    add_flash(request, "IP و موقعیت سرور تبلیغ دوباره بررسی شد.")
    return RedirectResponse("/admin/ad-servers", status_code=303)


@router.post("/ad-servers/{server_id}/toggle")
def ad_server_toggle(
    server_id: int,
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(AdVpnServer, server_id)
    if not server:
        raise HTTPException(404)
    server.enabled = not server.enabled
    _bump_revision(db)
    audit(
        db,
        request,
        admin,
        "ad_server.toggle",
        "ad_server",
        server.public_id,
        "enabled" if server.enabled else "disabled",
    )
    db.commit()
    add_flash(request, "وضعیت سرور تبلیغ تغییر کرد.")
    return RedirectResponse("/admin/ad-servers", status_code=303)


@router.post("/ad-servers/{server_id}/delete")
def ad_server_delete(
    server_id: int,
    request: Request,
    csrf_token: str = Form(...),
    db: Session = Depends(get_db),
):
    admin = current_admin(request, db)
    validate_csrf(request, csrf_token)
    server = db.get(AdVpnServer, server_id)
    if not server:
        raise HTTPException(404)
    public_id = server.public_id
    db.delete(server)
    _bump_revision(db)
    audit(db, request, admin, "ad_server.delete", "ad_server", public_id)
    db.commit()
    add_flash(request, "سرور تبلیغ حذف شد.", "warning")
    return RedirectResponse("/admin/ad-servers", status_code=303)


@router.get("/admob", response_class=HTMLResponse)
def admob_page(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    ads = db.get(AdSettings, 1)
    placements = {p.key: p for p in db.scalars(select(AdPlacement)).all()}
    return render(
        request,
        "admob.html",
        {
            "title": "تنظیمات AdMob",
            "admin": admin,
            "ads": ads,
            "placements": placements,
        },
    )


@router.post("/admob")
async def admob_save(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    form = await request.form()
    validate_csrf(request, str(form.get("csrf_token", "")))
    ads = db.get(AdSettings, 1)
    assert ads is not None
    try:
        ads.enabled = "enabled" in form
        ads.admob_app_id = _admob_id(
            str(form.get("admob_app_id", "")), "AdMob App ID", ADMOB_APP_ID
        )
        ads.legacy_banner_unit_id = _admob_id(
            str(form.get("legacy_banner_unit_id", "")),
            "Banner Unit ID",
            ADMOB_UNIT_ID,
        )
        ads.request_timeout_ms = _int(
            str(form.get("request_timeout_ms", "8000")), 1000, 60000, "تایم‌اوت درخواست"
        )
        ads.load_timeout_ms = _int(
            str(form.get("load_timeout_ms", "12000")), 1000, 60000, "تایم‌اوت بارگذاری"
        )
        ads.interstitial_every_connections = _int(
            str(form.get("interstitial_every_connections", "3")), 1, 100, "تناوب تبلیغ"
        )
        ads.ump_required = "ump_required" in form
        ads.test_mode = "test_mode" in form
        for key, ad_format in (
            ("before_connect", "interstitial"),
            ("after_connect", "interstitial"),
            ("app_open", "app_open"),
            ("splash", "app_open"),
        ):
            placement = db.scalar(select(AdPlacement).where(AdPlacement.key == key))
            if placement is None:
                placement = AdPlacement(key=key, ad_format=ad_format)
                db.add(placement)
            placement.enabled = f"{key}_enabled" in form
            placement.ad_format = ad_format
            placement.unit_id = _admob_id(
                str(form.get(f"{key}_unit_id", "")),
                "Ad Unit ID",
                ADMOB_UNIT_ID,
            )
            placement.every_n_actions = _int(
                str(form.get(f"{key}_every_n_actions", "1")), 1, 100, "تناوب نمایش"
            )
            placement.cooldown_seconds = _int(
                str(form.get(f"{key}_cooldown_seconds", "60")), 0, 86400, "فاصله نمایش"
            )
            placement.timeout_ms = _int(
                str(form.get(f"{key}_timeout_ms", "12000")),
                1000,
                60000,
                "تایم‌اوت جایگاه",
            )
            placement.max_per_day = _int(
                str(form.get(f"{key}_max_per_day", "0")), 0, 1000, "سقف روزانه"
            )
        db.flush()
        if ads.enabled and not ads.test_mode:
            placement_labels = {
                "before_connect": "قبل از اتصال",
                "after_connect": "بعد از اتصال",
                "app_open": "باز شدن اپ",
                "splash": "اسپلش",
            }
            missing = [
                placement_labels[item.key]
                for item in db.scalars(select(AdPlacement)).all()
                if item.key in placement_labels and item.enabled and not item.unit_id
            ]
            if missing:
                raise ValueError(
                    "برای جایگاه‌های فعال Unit ID وارد کنید: " + "، ".join(missing)
                )
            interstitials = [
                item
                for item in db.scalars(select(AdPlacement)).all()
                if item.key in {"before_connect", "after_connect"} and item.enabled
            ]
            if interstitials and (
                ads.interstitial_every_connections < 2
                or any(item.every_n_actions < 2 for item in interstitials)
            ):
                raise ValueError(
                    "در حالت واقعی، تناوب تبلیغ بین‌صفحه‌ای باید حداقل هر ۲ اتصال باشد. مقدار ۱ فقط در حالت تست مجاز است."
                )
        _bump_revision(db)
        audit(
            db, request, admin, "admob.update", "settings", "admob", "unit IDs redacted"
        )
        db.commit()
        add_flash(request, "تنظیمات AdMob ذخیره شد.")
        splash = db.scalar(select(AdPlacement).where(AdPlacement.key == "splash"))
        if splash and splash.enabled:
            active_ad_servers = (
                db.scalar(
                    select(func.count(AdVpnServer.id)).where(
                        AdVpnServer.enabled.is_(True)
                    )
                )
                or 0
            )
            if active_ad_servers == 0:
                add_flash(
                    request,
                    "جایگاه اسپلش فعال است اما سرور تبلیغ فعالی ثبت نشده؛ کلاینت بدون تبلیغ ادامه می‌دهد.",
                    "warning",
                )
        return RedirectResponse("/admin/admob", status_code=303)
    except ValueError as exc:
        db.rollback()
        add_flash(request, str(exc), "error")
        return RedirectResponse("/admin/admob", status_code=303)


@router.get("/updates", response_class=HTMLResponse)
def updates_page(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    policy = db.get(UpdatePolicy, 1)
    return render(
        request,
        "updates.html",
        {"title": "کنترل نسخه و آپدیت", "admin": admin, "policy": policy},
    )


@router.post("/updates")
async def updates_save(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    form = await request.form()
    validate_csrf(request, str(form.get("csrf_token", "")))
    policy = db.get(UpdatePolicy, 1)
    assert policy is not None
    try:
        minimum = _int(
            str(form.get("min_version_code", "1")), 1, 2_147_483_647, "حداقل نسخه"
        )
        maximum = _int(
            str(form.get("max_version_code", "1")), 1, 2_147_483_647, "حداکثر نسخه"
        )
        if maximum < minimum:
            raise ValueError("حداکثر نسخه نمی‌تواند از حداقل نسخه کمتر باشد.")
        policy.enabled = "enabled" in form
        policy.force_update = "force_update" in form
        policy.min_version_code = minimum
        policy.max_version_code = maximum
        policy.title = str(form.get("title", "")).strip()[:140]
        policy.message = str(form.get("message", "")).strip()[:2000]
        policy.direct_url = _url(str(form.get("direct_url", "")), "لینک مستقیم")
        policy.play_store_url = _url(
            str(form.get("play_store_url", "")), "لینک پلی‌استور"
        )
        _bump_revision(db)
        audit(
            db,
            request,
            admin,
            "update_policy.update",
            "settings",
            "update",
            f"allowed={minimum}..{maximum}",
        )
        db.commit()
        add_flash(request, "سیاست نسخه و آپدیت ذخیره شد.")
        return RedirectResponse("/admin/updates", status_code=303)
    except ValueError as exc:
        db.rollback()
        add_flash(request, str(exc), "error")
        return RedirectResponse("/admin/updates", status_code=303)


@router.get("/app-settings", response_class=HTMLResponse)
def app_settings_page(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    app = db.get(AppSettings, 1)
    return render(
        request,
        "app_settings.html",
        {"title": "تنظیمات اپ", "admin": admin, "app": app},
    )


@router.post("/app-settings")
async def app_settings_save(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    form = await request.form()
    validate_csrf(request, str(form.get("csrf_token", "")))
    app = db.get(AppSettings, 1)
    assert app is not None
    try:
        app.privacy_policy_url = _url(
            str(form.get("privacy_policy_url", "")), "لینک حریم خصوصی"
        )
        app.terms_url = _url(str(form.get("terms_url", "")), "لینک قوانین")
        app.support_url = _url(str(form.get("support_url", "")), "لینک پشتیبانی")
        app.share_url = _url(str(form.get("share_url", "")), "لینک اشتراک‌گذاری")
        app.website_url = _url(str(form.get("website_url", "")), "وب‌سایت")
        app.maintenance_enabled = "maintenance_enabled" in form
        app.maintenance_message = str(form.get("maintenance_message", "")).strip()[
            :2000
        ]
        app.payload_ttl_seconds = _int(
            str(form.get("payload_ttl_seconds", "900")), 300, 86400, "عمر کانفیگ"
        )
        app.fail_closed_on_integrity_error = "fail_closed_on_integrity_error" in form
        app.config_revision += 1
        audit(
            db,
            request,
            admin,
            "app_settings.update",
            "settings",
            "app",
            f"revision={app.config_revision}",
        )
        db.commit()
        add_flash(request, "تنظیمات اپ ذخیره شد.")
        return RedirectResponse("/admin/app-settings", status_code=303)
    except ValueError as exc:
        db.rollback()
        add_flash(request, str(exc), "error")
        return RedirectResponse("/admin/app-settings", status_code=303)


@router.get("/audit", response_class=HTMLResponse)
def audit_page(request: Request, db: Session = Depends(get_db)):
    admin = current_admin(request, db)
    rows = db.scalars(
        select(AuditLog).order_by(desc(AuditLog.created_at)).limit(200)
    ).all()
    return render(
        request,
        "audit.html",
        {"title": "گزارش تغییرات", "admin": admin, "audits": rows},
    )
