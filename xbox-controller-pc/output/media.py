"""
Media key output via pynput.
All methods are fire-and-forget single key taps.
"""
import logging

from pynput.keyboard import Controller, Key

logger = logging.getLogger(__name__)

_kb = Controller()


def _tap(key: Key) -> None:
    try:
        _kb.tap(key)
    except Exception:
        logger.exception("Media key tap failed: %s", key)


def play_pause() -> None:
    _tap(Key.media_play_pause)


def volume_up() -> None:
    _tap(Key.media_volume_up)


def volume_down() -> None:
    _tap(Key.media_volume_down)


def next_track() -> None:
    _tap(Key.media_next)


def prev_track() -> None:
    _tap(Key.media_previous)
