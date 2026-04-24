"""
Live tab: stylized controller diagram whose buttons / sticks / triggers
light up in real-time from StateBridge snapshots.

The diagram is pure Canvas primitives (no external images) laid out in a
400x240 virtual space centered horizontally in whatever width the tab has.
"""
import tkinter as tk
from tkinter import ttk

from config.settings import (
    AXIS_LX, AXIS_LY, AXIS_RX, AXIS_RY, AXIS_LT, AXIS_RT,
    BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y, BUTTON_LB, BUTTON_RB,
    BUTTON_BACK, BUTTON_START, BUTTON_LSTICK, BUTTON_RSTICK, BUTTON_GUIDE,
)
from ui.dashboard import theme as T
from ui.state_bridge import Snapshot, StateBridge

# Face-button positions relative to canvas center (dx, dy, label)
_FACE = [
    (BUTTON_Y, 130, -30, "Y", "#f5d847"),
    (BUTTON_X, 108,  -8, "X", "#4a9eff"),
    (BUTTON_B, 152,  -8, "B", "#ff5c6b"),
    (BUTTON_A, 130,  14, "A", "#4aff7a"),
]
# Stick "base" positions (center)
_LSTICK = (-90, 0)
_RSTICK = ( 60, 30)
# Shoulder buttons
_LB = (-140, -60, "LB")
_RB = ( 140, -60, "RB")
# Center cluster
_BACK  = (-30, -12, "⊟")
_START = ( 30, -12, "≡")
_GUIDE = (  0, -40, "◯")
# Triggers
_LT_RECT = (-170, -90, -130, -70)
_RT_RECT = ( 130, -90,  170, -70)
# D-pad cross
_DPAD = (-60, 30)   # center


