import base64
import json
import os
from glob import escape as glob_escape
import re
import threading
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlencode, urlparse
from urllib.request import Request, urlopen

from comet import metadata
from comet import subtitles as subs
from comet.config import settings
from comet.library import Library
from scripts import comet_search_download as cli

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
SEASON_EPISODE_PATTERN = re.compile(r"\bs0*(\d{1,2})e0*(\d{1,3})(?!\d)", re.IGNORECASE)
# A season pack named in a movie result: "S02", or "Season 2" unless a year follows it
# ("Open.Season.2.2008" is the film Open Season 2, "Open.Season.2006" has no season number at all).
MOVIE_SEASON_PACK_PATTERN = re.compile(
    r"\bs\d{1,2}\b|\bseason[ ._-]*\d{1,2}\b(?![ ._-]*\(?(?:19|20)\d{2}\b)",
    re.IGNORECASE,
)
SEASON_PATTERN = re.compile(r"\b(?:season[ ._-]*|s\s*)0*(\d{1,2})\b", re.IGNORECASE)
EXTRA_FILE_PATTERN = re.compile(
    r"\b(?:sample|trailer|preview|teaser|creditless|menu|extras?)\b",
    re.IGNORECASE,
)


def _media_to_dict(media: cli.MediaCandidate) -> dict[str, Any]:
    return {
        "media_type": media.media_type,
        "tmdb_id": media.tmdb_id,
        "title": media.title,
        "year": media.year,
        "imdb_id": media.imdb_id,
    }


def _media_from_dict(value: dict[str, Any]) -> cli.MediaCandidate:
    return cli.MediaCandidate(
        media_type=str(value["media_type"]),
        tmdb_id=int(value["tmdb_id"]),
        title=str(value["title"]),
        year=str(value.get("year") or "?"),
        imdb_id=str(value["imdb_id"]),
    )


def _stream_to_dict(stream: cli.StreamCandidate) -> dict[str, Any]:
    return {
        "index": stream.index,
        "resolution": stream.resolution,
        "is_cached": stream.is_cached,
        "size_bytes": stream.size_bytes,
        "name": stream.name,
        "description": stream.description,
        "playback_url": stream.playback_url,
        "is_strict_match": stream.is_strict_match,
        "preferred_distance_bytes": stream.preferred_distance_bytes,
        "fallback_reason": stream.fallback_reason,
        "has_subtitle_hint": stream.has_subtitle_hint,
    }


def _stream_from_dict(value: dict[str, Any]) -> cli.StreamCandidate:
    return cli.StreamCandidate(
        index=int(value["index"]),
        resolution=str(value["resolution"]),
        is_cached=bool(value["is_cached"]),
        size_bytes=int(value["size_bytes"]),
        name=str(value["name"]),
        description=str(value.get("description") or ""),
        playback_url=str(value["playback_url"]),
        is_strict_match=bool(value["is_strict_match"]),
        preferred_distance_bytes=int(value.get("preferred_distance_bytes") or 0),
        fallback_reason=str(value.get("fallback_reason") or ""),
        has_subtitle_hint=bool(value.get("has_subtitle_hint") or False),
    )


def _job_to_dict(job: cli.DownloadJobSnapshot) -> dict[str, Any]:
    files = _video_files(job)
    return {
        "job_id": job.job_id,
        "label": job.label,
        "status": job.status,
        "phase": job.phase,
        "created_at": job.created_at,
        "started_at": job.started_at,
        "finished_at": job.finished_at,
        "total_files": job.total_files,
        "completed_files": job.completed_files,
        "current_file_index": job.current_file_index,
        "current_file_name": job.current_file_name,
        "total_bytes": job.total_bytes,
        "downloaded_bytes": job.downloaded_bytes,
        "speed_bytes_per_second": job.speed_bytes_per_second,
        "eta_seconds": job.eta_seconds,
        "destinations": [str(path) for path in job.destinations],
        "error_text": friendly_error(job.error_text) if job.error_text else "",
        "cancel_requested": job.cancel_requested,
        "output_dir": str(job.output_dir),
        "final_bytes": sum(item["bytes"] for item in files),
        "files": files,
        "subtitle_status": job.subtitle_status,
        "subtitle_message": job.subtitle_message,
        "embedded_subtitles": list(job.embedded_subtitles),
        "media_type": job.media_type,
        "imdb_id": job.imdb_id,
        "season": job.season,
        "episode": job.episode,
        "title": job.title,
    }


def _video_files(job: cli.DownloadJobSnapshot) -> list[dict[str, Any]]:
    """The videos of a finished download: where each one is, which episode it is and its size."""
    if job.status != "completed":
        return []
    files: list[dict[str, Any]] = []
    for path in job.destinations:
        if not _is_video_file(path.name):
            continue
        season, episode = 0, 0
        if job.media_type == "series":
            # The file name knows best (a season pack is one job with many episodes); what
            # was asked for fills in whatever the name doesn't say.
            named_season, named_episode = subs.season_episode_from_filename(path)
            if named_episode is None:
                named_episode = subs.episode_number_from_filename(path)
            season = named_season or job.season or 0
            episode = named_episode or job.episode or 0
        try:
            size = path.stat().st_size
        except OSError:
            size = 0
        files.append({"path": str(path), "season": season, "episode": episode, "bytes": size})
    return files


_URL_PATTERN = re.compile(r"(?:https?|wss?)://\S+")
_NETWORK_HINTS = (
    "timed out", "name or service not known", "temporary failure in name resolution",
    "network is unreachable", "connection refused", "connection reset", "no address associated",
    "failed to establish", "connectionerror", "remote end closed",
)


