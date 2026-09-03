from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from starlette.middleware.sessions import SessionMiddleware

from .admin import router as admin_router
from .bootstrap import router as bootstrap_router
from .config import get_settings
from .crypto import get_signing_material
from .database import Base, SessionLocal, engine
from .image_bootstrap import (
    IMAGE_BOOTSTRAP_PATH,
    ImageBootstrapError,
    router as image_bootstrap_router,
    validate_image_bootstrap_readiness,
)
from .models import AdPlacement, AdSettings, AppSettings, UpdatePolicy
from .security import FieldCipher, seed_admin
from .secure_bootstrap import router as secure_bootstrap_router
from .secure_store import SecureStoreUnavailable, ping as secure_store_ping

settings = get_settings()


def seed_defaults() -> None:
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        seed_admin(db, settings)
        if db.get(AdSettings, 1) is None:
            db.add(AdSettings(id=1))
        if db.get(UpdatePolicy, 1) is None:
            db.add(
                UpdatePolicy(
                    id=1,
                    min_version_code=1000008,
                    max_version_code=1000008,
                    play_store_url=(
                        "https://play.google.com/store/apps/details?"
                        "id=com.vpn.tornadovpn"
                    ),
                )
            )
        if db.get(AppSettings, 1) is None:
            db.add(
                AppSettings(
                    id=1,
                    share_url=(
                        "https://play.google.com/store/apps/details?"
                        "id=com.vpn.tornadovpn"
                    ),
                    website_url=settings.public_base_url,
                )
            )
        existing = {row.key for row in db.query(AdPlacement).all()}
        defaults = {
            "before_connect": ("interstitial", 1, 60),
            "after_connect": ("interstitial", 1, 60),
            "app_open": ("app_open", 1, 300),
            # Used after the permission has already been granted: the client
            # briefly brings up an ad-only VPN route, loads this placement, and
            # only then enters the main activity.
            "splash": ("app_open", 1, 0),
        }
        for key, (ad_format, every, cooldown) in defaults.items():
            if key not in existing:
                db.add(
                    AdPlacement(
                        key=key,
                        ad_format=ad_format,
                        every_n_actions=every,
                        cooldown_seconds=cooldown,
                    )
                )
        db.commit()


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Fail early with a clear configuration error instead of returning a vague
    # 500 later when the first VPN server is added.
    FieldCipher(settings)
    seed_defaults()
    get_signing_material()
    yield


app = FastAPI(
    title="Tornado VPN Backend",
    version="2.1.0",
    docs_url=None if settings.is_production else "/docs",
    redoc_url=None,
    lifespan=lifespan,
)
app.add_middleware(
    SessionMiddleware,
    secret_key=settings.secret_key,
    https_only=settings.secure_cookies or settings.is_production,
    same_site="strict",
    max_age=8 * 60 * 60,
)
app.mount(
    "/static", StaticFiles(directory=Path(__file__).parent / "static"), name="static"
)
app.include_router(bootstrap_router)
app.include_router(secure_bootstrap_router)
app.include_router(image_bootstrap_router)
app.include_router(admin_router)


@app.middleware("http")
async def security_headers(request: Request, call_next):
    content_length = request.headers.get("content-length")
    if content_length and int(content_length) > 64 * 1024:
        return JSONResponse({"detail": "درخواست بیش از حد بزرگ است."}, status_code=413)
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "same-origin"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; img-src 'self' data:; style-src 'self'; "
        "script-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
    )
    if request.url.path == IMAGE_BOOTSTRAP_PATH:
        if "Cache-Control" not in response.headers:
            response.headers["Cache-Control"] = "no-store"
            response.headers["Pragma"] = "no-cache"
    else:
        sensitive_path = request.url.path.startswith(("/admin", "/v1/android"))
        response.headers["Cache-Control"] = (
            "no-store" if sensitive_path else "no-cache"
        )
        if sensitive_path:
            response.headers["Pragma"] = "no-cache"
    if settings.is_production:
        response.headers["Strict-Transport-Security"] = (
            "max-age=31536000; includeSubDomains"
        )
    return response


@app.get("/", include_in_schema=False)
def root():
    return RedirectResponse("/admin", status_code=302)


@app.get("/healthz", tags=["ops"])
def health():
    return {"status": "ok"}


@app.get("/readyz", tags=["ops"])
def ready():
    with SessionLocal() as db:
        db.execute(text("SELECT 1"))
    try:
        get_signing_material()
        FieldCipher(settings)
        validate_image_bootstrap_readiness(settings)
    except (ImageBootstrapError, RuntimeError, ValueError) as exc:
        raise HTTPException(
            status_code=503, detail="bootstrap dependencies are unavailable"
        ) from exc
    if settings.secure_bootstrap_enabled:
        if settings.is_production and not Path(
            settings.firebase_credentials_path
        ).is_file():
            raise HTTPException(
                status_code=503, detail="Firebase Admin credentials are unavailable"
            )
        try:
            secure_store_ping(settings.redis_url)
        except SecureStoreUnavailable as exc:
            raise HTTPException(
                status_code=503, detail="secure session store is unavailable"
            ) from exc
    return {"status": "ready"}


@app.get("/v1/public-info", tags=["ops"])
def public_info():
    info = {
        "packageName": settings.expected_package_name,
        "signingKeyId": settings.signing_key_id,
        "signingPublicKey": get_signing_material().public_key_b64,
        "bootstrapPath": IMAGE_BOOTSTRAP_PATH,
        "bootstrapTransport": "signed-tci1-lsb-rgb-png",
        "legacyBootstrapPath": "/v1/android/bootstrap",
        "secureBootstrapEnabled": settings.secure_bootstrap_enabled,
        "legacyBootstrapEnabled": settings.legacy_bootstrap_enabled,
        "imageBootstrapEnabled": settings.image_bootstrap_enabled,
        "imageBootstrapPath": IMAGE_BOOTSTRAP_PATH,
        "imageBootstrapFormat": "TCI1-LSB-RGB-PNG",
        "baseUrl": settings.public_base_url,
    }
    if settings.secure_bootstrap_enabled:
        info.update(
            {
                "secureSessionPath": "/v1/android/session",
                "secureBootstrapPath": "/v1/android/bootstrap/secure",
                "secureProtocolVersion": 2,
                "responseEncryption": (
                    "RSA-2048-OAEP-SHA256-MGF1-SHA1+AES-256-GCM"
                ),
                "deviceAuthentication": "ECDSA-P256-SHA256",
            }
        )
    return info
