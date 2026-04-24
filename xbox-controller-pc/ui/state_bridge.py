"""
Thread-safe snapshot of live controller/mode/profile state for UI consumers.

The main loop pushes updates every tick (cheap: just stores a reference);
the dashboard polls `snapshot()` at its own cadence (~10 Hz). No locks on
the hot path — we rely on CPython's atomic attribute assignment.
"""
from dataclasses import dataclass, field
from typing import Callable, Dict, List, Optional, Tuple

from controller.reader import ControllerState


@dataclass
class Snapshot:
    mode: str = "desktop"
    profile_name: str = "default"
    connected: bool = False
    axes: Dict[int, float] = field(default_factory=dict)
    buttons: Dict[int, bool] = field(default_factory=dict)
    hat: Tuple[int, int] = (0, 0)
    tick_hz: float = 0.0


class StateBridge:
    """One-writer (main loop) / many-reader (dashboard) snapshot store."""

    def __init__(self) -> None:
        self._snap = Snapshot()
        self._switch_mode_cb: Optional[Callable[[], None]] = None
        self._profiles_cb: Optional[Callable[[], List[dict]]] = None
        self._set_profile_cb: Optional[Callable[[str], None]] = None

    # ---- writer side (called from main loop / profile watcher) ----
    def push_state(self, state: ControllerState, tick_hz: float) -> None:
        self._snap = Snapshot(
            mode=self._snap.mode,
            profile_name=self._snap.profile_name,
            connected=state.connected,
            axes=dict(state.axes),
            buttons=dict(state.buttons),
            hat=state.hat,
            tick_hz=tick_hz,
        )

    def push_mode(self, mode: str) -> None:
        self._snap = Snapshot(
            mode=mode,
            profile_name=self._snap.profile_name,
            connected=self._snap.connected,
            axes=self._snap.axes,
            buttons=self._snap.buttons,
            hat=self._snap.hat,
            tick_hz=self._snap.tick_hz,
        )

    def push_profile(self, name: str) -> None:
        self._snap = Snapshot(
            mode=self._snap.mode,
            profile_name=name,
            connected=self._snap.connected,
            axes=self._snap.axes,
            buttons=self._snap.buttons,
            hat=self._snap.hat,
            tick_hz=self._snap.tick_hz,
        )

    # ---- reader side (dashboard) ----
    def snapshot(self) -> Snapshot:
        return self._snap

    # ---- command hooks (dashboard -> core) ----
    def set_switch_mode(self, fn: Callable[[], None]) -> None:
        self._switch_mode_cb = fn

    def set_profiles_provider(
        self,
        list_fn: Callable[[], List[dict]],
        set_fn: Callable[[str], None],
    ) -> None:
        self._profiles_cb = list_fn
        self._set_profile_cb = set_fn

    def switch_mode(self) -> None:
        if self._switch_mode_cb:
            self._switch_mode_cb()

    def list_profiles(self) -> List[dict]:
        return self._profiles_cb() if self._profiles_cb else []

    def activate_profile(self, name: str) -> None:
        if self._set_profile_cb:
            self._set_profile_cb(name)
