"""
Settings tab: live sliders that mutate config.settings at runtime.

Changes apply immediately (the main loop re-reads settings each tick).
A "Save to disk" button is explicitly NOT included in v1 — persistence
through JSON profiles is the intended path.
"""
import tkinter as tk
from tkinter import ttk

from config import settings as S
from ui.dashboard import theme as T
from ui.dashboard.widgets import LabeledSlider
from ui.state_bridge import StateBridge


class SettingsTab(ttk.Frame):
    def __init__(self, master: tk.Misc, bridge: StateBridge) -> None:
        super().__init__(master, style="Dash.TFrame")
        self._bridge = bridge

        ttk.Label(self, text="Runtime settings",
                  style="DashBold.TLabel").pack(anchor="w", padx=8, pady=(8, 2))
        ttk.Label(self,
                  text="Applied immediately. Not persisted to disk.",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(0, 8))

        body = ttk.Frame(self, style="Dash.TFrame")
        body.pack(fill="both", expand=True, padx=8, pady=4)
        body.columnconfigure(0, weight=1)
        body.columnconfigure(1, weight=1)

        self._add_slider(body, 0, 0, "Mouse sensitivity",
                         1.0, 60.0, S.MOUSE_SENSITIVITY,
                         lambda v: setattr(S, "MOUSE_SENSITIVITY", v))
        self._add_slider(body, 0, 1, "Mouse accel exponent",
                         1.0, 3.0, S.MOUSE_ACCEL_EXPONENT,
                         lambda v: setattr(S, "MOUSE_ACCEL_EXPONENT", v))
        self._add_slider(body, 1, 0, "Scroll sensitivity",
                         1.0, 40.0, S.SCROLL_SENSITIVITY,
                         lambda v: setattr(S, "SCROLL_SENSITIVITY", v))
        self._add_slider(body, 1, 1, "Trigger threshold",
                         0.05, 0.95, S.TRIGGER_THRESHOLD,
                         lambda v: setattr(S, "TRIGGER_THRESHOLD", v))
        self._add_slider(body, 2, 0, "Stick deadzone",
                         0.0, 0.4, S.DEADZONE,
                         lambda v: setattr(S, "DEADZONE", v))
        self._add_slider(body, 2, 1, "Combo hold (seconds)",
                         0.1, 2.0, S.COMBO_HOLD_SECONDS,
                         lambda v: setattr(S, "COMBO_HOLD_SECONDS", v))

        rumble = ttk.Frame(self, style="Dash.TFrame")
        rumble.pack(fill="x", padx=8, pady=(14, 4))
        ttk.Label(rumble, text="Rumble on mode switch",
                  style="DashBold.TLabel").pack(anchor="w")
        self._rumble_var = tk.BooleanVar(value=S.RUMBLE_ON_SWITCH)
        ttk.Checkbutton(rumble, text="Enabled",
                        variable=self._rumble_var,
                        command=self._on_rumble_toggle).pack(anchor="w", pady=2)

        rbody = ttk.Frame(self, style="Dash.TFrame")
        rbody.pack(fill="x", padx=8, pady=4)
        rbody.columnconfigure(0, weight=1)
        rbody.columnconfigure(1, weight=1)
        self._add_slider(rbody, 0, 0, "Left motor",
                         0.0, 1.0, S.RUMBLE_LEFT_MOTOR,
                         lambda v: setattr(S, "RUMBLE_LEFT_MOTOR", v))
        self._add_slider(rbody, 0, 1, "Right motor",
                         0.0, 1.0, S.RUMBLE_RIGHT_MOTOR,
                         lambda v: setattr(S, "RUMBLE_RIGHT_MOTOR", v))

    def _add_slider(self, parent, row, col, label, lo, hi, initial, cb) -> None:
        slider = LabeledSlider(parent, label=label, from_=lo, to=hi,
                               initial=initial, on_change=cb)
        slider.grid(row=row, column=col, sticky="ew", padx=6, pady=4)

    def _on_rumble_toggle(self) -> None:
        S.RUMBLE_ON_SWITCH = bool(self._rumble_var.get())
