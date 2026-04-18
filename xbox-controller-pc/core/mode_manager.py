"""
Tracks the active mode (game / desktop) and detects the toggle combo.

The toggle fires after the combo buttons are held continuously for
COMBO_HOLD_SECONDS, preventing accidental switches. It fires exactly once
per hold — releasing and re-pressing is required to switch again.
"""
import logging
import time
from typing import Callable, Dict, Optional

from config.settings import (
    COMBO_HOLD_SECONDS,
    MODE_TOGGLE_COMBO,
    DEFAULT_MODE,
    Mode,
)

logger = logging.getLogger(__name__)


class ModeManager:
    def __init__(self, on_switch: Optional[Callable[[str], None]] = None) -> None:
        """
        Args:
            on_switch: Optional callback invoked with the new mode string
                       whenever a mode switch occurs.
        """
        self.current_mode: str = DEFAULT_MODE
        self._on_switch = on_switch

        self._combo_start: Optional[float] = None
        self._combo_fired: bool = False

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def update(self, buttons: Dict[int, bool]) -> bool:
        """
        Call once per tick with the current button state.
        Returns True the tick the mode actually changes.
        """
        combo_held = all(buttons.get(b, False) for b in MODE_TOGGLE_COMBO)

        if combo_held:
            if self._combo_start is None:
                self._combo_start = time.monotonic()
                self._combo_fired = False

            elif not self._combo_fired:
                held_for = time.monotonic() - self._combo_start
                if held_for >= COMBO_HOLD_SECONDS:
                    self._switch()
                    self._combo_fired = True
                    return True
        else:
            # Reset whenever the combo breaks so a fresh hold can trigger again
            self._combo_start = None
            self._combo_fired = False

        return False

    def is_game_mode(self) -> bool:
        return self.current_mode == Mode.GAME

    def is_desktop_mode(self) -> bool:
        return self.current_mode == Mode.DESKTOP

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _switch(self) -> None:
        self.current_mode = (
            Mode.DESKTOP if self.current_mode == Mode.GAME else Mode.GAME
        )
        logger.info("Mode switched → %s", self.current_mode.upper())
        if self._on_switch:
            try:
                self._on_switch(self.current_mode)
            except Exception:
                logger.exception("on_switch callback raised an exception")