def friendly_error(error: BaseException | str) -> str:
    """Turns raw exceptions into one short line: no URLs, tokens or stack noise."""
    text = str(error).strip() or getattr(error, "__class__", type("x", (), {})).__name__
    match = re.search(r"HTTP (\d{3})", text)
    code = int(match.group(1)) if match else getattr(error, "code", None)
    lower = text.lower()
    if code in (401, 403):
        # Only the debrid store API (StremThru /v0/store/...) answers for the token. A 403 from the
        # file host or a metadata site says nothing about it.
        if "/v0/store/" in lower:
            return "Your debrid token was rejected. Check it in Settings."
        if "download server" in lower:
            return f"The download link was refused (HTTP {code}). Retry to get a fresh link."
        return f"The server refused the request (HTTP {code}). Try again in a bit."
    if code == 429:
        return "Too many requests right now. Wait a minute and try again."
    if isinstance(code, int) and code >= 500:
        return f"The server had a problem (HTTP {code}). Try again in a bit."
    if any(hint in lower for hint in _NETWORK_HINTS):
        return "Network problem: couldn't reach the server. Check your connection."
    if "no space left" in lower:
        return "Not enough free space on the device to finish this download."
    if "permission denied" in lower or "operation not permitted" in lower:
        return "Can't write to the Download folder. Allow file access for Comet and try again."
    text = _URL_PATTERN.sub("", text)
    text = re.sub(r"\b(?:GET|POST)\s+failed", "Request failed", text)
    text = re.sub(r"\s+", " ", text).strip(" :")
    return text[:200] + ("…" if len(text) > 200 else "")


def _friendly(method):
    def wrapper(*args, **kwargs):
        try:
            return method(*args, **kwargs)
        except Exception as error:
            raise RuntimeError(friendly_error(error)) from None

    wrapper.__name__ = method.__name__
    wrapper.__doc__ = method.__doc__
    return wrapper


def _extract_playback_token(playback_url: str) -> str:
    path = urlparse(playback_url).path or playback_url
    parts = [part for part in path.split("/") if part]
    if len(parts) < 3 or parts[-2] != "playback":
        raise RuntimeError("Stream candidate did not include a playback token.")
    return parts[-1]


def _b64decode_json(raw: str) -> dict[str, Any]:
    padding = "=" * (-len(raw) % 4)
    return json.loads(base64.b64decode(raw + padding).decode("utf-8"))


def _parse_config(b64config: str) -> tuple[str, str]:
    """Provider and token of a stream config. Links listed by this app carry no token (see
    [_fetch_streams_direct]); the token is then empty and comes from Settings instead."""
    payload = _b64decode_json(b64config)
    for entry in payload.get("debridServices", []):
        provider = str(entry.get("service", "")).strip().lower()
        token = str(entry.get("apiKey", "")).strip()
        if provider in cli.DEBRID_PROVIDERS:
            return provider, token
    raise RuntimeError("Missing supported debrid provider in stream config.")


def _strip_link_token(playback_url: str) -> str:
    """[playback_url] without the debrid token older versions put in its config part, so the
    token is never written to the library file. Links that already have none come back as is."""
    parts = playback_url.split("/")
    index = next((i for i, part in enumerate(parts) if part), None)
    if index is None:
        return playback_url
    try:
        provider, token = _parse_config(parts[index])
    except Exception:
        return playback_url
    if not token:
        return playback_url
    parts[index] = cli.build_b64_config(provider, "")
    return "/".join(parts)


def _decode_playback_payload(raw: str) -> dict[str, Any]:
    payload = _b64decode_json(raw.replace("-", "+").replace("_", "/"))
    return {
        "media_type": str(payload["media_type"]),
        "imdb_id": str(payload["imdb_id"]),
        "season": payload.get("season"),
        "episode": payload.get("episode"),
        "info_hash": str(payload["info_hash"]),
        "torrent_title": str(payload["torrent_title"]),
    }


def _torznab_attr(item: ET.Element, name: str) -> str | None:
    namespace = {"torznab": "http://torznab.com/schemas/2015/feed"}
    for attr in item.findall(".//torznab:attr", namespace):
        if attr.get("name") == name:
            return attr.get("value")
    return None


def _fetch_torznab_results(imdb_id: str) -> list[dict[str, Any]]:
    request = Request(
        f"{settings.stremthru_url}/v0/torznab/api?t=search&imdbid={quote(imdb_id)}",
        headers={"User-Agent": "Mozilla/5.0"},
    )
    with urlopen(request, timeout=settings.torznab_timeout_seconds) as response:
        root = ET.fromstring(response.read().decode("utf-8"))

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
        rows.append(
            {
                "title": title,
                "info_hash": info_hash,
                "size_bytes": size_bytes,
                "seeders": int(seeders_raw) if seeders_raw.isdigit() else 0,
            }
        )
    return rows


def _matches_scope(title: str, media_type: str, season: int | None, episode: int | None) -> bool:
    if media_type == "movie":
        # Only episode and season-pack names are out; the bare word "season" is fine
        # (Open Season, Season of the Witch).
        return SEASON_EPISODE_PATTERN.search(title) is None and MOVIE_SEASON_PACK_PATTERN.search(title) is None
    if season is None:
        return False
    if episode is not None:
        # Also matches the later half of a double episode: S01E01E02, S01E01-E02.
        return bool(
            re.search(
                rf"\bs0*{season}(?:e\d{{1,3}}-?)*e0*{episode}(?!\d)|\b{season}x0*{episode}\b",
                title,
                re.IGNORECASE,
            )
        )
    season_match = SEASON_PATTERN.search(title)
    if season_match:
        return int(season_match.group(1)) == season and SEASON_EPISODE_PATTERN.search(title) is None
    return bool(re.search(rf"\bs0*{season}\b", title, re.IGNORECASE)) and SEASON_EPISODE_PATTERN.search(title) is None


def _filter_candidates(
    rows: list[dict[str, Any]],
    media_type: str,
    season: int | None,
    episode: int | None,
) -> list[dict[str, Any]]:
    seen: set[str] = set()
    filtered: list[dict[str, Any]] = []
    for row in rows:
        info_hash = row["info_hash"]
        if info_hash in seen:
            continue
        resolution = cli.normalize_resolution(row["title"])
        if resolution is None:
            continue
        if not _matches_scope(row["title"], media_type, season, episode):
            continue
        seen.add(info_hash)
        filtered.append({**row, "resolution": "2160p" if resolution == "4K" else "1080p", "cached": False})
    return filtered


