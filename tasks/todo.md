# Project: DSLR-Level Android Camera App

## Goal
Build a smooth, fast, nifty Android camera app that achieves Pixel/Apple-level photo quality through computational photography and on-device ML — without needing dedicated ISP silicon.

---

## Realistic Expectations (Honest Assessment)

| What we CAN achieve | What we CANNOT fully replicate |
|---|---|
| HDR+ style burst merging | Apple's Photonic Engine (ISP-level) |
| Night mode frame stacking | Deep Fusion (needs ISP intermediate data) |
| ML portrait bokeh | Pixel's dual-pixel hardware AF advantage |
| Super resolution zoom | Google's proprietary GCam ML models |
| DSLR manual controls | Dedicated camera chip (e.g., Sony Exmor pipeline) |
| Color science tuning | 10+ years of Apple/Google perceptual tuning |
| RAW DNG capture + processing | — |
| Real-time noise reduction | — |
| **~85% of Pixel/Apple quality** | The last 15% needs hardware |

---

## Tech Stack Decision

```
Kotlin (primary language)
    + C++/JNI (performance-critical image math)
    + Camera2 API (raw sensor control — NOT CameraX)
    + OpenCV Android (HDR merge, alignment, sharpening)
    + TensorFlow Lite + GPU delegate (ML models)
    + Vulkan Compute Shaders (real-time preview pipeline)
    + Jetpack Compose (UI — smooth, modern)
    + MVVM + Clean Architecture
```

**Why Camera2 over CameraX?**
- CameraX abstracts away multi-frame capture needed for HDR/Night mode
- Camera2 gives direct access to: RAW streams, per-frame settings, sensor timestamps, burst sequences

---

## Phase 1 — Foundation (Weeks 1-3)
**Goal: Fast, stable camera with manual controls and RAW capture**

- [ ] Project setup: Kotlin + Jetpack Compose + Camera2 + OpenCV
- [ ] Camera2 preview pipeline (TextureView/SurfaceView → GPU path)
- [ ] Manual controls: ISO (50–6400), shutter (1/8000s–30s), WB presets, manual focus
- [ ] RAW DNG capture + JPEG capture (simultaneous)
- [ ] Measure and display: shutter lag (ms), autofocus time (ms)
- [ ] Basic DSLR-style UI: histogram, focus peaking overlay, exposure meter
- [ ] Zero-shutter-lag (ZSL) implementation using ring buffer

**Deliverable:** App that shoots faster and gives more control than stock camera

---

## Phase 2 — Computational Photography Core (Weeks 4-9)
**Goal: HDR and Night mode — the two biggest quality wins**

### 2A. HDR+ Style Burst Capture
- [ ] Capture 5-9 bracketed exposures in burst (±2EV)
- [ ] Frame alignment using Lucas-Kanade optical flow (OpenCV)
- [ ] Merge using Mertens exposure fusion or Debevec HDR algorithm
- [ ] Tone map result: Reinhard or ACES filmic curve
- [ ] Benchmark: merge time < 800ms on mid-range device

### 2B. Night Mode (Frame Stacking)
- [ ] Capture 12-20 long-exposure frames (auto ISO, auto shutter)
- [ ] Align frames: ORB feature matching + homography (OpenCV)
- [ ] Temporal average stack with outlier rejection (removes motion/noise)
- [ ] Adaptive sharpening post-stack
- [ ] Benchmark: total processing < 3s

### 2C. Noise Reduction Pipeline
- [ ] BM3D or DnCNN TFLite model for single-frame NR
- [ ] Apply in RAW domain before demosaicing for best results
- [ ] Real-time NR on preview stream (lite version)

**Deliverable:** Night shots and HDR that rival stock Google Camera on same hardware

---

## Phase 3 — ML Enhancement Layer (Weeks 10-16)
**Goal: Add the "magic" — portrait, super-res, scene intelligence**

### 3A. Portrait Mode / Bokeh
- [ ] MediaPipe Selfie Segmentation (real-time, on-device)
- [ ] Depth estimation: MiDaS TFLite model for scene depth
- [ ] Physically-based bokeh blur: disk kernel + depth-weighted composite
- [ ] Edge refinement: guided filter on segmentation mask

