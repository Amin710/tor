from __future__ import annotations

import base64
import re

from pydantic import BaseModel, ConfigDict, Field, field_validator


class BootstrapRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    installationId: str = Field(min_length=8, max_length=128)
    packageName: str = Field(min_length=1, max_length=160)
    versionCode: int = Field(ge=1, le=2_147_483_647)
    versionName: str = Field(min_length=1, max_length=40)


def _reject_newlines(value: str) -> str:
    if "\r" in value or "\n" in value:
        raise ValueError("newlines are not allowed")
    return value


class SecureSessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    protocolVersion: int = Field(ge=2, le=2, strict=True)
    installationId: str = Field(min_length=8, max_length=128)
    packageName: str = Field(min_length=1, max_length=160)
    versionCode: int = Field(ge=1, le=2_147_483_647, strict=True)
    versionName: str = Field(min_length=1, max_length=40)
    deviceCredential: str = Field(default="", max_length=128)
    clientEncryptionPublicKey: str = Field(min_length=32, max_length=1024)
    clientSigningPublicKey: str = Field(min_length=32, max_length=512)

    @field_validator(
        "installationId",
        "packageName",
        "versionName",
        "deviceCredential",
        "clientEncryptionPublicKey",
        "clientSigningPublicKey",
    )
    @classmethod
    def validate_text(cls, value: str) -> str:
        return _reject_newlines(value)

    @field_validator("deviceCredential")
    @classmethod
    def validate_credential(cls, value: str) -> str:
        if value and not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value):
            raise ValueError("device credential is malformed")
        return value


class SecureBootstrapRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    protocolVersion: int = Field(ge=2, le=2, strict=True)
    sessionId: str = Field(min_length=32, max_length=128)
    clientTimestampEpochSeconds: int = Field(ge=1, strict=True)
    requestSignature: str = Field(min_length=32, max_length=512)

    @field_validator("sessionId", "requestSignature")
    @classmethod
    def validate_text(cls, value: str) -> str:
        return _reject_newlines(value)

    @field_validator("sessionId")
    @classmethod
    def validate_session_id(cls, value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", value):
            raise ValueError("session id is malformed")
        try:
            raw = base64.urlsafe_b64decode(value + "=")
        except ValueError as exc:
            raise ValueError("session id is malformed") from exc
        if len(raw) != 32 or base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=") != value:
            raise ValueError("session id is malformed")
        return value
