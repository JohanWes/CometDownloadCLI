"""Subtitles: the OpenSubtitles client, result ranking, sidecar files and the reader for
subtitle tracks embedded in Matroska files. Shared by the CLI and the Android app."""
import gzip
import io
import json
import os
import re
import threading
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

OPENSUBTITLES_SESSION_LOCK = threading.Lock()
OPENSUBTITLES_SESSION_CACHE: dict[tuple[str, str, str], tuple[str, str]] = {}
OPENSUBTITLES_DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1"
OPENSUBTITLES_USER_AGENT = "CometDownloadCLI v0.1.0"


DEFAULT_SUBTITLE_LANGUAGES: tuple[str, ...] = ("en", "sv")
SUBTITLE_LANGUAGE_NAMES = {
    "en": "English", "sv": "Swedish", "no": "Norwegian", "da": "Danish", "fi": "Finnish",
    "de": "German", "fr": "French", "es": "Spanish", "it": "Italian", "nl": "Dutch",
    "pt": "Portuguese", "pt-br": "Portuguese (BR)", "pl": "Polish", "ja": "Japanese",
    "ko": "Korean", "zh-cn": "Chinese (simplified)", "zh-tw": "Chinese (traditional)",
    "ar": "Arabic", "tr": "Turkish", "ru": "Russian", "uk": "Ukrainian", "el": "Greek",
    "he": "Hebrew", "hu": "Hungarian", "cs": "Czech", "ro": "Romanian", "hr": "Croatian",
    "sr": "Serbian", "bg": "Bulgarian", "is": "Icelandic", "et": "Estonian",
}


def parse_subtitle_languages(value: str | None) -> tuple[str, ...]:
    """'en, sv' -> ('en', 'sv'); empty or invalid input falls back to the default order."""
    codes: list[str] = []
    for part in re.split(r"[\s,;]+", (value or "").lower()):
        if re.fullmatch(r"[a-z]{2,3}(-[a-z]{2})?", part) and part not in codes:
            codes.append(part)
    return tuple(codes) or DEFAULT_SUBTITLE_LANGUAGES


def subtitle_language_name(code: str) -> str:
    return SUBTITLE_LANGUAGE_NAMES.get(code.lower(), code.upper())


@dataclass(frozen=True)
class OpenSubtitlesConfig:
    api_key: str
    username: str
    password: str
    languages: tuple[str, ...] = DEFAULT_SUBTITLE_LANGUAGES

    @property
    def is_configured(self) -> bool:
        """An API key turns the subtitle step on; downloads also need an account login."""
        return bool(self.api_key)

    @property
    def has_login(self) -> bool:
        return bool(self.username and self.password)


def opensubtitles_headers(
    config: OpenSubtitlesConfig,
    token: str | None = None,
    *,
    content_type: bool = False,
) -> dict[str, str]:
    headers = {
        "Accept": "application/json",
        "Api-Key": config.api_key,
        "User-Agent": OPENSUBTITLES_USER_AGENT,
    }
    if content_type:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def normalize_opensubtitles_base_url(value: str) -> str:
    value = value.strip()
    if not value:
        return OPENSUBTITLES_DEFAULT_BASE_URL
    if not value.startswith(("http://", "https://")):
        value = f"https://{value}"
    value = value.rstrip("/")
    if value.endswith("/api/v1"):
        return value
    return value + "/api/v1"


class OpenSubtitlesError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, retry_after: float | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.retry_after = retry_after


class OpenSubtitlesQuotaError(OpenSubtitlesError):
    pass


OPENSUBTITLES_LOGIN_FAILURES: dict[tuple[str, str, str], float] = {}
OPENSUBTITLES_LOGIN_RETRY_SECONDS = 15 * 60


def _opensubtitles_error_message(body: bytes) -> str:
    try:
        payload = json.loads(body.decode("utf-8", errors="replace"))
    except ValueError:
        return ""
    if isinstance(payload, dict):
        for key in ("message", "error", "errors"):
            value = payload.get(key)
            if isinstance(value, list):
                value = "; ".join(str(item) for item in value)
            if value:
                return str(value)[:160]
    return ""


