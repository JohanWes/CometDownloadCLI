#!/usr/bin/env python3
import argparse
import base64
import json
import os
import re
import select
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from collections import deque
from contextlib import nullcontext
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

if os.name == "posix":
    import termios
else:  # pragma: no cover - platform-specific branch
    termios = None

try:
    from rich import box
    from rich.console import Console, Group
    from rich.layout import Layout
    from rich.live import Live
    from rich.markup import escape
    from rich.panel import Panel
    from rich.table import Table
    from rich.text import Text
    from rich.theme import Theme

    RICH_AVAILABLE = True
except ImportError:  # pragma: no cover - optional dependency fallback
    box = None
    Console = None
    Group = None
    Layout = None
    Live = None
    Panel = None
    Table = None
    Text = None
    Theme = None
    RICH_AVAILABLE = False

    def escape(value: str) -> str:
        return value


ROOT_DIR = Path(__file__).resolve().parents[1]
ENV_PATH = ROOT_DIR / ".env"
DEFAULT_HOST = "http://127.0.0.1:8000"
REALDEBRID_ENV_KEY = "REALDEBRID_API_TOKEN"
DOWNLOAD_DIR_ENV_KEY = "COMET_DOWNLOAD_DIR"
PUBLIC_API_TOKEN_ENV_KEY = "PUBLIC_API_TOKEN"
HEALTHCHECK_TIMEOUT = 60
STREAM_RESULT_LIMIT = 3
STARTUP_LOG_PATH = ROOT_DIR / "data" / "comet_search_download.log"
DEFAULT_TMDB_READ_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlNTkxMmVmOWFhM2IxNzg2Zjk3ZTE1NWY1YmQ3ZjY1MSIsInN1YiI6IjY1M2NjNWUyZTg5NGE2MDBmZjE2N2FmYyIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.xrIXsMFJpI1o1j5g2QpQcFP1X3AfRjFA5FlBFO5Naw8"
TMDB_BASE_URL = "https://api.themoviedb.org/3"
TMDB_HEADERS = {
    "Authorization": f"Bearer {DEFAULT_TMDB_READ_ACCESS_TOKEN}",
    "Content-Type": "application/json",
}

SAFE_FILENAME_CHARS = re.compile(r"[^A-Za-z0-9._() -]+")
CONTENT_DISPOSITION_FILENAME = re.compile(
    r'filename\*?=(?:UTF-8\'\')?"?([^";]+)"?', re.IGNORECASE
)

RESOLUTION_RULES = {
    "4K": {"min_bytes": 3 * 1024**3, "max_bytes": 10 * 1024**3},
    "1080P": {"min_bytes": 1 * 1024**3, "max_bytes": 5 * 1024**3},
}
FULL_SEASON_SIZE_MULTIPLIER = 2


@dataclass
class MediaCandidate:
    media_type: str
    tmdb_id: int
    title: str
    year: str
    imdb_id: str


@dataclass
class StreamCandidate:
    index: int
    resolution: str
    is_cached: bool
    size_bytes: int
    name: str
    description: str
    playback_url: str
    is_strict_match: bool
    preferred_distance_bytes: int
    fallback_reason: str = ""


@dataclass(frozen=True)
class SizePreferenceContext:
    media_type: str
    season: int | None = None
    episode: int | None = None


@dataclass
class LaunchCommand:
    argv: list[str]
    description: str


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class InputShield:
    def __init__(self) -> None:
        self._fd: int | None = None
        self._old_settings: Any = None
        self._enabled = False

    def __enter__(self) -> "InputShield":
        if os.name != "posix" or termios is None or not sys.stdin.isatty():
            return self

        try:
            fd = sys.stdin.fileno()
            old_settings = termios.tcgetattr(fd)
            new_settings = termios.tcgetattr(fd)
            new_settings[3] &= ~(termios.ECHO | termios.ICANON)
            new_settings[6][termios.VMIN] = 0
            new_settings[6][termios.VTIME] = 0
            termios.tcsetattr(fd, termios.TCSANOW, new_settings)
            termios.tcflush(fd, termios.TCIFLUSH)
        except (AttributeError, OSError, ValueError):
            return self

        self._fd = fd
        self._old_settings = old_settings
        self._enabled = True
        return self

    def drain(self) -> None:
        if not self._enabled or self._fd is None:
            return

        while True:
            try:
                ready, _, _ = select.select([self._fd], [], [], 0)
            except (OSError, ValueError):
                return

            if not ready:
                return

            try:
                chunk = os.read(self._fd, 1024)
            except OSError:
                return

            if not chunk:
                return

    def __exit__(self, exc_type, exc, tb) -> bool:
        self.drain()

        if self._enabled and self._fd is not None and self._old_settings is not None:
            try:
                termios.tcsetattr(self._fd, termios.TCSANOW, self._old_settings)
            except (AttributeError, OSError, ValueError):
                pass

            try:
                termios.tcflush(self._fd, termios.TCIFLUSH)
            except (AttributeError, OSError, ValueError):
                pass

        return False


class DownloadCancelled(Exception):
    pass


@dataclass
class DownloadJob:
    job_id: int
    label: str
    host: str
    candidate: StreamCandidate
    output_dir: Path
    staging_dir: Path
    fallback_name: str
    status: str = "queued"
    phase: str = "Queued"
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    total_files: int = 1
    completed_files: int = 0
    current_file_index: int | None = None
    current_file_name: str = ""
    total_bytes: int | None = None
    downloaded_bytes: int = 0
    speed_bytes_per_second: float = 0.0
    eta_seconds: float | None = None
    destinations: list[Path] = field(default_factory=list)
    error_text: str = ""
    cancel_requested: bool = False
    cancel_event: threading.Event = field(default_factory=threading.Event, repr=False)


@dataclass(frozen=True)
class DownloadJobSnapshot:
    job_id: int
    label: str
    status: str
    phase: str
    created_at: float
    started_at: float | None
    finished_at: float | None
    total_files: int
    completed_files: int
    current_file_index: int | None
    current_file_name: str
    total_bytes: int | None
    downloaded_bytes: int
    speed_bytes_per_second: float
    eta_seconds: float | None
    destinations: tuple[Path, ...]
    error_text: str
    cancel_requested: bool
    output_dir: Path


class DashboardRenderable:
    def __init__(self, ui: "TerminalUI") -> None:
        self._ui = ui

    def __rich__(self):
        return self._ui.build_layout()


class UIStatus:
    def __init__(self, ui: "TerminalUI", message: str) -> None:
        self._ui = ui
        self._message = message
        self._fallback = nullcontext()

    def __enter__(self):
        if self._ui.live_enabled():
            self._ui.set_status_message(self._message)
            return self

        if self._ui.console:
            self._fallback = self._ui.console.status(
                f"[accent]{escape(self._message)}[/accent]",
                spinner="dots",
                spinner_style="accent_soft",
            )
        self._fallback.__enter__()
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        if self._ui.live_enabled():
            self._ui.clear_status_message(self._message)
            return False
        return self._fallback.__exit__(exc_type, exc, tb)


