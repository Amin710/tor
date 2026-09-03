from __future__ import annotations

import json
import time
from functools import lru_cache
from typing import Any


class SecureStoreUnavailable(RuntimeError):
    pass


@lru_cache(maxsize=2)
def redis_client(url: str):
    import redis

    return redis.Redis.from_url(
        url,
        socket_connect_timeout=1.5,
        socket_timeout=1.5,
        health_check_interval=30,
        decode_responses=True,
    )


def _client(url: str):
    if not url:
        raise SecureStoreUnavailable("Redis is not configured")
    return redis_client(url)


def ping(url: str) -> None:
    try:
        if not _client(url).ping():
            raise SecureStoreUnavailable("Redis ping failed")
    except SecureStoreUnavailable:
        raise
    except Exception as exc:
        raise SecureStoreUnavailable("Redis is unavailable") from exc


def rate_limited(url: str, namespace: str, identity: str, limit: int) -> bool:
    window = int(time.time()) // 60
    key = f"tornado:rl:v2:{namespace}:{identity}:{window}"
    try:
        pipe = _client(url).pipeline(transaction=True)
        pipe.incr(key)
        pipe.expire(key, 120)
        current, _ = pipe.execute()
        return int(current) > limit
    except SecureStoreUnavailable:
        raise
    except Exception as exc:
        raise SecureStoreUnavailable("Redis rate limiter is unavailable") from exc


def put_session(url: str, session_id: str, value: dict[str, Any], ttl: int) -> bool:
    key = f"tornado:v2:session:{session_id}"
    encoded = json.dumps(value, separators=(",", ":"), ensure_ascii=True)
    try:
        return bool(_client(url).set(key, encoded, ex=ttl, nx=True))
    except SecureStoreUnavailable:
        raise
    except Exception as exc:
        raise SecureStoreUnavailable("Redis session store is unavailable") from exc


def get_session(url: str, session_id: str) -> dict[str, Any] | None:
    key = f"tornado:v2:session:{session_id}"
    try:
        encoded = _client(url).get(key)
    except SecureStoreUnavailable:
        raise
    except Exception as exc:
        raise SecureStoreUnavailable("Redis session store is unavailable") from exc
    if encoded is None:
        return None
    try:
        value = json.loads(encoded)
    except (TypeError, ValueError) as exc:
        raise SecureStoreUnavailable("Redis session data is invalid") from exc
    return value if isinstance(value, dict) else None


def consume_session(url: str, session_id: str) -> dict[str, Any] | None:
    key = f"tornado:v2:session:{session_id}"
    try:
        encoded = _client(url).getdel(key)
    except SecureStoreUnavailable:
        raise
    except Exception as exc:
        raise SecureStoreUnavailable("Redis session store is unavailable") from exc
    if encoded is None:
        return None
    try:
        value = json.loads(encoded)
    except (TypeError, ValueError) as exc:
        raise SecureStoreUnavailable("Redis session data is invalid") from exc
    return value if isinstance(value, dict) else None


def consume_app_check_token(url: str, token_hash: str, ttl: int) -> bool:
    key = f"tornado:v2:app-check:{token_hash}"
    try:
        return bool(_client(url).set(key, "1", ex=ttl, nx=True))
    except SecureStoreUnavailable:
        raise
    except Exception as exc:
        raise SecureStoreUnavailable("Redis token replay store is unavailable") from exc