### 3B. Super Resolution Zoom
- [ ] ESRGAN TFLite (4x upscale, open-source weights)
- [ ] Triggered when optical zoom insufficient (digital zoom > 2x)
- [ ] Patch-based inference for memory efficiency on mobile

### 3C. Scene Intelligence
- [ ] MobileNetV3 scene classifier: food/landscape/portrait/night/macro
- [ ] Auto-tune pipeline per scene: sharpening, saturation, contrast curves
- [ ] Real-time on preview (< 50ms inference)

### 3D. Color Science Tuning
- [ ] Custom ICC-style color matrix per lighting condition
- [ ] Skin tone protection: detect faces, reduce saturation shift in skin hue range
- [ ] Perceptual sharpening: high-frequency boost without halos (unsharp mask tuned)

**Deliverable:** App that consistently produces pleasing, professional-looking photos

---

## Phase 4 — Performance & Polish (Weeks 17-20)
**Goal: Make it feel premium — fast, smooth, nifty**

- [ ] GPU-accelerated preview: all real-time effects via OpenGL ES / Vulkan
- [ ] Async processing pipeline: capture returns instantly, processing in background
- [ ] Live processing progress indicator (like iOS processing spinner)
- [ ] Pro mode UI: scrollable parameter wheels (ISO, SS, WB, Focus)
- [ ] Histogram in real-time on preview
- [ ] Focus peaking (edge detection overlay, red highlights)
- [ ] Zebra stripes (overexposure warning)
- [ ] Video mode: LOG profile capture for grading
- [ ] Haptic feedback on capture
- [ ] Benchmark suite: measure every bottleneck, optimize hot paths

**Deliverable:** A polished app that feels faster than stock camera and produces better photos

---

## Architecture Overview

```
┌─────────────────────────────────────────┐
│              Compose UI Layer            │
│  (Viewfinder, Controls, Mode Selector)  │
└─────────────┬───────────────────────────┘
              │
┌─────────────▼───────────────────────────┐
│           ViewModel Layer               │
│  (CameraViewModel, ProcessingViewModel) │
└─────────────┬───────────────────────────┘
              │
┌─────────────▼───────────────────────────┐
│         Camera2 Controller              │
│  (Session, Requests, ZSL RingBuffer)    │
└──────┬──────────────────┬───────────────┘
       │                  │
┌──────▼──────┐   ┌───────▼──────────────┐
│  RAW/DNG    │   │  Computational       │
│  Pipeline   │   │  Photography Engine  │
│  (C++/JNI)  │   │  (OpenCV + TFLite)   │
└─────────────┘   └──────────────────────┘
```

---

## Critical Path (Minimum Viable Product)
If time-constrained, build in this order for maximum quality ROI:
1. Camera2 + ZSL (instant capture feel)
2. HDR+ burst merge (biggest visible quality jump)
3. Night mode stacking (beats every stock camera)
4. DnCNN noise reduction (cleaner ISO 800+ shots)
5. Color science tuning (makes everything look better)

---

## Time & Effort Reality Check

| Scenario | Timeline | Quality Ceiling |
|---|---|---|
| Solo dev, part-time | 10-14 months | ~75% of Pixel |
| Solo dev, full-time | 5-7 months | ~80% of Pixel |
| 2-3 dev team | 3-4 months | ~85% of Pixel |
| + ML specialist | +2 months | ~90% of Pixel |

The last 10% is Apple/Google's ISP hardware + years of perceptual tuning.
**~85% is absolutely achievable and would blow away 99% of third-party camera apps.**

---

## Open Source Models & Libraries (All Free)
- OpenCV Android: https://opencv.org/android/
- TensorFlow Lite: https://www.tensorflow.org/lite
- ESRGAN weights: github.com/xinntao/ESRGAN
- MiDaS depth: github.com/isl-org/MiDaS
- DnCNN: github.com/cszn/DnCNN
- MediaPipe: developers.google.com/mediapipe

---

## Review Section
_(To be filled after implementation)_
