"""
Desktop mode: converts controller input into mouse movement, scroll,
clicks, media keys, and Windows shortcuts.

Input routing (defaults — all configurable via profile JSON):
  Right stick X/Y  → mouse movement (acceleration curve applied)
  Left  stick Y    → vertical scroll  (acceleration curve applied)
  Left  stick X    → horizontal scroll
  A                → left click
  B                → right click
  X                → Enter
  Y                → Escape
  LStick click     → middle click
  Start            → play / pause
  LB (modifier) + A → Alt+Tab
  LB (modifier) + B → Alt+F4
  LB (modifier) + X → Win+D
  LB (modifier) + Y → Print Screen
  LB (modifier) + Start/Back → Win+Tab (Task View)
  D-pad Up/Down    → volume up / down
  D-pad Left/Right → prev / next track
  LT (> threshold) → browser back (Alt+Left)
  RT (> threshold) → browser forward (Alt+Right)
"""
import logging
import math
from typing import Dict, Optional, Tuple

from config.settings import (
    AXIS_LT, AXIS_LX, AXIS_LY, AXIS_RT, AXIS_RX, AXIS_RY,
    BUTTON_LB, BUTTON_RB, BUTTON_GUIDE,
    BUTTON_ACTIONS, DPAD_ACTIONS, LB_SHORTCUTS, LT_ACTION, RT_ACTION,
    MOUSE_ACCEL_EXPONENT, MOUSE_SENSITIVITY,
    SCROLL_SENSITIVITY, TICK_RATE, TRIGGER_THRESHOLD,
)
from controller.reader import ControllerState
from output import media
from output.mouse_keyboard import MouseKeyboardOutput

logger = logging.getLogger(__name__)

# Buttons that are modifier-only and must never trigger a normal action
_MODIFIER_BUTTONS = {BUTTON_LB, BUTTON_RB, BUTTON_GUIDE}


