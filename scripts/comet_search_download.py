#!/usr/bin/env python3
import argparse
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen
import urllib.request


ROOT_DIR = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT_DIR / ".env"
DEFAULT_HOST = "http://127.0.0.1:8000"
REALDEBRID_ENV_KEY = "REALDEBRID_API_TOKEN"
DOWNLOAD_DIR_ENV_KEY = "COMET_DOWNLOAD_DIR"
PUBLIC_API_TOKEN_ENV_KEY = "PUBLIC_API_TOKEN"
HEALTHCHECK_TIMEOUT = 60
STREAM_RESULT_LIMIT = 3
STARTUP_LOG_PATH = ROOT_DIR / "data" / "comet_search_download.log"
DEFAULT_TMDB_READ_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlNTkxMmVmOWFhM2IxNzg2Zjk3ZTE1NWY1YmQ3ZjY1MSIsInN1YiI6IjY1M2NjNWUyZTg5NGE2MDBmZjE2N2FmYyIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.xrIXsMFJpI1o1j5g2QpQcFP1X3AfRjFA5FlBFO5Naw8"
TMDB_BASE_URL = "https://api.themoviedb.org/3"
TMDB_HEADERS = {
    "Authorization": f"Bearer {DEFAULT_TMDB_READ_ACCESS_TOKEN}",
    "Content-Type": "application/json",
}

SAFE_FILENAME_CHARS = re.compile(r"[^A-Za-z0-9._() -]+")
CONTENT_DISPOSITION_FILENAME = re.compile(
    r'filename\*?=(?:UTF-8\'\')?"?([^";]+)"?', re.IGNORECASE
)

RESOLUTION_RULES = {
    "4K": {"min_bytes": 3 * 1024**3, "max_bytes": 10 * 1024**3},
    "1080P": {"min_bytes": 1 * 1024**3, "max_bytes": 5 * 1024**3},
}
FULL_SEASON_SIZE_MULTIPLIER = 2


@dataclass
class MediaCandidate:
    media_type: str
    tmdb_id: int
    title: str
    year: str
    imdb_id: str


@dataclass
class StreamCandidate:
    index: int
    resolution: str
    is_cached: bool
    size_bytes: int
    name: str
    description: str
    playback_url: str
    is_strict_match: bool
    preferred_distance_bytes: int
    fallback_reason: str = ""


@dataclass(frozen=True)
class SizePreferenceContext:
    media_type: str
    season: int | None = None
    episode: int | None = None


@dataclass
class LaunchCommand:
    argv: list[str]
    description: str


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def default_download_dir() -> Path:
    if os.name == "nt":
        user_profile = os.environ.get("USERPROFILE", "").strip()
        if user_profile:
            return Path(user_profile) / "Downloads"

        home_drive = os.environ.get("HOMEDRIVE", "").strip()
        home_path = os.environ.get("HOMEPATH", "").strip()
        if home_drive and home_path:
            return Path(f"{home_drive}{home_path}") / "Downloads"

    return Path.home() / "Downloads"


def is_temp_directory(path: Path) -> bool:
    resolved = path.expanduser().resolve(strict=False)
    candidates = {Path(tempfile.gettempdir()).resolve(strict=False)}

    for env_key in ("TMPDIR", "TEMP", "TMP"):
        value = os.environ.get(env_key, "").strip()
        if value:
            candidates.add(Path(value).expanduser().resolve(strict=False))

    return any(resolved == candidate or candidate in resolved.parents for candidate in candidates)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Search Comet with a Real-Debrid token and download a result."
    )
    parser.add_argument("--query", help="Movie or show title to search for.")
    parser.add_argument("--token", help="Real-Debrid API token. Saved to .env.")
    parser.add_argument("--output-dir", help="Download directory.")
    parser.add_argument("--host", default=DEFAULT_HOST, help="Comet base URL.")
    parser.add_argument(
        "--restart-comet",
        action="store_true",
        help="Restart the local Comet process before searching.",
    )
    return parser.parse_args()


def parse_env_file(path: Path) -> tuple[list[str], dict[str, str]]:
    if not path.exists():
        return [], {}

    lines = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = unquote_env_value(value.strip())
    return lines, values


