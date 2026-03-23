import asyncio
import base64
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import quote

import aiohttp
from RTN import parse

from comet.config import settings


VIDEO_EXTENSIONS = (
    ".3g2",
    ".3gp",
    ".avi",
    ".m4v",
    ".mkv",
    ".mov",
    ".mp4",
    ".mpeg",
    ".mpg",
    ".ogv",
    ".ts",
    ".webm",
    ".wmv",
)
RESOLUTION_MAP = {"2160P": "2160p", "4K": "2160p", "1080P": "1080p"}
SEASON_PATTERN = re.compile(r"\b(?:season|s)\s*0*(\d{1,2})\b", re.IGNORECASE)
EXTRA_FILE_PATTERN = re.compile(
    r"\b(?:sample|trailer|preview|teaser|ncop|nced|creditless|menu|extras?)\b",
    re.IGNORECASE,
)


def _season_episode_pattern(season: int, episode: int) -> re.Pattern[str]:
    return re.compile(
        rf"\b(?:s0*{season}e0*{episode}|{season}x0*{episode}|episode[ ._-]*0*{episode})\b",
        re.IGNORECASE,
    )


@dataclass
class SearchScope:
    media_type: str
    imdb_id: str
    season: int | None
    episode: int | None


@dataclass
class TorrentCandidate:
    title: str
    info_hash: str
    size_bytes: int
    seeders: int | None
    resolution: str
    cached: bool = False


@dataclass
class PlaybackPayload:
    media_type: str
    imdb_id: str
    season: int | None
    episode: int | None
    info_hash: str
    torrent_title: str


class BackendError(RuntimeError):
    pass


def _b64decode_json(raw: str) -> dict[str, Any]:
    padding = "=" * (-len(raw) % 4)
    return json.loads(base64.b64decode(raw + padding).decode("utf-8"))


def parse_user_config(b64config: str) -> tuple[str, set[str], bool]:
    payload = _b64decode_json(b64config)

    rd_token = ""
    for entry in payload.get("debridServices", []):
        if entry.get("service") == "realdebrid":
            rd_token = str(entry.get("apiKey", "")).strip()
            break
    if not rd_token:
        raise BackendError("Missing Real-Debrid token in request config.")

    enabled_resolutions: set[str] = set()
    for key, enabled in (payload.get("resolutions") or {}).items():
        if not enabled:
            continue
        normalized = RESOLUTION_MAP.get(key.replace("r", "").upper())
        if normalized:
            enabled_resolutions.add(normalized)
    if not enabled_resolutions:
        enabled_resolutions = {"2160p", "1080p"}

    require_english = "en" in set((payload.get("languages") or {}).get("required", []))
    return rd_token, enabled_resolutions, require_english


def parse_media_scope(media_type: str, media_id: str) -> SearchScope:
    if media_type == "series":
        parts = media_id.split(":")
        imdb_id = parts[0]
        season = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else None
        episode = int(parts[2]) if len(parts) >= 3 and parts[2].isdigit() else None
        return SearchScope(media_type=media_type, imdb_id=imdb_id, season=season, episode=episode)
    return SearchScope(media_type=media_type, imdb_id=media_id, season=None, episode=None)


def _torznab_attr(item: ET.Element, name: str) -> str | None:
    namespace = {"torznab": "http://torznab.com/schemas/2015/feed"}
    for attr in item.findall(".//torznab:attr", namespace):
        if attr.get("name") == name:
            return attr.get("value")
    return None


