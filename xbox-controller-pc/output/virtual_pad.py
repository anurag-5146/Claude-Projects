"""
Virtual Xbox 360 gamepad output via vgamepad, plus physical-controller
rumble feedback via XInput (Windows only).

Both subsystems degrade gracefully when their native libraries are absent
(e.g. on a Linux dev machine or when ViGEmBus is not installed).
"""
import ctypes
import logging
import struct
import threading
from typing import Dict, Optional

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# vgamepad — virtual controller output
# ---------------------------------------------------------------------------
try:
    import vgamepad as vg

    _BUTTON_MAP: Dict[int, "vg.XUSB_BUTTON"] = {
        0:  vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
        1:  vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
        2:  vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
        3:  vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
        4:  vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
        5:  vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
        6:  vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
        7:  vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
        8:  vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
        9:  vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    }

    _HAT_TO_DPAD: Dict = {
        (0,  1): vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
        (0, -1): vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
        (-1, 0): vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
        (1,  0): vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    }

    _VG_AVAILABLE = True
except Exception as _vg_err:
    _VG_AVAILABLE = False
    logger.warning("vgamepad not available (%s) — game-mode output disabled.", _vg_err)

# ---------------------------------------------------------------------------
# XInput — physical controller rumble (Windows only)
# ---------------------------------------------------------------------------
class _XINPUT_VIBRATION(ctypes.Structure):
    _fields_ = [
        ("wLeftMotorSpeed",  ctypes.c_ushort),
        ("wRightMotorSpeed", ctypes.c_ushort),
    ]

try:
    _xinput = ctypes.windll.xinput1_4   # type: ignore[attr-defined]
    _XINPUT_AVAILABLE = True
except (OSError, AttributeError):
    try:
        _xinput = ctypes.windll.xinput1_3  # type: ignore[attr-defined]
        _XINPUT_AVAILABLE = True
    except (OSError, AttributeError):
        _XINPUT_AVAILABLE = False
        logger.debug("XInput not available — physical rumble disabled.")


def _set_xinput_rumble(controller_index: int, left: float, right: float) -> None:
    if not _XINPUT_AVAILABLE:
        return
    vib = _XINPUT_VIBRATION(
        wLeftMotorSpeed=int(max(0.0, min(1.0, left)) * 65535),
        wRightMotorSpeed=int(max(0.0, min(1.0, right)) * 65535),
    )
    try:
        _xinput.XInputSetState(controller_index, ctypes.byref(vib))
    except Exception:
        logger.exception("XInputSetState failed")


# ---------------------------------------------------------------------------
# Public class
# ---------------------------------------------------------------------------

class VirtualPad:
    """
    Mirrors a physical ControllerState onto a virtual Xbox 360 pad.
    Also exposes rumble() to vibrate the *physical* controller via XInput.
    """

    def __init__(self, controller_index: int = 0) -> None:
        self._ctrl_idx = controller_index
        self._pad: Optional[object] = None
        self._prev_hat = (0, 0)
        self._rumble_timer: Optional[threading.Timer] = None

        if _VG_AVAILABLE:
            # Delay ViGEmBus device creation so pygame's joystick subsystem
            # initialises first and the reader locks onto the physical controller.
            # Without this, SDL2 sees both devices simultaneously and may
            # connect to the virtual pad instead of the physical one.
            t = threading.Timer(2.0, self._create_pad)
            t.daemon = True
            t.start()
        else:
            logger.warning("vgamepad not installed — game-mode output disabled.")

    # ------------------------------------------------------------------
    # Deferred ViGEmBus creation
    # ------------------------------------------------------------------

    def _create_pad(self) -> None:
        try:
            self._pad = vg.VX360Gamepad()
            logger.info("Virtual Xbox 360 pad created (deferred).")
        except Exception as exc:
            logger.error("Could not create virtual pad: %s", exc)

    # ------------------------------------------------------------------
    # Passthrough
    # ------------------------------------------------------------------

    def mirror_state(self, state) -> None:
        """Copy ControllerState onto the virtual pad and flush."""
        if self._pad is None:
            return

        pad = self._pad

        # Buttons
        for py_btn, xusb_btn in _BUTTON_MAP.items():
            if state.buttons.get(py_btn, False):
                pad.press_button(xusb_btn)
            else:
                pad.release_button(xusb_btn)

        # D-pad via hat
        for hat_val, dpad_btn in _HAT_TO_DPAD.items():
            if state.hat == hat_val:
                pad.press_button(dpad_btn)
            else:
                pad.release_button(dpad_btn)

        # Left stick
        lx = state.axes.get(0, 0.0)
        ly = state.axes.get(1, 0.0)
        pad.left_joystick_float(x_value_float=lx, y_value_float=-ly)

        # Right stick
        rx = state.axes.get(2, 0.0)
        ry = state.axes.get(3, 0.0)
        pad.right_joystick_float(x_value_float=rx, y_value_float=-ry)

        # Triggers (SDL2 returns −1 released → +1 pressed; vgamepad wants 0–1)
        lt_raw = state.axes.get(4, -1.0)
        rt_raw = state.axes.get(5, -1.0)
        pad.left_trigger_float(value_float=self._norm_trigger(lt_raw))
        pad.right_trigger_float(value_float=self._norm_trigger(rt_raw))

        pad.update()

    # ------------------------------------------------------------------
    # Rumble (physical controller)
    # ------------------------------------------------------------------

    def rumble(self, left: float = 0.5, right: float = 0.3,
               duration: float = 0.12) -> None:
        """Vibrate the physical controller for `duration` seconds."""
        if self._rumble_timer is not None:
            self._rumble_timer.cancel()
        _set_xinput_rumble(self._ctrl_idx, left, right)
        self._rumble_timer = threading.Timer(duration, self._stop_rumble)
        self._rumble_timer.daemon = True
        self._rumble_timer.start()

    def _stop_rumble(self) -> None:
        _set_xinput_rumble(self._ctrl_idx, 0.0, 0.0)
        self._rumble_timer = None

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _norm_trigger(raw: float) -> float:
        """Convert SDL2 trigger axis (−1 to +1 or 0 to +1) → 0 to 1."""
        if raw < 0:
            return (raw + 1.0) / 2.0
        return raw

    def shutdown(self) -> None:
        if self._rumble_timer:
            self._rumble_timer.cancel()
        _set_xinput_rumble(self._ctrl_idx, 0.0, 0.0)
