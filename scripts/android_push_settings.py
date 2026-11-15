#!/usr/bin/env python3
"""Push the subtitle languages (and optionally debrid) settings from .env to the Android debug app.

The values travel over adb stdin into the app's private files dir (`run-as`, debug builds
only), so they never show up in command lines, logs or the APK. The app imports them into
its encrypted settings on next launch and deletes the file.

    python scripts/android_push_settings.py --serial <adb serial> [--debrid]
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts import comet_search_download as cli  # noqa: E402

PACKAGE = "com.johanwes.cometdownload.debug"
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True, help="adb device serial")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--debrid", action="store_true", help="also push the debrid provider token")
    args = parser.parse_args()

    env = cli.load_env_values()
    seed: dict[str, str] = {}
    if env.get(cli.OPENSUBTITLES_LANGUAGES_ENV_KEY):
        seed["subtitle_languages"] = env[cli.OPENSUBTITLES_LANGUAGES_ENV_KEY]
    if args.debrid:
        for provider, key in (("torbox", cli.TORBOX_ENV_KEY), ("realdebrid", cli.REALDEBRID_ENV_KEY)):
            if env.get(key):
                seed.update(provider=provider, token=env[key])
                break
    present = sorted(name for name, value in seed.items() if value)
    if not present:
        print("Nothing to push: no matching values in .env", file=sys.stderr)
        return 1

    remote = f"run-as {args.package} sh -c 'mkdir -p files && cat > files/seed_settings.json && wc -c < files/seed_settings.json'"
    result = subprocess.run(
        [ADB, "-s", args.serial, "shell", remote],
        input=json.dumps(seed).encode(),
        capture_output=True,
    )
    if result.returncode != 0:
        print(result.stderr.decode(errors="replace").strip() or "adb failed", file=sys.stderr)
        return result.returncode
    print(f"Pushed {', '.join(present)} ({result.stdout.decode().strip()} bytes); imported on next app launch.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
