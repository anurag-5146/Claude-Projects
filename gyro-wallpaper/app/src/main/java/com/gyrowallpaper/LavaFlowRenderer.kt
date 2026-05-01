package com.gyrowallpaper

class LavaFlowRenderer : ShaderRenderer() {

    override fun fragmentShader() = """
        #version 300 es
        precision highp float;

        uniform float uTime;
        uniform vec2  uResolution;
        uniform vec2  uTilt;

        out vec4 fragColor;

        // ── Hash / noise ─────────────────────────────────────────────────────────
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float noise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(hash(i), hash(i+vec2(1,0)), f.x),
                mix(hash(i+vec2(0,1)), hash(i+vec2(1,1)), f.x), f.y);
        }

        float fbm(vec2 p, int oct) {
            float v = 0.0, a = 0.5;
            for (int i = 0; i < 8; i++) {
                if (i >= oct) break;
                v += a * noise(p);
                p  = p * 2.1 + vec2(5.3, 1.7);
                a *= 0.5;
            }
            return v;
        }

        // ── Voronoi – cracks in rock ─────────────────────────────────────────────
        float voronoi(vec2 p) {
            vec2 id = floor(p);
            vec2 gv = fract(p) - 0.5;
            float minDist = 8.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec2 nb  = vec2(float(x), float(y));
                    vec2 pt  = nb + vec2(hash(id + nb) - 0.5, hash(id + nb + 0.5) - 0.5) * 0.75;
                    float d  = length(gv - pt);
                    minDist  = min(minDist, d);
                }
            }
            return minDist;
        }

        // ── Lava colour ramp ─────────────────────────────────────────────────────
        vec3 lavaColor(float t) {
            // 0 = dark red core  → 1 = bright yellow-white tip
            t = clamp(t, 0.0, 1.0);
            vec3 dark   = vec3(0.55, 0.02, 0.00);
            vec3 mid    = vec3(1.00, 0.30, 0.00);
            vec3 bright = vec3(1.00, 0.90, 0.30);
            if (t < 0.5) return mix(dark, mid, t * 2.0);
            return mix(mid, bright, (t - 0.5) * 2.0);
        }

        // ── Bubble ───────────────────────────────────────────────────────────────
        float bubble(vec2 uv, vec2 pos, float r) {
            return smoothstep(r, r * 0.5, length(uv - pos));
        }

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;

            // Gravity direction from gyro – lava pools toward where the phone tilts
            vec2 gravity = vec2(uTilt.x * 0.30, -uTilt.y * 0.25);

            // Time-animated flow UV – shifts with gravity
            float flowTime = uTime * 0.12;
            vec2  flowUV   = uv + gravity * 0.5 + vec2(flowTime * 0.08, -flowTime);

            // ── Rock surface ─────────────────────────────────────────────────────
            float v1 = voronoi(uv * 5.5);
            float v2 = voronoi(uv * 9.0 + vec2(3.7, 1.1));
            float crack = min(v1, v2);                 // 0 = inside crack, >0 = rock

            // Displace cracks slightly with noise
            float disp = fbm(uv * 4.0 + gravity * 0.6, 3) * 0.12;
            crack = voronoi(uv * 5.5 + disp);

            float rockMask  = smoothstep(0.12, 0.22, crack); // 1 = rock, 0 = lava crack
            float crackMask = 1.0 - rockMask;

            // Rock colour – rough dark basalt with faint reddish tint
            float rockNoise = fbm(uv * 6.0, 4);
            vec3  rockCol   = mix(vec3(0.06, 0.02, 0.01), vec3(0.12, 0.04, 0.02), rockNoise);

            // ── Lava glow in cracks ──────────────────────────────────────────────
            // Animated lava "temperature" – brighter near top of crack
            float lavaBase = fbm(flowUV * 3.0, 5);
            float lavaHeat = lavaBase * lavaBase;

            // Pool accumulation – lava is brighter where gravity points
            float gravPool = dot(uv - 0.5, normalize(gravity + vec2(0.0, -0.1)));
            lavaHeat = clamp(lavaHeat + gravPool * 0.35, 0.0, 1.0);

            vec3 lavCol = lavaColor(lavaHeat * 1.3);

            // Lava emissive glow bleeds into rock
            float glowRadius = 0.04 + crackMask * 0.08;
            float glowBleed  = smoothstep(0.30, 0.05, crack);
            vec3  glowCol    = lavaColor(0.5) * glowBleed * 0.6;

            // ── Bubbles (occasional, near lava pools) ───────────────────────────
            float bubbleGlow = 0.0;
            for (int i = 0; i < 5; i++) {
                float fi    = float(i);
                float bSeed = fi * 137.53;
                float bTime = mod(uTime * (0.3 + fi * 0.07) + bSeed, 4.0);
                if (bTime < 2.5) {
                    vec2  bPos = vec2(hash(vec2(fi, 0.0)) * 0.8 + 0.1,
                                      1.0 - bTime * 0.3 - hash(vec2(fi, 1.0)) * 0.3);
                    bPos      += gravity * 0.5;
                    float brad = 0.012 + hash(vec2(fi, 2.0)) * 0.015;
                    float bAlpha = smoothstep(0.0, 0.4, bTime) * smoothstep(2.5, 1.8, bTime);
                    bubbleGlow  += bubble(uv, bPos, brad) * bAlpha;
                }
            }

            // ── Smoke / heat haze (top of screen) ───────────────────────────────
            float smokeY = uv.y - 0.8;
            vec3  smoke  = vec3(0.0);
            if (smokeY > 0.0) {
                float smokeN = fbm(vec2(uv.x * 3.0 + uTime * 0.05, uv.y * 2.0), 3);
                smoke = vec3(0.08, 0.04, 0.02) * smokeN * smokeY * 1.5;
            }

            // ── Compose ──────────────────────────────────────────────────────────
            vec3 col = mix(lavCol, rockCol + glowCol, rockMask);
            col += vec3(1.0, 0.60, 0.10) * bubbleGlow * crackMask;
            col += smoke;

            // Heat shimmer tint at horizon
            float heat = smoothstep(0.65, 1.0, uv.y);
            col = mix(col, vec3(1.0, 0.25, 0.0) * 0.25, heat * 0.3);

            // Vignette
            vec2 vg = uv - 0.5;
            col *= 1.0 - dot(vg, vg) * 0.55;

            // HDR-ish exposure
            col = 1.0 - exp(-col * 1.4);

            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()
}