class LiveTab(ttk.Frame):
    def __init__(self, master: tk.Misc, bridge: StateBridge) -> None:
        super().__init__(master, style="Dash.TFrame")
        self._bridge = bridge

        # Toolbar
        bar = ttk.Frame(self, style="Dash.TFrame")
        bar.pack(fill="x", padx=8, pady=(8, 4))
        ttk.Button(bar, text="Toggle Mode", style="Dash.TButton",
                   command=self._toggle_mode).pack(side="left")
        self._hz_var = tk.StringVar(value="0 Hz")
        ttk.Label(bar, textvariable=self._hz_var,
                  style="DashMuted.TLabel").pack(side="right")

        # Canvas
        self._canvas = tk.Canvas(self, bg=T.BG_SUNKEN, highlightthickness=0, bd=0)
        self._canvas.pack(fill="both", expand=True, padx=8, pady=(4, 8))
        self._canvas.bind("<Configure>", lambda _e: self._redraw_static())

        self._ids: dict = {}  # named item ids for fast updates
        self._last_size = (0, 0)

    # ---- public: called each poll by the dashboard ----
    def update_snapshot(self, snap: Snapshot) -> None:
        if self._last_size != (self._canvas.winfo_width(), self._canvas.winfo_height()):
            self._redraw_static()
        self._update_dynamic(snap)
        self._hz_var.set(f"{snap.tick_hz:.0f} Hz")

    # ---- static geometry ----
    def _redraw_static(self) -> None:
        self._canvas.delete("all")
        self._ids.clear()
        w = max(self._canvas.winfo_width(), 1)
        h = max(self._canvas.winfo_height(), 1)
        self._last_size = (w, h)
        cx, cy = w // 2, h // 2

        # Body outline
        self._canvas.create_oval(cx-210, cy-80, cx+210, cy+100,
            outline=T.BORDER, width=2)

        # Triggers (will fill dynamically)
        for key, rect in (("lt", _LT_RECT), ("rt", _RT_RECT)):
            x1, y1, x2, y2 = rect
            self._canvas.create_rectangle(cx+x1, cy+y1, cx+x2, cy+y2,
                outline=T.BORDER, width=1)
            self._ids[f"{key}_fill"] = self._canvas.create_rectangle(
                cx+x1, cy+y2, cx+x2, cy+y2,  # zero-height, grows up
                fill=T.ACCENT, outline="")
            self._ids[f"{key}_rect"] = rect

        # Shoulders
        for (key, (bx, by, label)) in (("lb", _LB), ("rb", _RB)):
            self._ids[f"{key}_bg"] = self._canvas.create_rectangle(
                cx+bx-22, cy+by-8, cx+bx+22, cy+by+8,
                fill=T.BG_ELEVATED, outline=T.BORDER)
            self._canvas.create_text(cx+bx, cy+by, text=label,
                fill=T.FG_MUTED, font=T.FONT_LABEL)

        # Face buttons
        for btn_idx, dx, dy, label, color in _FACE:
            self._ids[f"btn_{btn_idx}_bg"] = self._canvas.create_oval(
                cx+dx-12, cy+dy-12, cx+dx+12, cy+dy+12,
                fill=T.BG_ELEVATED, outline=T.BORDER)
            self._canvas.create_text(cx+dx, cy+dy, text=label,
                fill=color, font=T.FONT_BOLD)

        # Center cluster (Back, Start, Guide)
        for (key, (dx, dy, label)) in (("back", _BACK), ("start", _START), ("guide", _GUIDE)):
            self._ids[f"{key}_bg"] = self._canvas.create_oval(
                cx+dx-9, cy+dy-9, cx+dx+9, cy+dy+9,
                fill=T.BG_ELEVATED, outline=T.BORDER)
            self._canvas.create_text(cx+dx, cy+dy, text=label,
                fill=T.FG_MUTED, font=T.FONT_LABEL)

        # D-pad (4 triangles)
        dx, dy = _DPAD
        size = 16
        coords = {
            "up":    (cx+dx, cy+dy-size, cx+dx-size//2, cy+dy, cx+dx+size//2, cy+dy),
            "down":  (cx+dx, cy+dy+size, cx+dx-size//2, cy+dy, cx+dx+size//2, cy+dy),
            "left":  (cx+dx-size, cy+dy, cx+dx, cy+dy-size//2, cx+dx, cy+dy+size//2),
            "right": (cx+dx+size, cy+dy, cx+dx, cy+dy-size//2, cx+dx, cy+dy+size//2),
        }
        for d, c in coords.items():
            self._ids[f"dpad_{d}"] = self._canvas.create_polygon(
                c, fill=T.BG_ELEVATED, outline=T.BORDER)

        # Sticks — outer ring + moving dot
        for (key, (sx, sy)) in (("lstick", _LSTICK), ("rstick", _RSTICK)):
            self._canvas.create_oval(cx+sx-34, cy+sy-34, cx+sx+34, cy+sy+34,
                outline=T.BORDER, width=1)
            self._ids[f"{key}_dot"] = self._canvas.create_oval(
                cx+sx-8, cy+sy-8, cx+sx+8, cy+sy+8,
                fill=T.FG_MUTED, outline="")
            self._ids[f"{key}_center"] = (cx+sx, cy+sy)

    # ---- dynamic updates ----
    def _update_dynamic(self, snap: Snapshot) -> None:
        if not self._ids:
            return

        # Face buttons
        for btn_idx, *_rest in _FACE:
            pressed = snap.buttons.get(btn_idx, False)
            self._canvas.itemconfig(self._ids[f"btn_{btn_idx}_bg"],
                fill=T.PRESSED if pressed else T.BG_ELEVATED)

        # Shoulders
        for key, btn_idx in (("lb", BUTTON_LB), ("rb", BUTTON_RB)):
            pressed = snap.buttons.get(btn_idx, False)
            self._canvas.itemconfig(self._ids[f"{key}_bg"],
                fill=T.PRESSED if pressed else T.BG_ELEVATED)

        # Center cluster
        for key, btn_idx in (("back", BUTTON_BACK), ("start", BUTTON_START), ("guide", BUTTON_GUIDE)):
            pressed = snap.buttons.get(btn_idx, False)
            self._canvas.itemconfig(self._ids[f"{key}_bg"],
                fill=T.PRESSED if pressed else T.BG_ELEVATED)

        # D-pad
        hx, hy = snap.hat
        self._canvas.itemconfig(self._ids["dpad_up"],    fill=T.PRESSED if hy ==  1 else T.BG_ELEVATED)
        self._canvas.itemconfig(self._ids["dpad_down"],  fill=T.PRESSED if hy == -1 else T.BG_ELEVATED)
        self._canvas.itemconfig(self._ids["dpad_left"],  fill=T.PRESSED if hx == -1 else T.BG_ELEVATED)
        self._canvas.itemconfig(self._ids["dpad_right"], fill=T.PRESSED if hx ==  1 else T.BG_ELEVATED)

        # Sticks
        for key, ax, ay, click_btn in (
            ("lstick", AXIS_LX, AXIS_LY, BUTTON_LSTICK),
            ("rstick", AXIS_RX, AXIS_RY, BUTTON_RSTICK),
        ):
            vx = snap.axes.get(ax, 0.0)
            vy = snap.axes.get(ay, 0.0)
            base_x, base_y = self._ids[f"{key}_center"]
            x = base_x + vx * 26
            y = base_y + vy * 26
            self._canvas.coords(self._ids[f"{key}_dot"],
                x-8, y-8, x+8, y+8)
            clicked = snap.buttons.get(click_btn, False)
            self._canvas.itemconfig(self._ids[f"{key}_dot"],
                fill=T.PRESSED if clicked else T.ACCENT)

        # Triggers (normalize SDL2 -1..+1 to 0..1)
        for key, axis in (("lt", AXIS_LT), ("rt", AXIS_RT)):
            raw = snap.axes.get(axis, -1.0)
            val = max(0.0, min(1.0, (raw + 1.0) / 2.0))
            x1, y1, x2, y2 = self._ids[f"{key}_rect"]
            w = max(self._canvas.winfo_width(), 1)
            h = max(self._canvas.winfo_height(), 1)
            cx, cy = w // 2, h // 2
            height = (y2 - y1) * val
            self._canvas.coords(self._ids[f"{key}_fill"],
                cx+x1, cy+y2-height, cx+x2, cy+y2)

    def _toggle_mode(self) -> None:
        self._bridge.switch_mode()