def opensubtitles_request(
    config: OpenSubtitlesConfig,
    url: str,
    *,
    token: str | None = None,
    payload: dict[str, Any] | None = None,
    timeout: int = 30,
) -> dict[str, Any]:
    """One OpenSubtitles call. Retries once after a 429; errors never include URLs or keys."""
    for attempt in range(2):
        request = Request(
            url,
            data=json.dumps(payload).encode("utf-8") if payload is not None else None,
            headers=opensubtitles_headers(config, token, content_type=payload is not None),
            method="POST" if payload is not None else "GET",
        )
        try:
            with urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            detail = _opensubtitles_error_message(error.read())
            retry_after = None
            for header in ("Retry-After", "ratelimit-reset", "X-RateLimit-Reset"):
                value = (error.headers.get(header) or "").strip()
                if value.replace(".", "", 1).isdigit():
                    retry_after = float(value)
                    break
            if error.code == 429 and attempt == 0:
                time.sleep(min(retry_after or 2.0, 10.0))
                continue
            if error.code == 406 and "download" in url:
                raise OpenSubtitlesQuotaError(
                    detail or "Daily download limit reached.", status=406, retry_after=retry_after
                ) from None
            raise OpenSubtitlesError(
                f"OpenSubtitles HTTP {error.code}" + (f": {detail}" if detail else ""),
                status=error.code,
                retry_after=retry_after,
            ) from None
        except (URLError, OSError, ValueError) as error:
            reason = getattr(error, "reason", None) or error.__class__.__name__
            raise OpenSubtitlesError(f"Could not reach OpenSubtitles ({reason}).") from None
    raise OpenSubtitlesError("OpenSubtitles is rate limiting requests.", status=429)


class OpenSubtitlesSession:
    """Lazily signed-in session shared across jobs; re-logs in when the token expires."""

    def __init__(self, config: OpenSubtitlesConfig) -> None:
        self.config = config
        self.token: str | None = None
        self.base_url = OPENSUBTITLES_DEFAULT_BASE_URL
        self.login_error = ""
        self._login(force=False)

    def _key(self) -> tuple[str, str, str]:
        return (self.config.api_key, self.config.username, self.config.password)

    def _login(self, *, force: bool) -> None:
        if not self.config.has_login:
            return
        key = self._key()
        with OPENSUBTITLES_SESSION_LOCK:
            if force:
                OPENSUBTITLES_SESSION_CACHE.pop(key, None)
            cached = OPENSUBTITLES_SESSION_CACHE.get(key)
            if cached is not None:
                self.token, self.base_url = cached
                return
            failed_at = OPENSUBTITLES_LOGIN_FAILURES.get(key)
            if failed_at is not None and time.time() - failed_at < OPENSUBTITLES_LOGIN_RETRY_SECONDS:
                self.login_error = "OpenSubtitles rejected the sign-in a moment ago; check your details or wait a few minutes"
                self.token = None
                return
            try:
                payload = opensubtitles_request(
                    self.config,
                    f"{OPENSUBTITLES_DEFAULT_BASE_URL}/login",
                    payload={"username": self.config.username, "password": self.config.password},
                )
            except OpenSubtitlesError as error:
                rejected = error.status in (400, 401, 403)
                # Back off after a rejection or rate limit (OpenSubtitles blocks clients that
                # keep hammering /login), but let a network hiccup be retried right away.
                if rejected or error.status == 429:
                    OPENSUBTITLES_LOGIN_FAILURES[key] = time.time()
                self.login_error = "OpenSubtitles rejected the username or password" if rejected else str(error)
                self.token = None
                return
            token = str(payload.get("token", "")).strip()
            if not token:
                OPENSUBTITLES_LOGIN_FAILURES[key] = time.time()
                self.login_error = "OpenSubtitles sign-in returned no token"
                return
            OPENSUBTITLES_LOGIN_FAILURES.pop(key, None)
            self.token = token
            self.base_url = normalize_opensubtitles_base_url(str(payload.get("base_url", "")))
            OPENSUBTITLES_SESSION_CACHE[key] = (self.token, self.base_url)
            self.login_error = ""

    def call(self, path: str, *, params: dict[str, str] | None = None, payload: dict[str, Any] | None = None):
        url = f"{self.base_url}{path}"
        if params:
            # OpenSubtitles asks for sorted, lower-case query params (otherwise it redirects).
            url += "?" + urlencode(sorted((key.lower(), value.lower()) for key, value in params.items()))
        try:
            return opensubtitles_request(self.config, url, token=self.token, payload=payload)
        except OpenSubtitlesError as error:
            if error.status != 401 or not self.token:
                raise
        self._login(force=True)
        return opensubtitles_request(self.config, url, token=self.token, payload=payload)


