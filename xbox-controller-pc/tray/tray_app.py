"""
System tray icon via pystray.

Runs in its own daemon thread (call start(), not run()).
The icon colour reflects the active mode:
  Blue  (#4a9eff) = Desktop mode
  Green (#4aff7a) = Game mode
"""
import logging
import threading
from typing import Callable, Optional

logger = logging.getLogger(__name__)

try:
    import pystray
    from PIL import Image, ImageDraw
    _PYSTRAY_AVAILABLE = True
except ImportError as _e:
    _PYSTRAY_AVAILABLE = False
    logger.warning("pystray/Pillow not available (%s) — tray icon disabled.", _e)


_MODE_COLORS = {
    "desktop": "#4a9eff",
    "game":    "#4aff7a",
}
_ICON_SIZE = 64


def _make_icon(mode: str) -> "Image.Image":
    color = _MODE_COLORS.get(mode, "#888888")
    img = Image.new("RGBA", (_ICON_SIZE, _ICON_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    # Filled circle with a white border
    draw.ellipse([4, 4, _ICON_SIZE - 4, _ICON_SIZE - 4],
                 fill=color, outline="white", width=3)
    return img


class TrayApp:
    def __init__(
        self,
        get_mode_fn: Callable[[], str],
        switch_mode_fn: Callable[[], None],
        quit_fn: Callable[[], None],
        open_dashboard_fn: Optional[Callable[[], None]] = None,
    ) -> None:
        """
        Args:
            get_mode_fn:    returns current mode string ("game"/"desktop").
            switch_mode_fn: called when user clicks "Switch Mode".
            quit_fn:        called when user clicks "Quit".
            open_dashboard_fn: called when user clicks "Open Dashboard" (optional).
        """
        self._get_mode = get_mode_fn
        self._switch_mode = switch_mode_fn
        self._quit_fn = quit_fn
        self._open_dashboard_fn = open_dashboard_fn
        self._icon: Optional[object] = None

    def start(self) -> None:
        """Launch the tray icon on a daemon thread. Returns immediately."""
        if not _PYSTRAY_AVAILABLE:
            return
        mode = self._get_mode()
        self._icon = pystray.Icon(
            name="xbox-controller-pc",
            icon=_make_icon(mode),
            title=f"xbox-controller-pc  [{mode.upper()}]",
            menu=self._build_menu(),
        )
        t = threading.Thread(target=self._icon.run, daemon=True, name="tray")
        t.start()
        logger.info("Tray icon started.")

    def update_mode(self, new_mode: str) -> None:
        """Call from any thread when the mode changes."""
        if self._icon is None:
            return
        try:
            self._icon.icon = _make_icon(new_mode)
            self._icon.title = f"xbox-controller-pc  [{new_mode.upper()}]"
            self._icon.update_menu()
        except Exception:
            logger.exception("Tray update failed")

    def stop(self) -> None:
        if self._icon:
            try:
                self._icon.stop()
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _build_menu(self) -> "pystray.Menu":
        return pystray.Menu(
            pystray.MenuItem(
                lambda item: f"Mode: {self._get_mode().upper()}",
                action=None,
                enabled=False,
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Switch Mode",     self._on_switch),
            pystray.MenuItem("Open Dashboard",  self._on_open_dashboard,
                             visible=lambda item: self._open_dashboard_fn is not None),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit",            self._on_quit),
        )

    def _on_open_dashboard(self, icon, item) -> None:  # noqa: ANN001
        if self._open_dashboard_fn is None:
            return
        try:
            self._open_dashboard_fn()
        except Exception:
            logger.exception("Open dashboard from tray failed")

    def _on_switch(self, icon, item) -> None:  # noqa: ANN001
        try:
            self._switch_mode()
        except Exception:
            logger.exception("Switch mode from tray failed")

    def _on_quit(self, icon, item) -> None:  # noqa: ANN001
        try:
            self._quit_fn()
        except Exception:
            logger.exception("Quit from tray failed")
