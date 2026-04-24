"""
Buttons tab: read-only view of the active profile's button bindings,
grouped into Normal / LB-chord / D-pad / Triggers sections.

Read-only in v1: editing mappings is listed in open_questions below.
Refreshes whenever the active profile changes.
"""
import tkinter as tk
from tkinter import ttk

from config.settings import (
    BUTTON_ACTIONS, LB_SHORTCUTS, DPAD_ACTIONS, LT_ACTION, RT_ACTION,
)
from ui.dashboard import theme as T
from ui.state_bridge import StateBridge

_BTN_IDX_TO_NAME = {
    0: "A", 1: "B", 2: "X", 3: "Y",
    4: "LB", 5: "RB", 6: "BACK", 7: "START",
    8: "LSTICK", 9: "RSTICK", 10: "GUIDE",
}
_HAT_TO_NAME = {(0, 1): "Up", (0, -1): "Down", (-1, 0): "Left", (1, 0): "Right"}


class ButtonsTab(ttk.Frame):
    def __init__(self, master: tk.Misc, bridge: StateBridge) -> None:
        super().__init__(master, style="Dash.TFrame")
        self._bridge = bridge
        self._last_profile = ""

        bar = ttk.Frame(self, style="Dash.TFrame")
        bar.pack(fill="x", padx=8, pady=(8, 4))
        ttk.Label(bar, text="Active profile bindings",
                  style="DashBold.TLabel").pack(side="left")
        ttk.Button(bar, text="Refresh", style="Dash.TButton",
                   command=self._refresh).pack(side="right")

        self._tree = ttk.Treeview(self,
            columns=("action",), show="tree headings",
            selectmode="browse", height=18)
        self._tree.heading("#0", text="Input")
        self._tree.heading("action", text="Action")
        self._tree.column("#0", width=220, anchor="w")
        self._tree.column("action", width=280, anchor="w")
        self._tree.pack(fill="both", expand=True, padx=8, pady=(0, 8))

        self._refresh()
        # Cheap self-polling for profile change (every 500ms)
        self.after(500, self._poll)

    def _poll(self) -> None:
        snap = self._bridge.snapshot()
        if snap.profile_name != self._last_profile:
            self._refresh()
        self.after(500, self._poll)

    def _refresh(self) -> None:
        snap = self._bridge.snapshot()
        self._last_profile = snap.profile_name
        self._tree.delete(*self._tree.get_children())

        profile = self._current_profile()
        btns    = profile.get("button_actions", BUTTON_ACTIONS)
        chords  = profile.get("lb_shortcuts", LB_SHORTCUTS)
        dpad    = profile.get("dpad_actions", DPAD_ACTIONS)
        lt      = profile.get("lt_action", LT_ACTION)
        rt      = profile.get("rt_action", RT_ACTION)

        g_normal = self._tree.insert("", "end", text="Buttons", open=True)
        for idx, action in btns.items():
            name = _BTN_IDX_TO_NAME.get(idx, f"btn{idx}")
            self._tree.insert(g_normal, "end", text=f"  {name}",
                              values=(action,))

        g_chord = self._tree.insert("", "end", text="LB + button (chord)", open=True)
        for idx, action in chords.items():
            name = _BTN_IDX_TO_NAME.get(idx, f"btn{idx}")
            self._tree.insert(g_chord, "end", text=f"  LB + {name}",
                              values=(action,))

        g_dpad = self._tree.insert("", "end", text="D-pad", open=True)
        for hat, action in dpad.items():
            name = _HAT_TO_NAME.get(tuple(hat) if isinstance(hat, list) else hat, str(hat))
            self._tree.insert(g_dpad, "end", text=f"  {name}",
                              values=(action,))

        g_trig = self._tree.insert("", "end", text="Triggers", open=True)
        self._tree.insert(g_trig, "end", text="  LT", values=(lt,))
        self._tree.insert(g_trig, "end", text="  RT", values=(rt,))

    def _current_profile(self) -> dict:
        # Try to resolve the full active-profile dict via bridge
        for p in self._bridge.list_profiles():
            if p.get("name") == self._bridge.snapshot().profile_name:
                return p
        return {}
