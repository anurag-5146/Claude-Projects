"""
Game mode: transparent passthrough from physical controller → virtual pad.
Nothing else; the game reads the virtual pad as if it were a real controller.
"""
import logging

from controller.reader import ControllerState
from output.virtual_pad import VirtualPad

logger = logging.getLogger(__name__)


class GameMode:
    def __init__(self, virtual_pad: VirtualPad) -> None:
        self._pad = virtual_pad

    def handle(self, state: ControllerState) -> None:
        self._pad.mirror_state(state)