class TerminalUI:
    def __init__(self) -> None:
        self.console = None
        if RICH_AVAILABLE and sys.stdout.isatty():
            self.console = Console(
                theme=Theme(
                    {
                        "accent": "bold #86c8bc",
                        "accent_soft": "#aacfc8",
                        "heading": "bold #f4efe6",
                        "muted": "#98a4ad",
                        "panel": "#5e7f86",
                        "success": "bold #88b26f",
                        "warning": "bold #d2aa61",
                        "error": "bold #d97979",
                        "bar.back": "#2a3137",
                        "bar.complete": "bold #86c8bc",
                        "bar.finished": "bold #88b26f",
                        "bar.pulse": "#aacfc8",
                    }
                ),
                highlight=False,
                soft_wrap=True,
            )
        self._supports_live = (
            self.console is not None
            and Live is not None
            and Layout is not None
            and sys.stdin.isatty()
            and os.name == "posix"
            and termios is not None
        )
        self._live: Live | None = None
        self._main_renderable = None
        self._event_messages: deque[tuple[str, str]] = deque(maxlen=12)
        self._status_messages: list[str] = []
        self._prompt_label = ""
        self._prompt_buffer = ""
        self._input_mode = "query"
        self._job_browser_selected_id: int | None = None
        self._job_browser_notice = ""
        self._download_manager: DownloadManager | None = None
        self._lock = threading.RLock()

    def bind_download_manager(self, manager: "DownloadManager") -> None:
        self._download_manager = manager

    def rich_enabled(self) -> bool:
        return self.console is not None

    def live_enabled(self) -> bool:
        return self._live is not None

    def start_session(self) -> None:
        if not self._supports_live or self._live is not None:
            return
        self._live = Live(
            DashboardRenderable(self),
            console=self.console,
            auto_refresh=True,
            refresh_per_second=6,
            transient=False,
        )
        self._live.start()
        self.refresh()

    def stop_session(self) -> None:
        if self._live is None:
            return
        live = self._live
        self._live = None
        live.stop()

    def refresh(self) -> None:
        if self._live is not None:
            self._live.refresh()

    def _set_main_renderable(self, renderable) -> None:
        with self._lock:
            self._main_renderable = renderable
        if not self.live_enabled() and self.console:
            self.console.print()
            self.console.print(renderable)
        self.refresh()

    def _append_event(self, level: str, message: str) -> None:
        with self._lock:
            self._event_messages.append((level, message))
        self.refresh()

    def set_status_message(self, message: str) -> None:
        with self._lock:
            self._status_messages.append(message)
        self.refresh()

    def clear_status_message(self, message: str) -> None:
        with self._lock:
            for index in range(len(self._status_messages) - 1, -1, -1):
                if self._status_messages[index] == message:
                    self._status_messages.pop(index)
                    break
        self.refresh()

    def show_header(self) -> None:
        if self.live_enabled():
            self.show_help()
            return

        if not self.console:
            print("Comet Search Download")
            print("Search, select, queue, repeat.")
            return

        self.console.print(
            Panel.fit(
                "[heading]Comet Search Download[/heading]\n[muted]Search, select, queue, repeat.[/muted]",
                border_style="panel",
                padding=(1, 2),
            )
        )

    def blank(self) -> None:
        if not self.live_enabled():
            if self.console:
                self.console.print()
            else:
                print()

    def prompt(self, prompt: str) -> str:
        if self.live_enabled():
            return self._prompt_live(prompt)
        if self.console:
            return self.console.input(f"[accent]{escape(prompt)}[/accent]")
        return input(prompt)

    def show_jobs(self, jobs: list[DownloadJobSnapshot]) -> None:
        if not jobs:
            if self.live_enabled():
                self.info("No downloads in this session yet.")
                self._exit_job_browser()
            elif not self.console:
                self.info("No downloads in this session yet.")
            else:
                self._set_main_renderable(
                    Panel(
                        "[muted]No downloads in this session yet.[/muted]",
                        title="[heading]Jobs[/heading]",
                        border_style="panel",
                        padding=(1, 2),
                    )
                )
            return

        if self.live_enabled():
            with self._lock:
                self._input_mode = "jobs"
                self._job_browser_notice = "Use Up/Down to select a job. Enter opens cancel confirmation. Esc/q returns to search."
                if self._job_browser_selected_id not in {job.job_id for job in jobs}:
                    self._job_browser_selected_id = jobs[0].job_id
            self.refresh()
            return

        if not self.console:
            print("\nJobs:")
            for job in jobs:
                print(f"  #{job.job_id} {job.status}: {job.label}")
            return

        table = Table(
            box=box.SIMPLE_HEAVY,
            expand=True,
            show_header=True,
            header_style="accent",
            border_style="panel",
        )
        table.add_column("ID", justify="right", no_wrap=True, width=4)
        table.add_column("Status", no_wrap=True, width=12)
        table.add_column("Title", min_width=24)
        table.add_column("Progress", min_width=24)

        for job in jobs:
            table.add_row(
                str(job.job_id),
                job.status,
                escape(job.label),
                escape(self._job_progress_line(job)),
            )

        self._set_main_renderable(
            Panel(
                table,
                title="[heading]Jobs[/heading]",
                border_style="panel",
                padding=(0, 1),
            )
        )

    def _prompt_live(self, prompt: str) -> str:
        fd = sys.stdin.fileno()
        old_settings = termios.tcgetattr(fd)
        new_settings = termios.tcgetattr(fd)
        new_settings[3] &= ~(termios.ECHO | termios.ICANON)
        new_settings[6][termios.VMIN] = 0
        new_settings[6][termios.VTIME] = 0
        termios.tcsetattr(fd, termios.TCSANOW, new_settings)

        try:
            with self._lock:
                self._prompt_label = prompt
                self._prompt_buffer = ""
            self.refresh()
            pending_escape = False
            escape_sequence = ""
            while True:
                ready, _, _ = select.select([fd], [], [], 0.1)
                if not ready:
                    continue

                chunk = os.read(fd, 32)
                if not chunk:
                    continue

                for byte in chunk:
                    char = chr(byte)
                    if pending_escape:
                        escape_sequence += char
                        if not self._handle_live_escape_sequence(escape_sequence):
                            pending_escape = False
                            escape_sequence = ""
                        continue
                    if char == "\x1b":
                        pending_escape = True
                        escape_sequence = ""
                        continue
                    if self._input_mode != "query":
                        if char == "\x04":
                            raise EOFError
                        self._handle_browser_key(char)
                        continue
                    if char in ("\r", "\n"):
                        value = self._prompt_buffer
                        with self._lock:
                            self._prompt_label = ""
                            self._prompt_buffer = ""
                        self.refresh()
                        return value
                    if char in ("\x7f", "\b"):
                        with self._lock:
                            self._prompt_buffer = self._prompt_buffer[:-1]
                        self.refresh()
                        continue
                    if char == "\x04":
                        raise EOFError
                    if char.isprintable():
                        with self._lock:
                            self._prompt_buffer += char
                        self.refresh()
        finally:
            with self._lock:
                self._prompt_label = ""
                self._prompt_buffer = ""
            termios.tcsetattr(fd, termios.TCSANOW, old_settings)
            self.refresh()

    def _handle_live_escape_sequence(self, sequence: str) -> bool:
        if sequence in {"[A", "OA"}:
            self._handle_browser_navigation(-1)
            return False
        if sequence in {"[B", "OB"}:
            self._handle_browser_navigation(1)
            return False
        if sequence.startswith("[") and not sequence.endswith(("A", "B", "~")):
            return True
        if sequence.startswith("O") and len(sequence) < 2:
            return True
        self._handle_browser_escape()
        return False

    def _handle_browser_navigation(self, delta: int) -> None:
        if self._input_mode not in {"jobs", "confirm_cancel"}:
            return
        jobs = self._browser_jobs()
        if not jobs:
            self._exit_job_browser()
            return
        ids = [job.job_id for job in jobs]
        current_id = self._job_browser_selected_id if self._job_browser_selected_id in ids else ids[0]
        index = ids.index(current_id)
        index = (index + delta) % len(ids)
        with self._lock:
            self._job_browser_selected_id = ids[index]
            if self._input_mode == "confirm_cancel":
                self._input_mode = "jobs"
                self._job_browser_notice = "Press Enter to manage the selected job. Esc/q returns to search."
        self.refresh()

    def _handle_browser_escape(self) -> None:
        if self._input_mode == "confirm_cancel":
            with self._lock:
                self._input_mode = "jobs"
                self._job_browser_notice = "Cancel prompt dismissed. Enter opens it again, Esc/q returns to search."
            self.refresh()
            return
        self._exit_job_browser()

    def _handle_browser_key(self, char: str) -> None:
        if char in {"q", "Q"}:
            self._exit_job_browser()
            return
        if char in {"j", "J"}:
            self._handle_browser_navigation(1)
            return
        if char in {"k", "K"}:
            self._handle_browser_navigation(-1)
            return
        if char in ("\r", "\n"):
            selected_job = self._selected_browser_job()
            if selected_job is None:
                self._exit_job_browser()
                return
            if self._input_mode == "confirm_cancel":
                self._cancel_selected_browser_job(selected_job)
                return
            if selected_job.status not in {"queued", "resolving", "downloading"}:
                with self._lock:
                    self._job_browser_notice = (
                        f"Job #{selected_job.job_id} is already {selected_job.status}. Pick an active job or press Esc/q."
                    )
                self.refresh()
                return
            with self._lock:
                self._input_mode = "confirm_cancel"
                self._job_browser_notice = (
                    f"Cancel job #{selected_job.job_id} '{selected_job.label}'? Press y/n or Enter/Esc/q."
                )
            self.refresh()
            return
        if self._input_mode == "confirm_cancel":
            if char in {"y", "Y"}:
                selected_job = self._selected_browser_job()
                if selected_job is not None:
                    self._cancel_selected_browser_job(selected_job)
                return
            if char in {"n", "N"}:
                with self._lock:
                    self._input_mode = "jobs"
                    self._job_browser_notice = "Cancel prompt dismissed. Enter opens it again, Esc/q returns to search."
                self.refresh()
                return

    def _cancel_selected_browser_job(self, job: DownloadJobSnapshot) -> None:
        manager = self._download_manager
        if manager is None:
            self._exit_job_browser()
            return
        cancelled = manager.cancel(job.job_id)
        with self._lock:
            self._input_mode = "jobs"
            if cancelled:
                self._job_browser_notice = f"Cancel requested for job #{job.job_id}. Esc/q returns to search."
            else:
                self._job_browser_notice = f"Could not cancel job #{job.job_id}. It may already be finished."
        self.refresh()

    def _browser_jobs(self) -> list[DownloadJobSnapshot]:
        if self._download_manager is None:
            return []
        jobs = self._download_manager.snapshot()
        if not jobs:
            return []
        ids = {job.job_id for job in jobs}
        with self._lock:
            if self._job_browser_selected_id not in ids:
                self._job_browser_selected_id = jobs[0].job_id
        return jobs

    def _selected_browser_job(self) -> DownloadJobSnapshot | None:
        jobs = self._browser_jobs()
        if not jobs:
            return None
        selected_id = self._job_browser_selected_id
        for job in jobs:
            if job.job_id == selected_id:
                return job
        return jobs[0]

    def _exit_job_browser(self) -> None:
        with self._lock:
            self._input_mode = "query"
            self._job_browser_notice = ""
        self.refresh()

    def info(self, message: str) -> None:
        if self.live_enabled():
            self._append_event("muted", message)
        elif self.console:
            self.console.print(f"[muted]{escape(message)}[/muted]")
        else:
            print(message)

    def success(self, message: str) -> None:
        if self.live_enabled():
            self._append_event("success", message)
        elif self.console:
            self.console.print(f"[success]{escape(message)}[/success]")
        else:
            print(message)

    def warning(self, message: str) -> None:
        if self.live_enabled():
            self._append_event("warning", message)
        elif self.console:
            self.console.print(f"[warning]{escape(message)}[/warning]")
        else:
            print(message)

    def error(self, message: str) -> None:
        if self.live_enabled():
            self._append_event("error", message)
        elif self.console:
            self.console.print(f"[error]{escape(message)}[/error]")
        else:
            print(message, file=sys.stderr)

    def status(self, message: str):
        return UIStatus(self, message)

    def show_help(self) -> None:
        if not self.rich_enabled():
            print("Commands: /jobs, /clear-finished, /help, /quit")
            return

        help_table = Table.grid(expand=True)
        help_table.add_column(style="accent", ratio=1)
        help_table.add_column(style="muted", ratio=3)
        help_table.add_row("/jobs", "Open the live job browser. Use arrows, Enter, and Esc/q.")
        help_table.add_row("/clear-finished", "Drop completed, failed, and cancelled jobs from this session.")
        help_table.add_row("/help", "Show command help.")
        help_table.add_row("/quit", "Cancel remaining jobs and exit cleanly.")

        self._set_main_renderable(
            Panel(
                help_table,
                title="[heading]Commands[/heading]",
                border_style="panel",
                padding=(0, 1),
            )
        )

    def show_media_candidates(self, candidates: list[MediaCandidate]) -> None:
        if not self.console:
            print("\nMatches:")
            for index, candidate in enumerate(candidates, start=1):
                media_label = "Movie" if candidate.media_type == "movie" else "Series"
                print(
                    f"  {index}. {candidate.title} ({candidate.year}) [{media_label}] [{candidate.imdb_id}]"
                )
            return

        table = Table(
            box=box.SIMPLE_HEAVY,
            expand=True,
            show_header=True,
            header_style="accent",
            border_style="panel",
        )
        table.add_column("#", justify="right", no_wrap=True, width=4)
        table.add_column("Title", min_width=30)
        table.add_column("Type", no_wrap=True)
        table.add_column("IMDb", no_wrap=True)

        for index, candidate in enumerate(candidates, start=1):
            media_label = "Movie" if candidate.media_type == "movie" else "Series"
            table.add_row(
                str(index),
                f"{escape(candidate.title)} ({escape(candidate.year)})",
                media_label,
                escape(candidate.imdb_id),
            )

        panel = Panel(
            table,
            title="[heading]Matches[/heading]",
            border_style="panel",
            padding=(0, 1),
        )
        if self.live_enabled():
            self._set_main_renderable(panel)
            return

        self.console.print()
        self.console.print(panel)

    def show_stream_candidates(self, candidates: list[StreamCandidate]) -> None:
        if not self.console:
            print("\nFiltered results (up to 3 per resolution):")
            for resolution in ("4K", "1080P"):
                print(f"\n{resolution}")
                section = [item for item in candidates if item.resolution == resolution]
                if not section:
                    print("  No matching results.")
                    continue
                print(f"  Showing {len(section)} match{'es' if len(section) != 1 else ''}.")
                for item in section:
                    status = "cached" if item.is_cached else "uncached"
                    label = (
                        "strict"
                        if item.is_strict_match
                        else f"fallback: {item.fallback_reason}"
                    )
                    print(
                        f"  {item.index}. {item.name} | {format_bytes(item.size_bytes)} | {status} | {label}"
                    )
                    if item.description:
                        print(f"     {item.description.splitlines()[0]}")
            return

        panels = []
        for resolution in ("4K", "1080P"):
            section = [item for item in candidates if item.resolution == resolution]
            table = Table(
                box=box.SIMPLE_HEAVY,
                expand=True,
                show_header=True,
                header_style="accent",
                border_style="panel",
            )
            table.add_column("#", justify="right", width=4, no_wrap=True)
            table.add_column("Release", min_width=32)
            table.add_column("Size", justify="right", no_wrap=True)
            table.add_column("Cache", no_wrap=True)
            table.add_column("Fit", min_width=18)

            if section:
                for item in section:
                    status = "cached" if item.is_cached else "uncached"
                    fit = (
                        "strict"
                        if item.is_strict_match
                        else f"fallback: {item.fallback_reason}"
                    )
                    release = escape(item.name)
                    if item.description:
                        release += f"\n{escape(item.description.splitlines()[0])}"
                    table.add_row(
                        str(item.index),
                        release,
                        format_bytes(item.size_bytes),
                        status,
                        escape(fit),
                    )
                subtitle = (
                    f"{len(section)} match{'es' if len(section) != 1 else ''}"
                )
            else:
                table.add_row("-", "No matching results.", "-", "-", "-")
                subtitle = "No candidates"

            panels.append(
                Panel(
                    table,
                    title=f"[heading]{resolution}[/heading]",
                    subtitle=f"[muted]{subtitle}[/muted]",
                    border_style="panel",
                    padding=(0, 1),
                )
            )

        group = Group(*panels)
        if self.live_enabled():
            self._set_main_renderable(group)
            return

        self.console.print()
        self.console.print(group)

    def show_enqueued(self, job: DownloadJobSnapshot) -> None:
        message = f"Queued job #{job.job_id}: {job.label} -> {job.output_dir}"
        if self.live_enabled():
            self.success(message)
            return
        self.success(message)

    def show_downloaded(self, destinations: list[Path]) -> None:
        if len(destinations) == 1:
            self.success(f"Downloaded: {destinations[0]}")
            return

        if not self.console:
            print("Downloaded files:")
            for destination in destinations:
                print(f"  {destination}")
            return

        table = Table(
            box=box.SIMPLE_HEAVY,
            expand=True,
            show_header=True,
            header_style="accent",
            border_style="panel",
        )
        table.add_column("#", justify="right", width=4, no_wrap=True)
        table.add_column("Saved File")
        for index, destination in enumerate(destinations, start=1):
            table.add_row(str(index), escape(str(destination)))

        panel = Panel(
            table,
            title="[success]Downloaded Files[/success]",
            border_style="panel",
            padding=(0, 1),
        )
        if self.live_enabled():
            self._set_main_renderable(panel)
            return

        self.console.print(panel)

    def build_layout(self):
        layout = Layout()
        layout.split_row(
            Layout(self._build_main_column(), name="main"),
            Layout(self._build_sidebar(), name="sidebar", size=44),
        )
        return layout

    def _build_main_column(self):
        parts = [
            Panel.fit(
                "[heading]Comet Search Download[/heading]\n[muted]Search, select, queue, repeat.[/muted]",
                border_style="panel",
                padding=(1, 2),
            )
        ]

        with self._lock:
            status_text = self._status_messages[-1] if self._status_messages else ""
            main_renderable = self._main_renderable
            prompt_label = self._prompt_label
            prompt_buffer = self._prompt_buffer
            event_messages = list(self._event_messages)
            input_mode = self._input_mode
            browser_notice = self._job_browser_notice

        if status_text:
            parts.append(
                Panel.fit(
                    f"[accent]{escape(status_text)}[/accent]",
                    title="[heading]Working[/heading]",
                    border_style="panel",
                    padding=(0, 2),
                )
            )
        if input_mode in {"jobs", "confirm_cancel"}:
            parts.append(self._build_job_browser_panel())
        elif main_renderable is not None:
            parts.append(main_renderable)

        events_table = Table.grid(expand=True)
        events_table.add_column(ratio=1)
        if event_messages:
            for level, message in event_messages:
                events_table.add_row(f"[{level}]{escape(message)}[/{level}]")
        else:
            events_table.add_row("[muted]Session events will appear here.[/muted]")
        parts.append(
            Panel(
                events_table,
                title="[heading]Session[/heading]",
                border_style="panel",
                padding=(0, 1),
            )
        )

        prompt_text = Text()
        if input_mode == "jobs":
            prompt_text.append("Job browser: ", style="accent")
            prompt_text.append(browser_notice or "Up/Down select, Enter manage, Esc/q return to search.", style="muted")
        elif input_mode == "confirm_cancel":
            prompt_text.append("Cancel confirmation: ", style="warning")
            prompt_text.append(browser_notice or "Press y/n or Enter/Esc/q.", style="muted")
        else:
            prompt_text.append(prompt_label or "Search query or command: ", style="accent")
            prompt_text.append(prompt_buffer, style="heading")
            if prompt_label:
                prompt_text.append("█", style="accent_soft")
            else:
                prompt_text.append("/help for commands", style="muted")
        parts.append(
            Panel(
                prompt_text,
                title="[heading]Prompt[/heading]",
                border_style="panel",
                padding=(0, 1),
            )
        )

        return Group(*parts)

    def _build_sidebar(self):
        jobs = self._download_manager.snapshot() if self._download_manager else []
        active = [job for job in jobs if job.status in {"resolving", "downloading"}]
        queued = [job for job in jobs if job.status == "queued"]
        finished = [job for job in jobs if job.status in {"completed", "failed", "cancelled"}]
        finished.sort(key=lambda job: job.finished_at or job.created_at, reverse=True)
        finished = finished[:6]

        return Group(
            self._build_job_panel("Active", active, "accent"),
            self._build_job_panel("Queued", queued, "muted"),
            self._build_job_panel("Finished This Session", finished, "success"),
        )

    def _build_job_panel(self, title: str, jobs: list[DownloadJobSnapshot], style: str):
        table = Table.grid(expand=True)
        table.add_column(ratio=1)
        if not jobs:
            table.add_row("[muted]None[/muted]")
        else:
            for job in jobs:
                lines = [f"[{style}]#{job.job_id}[/{style}] {escape(trim_text(job.label, 28))}"]
                lines.append(f"[muted]{escape(job.phase)}[/muted]")
                progress_line = self._job_progress_line(job)
                if progress_line:
                    lines.append(f"[accent_soft]{escape(progress_line)}[/accent_soft]")
                if job.status == "completed" and job.destinations:
                    lines.append(f"[success]{escape(trim_text(str(job.destinations[-1]), 32))}[/success]")
                elif job.status == "failed" and job.error_text:
                    lines.append(f"[error]{escape(trim_text(job.error_text, 32))}[/error]")
                elif job.status == "cancelled":
                    lines.append("[warning]Cancelled[/warning]")
                table.add_row("\n".join(lines))

        return Panel(
            table,
            title=f"[heading]{title}[/heading]",
            border_style="panel",
            padding=(0, 1),
        )

    def _build_job_browser_panel(self):
        jobs = self._browser_jobs()
        if not jobs:
            return Panel(
                "[muted]No downloads in this session yet. Press Esc/q to return to search.[/muted]",
                title="[heading]Jobs[/heading]",
                border_style="panel",
                padding=(1, 2),
            )

        selected_job = self._selected_browser_job()
        selected_id = selected_job.job_id if selected_job is not None else None
        table = Table(
            box=box.SIMPLE_HEAVY,
            expand=True,
            show_header=True,
            header_style="accent",
            border_style="panel",
        )
        table.add_column("", width=2, no_wrap=True)
        table.add_column("ID", justify="right", no_wrap=True, width=4)
        table.add_column("Status", no_wrap=True, width=12)
        table.add_column("Title", min_width=24)
        table.add_column("Progress", min_width=24)

        for job in jobs:
            is_selected = job.job_id == selected_id
            row_style = "heading" if is_selected else ""
            marker = ">" if is_selected else ""
            table.add_row(
                marker,
                str(job.job_id),
                job.status,
                escape(job.label),
                escape(self._job_progress_line(job)),
                style=row_style,
            )

        instructions = (
            "[muted]Up/Down select. Enter opens cancel prompt for queued/active jobs. Esc/q returns to search.[/muted]"
        )
        if self._input_mode == "confirm_cancel" and selected_job is not None:
            instructions = (
                f"[warning]Cancel job #{selected_job.job_id}?[/warning] "
                "[muted]Press y/n or Enter/Esc/q.[/muted]"
            )

        return Panel(
            Group(table, Text.from_markup(instructions)),
            title="[heading]Jobs[/heading]",
            border_style="panel",
            padding=(0, 1),
        )

    def _job_progress_line(self, job: DownloadJobSnapshot) -> str:
        details: list[str] = []
        if job.current_file_index is not None:
            details.append(f"file {job.current_file_index}/{job.total_files}")
        elif job.total_files > 1 and job.completed_files:
            details.append(f"file {job.completed_files}/{job.total_files}")
        if job.total_bytes:
            percent = (job.downloaded_bytes / job.total_bytes) * 100 if job.total_bytes else 0.0
            details.append(f"{percent:5.1f}%")
            details.append(f"{format_bytes(job.downloaded_bytes)} / {format_bytes(job.total_bytes)}")
        elif job.downloaded_bytes:
            details.append(f"{format_bytes(job.downloaded_bytes)} downloaded")
        if job.speed_bytes_per_second > 0:
            details.append(format_speed(job.speed_bytes_per_second))
        if job.eta_seconds is not None:
            details.append(f"ETA {format_eta(job.eta_seconds)}")
        return " | ".join(details)

    def print_shutdown_summary(self, jobs: list[DownloadJobSnapshot]) -> None:
        completed = [job for job in jobs if job.status == "completed"]
        failed = [job for job in jobs if job.status == "failed"]
        cancelled = [job for job in jobs if job.status == "cancelled"]
        self.info(
            f"Session ended. Completed {len(completed)}, failed {len(failed)}, cancelled {len(cancelled)}."
        )
        if completed:
            self.show_downloaded([path for job in completed for path in job.destinations])


