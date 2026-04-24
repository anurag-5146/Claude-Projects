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
import time
from pathlib import Path
from typing import Any, Dict, List, Tuple

logger = logging.getLogger(__name__)

_PROFILES_DIR = Path(__file__).parent
_WINDOW_POLL_INTERVAL = 1.0   # seconds between foreground-window checks

# ---------------------------------------------------------------------------
# Key normalisers — JSON uses human-readable names; internals use int/tuple
# ---------------------------------------------------------------------------
_BTN_NAME_TO_IDX: Dict[str, int] = {
    "A": 0, "B": 1, "X": 2, "Y": 3,
    "LB": 4, "RB": 5, "BACK": 6, "START": 7,
    "LSTICK": 8, "RSTICK": 9, "GUIDE": 10,
}

_DPAD_NAME_TO_HAT: Dict[str, Tuple[int, int]] = {
    "up": (0, 1), "down": (0, -1),
    "left": (-1, 0), "right": (1, 0),
}


def _normalize(data: dict) -> dict:
    """Convert friendly JSON keys to the int/tuple keys DesktopMode expects."""
    data = dict(data)
    for field in ("button_actions", "lb_shortcuts"):
        if field in data:
            data[field] = {
                _BTN_NAME_TO_IDX.get(str(k).upper(), k): v
                for k, v in data[field].items()
            }
    if "dpad_actions" in data:
        data["dpad_actions"] = {
            _DPAD_NAME_TO_HAT.get(str(k).lower(), k): v
            for k, v in data["dpad_actions"].items()
        }
    return data

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
                data = _normalize(json.loads(path.read_text(encoding="utf-8")))
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

    # ------------------------------------------------------------------
    # Save / edit
    # ------------------------------------------------------------------

    def all_profiles(self) -> List[dict]:
        """Default first, then the rest — the full active set."""
        return ([self._default] if self._default else []) + list(self._profiles)

    def save(self, profile: dict) -> Path:
        """Write `profile` back to its JSON file (by `name`), preserving
        friendly key names (button_actions keyed by "A"/"B"/etc).

        Returns the path written.
        """
        name = profile.get("name", "").strip()
        if not name:
            raise ValueError("Profile has no 'name' — cannot save.")
        path = _PROFILES_DIR / f"{name}.json"
        data = _denormalize(profile)
        path.write_text(
            json.dumps(data, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        logger.info("Saved profile: %s", path.name)
        return path

    def reload(self) -> None:
        """Re-read all profiles from disk. Updates the active pointer to
        whichever profile currently matches the foreground window."""
        active_name = self._active.get("name", "")
        self.load_all()
        self._last_title = ""   # force re-match on next update()
        for p in self.all_profiles():
            if p.get("name") == active_name:
                self._active = p
                break


# ---------------------------------------------------------------------------
# De-normalizer — inverse of _normalize(): int keys -> human names
# ---------------------------------------------------------------------------
_IDX_TO_BTN_NAME: Dict[int, str] = {v: k for k, v in _BTN_NAME_TO_IDX.items()}
_HAT_TO_DPAD_NAME: Dict[Tuple[int, int], str] = {v: k for k, v in _DPAD_NAME_TO_HAT.items()}


def _denormalize(data: dict) -> dict:
    """Convert internal int/tuple keys back to the JSON-friendly forms."""
    out: Dict[str, Any] = {}
    for k, v in data.items():
        if k in ("button_actions", "lb_shortcuts"):
            out[k] = {
                _IDX_TO_BTN_NAME.get(int(bk), str(bk)) if isinstance(bk, int) else str(bk): bv
                for bk, bv in v.items()
            }
        elif k == "dpad_actions":
            out[k] = {}
            for hk, hv in v.items():
                if isinstance(hk, (tuple, list)) and len(hk) == 2:
                    out[k][_HAT_TO_DPAD_NAME.get(tuple(hk), str(hk))] = hv
                else:
                    out[k][str(hk)] = hv
        else:
            out[k] = v
    return out
