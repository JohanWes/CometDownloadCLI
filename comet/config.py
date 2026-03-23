import os
from dataclasses import dataclass
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT_DIR / ".env"


def _parse_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}

    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
            value = value[1:-1]
        values[key.strip()] = value
    return values


def _env(name: str, default: str) -> str:
    file_values = _parse_env_file(ENV_PATH)
    return os.environ.get(name, file_values.get(name, default))


def _env_int(name: str, default: int) -> int:
    raw = _env(name, str(default)).strip()
    try:
        return int(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    fastapi_host: str = _env("FASTAPI_HOST", "127.0.0.1")
    fastapi_port: int = _env_int("FASTAPI_PORT", 8000)
    public_api_token: str = _env("PUBLIC_API_TOKEN", "").strip()
    stremthru_url: str = _env("STREMTHRU_URL", "https://stremthru.13377001.xyz").rstrip("/")
    log_level: str = _env("COMET_LOG_LEVEL", "INFO")
    torznab_timeout_seconds: int = _env_int("COMET_TORZNAB_TIMEOUT_SECONDS", 30)
    stremthru_timeout_seconds: int = _env_int("COMET_STREMTHRU_TIMEOUT_SECONDS", 45)
    max_results_per_resolution: int = _env_int("COMET_MAX_RESULTS_PER_RESOLUTION", 25)


settings = Settings()