async def fetch_torznab_results(session: aiohttp.ClientSession, imdb_id: str) -> list[dict[str, Any]]:
    headers = {"User-Agent": "Mozilla/5.0"}
    url = f"{settings.stremthru_url}/v0/torznab/api?t=search&imdbid={quote(imdb_id)}"
    timeout = aiohttp.ClientTimeout(total=settings.torznab_timeout_seconds)

    async with session.get(url, headers=headers, timeout=timeout) as response:
        response.raise_for_status()
        xml_text = await response.text()

    root = ET.fromstring(xml_text)
    rows: list[dict[str, Any]] = []
    for item in root.findall(".//item"):
        title = (item.findtext("title") or "").strip()
        info_hash = (_torznab_attr(item, "infohash") or "").strip().lower()
        size_raw = (_torznab_attr(item, "size") or "0").strip()
        seeders_raw = (_torznab_attr(item, "seeders") or "").strip()

        if not title or not info_hash:
            continue
        try:
            size_bytes = int(size_raw)
        except ValueError:
            continue
        if size_bytes <= 0:
            continue

        seeders = None
        if seeders_raw.isdigit():
            seeders = int(seeders_raw)

        rows.append(
            {
                "title": title,
                "info_hash": info_hash,
                "size_bytes": size_bytes,
                "seeders": seeders,
            }
        )
    return rows


def _matches_movie_scope(parsed) -> bool:
    return not parsed.seasons and not parsed.episodes


def _matches_episode_scope(parsed, raw_title: str, season: int, episode: int) -> bool:
    if parsed.seasons and season not in parsed.seasons:
        return False
    if _season_episode_pattern(season, episode).search(raw_title):
        return True
    return parsed.seasons == [season] and parsed.episodes == [episode]


def _matches_season_scope(parsed, raw_title: str, season: int) -> bool:
    if parsed.seasons and season in parsed.seasons and not parsed.episodes:
        return True

    match = SEASON_PATTERN.search(raw_title)
    if not match:
        return False
    try:
        return int(match.group(1)) == season and not parsed.episodes
    except ValueError:
        return False


def _matches_season_file(parsed, raw_title: str, season: int) -> bool:
    if parsed.seasons and season in parsed.seasons:
        return True
    return _matches_season_scope(parsed, raw_title, season)


def _language_allowed(parsed, require_english: bool) -> bool:
    if not require_english:
        return True
    if not parsed.languages:
        return True
    return any(language in {"en", "multi"} for language in parsed.languages)


def _resolution_allowed(parsed, enabled_resolutions: set[str]) -> str | None:
    resolution = RESOLUTION_MAP.get(str(getattr(parsed, "resolution", "")).upper())
    if resolution in enabled_resolutions:
        return resolution
    return None


async def filter_candidates(
    rows: list[dict[str, Any]],
    scope: SearchScope,
    *,
    enabled_resolutions: set[str],
    require_english: bool,
) -> list[TorrentCandidate]:
    titles = [row["title"] for row in rows]
    parsed_results = await asyncio.to_thread(lambda: [parse(title) for title in titles])

    filtered: list[TorrentCandidate] = []
    seen: set[str] = set()
    for row, parsed in zip(rows, parsed_results):
        info_hash = row["info_hash"]
        if info_hash in seen:
            continue

        resolution = _resolution_allowed(parsed, enabled_resolutions)
        if resolution is None:
            continue
        if not _language_allowed(parsed, require_english):
            continue

        if scope.media_type == "movie":
            if not _matches_movie_scope(parsed):
                continue
        elif scope.episode is not None:
            if scope.season is None or not _matches_episode_scope(
                parsed, row["title"], scope.season, scope.episode
            ):
                continue
        else:
            if scope.season is None or not _matches_season_scope(parsed, row["title"], scope.season):
                continue

        seen.add(info_hash)
        filtered.append(
            TorrentCandidate(
                title=row["title"],
                info_hash=info_hash,
                size_bytes=row["size_bytes"],
                seeders=row["seeders"],
                resolution=resolution,
            )
        )
    return filtered


async def mark_cached(
    session: aiohttp.ClientSession, rd_token: str, scope: SearchScope, candidates: list[TorrentCandidate]
) -> None:
    headers = {
        "X-StremThru-Store-Name": "realdebrid",
        "X-StremThru-Store-Authorization": f"Bearer {rd_token}",
        "User-Agent": "comet-minimal",
    }
    timeout = aiohttp.ClientTimeout(total=settings.stremthru_timeout_seconds)
    by_hash = {candidate.info_hash: candidate for candidate in candidates}
    hashes = list(by_hash)

    for offset in range(0, len(hashes), 500):
        chunk = hashes[offset : offset + 500]
        params = {"magnet": ",".join(chunk), "client_ip": "", "sid": scope.imdb_id}
        url = f"{settings.stremthru_url}/v0/store/magnets/check"
        async with session.get(url, headers=headers, params=params, timeout=timeout) as response:
            response.raise_for_status()
            payload = await response.json()

        items = ((payload.get("data") or {}).get("items") or [])
        for item in items:
            candidate = by_hash.get(str(item.get("hash", "")).lower())
            if candidate is not None and item.get("status") == "cached":
                candidate.cached = True