def imdb_id_number(imdb_id: str) -> str:
    value = imdb_id[2:] if imdb_id.lower().startswith("tt") else imdb_id
    return value.lstrip("0") or "0"


def opensubtitles_moviehash(video_path: Path) -> str | None:
    size = video_path.stat().st_size
    chunk_size = 64 * 1024
    if size < chunk_size * 2:
        return None

    checksum = size
    with video_path.open("rb") as file_handle:
        for _ in range(chunk_size // 8):
            chunk = file_handle.read(8)
            if len(chunk) != 8:
                return None
            checksum = (checksum + int.from_bytes(chunk, "little", signed=False)) & 0xFFFFFFFFFFFFFFFF

        file_handle.seek(max(size - chunk_size, 0))
        for _ in range(chunk_size // 8):
            chunk = file_handle.read(8)
            if len(chunk) != 8:
                return None
            checksum = (checksum + int.from_bytes(chunk, "little", signed=False)) & 0xFFFFFFFFFFFFFFFF

    return f"{checksum:016x}"


_RELEASE_STOPWORDS = {"mkv", "mp4", "avi", "srt", "x264", "x265", "h264", "h265", "hevc", "aac", "the"}


def release_tokens(name: str) -> set[str]:
    stem = re.sub(r"\.(mkv|mp4|avi|m4v|ts|webm|srt)$", "", name.lower())
    return {token for token in re.split(r"[^a-z0-9]+", stem) if token and token not in _RELEASE_STOPWORDS}


@dataclass(frozen=True)
class SubtitleChoice:
    file_id: int
    language: str
    hash_match: bool
    release: str
    hearing_impaired: bool = False
    machine_translated: bool = False
    download_count: int = 0


def rank_subtitles(
    rows: list[dict[str, Any]], languages: tuple[str, ...], video_name: str
) -> list[SubtitleChoice]:
    """Best first: preferred language, then human over machine/AI translations,
    then exact file-hash matches (always in sync), release-name similarity, trust, popularity."""
    video_tokens = release_tokens(video_name)
    ranked: list[tuple[tuple, SubtitleChoice]] = []
    for row in rows:
        attributes = row.get("attributes") if isinstance(row, dict) else None
        if not isinstance(attributes, dict) or attributes.get("foreign_parts_only") is True:
            continue
        language = str(attributes.get("language") or "").lower()
        if language not in languages:
            continue
        file_id = None
        for file_info in attributes.get("files") or []:
            try:
                file_id = int(file_info.get("file_id"))
                break
            except (AttributeError, TypeError, ValueError):
                continue
        if file_id is None:
            continue
        release = str(attributes.get("release") or "")
        file_names = " ".join(str(f.get("file_name") or "") for f in attributes.get("files") or [] if isinstance(f, dict))
        tokens = release_tokens(release) | release_tokens(file_names)
        similarity = len(tokens & video_tokens) / max(len(tokens | video_tokens), 1)
        hash_match = attributes.get("moviehash_match") is True
        machine = bool(attributes.get("machine_translated")) or bool(attributes.get("ai_translated"))
        downloads = int(attributes.get("download_count") or 0)
        key = (
            languages.index(language),
            machine,
            not hash_match,
            -round(similarity, 2),
            not attributes.get("from_trusted"),
            -downloads,
        )
        choice = SubtitleChoice(
            file_id,
            language,
            hash_match,
            release or file_names.strip(),
            hearing_impaired=bool(attributes.get("hearing_impaired")),
            machine_translated=machine,
            download_count=downloads,
        )
        ranked.append((key, choice))
    ranked.sort(key=lambda item: item[0])
    return [choice for _, choice in ranked]


def search_subtitles(
    session: OpenSubtitlesSession,
    *,
    media_type: str,
    imdb_id: str,
    video_path: Path,
    season: int | None,
    episode: int | None,
    languages: tuple[str, ...] | None = None,
) -> list[SubtitleChoice]:
    """Every matching subtitle, best first (see [rank_subtitles]). Without an IMDb id the
    title is looked up by the video's file name and hash instead."""
    languages = languages or session.config.languages
    params = {"languages": ",".join(sorted(languages))}
    if not imdb_id:
        params["query"] = video_path.stem
    elif media_type == "series":
        params.update(
            parent_imdb_id=imdb_id_number(imdb_id),
            season_number=str(season),
            episode_number=str(episode),
            type="episode",
        )
    else:
        params.update(imdb_id=imdb_id_number(imdb_id), type="movie")
    try:
        moviehash = opensubtitles_moviehash(video_path)
    except OSError:
        moviehash = None
    if moviehash is not None:
        params["moviehash"] = moviehash
    rows = session.call("/subtitles", params=params).get("data") or []
    if not rows and moviehash is not None:
        params.pop("moviehash")
        rows = session.call("/subtitles", params=params).get("data") or []
    return rank_subtitles(rows, languages, video_path.name)


def find_subtitle(
    session: OpenSubtitlesSession,
    *,
    media_type: str,
    imdb_id: str,
    video_path: Path,
    season: int | None,
    episode: int | None,
    languages: tuple[str, ...] | None = None,
    exclude: set[int] | None = None,
) -> SubtitleChoice | None:
    ranked = search_subtitles(
        session,
        media_type=media_type,
        imdb_id=imdb_id,
        video_path=video_path,
        season=season,
        episode=episode,
        languages=languages,
    )
    return next((choice for choice in ranked if choice.file_id not in (exclude or set())), None)


def subtitle_bytes_from_download(raw: bytes, content_type: str, source_url: str) -> bytes:
    lower_url = urlparse(source_url).path.lower()
    if "gzip" in content_type.lower() or lower_url.endswith(".gz"):
        return gzip.decompress(raw)
    if "zip" in content_type.lower() or lower_url.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            subtitle_names = [
                name for name in archive.namelist() if name.lower().endswith((".srt", ".vtt", ".sub"))
            ]
            if not subtitle_names:
                raise RuntimeError("OpenSubtitles archive did not contain a subtitle file.")
            with archive.open(subtitle_names[0]) as file_handle:
                return file_handle.read()
    return raw


def normalize_subtitle_text(raw: bytes) -> bytes:
    """Subtitle bytes as UTF-8 without a BOM. Older files are often Windows-1252 (which
    turns å/ä/ö into garbage in players that assume UTF-8) or UTF-16."""
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        try:
            return raw.decode("utf-16").encode("utf-8")
        except UnicodeDecodeError:
            return raw
    try:
        return raw.decode("utf-8-sig").encode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp1252", errors="replace").encode("utf-8")


def write_subtitle_sidecar(
    video_path: Path, subtitle_bytes: bytes, language: str = "en", *, overwrite: bool = False
) -> Path:
    destination = video_path.with_suffix(f".{language}.srt")
    suffix = 1
    while destination.exists() and not overwrite:
        destination = video_path.with_name(f"{video_path.stem}.{language} ({suffix}).srt")
        suffix += 1
    destination.write_bytes(normalize_subtitle_text(subtitle_bytes))
    return destination


def download_subtitle_file(
    session: OpenSubtitlesSession, file_id: int, language: str, video_path: Path, *, overwrite: bool = False
) -> tuple[Path, int | None]:
    """Saves one subtitle next to the video. Returns the sidecar and how many downloads the
    account has left today (None when OpenSubtitles doesn't say)."""
    payload = session.call("/download", payload={"file_id": file_id, "sub_format": "srt"})
    link = str(payload.get("link", "")).strip()
    if not link:
        raise OpenSubtitlesError(str(payload.get("message") or "OpenSubtitles returned no download link."))
    request = Request(link, headers={"User-Agent": OPENSUBTITLES_USER_AGENT})
    try:
        with urlopen(request, timeout=60) as response:
            raw = response.read()
            content_type = response.headers.get("Content-Type", "")
    except HTTPError as error:
        raise OpenSubtitlesError(f"Subtitle file download returned HTTP {error.code}.") from None
    except (URLError, OSError):
        raise OpenSubtitlesError("Subtitle file download failed (network).") from None
    remaining = payload.get("remaining")
    path = write_subtitle_sidecar(
        video_path, subtitle_bytes_from_download(raw, content_type, link), language, overwrite=overwrite
    )
    return path, remaining if isinstance(remaining, int) else None


def download_subtitle(
    session: OpenSubtitlesSession, choice: SubtitleChoice, video_path: Path, *, overwrite: bool = False
) -> Path:
    return download_subtitle_file(session, choice.file_id, choice.language, video_path, overwrite=overwrite)[0]


# --- Embedded subtitle tracks (Matroska) ----------------------------------------------

_MKV_SEGMENT = 0x18538067
_MKV_TRACKS = 0x1654AE6B
_MKV_CLUSTER = 0x1F43B675
_MKV_TRACK_ENTRY = 0xAE
_MKV_TRACK_TYPE = 0x83
_MKV_CODEC_ID = 0x86
_MKV_LANGUAGE = 0x22B59C
_MKV_LANGUAGE_BCP47 = 0x22B59D
_MKV_FLAG_FORCED = 0x55AA
_MKV_SCAN_LIMIT = 64 * 1024 * 1024
_MKV_TRACKS_MAX_BYTES = 8 * 1024 * 1024
_ISO639_2_TO_1 = {
    "eng": "en", "swe": "sv", "nor": "no", "nob": "no", "nno": "no", "dan": "da", "fin": "fi",
    "ger": "de", "deu": "de", "fre": "fr", "fra": "fr", "spa": "es", "ita": "it", "dut": "nl",
    "nld": "nl", "por": "pt", "pol": "pl", "jpn": "ja", "rus": "ru", "chi": "zh", "zho": "zh",
    "kor": "ko", "ara": "ar", "tur": "tr", "hun": "hu", "cze": "cs", "ces": "cs", "gre": "el",
    "ell": "el", "heb": "he", "ice": "is", "isl": "is", "rum": "ro", "ron": "ro",
}
_TEXT_SUBTITLE_CODECS = ("S_TEXT/", "S_SSA", "S_ASS")


@dataclass(frozen=True)
class EmbeddedSubtitle:
    language: str  # ISO 639-1 where known, "" when undetermined
    codec: str
    forced: bool

    @property
    def is_text(self) -> bool:
        return self.codec.startswith(_TEXT_SUBTITLE_CODECS)


def _ebml_vint(handle, *, keep_marker: bool) -> tuple[int | None, int]:
    first = handle.read(1)
    if not first:
        raise EOFError
    byte = first[0]
    length, mask = 1, 0x80
    while length <= 8 and not byte & mask:
        length += 1
        mask >>= 1
    if length > 8:
        raise ValueError("bad EBML length")
    value = byte if keep_marker else byte & (mask - 1)
    rest = handle.read(length - 1)
    if len(rest) != length - 1:
        raise EOFError
    all_ones = (value == mask - 1) and not keep_marker
    for extra in rest:
        value = (value << 8) | extra
        all_ones = all_ones and extra == 0xFF
    return (None if all_ones else value), length


def _ebml_element(handle) -> tuple[int, int | None]:
    element_id, _ = _ebml_vint(handle, keep_marker=True)
    size, _ = _ebml_vint(handle, keep_marker=False)
    return element_id, size


def _normalize_language(code: str) -> str:
    code = code.strip().lower()
    if not code or code in {"und", "zxx", "mul"}:
        return ""
    base = code.split("-")[0]
    return _ISO639_2_TO_1.get(base, base if len(base) == 2 else "")


def mkv_subtitle_tracks(path: Path) -> list[EmbeddedSubtitle]:
    """Subtitle tracks from a Matroska file's Tracks element (read from the file head).
    Returns [] for other formats or anything unreadable."""
    if path.suffix.lower() not in (".mkv", ".webm"):
        return []
    try:
        with path.open("rb") as handle:
            header_id, header_size = _ebml_element(handle)
            if header_id != 0x1A45DFA3 or header_size is None:
                return []
            handle.seek(header_size, os.SEEK_CUR)
            segment_id, _ = _ebml_element(handle)
            if segment_id != _MKV_SEGMENT:
                return []
            while handle.tell() < _MKV_SCAN_LIMIT:
                element_id, size = _ebml_element(handle)
                if element_id == _MKV_TRACKS and size is not None:
                    if size > _MKV_TRACKS_MAX_BYTES:
                        return []  # a real Tracks element is a few KB; this file is broken
                    return _parse_mkv_tracks(handle.read(size))
                if element_id == _MKV_CLUSTER or size is None:
                    return []
                handle.seek(size, os.SEEK_CUR)
    except (OSError, EOFError, ValueError, OverflowError, MemoryError):
        return []
    return []


def _parse_mkv_tracks(data: bytes) -> list[EmbeddedSubtitle]:
    tracks: list[EmbeddedSubtitle] = []
    stream = io.BytesIO(data)
    while stream.tell() < len(data):
        element_id, size = _ebml_element(stream)
        if size is None:
            break
        body = stream.read(size)
        if element_id != _MKV_TRACK_ENTRY:
            continue
        entry = io.BytesIO(body)
        track_type, codec, language, bcp47, forced = 0, "", "eng", "", False
        while entry.tell() < len(body):
            child_id, child_size = _ebml_element(entry)
            if child_size is None:
                break
            value = entry.read(child_size)
            if child_id == _MKV_TRACK_TYPE:
                track_type = int.from_bytes(value, "big")
            elif child_id == _MKV_CODEC_ID:
                codec = value.decode("ascii", "replace").rstrip("\x00")
            elif child_id == _MKV_LANGUAGE:
                language = value.decode("ascii", "replace").rstrip("\x00")
            elif child_id == _MKV_LANGUAGE_BCP47:
                bcp47 = value.decode("ascii", "replace").rstrip("\x00")
            elif child_id == _MKV_FLAG_FORCED:
                forced = int.from_bytes(value, "big") == 1
        if track_type == 17:
            tracks.append(EmbeddedSubtitle(_normalize_language(bcp47 or language), codec, forced))
    return tracks


def embedded_subtitle_summary(paths: list[Path]) -> list[str]:
    """Languages of full (non-forced) subtitle tracks across the downloaded videos,
    e.g. ["English", "Swedish (image)"]."""
    names: list[str] = []
    for path in paths:
        for track in mkv_subtitle_tracks(path):
            if track.forced or not track.language:
                continue
            name = subtitle_language_name(track.language) + ("" if track.is_text else " (image)")
            if name not in names:
                names.append(name)
    return names


SUBTITLE_VIDEO_EXTENSIONS = (".mkv", ".mp4", ".avi", ".m4v", ".ts", ".mov", ".wmv", ".webm", ".mpg", ".mpeg")
SEASON_EPISODE_FILENAME = re.compile(r"\bS(\d{1,2})[ ._-]?E(\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b", re.IGNORECASE)


def episode_number_from_filename(path: Path) -> int | None:
    parsed = season_episode_from_filename(path)
    if parsed[1] is not None:
        return parsed[1]
    match = re.search(r"\b(?:E|EP|Episode)[ ._-]*(\d{1,3})\b", path.name, re.IGNORECASE)
    return int(match.group(1)) if match else None


def season_episode_from_filename(path: Path) -> tuple[int | None, int | None]:
    match = SEASON_EPISODE_FILENAME.search(path.name)
    if not match:
        return None, None
    season = match.group(1) or match.group(3)
    episode = match.group(2) or match.group(4)
    return int(season), int(episode)
