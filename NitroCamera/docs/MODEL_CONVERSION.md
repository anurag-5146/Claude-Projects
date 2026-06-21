# Model Conversion Guide

This document describes how to regenerate the two TFLite models NitroCamera's neural post-processor uses:

- `app/src/main/assets/nafnet.tflite` — learned denoiser
- `app/src/main/assets/realesrgan_x2.tflite` — learned 2× super-resolver

Both are loaded by `PostProcessor.kt` and run on every captured photo. If either asset is missing, the pipeline gracefully degrades (pass-through for denoise, bicubic for upscale).

---

## Prerequisites

```bash
pip install torch onnx onnx2tf tensorflow
```

Tested on Python 3.10 + torch 2.1.

---

## 1. NAFNet (denoise)

**Source:** https://github.com/megvii-research/NAFNet (Apache-2.0)

**Recommended weights:** `NAFNet-SIDD-width32.pth` (SIDD dataset, width 32 = mobile-friendly, ~7 MB after conversion)

Download from the repo's model zoo:
https://github.com/megvii-research/NAFNet/blob/main/docs/ReproduceResults.md

### Steps

```bash
git clone https://github.com/megvii-research/NAFNet.git
cd NAFNet
pip install -e .

# Export PyTorch → ONNX
python -c "
import torch
from basicsr.models.archs.NAFNet_arch import NAFNet
net = NAFNet(img_channel=3, width=32, middle_blk_num=1,
             enc_blk_nums=[1,1,1,28], dec_blk_nums=[1,1,1,1])
net.load_state_dict(torch.load('experiments/pretrained_models/NAFNet-SIDD-width32.pth')['params'])
net.eval()
dummy = torch.randn(1, 3, 256, 256)
torch.onnx.export(net, dummy, 'nafnet.onnx',
                  input_names=['input'], output_names=['output'],
                  opset_version=13, dynamic_axes=None)
"

# ONNX → TFLite (NHWC layout for Android)
onnx2tf -i nafnet.onnx -o nafnet_tflite/ -b 1 -kat input

# The output file lives at nafnet_tflite/nafnet_float32.tflite
cp nafnet_tflite/nafnet_float32.tflite ../NitroCamera/app/src/main/assets/nafnet.tflite
```

---

## 2. Real-ESRGAN x2 (super-resolution)

**Source:** https://github.com/xinntao/Real-ESRGAN (BSD-3)

**Recommended weights:** `RealESRGAN_x2plus.pth`

Download from:
https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.1/RealESRGAN_x2plus.pth

### Steps

```bash
git clone https://github.com/xinntao/Real-ESRGAN.git
cd Real-ESRGAN
pip install -r requirements.txt
python setup.py develop

# Export PyTorch → ONNX
python -c "
import torch
from basicsr.archs.rrdbnet_arch import RRDBNet
net = RRDBNet(num_in_ch=3, num_out_ch=3, num_feat=64, num_block=23, num_grow_ch=32, scale=2)
ckpt = torch.load('weights/RealESRGAN_x2plus.pth', map_location='cpu')
net.load_state_dict(ckpt['params_ema'] if 'params_ema' in ckpt else ckpt['params'])
net.eval()
dummy = torch.randn(1, 3, 256, 256)
torch.onnx.export(net, dummy, 'realesrgan_x2.onnx',
                  input_names=['input'], output_names=['output'],
                  opset_version=13)
"

# ONNX → TFLite
onnx2tf -i realesrgan_x2.onnx -o realesrgan_tflite/ -b 1 -kat input

cp realesrgan_tflite/realesrgan_x2_float32.tflite ../NitroCamera/app/src/main/assets/realesrgan_x2.tflite
```

---

## Verification

After placing the files, rebuild the app and capture a photo. In `adb logcat`:

```
NAFNetProcessor: NAFNet interpreter ready
NAFNetProcessor: NAFNet running on GPU
SuperResProcessor: ESRGAN interpreter ready
PostProcessor: Denoise 800 ms
PostProcessor: Super-res 1500 ms
```

If the model file is missing or malformed, you'll see:

```
NAFNetProcessor: NAFNet model not found — denoise disabled
SuperResProcessor: ESRGAN model not found — bicubic fallback active
```

The capture pipeline still works; it just skips the learned step.

---

## Optional: int8 quantization

Both models can be quantized to int8 for ~3× smaller size and ~2× faster inference, at the cost of slight quality loss. Append `-oiqt` to the `onnx2tf` command:

```bash
onnx2tf -i nafnet.onnx -o nafnet_int8/ -b 1 -oiqt
```

Requires a calibration dataset of ~100 representative camera photos. Place them in `calibration_images/` before running. Not yet wired into NitroCamera — float32 is the baseline.

---

## Licensing

- NAFNet: Apache-2.0 — OK to redistribute in the APK
- Real-ESRGAN: BSD-3-Clause — OK to redistribute, include attribution in app's About page when we build one
