"""
Generate assets/app_icon.ico — the Windows app icon used by:
  - the PyInstaller-built .exe
  - the tkinter Toplevel windows (OSD, Dashboard)
  - the Windows taskbar

Renders a rounded controller silhouette in the app's accent blue over a
dark background at multiple sizes and packs them into a single .ico file.

Run:
    python scripts/make_icon.py
"""
from __future__ import annotations
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "assets"
ASSETS.mkdir(exist_ok=True)

BG = (18, 20, 28, 255)          # #12141c
ACCENT = (74, 158, 255, 255)    # #4a9eff
WHITE = (240, 245, 255, 255)


def _draw(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Rounded-square background
    pad = max(1, size // 16)
    radius = size // 5
    d.rounded_rectangle([pad, pad, size - pad, size - pad],
                        radius=radius, fill=BG)

    # Stylised gamepad body: two overlapping ovals + centre rectangle
    cx, cy = size // 2, size // 2
    body_w = int(size * 0.72)
    body_h = int(size * 0.42)
    left  = (cx - body_w // 2, cy - body_h // 2)
    right = (cx + body_w // 2, cy + body_h // 2)
    d.rounded_rectangle([left[0], left[1], right[0], right[1]],
                        radius=body_h // 2, fill=ACCENT)

    # Two "stick" dots
    r = max(2, size // 16)
    lx, rx = cx - body_w // 4, cx + body_w // 4
    d.ellipse([lx - r, cy - r, lx + r, cy + r], fill=BG)
    d.ellipse([rx - r, cy - r, rx + r, cy + r], fill=BG)

    # Face-button dot cluster (tiny diamond of 4 dots on the right)
    dr = max(1, size // 28)
    fx, fy = int(cx + body_w * 0.30), cy
    off = max(2, size // 14)
    for dx, dy in ((0, -off), (off, 0), (0, off), (-off, 0)):
        d.ellipse([fx + dx - dr, fy + dy - dr, fx + dx + dr, fy + dy + dr],
                  fill=WHITE)

    return img


def main() -> int:
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    master = _draw(256)
    out = ASSETS / "app_icon.ico"
    # Pillow resizes the master image to each requested size internally.
    master.save(out, format="ICO", sizes=sizes)
    master.save(ASSETS / "app_icon.png", "PNG")
    print(f"[ok] wrote {out}  ({out.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