def unquote_env_value(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        quote = value[0]
        inner = value[1:-1]
        if quote == '"':
            inner = inner.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
        return inner
    return value


def format_env_value(value: str) -> str:
    if not value:
        return '""'
    if re.fullmatch(r"[A-Za-z0-9_./:-]+", value):
        return value
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def upsert_env_value(path: Path, key: str, value: str) -> bool:
    lines, existing = parse_env_file(path)
    if existing.get(key) == value:
        return False

    new_line = f"{key}={format_env_value(value)}"
    updated = False
    for index, line in enumerate(lines):
        if line.strip().startswith(f"{key}="):
            lines[index] = new_line
            updated = True
            break

    if not updated:
        if lines and lines[-1].strip():
            lines.append("")
        lines.append(new_line)

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return True


def load_env_values() -> dict[str, str]:
    _, values = parse_env_file(ENV_PATH)
    return values


def load_token(cli_token: str | None) -> tuple[str, bool]:
    if cli_token:
        token = cli_token.strip()
        return token, upsert_env_value(ENV_PATH, REALDEBRID_ENV_KEY, token)

    token = load_env_values().get(REALDEBRID_ENV_KEY, "").strip()
    if token:
        return token, False

    token = input("Real-Debrid API token: ").strip()
    if not token:
        raise SystemExit("A Real-Debrid API token is required.")
    return token, upsert_env_value(ENV_PATH, REALDEBRID_ENV_KEY, token)


def resolve_output_dir(cli_output_dir: str | None) -> tuple[Path, bool]:
    if cli_output_dir:
        path = Path(cli_output_dir).expanduser().resolve()
        return path, False

    configured = load_env_values().get(DOWNLOAD_DIR_ENV_KEY, "").strip()
    if configured:
        path = Path(configured).expanduser().resolve()
        if not is_temp_directory(path):
            return path, False

    return default_download_dir().expanduser().resolve(), False


def build_api_prefix() -> str:
    public_api_token = load_env_values().get(PUBLIC_API_TOKEN_ENV_KEY, "").strip()
    return f"/s/{public_api_token}" if public_api_token else ""


def http_json(
    url: str, *, headers: dict[str, str] | None = None, timeout: int = 30
) -> dict[str, Any]:
    request = Request(url, headers=headers or {})
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GET {url} failed with HTTP {error.code}: {body[:200]}")
    except URLError as error:
        raise RuntimeError(f"GET {url} failed: {error.reason}")


def http_response(
    url: str,
    *,
    headers: dict[str, str] | None = None,
    timeout: int = 30,
    follow_redirects: bool = True,
):
    request = Request(url, headers=headers or {})

    if follow_redirects:
        return urlopen(request, timeout=timeout)

    opener = urllib.request.build_opener(NoRedirectProcessor())
    return opener.open(request, timeout=timeout)


class NoRedirectProcessor(urllib.request.BaseHandler):
    def http_response(self, request, response):
        return response

    https_response = http_response


def check_health(host: str) -> bool:
    try:
        data = http_json(f"{host.rstrip('/')}/health", timeout=5)
        return data.get("status") == "ok"
    except Exception:
        return False


def has_module(python_executable: str, module_name: str) -> bool:
    result = subprocess.run(
        [python_executable, "-c", f"import {module_name}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        cwd=ROOT_DIR,
        check=False,
    )
    return result.returncode == 0


def pick_comet_launch_command() -> LaunchCommand:
    override = os.environ.get("COMET_PYTHON", "").strip()
    candidates: list[LaunchCommand] = []

    if override:
        candidates.append(
            LaunchCommand(
                argv=[override, "-m", "comet.main"],
                description=f"COMET_PYTHON={override}",
            )
        )

    venv_python = ROOT_DIR / ".venv" / "bin" / "python"
    if venv_python.exists():
        candidates.append(
            LaunchCommand(
                argv=[str(venv_python), "-m", "comet.main"],
                description=str(venv_python),
            )
        )

    uv_path = shutil.which("uv")
    if uv_path:
        candidates.append(
            LaunchCommand(
                argv=[uv_path, "run", "python", "-m", "comet.main"],
                description="uv run python",
            )
        )

    candidates.append(
        LaunchCommand(
            argv=[sys.executable, "-m", "comet.main"],
            description=sys.executable,
        )
    )

    for candidate in candidates:
        program = Path(candidate.argv[0])
        if candidate.argv[0] != "uv" and not program.exists() and not shutil.which(candidate.argv[0]):
            continue
        if candidate.argv[0].endswith("python") or candidate.argv[0].endswith("python3") or len(candidate.argv) >= 3 and candidate.argv[1] == "run":
            if candidate.argv[0] == uv_path:
                return candidate
            python_executable = candidate.argv[0]
            if has_module(python_executable, "uvicorn"):
                return candidate

    raise RuntimeError(
        "Could not find a Python environment that can run Comet. "
        "Install the project dependencies first, or set COMET_PYTHON to a Python interpreter "
        "that has Comet's dependencies installed."
    )


def stop_existing_comet_processes(host: str) -> bool:
    result = subprocess.run(
        ["pgrep", "-af", "comet.main"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        cwd=ROOT_DIR,
        check=False,
    )
    stopped_any = False
    for line in result.stdout.splitlines():
        parts = line.strip().split(maxsplit=1)
        if not parts:
            continue
        try:
            pid = int(parts[0])
        except ValueError:
            continue
        cmdline = parts[1] if len(parts) > 1 else ""
        if str(ROOT_DIR) not in cmdline:
            continue
        try:
            os.kill(pid, 15)
            stopped_any = True
        except ProcessLookupError:
            continue

    if stopped_any:
        deadline = time.time() + 10
        while time.time() < deadline and check_health(host):
            time.sleep(0.5)
    return stopped_any


def ensure_comet_running(host: str, restart: bool = False) -> None:
    if restart and check_health(host):
        stop_existing_comet_processes(host)

    if check_health(host):
        return

    launch = pick_comet_launch_command()
    STARTUP_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with STARTUP_LOG_PATH.open("ab") as log_file:
        subprocess.Popen(
            launch.argv,
            cwd=ROOT_DIR,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    deadline = time.time() + HEALTHCHECK_TIMEOUT
    while time.time() < deadline:
        if check_health(host):
            return
        time.sleep(1)

    raise RuntimeError(
        f"Comet did not become healthy within {HEALTHCHECK_TIMEOUT}s using {launch.description}. "
        f"Check {STARTUP_LOG_PATH}."
    )


def build_b64_config(token: str) -> str:
    config: dict[str, Any] = {
        "debridServices": [{"service": "realdebrid", "apiKey": token}],
        "enableTorrent": False,
        "maxResultsPerResolution": 10,
        "maxSize": 0.0,
        "cachedOnly": False,
        "sortCachedUncachedTogether": False,
        "removeTrash": True,
        "deduplicateStreams": True,
        "languages": {
            "required": ["en"],
            "allowed": [],
            "exclude": [],
            "preferred": ["en"],
        },
        "resolutions": {
            "r2160p": True,
            "r1440p": False,
            "r1080p": True,
            "r720p": False,
            "r576p": False,
            "r480p": False,
            "r360p": False,
            "r240p": False,
            "unknown": False,
        },
        "options": {
            "remove_ranks_under": 0,
            "allow_english_in_languages": True,
            "remove_unknown_languages": True,
        },
    }
    raw = json.dumps(config, separators=(",", ":")).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def tmdb_json(path: str, params: dict[str, str]) -> dict[str, Any]:
    query = urlencode(params)
    url = f"{TMDB_BASE_URL}{path}?{query}"
    return http_json(url, headers=TMDB_HEADERS)


def fetch_imdb_id_for_tmdb(media_type: str, tmdb_id: int) -> str | None:
    endpoint = "movie" if media_type == "movie" else "tv"
    data = http_json(
        f"{TMDB_BASE_URL}/{endpoint}/{tmdb_id}/external_ids",
        headers=TMDB_HEADERS,
    )
    imdb_id = data.get("imdb_id")
    return imdb_id if isinstance(imdb_id, str) and imdb_id else None


def search_tmdb(query: str) -> list[MediaCandidate]:
    movie_data = tmdb_json("/search/movie", {"query": query, "include_adult": "false"})
    tv_data = tmdb_json("/search/tv", {"query": query, "include_adult": "false"})

    raw_candidates: list[tuple[str, dict[str, Any]]] = []
    raw_candidates.extend(("movie", item) for item in movie_data.get("results", [])[:5])
    raw_candidates.extend(("series", item) for item in tv_data.get("results", [])[:5])

    candidates: list[MediaCandidate] = []
    for media_type, item in raw_candidates:
        tmdb_id = item.get("id")
        if not tmdb_id:
            continue
        try:
            imdb_id = fetch_imdb_id_for_tmdb(media_type, int(tmdb_id))
        except Exception:
            continue
        if not imdb_id or not imdb_id.startswith("tt"):
            continue

        if media_type == "movie":
            title = item.get("title") or "Untitled"
            year = (item.get("release_date") or "")[:4]
        else:
            title = item.get("name") or "Untitled"
            year = (item.get("first_air_date") or "")[:4]

        candidates.append(
            MediaCandidate(
                media_type=media_type,
                tmdb_id=int(tmdb_id),
                title=title,
                year=year or "?",
                imdb_id=imdb_id,
            )
        )

    seen: set[tuple[str, str]] = set()
    deduped: list[MediaCandidate] = []
    for candidate in candidates:
        key = (candidate.media_type, candidate.imdb_id)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(candidate)
    return deduped


def prompt_choice(prompt: str, max_value: int) -> int:
    while True:
        raw = input(prompt).strip()
        if raw.isdigit() and 1 <= int(raw) <= max_value:
            return int(raw)
        print(f"Enter a number between 1 and {max_value}.")


def choose_media(candidates: list[MediaCandidate]) -> MediaCandidate:
    if not candidates:
        raise SystemExit("No movie or show candidates were found for that query.")

    print("\nMatches:")
    for index, candidate in enumerate(candidates, start=1):
        media_label = "Movie" if candidate.media_type == "movie" else "Series"
        print(
            f"  {index}. {candidate.title} ({candidate.year}) [{media_label}] [{candidate.imdb_id}]"
        )
    return candidates[prompt_choice("Choose a title: ", len(candidates)) - 1]


def prompt_series_scope() -> tuple[int, int | None]:
    while True:
        season = input("Season number: ").strip()
        episode = input("Episode number (leave blank for full season): ").strip()
        if not season.isdigit() or int(season) <= 0:
            print("Season number must be a positive integer.")
            continue
        if not episode:
            return int(season), None
        if episode.isdigit() and int(episode) > 0:
            return int(season), int(episode)
        print("Episode number must be blank or a positive integer.")


def normalize_resolution(name: str) -> str | None:
    upper = name.upper()
    if "4K" in upper or "2160P" in upper:
        return "4K"
    if "1080P" in upper:
        return "1080P"
    return None


def is_cached_stream(name: str) -> bool:
    return "⚡" in name or " C]" in name or "[RD C]" in name


def resolution_rule_for_context(
    resolution: str, context: SizePreferenceContext
) -> dict[str, int]:
    rule = RESOLUTION_RULES[resolution]
    if context.media_type == "series" and context.season is not None and context.episode is None:
        return {
            "min_bytes": rule["min_bytes"] * FULL_SEASON_SIZE_MULTIPLIER,
            "max_bytes": rule["max_bytes"] * FULL_SEASON_SIZE_MULTIPLIER,
        }
    return rule


def build_stream_candidates(
    streams: list[dict[str, Any]], context: SizePreferenceContext
) -> list[StreamCandidate]:
    results: list[StreamCandidate] = []
    next_index = 1
    for stream in streams:
        name = str(stream.get("name", ""))
        resolution = normalize_resolution(name)
        if resolution is None:
            continue

        behavior_hints = stream.get("behaviorHints") or {}
        size_bytes = behavior_hints.get("videoSize")
        if not isinstance(size_bytes, int) or size_bytes <= 0:
            continue

        rule = resolution_rule_for_context(resolution, context)
        is_strict_match = True
        preferred_distance_bytes = 0
        fallback_reason = ""
        if size_bytes < rule["min_bytes"]:
            is_strict_match = False
            preferred_distance_bytes = rule["min_bytes"] - size_bytes
            fallback_reason = f"below preferred size floor ({format_bytes(size_bytes)})"
        elif size_bytes > rule["max_bytes"]:
            is_strict_match = False
            preferred_distance_bytes = size_bytes - rule["max_bytes"]
            fallback_reason = f"above preferred size ceiling ({format_bytes(size_bytes)})"

        playback_url = stream.get("url")
        if not isinstance(playback_url, str) or not playback_url:
            continue

        results.append(
            StreamCandidate(
                index=next_index,
                resolution=resolution,
                is_cached=is_cached_stream(name),
                size_bytes=size_bytes,
                name=name,
                description=str(stream.get("description", "")).strip(),
                playback_url=playback_url,
                is_strict_match=is_strict_match,
                preferred_distance_bytes=preferred_distance_bytes,
                fallback_reason=fallback_reason,
            )
        )
        next_index += 1
    return results


def group_top_streams(candidates: list[StreamCandidate]) -> list[StreamCandidate]:
    grouped: list[StreamCandidate] = []
    for resolution in ("4K", "1080P"):
        resolution_candidates = [item for item in candidates if item.resolution == resolution]
        strict_matches = [item for item in resolution_candidates if item.is_strict_match]
        fallback_matches = [item for item in resolution_candidates if not item.is_strict_match]
        strict_matches.sort(key=lambda item: (not item.is_cached, item.index))
        fallback_matches.sort(
            key=lambda item: (
                item.preferred_distance_bytes,
                not item.is_cached,
                item.size_bytes,
                item.index,
            )
        )

        selected = strict_matches[:STREAM_RESULT_LIMIT]
        remaining = STREAM_RESULT_LIMIT - len(selected)
        if remaining > 0:
            selected.extend(fallback_matches[:remaining])
        grouped.extend(selected)

    for new_index, candidate in enumerate(grouped, start=1):
        candidate.index = new_index
    return grouped


def format_bytes(size_bytes: int) -> str:
    return f"{size_bytes / 1024**3:.2f} GB"


def print_streams(candidates: list[StreamCandidate]) -> None:
    if not candidates:
        raise SystemExit(
            "No streams matched the current filters for 4K/1080P, size, and English language."
        )

    print("\nFiltered results (up to 3 per resolution):")
    for resolution in ("4K", "1080P"):
        print(f"\n{resolution}")
        section = [item for item in candidates if item.resolution == resolution]
        if not section:
            print("  No matching results.")
            continue
        print(f"  Showing {len(section)} match{'es' if len(section) != 1 else ''}.")
        for item in section:
            status = "cached" if item.is_cached else "uncached"
            label = "strict" if item.is_strict_match else f"fallback: {item.fallback_reason}"
            print(
                f"  {item.index}. {item.name} | {format_bytes(item.size_bytes)} | {status} | {label}"
            )
            if item.description:
                print(f"     {item.description.splitlines()[0]}")


def fetch_streams(host: str, b64config: str, media_type: str, media_id: str) -> list[dict[str, Any]]:
    api_prefix = build_api_prefix()
    url = f"{host.rstrip('/')}{api_prefix}/{b64config}/stream/{media_type}/{media_id}.json"
    payload = http_json(url, timeout=120)
    return payload.get("streams", [])


def choose_stream(candidates: list[StreamCandidate]) -> StreamCandidate:
    return candidates[prompt_choice("Choose a stream to download: ", len(candidates)) - 1]


def sanitize_filename(value: str) -> str:
    cleaned = SAFE_FILENAME_CHARS.sub(" ", value).strip().rstrip(".")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned or "download.bin"


def looks_generic_filename(filename: str) -> bool:
    normalized = filename.lower()
    return (
        normalized.startswith("rd comet ")
        or normalized.startswith("comet ")
        or normalized in {"download.mkv", "video.mkv", "download.bin"}
    )


def format_eta(seconds: float | None) -> str:
    if seconds is None or seconds < 0 or seconds == float("inf"):
        return "--:--"
    total = int(seconds)
    minutes, secs = divmod(total, 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours:d}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


def format_speed(bytes_per_second: float) -> str:
    return f"{bytes_per_second / 1024**2:.2f} MB/s"


def build_preferred_filename_base(
    media: MediaCandidate,
    resolution: str,
    season: int | None = None,
    episode: int | None = None,
) -> str:
    if resolution == "4K":
        resolution = "2160p"
    elif resolution == "1080P":
        resolution = "1080p"

    parts = [media.title]
    if media.year and media.year != "?":
        parts.append(f"({media.year})")
    if media.media_type == "series" and season is not None:
        if episode is None:
            parts.append(f"Season {season}")
        else:
            parts.append(f"S{season:02d}E{episode:02d}")
    parts.append(resolution)
    return " ".join(parts)


def build_collection_dir_name(media: MediaCandidate, season: int | None = None) -> str:
    parts = [media.title]
    if media.media_type == "series" and season is not None:
        parts.append(f"Season {season:02d}")
    if media.year and media.year != "?":
        parts.append(f"({media.year})")
    return sanitize_filename(" ".join(parts))


def extract_filename(headers, fallback_name: str, fallback_ext: str = ".mkv") -> str:
    header = headers.get("Content-Disposition", "")
    match = CONTENT_DISPOSITION_FILENAME.search(header)
    if match:
        filename = os.path.basename(match.group(1).strip().strip('"'))
        if filename and not looks_generic_filename(filename):
            return sanitize_filename(filename)

    cleaned = sanitize_filename(fallback_name)
    if "." not in cleaned:
        cleaned += fallback_ext
    return cleaned


def download_from_url(download_url: str, output_dir: Path, fallback_name: str) -> Path:
    return download_from_url_with_progress(download_url, output_dir, fallback_name)


def download_from_url_with_progress(
    download_url: str,
    output_dir: Path,
    fallback_name: str,
    *,
    completed_files: int = 0,
    total_files: int = 1,
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)

    with urlopen(download_url, timeout=300) as download_response:
        filename = extract_filename(download_response.headers, fallback_name)
        destination = output_dir / filename
        suffix = 1
        while destination.exists():
            destination = destination.with_name(
                f"{destination.stem} ({suffix}){destination.suffix}"
            )
            suffix += 1

        total_bytes = download_response.headers.get("Content-Length")
        total_bytes_int = int(total_bytes) if total_bytes and total_bytes.isdigit() else None
        downloaded_bytes = 0
        started_at = time.time()

        with destination.open("wb") as file_handle:
            while True:
                chunk = download_response.read(1024 * 1024)
                if not chunk:
                    break
                file_handle.write(chunk)
                downloaded_bytes += len(chunk)

                elapsed = max(time.time() - started_at, 1e-6)
                speed = downloaded_bytes / elapsed
                if total_bytes_int:
                    if total_files > 1:
                        estimated_total_bytes = total_bytes_int * total_files
                        aggregate_downloaded_bytes = (
                            completed_files * total_bytes_int + downloaded_bytes
                        )
                        percent = (aggregate_downloaded_bytes / estimated_total_bytes) * 100
                        remaining = max(estimated_total_bytes - aggregate_downloaded_bytes, 0)
                        progress = (
                            f"\rProgress: {percent:6.2f}% | "
                            f"{format_bytes(aggregate_downloaded_bytes)} / "
                            f"{format_bytes(estimated_total_bytes)} estimated | "
                        )
                    else:
                        percent = (downloaded_bytes / total_bytes_int) * 100
                        remaining = max(total_bytes_int - downloaded_bytes, 0)
                        progress = (
                            f"\rProgress: {percent:6.2f}% | "
                            f"{format_bytes(downloaded_bytes)} / {format_bytes(total_bytes_int)} | "
                        )
                    eta = remaining / speed if speed > 0 else None
                    progress += f"{format_speed(speed)} | ETA {format_eta(eta)}"
                else:
                    progress = (
                        f"\rProgress: {format_bytes(downloaded_bytes)} downloaded | "
                        f"{format_speed(speed)} | ETA {format_eta(None)}"
                    )
                print(progress, end="", flush=True)

        print()

    return destination


def build_file_fallback_name(file_name: str, default_name: str) -> str:
    basename = PurePosixPath(file_name).name.strip()
    if not basename:
        return default_name
    cleaned = sanitize_filename(basename)
    return cleaned or default_name


def download_selected_stream(
    host: str,
    candidate: StreamCandidate,
    output_dir: Path,
    fallback_name: str,
) -> list[Path]:
    playback_url = urljoin(f"{host.rstrip('/')}/", candidate.playback_url.lstrip("/"))
    request = Request(playback_url)

    opener = urllib.request.build_opener(NoRedirectHandler(), NoRedirectProcessor())
    try:
        response = opener.open(request, timeout=30)
    except HTTPError as error:
        response = error

    if response.code in (301, 302, 303, 307, 308):
        location = response.headers.get("Location", "").strip()
        if not location:
            raise RuntimeError("Playback redirect did not include a download URL.")
        download_url = urljoin(playback_url, location)
        return [download_from_url(download_url, output_dir, fallback_name)]

    content_type = response.headers.get("Content-Type", "")
    if response.code == 200 and "application/json" in content_type:
        payload = json.loads(response.read().decode("utf-8"))
        downloads = payload.get("downloads")
        if not isinstance(downloads, list) or not downloads:
            raise RuntimeError("Playback JSON response did not include downloadable files.")

        destinations: list[Path] = []
        for index, item in enumerate(downloads, start=1):
            if not isinstance(item, dict):
                continue
            download_url = str(item.get("url", "")).strip()
            if not download_url:
                continue
            per_file_name = build_file_fallback_name(str(item.get("name", "")), fallback_name)
            print(f"\nDownloading file {index}/{len(downloads)} ...")
            destinations.append(
                download_from_url_with_progress(
                    download_url,
                    output_dir,
                    per_file_name,
                    completed_files=index - 1,
                    total_files=len(downloads),
                )
            )

        if destinations:
            return destinations
        raise RuntimeError("Playback JSON response did not contain valid downloadable URLs.")

    body = response.read().decode("utf-8", errors="replace")
    raise RuntimeError(
        f"Playback did not return a download URL or file list (HTTP {response.code}): {body[:200]}"
    )


def main() -> None:
    args = parse_args()
    token, token_saved = load_token(args.token)
    base_output_dir, output_saved = resolve_output_dir(args.output_dir)

    should_restart = args.restart_comet
    ensure_comet_running(args.host, restart=should_restart)
    if token_saved:
        print(f"Saved {REALDEBRID_ENV_KEY} to {ENV_PATH}.")
    if output_saved:
        print(f"Saved {DOWNLOAD_DIR_ENV_KEY} to {ENV_PATH}.")

    query_override = args.query
    while True:
        print()
        query = (query_override or input("Search query: ")).strip()
        query_override = None
        if not query:
            print("A search query is required.")
            continue

        candidates = search_tmdb(query)
        chosen_media = choose_media(candidates)
        media_id = chosen_media.imdb_id
        season = None
        episode = None

        if chosen_media.media_type == "series":
            season, episode = prompt_series_scope()
            media_id = (
                f"{media_id}:{season}"
                if episode is None
                else f"{media_id}:{season}:{episode}"
            )

        b64config = build_b64_config(token)
        raw_streams = fetch_streams(args.host, b64config, chosen_media.media_type, media_id)
        size_context = SizePreferenceContext(
            media_type=chosen_media.media_type,
            season=season,
            episode=episode,
        )
        grouped_streams = group_top_streams(build_stream_candidates(raw_streams, size_context))
        print_streams(grouped_streams)
        selected_stream = choose_stream(grouped_streams)
        preferred_name = build_preferred_filename_base(
            chosen_media,
            selected_stream.resolution,
            season=season,
            episode=episode,
        )
        target_dir = base_output_dir / build_collection_dir_name(chosen_media, season=season)

        print(f"\nDownloading to {target_dir} ...")
        destinations = download_selected_stream(
            args.host,
            selected_stream,
            target_dir,
            preferred_name,
        )
        if len(destinations) == 1:
            print(f"Downloaded: {destinations[0]}")
        else:
            print("Downloaded files:")
            for destination in destinations:
                print(f"  {destination}")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        raise SystemExit(130)
