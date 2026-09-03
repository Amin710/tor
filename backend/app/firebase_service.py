from __future__ import annotations

import threading
import time
from dataclasses import dataclass

from .config import Settings

_firebase_lock = threading.Lock()
_firebase_app = None


def verify_app_check(token: str, settings: Settings) -> dict:
    if settings.allow_insecure_dev_attestation and not settings.is_production:
        if token == settings.dev_app_check_token:
            return {
                "app_id": settings.firebase_app_id or "development-app",
                "sub": settings.firebase_app_id or "development-app",
            }
    if not token:
        raise ValueError("App Check token is missing")

    global _firebase_app
    with _firebase_lock:
        if _firebase_app is None:
            import firebase_admin
            from firebase_admin import credentials

            try:
                _firebase_app = firebase_admin.get_app("tornado-app-check")
            except ValueError:
                options = (
                    {"projectId": settings.firebase_project_id}
                    if settings.firebase_project_id
                    else None
                )
                if settings.firebase_credentials_path:
                    credential = credentials.Certificate(
                        settings.firebase_credentials_path
                    )
                    _firebase_app = firebase_admin.initialize_app(
                        credential, options=options, name="tornado-app-check"
                    )
                else:
                    _firebase_app = firebase_admin.initialize_app(
                        options=options, name="tornado-app-check"
                    )

    return _verify_with_app(token, settings)


def _verify_with_app(token: str, settings: Settings) -> dict:
    from firebase_admin import app_check

    claims = app_check.verify_token(token, app=_firebase_app)
    token_app_id = str(claims.get("app_id") or claims.get("sub") or "")
    if settings.firebase_app_id and token_app_id != settings.firebase_app_id:
        raise ValueError("App Check token belongs to another Firebase app")
    return claims


@dataclass(frozen=True)
class AnalyticsSnapshot:
    active_1d: int | None = None
    active_7d: int | None = None
    active_30d: int | None = None
    configured: bool = False
    error: str = ""


_analytics_cache: tuple[float, AnalyticsSnapshot] | None = None
_analytics_lock = threading.Lock()


def firebase_active_users(settings: Settings) -> AnalyticsSnapshot:
    """Fetch real GA4 activeUsers. Results are cached for ten minutes."""

    if not settings.ga4_property_id:
        return AnalyticsSnapshot(configured=False)
    global _analytics_cache
    now = time.monotonic()
    with _analytics_lock:
        if _analytics_cache and now - _analytics_cache[0] < 600:
            return _analytics_cache[1]
        try:
            from google.analytics.data_v1beta import BetaAnalyticsDataClient
            from google.analytics.data_v1beta.types import (
                DateRange,
                Metric,
                RunReportRequest,
            )

            client_options = {}
            if settings.firebase_credentials_path:
                from google.oauth2 import service_account

                client_options["credentials"] = (
                    service_account.Credentials.from_service_account_file(
                        settings.firebase_credentials_path,
                        scopes=[
                            "https://www.googleapis.com/auth/analytics.readonly"
                        ],
                    )
                )
            client = BetaAnalyticsDataClient(**client_options)

            def active(start_date: str) -> int:
                report = client.run_report(
                    RunReportRequest(
                        property=f"properties/{settings.ga4_property_id}",
                        metrics=[Metric(name="activeUsers")],
                        date_ranges=[
                            DateRange(start_date=start_date, end_date="today")
                        ],
                        limit=1,
                    )
                )
                return int(report.rows[0].metric_values[0].value) if report.rows else 0

            snapshot = AnalyticsSnapshot(
                active_1d=active("1daysAgo"),
                active_7d=active("7daysAgo"),
                active_30d=active("30daysAgo"),
                configured=True,
            )
        except (
            Exception
        ) as exc:  # Google SDK exposes several transport/auth exceptions.
            snapshot = AnalyticsSnapshot(configured=True, error=type(exc).__name__)
        _analytics_cache = (now, snapshot)
        return snapshot