def _mark_cached(provider: str, token: str, imdb_id: str, candidates: list[dict[str, Any]]) -> None:
    by_hash = {candidate["info_hash"]: candidate for candidate in candidates}
    hashes = list(by_hash)
    headers = {
        "X-StremThru-Store-Name": provider,
        "X-StremThru-Store-Authorization": f"Bearer {token}",
        "User-Agent": "comet-minimal",
    }
    for offset in range(0, len(hashes), 500):
        params = urlencode({"magnet": ",".join(hashes[offset : offset + 500]), "client_ip": "", "sid": imdb_id})
        payload = cli.http_json(f"{settings.stremthru_url}/v0/store/magnets/check?{params}", headers=headers)
        for item in ((payload.get("data") or {}).get("items") or []):
            candidate = by_hash.get(str(item.get("hash", "")).lower())
            if candidate is not None and item.get("status") == "cached":
                candidate["cached"] = True


def _sort_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    ordered = sorted(
        candidates,
        key=lambda item: (1 if item.get("cached") else 0, int(item.get("seeders") or 0), int(item.get("size_bytes") or 0)),
        reverse=True,
    )
    counts: dict[str, int] = {}
    limited: list[dict[str, Any]] = []
    for item in ordered:
        resolution = str(item["resolution"])
        if counts.get(resolution, 0) >= settings.max_results_per_resolution:
            continue
        counts[resolution] = counts.get(resolution, 0) + 1
        limited.append(item)
    return limited


