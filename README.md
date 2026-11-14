# Comet Search Download

I needed a good CLI-based option to download files with fast debrid downloads for offline use, plane trips, and similar cases, so I built this tool from a fork of Comet.

This project has nothing to do with Stremio-style streaming. It does not stream video files. It searches, queues, and downloads them with a clean terminal UI, and you need to supply your own Real-Debrid or TorBox API key.

## What It Does

- searches movies and series by title
- lets you pick a movie, single episode, or full season
- queues downloads and keeps the CLI usable while downloads run
- shows active, queued, and finished jobs in a live sidebar on Rich-capable terminals
- downloads files to your local machine for offline use
- downloads a matching OpenSubtitles subtitle next to each downloaded video, in the first available preferred language (English, then Swedish by default)

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

To enable automatic subtitle downloads, add OpenSubtitles.com API credentials to `.env` or enter them when prompted:

```dotenv
OPENSUBTITLES_API_KEY=your_api_key_here
OPENSUBTITLES_USERNAME=your_username_here
OPENSUBTITLES_PASSWORD=your_password_here
# Optional: preference order, first language found wins (default: en, sv)
OPENSUBTITLES_LANGUAGES=en, sv
```

OpenSubtitles requires the API key for API access and a bearer token for subtitle downloads. The CLI gets that bearer token from OpenSubtitles with `OPENSUBTITLES_USERNAME` and `OPENSUBTITLES_PASSWORD`.

Subtitles are optional. Leave the API key prompt blank to skip them; the CLI then saves `OPENSUBTITLES_ENABLED=false` to `.env` and stops asking. Remove that line to set subtitles up later.

When these are set, each movie download gets one `.<lang>.srt` sidecar (for example `.en.srt`, or `.sv.srt` when no English subtitle exists). Full-season downloads request one subtitle per episode file, matched by the `SxxEyy` in its name; files without one are skipped rather than guessed. Results are ranked by language preference, then human over machine translations, exact file-hash matches, release-name similarity, trusted uploaders and download count. Episodes are searched by the show's IMDb id plus season/episode. The download quota and rate limits are respected, and an expired login is renewed automatically.

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
5. Background workers resolve links, download the files locally, and add OpenSubtitles sidecars when configured.

## Android App

`app/` holds an Android version of the same tool (Jetpack Compose UI, with this repo's Python code running on the device through Chaquopy). It searches and downloads to the phone's `Download` folder and plays the result itself.

- Build and install: `./gradlew :app:installDebug` (needs the Android SDK with platform 36). Settings such as the debrid token are entered in the app; `scripts/android_push_settings.py` can push them from `.env` to a debug build.
- The Library tab shows what you have downloaded as a poster grid, grouped per movie or show, with IMDb rating, plot, and where you are in a series (next episode, time left, watched marks). Running downloads sit on top with progress, speed and time left.
  - A title's page lists its seasons and episodes; play, resume, mark watched, or delete a single episode, a season or the whole title (videos and subtitles are removed from the `Download` folder).
  - Plot, artwork and episode titles are saved on the device when a download is queued (from Stremio's Cinemeta, no extra key), so the library looks the same without a connection.
  - The library is kept in the app's private storage and survives restarts; an entry can also be removed from it while keeping its files.
- Built-in player (AndroidX Media3/ExoPlayer, with [NextLib](https://github.com/anilbeesetti/nextlib)'s FFmpeg decoders for audio the phone can't decode itself, such as DTS and TrueHD):
  - subtitles: the automatically downloaded ones, tracks inside the file (SRT, ASS, PGS...), and more from OpenSubtitles in any language, picked and downloaded while the video plays; delay and size are adjustable
  - audio track choice, chapters (seek bar marks and a chapter list), playback speed, fit or fill
  - double tap the sides to skip 10 s, the middle to pause; hold for 2x; drag up or down on the left for brightness and on the right for volume; pinch to fill the screen
  - remembers the position, subtitle and audio choice per video, and plays a season's episodes in order

## Where Your Tokens Go

- The debrid token is sent to your debrid service through a [StremThru](https://github.com/MunifTanjim/stremthru) server, which looks up cached torrents and creates the download links. The default is the public instance `https://stremthru.13377001.xyz`; whoever runs that server can see the token. To avoid that with the CLI, run your own StremThru and set `STREMTHRU_URL` in `.env`. The Android app always uses the public instance.
- OpenSubtitles credentials are only sent to OpenSubtitles.
- The CLI keeps tokens in `.env`, readable by your user only (mode 600). The Android app keeps them in encrypted preferences and excludes its data from backups.
- Tokens are left out of the log file and of error messages.

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
