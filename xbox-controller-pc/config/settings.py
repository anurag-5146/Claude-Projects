"""
Central configuration for xbox-controller-pc.
All tunable constants live here so no magic numbers are scattered through the code.
"""
from typing import Dict, Tuple


# ---------------------------------------------------------------------------
# Xbox controller button indices (pygame / SDL2 mapping for Xbox One/Series)
# ---------------------------------------------------------------------------
BUTTON_A      = 0
BUTTON_B      = 1
BUTTON_X      = 2
BUTTON_Y      = 3
BUTTON_LB     = 4
BUTTON_RB     = 5
BUTTON_BACK   = 6   # "View" button
BUTTON_START  = 7   # "Menu" button
BUTTON_LSTICK = 8   # Left stick click
BUTTON_RSTICK = 9   # Right stick click
# The Guide (Xbox logo) button is button 10 on most SDL2 builds.
# If it doesn't register, swap BUTTON_GUIDE for BUTTON_BACK or BUTTON_START.
BUTTON_GUIDE  = 10

# ---------------------------------------------------------------------------
# Axis indices (Xbox One/Series via SDL2 on Windows)
# ---------------------------------------------------------------------------
AXIS_LX = 0   # Left  stick X   (−1 = left,   +1 = right)
AXIS_LY = 1   # Left  stick Y   (−1 = up,     +1 = down)
AXIS_RX = 2   # Right stick X
AXIS_RY = 3   # Right stick Y
AXIS_LT = 4   # Left  trigger   (−1 = released, +1 = fully pressed in SDL2)
AXIS_RT = 5   # Right trigger

# D-pad hat index
HAT_DPAD = 0

# ---------------------------------------------------------------------------
# Mode toggle combo
# Hold ALL buttons in this tuple for COMBO_HOLD_SECONDS to switch modes.
# Default: Guide + RB
# ---------------------------------------------------------------------------
MODE_TOGGLE_COMBO: Tuple[int, ...] = (BUTTON_GUIDE, BUTTON_RB)
COMBO_HOLD_SECONDS: float = 0.5

# ---------------------------------------------------------------------------
# Dead-zones
# ---------------------------------------------------------------------------
DEADZONE: float = 0.15          # Stick dead-zone (fraction of full range)
TRIGGER_DEADZONE: float = 0.05  # Trigger dead-zone

# ---------------------------------------------------------------------------
# Desktop-mode mouse / scroll settings
# ---------------------------------------------------------------------------
MOUSE_SENSITIVITY: float = 35.0     # px per tick at full stick deflection
MOUSE_ACCEL_EXPONENT: float = 1.6   # >1 = slow small, fast large movements
# Scroll events per second at full left-stick deflection (non-linear via exponent)
SCROLL_SENSITIVITY: float = 12.0
# Fraction of trigger travel required to fire a trigger action
TRIGGER_THRESHOLD: float = 0.5

# ---------------------------------------------------------------------------
# Desktop-mode default button → action mapping
# Actions used here must be handled in DesktopMode._do_action().
# These are the baseline defaults; profile JSONs may override per-button.
# ---------------------------------------------------------------------------
BUTTON_ACTIONS: Dict[int, str] = {
    BUTTON_A:      "left_click",
    BUTTON_B:      "right_click",
    BUTTON_X:      "enter",
    BUTTON_Y:      "escape",
    BUTTON_START:  "play_pause",
    BUTTON_LSTICK: "middle_click",
}

# LB (held) + button → Windows shortcut action
LB_SHORTCUTS: Dict[int, str] = {
    BUTTON_A:     "alt_tab",
    BUTTON_B:     "alt_f4",
    BUTTON_X:     "win_d",
    BUTTON_Y:     "print_screen",
    BUTTON_START: "win_tab",
    BUTTON_BACK:  "win_tab",
}

# D-pad directions → action  (hat values from pygame)
DPAD_ACTIONS: Dict[Tuple[int, int], str] = {
    (0,  1): "volume_up",
    (0, -1): "volume_down",
    (-1, 0): "prev_track",
    (1,  0): "next_track",
}

# Trigger actions when pulled past TRIGGER_THRESHOLD
LT_ACTION: str = "browser_back"
RT_ACTION: str = "browser_forward"

# ---------------------------------------------------------------------------
# Rumble feedback on mode switch (physical controller via XInput)
# ---------------------------------------------------------------------------
RUMBLE_ON_SWITCH: bool = True
RUMBLE_LEFT_MOTOR: float = 0.5    # 0.0 – 1.0
RUMBLE_RIGHT_MOTOR: float = 0.3
RUMBLE_DURATION: float = 0.12     # seconds

# ---------------------------------------------------------------------------
# Loop timing
# ---------------------------------------------------------------------------
TICK_RATE: int = 60   # ticks per second for the main loop

# ---------------------------------------------------------------------------
# Reconnect behaviour
# ---------------------------------------------------------------------------
RECONNECT_POLL_INTERVAL: float = 2.0   # seconds between reconnect attempts


# ---------------------------------------------------------------------------
# Mode constants
# ---------------------------------------------------------------------------
class Mode:
    GAME    = "game"
    DESKTOP = "desktop"


DEFAULT_MODE: str = Mode.DESKTOP
