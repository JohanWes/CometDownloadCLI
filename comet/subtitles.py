"""Subtitles: keyless search and download (the OpenSubtitles catalogue, reached through the
legacy opensubtitles.org REST API and Stremio's OpenSubtitles addon), result ranking, sidecar
files and the reader for subtitle tracks embedded in Matroska files. Shared by the CLI and
the Android app. No account, API key or login is involved."""
import gzip
import io
import json
import os
import re
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

# Search with full metadata (file-hash matches, trusted uploaders, machine translations).
LEGACY_SEARCH_URL = "https://rest.opensubtitles.org/search"
# Same catalogue with less detail; used when the legacy API is down or finds nothing.
STREMIO_ADDON_URL = "https://opensubtitles-v3.strem.io/subtitles"
# Serves a subtitle file by its id as UTF-8, without the ad the legacy links add.
STREMIO_DOWNLOAD_URL = "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file"
LEGACY_DOWNLOAD_URL = "https://dl.opensubtitles.org/en/download/file"
SUBTITLE_USER_AGENT = "CometDownloadCLI v1.0.2"


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
# Our language codes -> the three-letter ids both subtitle sources use.
_SOURCE_LANGUAGE_IDS = {
    "en": "eng", "sv": "swe", "no": "nor", "da": "dan", "fi": "fin", "de": "ger", "fr": "fre",
    "es": "spa", "it": "ita", "nl": "dut", "pt": "por", "pt-br": "pob", "pl": "pol",
    "ja": "jpn", "ko": "kor", "zh": "chi", "zh-cn": "chi", "zh-tw": "zht", "ar": "ara",
    "tr": "tur", "ru": "rus", "uk": "ukr", "el": "ell", "he": "heb", "hu": "hun", "cs": "cze",
    "ro": "rum", "hr": "hrv", "sr": "scc", "bg": "bul", "is": "ice", "et": "est",
}
_ISO639_2_TO_1 = {
    "eng": "en", "swe": "sv", "nor": "no", "nob": "no", "nno": "no", "dan": "da", "fin": "fi",
    "ger": "de", "deu": "de", "fre": "fr", "fra": "fr", "spa": "es", "ita": "it", "dut": "nl",
    "nld": "nl", "por": "pt", "pol": "pl", "jpn": "ja", "rus": "ru", "chi": "zh", "zho": "zh",
    "kor": "ko", "ara": "ar", "tur": "tr", "hun": "hu", "cze": "cs", "ces": "cs", "gre": "el",
    "ell": "el", "heb": "he", "ice": "is", "isl": "is", "rum": "ro", "ron": "ro",
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


def _source_language_ids(languages: tuple[str, ...]) -> dict[str, str]:
    """Source id -> our code, in preference order, for the languages the sources know."""
    ids: dict[str, str] = {}
    for code in languages:
        source = _SOURCE_LANGUAGE_IDS.get(code)
        if source is None and len(code) == 2:
            source = next((three for three, two in _ISO639_2_TO_1.items() if two == code), None)
        if source is None and len(code) == 3:
            source = code
        if source is not None:
            ids.setdefault(source, code)
    return ids


@dataclass(frozen=True)
class SubtitleConfig:
    languages: tuple[str, ...] = DEFAULT_SUBTITLE_LANGUAGES
    enabled: bool = True


class SubtitleError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


def _fetch(url: str, *, timeout: int = 30) -> tuple[bytes, str]:
    """One GET: the body and its content type. Retries once after a 429; errors never
    include the URL."""
    for attempt in range(2):
        request = Request(url, headers={"User-Agent": SUBTITLE_USER_AGENT, "Accept": "*/*"})
        try:
            with urlopen(request, timeout=timeout) as response:
                return response.read(), response.headers.get("Content-Type", "")
        except HTTPError as error:
            if error.code == 429 and attempt == 0:
                value = (error.headers.get("Retry-After") or "").strip()
                time.sleep(min(float(value) if value.replace(".", "", 1).isdigit() else 2.0, 10.0))
                continue
            if error.code == 429:
                raise SubtitleError("The subtitle service is rate limiting requests.", status=429) from None
            raise SubtitleError(f"The subtitle service returned HTTP {error.code}.", status=error.code) from None
        except (URLError, OSError) as error:
            reason = getattr(error, "reason", None) or error.__class__.__name__
            raise SubtitleError(f"Could not reach the subtitle service ({reason}).") from None
    raise SubtitleError("The subtitle service is rate limiting requests.", status=429)


def _fetch_json(url: str) -> Any:
    body, _ = _fetch(url)
    try:
        return json.loads(body.decode("utf-8", errors="replace"))
    except ValueError:
        raise SubtitleError("The subtitle service sent an unreadable answer.") from None


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
    trusted: bool = False


def rank_subtitles(
    choices: list[SubtitleChoice], languages: tuple[str, ...], video_name: str
) -> list[SubtitleChoice]:
    """Best first: preferred language, then human over machine translations, then exact
    file-hash matches (always in sync), release-name similarity, trust, popularity."""
    video_tokens = release_tokens(video_name)

    def key(choice: SubtitleChoice) -> tuple:
        tokens = release_tokens(choice.release)
        similarity = len(tokens & video_tokens) / max(len(tokens | video_tokens), 1)
        return (
            languages.index(choice.language),
            choice.machine_translated,
            not choice.hash_match,
            -round(similarity, 2),
            not choice.trusted,
            -choice.download_count,
        )

    return sorted((choice for choice in choices if choice.language in languages), key=key)


def _flag(value: Any) -> bool:
    return str(value or "0").strip() not in ("0", "")


def _number(value: Any) -> int:
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0


def _legacy_choice(row: Any, language_ids: dict[str, str]) -> SubtitleChoice | None:
    """One row of the legacy API as a choice; None for anything we can't use (other
    language, forced-only, split over several files, not SubRip)."""
    if not isinstance(row, dict):
        return None
    language = language_ids.get(str(row.get("SubLanguageID") or "").lower())
    file_id = _number(row.get("IDSubtitleFile"))
    if language is None or not file_id or _flag(row.get("SubForeignPartsOnly")):
        return None
    if _number(row.get("SubSumCD")) > 1 or str(row.get("SubFormat") or "srt").lower() != "srt":
        return None
    release = str(row.get("MovieReleaseName") or "").strip()
    file_name = str(row.get("SubFileName") or "").strip()
    return SubtitleChoice(
        file_id,
        language,
        str(row.get("MatchedBy") or "") == "moviehash",
        f"{release} {file_name}".strip() if release and file_name else release or file_name,
        hearing_impaired=_flag(row.get("SubHearingImpaired")),
        machine_translated=_flag(row.get("SubAutoTranslation")),
        download_count=_number(row.get("SubDownloadsCnt")),
        trusted=_flag(row.get("SubFromTrusted")),
    )


def _legacy_search(segments: dict[str, str]) -> list[Any]:
    # The legacy API wants its path segments lower-case and in alphabetical order.
    path = "/".join(f"{key}-{quote(value.lower(), safe='')}" for key, value in sorted(segments.items()))
    rows = _fetch_json(f"{LEGACY_SEARCH_URL}/{path}")
    return rows if isinstance(rows, list) else []


def _hash_matches(
    video_path: Path,
    *,
    media_type: str,
    imdb_id: str,
    season: int | None,
    episode: int | None,
    language_ids: dict[str, str],
) -> list[SubtitleChoice]:
    """Subtitles uploaded for this exact video file, so they are in sync. The hash index
    holds a few wrong pairings, so a row must also be for the title we asked for."""
    try:
        moviehash = opensubtitles_moviehash(video_path)
        size = video_path.stat().st_size
    except OSError:
        return []
    if moviehash is None:
        return []
    number = imdb_id_number(imdb_id) if imdb_id else ""
    choices: list[SubtitleChoice] = []
    for row in _legacy_search({"moviebytesize": str(size), "moviehash": moviehash}):
        if not isinstance(row, dict):
            continue
        if number and media_type == "series":
            if str(row.get("SeriesIMDBParent") or "").lstrip("0") != number:
                continue
            numbers = (_number(row.get("SeriesSeason")), _number(row.get("SeriesEpisode")))
            if season is not None and episode is not None and numbers != (season, episode):
                continue
        elif number and str(row.get("IDMovieImdb") or "").lstrip("0") != number:
            continue
        choice = _legacy_choice(row, language_ids)
        if choice is not None:
            choices.append(replace(choice, hash_match=True))
    return choices


def _stremio_search(
    *, media_type: str, imdb_id: str, season: int | None, episode: int | None, language_ids: dict[str, str]
) -> list[SubtitleChoice]:
    imdb = imdb_id if imdb_id.lower().startswith("tt") else f"tt{imdb_id}"
    if media_type == "series":
        if season is None or episode is None:
            return []
        url = f"{STREMIO_ADDON_URL}/series/{imdb}:{season}:{episode}.json"
    else:
        url = f"{STREMIO_ADDON_URL}/movie/{imdb}.json"
    payload = _fetch_json(url)
    rows = payload.get("subtitles") if isinstance(payload, dict) else None
    choices: list[SubtitleChoice] = []
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        language = language_ids.get(str(row.get("lang") or "").lower())
        file_id = _number(str(row.get("url") or "").rstrip("/").rsplit("/", 1)[-1])
        if language is None or not file_id:
            continue
        release = str(row.get("movieReleaseName") or "").strip()
        file_name = str(row.get("subtitleFileName") or "").strip()
        choices.append(
            SubtitleChoice(
                file_id,
                language,
                False,
                f"{release} {file_name}".strip() if release and file_name else release or file_name,
                hearing_impaired=bool(re.search(r"\b(hi|sdh|cc)\b", file_name, re.IGNORECASE)),
            )
        )
    return choices


def search_subtitles(
    *,
    media_type: str,
    imdb_id: str,
    video_path: Path,
    season: int | None,
    episode: int | None,
    languages: tuple[str, ...],
    exclude: set[int] | None = None,
    first_language_only: bool = False,
) -> list[SubtitleChoice]:
    """Every matching subtitle, best first (see [rank_subtitles]). Without an IMDb id the
    title is looked up by the video's file name instead. With [first_language_only] the
    search stops at the first preferred language that has something (fewer requests)."""
    exclude = exclude or set()
    language_ids = _source_language_ids(languages)
    found: dict[int, SubtitleChoice] = {}
    error: SubtitleError | None = None

    def add(choices: list[SubtitleChoice]) -> None:
        for choice in choices:
            if choice.file_id not in exclude and choice.file_id not in found:
                found[choice.file_id] = choice

    try:
        add(
            _hash_matches(
                video_path,
                media_type=media_type,
                imdb_id=imdb_id,
                season=season,
                episode=episode,
                language_ids=language_ids,
            )
        )
    except SubtitleError as hash_error:
        error = hash_error

    segments: dict[str, str]
    is_episode = media_type == "series" and season is not None and episode is not None
    if imdb_id and (media_type != "series" or is_episode):
        segments = {"imdbid": imdb_id_number(imdb_id)}
        if is_episode:
            segments.update(season=str(season), episode=str(episode))
    else:
        segments = {"query": video_path.stem}
    for source_id, language in language_ids.items():
        if first_language_only and any(choice.language == language for choice in found.values()):
            break
        if error is not None:
            break  # the legacy API is down; don't wait for it once per language
        try:
            rows = _legacy_search({**segments, "sublanguageid": source_id})
        except SubtitleError as search_error:
            error = search_error
            break
        add([choice for row in rows if (choice := _legacy_choice(row, {source_id: language})) is not None])
        if first_language_only and any(choice.language == language for choice in found.values()):
            break

    if not found and imdb_id:
        try:
            add(
                _stremio_search(
                    media_type=media_type,
                    imdb_id=imdb_id,
                    season=season,
                    episode=episode,
                    language_ids=language_ids,
                )
            )
        except SubtitleError as stremio_error:
            error = error or stremio_error
    if not found and error is not None:
        raise error
    return rank_subtitles(list(found.values()), languages, video_path.name)


def find_subtitle(
    *,
    media_type: str,
    imdb_id: str,
    video_path: Path,
    season: int | None,
    episode: int | None,
    languages: tuple[str, ...],
    exclude: set[int] | None = None,
) -> SubtitleChoice | None:
    ranked = search_subtitles(
        media_type=media_type,
        imdb_id=imdb_id,
        video_path=video_path,
        season=season,
        episode=episode,
        languages=languages,
        exclude=exclude,
        first_language_only=True,
    )
    return ranked[0] if ranked else None


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


_AD_CUE = re.compile(
    r"opensubtitles|osdb\.link|(get|try)ray\.app|advertise your product|rate this subtitle", re.IGNORECASE
)
_AD_FIRST_TIMING = "00:00:06,000 --> 00:00:12,074"
_CUE_TIMING = re.compile(r"(\d+):(\d\d):(\d\d)[,.](\d{3}) --> (\d+):(\d\d):(\d\d)[,.](\d{3})")


def _is_ad_cue(block: str, *, last: bool) -> bool:
    """The legacy download host adds one cue at a fixed time near the start and a
    minute-long one after the last line; the wording changes, the timing doesn't."""
    if _AD_CUE.search(block) or _AD_FIRST_TIMING in block:
        return True
    match = _CUE_TIMING.search(block)
    if not last or match is None:
        return False
    h1, m1, s1, ms1, h2, m2, s2, ms2 = (int(part) for part in match.groups())
    return (h2 * 3600 + m2 * 60 + s2) * 1000 + ms2 - ((h1 * 3600 + m1 * 60 + s1) * 1000 + ms1) >= 59_000


def strip_ad_cues(raw: bytes) -> bytes:
    """Drops the advertising cues the legacy download links add to a subtitle."""
    text = normalize_subtitle_text(raw).decode("utf-8").replace("\r\n", "\n")
    blocks = [block for block in re.split(r"\n{2,}", text) if block.strip()]
    kept = [
        block for index, block in enumerate(blocks) if not _is_ad_cue(block, last=index == len(blocks) - 1)
    ]
    return ("\n\n".join(kept) + "\n").encode("utf-8")


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


def _looks_like_subtitle(raw: bytes) -> bool:
    return b"-->" in raw[:65536]


def fetch_subtitle(file_id: int) -> bytes:
    """One subtitle file by id: from Stremio's mirror (UTF-8, no ads), otherwise from the
    legacy download host with its ad cues removed."""
    try:
        raw, _ = _fetch(f"{STREMIO_DOWNLOAD_URL}/{int(file_id)}", timeout=60)
        if _looks_like_subtitle(raw):
            return raw
    except SubtitleError:
        pass
    raw, _ = _fetch(f"{LEGACY_DOWNLOAD_URL}/{int(file_id)}.gz", timeout=60)
    try:
        raw = gzip.decompress(raw)
    except (OSError, EOFError):
        pass
    if not _looks_like_subtitle(raw):
        raise SubtitleError("The subtitle file could not be downloaded.")
    return strip_ad_cues(raw)


def download_subtitle_file(file_id: int, language: str, video_path: Path, *, overwrite: bool = False) -> Path:
    """Saves one subtitle next to the video and returns the sidecar."""
    return write_subtitle_sidecar(video_path, fetch_subtitle(file_id), language, overwrite=overwrite)


def download_subtitle(choice: SubtitleChoice, video_path: Path, *, overwrite: bool = False) -> Path:
    return download_subtitle_file(choice.file_id, choice.language, video_path, overwrite=overwrite)


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
