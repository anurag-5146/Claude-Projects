"""
Main event loop.

Wires ControllerReader → ModeManager → active mode handler.
Mode handlers are plain callables with signature:
    handler(state: ControllerState) -> None

They are registered via set_game_handler() / set_desktop_handler() before
calling run(), so the loop itself never imports from modes/ or output/.
"""
import logging
import time
from typing import Callable, List, Optional

import pygame

from config.settings import Mode, RECONNECT_POLL_INTERVAL, TICK_RATE
from controller.reader import ControllerReader, ControllerState
from core.mode_manager import ModeManager

logger = logging.getLogger(__name__)

Handler = Callable[[ControllerState], None]
SwitchListener = Callable[[str], None]
StateObserver = Callable[[ControllerState, float], None]


class MainLoop:
    def __init__(self) -> None:
        self._reader = ControllerReader()
        self._mode_manager = ModeManager(on_switch=self._on_mode_switch)
        self._running = False

        self._game_handler: Optional[Handler] = None
        self._desktop_handler: Optional[Handler] = None
        self._switch_listeners: List[SwitchListener] = []
        self._state_observers: List[StateObserver] = []

        self._last_reconnect_attempt: float = 0.0
        self._tick_duration: float = 1.0 / TICK_RATE
        self._last_tick_time: float = 0.0
        self._tick_hz_ema: float = 0.0

    # ------------------------------------------------------------------
    # Handler / listener registration
    # ------------------------------------------------------------------

    def set_game_handler(self, handler: Handler) -> None:
        self._game_handler = handler

    def set_desktop_handler(self, handler: Handler) -> None:
        self._desktop_handler = handler

    def add_switch_listener(self, listener: SwitchListener) -> None:
        """Register a callback invoked (new_mode: str) on every mode switch."""
        self._switch_listeners.append(listener)

    def add_state_observer(self, observer: StateObserver) -> None:
        """Register a per-tick observer: (state, tick_hz) -> None.

        Must be fast and non-blocking — runs on the loop thread.
        """
        self._state_observers.append(observer)

    @property
    def mode_manager(self) -> ModeManager:
        return self._mode_manager

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def run(self) -> None:
        """Block until stop() is called or KeyboardInterrupt is received."""
        logger.info("Initialising controller reader…")
        self._reader.initialize()

        self._running = True
        logger.info(
            "Running in %s mode. Hold Guide+RB for %.1fs to toggle.",
            self._mode_manager.current_mode.upper(),
            0.5,
        )

        try:
            while self._running:
                t0 = time.monotonic()
                self._tick()
                self._sleep_remainder(t0)
        except KeyboardInterrupt:
            logger.info("KeyboardInterrupt — shutting down.")
        finally:
            self._shutdown()

    def stop(self) -> None:
        self._running = False

    # ------------------------------------------------------------------
    # Per-tick logic
    # ------------------------------------------------------------------

    def _tick(self) -> None:
        state = self._reader.poll()

        # Instantaneous tick-rate (EMA) for UI consumers
        now = time.monotonic()
        if self._last_tick_time > 0.0:
            dt = now - self._last_tick_time
            if dt > 0:
                inst = 1.0 / dt
                self._tick_hz_ema = (0.9 * self._tick_hz_ema + 0.1 * inst) \
                    if self._tick_hz_ema else inst
        self._last_tick_time = now

        for obs in self._state_observers:
            try:
                obs(state, self._tick_hz_ema)
            except Exception:
                logger.exception("state observer raised")

        if not state.connected:
            self._maybe_reconnect()
            return

        switched = self._mode_manager.update(state.buttons)
        if switched:
            pass

        self._dispatch(state)

    def _dispatch(self, state: ControllerState) -> None:
        mode = self._mode_manager.current_mode
        handler: Optional[Handler] = None

        if mode == Mode.GAME:
            handler = self._game_handler
        elif mode == Mode.DESKTOP:
            handler = self._desktop_handler

        if handler is not None:
            try:
                handler(state)
            except Exception:
                logger.exception("Exception in %s handler", mode)

    def _maybe_reconnect(self) -> None:
        now = time.monotonic()
        if now - self._last_reconnect_attempt >= RECONNECT_POLL_INTERVAL:
            self._last_reconnect_attempt = now
            logger.debug("No controller — attempting reconnect…")
            self._reader.try_reconnect()

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _on_mode_switch(self, new_mode: str) -> None:
        logger.info("Active mode: %s", new_mode.upper())
        for listener in self._switch_listeners:
            try:
                listener(new_mode)
            except Exception:
                logger.exception("Switch listener raised")

    def _sleep_remainder(self, tick_start: float) -> None:
        elapsed = time.monotonic() - tick_start
        remaining = self._tick_duration - elapsed
        if remaining > 0:
            time.sleep(remaining)

    def _shutdown(self) -> None:
        self._reader.shutdown()
        pygame.quit()
        logger.info("Shutdown complete.")
