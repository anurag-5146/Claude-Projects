"""Small reusable widgets for the dashboard."""
import tkinter as tk
from tkinter import ttk
from typing import Callable, Optional

from ui.dashboard import theme as T


class StatusDot(tk.Canvas):
    """Filled circle used for connection / mode indicators."""

    def __init__(self, master: tk.Misc, size: int = 10) -> None:
        super().__init__(
            master, width=size, height=size,
            bg=T.BG, highlightthickness=0, bd=0,
        )
        self._size = size
        self._oval = self.create_oval(
            1, 1, size - 1, size - 1, fill=T.FG_MUTED, outline="",
        )

    def set_color(self, color: str) -> None:
        self.itemconfig(self._oval, fill=color)


class LabeledSlider(ttk.Frame):
    """Slider + live value label + reset button."""

    def __init__(
        self,
        master: tk.Misc,
        label: str,
        from_: float,
        to: float,
        initial: float,
        on_change: Callable[[float], None],
        fmt: str = "{:.2f}",
        resolution: Optional[float] = None,
    ) -> None:
        super().__init__(master, style="Dash.TFrame")
        self._fmt = fmt
        self._on_change = on_change
        self._default = initial

        self.columnconfigure(1, weight=1)

        ttk.Label(self, text=label, style="Dash.TLabel").grid(
            row=0, column=0, sticky="w", padx=(0, 8),
        )
        self._value_var = tk.StringVar(value=fmt.format(initial))
        ttk.Label(self, textvariable=self._value_var, style="DashMuted.TLabel",
                  width=8, anchor="e").grid(row=0, column=2, sticky="e")

        self._scale = ttk.Scale(
            self, from_=from_, to=to, value=initial,
            orient="horizontal", command=self._on_scale,
        )
        self._scale.grid(row=1, column=0, columnspan=3, sticky="ew", pady=(2, 0))

        if resolution:
            self._resolution = resolution
        else:
            self._resolution = None

    def _on_scale(self, raw: str) -> None:
        val = float(raw)
        if self._resolution:
            val = round(val / self._resolution) * self._resolution
        self._value_var.set(self._fmt.format(val))
        self._on_change(val)

    def set(self, val: float) -> None:
        self._scale.set(val)
        self._value_var.set(self._fmt.format(val))


def apply_ttk_theme(root: tk.Misc) -> None:
    """Apply the dark theme to ttk widgets. Call once per Tk root."""
    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass

    style.configure("Dash.TFrame", background=T.BG)
    style.configure("DashElevated.TFrame", background=T.BG_ELEVATED)
    style.configure("Dash.TLabel", background=T.BG, foreground=T.FG, font=T.FONT_BODY)
    style.configure("DashMuted.TLabel", background=T.BG, foreground=T.FG_MUTED, font=T.FONT_LABEL)
    style.configure("DashBold.TLabel", background=T.BG, foreground=T.FG, font=T.FONT_BOLD)
    style.configure("DashH1.TLabel", background=T.BG, foreground=T.FG, font=T.FONT_H1)

    style.configure("Dash.TButton", background=T.BG_ELEVATED, foreground=T.FG,
                    borderwidth=0, focusthickness=0, padding=6, font=T.FONT_BODY)
    style.map("Dash.TButton",
              background=[("active", T.BORDER), ("pressed", T.ACCENT)])

    style.configure("TNotebook", background=T.BG, borderwidth=0)
    style.configure("TNotebook.Tab", background=T.BG, foreground=T.FG_MUTED,
                    padding=(14, 6), borderwidth=0, font=T.FONT_BODY)
    style.map("TNotebook.Tab",
              background=[("selected", T.BG_ELEVATED)],
              foreground=[("selected", T.FG)])

    style.configure("Dash.Horizontal.TScale", background=T.BG,
                    troughcolor=T.BG_SUNKEN)
    style.configure("Treeview", background=T.BG_ELEVATED, foreground=T.FG,
                    fieldbackground=T.BG_ELEVATED, borderwidth=0,
                    font=T.FONT_BODY, rowheight=22)
    style.configure("Treeview.Heading", background=T.BG, foreground=T.FG_MUTED,
                    font=T.FONT_LABEL, borderwidth=0)
    style.map("Treeview",
              background=[("selected", T.ACCENT)],
              foreground=[("selected", T.BG)])