class JobReporter:
    def __init__(self, manager: "DownloadManager", job_id: int) -> None:
        self._manager = manager
        self._job_id = job_id

    def ensure_not_cancelled(self) -> None:
        if self._manager.is_cancel_requested(self._job_id):
            raise DownloadCancelled()

    def set_resolving(self) -> None:
        self._manager.update_job(
            self._job_id,
            status="resolving",
            phase="Resolving links",
            started_at=time.time(),
            speed_bytes_per_second=0.0,
            eta_seconds=None,
        )

    def set_total_files(self, total_files: int) -> None:
        self._manager.update_job(self._job_id, total_files=total_files)

    def start_file(self, index: int, total_files: int, file_name: str, total_bytes: int | None) -> None:
        self._manager.update_job(
            self._job_id,
            status="downloading",
            phase=f"Downloading file {index}/{total_files}",
            current_file_index=index,
            current_file_name=file_name,
            total_files=total_files,
            total_bytes=total_bytes,
            downloaded_bytes=0,
            speed_bytes_per_second=0.0,
            eta_seconds=None,
        )

    def update_progress(
        self,
        *,
        downloaded_bytes: int,
        total_bytes: int | None,
        speed: float,
        completed_files: int,
        total_files: int,
    ) -> None:
        eta = None
        if total_bytes is not None and speed > 0:
            eta = max(total_bytes - downloaded_bytes, 0) / speed
        self._manager.update_job(
            self._job_id,
            status="downloading",
            phase=f"Downloading file {completed_files + 1}/{total_files}",
            downloaded_bytes=downloaded_bytes,
            total_bytes=total_bytes,
            completed_files=completed_files,
            total_files=total_files,
            speed_bytes_per_second=speed,
            eta_seconds=eta,
        )

    def finish_file(self, destination: Path, completed_files: int, total_files: int) -> None:
        self._manager.finish_file(self._job_id, destination, completed_files, total_files)

    def set_finalizing(self) -> None:
        self._manager.update_job(
            self._job_id,
            status="downloading",
            phase="Finalizing files",
            current_file_index=None,
            current_file_name="",
            downloaded_bytes=0,
            total_bytes=None,
            speed_bytes_per_second=0.0,
            eta_seconds=None,
        )


