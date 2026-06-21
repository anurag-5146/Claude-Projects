# NitroCamera UI Design with Canva

This guide walks you through creating beautiful, user-friendly mockups for the NitroCamera app using Canva.

## Why Canva?

Canva is a free, drag-and-drop design tool that lets you create professional UI mockups without coding. You design first, we code second — this ensures the UI is polished before development.

---

## Getting Started

### 1. Sign Up
- Go to https://www.canva.com
- Sign up with Google, Facebook, or email (all free)
- No credit card required

### 2. Create a New Design
- Click **"Create a design"**
- Search for **"Custom size"**
- Enter dimensions: **1080 x 2400 px** (standard Android phone portrait)
- Click **"Create design"**

---

## Design Your NitroCamera UI

### Current Layout (for reference)
```
┌─────────────────────────────┐
│ [⚡PEAK ZEBRA HIST]         │  ← HUD toggles (top-right)
│                             │
│                             │
│        CAMERA PREVIEW       │  ← 4:3 letterboxed, black bars
│                             │
│                             │
│ [Scene badge]               │  ← Scene detection (top-center)
│                             │
├─────────────────────────────┤
│ [PHOTO HDR NIGHT PORTRAIT] │  ← Mode chips (bottom-center)
│      [CAPTURE BUTTON]       │  ← Circular white button
│                             │
└─────────────────────────────┘
```

### UI Friendliness Factors to Consider

**1. Thumb Ergonomics**
- Can you reach all controls with one hand (thumb)?
- Capture button should be in the bottom half
- Frequently-used modes should be within thumb range

**2. Visual Hierarchy**
- What's most important? (Preview > capture button > modes > overlays)
- Use size, color, and positioning to emphasize
- Don't clutter the preview with UI

**3. Feedback Clarity**
- Is it obvious when the app is: idle, capturing, processing, done?
- Progress bars and status badges help

**4. Accessibility**
- Touch targets should be at least 44x44 dp (≈120px in Canva)
- High contrast text (white on black works well)
- Icons should be clear without text

**5. Fun & Delight**
- Color harmony (avoid clashing colors)
- Subtle animations (indicate in design: "button pulsates when active")
- Personality (camera apps can be playful, not just functional)

---

## Canva Tips & Tricks

### Adding Elements
- **Search icons**: Type "camera", "delete", "share" → find icons
- **Colors**: Use Material Design colors or system theme colors
- **Typography**: Use simple fonts (Roboto, Poppins) for mobile clarity
- **Spacing**: Use a 8px grid (Canva helps with alignment guides)

### Mockup Structure
Create 2–3 variations showing:
1. **Idle state** — waiting for user input
2. **Capture state** — showing which photo was just taken
3. **Optional** — full-screen photo lightbox

### Example Layouts to Try

**Option A: Bottom-Aligned Controls**
```
Preview (top 70%)
├─ Mode chips (horizontal row)
└─ Capture button + photo preview thumbnail
```

**Option B: Side Controls**
```
Preview (left 85%)
├─ Mode selector (vertical pill on right)
└─ Capture button (bottom-right, large)
```

**Option C: Minimal**
```
Preview (full screen except bottom 60px)
├─ Mode chips (bottom bar, horizontal)
└─ Capture button (center-bottom)
```

---

## Export Your Mockup

1. Click **"Download"** (top-right)
2. Select **PNG** format
3. Choose **"Download"** (not "Share link")
4. Save to your computer

---

## Share with Claude

Once you've created your mockup:

1. **Export as PNG** (as above)
2. **Upload the image** to this conversation
3. **Describe what's changed:**
   - Where did you move the mode chips?
   - How is the capture button styled?
   - Any new overlays or information panels?
4. **Claude will:**
   - Review it for feasibility on Android
   - Suggest adjustments if needed
   - Code the exact design once approved

---

## Design Examples

### Good Camera App UIs (for inspiration)
- **Google Pixel Camera**: minimal, mode chips at bottom
- **iPhone Camera**: side-swipe mode selector, large capture button
- **GCam (third-party)**: floating controls, night mode badge
- **Samsung Camera**: customizable layout, mode buttons

### Mobile Design Best Practices
- Keep the camera preview large (70%+ of screen)
- Don't overlay text on the live preview (use darkened backgrounds)
- Keep capture button in a consistent location
- Use color to indicate state (e.g., red circle = recording video)

---

## Constraints for NitroCamera

Keep these in mind when designing:

1. **Capture button placement**: Must stay in bottom half (thumb-reachable)
2. **Preview visibility**: Never fully cover the camera preview
3. **Mode selection**: Should be quick (max 2 taps to change modes)
4. **Photo preview**: Should NOT hide the capture button
5. **Performance**: Avoid overly complex animations (we'll keep it smooth)

---

## Timeline

1. **Today**: You design in Canva (30–60 min)
2. **Today**: Share mockup with Claude
3. **Claude reviews**: Feasibility check (5 min)
4. **You refine**: Minor adjustments if needed (10 min)
5. **Claude codes**: UI implementation (2–4 hours, depends on complexity)
6. **Next day**: Test on device

---

## Questions?

If you get stuck:
- Canva Help: https://www.canva.com/help/
- Send the partially-complete mockup to Claude for feedback
- No design is perfect on the first try — iterate!

**Have fun designing! 🎨**