def _encode_playback_payload(payload: dict[str, Any]) -> str:
    raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _build_streams(
    b64config: str,
    provider: str,
    media_type: str,
    imdb_id: str,
    season: int | None,
    episode: int | None,
    candidates: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    stream_prefix = str(cli.DEBRID_PROVIDERS[provider]["stream_prefix"])
    streams: list[dict[str, Any]] = []
    for candidate in candidates:
        playback = _encode_playback_payload(
            {
                "media_type": media_type,
                "imdb_id": imdb_id,
                "season": season,
                "episode": episode,
                "info_hash": candidate["info_hash"],
                "torrent_title": candidate["title"],
            }
        )
        cache_prefix = f"[{stream_prefix} C]" if candidate.get("cached") else f"[{stream_prefix}]"
        streams.append(
            {
                "name": f"{cache_prefix} Comet {candidate['resolution']}",
                "description": candidate["title"],
                "behaviorHints": {"videoSize": candidate["size_bytes"]},
                "url": f"/{b64config}/playback/{playback}",
            }
        )
    return streams


def _fetch_streams_direct(
    provider: str,
    token: str,
    media_type: str,
    media_id: str,
) -> list[dict[str, Any]]:
    # The links only name the provider. They end up in the library file on the device, so the
    # token stays out of them and is read from Settings when a download starts (_parse_playback).
    b64config = cli.build_b64_config(provider, "")
    parts = media_id.split(":")
    imdb_id = parts[0]
    season = int(parts[1]) if len(parts) >= 2 and parts[1].isdigit() else None
    episode = int(parts[2]) if len(parts) >= 3 and parts[2].isdigit() else None
    rows = _fetch_torznab_results(imdb_id)
    candidates = _filter_candidates(rows, media_type, season, episode)
    _mark_cached(provider, token, imdb_id, candidates)
    ordered = _sort_candidates(candidates)
    return _build_streams(b64config, provider, media_type, imdb_id, season, episode, ordered)


def _is_video_file(name: str) -> bool:
    return name.lower().endswith(VIDEO_EXTENSIONS)


def _sidecars_of(video: Path, candidates) -> list[Path]:
    """The non-video files among [candidates] that are named after [video] ("Movie.en.srt" for
    "Movie.mkv"). A file that fits a longer-named video next to it belongs to that one instead:
    "Movie.Extended.en.srt" goes with "Movie.Extended.mkv", not with "Movie.mkv"."""
    try:
        longer_stems = [
            other.stem
            for other in video.parent.iterdir()
            if _is_video_file(other.name) and other.stem.startswith(video.stem + ".")
        ]
    except OSError:
        longer_stems = []
    prefix = video.stem + "."
    return [
        path
        for path in candidates
        if path.parent == video.parent
        and path.name.startswith(prefix)
        and not _is_video_file(path.name)
        and not any(path.name.startswith(stem + ".") for stem in longer_stems)
    ]


def _season_pack_sort_key(file_info: dict[str, Any]) -> tuple[int, str]:
    name = os.path.basename(str(file_info.get("name", ""))).lower()
    match = re.search(
        r"\bs\d{1,2}e0*(\d{1,3})\b|\b\d{1,2}x0*(\d{1,3})\b|\b(?:e|ep|episode)[ ._-]*0*(\d{1,3})\b",
        name,
        re.IGNORECASE,
    )
    if match:
        for group in match.groups():
            if group:
                return int(group), name

    leading_number = re.match(r"^\D*0*(\d{1,3})(?:\D|$)", name)
    if leading_number:
        return int(leading_number.group(1)), name

    return 10_000, name


def _choose_files(payload: dict[str, Any], files: list[dict[str, Any]]) -> list[dict[str, Any]]:
    video_files = [item for item in files if _is_video_file(str(item.get("name", "")))]
    if not video_files:
        raise RuntimeError("No video files were available in the selected torrent.")
    season = payload.get("season")
    episode = payload.get("episode")
    if payload.get("media_type") == "series" and season:
        if episode:
            matches = [
                item
                for item in video_files
                if re.search(rf"\bs0*{season}e0*{episode}\b|\b{season}x0*{episode}\b", str(item.get("name", "")), re.IGNORECASE)
            ]
            if matches:
                return sorted(matches, key=lambda item: int(item.get("size") or 0), reverse=True)[:1]
        else:
            matches = [
                item
                for item in video_files
                if not EXTRA_FILE_PATTERN.search(os.path.basename(str(item.get("name", ""))))
                and (
                    re.search(rf"\bs0*{season}e\d{{1,3}}\b|\b{season}x\d{{1,3}}\b", str(item.get("name", "")), re.IGNORECASE)
                    or re.search(rf"\bseason[ ._-]*0*{season}\b", str(item.get("name", "")), re.IGNORECASE)
                )
            ]
            if matches:
                return sorted(matches, key=_season_pack_sort_key)

            season_pack_files = [
                item
                for item in video_files
                if not EXTRA_FILE_PATTERN.search(os.path.basename(str(item.get("name", ""))))
            ]
            if len(season_pack_files) > 1:
                return sorted(season_pack_files, key=_season_pack_sort_key)
    return [max(video_files, key=lambda item: int(item.get("size") or 0))]


def _add_magnet(provider: str, token: str, info_hash: str, torrent_title: str) -> dict[str, Any]:
    headers = {
        "X-StremThru-Store-Name": provider,
        "X-StremThru-Store-Authorization": f"Bearer {token}",
        "User-Agent": "comet-minimal",
        "Content-Type": "application/json",
    }
    magnet_uri = f"magnet:?xt=urn:btih:{info_hash}&dn={quote(torrent_title)}"
    return cli.http_post_json(
        f"{settings.stremthru_url}/v0/store/magnets?client_ip=",
        {"magnet": magnet_uri},
        headers=headers,
        timeout=settings.stremthru_timeout_seconds,
    )


def _generate_download_link(provider: str, token: str, file_link: str) -> str:
    headers = {
        "X-StremThru-Store-Name": provider,
        "X-StremThru-Store-Authorization": f"Bearer {token}",
        "User-Agent": "comet-minimal",
        "Content-Type": "application/json",
    }
    payload = cli.http_post_json(
        f"{settings.stremthru_url}/v0/store/link/generate?client_ip=",
        {"link": file_link},
        headers=headers,
        timeout=settings.stremthru_timeout_seconds,
    )
    link = ((payload.get("data") or {}).get("link") or "").strip()
    if not link:
        raise RuntimeError("StremThru did not return a download link.")
    return link


ANDROID_STREAM_LIMIT = 12
PROVIDER_LABELS = {"realdebrid": "Real-Debrid", "torbox": "TorBox"}
UNCACHED_POLL_SECONDS = 30
UNCACHED_MAX_WAIT_SECONDS = 6 * 3600
# Polls in a row that may be refused (HTTP 4xx) before the wait is given up.
UNCACHED_MAX_REFUSED_POLLS = 4
_READY_STATUSES = {"cached", "downloaded"}
_DEAD_STATUSES = {"failed", "invalid"}


def _store_headers(provider: str, token: str) -> dict[str, str]:
    return {
        "X-StremThru-Store-Name": provider,
        "X-StremThru-Store-Authorization": f"Bearer {token}",
        "User-Agent": "comet-minimal",
    }


def _get_magnet(provider: str, token: str, magnet_id: str) -> dict[str, Any]:
    return cli.http_json(
        f"{settings.stremthru_url}/v0/store/magnets/{quote(magnet_id)}?client_ip=",
        headers=_store_headers(provider, token),
        timeout=settings.stremthru_timeout_seconds,
    )


def _parse_playback(playback_url: str) -> tuple[str, str, dict[str, Any]]:
    token = _extract_playback_token(playback_url)
    path = urlparse(playback_url).path or playback_url
    b64config = [part for part in path.split("/") if part][0]
    provider, provider_token = _parse_config(b64config)
    # The link names the provider only; the token is the one in Settings right now, so a token
    # corrected there also applies to queued jobs and retries.
    current = _api
    if current is not None and current.provider == provider and current.token:
        provider_token = current.token
    if not provider_token:
        label = PROVIDER_LABELS.get(provider, provider)
        raise RuntimeError(f"This was listed with {label}. Select {label} and enter its token in Settings.")
    return provider, provider_token, _decode_playback_payload(token)


def _wait_for_magnet(
    provider: str,
    provider_token: str,
    payload: dict[str, Any],
    magnet: dict[str, Any],
    *,
    on_wait,
    cancel_event: threading.Event | None = None,
) -> dict[str, Any]:
    """Polls the debrid service until it has fetched the torrent; returns the ready magnet."""
    label = PROVIDER_LABELS.get(provider, "your debrid service")
    status = str(magnet.get("status", "")).lower()
    started = time.time()
    refused_polls = 0
    while status not in _READY_STATUSES:
        if status in _DEAD_STATUSES:
            raise RuntimeError(f"{label} couldn't fetch this torrent (status: {status}). Try another stream.")
        if time.time() - started > UNCACHED_MAX_WAIT_SECONDS:
            raise RuntimeError(f"Gave up after waiting 6 hours for {label} to fetch this torrent.")
        on_wait(label, status or "queued")
        if cancel_event is not None:
            if cancel_event.wait(UNCACHED_POLL_SECONDS):
                raise cli.DownloadCancelled()
        else:
            time.sleep(UNCACHED_POLL_SECONDS)
        magnet_id = str(magnet.get("id") or "")
        try:
            refreshed = _get_magnet(provider, provider_token, magnet_id) if magnet_id else _add_magnet(
                provider, provider_token, payload["info_hash"], payload["torrent_title"]
            )
        except Exception as error:
            # Timeouts, dropped connections and server hiccups are expected over a wait of
            # hours (they don't all surface as RuntimeError), so keep waiting. A request the
            # service keeps refusing (token revoked, torrent removed) won't fix itself.
            text = str(error)
            refused = re.search(r"HTTP 4\d\d", text) and "HTTP 429" not in text
            refused_polls = refused_polls + 1 if refused else 0
            if refused_polls >= UNCACHED_MAX_REFUSED_POLLS:
                if re.search(r"HTTP 40[13]", text):
                    raise  # reported as a rejected token
                raise RuntimeError(
                    f"{label} no longer lists this torrent, so the wait was stopped. Try again or pick another stream."
                ) from None
            continue
        refused_polls = 0
        magnet = refreshed.get("data") or magnet
        status = str(magnet.get("status", "")).lower()
    return magnet


def _resolve_download_links(
    playback_url: str,
    *,
    on_wait=None,
    cancel_event: threading.Event | None = None,
    defer: bool = False,
) -> list[tuple[str, str]]:
    """Download links for the stream's files. An uncached torrent is added to the debrid
    account; with [on_wait] we then poll until the service has fetched it. With [defer] that
    wait is handed to the download manager (cli.JobDeferred) instead of blocking the caller,
    which simply asks again once the wait is over."""
    provider, provider_token, payload = _parse_playback(playback_url)
    added = _add_magnet(provider, provider_token, payload["info_hash"], payload["torrent_title"])
    magnet = added.get("data") or {}
    status = str(magnet.get("status", "")).lower()
    label = PROVIDER_LABELS.get(provider, "your debrid service")
    if status not in _READY_STATUSES:
        if status in _DEAD_STATUSES:
            raise RuntimeError(f"{label} couldn't fetch this torrent (status: {status}). Try another stream.")
        if on_wait is None:
            raise RuntimeError(
                f"Not cached yet. {label} has started fetching it"
                f" (status: {status or 'unknown'}); retry once it's ready."
            )

        def wait() -> dict[str, Any]:
            return _wait_for_magnet(
                provider, provider_token, payload, magnet, on_wait=on_wait, cancel_event=cancel_event
            )

        if defer:
            raise cli.JobDeferred(wait)
        magnet = wait()
    target_files = _choose_files(payload, magnet.get("files") or [])
    downloads: list[tuple[str, str]] = []
    for target_file in target_files:
        file_link = str(target_file.get("link", "")).strip()
        if file_link:
            downloads.append(
                (
                    _generate_download_link(provider, provider_token, file_link),
                    str(target_file.get("name", "")).strip(),
                )
            )
    if not downloads:
        raise RuntimeError("The selected torrent files did not contain downloadable links.")
    return downloads


def _download_selected_stream_direct(
    host: str,
    candidate: cli.StreamCandidate,
    output_dir: Path,
    fallback_name: str,
    *,
    reporter: cli.JobReporter | None = None,
    cancel_event: threading.Event | None = None,
) -> list[Path]:
    if reporter is not None:
        reporter.ensure_not_cancelled()
        reporter.set_resolving()
    if cancel_event is not None and cancel_event.is_set():
        raise cli.DownloadCancelled()

    def on_wait(label: str, status: str) -> None:
        if reporter is not None:
            reporter.update_job_phase(f"Waiting for {label} to fetch it ({status})")

    # Under the download manager an uncached torrent is waited for off the worker pool
    # (JobDeferred), so it doesn't block a download slot for hours.
    downloads = _resolve_download_links(
        candidate.playback_url, on_wait=on_wait, cancel_event=cancel_event, defer=reporter is not None
    )
    if reporter is not None:
        reporter.set_resolving()
        reporter.set_total_files(len(downloads))

    destinations: list[Path] = []
    for index, (download_url, original_name) in enumerate(downloads, start=1):
        if cancel_event is not None and cancel_event.is_set():
            raise cli.DownloadCancelled()
        per_file_name = cli.build_file_fallback_name(original_name, fallback_name)
        destination = cli.download_from_url_with_progress(
            download_url,
            output_dir,
            per_file_name,
            reporter=reporter,
            cancel_event=cancel_event,
            completed_files=index - 1,
            total_files=len(downloads),
        )
        destinations.append(destination)
        if reporter is not None:
            reporter.finish_file(destination, index, len(downloads))
    return destinations


class AndroidCometApi:
    def __init__(self, download_dir: str, max_parallel: int = 2, state_dir: str = "") -> None:
        self.provider = ""
        self.token = ""
        self.subtitles = subs.SubtitleConfig()
        self.download_dir = Path(download_dir).expanduser()
        self.events: list[dict[str, Any]] = []
        self._event_lock = threading.Lock()
        # job_id -> the request that created it, so failed jobs can be retried.
        self._requests: dict[int, tuple[str, str, int | None, int | None]] = {}
        self._requests_lock = threading.Lock()
        self._sweep_stale_partials()
        cli.download_selected_stream = _download_selected_stream_direct
        # Finished downloads are kept on disk so they are still listed after a restart.
        self.library = Library(Path(state_dir) / "library.json") if state_dir else None
        # Plot, rating and artwork of every title in the library, kept for offline use.
        self.meta = metadata.MetaStore(Path(state_dir) / "metadata") if state_dir else None
        self._restoring = True
        self.manager = cli.DownloadManager(
            max_parallel, event_callback=self._record_event, change_callback=self._save_library
        )
        self._restore_library()
        self._restoring = False
        if self._library_needs_rewrite:
            self._save_library()

    def _restore_library(self) -> None:
        self._library_needs_rewrite = False
        if self.library is None:
            return
        for record in self.library.load():
            try:
                request = record["request"]
                # Files written before 1.0 kept the debrid token inside the link: drop it and
                # rewrite the file without it.
                link = str(request["stream"]["playback_url"])
                clean_link = _strip_link_token(link)
                if clean_link != link:
                    request["stream"]["playback_url"] = clean_link
                    self._library_needs_rewrite = True
                media_json = json.dumps(request["media"])
                stream_json = json.dumps(request["stream"])
                season, episode = request.get("season"), request.get("episode")
                files = {int(file_id): Path(path) for file_id, path in (record.get("subtitle_files") or {}).items()}
                job = self.manager.restore_completed(
                    label=str(record["label"]),
                    candidate=_stream_from_dict(request["stream"]),
                    subtitles=self.subtitles,
                    media=_media_from_dict(request["media"]),
                    season=season,
                    episode=episode,
                    output_dir=Path(record["output_dir"]),
                    fallback_name=str(record["label"]),
                    created_at=float(record.get("created_at") or 0),
                    finished_at=float(record.get("finished_at") or 0),
                    destinations=[Path(path) for path in record["destinations"]],
                    total_files=int(record.get("total_files") or 1),
                    completed_files=int(record.get("total_files") or 1),
                    subtitle_status=str(record.get("subtitle_status") or ""),
                    subtitle_message=str(record.get("subtitle_message") or ""),
                    embedded_subtitles=list(record.get("embedded_subtitles") or []),
                    subtitle_file_ids=[int(file_id) for file_id in record.get("subtitle_file_ids") or []],
                    subtitle_files={file_id: path for file_id, path in files.items() if path.exists()},
                    subtitle_sidecars={
                        Path(item["video"]): (Path(item["path"]), str(item["language"]))
                        for item in record.get("sidecars") or []
                    },
                )
            except (KeyError, TypeError, ValueError):
                continue  # a record from an incompatible version: skip it, keep the rest
            self._requests[job.job_id] = (media_json, stream_json, season, episode)

    def _save_library(self) -> None:
        if self.library is None or self._restoring:
            return

        def describe(job: cli.DownloadJob) -> dict[str, Any] | None:
            request = self._requests.get(job.job_id)
            if request is None:
                return None
            media_json, stream_json, season, episode = request
            return {
                "request": {
                    "media": json.loads(media_json),
                    "stream": json.loads(stream_json),
                    "season": season,
                    "episode": episode,
                },
                "label": job.label,
                "output_dir": str(job.output_dir),
                "destinations": [str(path) for path in job.destinations],
                "created_at": job.created_at,
                "finished_at": job.finished_at,
                "total_files": job.total_files,
                # A lookup that is still running is simply not remembered as such.
                "subtitle_status": "" if job.subtitle_status == "searching" else job.subtitle_status,
                "subtitle_message": "" if job.subtitle_status == "searching" else job.subtitle_message,
                "embedded_subtitles": list(job.embedded_subtitles),
                "subtitle_file_ids": list(job.subtitle_file_ids),
                "subtitle_files": {str(file_id): str(path) for file_id, path in job.subtitle_files.items()},
                "sidecars": [
                    {"video": str(video), "path": str(path), "language": language}
                    for video, (path, language) in job.subtitle_sidecars.items()
                ],
            }

        self.library.save([record for record in self.manager.map_completed(describe) if record is not None])

    def _sweep_stale_partials(self) -> None:
        """Staging dirs left by a killed process (jobs never survive a restart)."""
        try:
            stale = list(self.download_dir.glob(".*.job-*.partial"))
        except OSError:
            return
        for path in stale:
            cli.cleanup_download_dir(path)
        if stale:
            self._record_event("info", f"Removed {len(stale)} unfinished download folder(s) from a previous session.")

    def _record_event(self, level: str, message: str) -> None:
        with self._event_lock:
            self.events.append({"level": level, "message": message, "created_at": time.time()})
            del self.events[:-60]

    def configure(
        self,
        provider: str,
        token: str,
        subtitle_languages: str = "",
        subtitles_enabled: bool = True,
    ) -> str:
        provider = provider.strip().lower()
        if provider not in cli.DEBRID_PROVIDERS:
            raise RuntimeError("Choose Real-Debrid or TorBox.")
        token = token.strip()
        if not token:
            raise RuntimeError("A provider API token is required.")
        self.provider = provider
        self.token = token
        self.subtitles = subs.SubtitleConfig(
            languages=subs.parse_subtitle_languages(subtitle_languages),
            enabled=bool(subtitles_enabled),
        )
        return "ok"

    @_friendly
    def search(self, query: str) -> str:
        query = query.strip()
        if not query:
            raise RuntimeError("Enter a search query.")
        try:
            return json.dumps(metadata.search(query))
        except Exception as error:
            self._record_event("warning", f"Cinemeta search failed, using TMDB: {error}")
        return json.dumps([_media_to_dict(media) for media in cli.search_tmdb(query)])

    @_friendly
    def details(self, media_json: str, refresh: bool = False) -> str:
        media = _media_from_dict(json.loads(media_json))
        return json.dumps(metadata.details(media.media_type, media.imdb_id, bool(refresh)))

    @_friendly
    def streams(
        self,
        media_json: str,
        season: int | None = None,
        episode: int | None = None,
    ) -> str:
        if not self.provider or not self.token:
            raise RuntimeError("Provider credentials are not configured.")

        media = _media_from_dict(json.loads(media_json))
        media_id = media.imdb_id
        if media.media_type == "series":
            if season is None or int(season) <= 0:
                raise RuntimeError("Season number is required for series.")
            media_id = f"{media_id}:{int(season)}"
            if episode is not None and int(episode) > 0:
                media_id = f"{media_id}:{int(episode)}"

        raw_streams = _fetch_streams_direct(self.provider, self.token, media.media_type, media_id)
        context = cli.SizePreferenceContext(
            media_type=media.media_type,
            season=int(season) if season is not None and int(season) > 0 else None,
            episode=int(episode) if episode is not None and int(episode) > 0 else None,
        )
        grouped = cli.group_top_streams(cli.build_stream_candidates(raw_streams, context), limit=ANDROID_STREAM_LIMIT)
        return json.dumps([_stream_to_dict(stream) for stream in grouped])

    @_friendly
    def enqueue(
        self,
        media_json: str,
        stream_json: str,
        season: int | None = None,
        episode: int | None = None,
    ) -> str:
        media = _media_from_dict(json.loads(media_json))
        stream_dict = json.loads(stream_json)
        # Whatever built the link, the copy kept for retries and the library file has no token.
        stream_dict["playback_url"] = _strip_link_token(str(stream_dict["playback_url"]))
        stream_json = json.dumps(stream_dict)
        stream = _stream_from_dict(stream_dict)
        actual_season = int(season) if season is not None and int(season) > 0 else None
        actual_episode = int(episode) if episode is not None and int(episode) > 0 else None
        preferred_name = cli.build_preferred_filename_base(
            media,
            stream.resolution,
            season=actual_season,
            episode=actual_episode,
        )
        target_dir = self.download_dir / cli.build_collection_dir_name(media, season=actual_season)
        with self._requests_lock:  # a concurrent clear_finished must not drop the new entry
            job = self.manager.enqueue(
                label=preferred_name,
                host="",
                candidate=stream,
                subtitles=self.subtitles,
                media=media,
                season=actual_season,
                episode=actual_episode,
                output_dir=target_dir,
                fallback_name=preferred_name,
            )
            self._requests[job.job_id] = (media_json, stream_json, actual_season, actual_episode)
        if self.meta is not None:
            # Queuing means there is a connection right now; the library may be opened without one.
            threading.Thread(
                target=self.meta.ensure, args=(media.media_type, media.imdb_id), name="library-meta", daemon=True
            ).start()
        return json.dumps(_job_to_dict(job))

    @_friendly
    def retry(self, job_id: int) -> str:
        request = self._requests.get(int(job_id))
        if request is None:
            raise RuntimeError("This download can't be retried. Search for it again.")
        media_json, stream_json, season, episode = request
        result = self.enqueue(media_json, stream_json, season, episode)
        self.manager.remove(int(job_id))
        self._requests.pop(int(job_id), None)
        return result

    @_friendly
    def refetch_subtitles(self, job_id: int, try_another: bool = False) -> str:
        if not self.manager.refetch_subtitles(int(job_id), self.subtitles, bool(try_another)):
            raise RuntimeError("Subtitles can only be fetched for finished downloads.")
        return "ok"

    def subtitle_languages(self) -> str:
        """Languages offered when looking for more subtitles: the preferred ones first."""
        preferred = list(self.subtitles.languages)
        others = sorted(
            (code for code in subs.SUBTITLE_LANGUAGE_NAMES if code not in preferred),
            key=subs.subtitle_language_name,
        )
        return json.dumps(
            [
                {"code": code, "name": subs.subtitle_language_name(code), "preferred": code in preferred}
                for code in preferred + others
            ]
        )

    def subtitle_search(self, video_path: str, languages: str = "") -> str:
        """Subtitles available online for one downloaded video, best match first. Ones that
        were downloaded before carry the path of their file."""
        video = Path(video_path)
        if not video.is_file():
            raise RuntimeError("The video file was moved or deleted.")
        job = self.manager.job_for_file(video)
        media_type, imdb_id, season, episode = "movie", "", None, None
        known: dict[int, Path] = {}
        if job is not None:
            media_type, imdb_id = job.media.media_type, job.media.imdb_id
            known = dict(job.subtitle_files)
            if media_type == "series":
                season, episode = subs.season_episode_from_filename(video)
                if episode is None:
                    episode = subs.episode_number_from_filename(video) or job.episode
                season = season or job.season
                if season is None or episode is None:
                    imdb_id = ""  # can't tell which episode this is: go by file name instead
        wanted = subs.parse_subtitle_languages(languages) if languages.strip() else self.subtitles.languages
        try:
            choices = subs.search_subtitles(
                media_type=media_type,
                imdb_id=imdb_id,
                video_path=video,
                season=season,
                episode=episode,
                languages=wanted,
            )
        except subs.SubtitleError as error:
            raise RuntimeError(str(error)) from None
        return json.dumps(
            [
                {
                    "file_id": choice.file_id,
                    "language": choice.language,
                    "language_name": subs.subtitle_language_name(choice.language),
                    "release": choice.release,
                    "hash_match": choice.hash_match,
                    "hearing_impaired": choice.hearing_impaired,
                    "machine_translated": choice.machine_translated,
                    "download_count": choice.download_count,
                    "path": str(known[choice.file_id])
                    if choice.file_id in known and known[choice.file_id].exists()
                    else "",
                }
                for choice in choices[:60]
            ]
        )

    def subtitle_download(self, video_path: str, file_id: int, language: str) -> str:
        """Saves one more subtitle next to the video; returns its path."""
        video = Path(video_path)
        if not video.is_file():
            raise RuntimeError("The video file was moved or deleted.")
        try:
            path = subs.download_subtitle_file(int(file_id), language.strip().lower() or "en", video)
        except subs.SubtitleError as error:
            raise RuntimeError(str(error)) from None
        except OSError as error:
            raise RuntimeError(friendly_error(error)) from None
        job = self.manager.job_for_file(video)
        if job is not None:
            self.manager.record_subtitle(job, path, int(file_id), language)
        return json.dumps({"path": str(path)})

    def remove_job(self, job_id: int, delete_files: bool = False) -> bool:
        """Takes a finished download off the list, optionally deleting what it saved."""
        job_id = int(job_id)
        files = next((job.destinations for job in self.manager.snapshot() if job.job_id == job_id), ())
        output_dir = next((job.output_dir for job in self.manager.snapshot() if job.job_id == job_id), None)
        with self._requests_lock:
            removed = self.manager.remove(job_id)
            if removed:
                self._requests.pop(job_id, None)
        if removed and delete_files:
            self._delete_from_disk(files, output_dir)
        if removed:
            self._prune_meta()
        return removed

    def delete_files(self, job_id: int, paths_json: str) -> bool:
        """Deletes some videos of a finished download (one episode of a season pack, say),
        with their subtitles. The download leaves the library once none of its videos is left."""
        job_id = int(job_id)
        job = next((job for job in self.manager.snapshot() if job.job_id == job_id), None)
        if job is None or job.status != "completed":
            return False
        wanted = {Path(str(path)) for path in json.loads(paths_json)}
        # Only what this download saved: the paths come from the UI, the deleting happens here.
        videos = [path for path in job.destinations if path in wanted and _is_video_file(path.name)]
        if not videos:
            return False
        doomed: list[Path] = []
        for video in videos:
            doomed.append(video)
            doomed.extend(_sidecars_of(video, job.destinations))
        videos_left = self.manager.drop_files(job_id, doomed)
        if not videos_left:
            with self._requests_lock:
                if self.manager.remove(job_id):
                    self._requests.pop(job_id, None)
        self._delete_from_disk(videos, job.output_dir)
        self._prune_meta()
        return True

    def _delete_from_disk(self, files, output_dir: Path | None) -> None:
        # Sidecars are picked before anything is deleted, while every video is still there to claim its own.
        extras: list[Path] = []
        for path in files:
            if _is_video_file(path.name):
                # Subtitles fetched in other ways (same name, other suffix) go with their video.
                extras.extend(_sidecars_of(path, path.parent.glob(glob_escape(path.stem) + ".*.srt")))
        for target in (*files, *extras):
            try:
                target.unlink(missing_ok=True)
            except OSError:
                pass
        try:
            if output_dir is not None and output_dir != self.download_dir:
                output_dir.rmdir()  # only when nothing else is left in it
        except OSError:
            pass

    def _prune_meta(self) -> None:
        """Drops stored details and artwork of titles that have no download left."""
        if self.meta is not None:
            self.meta.prune({job.imdb_id for job in self.manager.snapshot()})

    def library_details(self, fetch: bool = False) -> str:
        """Details (plot, rating, artwork paths, episode titles) per IMDb id, for every title
        with a download. Without [fetch] only what is stored on the device is returned, which
        is instant and works offline; with it, missing and week-old entries are fetched first."""
        if self.meta is None:
            return "{}"
        titles = {job.imdb_id: job.media_type for job in self.manager.snapshot() if job.imdb_id}
        if not fetch:
            stored = {imdb_id: self.meta.load(imdb_id) for imdb_id in titles}
        else:
            with ThreadPoolExecutor(max_workers=4) as pool:
                futures = {
                    imdb_id: pool.submit(self.meta.ensure, media_type, imdb_id)
                    for imdb_id, media_type in titles.items()
                }
                stored = {imdb_id: future.result() for imdb_id, future in futures.items()}
        return json.dumps({imdb_id: value for imdb_id, value in stored.items() if value})

    def test_debrid(self, provider: str, token: str) -> str:
        """One-line account summary, or raises with a readable reason."""
        provider = provider.strip().lower()
        token = token.strip()
        if not token:
            raise RuntimeError("Enter a token first.")
        try:
            if provider == "realdebrid":
                user = cli.http_json(
                    "https://api.real-debrid.com/rest/1.0/user",
                    headers={"Authorization": f"Bearer {token}", "User-Agent": "comet-minimal"},
                    timeout=15,
                )
                kind = str(user.get("type") or "").lower()
                until = str(user.get("expiration") or "")[:10]
                plan = f"premium until {until}" if kind == "premium" and until else (kind or "free")
                return f"Connected as {user.get('username') or 'unknown'} · {plan}"
            if provider == "torbox":
                payload = cli.http_json(
                    "https://api.torbox.app/v1/api/user/me",
                    headers={"Authorization": f"Bearer {token}", "User-Agent": "comet-minimal"},
                    timeout=15,
                )
                data = payload.get("data") or {}
                plans = {0: "free plan", 1: "Essential", 2: "Pro", 3: "Standard"}
                plan = plans.get(data.get("plan"), "active plan")
                until = str(data.get("premium_expires_at") or "")[:10]
                return f"Connected · {plan}" + (f" until {until}" if until else "")
        except Exception as error:  # timeouts and bad JSON don't arrive as RuntimeError
            if re.search(r"HTTP (401|403)", str(error)):
                raise RuntimeError(f"{PROVIDER_LABELS.get(provider, 'The service')} rejected this token.") from None
            raise RuntimeError(friendly_error(error)) from None
        raise RuntimeError("Choose Real-Debrid or TorBox.")

    def active_count(self) -> int:
        return sum(1 for job in self.manager.snapshot() if job.status in {"queued", "resolving", "downloading"})

    def jobs(self) -> str:
        """Every job, each with the title it was queued for (poster, rating... as searched)."""
        rows = []
        for job in self.manager.snapshot():
            row = _job_to_dict(job)
            request = self._requests.get(job.job_id)
            if request is not None:
                try:
                    row["media"] = json.loads(request[0])
                except ValueError:
                    pass
            rows.append(row)
        return json.dumps(rows)

    def events_json(self) -> str:
        with self._event_lock:
            return json.dumps(list(self.events))

    def cancel(self, job_id: int) -> bool:
        return self.manager.cancel(int(job_id))

    def clear_finished(self) -> int:
        """Drops failed and cancelled jobs. Completed ones stay: they are the library."""
        with self._requests_lock:
            removed = self.manager.clear_finished(("failed", "cancelled"))
            remaining = {job.job_id for job in self.manager.snapshot()}
            for job_id in [job_id for job_id in self._requests if job_id not in remaining]:
                self._requests.pop(job_id, None)
        self._prune_meta()
        return removed

    def shutdown(self) -> str:
        return json.dumps([_job_to_dict(job) for job in self.manager.shutdown()])


_api: AndroidCometApi | None = None
_api_lock = threading.Lock()


def get_api(download_dir: str, max_parallel: int = 2, state_dir: str = "") -> AndroidCometApi:
    """The one download manager of this process (the activity and the service share it)."""
    global _api
    with _api_lock:
        if _api is None:
            _api = AndroidCometApi(download_dir, max_parallel=max_parallel, state_dir=state_dir)
        return _api
