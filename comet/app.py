from contextlib import asynccontextmanager

import aiohttp
from fastapi import FastAPI, HTTPException
from fastapi.responses import RedirectResponse

from comet.backend import (
    BackendError,
    add_magnet,
    build_streams,
    choose_files,
    decode_playback_payload,
    fetch_torznab_results,
    filter_candidates,
    generate_download_link,
    mark_cached,
    parse_media_scope,
    parse_user_config,
    sort_candidates,
)
from comet.config import settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    timeout = aiohttp.ClientTimeout(
        total=max(settings.torznab_timeout_seconds, settings.stremthru_timeout_seconds)
    )
    async with aiohttp.ClientSession(timeout=timeout) as session:
        app.state.http = session
        yield


app = FastAPI(
    title="Comet Search Download",
    summary="Minimal local backend for scripts/comet_search_download.py.",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)


@app.get("/")
async def root():
    return {"name": "comet-search-download", "status": "ok"}


@app.get("/health")
async def health():
    return {"status": "ok"}


def _register_stream_routes(prefix: str = "") -> None:
    @app.get(f"{prefix}/{{b64config}}/stream/{{media_type}}/{{media_id:path}}.json")
    async def stream_results(b64config: str, media_type: str, media_id: str):
        try:
            rd_token, enabled_resolutions, require_english = parse_user_config(b64config)
            scope = parse_media_scope(media_type, media_id)
            session = app.state.http
            rows = await fetch_torznab_results(session, scope.imdb_id)
            candidates = await filter_candidates(
                rows,
                scope,
                enabled_resolutions=enabled_resolutions,
                require_english=require_english,
            )
            await mark_cached(session, rd_token, scope, candidates)
            ordered = sort_candidates(candidates)
            return {"streams": build_streams(b64config, scope, ordered)}
        except BackendError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        except aiohttp.ClientResponseError as error:
            raise HTTPException(status_code=502, detail=f"Upstream error: {error.status}") from error

    @app.get(f"{prefix}/{{b64config}}/playback/{{playback_token}}")
    async def playback(b64config: str, playback_token: str):
        try:
            rd_token, _, _ = parse_user_config(b64config)
            payload = decode_playback_payload(playback_token)
            session = app.state.http
            added = await add_magnet(session, rd_token, payload.info_hash, payload.torrent_title)
            magnet = added.get("data") or {}
            status = str(magnet.get("status", "")).lower()
            if status not in {"cached", "downloaded"}:
                raise BackendError(
                    f"The selected torrent is not cached yet (status: {status or 'unknown'})."
                )
            target_files = await choose_files(payload, magnet.get("files") or [])
            if not target_files:
                raise BackendError("No matching video files were available in the selected torrent.")

            if len(target_files) == 1:
                file_link = str(target_files[0].get("link", "")).strip()
                if not file_link:
                    raise BackendError(
                        "The selected torrent file did not contain a downloadable link."
                    )
                download_link = await generate_download_link(session, rd_token, file_link)
                return RedirectResponse(download_link, status_code=302)

            downloads = []
            for target_file in target_files:
                file_link = str(target_file.get("link", "")).strip()
                if not file_link:
                    continue
                download_link = await generate_download_link(session, rd_token, file_link)
                downloads.append(
                    {
                        "name": str(target_file.get("name", "")).strip(),
                        "url": download_link,
                    }
                )

            if not downloads:
                raise BackendError("The selected torrent files did not contain downloadable links.")
            return {"downloads": downloads}
        except BackendError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        except aiohttp.ClientResponseError as error:
            raise HTTPException(status_code=502, detail=f"Upstream error: {error.status}") from error


_register_stream_routes()
if settings.public_api_token:
    _register_stream_routes(f"/s/{settings.public_api_token}")
