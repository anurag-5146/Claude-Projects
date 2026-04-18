"""
Reads Xbox controller state via pygame/SDL2.

Handles first-connect, disconnect, and Bluetooth reconnect transparently.
Callers receive a ControllerState snapshot each tick; they never touch pygame directly.

Instance-ID tracking prevents ViGEmBus virtual-pad JOYDEVICEADDED/REMOVED
events from destabilising the reader.
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
        self._instance_id: int = -1   # SDL instance_id of our connected joystick
        self.state = ControllerState()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def initialize(self) -> bool:
        """Call once at startup. Returns True if a controller is found."""
        if not pygame.get_init():
            pygame.init()
        pygame.joystick.init()
        self._log_available()
        found = self._try_connect()
        if not found:
            logger.warning("No controller detected at startup — will retry every 2s.")
        return found

    def poll(self) -> ControllerState:
        """Pump the SDL event queue and return an updated ControllerState."""
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

    def _log_available(self) -> None:
        """Log all detected joysticks so the user can diagnose GUID conflicts."""
        count = pygame.joystick.get_count()
        logger.info("Joysticks available: %d", count)
        for i in range(count):
            try:
                j = pygame.joystick.Joystick(i)
                j.init()
                logger.info("  [%d] %-28s guid=%s  axes=%d  buttons=%d  hats=%d",
                            i, j.get_name(), j.get_guid(),
                            j.get_numaxes(), j.get_numbuttons(), j.get_numhats())
                j.quit()
            except pygame.error:
                pass

    def _process_events(self) -> None:
        for event in pygame.event.get():
            if event.type == pygame.JOYDEVICEREMOVED:
                # Only care about OUR joystick; ignore ViGEmBus virtual-pad cycling.
                if event.instance_id == self._instance_id:
                    logger.warning("Controller disconnected (instance_id=%d).",
                                   event.instance_id)
                    if self._joystick:
                        try:
                            self._joystick.quit()
                        except Exception:
                            pass
                    self._joystick = None
                    self._instance_id = -1
                    self.state.connected = False
                    self._clear_state()
            elif event.type == pygame.JOYDEVICEADDED:
                if not self.state.connected:
                    logger.info("Device added — attempting reconnect.")
                    self._try_connect()

    def _try_connect(self) -> bool:
        count = pygame.joystick.get_count()
        if count == 0:
            return False
        try:
            joy = pygame.joystick.Joystick(0)
            joy.init()
            self._joystick = joy
            self._instance_id = joy.get_instance_id()
            self.state.connected = True
            logger.info("Connected: [0] %s  guid=%s  instance=%d  axes=%d  buttons=%d  hats=%d",
                        joy.get_name(), joy.get_guid(), self._instance_id,
                        joy.get_numaxes(), joy.get_numbuttons(), joy.get_numhats())
            return True
        except pygame.error as exc:
            logger.error("Failed to connect: %s", exc)
            return False

    def _read_joystick(self) -> None:
        j = self._joystick
        for i in range(j.get_numaxes()):
            raw = j.get_axis(i)
            dz = TRIGGER_DEADZONE if i in (AXIS_LT, AXIS_RT) else DEADZONE
            self.state.axes[i] = self._apply_deadzone(raw, dz)
        for i in range(j.get_numbuttons()):
            self.state.buttons[i] = bool(j.get_button(i))
        if j.get_numhats() > 0:
            self.state.hat = j.get_hat(0)

    @staticmethod
    def _apply_deadzone(value: float, deadzone: float) -> float:
        if abs(value) < deadzone:
            return 0.0
        sign = 1.0 if value > 0 else -1.0
        return sign * (abs(value) - deadzone) / (1.0 - deadzone)

    def _clear_state(self) -> None:
        self.state.axes.clear()
        self.state.buttons.clear()
        self.state.hat = (0, 0)