def sort_candidates(candidates: list[TorrentCandidate]) -> list[TorrentCandidate]:
    def sort_key(candidate: TorrentCandidate) -> tuple[int, int, int]:
        return (
            1 if candidate.cached else 0,
            candidate.seeders or 0,
            candidate.size_bytes,
        )

    sorted_candidates = sorted(candidates, key=sort_key, reverse=True)
    per_resolution: dict[str, int] = {}
    limited: list[TorrentCandidate] = []
    for candidate in sorted_candidates:
        count = per_resolution.get(candidate.resolution, 0)
        if count >= settings.max_results_per_resolution:
            continue
        per_resolution[candidate.resolution] = count + 1
        limited.append(candidate)
    return limited


def encode_playback_payload(payload: PlaybackPayload) -> str:
    raw = json.dumps(payload.__dict__, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def decode_playback_payload(raw: str) -> PlaybackPayload:
    payload = _b64decode_json(raw.replace("-", "+").replace("_", "/"))
    return PlaybackPayload(
        media_type=str(payload["media_type"]),
        imdb_id=str(payload["imdb_id"]),
        season=payload.get("season"),
        episode=payload.get("episode"),
        info_hash=str(payload["info_hash"]),
        torrent_title=str(payload["torrent_title"]),
    )


def build_streams(b64config: str, scope: SearchScope, candidates: list[TorrentCandidate]) -> list[dict[str, Any]]:
    streams: list[dict[str, Any]] = []
    for candidate in candidates:
        playback = encode_playback_payload(
            PlaybackPayload(
                media_type=scope.media_type,
                imdb_id=scope.imdb_id,
                season=scope.season,
                episode=scope.episode,
                info_hash=candidate.info_hash,
                torrent_title=candidate.title,
            )
        )
        cache_prefix = "[RD C]" if candidate.cached else "[RD]"
        streams.append(
            {
                "name": f"{cache_prefix} Comet {candidate.resolution}",
                "description": candidate.title,
                "behaviorHints": {"videoSize": candidate.size_bytes},
                "url": f"/{b64config}/playback/{playback}",
            }
        )
    return streams


def _is_video_file(name: str) -> bool:
    return name.lower().endswith(VIDEO_EXTENSIONS)


def _file_match_text(file_info: dict[str, Any]) -> str:
    raw_name = str(file_info.get("name", ""))
    return raw_name.replace("/", " ").replace("\\", " ")


def _episode_sort_key(parsed, filename: str) -> tuple[int, str]:
    if getattr(parsed, "episodes", None):
        try:
            return int(parsed.episodes[0]), filename.lower()
        except (TypeError, ValueError, IndexError):
            pass
    match = re.search(r"\b(?:e|ep|episode)[ ._-]*0*(\d{1,3})\b", filename, re.IGNORECASE)
    if match:
        return int(match.group(1)), filename.lower()
    return 10_000, filename.lower()


def _is_probable_extra(filename: str) -> bool:
    return bool(EXTRA_FILE_PATTERN.search(filename))


async def add_magnet(
    session: aiohttp.ClientSession,
    rd_token: str,
    info_hash: str,
    torrent_title: str,
) -> dict[str, Any]:
    headers = {
        "X-StremThru-Store-Name": "realdebrid",
        "X-StremThru-Store-Authorization": f"Bearer {rd_token}",
        "User-Agent": "comet-minimal",
        "Content-Type": "application/json",
    }
    timeout = aiohttp.ClientTimeout(total=settings.stremthru_timeout_seconds)
    magnet_uri = f"magnet:?xt=urn:btih:{info_hash}&dn={quote(torrent_title)}"
    payload = {"magnet": magnet_uri}

    async with session.post(
        f"{settings.stremthru_url}/v0/store/magnets",
        headers=headers,
        params={"client_ip": ""},
        json=payload,
        timeout=timeout,
    ) as response:
        response.raise_for_status()
        return await response.json()


async def generate_download_link(
    session: aiohttp.ClientSession, rd_token: str, file_link: str
) -> str:
    headers = {
        "X-StremThru-Store-Name": "realdebrid",
        "X-StremThru-Store-Authorization": f"Bearer {rd_token}",
        "User-Agent": "comet-minimal",
        "Content-Type": "application/json",
    }
    timeout = aiohttp.ClientTimeout(total=settings.stremthru_timeout_seconds)

    async with session.post(
        f"{settings.stremthru_url}/v0/store/link/generate",
        headers=headers,
        params={"client_ip": ""},
        json={"link": file_link},
        timeout=timeout,
    ) as response:
        response.raise_for_status()
        payload = await response.json()

    link = ((payload.get("data") or {}).get("link") or "").strip()
    if not link:
        raise BackendError("StremThru did not return a download link.")
    return link


async def choose_files(
    payload: PlaybackPayload,
    files: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    video_files = [item for item in files if _is_video_file(str(item.get("name", "")))]
    if not video_files:
        raise BackendError("No video files were available in the selected torrent.")

    parsed_results = await asyncio.to_thread(
        lambda: [parse(_file_match_text(item)) for item in video_files]
    )

    if payload.media_type == "series" and payload.season is not None and payload.episode is None:
        season_matches: list[tuple[tuple[int, str], dict[str, Any]]] = []
        for file_info, parsed in zip(video_files, parsed_results):
            match_text = _file_match_text(file_info)
            filename = PurePosixPath(str(file_info.get("name", ""))).name
            if not _matches_season_file(parsed, match_text, payload.season):
                continue
            season_matches.append((_episode_sort_key(parsed, filename), file_info))

        if season_matches:
            season_matches.sort(key=lambda item: item[0])
            return [file_info for _, file_info in season_matches]

        torrent_parsed = await asyncio.to_thread(lambda: parse(payload.torrent_title))
        if _matches_season_scope(torrent_parsed, payload.torrent_title, payload.season):
            season_pack_matches: list[tuple[tuple[int, str], dict[str, Any]]] = []
            for file_info, parsed in zip(video_files, parsed_results):
                filename = PurePosixPath(str(file_info.get("name", ""))).name
                if _is_probable_extra(filename):
                    continue
                season_pack_matches.append((_episode_sort_key(parsed, filename), file_info))

            if season_pack_matches:
                season_pack_matches.sort(key=lambda item: item[0])
                return [file_info for _, file_info in season_pack_matches]

    scored: list[tuple[float, dict[str, Any]]] = []
    for file_info, parsed in zip(video_files, parsed_results):
        filename = PurePosixPath(str(file_info.get("name", ""))).name
        match_text = _file_match_text(file_info)
        size = int(file_info.get("size") or 0)
        score = 0.0

        if payload.media_type == "movie":
            if parsed.seasons or parsed.episodes:
                continue
            score += 50
        elif payload.episode is not None:
            if payload.season is None or not _matches_episode_scope(
                parsed, match_text, payload.season, payload.episode
            ):
                continue
            score += 1000
            if parsed.seasons and parsed.episodes:
                score += 100
        else:
            if payload.season is None:
                continue
            if parsed.seasons and payload.season in parsed.seasons:
                score += 400
                if not parsed.episodes:
                    score += 600
            elif not _matches_season_scope(parsed, match_text, payload.season):
                continue

        score += min(size / (1024**3), 100)
        scored.append((score, file_info))

    if scored:
        scored.sort(key=lambda item: item[0], reverse=True)
        return [scored[0][1]]

    return [max(video_files, key=lambda item: int(item.get("size") or 0))]
