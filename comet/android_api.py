import asyncio
import base64
import json
import os
import re
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlencode, urlparse
from urllib.request import Request, urlopen

from comet.config import settings
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
SEASON_EPISODE_PATTERN = re.compile(r"\bs0*(\d{1,2})e0*(\d{1,3})\b", re.IGNORECASE)
SEASON_PATTERN = re.compile(r"\b(?:season|s)\s*0*(\d{1,2})\b", re.IGNORECASE)
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
        "error_text": job.error_text,
        "cancel_requested": job.cancel_requested,
        "output_dir": str(job.output_dir),
    }


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
    payload = _b64decode_json(b64config)
    for entry in payload.get("debridServices", []):
        provider = str(entry.get("service", "")).strip().lower()
        token = str(entry.get("apiKey", "")).strip()
        if provider in cli.DEBRID_PROVIDERS and token:
            return provider, token
    raise RuntimeError("Missing supported debrid provider token in stream config.")


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
    normalized = title.lower()
    if media_type == "movie":
        return SEASON_EPISODE_PATTERN.search(normalized) is None and "season" not in normalized
    if season is None:
        return False
    if episode is not None:
        return bool(re.search(rf"\bs0*{season}e0*{episode}\b|\b{season}x0*{episode}\b", title, re.IGNORECASE))
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
    b64config = cli.build_b64_config(provider, token)
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
                return sorted(matches, key=lambda item: str(item.get("name", "")).lower())
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


def _resolve_download_links(playback_url: str) -> list[tuple[str, str]]:
    token = _extract_playback_token(playback_url)
    path = urlparse(playback_url).path or playback_url
    b64config = [part for part in path.split("/") if part][0]
    provider, provider_token = _parse_config(b64config)
    payload = _decode_playback_payload(token)
    added = _add_magnet(provider, provider_token, payload["info_hash"], payload["torrent_title"])
    magnet = added.get("data") or {}
    status = str(magnet.get("status", "")).lower()
    if status not in {"cached", "downloaded"}:
        raise RuntimeError(f"The selected torrent is not cached yet (status: {status or 'unknown'}).")
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

    downloads = _resolve_download_links(candidate.playback_url)
    if reporter is not None:
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
    def __init__(self, download_dir: str, max_parallel: int = 2) -> None:
        self.provider = ""
        self.token = ""
        self.subtitles = cli.OpenSubtitlesConfig(api_key="", username="", password="")
        self.download_dir = Path(download_dir).expanduser()
        self.events: list[dict[str, Any]] = []
        self._event_lock = threading.Lock()
        cli.download_selected_stream = _download_selected_stream_direct
        self.manager = cli.DownloadManager(max_parallel, event_callback=self._record_event)

    def _record_event(self, level: str, message: str) -> None:
        with self._event_lock:
            self.events.append({"level": level, "message": message, "created_at": time.time()})
            del self.events[:-60]

    def configure(
        self,
        provider: str,
        token: str,
        opensubtitles_api_key: str = "",
        opensubtitles_username: str = "",
        opensubtitles_password: str = "",
    ) -> str:
        provider = provider.strip().lower()
        if provider not in cli.DEBRID_PROVIDERS:
            raise RuntimeError("Choose Real-Debrid or TorBox.")
        token = token.strip()
        if not token:
            raise RuntimeError("A provider API token is required.")
        self.provider = provider
        self.token = token
        self.subtitles = cli.OpenSubtitlesConfig(
            api_key=opensubtitles_api_key.strip(),
            username=opensubtitles_username.strip(),
            password=opensubtitles_password.strip(),
        )
        return "ok"

    def search(self, query: str) -> str:
        query = query.strip()
        if not query:
            raise RuntimeError("Enter a search query.")
        return json.dumps([_media_to_dict(media) for media in cli.search_tmdb(query)])

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
        grouped = cli.group_top_streams(cli.build_stream_candidates(raw_streams, context))
        return json.dumps([_stream_to_dict(stream) for stream in grouped])

    def enqueue(
        self,
        media_json: str,
        stream_json: str,
        season: int | None = None,
        episode: int | None = None,
    ) -> str:
        media = _media_from_dict(json.loads(media_json))
        stream = _stream_from_dict(json.loads(stream_json))
        actual_season = int(season) if season is not None and int(season) > 0 else None
        actual_episode = int(episode) if episode is not None and int(episode) > 0 else None
        preferred_name = cli.build_preferred_filename_base(
            media,
            stream.resolution,
            season=actual_season,
            episode=actual_episode,
        )
        target_dir = self.download_dir / cli.build_collection_dir_name(media, season=actual_season)
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
        return json.dumps(_job_to_dict(job))

    def jobs(self) -> str:
        return json.dumps([_job_to_dict(job) for job in self.manager.snapshot()])

    def events_json(self) -> str:
        with self._event_lock:
            return json.dumps(list(self.events))

    def cancel(self, job_id: int) -> bool:
        return self.manager.cancel(int(job_id))

    def clear_finished(self) -> int:
        return self.manager.clear_finished()

    def shutdown(self) -> str:
        return json.dumps([_job_to_dict(job) for job in self.manager.shutdown()])


_api: AndroidCometApi | None = None


def get_api(download_dir: str, max_parallel: int = 2) -> AndroidCometApi:
    global _api
    if _api is None:
        _api = AndroidCometApi(download_dir, max_parallel=max_parallel)
    return _api
