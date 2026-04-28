# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec for xbox-controller-pc.

Build:
    python scripts/build_exe.py          (convenience wrapper)
    # or directly:
    pyinstaller --clean --noconfirm xbox_controller.spec

Output:
    dist/XboxControllerPC.exe            (single-file, no console window)

The spec is pinned to a windowed (no-console) single-file executable with
the app icon embedded. Bundled data:
  - assets/          (controller.png, layout JSON, app_icon.ico)
  - profiles/        (all .json profiles)
"""
from pathlib import Path

SPEC_DIR = Path(SPECPATH).resolve()

datas = [
    (str(SPEC_DIR / "assets"),   "assets"),
    (str(SPEC_DIR / "profiles"), "profiles"),
]

# Submodules pystray loads dynamically based on the platform.
hiddenimports = [
    "pystray._win32",
    "PIL._tkinter_finder",
]

block_cipher = None


a = Analysis(
    ["main.py"],
    pathex=[str(SPEC_DIR)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "matplotlib", "numpy.distutils", "scipy", "pandas",
        "pytest", "IPython", "notebook",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="XboxControllerPC",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,                        # no console window (the key fix)
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(SPEC_DIR / "assets" / "app_icon.ico"),
    uac_admin=False,
)
