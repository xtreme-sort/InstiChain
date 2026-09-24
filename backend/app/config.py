from pathlib import Path
from typing import Literal

from pydantic import AnyHttpUrl, Field, SecretStr, field_validator

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="INSTICHAIN_",
        env_file=Path(__file__).resolve().parents[1] / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "InstiChain API"
    database_url: str = "postgresql+psycopg://instichain:instichain@127.0.0.1:5432/instichain"
    public_app_url: AnyHttpUrl = "http://127.0.0.1:5173"
    verification_ttl_minutes: int = Field(default=15, ge=1, le=60)
    smtp_host: str = "127.0.0.1"
    smtp_port: int = Field(default=1025, ge=1, le=65535)
    smtp_security: Literal["plain", "starttls", "ssl"] = "plain"
    smtp_username: str | None = None
    smtp_password: SecretStr | None = None
    smtp_from: str = "InstiChain <no-reply@instichain.local>"

    @field_validator("public_app_url")
    @classmethod
    def validate_app_url(cls, value: AnyHttpUrl) -> AnyHttpUrl:
        if value.query or value.fragment or value.username or value.password:
            raise ValueError("Public app URL must not contain credentials, a query or a fragment")
        if value.scheme != "https" and value.host not in {"localhost", "127.0.0.1", "[::1]"}:
            raise ValueError("Public app URL must use HTTPS outside localhost")
        return value
