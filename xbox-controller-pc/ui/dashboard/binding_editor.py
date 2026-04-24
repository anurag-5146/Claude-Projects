"""
Modal dialog for editing a single button/chord/dpad/trigger binding.

Presents a category notebook: Named / Key / Combo / Text / Mouse / Launch.
On OK, calls `on_save(action)` with either a string (named) or a dict
(structured action) — whatever the active tab produced.
"""
import tkinter as tk
from tkinter import ttk
from typing import Any, Callable, Dict, List, Union

from output.actions import LAUNCH_ALIASES, named_action_catalog, resolve_key
from ui.dashboard import theme as T
from ui.dashboard.widgets import apply_ttk_theme

Action = Union[str, Dict[str, Any]]
_MOUSE_BUTTONS = ["left_click", "right_click", "middle_click", "double_click"]


class BindingEditor(tk.Toplevel):
    def __init__(
        self,
        master: tk.Misc,
        title: str,
        current: Action,
        on_save: Callable[[Action], None],
    ) -> None:
        super().__init__(master)
        self.title(f"Bind — {title}")
        self.geometry("460x360")
        self.resizable(False, False)
        self.configure(bg=T.BG)
        self.transient(master)
        apply_ttk_theme(self)

        self._on_save = on_save

        ttk.Label(self, text=f"Action for: {title}",
                  style="DashH1.TLabel").pack(anchor="w", padx=12, pady=(10, 4))
        self._current_var = tk.StringVar(value=f"Current: {self._format(current)}")
        ttk.Label(self, textvariable=self._current_var,
                  style="DashMuted.TLabel").pack(anchor="w", padx=12)

        self._nb = ttk.Notebook(self)
        self._nb.pack(fill="both", expand=True, padx=12, pady=10)

        self._build_named_tab()
        self._build_key_tab()
        self._build_combo_tab()
        self._build_text_tab()
        self._build_mouse_tab()
        self._build_launch_tab()
        self._preselect(current)

        btns = ttk.Frame(self, style="Dash.TFrame")
        btns.pack(fill="x", padx=12, pady=(0, 10))
        ttk.Button(btns, text="Cancel", style="Dash.TButton",
                   command=self.destroy).pack(side="right")
        ttk.Button(btns, text="Save", style="Dash.TButton",
                   command=self._save).pack(side="right", padx=6)
        ttk.Button(btns, text="Clear (noop)", style="Dash.TButton",
                   command=self._save_noop).pack(side="left")

        self.grab_set()

    # ---- tab builders ----
    def _build_named_tab(self) -> None:
        f = ttk.Frame(self._nb, style="Dash.TFrame")
        self._nb.add(f, text="Named")
        ttk.Label(f, text="Built-in actions",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(8, 2))
        self._named_var = tk.StringVar()
        self._named_cb = ttk.Combobox(f, textvariable=self._named_var,
            values=named_action_catalog(), state="readonly", width=40)
        self._named_cb.pack(anchor="w", padx=8, pady=4)

    def _build_key_tab(self) -> None:
        f = ttk.Frame(self._nb, style="Dash.TFrame")
        self._nb.add(f, text="Key")
        ttk.Label(f, text="Single key (e.g. a, f5, space, enter)",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(8, 2))
        self._key_var = tk.StringVar()
        entry = ttk.Entry(f, textvariable=self._key_var, width=30)
        entry.pack(anchor="w", padx=8, pady=4)
        self._key_preview = ttk.Label(f, text="", style="DashMuted.TLabel")
        self._key_preview.pack(anchor="w", padx=8)
        self._key_var.trace_add("write", lambda *_: self._update_key_preview())

    def _build_combo_tab(self) -> None:
        f = ttk.Frame(self._nb, style="Dash.TFrame")
        self._nb.add(f, text="Combo")
        ttk.Label(f, text="Modifiers + key (e.g. Ctrl+Shift+T)",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(8, 2))

        row = ttk.Frame(f, style="Dash.TFrame")
        row.pack(anchor="w", padx=8, pady=4)
        self._combo_ctrl  = tk.BooleanVar()
        self._combo_alt   = tk.BooleanVar()
        self._combo_shift = tk.BooleanVar()
        self._combo_win   = tk.BooleanVar()
        for label, var in [("Ctrl", self._combo_ctrl), ("Alt", self._combo_alt),
                           ("Shift", self._combo_shift), ("Win", self._combo_win)]:
            ttk.Checkbutton(row, text=label, variable=var).pack(side="left", padx=4)

        row2 = ttk.Frame(f, style="Dash.TFrame")
        row2.pack(anchor="w", padx=8, pady=(6, 4))
        ttk.Label(row2, text="Key:", style="Dash.TLabel").pack(side="left")
        self._combo_key_var = tk.StringVar()
        ttk.Entry(row2, textvariable=self._combo_key_var, width=15).pack(side="left", padx=6)

        tip = "Examples: t, f5, tab, space, delete, up, enter"
        ttk.Label(f, text=tip, style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(8, 0))

    def _build_text_tab(self) -> None:
        f = ttk.Frame(self._nb, style="Dash.TFrame")
        self._nb.add(f, text="Text")
        ttk.Label(f, text="Type this text on press",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(8, 2))
        self._text_var = tk.StringVar()
        ttk.Entry(f, textvariable=self._text_var, width=50).pack(
            anchor="w", padx=8, pady=4, fill="x")

    def _build_mouse_tab(self) -> None:
        f = ttk.Frame(self._nb, style="Dash.TFrame")
        self._nb.add(f, text="Mouse")
        self._mouse_var = tk.StringVar(value="left_click")
        for b in _MOUSE_BUTTONS:
            ttk.Radiobutton(f, text=b.replace("_", " ").title(),
                            value=b, variable=self._mouse_var).pack(
                anchor="w", padx=10, pady=2)

    def _build_launch_tab(self) -> None:
        f = ttk.Frame(self._nb, style="Dash.TFrame")
        self._nb.add(f, text="Launch")
        ttk.Label(f, text="Preset",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(8, 2))
        self._launch_alias_var = tk.StringVar()
        ttk.Combobox(f, textvariable=self._launch_alias_var,
            values=[""] + sorted(LAUNCH_ALIASES.keys()),
            state="readonly", width=30).pack(anchor="w", padx=8, pady=4)

        ttk.Label(f, text="Or custom (URL, .exe path, or protocol like spotify:)",
                  style="DashMuted.TLabel").pack(anchor="w", padx=8, pady=(10, 2))
        self._launch_custom_var = tk.StringVar()
        ttk.Entry(f, textvariable=self._launch_custom_var, width=50).pack(
            anchor="w", padx=8, pady=4, fill="x")

    # ---- behavior ----
    def _update_key_preview(self) -> None:
        k = resolve_key(self._key_var.get())
        self._key_preview.config(
            text=f"  → {k!r}" if k is not None else "  → (unknown)")

    def _preselect(self, current: Action) -> None:
        # Best-effort: put the user on the tab matching the current action
        if isinstance(current, str) and current in named_action_catalog():
            self._nb.select(0)
            self._named_var.set(current)
            return
        if isinstance(current, dict):
            t = current.get("type")
            order = {"key": 1, "combo": 2, "text": 3, "mouse": 4, "launch": 5}
            self._nb.select(order.get(t, 0))
            if t == "key":
                self._key_var.set(str(current.get("key", "")))
            elif t == "combo":
                keys = [str(k).lower() for k in current.get("keys", [])]
                self._combo_ctrl.set("ctrl" in keys)
                self._combo_alt.set("alt" in keys)
                self._combo_shift.set("shift" in keys)
                self._combo_win.set("win" in keys or "cmd" in keys)
                non_mods = [k for k in keys if k not in ("ctrl","alt","shift","win","cmd")]
                if non_mods:
                    self._combo_key_var.set(non_mods[-1])
            elif t == "text":
                self._text_var.set(str(current.get("text", "")))
            elif t == "mouse":
                btn = str(current.get("button", "left_click"))
                if not btn.endswith("_click"):
                    btn = f"{btn}_click"
                self._mouse_var.set(btn)
            elif t == "launch":
                tgt = str(current.get("target", ""))
                if tgt.lower() in LAUNCH_ALIASES:
                    self._launch_alias_var.set(tgt.lower())
                else:
                    self._launch_custom_var.set(tgt)

    def _save(self) -> None:
        action = self._read_active()
        if action is None:
            return
        self._on_save(action)
        self.destroy()

    def _save_noop(self) -> None:
        self._on_save("noop")
        self.destroy()

    def _read_active(self) -> Action:
        idx = self._nb.index(self._nb.select())
        if idx == 0:
            return self._named_var.get() or "noop"
        if idx == 1:
            k = self._key_var.get().strip().lower()
            if not k:
                return None
            return {"type": "key", "key": k}
        if idx == 2:
            keys: List[str] = []
            if self._combo_ctrl.get():  keys.append("ctrl")
            if self._combo_alt.get():   keys.append("alt")
            if self._combo_shift.get(): keys.append("shift")
            if self._combo_win.get():   keys.append("win")
            k = self._combo_key_var.get().strip().lower()
            if k:
                keys.append(k)
            if not keys:
                return None
            return {"type": "combo", "keys": keys}
        if idx == 3:
            return {"type": "text", "text": self._text_var.get()}
        if idx == 4:
            return {"type": "mouse", "button": self._mouse_var.get()}
        if idx == 5:
            alias = self._launch_alias_var.get().strip()
            custom = self._launch_custom_var.get().strip()
            target = alias or custom
            if not target:
                return None
            return {"type": "launch", "target": target}
        return None

    @staticmethod
    def _format(a: Action) -> str:
        if isinstance(a, str):
            return a
        if isinstance(a, dict):
            t = a.get("type", "?")
            if t == "key":    return f"key({a.get('key','')})"
            if t == "combo":  return "+".join(a.get("keys", []))
            if t == "text":   return f"text({a.get('text','')!r})"
            if t == "mouse":  return a.get("button", "?")
            if t == "launch": return f"launch({a.get('target','')})"
        return str(a)
