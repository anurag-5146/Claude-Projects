"""
Structured-action executor.

An action binding can be either:

  1. A legacy string name  ("left_click", "alt_tab", "volume_up", ...)

  2. A dict with a "type" field:
        {"type": "key",    "key": "f5"}
        {"type": "combo",  "keys": ["ctrl", "shift", "t"]}
        {"type": "text",   "text": "hello world"}
        {"type": "mouse",  "button": "double_left"}
        {"type": "launch", "target": "notepad.exe"}
        {"type": "launch", "target": "https://youtube.com"}
        {"type": "launch", "target": "netflix"}       # uses LAUNCH_ALIASES

Resolver returns True on success (for UI feedback). All exceptions are
caught and logged — controller input must never take down the main loop.
"""
from __future__ import annotations
import logging
import os
import subprocess
import sys
import webbrowser
from typing import Any, Dict, List, Union

from pynput.keyboard import Key

from output import media
from output.mouse_keyboard import MouseKeyboardOutput

logger = logging.getLogger(__name__)

Action = Union[str, Dict[str, Any]]

# ---------------------------------------------------------------------------
# Launch aliases — human-friendly names mapped to URLs/exes.
# Anything unmapped is opened as-is (URL -> browser, path -> os.startfile).
# ---------------------------------------------------------------------------
LAUNCH_ALIASES: Dict[str, str] = {
    # Streaming
    "netflix":        "https://www.netflix.com",
    "prime_video":    "https://www.primevideo.com",
    "hotstar":        "https://www.hotstar.com",
    "disney_plus":    "https://www.disneyplus.com",
    "youtube":        "https://www.youtube.com",
    "youtube_music":  "https://music.youtube.com",
    "spotify":        "spotify:",
    "twitch":         "https://www.twitch.tv",
    # Productivity / social
    "gmail":          "https://mail.google.com",
    "github":         "https://github.com",
    "chatgpt":        "https://chat.openai.com",
    "claude":         "https://claude.ai",
    # Windows built-ins
    "calculator":     "calc.exe",
    "notepad":        "notepad.exe",
    "task_manager":   "taskmgr.exe",
    "explorer":       "explorer.exe",
    "settings":       "ms-settings:",
}

# ---------------------------------------------------------------------------
# Key-name → pynput Key resolver
# ---------------------------------------------------------------------------
_KEY_ALIASES: Dict[str, Any] = {
    "ctrl": Key.ctrl,   "control": Key.ctrl,
    "alt": Key.alt,
    "shift": Key.shift,
    "win": Key.cmd,     "windows": Key.cmd, "cmd": Key.cmd, "meta": Key.cmd,
    "enter": Key.enter, "return": Key.enter,
    "esc": Key.esc,     "escape": Key.esc,
    "tab": Key.tab,
    "space": Key.space, "spacebar": Key.space,
    "backspace": Key.backspace, "bksp": Key.backspace,
    "delete": Key.delete, "del": Key.delete,
    "insert": Key.insert, "ins": Key.insert,
    "home": Key.home,   "end": Key.end,
    "pageup": Key.page_up,   "pgup": Key.page_up, "page_up": Key.page_up,
    "pagedown": Key.page_down, "pgdn": Key.page_down, "page_down": Key.page_down,
    "up": Key.up, "down": Key.down, "left": Key.left, "right": Key.right,
    "printscreen": Key.print_screen, "prtsc": Key.print_screen,
    "caps": Key.caps_lock, "capslock": Key.caps_lock,
    "menu": Key.menu,
}


def resolve_key(name: str) -> Any:
    """Turn a human key name into something pynput.tap() accepts."""
    s = str(name).strip().lower()
    if not s:
        return None
    if len(s) == 1:
        return s  # letter / digit / punctuation
    if s in _KEY_ALIASES:
        return _KEY_ALIASES[s]
    # Function keys: f1..f24
    if s.startswith("f") and s[1:].isdigit():
        attr = s  # "f5" -> Key.f5
        return getattr(Key, attr, None)
    return getattr(Key, s, None)