class DesktopMode:
    def __init__(self, mouse_kb: MouseKeyboardOutput,
                 profile_fn=None) -> None:
        """
        Args:
            mouse_kb: output driver for mouse and keyboard.
            profile_fn: zero-arg callable returning the current profile dict,
                        or None to use global defaults from settings.py.
        """
        self._mk = mouse_kb
        self._profile_fn = profile_fn or (lambda: {})

        self._prev_buttons: Dict[int, bool] = {}
        self._prev_hat: Tuple[int, int] = (0, 0)
        self._prev_lt_active: bool = False
        self._prev_rt_active: bool = False

        self._mouse_rem_x: float = 0.0
        self._mouse_rem_y: float = 0.0
        self._scroll_rem: float = 0.0
        self._h_scroll_rem: float = 0.0

    # ------------------------------------------------------------------
    # Main handler — called every tick by MainLoop
    # ------------------------------------------------------------------

    def handle(self, state: ControllerState) -> None:
        profile = self._profile_fn()
        lb_held = state.buttons.get(BUTTON_LB, False)

        self._update_mouse(state.axes.get(AXIS_LX, 0.0),
                           state.axes.get(AXIS_LY, 0.0), profile)
        self._update_scroll(state.axes.get(AXIS_RX, 0.0),
                            state.axes.get(AXIS_RY, 0.0), profile)
        self._update_buttons(state.buttons, lb_held, profile)
        self._update_dpad(state.hat, profile)
        self._update_triggers(state.axes, profile)

        self._prev_buttons = dict(state.buttons)
        self._prev_hat = state.hat

    # ------------------------------------------------------------------
    # Mouse movement (right stick, with acceleration curve)
    # ------------------------------------------------------------------

    def _update_mouse(self, ax: float, ay: float, profile: dict) -> None:
        sens = profile.get("mouse_sensitivity", MOUSE_SENSITIVITY)
        exp  = profile.get("mouse_accel_exponent", MOUSE_ACCEL_EXPONENT)

        dx_f = self._accel(ax, exp) * sens + self._mouse_rem_x
        dy_f = self._accel(ay, exp) * sens + self._mouse_rem_y

        idx, idy = int(dx_f), int(dy_f)
        self._mouse_rem_x = dx_f - idx
        self._mouse_rem_y = dy_f - idy
        self._mk.move(idx, idy)

    # ------------------------------------------------------------------
    # Scroll (left stick, with acceleration curve)
    # ------------------------------------------------------------------

    def _update_scroll(self, lx: float, ly: float, profile: dict) -> None:
        sens = profile.get("scroll_sensitivity", SCROLL_SENSITIVITY)
        exp  = profile.get("mouse_accel_exponent", MOUSE_ACCEL_EXPONENT)
        delta_per_tick = sens / TICK_RATE

        # Vertical — note: stick down (ly > 0) = scroll down (negative)
        if ly != 0.0:
            v = self._accel(ly, exp) * delta_per_tick + self._scroll_rem
            iv = int(v)
            self._scroll_rem = v - iv
            self._mk.scroll(-iv)   # invert: stick down → page scrolls down
        else:
            self._scroll_rem = 0.0

        # Horizontal
        if lx != 0.0:
            h = self._accel(lx, exp) * delta_per_tick + self._h_scroll_rem
            ih = int(h)
            self._h_scroll_rem = h - ih
            self._mk.h_scroll(ih)
        else:
            self._h_scroll_rem = 0.0

    # ------------------------------------------------------------------
    # Buttons (edge-detected: fire once on press, not on hold)
    # ------------------------------------------------------------------

    def _update_buttons(self, buttons: Dict[int, bool],
                        lb_held: bool, profile: dict) -> None:
        btn_actions = {**BUTTON_ACTIONS,
                       **profile.get("button_actions", {})}
        lb_map = {**LB_SHORTCUTS, **profile.get("lb_shortcuts", {})}

        for btn, pressed in buttons.items():
            if btn in _MODIFIER_BUTTONS:
                continue
            was = self._prev_buttons.get(btn, False)
            if pressed and not was:
                if lb_held and btn in lb_map:
                    self._do_action(lb_map[btn])
                elif not lb_held and btn in btn_actions:
                    self._do_action(btn_actions[btn])

    # ------------------------------------------------------------------
    # D-pad (fire on hat state change, not every tick)
    # ------------------------------------------------------------------

    def _update_dpad(self, hat: Tuple[int, int], profile: dict) -> None:
        if hat == self._prev_hat:
            return
        dpad = {**DPAD_ACTIONS, **profile.get("dpad_actions", {})}
        if hat in dpad:
            self._do_action(dpad[hat])

    # ------------------------------------------------------------------
    # Triggers (fire once when crossing threshold)
    # ------------------------------------------------------------------

    def _update_triggers(self, axes: Dict[int, float], profile: dict) -> None:
        threshold = profile.get("trigger_threshold", TRIGGER_THRESHOLD)
        lt_action = profile.get("lt_action", LT_ACTION)
        rt_action = profile.get("rt_action", RT_ACTION)

        lt_val = self._norm_trigger(axes.get(AXIS_LT, -1.0))
        rt_val = self._norm_trigger(axes.get(AXIS_RT, -1.0))

        lt_active = lt_val >= threshold
        rt_active = rt_val >= threshold

        if lt_active and not self._prev_lt_active:
            self._do_action(lt_action)
        if rt_active and not self._prev_rt_active:
            self._do_action(rt_action)

        self._prev_lt_active = lt_active
        self._prev_rt_active = rt_active

    # ------------------------------------------------------------------
    # Action dispatcher
    # ------------------------------------------------------------------

    def _do_action(self, action: str) -> None:
        mk = self._mk
        dispatch = {
            "left_click":      mk.left_click,
            "right_click":     mk.right_click,
            "middle_click":    mk.middle_click,
            "enter":           mk.enter,
            "escape":          mk.escape,
            "alt_tab":         mk.alt_tab,
            "alt_f4":          mk.alt_f4,
            "win_d":           mk.win_d,
            "win_tab":         mk.win_tab,
            "print_screen":    mk.print_screen,
            "browser_back":    mk.browser_back,
            "browser_forward": mk.browser_forward,
            "play_pause":      media.play_pause,
            "volume_up":       media.volume_up,
            "volume_down":     media.volume_down,
            "next_track":      media.next_track,
            "prev_track":      media.prev_track,
        }
        fn = dispatch.get(action)
        if fn:
            try:
                fn()
            except Exception:
                logger.exception("Action %r raised", action)
        else:
            logger.warning("Unknown action: %r", action)

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _accel(value: float, exp: float) -> float:
        """Apply acceleration curve, preserving sign."""
        if value == 0.0:
            return 0.0
        return math.copysign(abs(value) ** exp, value)

    @staticmethod
    def _norm_trigger(raw: float) -> float:
        """SDL2 trigger: −1 (released) to +1 (pressed) → 0 to 1."""
        if raw < 0:
            return (raw + 1.0) / 2.0
        return raw
