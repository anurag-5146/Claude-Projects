"""
Entry point for xbox-controller-pc.

Run:
    python main.py

Mode handlers (game_mode, desktop_mode) are wired in here once they exist.
Until then the loop runs cleanly, just without output — useful for testing
the controller reader and mode-toggle logic in isolation.
"""
import logging

from core.main_loop import MainLoop

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)

if __name__ == "__main__":
    loop = MainLoop()

    # Uncomment once modes/game_mode.py and modes/desktop_mode.py exist:
    # from modes.game_mode import GameMode
    # from modes.desktop_mode import DesktopMode
    # loop.set_game_handler(GameMode().handle)
    # loop.set_desktop_handler(DesktopMode().handle)

    loop.run()
