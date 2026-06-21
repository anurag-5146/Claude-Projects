"""
One-shot converter for NitroCamera neural models.

Downloads PyTorch weights for NAFNet + Real-ESRGAN x2, exports to ONNX,
then to TFLite. Drops the two resulting .tflite files into app/src/main/assets/.

Usage:
    pip install torch onnx onnx2tf tensorflow requests
    python scripts/convert_models.py

Output:
    app/src/main/assets/nafnet.tflite         (~7 MB)
    app/src/main/assets/realesrgan_x2.tflite  (~17 MB)

Runtime: ~5-10 minutes on a decent laptop (no GPU required).
"""

import os
import sys
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
WORK = ROOT / "scripts" / "_work"
ASSETS.mkdir(parents=True, exist_ok=True)
WORK.mkdir(parents=True, exist_ok=True)

NAFNET_URL = "https://github.com/megvii-research/NAFNet/releases/download/v1.0/NAFNet-SIDD-width32.pth"
ESRGAN_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.1/RealESRGAN_x2plus.pth"


def download(url: str, dest: Path):
    if dest.exists():
        print(f"  [skip] {dest.name} already present")
        return
    print(f"  downloading {url}")
    urllib.request.urlretrieve(url, dest)
    print(f"  -> {dest} ({dest.stat().st_size / 1e6:.1f} MB)")


def run(cmd: list[str]):
    print(f"  $ {' '.join(cmd)}")
    subprocess.run(cmd, check=True)


def convert_nafnet():
    import torch
    from basicsr.models.archs.NAFNet_arch import NAFNet  # requires NAFNet repo on PYTHONPATH

    weights = WORK / "NAFNet-SIDD-width32.pth"
    download(NAFNET_URL, weights)

    onnx_path = WORK / "nafnet.onnx"
    print("  exporting NAFNet -> ONNX")
    net = NAFNet(
        img_channel=3, width=32, middle_blk_num=1,
        enc_blk_nums=[1, 1, 1, 28], dec_blk_nums=[1, 1, 1, 1],
    )
    ckpt = torch.load(weights, map_location="cpu")
    net.load_state_dict(ckpt["params"])
    net.eval()
    dummy = torch.randn(1, 3, 256, 256)
    torch.onnx.export(
        net, dummy, onnx_path,
        input_names=["input"], output_names=["output"],
        opset_version=13,
    )

    print("  ONNX -> TFLite")
    out = WORK / "nafnet_tflite"
    run(["onnx2tf", "-i", str(onnx_path), "-o", str(out), "-b", "1", "-kat", "input"])

    tflite = next(out.glob("*float32*.tflite"))
    dest = ASSETS / "nafnet.tflite"
    dest.write_bytes(tflite.read_bytes())
    print(f"  -> {dest} ({dest.stat().st_size / 1e6:.1f} MB)")


def convert_esrgan():
    import torch
    from basicsr.archs.rrdbnet_arch import RRDBNet

    weights = WORK / "RealESRGAN_x2plus.pth"
    download(ESRGAN_URL, weights)

    onnx_path = WORK / "realesrgan_x2.onnx"
    print("  exporting Real-ESRGAN x2 -> ONNX")
    net = RRDBNet(
        num_in_ch=3, num_out_ch=3, num_feat=64,
        num_block=23, num_grow_ch=32, scale=2,
    )
    ckpt = torch.load(weights, map_location="cpu")
    net.load_state_dict(ckpt.get("params_ema", ckpt.get("params", ckpt)))
    net.eval()
    dummy = torch.randn(1, 3, 256, 256)
    torch.onnx.export(
        net, dummy, onnx_path,
        input_names=["input"], output_names=["output"],
        opset_version=13,
    )

    print("  ONNX -> TFLite")
    out = WORK / "realesrgan_tflite"
    run(["onnx2tf", "-i", str(onnx_path), "-o", str(out), "-b", "1", "-kat", "input"])

    tflite = next(out.glob("*float32*.tflite"))
    dest = ASSETS / "realesrgan_x2.tflite"
    dest.write_bytes(tflite.read_bytes())
    print(f"  -> {dest} ({dest.stat().st_size / 1e6:.1f} MB)")


def main():
    print("=== NAFNet ===")
    convert_nafnet()
    print("=== Real-ESRGAN x2 ===")
    convert_esrgan()
    print("\nDone. Rebuild the app in Android Studio to bundle the models.")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"\nERROR: {e}", file=sys.stderr)
        print("See docs/MODEL_CONVERSION.md for manual steps.", file=sys.stderr)
        sys.exit(1)
