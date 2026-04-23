"""
Entry point for xbox-controller-pc.

Threading model
---------------
  Main thread  : OSD overlay (tkinter mainloop — Windows requires this)
  Daemon thread: Controller loop (MainLoop.run)
  Daemon thread: Tray icon (pystray, started by TrayApp.start)

Run:
    python main.py
"""
import ctypes
import logging
import threading

from config.settings import Mode, RUMBLE_DURATION, RUMBLE_LEFT_MOTOR, RUMBLE_ON_SWITCH, RUMBLE_RIGHT_MOTOR
from core.main_loop import MainLoop
from modes.desktop_mode import DesktopMode
from modes.game_mode import GameMode
from output.mouse_keyboard import MouseKeyboardOutput
from output.virtual_pad import VirtualPad
from profiles.profiles import ProfileManager
from tray.tray_app import TrayApp
from ui.osd_overlay import OSDOverlay

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Window minimize / restore helpers (Windows only, degrade gracefully)
# ---------------------------------------------------------------------------
try:
    _user32 = ctypes.windll.user32  # type: ignore[attr-defined]
    _SW_MINIMIZE = 6
    _SW_RESTORE  = 9

    def _get_foreground_hwnd() -> int:
        return _user32.GetForegroundWindow()

    def _is_iconic(hwnd: int) -> bool:
        return bool(hwnd and _user32.IsIconic(hwnd))

    def _minimize_hwnd(hwnd: int) -> None:
        if hwnd:
            _user32.ShowWindow(hwnd, _SW_MINIMIZE)

    def _restore_hwnd(hwnd: int) -> None:
        if hwnd:
            _user32.ShowWindow(hwnd, _SW_RESTORE)
            try:
                _user32.SetForegroundWindow(hwnd)
            except Exception:
                pass

except (OSError, AttributeError):
    def _get_foreground_hwnd() -> int: return 0          # type: ignore[misc]
    def _is_iconic(hwnd: int) -> bool: return False      # type: ignore[misc]
    def _minimize_hwnd(hwnd: int) -> None: pass          # type: ignore[misc]
    def _restore_hwnd(hwnd: int) -> None: pass           # type: ignore[misc]


def main() -> None:
    # ------------------------------------------------------------------
    # Instantiate subsystems
    # ------------------------------------------------------------------
    virtual_pad  = VirtualPad(controller_index=0)
    mouse_kb     = MouseKeyboardOutput()
    profiles     = ProfileManager()
    loop         = MainLoop()
    osd          = OSDOverlay()

    # Desktop mode receives the active profile dict each tick
    desktop = DesktopMode(mouse_kb, profile_fn=profiles.current)
    game    = GameMode(virtual_pad)

    loop.set_game_handler(game.handle)
    loop.set_desktop_handler(desktop.handle)

    # ------------------------------------------------------------------
    # Mode-switch side-effects: rumble + OSD + tray + window management
    # ------------------------------------------------------------------
    _game_hwnd: int = 0

    def on_mode_switch(new_mode: str) -> None:
        nonlocal _game_hwnd

        if new_mode == Mode.DESKTOP:
            # Release all virtual-pad inputs so the game doesn't see stuck buttons.
            virtual_pad.release_all()
            # If game window is still visible, save and minimize it.
            # (If already minimized — e.g. watcher-triggered — just save the hwnd.)
            fg = _get_foreground_hwnd()
            if fg:
                _game_hwnd = fg
            if _game_hwnd and not _is_iconic(_game_hwnd):
                logger.info("Minimising game window (hwnd=%d).", _game_hwnd)
                _minimize_hwnd(_game_hwnd)

        elif new_mode == Mode.GAME:
            # Restore the game window.
            if _game_hwnd:
                logger.info("Restoring game window (hwnd=%d).", _game_hwnd)
                _restore_hwnd(_game_hwnd)

        if RUMBLE_ON_SWITCH:
            virtual_pad.rumble(RUMBLE_LEFT_MOTOR, RUMBLE_RIGHT_MOTOR, RUMBLE_DURATION)
        osd.show(new_mode)
        tray.update_mode(new_mode)

    loop.add_switch_listener(on_mode_switch)

    # ------------------------------------------------------------------
    # Tray icon
    # ------------------------------------------------------------------
    def _force_switch() -> None:
        """Called from tray menu — toggles mode programmatically."""
        loop.mode_manager._switch()          # direct call into ModeManager

    tray = TrayApp(
        get_mode_fn    = lambda: loop.mode_manager.current_mode,
        switch_mode_fn = _force_switch,
        quit_fn        = lambda: (loop.stop(), osd.destroy()),
    )
    tray.start()

    # ------------------------------------------------------------------
    # Window watcher — auto-switch mode when game is minimized/restored
    # ------------------------------------------------------------------
    def _window_watcher() -> None:
        import time
        nonlocal _game_hwnd

        # If we start in game mode, learn the current foreground window.
        time.sleep(1.5)
        if loop.mode_manager.is_game_mode() and not _game_hwnd:
            _game_hwnd = _get_foreground_hwnd()
            if _game_hwnd:
                logger.info("Game window captured at startup (hwnd=%d).", _game_hwnd)

        while True:
            time.sleep(0.5)
            if not _game_hwnd:
                continue

            if loop.mode_manager.is_game_mode():
                # User minimized the game (Alt+Tab, Win key, etc.) → go desktop
                if _is_iconic(_game_hwnd):
                    logger.info("Game minimized externally — switching to DESKTOP.")
                    loop.mode_manager._switch()

            elif loop.mode_manager.is_desktop_mode():
                # User clicked the game in the taskbar / restored it → go game
                fg = _get_foreground_hwnd()
                if fg == _game_hwnd and not _is_iconic(_game_hwnd):
                    logger.info("Game restored externally — switching to GAME.")
                    loop.mode_manager._switch()

    threading.Thread(target=_window_watcher, daemon=True,
                     name="window-watcher").start()

    # ------------------------------------------------------------------
    # Profile auto-detection — poll every tick-ish via a side thread
    # ------------------------------------------------------------------
    def _profile_watcher() -> None:
        import time
        while True:
            profiles.update()
            time.sleep(1.0)

    threading.Thread(target=_profile_watcher, daemon=True,
                     name="profile-watcher").start()

    # ------------------------------------------------------------------
    # Controller loop on a daemon thread
    # ------------------------------------------------------------------
    ctrl_thread = threading.Thread(target=loop.run, daemon=True, name="ctrl-loop")
    ctrl_thread.start()

    # ------------------------------------------------------------------
    # OSD on main thread (blocks until quit)
    # ------------------------------------------------------------------
    logger.info("OSD ready.  Use Guide+RB (hold 0.5 s) to toggle modes.")
    try:
        osd.run()                   # blocks until destroy() is called
    except KeyboardInterrupt:
        pass
    finally:
        loop.stop()
        virtual_pad.shutdown()
        tray.stop()
        logger.info("Exited.")


if __name__ == "__main__":
    main()
