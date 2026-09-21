from pathlib import Path

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
