"""
On-screen display (OSD) overlay: a small borderless tkinter window that
appears briefly when the mode switches, then fades out.

Threading model
---------------
tkinter must run on the main thread (Windows requirement).
`OSDOverlay.run()` blocks the calling thread — put it on the main thread
and run the controller loop on a daemon thread.

Other threads post messages via `OSDOverlay.show(text)`, which is
thread-safe because it only puts items in a Queue.
"""
import logging
import queue
import tkinter as tk
from typing import Callable, Optional

logger = logging.getLogger(__name__)

_FONT_FAMILY  = "Segoe UI"
_FONT_SIZE    = 22
_BG_COLOR     = "#1a1a2e"
_FG_COLOR     = "#e0e0ff"
_SHOW_TICKS   = 18   # 18 × 80ms = ~1.4 s fully visible
_FADE_STEPS   = 10   # 10 × 80ms = 0.8 s fade
_POLL_MS      = 80


class OSDOverlay:
    def __init__(self) -> None:
        self._q: queue.Queue[str] = queue.Queue()
        self._callbacks: "queue.Queue[Callable[[], None]]" = queue.Queue()
        self._root: Optional[tk.Tk] = None
        self._label: Optional[tk.Label] = None
        self._after_id: Optional[str] = None
        self._ticks_left: int = 0
        self._fading: bool = False

    # ------------------------------------------------------------------
    # Thread-safe message API
    # ------------------------------------------------------------------

    def show(self, text: str) -> None:
        """Post a message to display. Safe to call from any thread."""
        self._q.put(text)

    # ------------------------------------------------------------------
    # Main-thread lifecycle
    # ------------------------------------------------------------------

    def run(self) -> None:
        """
        Build the tkinter window and enter mainloop.
        Blocks until the window is destroyed.
        """
        self._root = tk.Tk()
        self._root.overrideredirect(True)
        self._root.attributes("-topmost", True)
        self._root.attributes("-alpha", 0.0)   # start invisible
        self._root.configure(bg=_BG_COLOR)
        self._root.withdraw()

        self._label = tk.Label(
            self._root,
            text="",
            font=(_FONT_FAMILY, _FONT_SIZE, "bold"),
            fg=_FG_COLOR,
            bg=_BG_COLOR,
            padx=28,
            pady=14,
        )
        self._label.pack()

        self._root.after(_POLL_MS, self._poll)
        self._root.mainloop()

    def destroy(self) -> None:
        """Ask the OSD to tear itself down (from any thread)."""
        self._q.put("__quit__")

    def run_on_main(self, fn: Callable[[], None]) -> None:
        """Schedule a callable to run on the tkinter main thread.

        Required for anything that creates Toplevels (e.g. the dashboard),
        since tkinter is not thread-safe on Windows.
        """
        self._callbacks.put(fn)

    def root(self) -> Optional[tk.Tk]:
        """Return the underlying Tk root (None until run() has started)."""
        return self._root

    # ------------------------------------------------------------------
    # Internal: tkinter-thread only below this line
    # ------------------------------------------------------------------

    def _poll(self) -> None:
        # Drain any main-thread callbacks first (dashboard open, etc.)
        while True:
            try:
                cb = self._callbacks.get_nowait()
            except queue.Empty:
                break
            try:
                cb()
            except Exception:
                logger.exception("run_on_main callback failed")

        try:
            text = self._q.get_nowait()
        except queue.Empty:
            text = None

        if text == "__quit__":
            self._root.destroy()
            return

        if text:
            self._display(text)
        elif self._ticks_left > 0:
            self._tick()

        self._root.after(_POLL_MS, self._poll)

    def _display(self, text: str) -> None:
        self._label.config(text=self._mode_label(text))
        self._root.update_idletasks()

        # Centre on primary monitor
        w = self._root.winfo_reqwidth()
        h = self._root.winfo_reqheight()
        sw = self._root.winfo_screenwidth()
        sh = self._root.winfo_screenheight()
        x = (sw - w) // 2
        y = sh // 4            # upper quarter looks unobtrusive
        self._root.geometry(f"+{x}+{y}")

        self._root.deiconify()
        self._root.attributes("-alpha", 0.92)
        self._ticks_left = _SHOW_TICKS + _FADE_STEPS
        self._fading = False

    def _tick(self) -> None:
        self._ticks_left -= 1
        if self._ticks_left <= _FADE_STEPS:
            self._fading = True
        if self._fading:
            alpha = max(0.0, (self._ticks_left / _FADE_STEPS) * 0.92)
            self._root.attributes("-alpha", alpha)
        if self._ticks_left <= 0:
            self._root.withdraw()
            self._fading = False

    @staticmethod
    def _mode_label(mode: str) -> str:
        icons = {"game": "🎮  Game Mode", "desktop": "🖥  Desktop Mode"}
        return icons.get(mode.lower(), mode)
