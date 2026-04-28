"""
One-shot build script: regenerates the app icon, ensures PyInstaller is
installed, and builds `dist/XboxControllerPC.exe` (single-file, windowed).

Run (from project root):
    python scripts/build_exe.py

Requirements:
    pip install -r requirements.txt
    pip install pyinstaller
"""
from __future__ import annotations
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "xbox_controller.spec"
DIST = ROOT / "dist"
BUILD = ROOT / "build"


def _ensure_pyinstaller() -> None:
    try:
        import PyInstaller  # noqa: F401
        return
    except ImportError:
        pass
    print("[build] PyInstaller not found — installing…")
    subprocess.check_call(
        [sys.executable, "-m", "pip", "install", "pyinstaller"])


def _regen_icon() -> None:
    print("[build] regenerating app icon…")
    subprocess.check_call(
        [sys.executable, str(ROOT / "scripts" / "make_icon.py")])


def _clean() -> None:
    for d in (DIST, BUILD):
        if d.exists():
            print(f"[build] removing {d}")
            shutil.rmtree(d, ignore_errors=True)


def main() -> int:
    _ensure_pyinstaller()
    _regen_icon()
    _clean()
    print(f"[build] pyinstaller → {SPEC.name}")
    rc = subprocess.call(
        [sys.executable, "-m", "PyInstaller",
         "--clean", "--noconfirm", str(SPEC)],
        cwd=str(ROOT),
    )
    if rc != 0:
        print(f"[build] FAILED (exit {rc})")
        return rc
    exe = DIST / "XboxControllerPC.exe"
    if not exe.exists():
        print(f"[build] expected {exe} not found")
        return 1
    print(f"[build] OK → {exe}  ({exe.stat().st_size / 1_048_576:.1f} MB)")
    print("[build] Double-click the .exe to launch without a console window.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
