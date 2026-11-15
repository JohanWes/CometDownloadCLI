"""Title metadata (posters, IMDb ratings, episode lists) from Stremio's Cinemeta.

Cinemeta is a public, keyless metadata service keyed by IMDb id, so the Android
app gets box art and ratings without asking the user for another API key.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote
from urllib.request import Request, urlopen

CINEMETA_URL = "https://v3-cinemeta.strem.io"
METAHUB_URL = "https://images.metahub.space"
USER_AGENT = "CometDownload/1.0"
REQUEST_TIMEOUT = 10
MAX_MOVIES = 10
MAX_SERIES = 8
_OVERVIEW_LIMIT = 400
YEAR_PATTERN = re.compile(r"\d{4}")
RUNTIME_PATTERN = re.compile(r"(\d+)\s*min")

_meta_cache: dict[tuple[str, str], tuple[float, dict[str, Any]]] = {}
_cache_lock = threading.Lock()
_CACHE_LIMIT = 200
_CACHE_TTL_SECONDS = 6 * 3600


def _get_json(url: str) -> dict[str, Any]:
    request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urlopen(request, timeout=REQUEST_TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def _cinemeta_type(media_type: str) -> str:
    return "series" if media_type == "series" else "movie"


def _year(value: Any) -> str:
    match = YEAR_PATTERN.search(str(value or ""))
    return match.group(0) if match else ""


def _rating(value: Any) -> str:
    """IMDb rating as text ("8.4"), or "" when missing or zero."""
    try:
        rating = float(str(value).strip())
    except (TypeError, ValueError):
        return ""
    return f"{rating:.1f}" if rating > 0 else ""


def _runtime_minutes(value: Any) -> int:
    match = RUNTIME_PATTERN.search(str(value or ""))
    return int(match.group(1)) if match else 0


def _popularity(meta: dict[str, Any]) -> float:
    try:
        return float(meta.get("popularity") or 0.0)
    except (TypeError, ValueError):
        return 0.0


def fetch_meta(media_type: str, imdb_id: str, *, refresh: bool = False) -> dict[str, Any]:
    """Raw Cinemeta meta for one title, cached for a few hours (new episodes air)."""
    key = (_cinemeta_type(media_type), imdb_id)
    with _cache_lock:
        cached = _meta_cache.get(key)
    if cached is not None and not refresh and time.time() - cached[0] < _CACHE_TTL_SECONDS:
        return cached[1]
    meta = _get_json(f"{CINEMETA_URL}/meta/{key[0]}/{quote(imdb_id)}.json").get("meta") or {}
    with _cache_lock:
        _meta_cache.pop(key, None)
        if len(_meta_cache) >= _CACHE_LIMIT:
            _meta_cache.pop(next(iter(_meta_cache)))
        _meta_cache[key] = (time.time(), meta)
    return meta


def _season_count(meta: dict[str, Any]) -> int:
    seasons = {
        int(video.get("season") or 0)
        for video in meta.get("videos") or []
        if int(video.get("season") or 0) > 0
    }
    return len(seasons)


def _summary(media_type: str, catalog_item: dict[str, Any], meta: dict[str, Any]) -> dict[str, Any] | None:
    imdb_id = str(meta.get("imdb_id") or catalog_item.get("imdb_id") or catalog_item.get("id") or "")
    if not imdb_id.startswith("tt"):
        return None
    return {
        "media_type": media_type,
        "tmdb_id": int(meta.get("moviedb_id") or 0),
        "title": str(meta.get("name") or catalog_item.get("name") or "Untitled"),
        "year": _year(meta.get("year") or meta.get("releaseInfo") or catalog_item.get("releaseInfo")),
        "imdb_id": imdb_id,
        "poster": str(catalog_item.get("poster") or meta.get("poster") or ""),
        "rating": _rating(meta.get("imdbRating") or catalog_item.get("imdbRating")),
        "runtime_minutes": _runtime_minutes(meta.get("runtime")),
        "genres": [str(genre) for genre in (meta.get("genres") or catalog_item.get("genres") or [])][:3],
        "season_count": _season_count(meta) if media_type == "series" else 0,
        "popularity": _popularity(meta),
    }


def _catalog(media_type: str, query: str, limit: int) -> list[dict[str, Any]]:
    url = f"{CINEMETA_URL}/catalog/{_cinemeta_type(media_type)}/top/search={quote(query)}.json"
    return list(_get_json(url).get("metas") or [])[:limit]


def _merge_by_popularity(first: list[dict[str, Any]], second: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Interleave two relevance-ordered lists, taking the more popular head each step."""
    merged: list[dict[str, Any]] = []
    i = j = 0
    while i < len(first) and j < len(second):
        if first[i]["popularity"] >= second[j]["popularity"]:
            merged.append(first[i])
            i += 1
        else:
            merged.append(second[j])
            j += 1
    merged.extend(first[i:])
    merged.extend(second[j:])
    return merged


