"""
Buttons tab: edit the active profile's bindings.

Double-click any row (or select + click Edit) to open the BindingEditor.
Changes update the profile dict in memory (so DesktopMode sees them at the
next tick) AND are persisted to disk via bridge.save_profile().
"""
import tkinter as tk
from tkinter import messagebox, ttk
from typing import Any, Tuple

from config.settings import (
    BUTTON_ACTIONS, LB_SHORTCUTS, DPAD_ACTIONS, LT_ACTION, RT_ACTION,
)
from ui.dashboard.binding_editor import BindingEditor
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
        # Each treeview iid maps to ("section", key) where section is
        # "button_actions" | "lb_shortcuts" | "dpad_actions" | "trigger"
        # and key is the int/tuple/str that identifies the binding.
        self._row_keys: dict = {}

        bar = ttk.Frame(self, style="Dash.TFrame")
        bar.pack(fill="x", padx=8, pady=(8, 4))
        ttk.Label(bar, text="Active profile bindings",
                  style="DashBold.TLabel").pack(side="left")
        ttk.Button(bar, text="Edit", style="Dash.TButton",
                   command=self._edit_selected).pack(side="right")
        ttk.Button(bar, text="Refresh", style="Dash.TButton",
                   command=self._refresh).pack(side="right", padx=6)

        self._tree = ttk.Treeview(self,
            columns=("action",), show="tree headings",
            selectmode="browse", height=18)
        self._tree.heading("#0", text="Input")
        self._tree.heading("action", text="Action")
        self._tree.column("#0", width=200, anchor="w")
        self._tree.column("action", width=320, anchor="w")
        self._tree.pack(fill="both", expand=True, padx=8, pady=(0, 8))
        self._tree.bind("<Double-1>", lambda _e: self._edit_selected())

        self._refresh()
        self.after(500, self._poll)

    def _poll(self) -> None:
        snap = self._bridge.snapshot()
        if snap.profile_name != self._last_profile:
            self._refresh()
        self.after(500, self._poll)

    # ---- refresh ----
    def _current_profile(self) -> dict:
        name = self._bridge.snapshot().profile_name
        for p in self._bridge.list_profiles():
            if p.get("name") == name:
                return p
        return {}

    def _refresh(self) -> None:
        snap = self._bridge.snapshot()
        self._last_profile = snap.profile_name
        self._tree.delete(*self._tree.get_children())
        self._row_keys.clear()

        profile = self._current_profile()
        btns    = profile.get("button_actions", BUTTON_ACTIONS)
        chords  = profile.get("lb_shortcuts", LB_SHORTCUTS)
        dpad    = profile.get("dpad_actions", DPAD_ACTIONS)
        lt      = profile.get("lt_action", LT_ACTION)
        rt      = profile.get("rt_action", RT_ACTION)

        g_normal = self._tree.insert("", "end", text="Buttons", open=True)
        for idx, action in btns.items():
            iid = self._tree.insert(g_normal, "end",
                text=f"  {_BTN_IDX_TO_NAME.get(idx, idx)}",
                values=(_format_action(action),))
            self._row_keys[iid] = ("button_actions", idx)

        g_chord = self._tree.insert("", "end", text="LB + button (chord)", open=True)
        for idx, action in chords.items():
            iid = self._tree.insert(g_chord, "end",
                text=f"  LB + {_BTN_IDX_TO_NAME.get(idx, idx)}",
                values=(_format_action(action),))
            self._row_keys[iid] = ("lb_shortcuts", idx)

        g_dpad = self._tree.insert("", "end", text="D-pad", open=True)
        for hat, action in dpad.items():
            h = tuple(hat) if isinstance(hat, list) else hat
            iid = self._tree.insert(g_dpad, "end",
                text=f"  {_HAT_TO_NAME.get(h, str(h))}",
                values=(_format_action(action),))
            self._row_keys[iid] = ("dpad_actions", h)

        g_trig = self._tree.insert("", "end", text="Triggers", open=True)
        iid = self._tree.insert(g_trig, "end", text="  LT",
            values=(_format_action(lt),))
        self._row_keys[iid] = ("trigger", "lt")
        iid = self._tree.insert(g_trig, "end", text="  RT",
            values=(_format_action(rt),))
        self._row_keys[iid] = ("trigger", "rt")

    # ---- edit ----
    def _edit_selected(self) -> None:
        sel = self._tree.selection()
        if not sel:
            return
        iid = sel[0]
        meta = self._row_keys.get(iid)
        if meta is None:
            return   # heading row
        section, key = meta
        profile = self._current_profile()
        if not profile:
            return
        current = self._current_binding(profile, section, key)
        label = self._tree.item(iid, "text").strip()

        def on_save(new_action):
            self._apply_binding(profile, section, key, new_action)
            if self._bridge.save_profile(profile):
                self._refresh()
            else:
                messagebox.showwarning("Save failed",
                    "Could not write profile JSON.\n"
                    "Change applied in memory only; will revert on restart.",
                    parent=self)
                self._refresh()

        BindingEditor(self, label, current, on_save)

    @staticmethod
    def _current_binding(profile: dict, section: str, key: Any):
        if section == "trigger":
            return profile.get(f"{key}_action",
                LT_ACTION if key == "lt" else RT_ACTION)
        defaults = {
            "button_actions": BUTTON_ACTIONS,
            "lb_shortcuts":   LB_SHORTCUTS,
            "dpad_actions":   DPAD_ACTIONS,
        }
        return profile.get(section, {}).get(key, defaults[section].get(key, "noop"))

    @staticmethod
    def _apply_binding(profile: dict, section: str, key: Any, action) -> None:
        if section == "trigger":
            profile[f"{key}_action"] = action
            return
        profile.setdefault(section, {})[key] = action


def _format_action(a) -> str:
    if isinstance(a, str):
        return a
    if isinstance(a, dict):
        t = a.get("type", "?")
        if t == "key":    return f"key: {a.get('key','')}"
        if t == "combo":  return "combo: " + "+".join(a.get("keys", []))
        if t == "text":   return f"text: {a.get('text','')!r}"
        if t == "mouse":  return f"mouse: {a.get('button','')}"
        if t == "launch": return f"launch: {a.get('target','')}"
    return str(a)
