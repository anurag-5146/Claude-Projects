"""
Mouse and keyboard output via pynput.
"""
import logging

from pynput.keyboard import Controller as KbController, Key
from pynput.mouse import Button, Controller as MouseController

logger = logging.getLogger(__name__)


class MouseKeyboardOutput:
    def __init__(self) -> None:
        self._mouse = MouseController()
        self._kb = KbController()

    # ------------------------------------------------------------------
    # Mouse
    # ------------------------------------------------------------------

    def move(self, dx: int, dy: int) -> None:
        if dx or dy:
            self._mouse.move(dx, dy)

    def scroll(self, clicks: int) -> None:
        """Positive = scroll up, negative = scroll down."""
        if clicks:
            self._mouse.scroll(0, clicks)

    def h_scroll(self, clicks: int) -> None:
        """Positive = scroll right, negative = scroll left."""
        if clicks:
            self._mouse.scroll(clicks, 0)

    def left_click(self) -> None:
        self._mouse.click(Button.left)

    def right_click(self) -> None:
        self._mouse.click(Button.right)

    def middle_click(self) -> None:
        self._mouse.click(Button.middle)

    # ------------------------------------------------------------------
    # Keyboard — single keys
    # ------------------------------------------------------------------

    def tap(self, key) -> None:
        try:
            self._kb.tap(key)
        except Exception:
            logger.exception("Key tap failed: %s", key)

    def press(self, key) -> None:
        try:
            self._kb.press(key)
        except Exception:
            logger.exception("Key press failed: %s", key)

    def release(self, key) -> None:
        try:
            self._kb.release(key)
        except Exception:
            logger.exception("Key release failed: %s", key)

    # ------------------------------------------------------------------
    # Keyboard — combos
    # ------------------------------------------------------------------

    def combo(self, *keys) -> None:
        """Press all keys in order, then release in reverse."""
        try:
            for k in keys:
                self._kb.press(k)
            for k in reversed(keys):
                self._kb.release(k)
        except Exception:
            logger.exception("Key combo failed: %s", keys)

    # ------------------------------------------------------------------
    # Named combos used by DesktopMode
    # ------------------------------------------------------------------

    def alt_tab(self)        -> None: self.combo(Key.alt, Key.tab)
    def alt_f4(self)         -> None: self.combo(Key.alt, Key.f4)
    def win_d(self)          -> None: self.combo(Key.cmd, 'd')
    def win_tab(self)        -> None: self.combo(Key.cmd, Key.tab)
    def print_screen(self)   -> None: self.tap(Key.print_screen)
    def browser_back(self)   -> None: self.combo(Key.alt, Key.left)
    def browser_forward(self)-> None: self.combo(Key.alt, Key.right)
    def enter(self)          -> None: self.tap(Key.enter)
    def escape(self)         -> None: self.tap(Key.esc)