def search(query: str) -> list[dict[str, Any]]:
    """Movies and series matching `query`, enriched with poster, rating and runtime."""
    with ThreadPoolExecutor(max_workers=8) as pool:
        movie_future = pool.submit(_catalog, "movie", query, MAX_MOVIES)
        series_future = pool.submit(_catalog, "series", query, MAX_SERIES)
        catalogs = {"movie": movie_future.result(), "series": series_future.result()}

        def enrich(media_type: str, item: dict[str, Any]) -> dict[str, Any] | None:
            try:
                meta = fetch_meta(media_type, str(item.get("id") or ""))
            except Exception:
                meta = {}
            return _summary(media_type, item, meta)

        futures = {
            media_type: [pool.submit(enrich, media_type, item) for item in items]
            for media_type, items in catalogs.items()
        }
        results = {
            media_type: [summary for summary in (future.result() for future in items) if summary]
            for media_type, items in futures.items()
        }

    merged = _merge_by_popularity(results["movie"], results["series"])
    seen: set[str] = set()
    unique = []
    for item in merged:
        if item["imdb_id"] in seen:
            continue
        seen.add(item["imdb_id"])
        unique.append(item)
    return unique


def _released(value: Any) -> str:
    text = str(value or "")
    return text[:10] if len(text) >= 10 else ""


def _overview(value: Any) -> str:
    """One paragraph about an episode, cut short when it runs long."""
    text = " ".join(str(value or "").split())
    return text if len(text) <= _OVERVIEW_LIMIT else text[: _OVERVIEW_LIMIT - 1].rstrip() + "…"


def details(media_type: str, imdb_id: str, refresh: bool = False) -> dict[str, Any]:
    """Header data for the title view plus, for series, the episode list."""
    meta = fetch_meta(media_type, imdb_id, refresh=refresh)
    today = datetime.now(timezone.utc).date().isoformat()
    episodes = []
    seen: set[tuple[int, int]] = set()
    for video in meta.get("videos") or []:
        try:
            season = int(video.get("season") or 0)
            number = int(video.get("episode") or video.get("number") or 0)
        except (TypeError, ValueError):
            continue
        if season <= 0 or number <= 0:
            continue  # season 0 holds specials/extras, which stream lookups don't cover
        if (season, number) in seen:
            continue  # the episode list is keyed by number; a repeat would crash it
        seen.add((season, number))
        released = _released(video.get("released") or video.get("firstAired"))
        episodes.append(
            {
                "season": season,
                "episode": number,
                "title": str(video.get("name") or video.get("title") or ""),
                "released": released,
                "aired": not released or released <= today,
                # For the library's episode list: what the episode is about and a still from it.
                "overview": _overview(video.get("overview") or video.get("description")),
                "thumbnail": str(video.get("thumbnail") or ""),
            }
        )
    episodes.sort(key=lambda item: (item["season"], item["episode"]))
    return {
        "imdb_id": imdb_id,
        "rating": _rating(meta.get("imdbRating")),
        "runtime_minutes": _runtime_minutes(meta.get("runtime")),
        "genres": [str(genre) for genre in meta.get("genres") or []][:4],
        "description": str(meta.get("description") or "").strip(),
        "poster": str(meta.get("poster") or f"{METAHUB_URL}/poster/small/{imdb_id}/img"),
        "background": str(meta.get("background") or f"{METAHUB_URL}/background/medium/{imdb_id}/img"),
        "year_label": str(meta.get("releaseInfo") or meta.get("year") or ""),
        "episodes": episodes,
    }


