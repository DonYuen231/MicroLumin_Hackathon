"""Settings. The .env file is only read by load_settings(); tests never call it with a file."""

from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, ValidationError
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parents[1]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_ignore_empty=True, extra="ignore", frozen=True)

    app_token: SecretStr = Field(min_length=1)

    nanogpt_api_key: SecretStr = SecretStr("")
    nanogpt_base_url: str = "https://nano-gpt.com/api/subscription/v1"
    llm_model: str = "z-ai/glm-5.3"

    stt_provider: Literal["nanogpt", "deepgram"] = "nanogpt"
    stt_model: str = "xiaomi/mimo-v2.6-pro"
    deepgram_api_key: SecretStr = SecretStr("")
    deepgram_base_url: str = "https://api.deepgram.com"
    deepgram_model: str = "nova-3"

    face_model: str = "buffalo_l"
    face_model_root: str = "~/.insightface"
    preload_face_engine: bool = False

    max_image_bytes: int = 2 * 1024 * 1024
    max_audio_bytes: int = 50 * 1024 * 1024
    max_json_bytes: int = 1024 * 1024

    # Outside calls. Cloudflare drops requests after 100 s, so keep the budgets below that.
    llm_timeout_s: float = 60.0
    stt_timeout_s: float = 80.0
    upstream_retries: int = 2
    upstream_backoff_s: float = 0.5
    llm_json_retries: int = 2
    stt_json_retries: int = 1
    ffmpeg_timeout_s: float = 60.0

    refresh_fixture_path: Path = BACKEND_DIR / "fixtures" / "refresh.json"


def load_settings(env_file: str | None = ".env") -> Settings:
    """Load settings, failing with a clear message if APP_TOKEN is missing."""
    try:
        return Settings(_env_file=env_file)  # type: ignore[call-arg]
    except ValidationError as exc:
        missing = [".".join(str(p) for p in e["loc"]).upper() for e in exc.errors()]
        raise RuntimeError(
            "Invalid configuration for: "
            + ", ".join(missing)
            + ". APP_TOKEN is required and must not be empty. "
            "Copy .env.example to .env and fill it in."
        ) from None
