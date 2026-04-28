"""
Resolve the app-icon path in both dev and PyInstaller-bundled runs, and
apply it to a tkinter window so the taskbar no longer shows the default
tkinter feather.

Usage:
    from ui.app_icon import apply_icon, set_app_user_model_id
    set_app_user_model_id()   # once, at startup
    apply_icon(window)        # for every Toplevel / Tk root you create
"""
from __future__ import annotations
import logging
import sys
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

APP_ID = "anurag.xbox-controller-pc.app.1"


def _base_dir() -> Path:
    """Return the directory bundled assets live in.

    PyInstaller unpacks data files under sys._MEIPASS at runtime; in a
    normal `python main.py` run we fall back to the project root.
    """
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        return Path(meipass)
    return Path(__file__).resolve().parent.parent


def icon_path() -> Optional[Path]:
    p = _base_dir() / "assets" / "app_icon.ico"
    return p if p.exists() else None


def apply_icon(window) -> None:
    """Set the window/taskbar icon. Silently no-ops if the file is missing."""
    p = icon_path()
    if p is None:
        return
    try:
        window.iconbitmap(default=str(p))
    except Exception:
        try:
            window.iconbitmap(str(p))
        except Exception:
            logger.debug("iconbitmap failed for %s", p, exc_info=True)


def set_app_user_model_id() -> None:
    """Tell Windows this process has its own app identity so the taskbar
    shows our icon (and groups our windows) instead of bucketing us under
    'python.exe'. Safe to call on non-Windows — it just no-ops."""
    try:
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
    except (OSError, AttributeError):
        pass
