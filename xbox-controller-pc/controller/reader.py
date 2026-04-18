"""
Reads Xbox controller state via pygame/SDL2.

Handles first-connect, disconnect, and Bluetooth reconnect transparently.
Callers receive a ControllerState snapshot each tick; they never touch pygame directly.
"""
import logging
from dataclasses import dataclass, field
from typing import Dict, Optional, Tuple

import pygame

from config.settings import DEADZONE, TRIGGER_DEADZONE, AXIS_LT, AXIS_RT

logger = logging.getLogger(__name__)


@dataclass
class ControllerState:
    connected: bool = False
    # axis_index -> value in [-1.0, 1.0], dead-zone already applied
    axes: Dict[int, float] = field(default_factory=dict)
    # button_index -> bool
    buttons: Dict[int, bool] = field(default_factory=dict)
    # (x, y) from first hat; each component is -1, 0, or 1
    hat: Tuple[int, int] = (0, 0)


class ControllerReader:
    """Wraps a single pygame Joystick with automatic reconnect support."""

    def __init__(self) -> None:
        self._joystick: Optional[pygame.joystick.Joystick] = None
        self.state = ControllerState()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def initialize(self) -> bool:
        """Call once at startup. Returns True if a controller is found."""
        if not pygame.get_init():
            pygame.init()
        pygame.joystick.init()
        found = self._try_connect()
        if not found:
            logger.warning("No controller detected at startup — will retry every %.1fs.",
                           2.0)
        return found

    def poll(self) -> ControllerState:
        """
        Pump the SDL event queue and return an updated ControllerState.
        Safe to call every tick; reconnect attempts are rate-limited by the caller.
        """
        self._process_events()

        if self._joystick and self.state.connected:
            self._read_joystick()

        return self.state

    def try_reconnect(self) -> bool:
        """Explicitly try to find and attach a controller. Returns True on success."""
        pygame.joystick.quit()
        pygame.joystick.init()
        return self._try_connect()

    def shutdown(self) -> None:
        if self._joystick:
            try:
                self._joystick.quit()
            except Exception:
                pass
        pygame.joystick.quit()

    # ------------------------------------------------------------------
    # Convenience accessors
    # ------------------------------------------------------------------

    def button(self, index: int) -> bool:
        return self.state.buttons.get(index, False)

    def axis(self, index: int) -> float:
        return self.state.axes.get(index, 0.0)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _process_events(self) -> None:
        for event in pygame.event.get():
            if event.type == pygame.JOYDEVICEREMOVED:
                logger.warning("Controller disconnected (instance_id=%s).", event.instance_id)
                self._joystick = None
                self.state.connected = False
                self._clear_state()
            elif event.type == pygame.JOYDEVICEADDED:
                if not self.state.connected:
                    logger.info("Controller device added — reconnecting.")
                    self._try_connect()

    def _try_connect(self) -> bool:
        count = pygame.joystick.get_count()
        if count == 0:
            return False
        try:
            joy = pygame.joystick.Joystick(0)
            joy.init()
            self._joystick = joy
            self.state.connected = True
            logger.info("Connected: %s  (axes=%d  buttons=%d  hats=%d)",
                        joy.get_name(), joy.get_numaxes(),
                        joy.get_numbuttons(), joy.get_numhats())
            return True
        except pygame.error as exc:
            logger.error("Failed to initialise joystick: %s", exc)
            return False

    def _read_joystick(self) -> None:
        j = self._joystick

        # Axes — apply appropriate dead-zone per axis type
        for i in range(j.get_numaxes()):
            raw = j.get_axis(i)
            dz = TRIGGER_DEADZONE if i in (AXIS_LT, AXIS_RT) else DEADZONE
            self.state.axes[i] = self._apply_deadzone(raw, dz)

        # Buttons
        for i in range(j.get_numbuttons()):
            self.state.buttons[i] = bool(j.get_button(i))

        # Hat / D-pad
        if j.get_numhats() > 0:
            self.state.hat = j.get_hat(0)

    @staticmethod
    def _apply_deadzone(value: float, deadzone: float) -> float:
        if abs(value) < deadzone:
            return 0.0
        # Rescale so the output still spans [-1, 1] outside the dead-zone
        sign = 1.0 if value > 0 else -1.0
        return sign * (abs(value) - deadzone) / (1.0 - deadzone)

    def _clear_state(self) -> None:
        self.state.axes.clear()
        self.state.buttons.clear()
        self.state.hat = (0, 0)
