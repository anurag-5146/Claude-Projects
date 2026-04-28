"""
Modal dialog: pick the buttons that form a chord.

Two ways to specify the chord:
  1. Tick the checkboxes for the buttons you want.
  2. Click "Capture from controller" and physically hold the buttons —
     the dialog reads the live snapshot from StateBridge until you press
     "Save".

The captured chord is returned as a frozenset[int] of button indices.
"""
from __future__ import annotations
import tkinter as tk
from tkinter import ttk
from typing import Callable, Optional

from ui.app_icon import apply_icon
from ui.dashboard import theme as T
from ui.dashboard.widgets import apply_ttk_theme
from ui.state_bridge import StateBridge

_BTN_NAME_TO_IDX = {
    "A": 0, "B": 1, "X": 2, "Y": 3,
    "LB": 4, "RB": 5, "BACK": 6, "START": 7,
    "LSTICK": 8, "RSTICK": 9, "GUIDE": 10,
}
_BTN_DISPLAY_ORDER = ["LB", "RB", "A", "B", "X", "Y",
                      "BACK", "START", "GUIDE", "LSTICK", "RSTICK"]


class ChordPicker(tk.Toplevel):
    def __init__(
        self,
        master: tk.Misc,
        bridge: StateBridge,
        initial: Optional[frozenset] = None,
        on_save: Optional[Callable[[frozenset], None]] = None,
    ) -> None:
        super().__init__(master)
        apply_icon(self)
        self.title("Pick chord buttons")
        self.geometry("420x320")
        self.resizable(False, False)
        self.configure(bg=T.BG)
        self.transient(master)
        apply_ttk_theme(self)

        self._bridge = bridge
        self._on_save = on_save
        self._capturing = False
        self._after_id: Optional[str] = None

        ttk.Label(self, text="Select 2 or more buttons",
                  style="DashH1.TLabel").pack(anchor="w", padx=12, pady=(10, 4))
        ttk.Label(self,
                  text="Tip: tap 'Capture' and physically hold the chord on the controller.",
                  style="DashMuted.TLabel").pack(anchor="w", padx=12)

        self._vars: dict[str, tk.BooleanVar] = {}
        grid = ttk.Frame(self, style="Dash.TFrame")
        grid.pack(fill="both", expand=True, padx=12, pady=10)
        for i, name in enumerate(_BTN_DISPLAY_ORDER):
            var = tk.BooleanVar(value=False)
            self._vars[name] = var
            cb = ttk.Checkbutton(grid, text=name, variable=var,
                                 command=self._update_preview)
            cb.grid(row=i // 4, column=i % 4, sticky="w", padx=6, pady=4)

        if initial:
            inv = {v: k for k, v in _BTN_NAME_TO_IDX.items()}
            for idx in initial:
                name = inv.get(int(idx))
                if name and name in self._vars:
                    self._vars[name].set(True)

        self._preview = ttk.Label(self, text="—", style="Dash.TLabel")
        self._preview.pack(anchor="w", padx=12, pady=(4, 0))
        self._update_preview()

        bar = ttk.Frame(self, style="Dash.TFrame")
        bar.pack(fill="x", padx=12, pady=10)
        self._capture_btn = ttk.Button(bar, text="Capture from controller",
                                       style="Dash.TButton",
                                       command=self._toggle_capture)
        self._capture_btn.pack(side="left")
        ttk.Button(bar, text="Cancel", style="Dash.TButton",
                   command=self.destroy).pack(side="right")
        ttk.Button(bar, text="Save", style="Dash.TButton",
                   command=self._save).pack(side="right", padx=6)

        self.protocol("WM_DELETE_WINDOW", self._close)
        self.grab_set()

    # ---- live capture ----
    def _toggle_capture(self) -> None:
        self._capturing = not self._capturing
        if self._capturing:
            for v in self._vars.values():
                v.set(False)
            self._capture_btn.config(text="Stop capture")
            self._poll_capture()
        else:
            self._capture_btn.config(text="Capture from controller")

    def _poll_capture(self) -> None:
        if not self._capturing:
            return
        snap = self._bridge.snapshot()
        inv = {v: k for k, v in _BTN_NAME_TO_IDX.items()}
        for idx, pressed in (snap.buttons or {}).items():
            if pressed:
                name = inv.get(int(idx))
                if name and name in self._vars:
                    self._vars[name].set(True)
        self._update_preview()
        self._after_id = self.after(80, self._poll_capture)

    # ---- preview / save ----
    def _selected(self) -> frozenset:
        return frozenset(_BTN_NAME_TO_IDX[n] for n, v in self._vars.items() if v.get())

    def _update_preview(self) -> None:
        names = [n for n in _BTN_DISPLAY_ORDER if self._vars[n].get()]
        self._preview.config(text=("Chord: " + " + ".join(names)) if names else "—")

    def _save(self) -> None:
        chord = self._selected()
        if len(chord) < 2:
            self._preview.config(text="Pick at least 2 buttons")
            return
        if self._on_save:
            self._on_save(chord)
        self._close()

    def _close(self) -> None:
        self._capturing = False
        if self._after_id:
            try:
                self.after_cancel(self._after_id)
            except Exception:
                pass
        self.destroy()