# ---------------------------------------------------------------------------
# Built-in named actions (legacy string API — kept stable)
# ---------------------------------------------------------------------------
def _named_actions(mk: MouseKeyboardOutput) -> Dict[str, Any]:
    return {
        "left_click":      mk.left_click,
        "right_click":     mk.right_click,
        "middle_click":    mk.middle_click,
        "double_click":    lambda: (mk.left_click(), mk.left_click()),
        "enter":            mk.enter,
        "escape":           mk.escape,
        "alt_tab":          mk.alt_tab,
        "alt_f4":           mk.alt_f4,
        "win_d":            mk.win_d,
        "win_tab":          mk.win_tab,
        "print_screen":     mk.print_screen,
        "browser_back":     mk.browser_back,
        "browser_forward":  mk.browser_forward,
        "play_pause":       media.play_pause,
        "volume_up":        media.volume_up,
        "volume_down":      media.volume_down,
        "volume_mute":      getattr(media, "volume_mute", lambda: None),
        "next_track":       media.next_track,
        "prev_track":       media.prev_track,
        "noop":             lambda: None,
    }


# ---------------------------------------------------------------------------
# Dispatcher
# ---------------------------------------------------------------------------
def perform(mk: MouseKeyboardOutput, action: Action) -> bool:
    """Execute a string or dict action. Returns True on success."""
    try:
        if isinstance(action, str):
            return _perform_named(mk, action)
        if isinstance(action, dict):
            return _perform_dict(mk, action)
        logger.warning("Unsupported action type: %r", type(action))
        return False
    except Exception:
        logger.exception("Action failed: %r", action)
        return False


def _perform_named(mk: MouseKeyboardOutput, name: str) -> bool:
    fn = _named_actions(mk).get(name)
    if fn is None:
        logger.warning("Unknown named action: %r", name)
        return False
    fn()
    return True


def _perform_dict(mk: MouseKeyboardOutput, a: Dict[str, Any]) -> bool:
    t = str(a.get("type", "")).lower()

    if t == "key":
        k = resolve_key(a.get("key", ""))
        if k is None:
            logger.warning("Unknown key in action: %r", a)
            return False
        mk.tap(k)
        return True

    if t == "combo":
        raw_keys: List[str] = list(a.get("keys", []))
        keys = [resolve_key(k) for k in raw_keys]
        if any(k is None for k in keys):
            logger.warning("Unknown key(s) in combo: %r", raw_keys)
            return False
        mk.combo(*keys)
        return True

    if t == "text":
        text = str(a.get("text", ""))
        for ch in text:
            mk.tap(ch)
        return True

    if t == "mouse":
        btn = str(a.get("button", "left")).lower()
        return _perform_named(mk, f"{btn}_click" if not btn.endswith("_click") else btn)

    if t == "launch":
        target = str(a.get("target", "")).strip()
        if not target:
            return False
        # Alias lookup
        resolved = LAUNCH_ALIASES.get(target.lower(), target)
        return _launch(resolved)

    logger.warning("Unknown action type: %r", t)
    return False


def _launch(target: str) -> bool:
    """Open a URL, file path, or protocol handler."""
    try:
        low = target.lower()
        if low.startswith(("http://", "https://")):
            webbrowser.open(target)
            return True
        # Protocol URIs like "spotify:", "ms-settings:"
        if ":" in target and not os.path.exists(target):
            if sys.platform == "win32":
                os.startfile(target)  # type: ignore[attr-defined]
            else:
                subprocess.Popen(["xdg-open", target])
            return True
        # Plain path or bare exe name on PATH
        if sys.platform == "win32":
            os.startfile(target)  # type: ignore[attr-defined]
        else:
            subprocess.Popen([target])
        return True
    except Exception:
        logger.exception("Launch failed: %r", target)
        return False


# ---------------------------------------------------------------------------
# Catalog — used by the dashboard's action picker
# ---------------------------------------------------------------------------
def named_action_catalog() -> List[str]:
    """Sorted list of built-in named actions (for UI pickers)."""
    # Stub mk to enumerate keys; real dispatch uses a real mk.
    class _Stub:
        def __getattr__(self, _name): return lambda *a, **kw: None
    return sorted(_named_actions(_Stub()).keys())
