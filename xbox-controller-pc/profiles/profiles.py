"""
Profile management: loads JSON profile files and auto-selects the active one
based on the foreground window title (Windows GetForegroundWindow).

Profile JSON fields (all optional — missing fields fall back to settings.py defaults):
  name              : str
  match_patterns    : list[str]  — substrings matched against window title
  mouse_sensitivity : float
  mouse_accel_exponent : float
  scroll_sensitivity : float
  trigger_threshold : float
  button_actions    : {button_name: action_str}   (A/B/X/Y/START/LSTICK)
  lb_shortcuts      : {button_name: action_str}
  dpad_actions      : {direction: action_str}      (up/down/left/right)
  lt_action         : str
  rt_action         : str
"""
import json
import logging
import os
import time
from pathlib import Path
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)

_PROFILES_DIR = Path(__file__).parent
_WINDOW_POLL_INTERVAL = 1.0   # seconds between foreground-window checks

# ---------------------------------------------------------------------------
# Foreground window title (Windows only)
# ---------------------------------------------------------------------------
try:
    import ctypes as _ctypes

    _user32 = _ctypes.windll.user32  # type: ignore[attr-defined]

    def _get_foreground_title() -> str:
        hwnd = _user32.GetForegroundWindow()
        length = _user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return ""
        buf = _ctypes.create_unicode_buffer(length + 1)
        _user32.GetWindowTextW(hwnd, buf, length + 1)
        return buf.value

    _WIN32_AVAILABLE = True

except (OSError, AttributeError):
    _WIN32_AVAILABLE = False

    def _get_foreground_title() -> str:  # type: ignore[misc]
        return ""


# ---------------------------------------------------------------------------
# ProfileManager
# ---------------------------------------------------------------------------

class ProfileManager:
    def __init__(self) -> None:
        self._profiles: List[dict] = []
        self._default: dict = {}
        self._active: dict = {}
        self._last_poll: float = 0.0
        self._last_title: str = ""
        self.load_all()

    # ------------------------------------------------------------------
    # Load
    # ------------------------------------------------------------------

    def load_all(self) -> None:
        """Scan profiles/ for JSON files and build the profile list."""
        self._profiles = []
        self._default = {}

        for path in sorted(_PROFILES_DIR.glob("*.json")):
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if data.get("name") == "default":
                    self._default = data
                else:
                    self._profiles.append(data)
                logger.info("Loaded profile: %s", path.name)
            except Exception:
                logger.exception("Failed to load profile: %s", path)

        self._active = self._default

    # ------------------------------------------------------------------
    # Tick — call once per second (cheaply) or from the main loop
    # ------------------------------------------------------------------

    def update(self) -> bool:
        """
        Check the foreground window and switch profile if needed.
        Returns True if the active profile changed.
        """
        now = time.monotonic()
        if now - self._last_poll < _WINDOW_POLL_INTERVAL:
            return False
        self._last_poll = now

        title = _get_foreground_title()
        if title == self._last_title:
            return False
        self._last_title = title

        matched = self._match(title)
        if matched is self._active:
            return False
        self._active = matched
        logger.info("Profile: %s  (window: %r)", matched.get("name", "?"), title[:60])
        return True

    def current(self) -> dict:
        """Return the currently active profile dict."""
        return self._active

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _match(self, title: str) -> dict:
        for profile in self._profiles:
            for pattern in profile.get("match_patterns", []):
                if pattern.lower() in title.lower():
                    return profile
        return self._default
