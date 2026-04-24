"""
Profiles tab: list loaded profiles, show their match patterns, and test
which profile would match an arbitrary window title.
"""
import tkinter as tk
from tkinter import ttk

from ui.dashboard import theme as T
from ui.state_bridge import StateBridge


class ProfilesTab(ttk.Frame):
    def __init__(self, master: tk.Misc, bridge: StateBridge) -> None:
        super().__init__(master, style="Dash.TFrame")
        self._bridge = bridge

        top = ttk.Frame(self, style="Dash.TFrame")
        top.pack(fill="x", padx=8, pady=(8, 4))
        ttk.Label(top, text="Loaded profiles",
                  style="DashBold.TLabel").pack(side="left")
        ttk.Button(top, text="Reload", style="Dash.TButton",
                   command=self._refresh).pack(side="right")

        # Profile list
        self._tree = ttk.Treeview(self,
            columns=("patterns", "status"),
            show="tree headings", selectmode="browse", height=10)
        self._tree.heading("#0", text="Profile")
        self._tree.heading("patterns", text="Match patterns")
        self._tree.heading("status", text="")
        self._tree.column("#0", width=160, anchor="w")
        self._tree.column("patterns", width=300, anchor="w")
        self._tree.column("status", width=80, anchor="center")
        self._tree.pack(fill="both", expand=True, padx=8, pady=(0, 8))

        # Match tester
        tester = ttk.Frame(self, style="DashElevated.TFrame")
        tester.pack(fill="x", padx=8, pady=(0, 8))
        inner = tk.Frame(tester, bg=T.BG_ELEVATED)
        inner.pack(fill="x", padx=8, pady=8)

        tk.Label(inner, text="Test match:",
                 bg=T.BG_ELEVATED, fg=T.FG_MUTED,
                 font=T.FONT_LABEL).pack(side="left")
        self._test_var = tk.StringVar()
        entry = tk.Entry(inner, textvariable=self._test_var,
                         bg=T.BG_SUNKEN, fg=T.FG,
                         insertbackground=T.FG,
                         relief="flat", font=T.FONT_BODY)
        entry.pack(side="left", fill="x", expand=True, padx=8)
        entry.bind("<KeyRelease>", lambda _e: self._update_match())

        self._match_var = tk.StringVar(value="—")
        tk.Label(inner, textvariable=self._match_var,
                 bg=T.BG_ELEVATED, fg=T.ACCENT,
                 font=T.FONT_BOLD).pack(side="right")

        self._refresh()
        self.after(1000, self._poll)

    def _poll(self) -> None:
        self._update_active_marker()
        self.after(1000, self._poll)

    def _refresh(self) -> None:
        self._tree.delete(*self._tree.get_children())
        for p in self._bridge.list_profiles():
            name = p.get("name", "?")
            patterns = ", ".join(p.get("match_patterns", [])) or "(default)"
            self._tree.insert("", "end", iid=name, text=name,
                values=(patterns, ""))
        self._update_active_marker()

    def _update_active_marker(self) -> None:
        active = self._bridge.snapshot().profile_name
        for iid in self._tree.get_children():
            marker = "● active" if iid == active else ""
            self._tree.set(iid, "status", marker)

    def _update_match(self) -> None:
        title = self._test_var.get().lower()
        if not title:
            self._match_var.set("—")
            return
        matched = "default"
        for p in self._bridge.list_profiles():
            if p.get("name") == "default":
                continue
            for pattern in p.get("match_patterns", []):
                if pattern.lower() in title:
                    matched = p.get("name", "?")
                    break
            if matched != "default":
                break
        self._match_var.set(matched)
