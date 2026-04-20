# Comet Search Download

I needed a good CLI-based option to download files with fast debrid downloads for offline use, plane trips, and similar cases, so I built this tool from a fork of Comet.

This project has nothing to do with Stremio-style streaming. It does not stream video files. It searches, queues, and downloads them with a clean terminal UI, and you need to supply your own Real-Debrid or TorBox API key.

## What It Does

- searches movies and series by title
- lets you pick a movie, single episode, or full season
- queues downloads and keeps the CLI usable while downloads run
- shows active, queued, and finished jobs in a live sidebar on Rich-capable terminals
- downloads files to your local machine for offline use
- downloads the highest-rated English OpenSubtitles subtitle next to each downloaded video

## Requirements

- Linux or macOS shell
- Python 3.13+
- Python `venv` support
- `git`
- a Real-Debrid or TorBox account and API token

## Quick Start

From the repo root:

```bash
./cometCLI
```

On first run, `./cometCLI` will create `.venv` if needed, install dependencies, and launch the downloader.

On first run, choose your debrid provider, then paste that provider's API token:

```text
1. Real-Debrid
2. TorBox
```

You can also create `.env` from [`.env-sample`](.env-sample) and set one provider token:

```dotenv
REALDEBRID_API_TOKEN=your_token_here
# or
TORBOX_API_TOKEN=your_token_here
```

If only `TORBOX_API_TOKEN` is set, `./cometCLI` uses TorBox automatically. If only `REALDEBRID_API_TOKEN` is set, it uses Real-Debrid automatically. If both are set, choose explicitly:

```bash
./cometCLI --provider torbox
```

To enable automatic English subtitle downloads, add OpenSubtitles.com API credentials to `.env` or enter them when prompted:

```dotenv
OPENSUBTITLES_API_KEY=your_api_key_here
OPENSUBTITLES_USERNAME=your_username_here
OPENSUBTITLES_PASSWORD=your_password_here
```

OpenSubtitles requires the API key for API access and a bearer token for subtitle downloads. The CLI gets that bearer token from OpenSubtitles with `OPENSUBTITLES_USERNAME` and `OPENSUBTITLES_PASSWORD`.

When these are set, each movie download gets one `.en.srt` sidecar file. Full-season downloads request one subtitle for every episode in the selected season. The CLI first tries an exact OpenSubtitles file-hash and byte-size match against the downloaded video, then falls back to the top-rated English IMDb/episode result.

## Useful Commands

Run the CLI:

```bash
./cometCLI
```

Useful options:

```bash
./cometCLI --query "Example Title"
./cometCLI --provider torbox --query "Example Title"
./cometCLI --restart-comet
./cometCLI --output-dir ~/Downloads
./cometCLI --parallel-downloads 3
```

Inside the live CLI:

```text
/jobs
/clear-finished
/help
/quit
```

In the `/jobs` view, use the arrow keys to select a job, `Enter` to open the cancel prompt, and `Esc` or `q` to return to normal search mode.

While choosing a title, season, episode, or stream, press `Esc` to cancel that search and return to the main search prompt.

## How It Works

1. Search for a title.
2. Pick a movie, episode, or full season.
3. Choose one of the filtered 4K or 1080p results.
4. The download is queued immediately.
5. Background workers resolve links, download the files locally, and add English OpenSubtitles sidecars when configured.

## Notes

- downloads go to `~/Downloads` by default unless overridden
- files are saved for local playback; this tool does not stream them
- cancelled jobs remove their temporary in-progress download folder so partial files do not linger
- if the upstream filename is generic, the CLI falls back to a cleaner local filename

## Troubleshooting

- `Comet did not become healthy`
  - Check `data/comet_search_download.log`.
- `No streams matched`
  - The selected title may not have suitable cached results right now.
- `The selected torrent is not cached yet`
  - Pick a result marked `cached`.