class DownloadManager:
    def __init__(
        self,
        max_parallel: int,
        event_callback: Callable[[str, str], None] | None = None,
    ) -> None:
        self._max_parallel = max_parallel
        self._event_callback = event_callback
        self._jobs: dict[int, DownloadJob] = {}
        self._queued_job_ids: deque[int] = deque()
        self._lock = threading.RLock()
        self._condition = threading.Condition(self._lock)
        self._next_job_id = 1
        self._accepting = True
        self._workers = [
            threading.Thread(target=self._worker_loop, name=f"download-worker-{index}", daemon=True)
            for index in range(max_parallel)
        ]
        for worker in self._workers:
            worker.start()

    def enqueue(
        self,
        *,
        label: str,
        host: str,
        candidate: StreamCandidate,
        output_dir: Path,
        fallback_name: str,
    ) -> DownloadJobSnapshot:
        with self._condition:
            if not self._accepting:
                raise RuntimeError("Downloads are shutting down.")
            job_id = self._next_job_id
            self._next_job_id += 1
            job = DownloadJob(
                job_id=job_id,
                label=label,
                host=host,
                candidate=candidate,
                output_dir=output_dir,
                staging_dir=build_staging_dir(output_dir, job_id),
                fallback_name=fallback_name,
            )
            self._jobs[job_id] = job
            self._queued_job_ids.append(job_id)
            self._condition.notify()
        self._emit("info", f"Queued job #{job_id}: {label}")
        return self.snapshot_job(job_id)

    def _worker_loop(self) -> None:
        while True:
            with self._condition:
                while not self._queued_job_ids and self._accepting:
                    self._condition.wait(timeout=0.2)

                if not self._queued_job_ids and not self._accepting:
                    return

                job_id = self._queued_job_ids.popleft()
                job = self._jobs.get(job_id)
                if job is None:
                    continue
                if job.cancel_requested:
                    job.status = "cancelled"
                    job.phase = "Cancelled before start"
                    job.finished_at = time.time()
                    continue

            reporter = JobReporter(self, job_id)
            try:
                reporter.set_resolving()
                staged_destinations = download_selected_stream(
                    job.host,
                    job.candidate,
                    job.staging_dir,
                    job.fallback_name,
                    reporter=reporter,
                    cancel_event=job.cancel_event,
                )
                reporter.set_finalizing()
                destinations = finalize_downloaded_files(
                    staged_destinations,
                    job.staging_dir,
                    job.output_dir,
                )
            except DownloadCancelled:
                cleanup_download_dir(job.staging_dir)
                self._mark_cancelled(job_id, "Cancelled")
            except Exception as error:
                cleanup_download_dir(job.staging_dir)
                self._mark_failed(job_id, str(error))
            else:
                self._mark_completed(job_id, destinations)

    def update_job(self, job_id: int, **changes: Any) -> None:
        with self._lock:
            job = self._jobs[job_id]
            for key, value in changes.items():
                setattr(job, key, value)

    def finish_file(self, job_id: int, destination: Path, completed_files: int, total_files: int) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.completed_files = completed_files
            job.total_files = total_files
            job.current_file_name = ""
            job.current_file_index = None
            job.downloaded_bytes = 0
            job.total_bytes = None
            job.speed_bytes_per_second = 0.0
            job.eta_seconds = None

    def is_cancel_requested(self, job_id: int) -> bool:
        with self._lock:
            job = self._jobs[job_id]
            return job.cancel_requested or job.cancel_event.is_set()

    def cancel(self, job_id: int) -> bool:
        with self._condition:
            job = self._jobs.get(job_id)
            if job is None or job.status in {"completed", "failed", "cancelled"}:
                return False
            job.cancel_requested = True
            job.cancel_event.set()
            if job.status == "queued":
                try:
                    self._queued_job_ids.remove(job_id)
                except ValueError:
                    pass
                cleanup_download_dir(job.staging_dir)
                job.status = "cancelled"
                job.phase = "Cancelled"
                job.finished_at = time.time()
                self._emit("warning", f"Cancelled queued job #{job_id}: {job.label}")
            else:
                self._emit("warning", f"Cancelling active job #{job_id}: {job.label}")
            self._condition.notify_all()
        return True

    def clear_finished(self) -> int:
        with self._condition:
            finished_ids = [
                job_id
                for job_id, job in self._jobs.items()
                if job.status in {"completed", "failed", "cancelled"}
            ]
            for job_id in finished_ids:
                self._jobs.pop(job_id, None)
            return len(finished_ids)

    def shutdown(self) -> list[DownloadJobSnapshot]:
        with self._condition:
            self._accepting = False
            for job_id in list(self._queued_job_ids):
                job = self._jobs.get(job_id)
                if job is None:
                    continue
                job.cancel_requested = True
                job.status = "cancelled"
                job.phase = "Cancelled during shutdown"
                job.finished_at = time.time()
                self._emit("warning", f"Cancelled queued job #{job_id}: {job.label}")
            self._queued_job_ids.clear()

            for job in self._jobs.values():
                if job.status in {"resolving", "downloading"}:
                    job.cancel_requested = True
                    job.cancel_event.set()

            self._condition.notify_all()

        for worker in self._workers:
            worker.join()

        return self.snapshot()

    def snapshot(self) -> list[DownloadJobSnapshot]:
        with self._lock:
            jobs = [self._make_snapshot(job) for job in self._jobs.values()]
        jobs.sort(key=lambda job: job.created_at)
        return jobs

    def snapshot_job(self, job_id: int) -> DownloadJobSnapshot:
        with self._lock:
            job = self._jobs[job_id]
            return self._make_snapshot(job)

    def _make_snapshot(self, job: DownloadJob) -> DownloadJobSnapshot:
        return DownloadJobSnapshot(
            job_id=job.job_id,
            label=job.label,
            status=job.status,
            phase=job.phase,
            created_at=job.created_at,
            started_at=job.started_at,
            finished_at=job.finished_at,
            total_files=job.total_files,
            completed_files=job.completed_files,
            current_file_index=job.current_file_index,
            current_file_name=job.current_file_name,
            total_bytes=job.total_bytes,
            downloaded_bytes=job.downloaded_bytes,
            speed_bytes_per_second=job.speed_bytes_per_second,
            eta_seconds=job.eta_seconds,
            destinations=tuple(job.destinations),
            error_text=job.error_text,
            cancel_requested=job.cancel_requested,
            output_dir=job.output_dir,
        )

    def _mark_completed(self, job_id: int, destinations: list[Path]) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.destinations = list(destinations)
            job.status = "completed"
            job.phase = "Completed"
            job.finished_at = time.time()
            job.current_file_index = None
            job.current_file_name = ""
            job.speed_bytes_per_second = 0.0
            job.eta_seconds = None
            job.downloaded_bytes = 0
            job.total_bytes = None
            outputs = [str(path) for path in job.destinations]
            label = job.label
        self._emit("success", f"Completed job #{job_id}: {label}")
        if outputs:
            self._emit("success", "Saved: " + ", ".join(outputs[:2]))

    def _mark_failed(self, job_id: int, error_text: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "failed"
            job.phase = "Failed"
            job.finished_at = time.time()
            job.error_text = error_text
            job.speed_bytes_per_second = 0.0
            job.eta_seconds = None
            label = job.label
        self._emit("error", f"Job #{job_id} failed: {label} ({error_text})")

    def _mark_cancelled(self, job_id: int, phase: str) -> None:
        with self._lock:
            job = self._jobs[job_id]
            job.status = "cancelled"
            job.phase = phase
            job.finished_at = time.time()
            job.speed_bytes_per_second = 0.0
            job.eta_seconds = None
            label = job.label
        self._emit("warning", f"Job #{job_id} cancelled: {label}")

    def _emit(self, level: str, message: str) -> None:
        if self._event_callback is not None:
            self._event_callback(level, message)


def trim_text(value: str, max_length: int) -> str:
    if len(value) <= max_length:
        return value
    return value[: max_length - 3] + "..."


UI = TerminalUI()


def default_download_dir() -> Path:
    if os.name == "nt":
        user_profile = os.environ.get("USERPROFILE", "").strip()
        if user_profile:
            return Path(user_profile) / "Downloads"

        home_drive = os.environ.get("HOMEDRIVE", "").strip()
        home_path = os.environ.get("HOMEPATH", "").strip()
        if home_drive and home_path:
            return Path(f"{home_drive}{home_path}") / "Downloads"

    return Path.home() / "Downloads"


def is_temp_directory(path: Path) -> bool:
    resolved = path.expanduser().resolve(strict=False)
    candidates = {Path(tempfile.gettempdir()).resolve(strict=False)}

    for env_key in ("TMPDIR", "TEMP", "TMP"):
        value = os.environ.get(env_key, "").strip()
        if value:
            candidates.add(Path(value).expanduser().resolve(strict=False))

    return any(resolved == candidate or candidate in resolved.parents for candidate in candidates)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Search Comet with a Real-Debrid token and download a result."
    )
    parser.add_argument("--query", help="Movie or show title to search for.")
    parser.add_argument("--token", help="Real-Debrid API token. Saved to .env.")
    parser.add_argument("--output-dir", help="Download directory.")
    parser.add_argument("--host", default=DEFAULT_HOST, help="Comet base URL.")
    parser.add_argument(
        "--restart-comet",
        action="store_true",
        help="Restart the local Comet process before searching.",
    )
    parser.add_argument(
        "--parallel-downloads",
        type=int,
        default=2,
        help="Maximum number of downloads to run at once.",
    )
    args = parser.parse_args()
    if args.parallel_downloads < 1:
        parser.error("--parallel-downloads must be at least 1.")
    return args


