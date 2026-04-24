"""
Generate a clean top-down Xbox controller render via Replicate (FLUX),
save it to ../assets/controller.png, and write a matching layout JSON
with pixel-space regions used by the dashboard's Live tab.

Usage:
    python scripts/generate_controller_art.py

Reads REPLICATE_API_TOKEN from:
    1. the current environment
    2. E:\\Claude\\history-pipeline\\.env   (fallback)

Falls back to FAL_KEY + the fal.ai FLUX endpoint if Replicate is absent.
"""
from __future__ import annotations
import json
import os
import sys
import time
from pathlib import Path
from typing import Optional
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "assets"
ASSETS.mkdir(exist_ok=True)

PROMPT = (
    "Top-down photograph of a modern Xbox Series X wireless controller, "
    "matte black finish with white accents, centered, symmetric, both "
    "thumbsticks aligned, crisp macro detail on buttons and d-pad, "
    "pure white studio background, soft even lighting, no hands, "
    "no shadows, commercial product photography, 4k, ultra sharp"
)
NEGATIVE = "people, hands, text, logos, shadows, tilted, perspective, blurry"

WIDTH, HEIGHT = 1024, 640


def load_env_fallback() -> None:
    env_path = Path(r"E:\Claude\history-pipeline\.env")
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        os.environ.setdefault(k, v)


def _http_json(url: str, method: str, headers: dict, body: Optional[dict] = None,
               timeout: int = 60) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = Request(url, data=data, method=method, headers=headers)
    with urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def generate_via_replicate(token: str) -> Optional[bytes]:
    """Use Replicate FLUX-schnell. Returns PNG bytes or None."""
    print("[replicate] submitting…")
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Prefer": "wait",
    }
    # black-forest-labs/flux-schnell — fast, free tier.
    body = {
        "input": {
            "prompt": PROMPT,
            "aspect_ratio": "16:9",
        }
    }
    url = "https://api.replicate.com/v1/models/black-forest-labs/flux-schnell/predictions"
    try:
        pred = _http_json(url, "POST", headers, body, timeout=120)
    except Exception as e:
        print(f"[replicate] submit failed: {e}")
        return None

    # If 'Prefer: wait' didn't return a completed prediction, poll.
    status = pred.get("status")
    for _ in range(60):
        if status in ("succeeded", "failed", "canceled"):
            break
        time.sleep(2)
        try:
            pred = _http_json(pred["urls"]["get"], "GET", headers, timeout=30)
            status = pred.get("status")
        except Exception as e:
            print(f"[replicate] poll failed: {e}")
            return None

    if status != "succeeded":
        print(f"[replicate] ended status={status}  err={pred.get('error')}")
        return None

    out = pred.get("output")
    if isinstance(out, list):
        out = out[0] if out else None
    if not out:
        print("[replicate] no output URL")
        return None

    print(f"[replicate] fetching {out}")
    with urlopen(out, timeout=120) as resp:
        return resp.read()


def generate_via_fal(key: str) -> Optional[bytes]:
    print("[fal] submitting…")
    headers = {
        "Authorization": f"Key {key}",
        "Content-Type": "application/json",
    }
    body = {
        "prompt": PROMPT,
        "image_size": {"width": WIDTH, "height": HEIGHT},
        "num_inference_steps": 4,
        "num_images": 1,
        "enable_safety_checker": False,
    }
    try:
        resp = _http_json("https://fal.run/fal-ai/flux/schnell",
                          "POST", headers, body, timeout=120)
    except Exception as e:
        print(f"[fal] submit failed: {e}")
        return None

    imgs = resp.get("images") or []
    if not imgs:
        print(f"[fal] no images in response: {resp}")
        return None
    img_url = imgs[0].get("url")
    if not img_url:
        return None

    print(f"[fal] fetching {img_url}")
    with urlopen(img_url, timeout=120) as r:
        return r.read()


def write_layout_hint(image_size: tuple) -> None:
    """Write a layout JSON the Live tab uses to place overlays on the image.

    Coordinates are normalized (0-1) so they scale with the canvas.
    These values assume the prompt's top-down symmetric composition; users
    can fine-tune this JSON without regenerating the image.
    """
    layout = {
        "image_size": {"w": image_size[0], "h": image_size[1]},
        # Normalized centers (x, y) and a radius "r" (also normalized by width).
        "face_buttons": {
            "A": {"x": 0.735, "y": 0.55, "r": 0.022},
            "B": {"x": 0.775, "y": 0.49, "r": 0.022},
            "X": {"x": 0.695, "y": 0.49, "r": 0.022},
            "Y": {"x": 0.735, "y": 0.43, "r": 0.022},
        },
        "shoulders": {
            "LB": {"x": 0.28, "y": 0.22, "w": 0.09, "h": 0.03},
            "RB": {"x": 0.72, "y": 0.22, "w": 0.09, "h": 0.03},
        },
        "triggers": {
            "LT": {"x": 0.28, "y": 0.14, "w": 0.09, "h": 0.05},
            "RT": {"x": 0.72, "y": 0.14, "w": 0.09, "h": 0.05},
        },
        "center": {
            "BACK":  {"x": 0.44, "y": 0.45, "r": 0.015},
            "START": {"x": 0.56, "y": 0.45, "r": 0.015},
            "GUIDE": {"x": 0.50, "y": 0.32, "r": 0.022},
        },
        "sticks": {
            "L": {"x": 0.305, "y": 0.50, "r": 0.055},
            "R": {"x": 0.595, "y": 0.63, "r": 0.055},
        },
        "dpad": {"x": 0.405, "y": 0.63, "r": 0.040},
    }
    (ASSETS / "controller_layout.json").write_text(
        json.dumps(layout, indent=2), encoding="utf-8")
    print("[ok] wrote assets/controller_layout.json")


def generate_via_pollinations() -> Optional[bytes]:
    """Free, no-auth image gen via pollinations.ai (rate-limited)."""
    from urllib.parse import quote
    url = (
        f"https://image.pollinations.ai/prompt/{quote(PROMPT)}"
        f"?width={WIDTH}&height={HEIGHT}&nologo=true&model=flux"
    )
    print(f"[pollinations] fetching {url[:100]}…")
    try:
        with urlopen(url, timeout=120) as r:
            return r.read()
    except Exception as e:
        print(f"[pollinations] failed: {e}")
        return None


def main() -> int:
    load_env_fallback()
    image_bytes: Optional[bytes] = None

    repl = os.environ.get("REPLICATE_API_TOKEN")
    fal  = os.environ.get("FAL_KEY")

    if repl:
        image_bytes = generate_via_replicate(repl)
    if image_bytes is None and fal:
        image_bytes = generate_via_fal(fal)
    if image_bytes is None:
        image_bytes = generate_via_pollinations()

    if image_bytes is None:
        print("ERROR: no image generated (all providers failed).")
        return 1

    out_path = ASSETS / "controller.png"
    out_path.write_bytes(image_bytes)
    print(f"[ok] wrote {out_path}  ({len(image_bytes):,} bytes)")

    # Try to read actual image size for the layout file
    try:
        from PIL import Image  # noqa
        with Image.open(out_path) as im:
            size = im.size
    except Exception:
        size = (WIDTH, HEIGHT)
    write_layout_hint(size)
    return 0


if __name__ == "__main__":
    sys.exit(main())
