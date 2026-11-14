"""Finished downloads, remembered across app restarts.

The download manager only lives as long as the process. This keeps one small JSON record per
completed job (what was asked for, where the files went, which subtitles it has) so the app can
list, play and manage them later. Files on disk stay the source of truth: a record whose videos
are gone is dropped when it is loaded.
"""
import json
import os
import threading
from pathlib import Path
from typing import Any

from comet.subtitles import SUBTITLE_VIDEO_EXTENSIONS

LIBRARY_VERSION = 1


class Library:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()

    def load(self) -> list[dict[str, Any]]:
        """Records that still have at least one video on disk, oldest first."""
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
        if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
            return []
        records: list[dict[str, Any]] = []
        for record in payload["items"]:
            if not isinstance(record, dict) or not isinstance(record.get("request"), dict):
                continue
            files = [str(path) for path in record.get("destinations") or [] if _exists(str(path))]
            if not any(path.lower().endswith(SUBTITLE_VIDEO_EXTENSIONS) for path in files):
                continue
            records.append({**record, "destinations": files})
        records.sort(key=lambda record: float(record.get("finished_at") or 0))
        return records

    def save(self, records: list[dict[str, Any]]) -> None:
        """Replaces the stored list. Written to a temp file first, so a crash mid-write
        can't leave a truncated library behind."""
        payload = json.dumps({"version": LIBRARY_VERSION, "items": records}, ensure_ascii=False)
        with self._lock:
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                temp = self.path.with_name(self.path.name + ".tmp")
                temp.write_text(payload, encoding="utf-8")
                os.replace(temp, self.path)
            except OSError:
                pass  # best effort: the downloads themselves are unaffected


def _exists(path: str) -> bool:
    try:
        return os.path.isfile(path)
    except OSError:
        return False
