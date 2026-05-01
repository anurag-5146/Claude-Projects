package com.gyrowallpaper

class DeepSpaceRenderer : ShaderRenderer() {

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
        float hash1(float x) { return fract(sin(x * 127.1) * 43758.5453); }

        float noise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(hash(i), hash(i + vec2(1,0)), f.x),
                mix(hash(i + vec2(0,1)), hash(i + vec2(1,1)), f.x), f.y);
        }

        float fbm(vec2 p, int oct) {
            float v = 0.0, a = 0.5;
            for (int i = 0; i < 8; i++) {
                if (i >= oct) break;
                v += a * noise(p);
                p  = p * 2.1 + vec2(1.7, 9.2);
                a *= 0.5;
            }
            return v;
        }

        // ── Stars ────────────────────────────────────────────────────────────────
        float star(vec2 uv, float scale, float density) {
            vec2 id = floor(uv * scale);
            vec2 gv = fract(uv * scale) - 0.5;
            float h  = hash(id);
            if (h < density) return 0.0;
            float t  = 0.55 + 0.45 * sin(uTime * (2.0 + h * 3.0) + h * 100.0);
            float sz = 0.5 + (h - density) / (1.0 - density);
            return smoothstep(0.20 * sz, 0.0, length(gv)) * t;
        }

        // ── Shooting star ────────────────────────────────────────────────────────
        float shootingStar(vec2 uv, float t) {
            // New star every ~12 seconds, random seed per cycle
            float cycle = 12.0;
            float phase = mod(t, cycle) / cycle;
            float seed  = floor(t / cycle);

            if (phase > 0.18) return 0.0;   // only visible at start of cycle

            vec2  origin = vec2(hash1(seed) * 2.0 - 1.0, hash1(seed + 0.5) * 0.6 + 0.2);
            vec2  dir    = normalize(vec2(0.8 + hash1(seed + 1.0) * 0.4, -0.5 - hash1(seed + 2.0) * 0.5));
            float speed  = 1.4 + hash1(seed + 3.0);
            float length = speed * phase * cycle;

            // Distance from point to line segment
            vec2  d   = uv - origin;
            float proj = clamp(dot(d, dir), -length, 0.0);
            vec2  closest = d - dir * proj;
            float dist = length(closest);

            float fade  = 1.0 - (phase / 0.18);
            float trail = 1.0 - clamp(-proj / length, 0.0, 1.0);
            return smoothstep(0.010, 0.001, dist) * fade * trail;
        }

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            // Convert to aspect-correct [-1,1] space
            vec2 uvc = uv * 2.0 - 1.0;
            uvc.x   *= uResolution.x / uResolution.y;

            vec2 gyro = uTilt * 0.10;

            // ── Deep black background ───────────────────────────────────────────
            vec3 col = vec3(0.0, 0.0, 0.008);

            // ── Nebula clouds (3 overlapping colour volumes) ────────────────────
            vec2 nu1 = uvc * 0.45 + gyro * 0.18 + vec2(0.0, 0.3);
            vec2 nu2 = uvc * 0.35 + gyro * 0.12 + vec2(1.5, -0.8);
            vec2 nu3 = uvc * 0.55 + gyro * 0.08 + vec2(-0.9, 0.5);

            float nb1 = fbm(nu1 + uTime * 0.007, 5);
            float nb2 = fbm(nu2 + uTime * 0.006, 4);
            float nb3 = fbm(nu3 + uTime * 0.005, 4);

            col += vec3(0.04, 0.10, 0.28) * nb1 * nb1 * 1.6;   // blue nebula
            col += vec3(0.26, 0.04, 0.10) * nb2 * nb2 * 1.2;   // crimson nebula
            col += vec3(0.02, 0.18, 0.20) * nb3 * 0.9;          // teal whisp

            // Bright nebula core
            float core = fbm(uvc * 0.8 + gyro * 0.05, 3);
            col += vec3(0.30, 0.22, 0.55) * pow(core, 3.0) * 2.5;

            // ── Stars – three depth layers with parallax ────────────────────────
            vec2 s1uv = uvc + gyro;
            vec2 s2uv = uvc + gyro * 1.6 + vec2(0.47, 0.31);
            vec2 s3uv = uvc + gyro * 2.2 + vec2(0.13, 0.77);

            col += star(s1uv, 22.0, 0.960) * vec3(1.0,  0.95, 0.85);
            col += star(s2uv, 48.0, 0.950) * vec3(0.80, 0.88, 1.00) * 0.75;
            col += star(s3uv, 90.0, 0.942) * vec3(1.0,  1.0,  1.0)  * 0.45;

            // ── Galaxy core glow ────────────────────────────────────────────────
            float galDist = length(uvc - gyro * 0.04);
            col += vec3(0.35, 0.25, 0.55) * exp(-galDist * galDist * 1.8) * 0.7;
            col += vec3(0.60, 0.50, 0.90) * exp(-galDist * galDist * 8.0) * 0.5;

            // ── Shooting star ───────────────────────────────────────────────────
            float ss = shootingStar(uvc, uTime);
            col += vec3(0.85, 0.92, 1.00) * ss * 4.0;

            // ── Vignette ────────────────────────────────────────────────────────
            col *= 1.0 - dot(uvc * 0.5, uvc * 0.5) * 0.55;

            // Tone-map (simple Reinhard)
            col = col / (col + 0.8);
            col = pow(col, vec3(0.9));   // slight gamma lift

            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()
}
