"""DB 접속 설정. backend와 같은 `.env` 키(`WIO_DB_*`)를 쓴다."""

from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WIO_", env_file=".env", extra="ignore")

    db_url: str = "jdbc:postgresql://localhost:5432/when_i_off"
    db_user: str = "wio"
    db_password: str = "wio"
