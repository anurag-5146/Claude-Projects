"""
Dashboard Toplevel window — tabbed control surface for the controller app.

Must be created on the tkinter main thread (the same thread that runs the
OSD). Entry point: `open_dashboard(root, bridge)`.
"""
import logging
import tkinter as tk
from tkinter import ttk
from typing import Optional

from ui.dashboard import theme as T
from ui.dashboard.widgets import StatusDot, apply_ttk_theme
from ui.state_bridge import StateBridge

logger = logging.getLogger(__name__)

_POLL_MS = 100  # dashboard refresh cadence (~10 Hz)


class Dashboard:
    _instance: Optional["Dashboard"] = None

    def __init__(self, root: tk.Misc, bridge: StateBridge) -> None:
        self._bridge = bridge
        self._win = tk.Toplevel(root)
        self._win.title("Xbox Controller — Dashboard")
        self._win.geometry("680x540")
        self._win.minsize(620, 480)
        self._win.configure(bg=T.BG)
        self._win.protocol("WM_DELETE_WINDOW", self.close)

        apply_ttk_theme(self._win)

        self._build_header()
        self._build_notebook()
        self._build_footer()

        self._after_id: Optional[str] = None
        self._schedule_poll()

    # ---- build ----
    def _build_header(self) -> None:
        hdr = ttk.Frame(self._win, style="Dash.TFrame")
        hdr.pack(fill="x", padx=T.PAD_X, pady=(T.PAD_Y, 4))
        ttk.Label(hdr, text="Xbox Controller", style="DashH1.TLabel").pack(side="left")
        ttk.Label(hdr, text="  Dashboard", style="DashMuted.TLabel").pack(side="left")

    def _build_notebook(self) -> None:
        self._nb = ttk.Notebook(self._win)
        self._nb.pack(fill="both", expand=True, padx=T.PAD_X, pady=4)

        # Lazy imports — keeps this shell file small and avoids circular deps
        from ui.dashboard.tab_live import LiveTab
        from ui.dashboard.tab_buttons import ButtonsTab
        from ui.dashboard.tab_settings import SettingsTab
        from ui.dashboard.tab_profiles import ProfilesTab

        self._tab_live     = LiveTab(self._nb, self._bridge)
        self._tab_buttons  = ButtonsTab(self._nb, self._bridge)
        self._tab_settings = SettingsTab(self._nb, self._bridge)
        self._tab_profiles = ProfilesTab(self._nb, self._bridge)

        self._nb.add(self._tab_live,     text=" Live ")
        self._nb.add(self._tab_buttons,  text=" Buttons ")
        self._nb.add(self._tab_settings, text=" Settings ")
        self._nb.add(self._tab_profiles, text=" Profiles ")

    def _build_footer(self) -> None:
        foot = ttk.Frame(self._win, style="DashElevated.TFrame")
        foot.pack(fill="x", side="bottom")

        inner = tk.Frame(foot, bg=T.BG_ELEVATED)
        inner.pack(fill="x", padx=T.PAD_X, pady=6)

        self._mode_dot = StatusDot(inner, size=11)
        self._mode_dot.configure(bg=T.BG_ELEVATED)
        self._mode_dot.pack(side="left", padx=(0, 6))

        self._mode_label = tk.Label(inner, text="DESKTOP",
            bg=T.BG_ELEVATED, fg=T.FG, font=T.FONT_BOLD)
        self._mode_label.pack(side="left")

        tk.Label(inner, text="  |  Profile:",
            bg=T.BG_ELEVATED, fg=T.FG_MUTED, font=T.FONT_LABEL).pack(side="left")
        self._prof_label = tk.Label(inner, text="default",
            bg=T.BG_ELEVATED, fg=T.FG, font=T.FONT_BODY)
        self._prof_label.pack(side="left", padx=(4, 0))

        self._conn_dot = StatusDot(inner, size=11)
        self._conn_dot.configure(bg=T.BG_ELEVATED)
        self._conn_dot.pack(side="right")
        self._conn_label = tk.Label(inner, text="Disconnected",
            bg=T.BG_ELEVATED, fg=T.FG_MUTED, font=T.FONT_LABEL)
        self._conn_label.pack(side="right", padx=(0, 6))

    # ---- poll loop ----
    def _schedule_poll(self) -> None:
        self._after_id = self._win.after(_POLL_MS, self._poll)

    def _poll(self) -> None:
        try:
            snap = self._bridge.snapshot()
            self._update_footer(snap)
            self._tab_live.update_snapshot(snap)
        except Exception:
            logger.exception("Dashboard poll failed")
        self._schedule_poll()

    def _update_footer(self, snap) -> None:
        if snap.mode == "game":
            self._mode_dot.set_color(T.ACCENT_GAME)
            self._mode_label.config(text="GAME", fg=T.ACCENT_GAME)
        else:
            self._mode_dot.set_color(T.ACCENT)
            self._mode_label.config(text="DESKTOP", fg=T.ACCENT)

        self._prof_label.config(text=snap.profile_name)

        if snap.connected:
            self._conn_dot.set_color(T.ACCENT_GAME)
            self._conn_label.config(text="Connected", fg=T.FG)
        else:
            self._conn_dot.set_color(T.ERROR)
            self._conn_label.config(text="Disconnected", fg=T.FG_MUTED)

    # ---- lifecycle ----
    def close(self) -> None:
        if self._after_id:
            try:
                self._win.after_cancel(self._after_id)
            except Exception:
                pass
        try:
            self._win.destroy()
        finally:
            Dashboard._instance = None


def open_dashboard(root: tk.Misc, bridge: StateBridge) -> Dashboard:
    """Open the dashboard (or focus it if already open). Main-thread only."""
    if Dashboard._instance is not None:
        try:
            Dashboard._instance._win.deiconify()
            Dashboard._instance._win.lift()
            Dashboard._instance._win.focus_force()
            return Dashboard._instance
        except tk.TclError:
            Dashboard._instance = None
    Dashboard._instance = Dashboard(root, bridge)
    return Dashboard._instance