# ---------------------------------------------------------------------------
# Details and artwork kept on disk, so the library also works offline
# ---------------------------------------------------------------------------

_STORE_TTL_SECONDS = 7 * 24 * 3600
_ARTWORK_RETRY_SECONDS = 6 * 3600
_ARTWORK_TIMEOUT = 20
_ARTWORK_MAX_BYTES = 8 * 1024 * 1024
_STILL_TIMEOUT = 10
_STILL_WORKERS = 4
_IMDB_ID_PATTERN = re.compile(r"^tt\d+$")


class MetaStore:
    """One folder per title: `meta.json` (what `details` returned) plus its poster and backdrop,
    and a still for each episode that is on the device.

    The library reads from here, so box art, rating, plot and episode titles are there on a
    plane too. Everything in it can be fetched again; a missing or broken entry is not an error.
    """

    def __init__(self, root: Path) -> None:
        self.root = root
        self._locks: dict[str, threading.Lock] = {}
        self._locks_guard = threading.Lock()

    def _dir(self, imdb_id: str) -> Path | None:
        # The id becomes a folder name, so only real IMDb ids get one.
        return self.root / imdb_id if _IMDB_ID_PATTERN.match(imdb_id) else None

    def _lock(self, imdb_id: str) -> threading.Lock:
        with self._locks_guard:
            return self._locks.setdefault(imdb_id, threading.Lock())

    def load(self, imdb_id: str) -> dict[str, Any] | None:
        """What is stored for a title, with artwork that has gone missing blanked out."""
        folder = self._dir(imdb_id)
        if folder is None:
            return None
        try:
            stored = json.loads((folder / "meta.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(stored, dict):
            return None
        for key in ("poster", "background"):
            value = str(stored.get(key) or "")
            if value.startswith("/") and not os.path.isfile(value):
                stored[key] = ""
        for episode in stored.get("episodes") or []:
            if isinstance(episode, dict) and episode.get("still") and not os.path.isfile(str(episode["still"])):
                episode["still"] = ""
        return stored

    def ensure(
        self,
        media_type: str,
        imdb_id: str,
        *,
        refresh: bool = False,
        episodes: Iterable[tuple[int, int]] = (),
    ) -> dict[str, Any] | None:
        """Stored details, fetched first when missing, incomplete or older than a week.

        [episodes] are the (season, episode) pairs that are on the device: each of those gets
        a still saved next to the details, and stills of episodes that are gone are removed.

        Without a network this returns whatever is stored (possibly nothing)."""
        folder = self._dir(imdb_id)
        if folder is None:
            return None
        wanted = set(episodes)
        with self._lock(imdb_id):
            stored = self.load(imdb_id)
            if stored is not None and not refresh and _is_fresh(stored) and _describes(stored, wanted):
                if self._sync_stills(folder, stored, wanted):
                    _write_json(folder / "meta.json", stored)
                return stored
            try:
                fresh = details(media_type, imdb_id, refresh=refresh or stored is not None)
            except Exception:
                return stored
            try:
                folder.mkdir(parents=True, exist_ok=True)
            except OSError:
                return fresh  # nowhere to keep it: still usable for this session
            for key in ("poster", "background"):
                # No extension: posters arrive as WebP or JPEG, and the app sniffs the content.
                target = folder / f"{key}.img"
                # The small poster is made for lists; the library also shows it large.
                url = str(fresh.get(key) or "").replace("/poster/small/", "/poster/medium/")
                # Artwork doesn't change; one copy is enough unless a refresh was asked for.
                if (refresh or not target.is_file()) and not _download(url, target):
                    if not target.is_file():
                        fresh[key] = ""
                        continue
                fresh[key] = str(target)
            self._sync_stills(folder, fresh, wanted)
            fresh["fetched_at"] = time.time()
            _write_json(folder / "meta.json", fresh)
            return fresh

    def _sync_stills(self, folder: Path, stored: dict[str, Any], wanted: set[tuple[int, int]]) -> bool:
        """Saves a still for every episode in [wanted] and drops the stills of all others.

        Each episode of [stored] gets `still`: the path of its saved image, or "". Returns
        whether [stored] changed. A still that could not be fetched is tried again later, not
        on every look at the library."""
        stills = folder / "stills"
        now = time.time()
        changed = False
        missing: list[tuple[dict[str, Any], Path]] = []
        for episode in stored.get("episodes") or []:
            key = (episode.get("season"), episode.get("episode"))
            target = stills / f"{key[0]}x{key[1]}.img"
            value = ""
            if key in wanted:
                if target.is_file():
                    value = str(target)
                elif episode.get("thumbnail") and not 0 <= now - float(episode.get("still_tried_at") or 0) < _ARTWORK_RETRY_SECONDS:
                    missing.append((episode, target))
            if value != str(episode.get("still") or ""):
                episode["still"] = value
                changed = True

        keep = {f"{season}x{number}.img" for season, number in wanted}
        try:
            for path in stills.iterdir():
                if path.name not in keep:
                    path.unlink(missing_ok=True)
        except OSError:
            pass  # no stills folder yet

        if not missing:
            return changed
        try:
            stills.mkdir(parents=True, exist_ok=True)
        except OSError:
            return changed

        def fetch(item: tuple[dict[str, Any], Path]) -> bool:
            # The list shows them small; the full-size still is five times the bytes.
            url = str(item[0]["thumbnail"]).replace("/w780.jpg", "/w500.jpg")
            return _download(url, item[1], timeout=_STILL_TIMEOUT)

        with ThreadPoolExecutor(max_workers=_STILL_WORKERS) as pool:
            saved = list(pool.map(fetch, missing))
        for (episode, target), ok in zip(missing, saved):
            if ok:
                episode["still"] = str(target)
                episode.pop("still_tried_at", None)
            else:
                episode["still_tried_at"] = now
        return True

    def remove(self, imdb_id: str) -> None:
        folder = self._dir(imdb_id)
        if folder is not None:
            shutil.rmtree(folder, ignore_errors=True)

    def prune(self, keep: set[str]) -> None:
        """Forgets every title that is not in [keep]."""
        try:
            folders = [path for path in self.root.iterdir() if path.is_dir()]
        except OSError:
            return
        for folder in folders:
            if folder.name not in keep:
                shutil.rmtree(folder, ignore_errors=True)


def _is_fresh(stored: dict[str, Any]) -> bool:
    try:
        age = time.time() - float(stored.get("fetched_at") or 0)
    except (TypeError, ValueError):
        return False
    # Artwork that could not be saved (or does not exist) is tried again sooner, but not on
    # every look at the library.
    complete = bool(stored.get("poster")) and bool(stored.get("background"))
    return 0 <= age < (_STORE_TTL_SECONDS if complete else _ARTWORK_RETRY_SECONDS)


def _describes(stored: dict[str, Any], wanted: set[tuple[int, int]]) -> bool:
    """Whether the stored episodes in [wanted] carry what the library shows of them.

    Details saved by an older version of the app have no overview or still address."""
    return all(
        "overview" in episode
        for episode in stored.get("episodes") or []
        if (episode.get("season"), episode.get("episode")) in wanted
    )


def _download(url: str, target: Path, timeout: float = _ARTWORK_TIMEOUT) -> bool:
    """Saves an image next to the details. False when there was nothing usable to save."""
    if not url.startswith(("http://", "https://")):
        return False
    temp = target.with_name(target.name + ".tmp")
    try:
        request = Request(url, headers={"User-Agent": USER_AGENT})
        with urlopen(request, timeout=timeout) as response:
            data = response.read(_ARTWORK_MAX_BYTES + 1)
        if not data or len(data) > _ARTWORK_MAX_BYTES:
            return False
        temp.write_bytes(data)
        os.replace(temp, target)
        return True
    except Exception:
        try:
            temp.unlink(missing_ok=True)
        except OSError:
            pass
        return False


def _write_json(path: Path, value: dict[str, Any]) -> None:
    temp = path.with_name(path.name + ".tmp")
    try:
        temp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        os.replace(temp, path)
    except OSError:
        pass  # best effort, like the artwork
