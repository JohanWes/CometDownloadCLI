# Comet Search Download

This repository is now a single-purpose local downloader built around [`scripts/comet_search_download.py`](/home/johanw/repos/comet/scripts/comet_search_download.py).

The script:

- stores and reuses your `REALDEBRID_API_TOKEN` in `.env`
- starts the local backend automatically when needed
- searches movies and TV shows by title
- lets you choose a movie, an episode, or a season search
- shows top 4K and 1080p results, preferring movies and episodes at 4K 3-10 GB and 1080p 1-5 GB, with wider bands for full seasons
- downloads the selected result to the OS Downloads folder by default:
  Linux uses `~/Downloads`, Windows uses `%USERPROFILE%\Downloads`
- creates a per-title folder such as `~/Downloads/<Title> (<Year>)/` for movies and `~/Downloads/<Title> Season 01 (<Year>)/` for series downloads
- ignores old temp-directory defaults such as `/tmp/...` and falls back to the OS Downloads folder instead
- downloads every matching episode file for full-season selections
- shows progress percent, speed, and ETA
- loops back to a new search after each completed download
- falls back to human-friendly filenames such as `Inception (2010) 2160p.mkv`

## Requirements

- Linux or macOS shell environment
- Python 3.13+
- A Real-Debrid account and API token
- Network access to:
  - TMDB, for title search in the CLI script
  - `stremthru.13377001.xyz`, for torrent search/cache/link generation

## Setup

Create a virtual environment and install the project:

```bash
python -m venv .venv
./.venv/bin/pip install -e .
```

Create `.env` from [`.env-sample`](/home/johanw/repos/comet/.env-sample) and set at least:

```dotenv
REALDEBRID_API_TOKEN=your_token_here
```

Optional `.env` values:

- `COMET_DOWNLOAD_DIR=/path/to/downloads`
- `PUBLIC_API_TOKEN=your_prefix_token`
- `FASTAPI_HOST=127.0.0.1`
- `FASTAPI_PORT=8000`
- `STREMTHRU_URL=https://stremthru.13377001.xyz`

## Run

Run the downloader directly:

```bash
./.venv/bin/python scripts/comet_search_download.py
```

Useful options:

```bash
./.venv/bin/python scripts/comet_search_download.py --query "Inception"
./.venv/bin/python scripts/comet_search_download.py --restart-comet
./.venv/bin/python scripts/comet_search_download.py --output-dir ~/Downloads
```

The script starts the local backend by launching:

```bash
python -m comet.main
```

You can also start the backend manually:

```bash
./.venv/bin/python -m comet.main
```

## How `.env` Is Used

- `REALDEBRID_API_TOKEN` is read by the script and saved automatically the first time you enter it.
- `COMET_DOWNLOAD_DIR` is reused as the default destination if set.
- `--output-dir` only applies to the current run and does not update `.env`.
- `PUBLIC_API_TOKEN` adds an optional `/s/<token>` API prefix. The script uses it automatically if present.
- Backend host and port come from `FASTAPI_HOST` and `FASTAPI_PORT`.

## How Downloads Work

1. The CLI searches TMDB by title and lets you pick a movie or show.
2. The local backend searches StremThru’s Torznab feed by IMDb ID.
3. Results are filtered to 4K and 1080p, with preferred size bands of 3-10 GB for 4K and 1-5 GB for 1080p for movies and episodes, and doubled bands for full-season searches, then cache-checked against your Real-Debrid account.
4. When you choose a result, the backend adds the magnet through StremThru and generates Real-Debrid download links for the matching file or files.
5. The CLI downloads the file locally and shows progress percent, speed, and ETA.
6. If the upstream filename is generic, the CLI falls back to a readable local name.

## Restart Or Reset

Restart the backend from the script:

```bash
./.venv/bin/python scripts/comet_search_download.py --restart-comet
```

Stop it manually:

```bash
pkill -f "comet.main"
```

Reset local runtime artifacts:

```bash
rm -f data/comet_search_download.log
```

## Troubleshooting

- `Could not find a Python environment that can run Comet`
  - Install the project into `.venv` and run the script with `./.venv/bin/python`.
- `Comet did not become healthy`
  - Check [`data/comet_search_download.log`](/home/johanw/repos/comet/data/comet_search_download.log).
- `No streams matched`
  - The selected title may not have suitable 4K/1080p English-friendly results at that moment.
- `The selected torrent is not cached yet`
  - Pick a result marked `cached` in the CLI output.
- Download filename looks generic
  - The script will automatically fall back to a readable name based on the chosen title and resolution.