def parse_env_file(path: Path) -> tuple[list[str], dict[str, str]]:
    if not path.exists():
        return [], {}

    lines = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = unquote_env_value(value.strip())
    return lines, values


def unquote_env_value(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        quote = value[0]
        inner = value[1:-1]
        if quote == '"':
            inner = inner.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
        return inner
    return value


def format_env_value(value: str) -> str:
    if not value:
        return '""'
    if re.fullmatch(r"[A-Za-z0-9_./:-]+", value):
        return value
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def upsert_env_value(path: Path, key: str, value: str) -> bool:
    lines, existing = parse_env_file(path)
    if existing.get(key) == value:
        return False

    new_line = f"{key}={format_env_value(value)}"
    updated = False
    for index, line in enumerate(lines):
        if line.strip().startswith(f"{key}="):
            lines[index] = new_line
            updated = True
            break

    if not updated:
        if lines and lines[-1].strip():
            lines.append("")
        lines.append(new_line)

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return True


def load_env_values() -> dict[str, str]:
    _, values = parse_env_file(ENV_PATH)
    return values


def load_token(cli_token: str | None) -> tuple[str, bool]:
    if cli_token:
        token = cli_token.strip()
        return token, upsert_env_value(ENV_PATH, REALDEBRID_ENV_KEY, token)

    token = load_env_values().get(REALDEBRID_ENV_KEY, "").strip()
    if token:
        return token, False

    token = UI.prompt("Real-Debrid API token: ").strip()
    if not token:
        raise SystemExit("A Real-Debrid API token is required.")
    return token, upsert_env_value(ENV_PATH, REALDEBRID_ENV_KEY, token)


def resolve_output_dir(cli_output_dir: str | None) -> tuple[Path, bool]:
    if cli_output_dir:
        path = Path(cli_output_dir).expanduser().resolve()
        return path, False

    configured = load_env_values().get(DOWNLOAD_DIR_ENV_KEY, "").strip()
    if configured:
        path = Path(configured).expanduser().resolve()
        if not is_temp_directory(path):
            return path, False

    return default_download_dir().expanduser().resolve(), False


def build_api_prefix() -> str:
    public_api_token = load_env_values().get(PUBLIC_API_TOKEN_ENV_KEY, "").strip()
    return f"/s/{public_api_token}" if public_api_token else ""


def http_json(
    url: str, *, headers: dict[str, str] | None = None, timeout: int = 30
) -> dict[str, Any]:
    request = Request(url, headers=headers or {})
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GET {url} failed with HTTP {error.code}: {body[:200]}")
    except URLError as error:
        raise RuntimeError(f"GET {url} failed: {error.reason}")


def http_response(
    url: str,
    *,
    headers: dict[str, str] | None = None,
    timeout: int = 30,
    follow_redirects: bool = True,
):
    request = Request(url, headers=headers or {})

    if follow_redirects:
        return urlopen(request, timeout=timeout)

    opener = urllib.request.build_opener(NoRedirectProcessor())
    return opener.open(request, timeout=timeout)


class NoRedirectProcessor(urllib.request.BaseHandler):
    def http_response(self, request, response):
        return response

    https_response = http_response


def check_health(host: str) -> bool:
    try:
        data = http_json(f"{host.rstrip('/')}/health", timeout=5)
        return data.get("status") == "ok"
    except Exception:
        return False


def has_module(python_executable: str, module_name: str) -> bool:
    result = subprocess.run(
        [python_executable, "-c", f"import {module_name}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        cwd=ROOT_DIR,
        check=False,
    )
    return result.returncode == 0


def pick_comet_launch_command() -> LaunchCommand:
    override = os.environ.get("COMET_PYTHON", "").strip()
    candidates: list[LaunchCommand] = []

    if override:
        candidates.append(
            LaunchCommand(
                argv=[override, "-m", "comet.main"],
                description=f"COMET_PYTHON={override}",
            )
        )

    venv_python = ROOT_DIR / ".venv" / "bin" / "python"
    if venv_python.exists():
        candidates.append(
            LaunchCommand(
                argv=[str(venv_python), "-m", "comet.main"],
                description=str(venv_python),
            )
        )

    uv_path = shutil.which("uv")
    if uv_path:
        candidates.append(
            LaunchCommand(
                argv=[uv_path, "run", "python", "-m", "comet.main"],
                description="uv run python",
            )
        )

    candidates.append(
        LaunchCommand(
            argv=[sys.executable, "-m", "comet.main"],
            description=sys.executable,
        )
    )

    for candidate in candidates:
        program = Path(candidate.argv[0])
        if candidate.argv[0] != "uv" and not program.exists() and not shutil.which(candidate.argv[0]):
            continue
        if candidate.argv[0].endswith("python") or candidate.argv[0].endswith("python3") or len(candidate.argv) >= 3 and candidate.argv[1] == "run":
            if candidate.argv[0] == uv_path:
                return candidate
            python_executable = candidate.argv[0]
            if has_module(python_executable, "uvicorn"):
                return candidate

    raise RuntimeError(
        "Could not find a Python environment that can run Comet. "
        "Install the project dependencies first, or set COMET_PYTHON to a Python interpreter "
        "that has Comet's dependencies installed."
    )


def stop_existing_comet_processes(host: str) -> bool:
    result = subprocess.run(
        ["pgrep", "-af", "comet.main"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        cwd=ROOT_DIR,
        check=False,
    )
    stopped_any = False
    for line in result.stdout.splitlines():
        parts = line.strip().split(maxsplit=1)
        if not parts:
            continue
        try:
            pid = int(parts[0])
        except ValueError:
            continue
        cmdline = parts[1] if len(parts) > 1 else ""
        if str(ROOT_DIR) not in cmdline:
            continue
        try:
            os.kill(pid, 15)
            stopped_any = True
        except ProcessLookupError:
            continue

    if stopped_any:
        deadline = time.time() + 10
        while time.time() < deadline and check_health(host):
            time.sleep(0.5)
    return stopped_any


def ensure_comet_running(host: str, restart: bool = False) -> None:
    if restart and check_health(host):
        with UI.status("Restarting local Comet backend"):
            stop_existing_comet_processes(host)

    if check_health(host):
        return

    launch = pick_comet_launch_command()
    STARTUP_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with STARTUP_LOG_PATH.open("ab") as log_file:
        subprocess.Popen(
            launch.argv,
            cwd=ROOT_DIR,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )

    deadline = time.time() + HEALTHCHECK_TIMEOUT
    with UI.status(f"Starting local Comet backend via {launch.description}"):
        while time.time() < deadline:
            if check_health(host):
                return
            time.sleep(1)

    raise RuntimeError(
        f"Comet did not become healthy within {HEALTHCHECK_TIMEOUT}s using {launch.description}. "
        f"Check {STARTUP_LOG_PATH}."
    )


def build_b64_config(token: str) -> str:
    config: dict[str, Any] = {
        "debridServices": [{"service": "realdebrid", "apiKey": token}],
        "enableTorrent": False,
        "maxResultsPerResolution": 10,
        "maxSize": 0.0,
        "cachedOnly": False,
        "sortCachedUncachedTogether": False,
        "removeTrash": True,
        "deduplicateStreams": True,
        "languages": {
            "required": ["en"],
            "allowed": [],
            "exclude": [],
            "preferred": ["en"],
        },
        "resolutions": {
            "r2160p": True,
            "r1440p": False,
            "r1080p": True,
            "r720p": False,
            "r576p": False,
            "r480p": False,
            "r360p": False,
            "r240p": False,
            "unknown": False,
        },
        "options": {
            "remove_ranks_under": 0,
            "allow_english_in_languages": True,
            "remove_unknown_languages": True,
        },
    }
    raw = json.dumps(config, separators=(",", ":")).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def tmdb_json(path: str, params: dict[str, str]) -> dict[str, Any]:
    query = urlencode(params)
    url = f"{TMDB_BASE_URL}{path}?{query}"
    return http_json(url, headers=TMDB_HEADERS)


def fetch_imdb_id_for_tmdb(media_type: str, tmdb_id: int) -> str | None:
    endpoint = "movie" if media_type == "movie" else "tv"
    data = http_json(
        f"{TMDB_BASE_URL}/{endpoint}/{tmdb_id}/external_ids",
        headers=TMDB_HEADERS,
    )
    imdb_id = data.get("imdb_id")
    return imdb_id if isinstance(imdb_id, str) and imdb_id else None


def search_tmdb(query: str) -> list[MediaCandidate]:
    movie_data = tmdb_json("/search/movie", {"query": query, "include_adult": "false"})
    tv_data = tmdb_json("/search/tv", {"query": query, "include_adult": "false"})

    raw_candidates: list[tuple[str, dict[str, Any]]] = []
    raw_candidates.extend(("movie", item) for item in movie_data.get("results", [])[:5])
    raw_candidates.extend(("series", item) for item in tv_data.get("results", [])[:5])

    candidates: list[MediaCandidate] = []
    for media_type, item in raw_candidates:
        tmdb_id = item.get("id")
        if not tmdb_id:
            continue
        try:
            imdb_id = fetch_imdb_id_for_tmdb(media_type, int(tmdb_id))
        except Exception:
            continue
        if not imdb_id or not imdb_id.startswith("tt"):
            continue

        if media_type == "movie":
            title = item.get("title") or "Untitled"
            year = (item.get("release_date") or "")[:4]
        else:
            title = item.get("name") or "Untitled"
            year = (item.get("first_air_date") or "")[:4]

        candidates.append(
            MediaCandidate(
                media_type=media_type,
                tmdb_id=int(tmdb_id),
                title=title,
                year=year or "?",
                imdb_id=imdb_id,
            )
        )

    seen: set[tuple[str, str]] = set()
    deduped: list[MediaCandidate] = []
    for candidate in candidates:
        key = (candidate.media_type, candidate.imdb_id)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(candidate)
    return deduped


def prompt_choice(prompt: str, max_value: int) -> int:
    while True:
        raw = UI.prompt(prompt).strip()
        if raw.isdigit() and 1 <= int(raw) <= max_value:
            return int(raw)
        UI.warning(f"Enter a number between 1 and {max_value}.")


def choose_media(candidates: list[MediaCandidate]) -> MediaCandidate:
    if not candidates:
        raise SystemExit("No movie or show candidates were found for that query.")

    UI.show_media_candidates(candidates)
    return candidates[prompt_choice("Choose a title: ", len(candidates)) - 1]


def prompt_series_scope() -> tuple[int, int | None]:
    while True:
        season = UI.prompt("Season number: ").strip()
        episode = UI.prompt("Episode number (leave blank for full season): ").strip()
        if not season.isdigit() or int(season) <= 0:
            UI.warning("Season number must be a positive integer.")
            continue
        if not episode:
            return int(season), None
        if episode.isdigit() and int(episode) > 0:
            return int(season), int(episode)
        UI.warning("Episode number must be blank or a positive integer.")


def normalize_resolution(name: str) -> str | None:
    upper = name.upper()
    if "4K" in upper or "2160P" in upper:
        return "4K"
    if "1080P" in upper:
        return "1080P"
    return None


def is_cached_stream(name: str) -> bool:
    return "⚡" in name or " C]" in name or "[RD C]" in name


def resolution_rule_for_context(
    resolution: str, context: SizePreferenceContext
) -> dict[str, int]:
    rule = RESOLUTION_RULES[resolution]
    if context.media_type == "series" and context.season is not None and context.episode is None:
        return {
            "min_bytes": rule["min_bytes"] * FULL_SEASON_SIZE_MULTIPLIER,
            "max_bytes": rule["max_bytes"] * FULL_SEASON_SIZE_MULTIPLIER,
        }
    return rule


def build_stream_candidates(
    streams: list[dict[str, Any]], context: SizePreferenceContext
) -> list[StreamCandidate]:
    results: list[StreamCandidate] = []
    next_index = 1
    for stream in streams:
        name = str(stream.get("name", ""))
        resolution = normalize_resolution(name)
        if resolution is None:
            continue

        behavior_hints = stream.get("behaviorHints") or {}
        size_bytes = behavior_hints.get("videoSize")
        if not isinstance(size_bytes, int) or size_bytes <= 0:
            continue

        rule = resolution_rule_for_context(resolution, context)
        is_strict_match = True
        preferred_distance_bytes = 0
        fallback_reason = ""
        if size_bytes < rule["min_bytes"]:
            is_strict_match = False
            preferred_distance_bytes = rule["min_bytes"] - size_bytes
            fallback_reason = f"below preferred size floor ({format_bytes(size_bytes)})"
        elif size_bytes > rule["max_bytes"]:
            is_strict_match = False
            preferred_distance_bytes = size_bytes - rule["max_bytes"]
            fallback_reason = f"above preferred size ceiling ({format_bytes(size_bytes)})"

        playback_url = stream.get("url")
        if not isinstance(playback_url, str) or not playback_url:
            continue

        results.append(
            StreamCandidate(
                index=next_index,
                resolution=resolution,
                is_cached=is_cached_stream(name),
                size_bytes=size_bytes,
                name=name,
                description=str(stream.get("description", "")).strip(),
                playback_url=playback_url,
                is_strict_match=is_strict_match,
                preferred_distance_bytes=preferred_distance_bytes,
                fallback_reason=fallback_reason,
            )
        )
        next_index += 1
    return results


def group_top_streams(candidates: list[StreamCandidate]) -> list[StreamCandidate]:
    grouped: list[StreamCandidate] = []
    for resolution in ("4K", "1080P"):
        resolution_candidates = [item for item in candidates if item.resolution == resolution]
        strict_matches = [item for item in resolution_candidates if item.is_strict_match]
        fallback_matches = [item for item in resolution_candidates if not item.is_strict_match]
        strict_matches.sort(key=lambda item: (not item.is_cached, item.index))
        fallback_matches.sort(
            key=lambda item: (
                item.preferred_distance_bytes,
                not item.is_cached,
                item.size_bytes,
                item.index,
            )
        )

        selected = strict_matches[:STREAM_RESULT_LIMIT]
        remaining = STREAM_RESULT_LIMIT - len(selected)
        if remaining > 0:
            selected.extend(fallback_matches[:remaining])
        grouped.extend(selected)

    for new_index, candidate in enumerate(grouped, start=1):
        candidate.index = new_index
    return grouped


def format_bytes(size_bytes: int) -> str:
    return f"{size_bytes / 1024**3:.2f} GB"


def print_streams(candidates: list[StreamCandidate]) -> None:
    if not candidates:
        raise SystemExit(
            "No streams matched the current filters for 4K/1080P, size, and English language."
        )

    UI.show_stream_candidates(candidates)


def fetch_streams(host: str, b64config: str, media_type: str, media_id: str) -> list[dict[str, Any]]:
    api_prefix = build_api_prefix()
    url = f"{host.rstrip('/')}{api_prefix}/{b64config}/stream/{media_type}/{media_id}.json"
    payload = http_json(url, timeout=120)
    return payload.get("streams", [])


def choose_stream(candidates: list[StreamCandidate]) -> StreamCandidate:
    return candidates[prompt_choice("Choose a stream to download: ", len(candidates)) - 1]


def sanitize_filename(value: str) -> str:
    cleaned = SAFE_FILENAME_CHARS.sub(" ", value).strip().rstrip(".")
    cleaned = re.sub(r"\s+", " ", cleaned)
    return cleaned or "download.bin"


def looks_generic_filename(filename: str) -> bool:
    normalized = filename.lower()
    return (
        normalized.startswith("rd comet ")
        or normalized.startswith("comet ")
        or normalized in {"download.mkv", "video.mkv", "download.bin"}
    )


def format_eta(seconds: float | None) -> str:
    if seconds is None or seconds < 0 or seconds == float("inf"):
        return "--:--"
    total = int(seconds)
    minutes, secs = divmod(total, 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours:d}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


def format_speed(bytes_per_second: float) -> str:
    return f"{bytes_per_second / 1024**2:.2f} MB/s"


def build_preferred_filename_base(
    media: MediaCandidate,
    resolution: str,
    season: int | None = None,
    episode: int | None = None,
) -> str:
    if resolution == "4K":
        resolution = "2160p"
    elif resolution == "1080P":
        resolution = "1080p"

    parts = [media.title]
    if media.year and media.year != "?":
        parts.append(f"({media.year})")
    if media.media_type == "series" and season is not None:
        if episode is None:
            parts.append(f"Season {season}")
        else:
            parts.append(f"S{season:02d}E{episode:02d}")
    parts.append(resolution)
    return " ".join(parts)


def build_collection_dir_name(media: MediaCandidate, season: int | None = None) -> str:
    parts = [media.title]
    if media.media_type == "series" and season is not None:
        parts.append(f"Season {season:02d}")
    if media.year and media.year != "?":
        parts.append(f"({media.year})")
    return sanitize_filename(" ".join(parts))


def build_staging_dir(output_dir: Path, job_id: int) -> Path:
    return output_dir.parent / f".{output_dir.name}.job-{job_id}.partial"


def extract_filename(headers, fallback_name: str, fallback_ext: str = ".mkv") -> str:
    header = headers.get("Content-Disposition", "")
    match = CONTENT_DISPOSITION_FILENAME.search(header)
    if match:
        filename = os.path.basename(match.group(1).strip().strip('"'))
        if filename and not looks_generic_filename(filename):
            return sanitize_filename(filename)

    cleaned = sanitize_filename(fallback_name)
    if "." not in cleaned:
        cleaned += fallback_ext
    return cleaned


def reserve_destination_path(output_dir: Path, filename: str) -> tuple[Path, Path]:
    destination = output_dir / filename
    suffix = 1
    while destination.exists() or destination.with_name(f"{destination.name}.part").exists():
        destination = destination.with_name(f"{destination.stem} ({suffix}){destination.suffix}")
        suffix += 1
    return destination, destination.with_name(f"{destination.name}.part")


def cleanup_download_dir(path: Path) -> None:
    if not path.exists():
        return
    if path.is_dir():
        shutil.rmtree(path, ignore_errors=True)
        return
    path.unlink(missing_ok=True)


def finalize_downloaded_files(
    staged_paths: list[Path],
    staging_dir: Path,
    output_dir: Path,
) -> list[Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    finalized_paths: list[Path] = []
    for staged_path in staged_paths:
        destination, _ = reserve_destination_path(output_dir, staged_path.name)
        shutil.move(str(staged_path), str(destination))
        finalized_paths.append(destination)
    cleanup_download_dir(staging_dir)
    return finalized_paths


def download_from_url_with_progress(
    download_url: str,
    output_dir: Path,
    fallback_name: str,
    *,
    reporter: JobReporter | None = None,
    cancel_event: threading.Event | None = None,
    completed_files: int = 0,
    total_files: int = 1,
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    if cancel_event is not None and cancel_event.is_set():
        raise DownloadCancelled()

    with urlopen(download_url, timeout=300) as download_response:
        filename = extract_filename(download_response.headers, fallback_name)
        destination, temp_destination = reserve_destination_path(output_dir, filename)
        total_bytes = download_response.headers.get("Content-Length")
        total_bytes_int = int(total_bytes) if total_bytes and total_bytes.isdigit() else None
        downloaded_bytes = 0
        started_at = time.time()
        if reporter is not None:
            reporter.start_file(completed_files + 1, total_files, filename, total_bytes_int)

        try:
            with temp_destination.open("wb") as file_handle:
                while True:
                    if cancel_event is not None and cancel_event.is_set():
                        raise DownloadCancelled()
                    if reporter is not None:
                        reporter.ensure_not_cancelled()

                    chunk = download_response.read(1024 * 1024)
                    if not chunk:
                        break
                    file_handle.write(chunk)
                    downloaded_bytes += len(chunk)

                    elapsed = max(time.time() - started_at, 1e-6)
                    speed = downloaded_bytes / elapsed
                    if reporter is not None:
                        reporter.update_progress(
                            downloaded_bytes=downloaded_bytes,
                            total_bytes=total_bytes_int,
                            speed=speed,
                            completed_files=completed_files,
                            total_files=total_files,
                        )
            os.replace(temp_destination, destination)
        except Exception:
            temp_destination.unlink(missing_ok=True)
            raise

    return destination


def build_file_fallback_name(file_name: str, default_name: str) -> str:
    basename = PurePosixPath(file_name).name.strip()
    if not basename:
        return default_name
    cleaned = sanitize_filename(basename)
    return cleaned or default_name


def download_selected_stream(
    host: str,
    candidate: StreamCandidate,
    output_dir: Path,
    fallback_name: str,
    *,
    reporter: JobReporter | None = None,
    cancel_event: threading.Event | None = None,
) -> list[Path]:
    playback_url = urljoin(f"{host.rstrip('/')}/", candidate.playback_url.lstrip("/"))
    request = Request(playback_url)

    opener = urllib.request.build_opener(NoRedirectHandler(), NoRedirectProcessor())
    if reporter is not None:
        reporter.ensure_not_cancelled()
        reporter.set_resolving()
    if cancel_event is not None and cancel_event.is_set():
        raise DownloadCancelled()
    try:
        response = opener.open(request, timeout=30)
    except HTTPError as error:
        response = error

    response_code = response.code
    headers = response.headers
    body_bytes = response.read() if response_code == 200 else None

    if response_code in (301, 302, 303, 307, 308):
        location = headers.get("Location", "").strip()
        if not location:
            raise RuntimeError("Playback redirect did not include a download URL.")
        download_url = urljoin(playback_url, location)
        if reporter is not None:
            reporter.set_total_files(1)
        destination = download_from_url_with_progress(
            download_url,
            output_dir,
            fallback_name,
            reporter=reporter,
            cancel_event=cancel_event,
        )
        if reporter is not None:
            reporter.finish_file(destination, 1, 1)
        return [destination]

    content_type = headers.get("Content-Type", "")
    if response_code == 200 and "application/json" in content_type:
        payload = json.loads((body_bytes or b"").decode("utf-8"))
        downloads = payload.get("downloads")
        if not isinstance(downloads, list) or not downloads:
            raise RuntimeError("Playback JSON response did not include downloadable files.")

        valid_downloads: list[tuple[str, str]] = []
        for item in downloads:
            if not isinstance(item, dict):
                continue
            download_url = str(item.get("url", "")).strip()
            if not download_url:
                continue
            per_file_name = build_file_fallback_name(str(item.get("name", "")), fallback_name)
            valid_downloads.append((download_url, per_file_name))

        if not valid_downloads:
            raise RuntimeError("Playback JSON response did not contain valid downloadable URLs.")

        if reporter is not None:
            reporter.set_total_files(len(valid_downloads))
        destinations: list[Path] = []
        for index, (download_url, per_file_name) in enumerate(valid_downloads, start=1):
            if cancel_event is not None and cancel_event.is_set():
                raise DownloadCancelled()
            if reporter is not None:
                reporter.ensure_not_cancelled()
            destination = download_from_url_with_progress(
                download_url,
                output_dir,
                per_file_name,
                reporter=reporter,
                cancel_event=cancel_event,
                completed_files=index - 1,
                total_files=len(valid_downloads),
            )
            destinations.append(destination)
            if reporter is not None:
                reporter.finish_file(destination, index, len(valid_downloads))
        return destinations

    body = (body_bytes or response.read()).decode("utf-8", errors="replace")
    raise RuntimeError(
        f"Playback did not return a download URL or file list (HTTP {response_code}): {body[:200]}"
    )


def emit_ui_event(level: str, message: str) -> None:
    if level == "success":
        UI.success(message)
    elif level == "warning":
        UI.warning(message)
    elif level == "error":
        UI.error(message)
    else:
        UI.info(message)


def queue_download_for_query(
    *,
    host: str,
    token: str,
    base_output_dir: Path,
    manager: DownloadManager,
    query: str,
) -> None:
    with UI.status(f"Searching TMDB for {query}"):
        candidates = search_tmdb(query)
    chosen_media = choose_media(candidates)
    media_id = chosen_media.imdb_id
    season = None
    episode = None

    if chosen_media.media_type == "series":
        season, episode = prompt_series_scope()
        media_id = (
            f"{media_id}:{season}"
            if episode is None
            else f"{media_id}:{season}:{episode}"
        )

    b64config = build_b64_config(token)
    with UI.status("Fetching stream candidates"):
        raw_streams = fetch_streams(host, b64config, chosen_media.media_type, media_id)
        size_context = SizePreferenceContext(
            media_type=chosen_media.media_type,
            season=season,
            episode=episode,
        )
        grouped_streams = group_top_streams(
            build_stream_candidates(raw_streams, size_context)
        )
    print_streams(grouped_streams)
    selected_stream = choose_stream(grouped_streams)
    preferred_name = build_preferred_filename_base(
        chosen_media,
        selected_stream.resolution,
        season=season,
        episode=episode,
    )
    target_dir = base_output_dir / build_collection_dir_name(chosen_media, season=season)
    job = manager.enqueue(
        label=preferred_name,
        host=host,
        candidate=selected_stream,
        output_dir=target_dir,
        fallback_name=preferred_name,
    )
    UI.show_enqueued(job)


def handle_command(raw_command: str, manager: DownloadManager) -> bool:
    parts = raw_command.split()
    command = parts[0].lower()

    if command == "/jobs":
        UI.show_jobs(manager.snapshot())
        return False

    if command == "/cancel":
        if len(parts) != 2 or not parts[1].isdigit():
            UI.warning("Usage: /cancel <job_id>")
            return False
        job_id = int(parts[1])
        if manager.cancel(job_id):
            UI.warning(f"Cancel requested for job #{job_id}.")
        else:
            UI.warning(f"Could not cancel job #{job_id}.")
        return False

    if command == "/clear-finished":
        cleared = manager.clear_finished()
        UI.info(f"Cleared {cleared} finished job{'s' if cleared != 1 else ''}.")
        return False

    if command == "/help":
        UI.show_help()
        return False

    if command == "/quit":
        return True

    UI.warning("Unknown command. Use /help to list available commands.")
    return False


def main() -> None:
    args = parse_args()
    token, token_saved = load_token(args.token)
    base_output_dir, output_saved = resolve_output_dir(args.output_dir)
    manager = DownloadManager(args.parallel_downloads, event_callback=emit_ui_event)
    UI.bind_download_manager(manager)

    UI.start_session()
    shutdown_jobs: list[DownloadJobSnapshot] = []
    try:
        should_restart = args.restart_comet
        ensure_comet_running(args.host, restart=should_restart)
        if token_saved:
            UI.success(f"Saved {REALDEBRID_ENV_KEY} to {ENV_PATH}.")
        if output_saved:
            UI.success(f"Saved {DOWNLOAD_DIR_ENV_KEY} to {ENV_PATH}.")

        UI.show_header()
        UI.info(
            f"Download queue ready. Running up to {args.parallel_downloads} download"
            f"{'s' if args.parallel_downloads != 1 else ''} at once."
        )

        query_override = args.query
        while True:
            UI.blank()
            try:
                raw_value = (query_override or UI.prompt("Search query or command: ")).strip()
            except EOFError:
                raw_value = "/quit"
            query_override = None
            if not raw_value:
                UI.warning("A search query or command is required.")
                continue

            if raw_value.startswith("/"):
                if handle_command(raw_value, manager):
                    break
                continue

            queue_download_for_query(
                host=args.host,
                token=token,
                base_output_dir=base_output_dir,
                manager=manager,
                query=raw_value,
            )
    finally:
        shutdown_jobs = manager.shutdown()
        UI.stop_session()
        if shutdown_jobs:
            UI.print_shutdown_summary(shutdown_jobs)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        raise SystemExit(130)
