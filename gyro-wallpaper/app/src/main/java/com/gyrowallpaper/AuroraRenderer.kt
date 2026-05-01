package com.gyrowallpaper

class AuroraRenderer : ShaderRenderer() {

    override fun fragmentShader() = """
        #version 300 es
        precision highp float;

        uniform float uTime;
        uniform vec2  uResolution;
        uniform vec2  uTilt;

        out vec4 fragColor;

        // ── Noise helpers ────────────────────────────────────────────────────────
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
        }

        float noise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash(i), b = hash(i + vec2(1,0));
            float c = hash(i + vec2(0,1)), d = hash(i + vec2(1,1));
            return mix(mix(a,b,f.x), mix(c,d,f.x), f.y);
        }

        float fbm(vec2 p) {
            float v = 0.0, a = 0.5;
            mat2 rot = mat2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5));
            for (int i = 0; i < 7; i++) {
                v += a * noise(p);
                p  = rot * p * 2.0 + vec2(100.0);
                a *= 0.5;
            }
            return v;
        }

        // ── Stars ────────────────────────────────────────────────────────────────
        float starLayer(vec2 uv, float scale, float density) {
            vec2 id = floor(uv * scale);
            vec2 gv = fract(uv * scale) - 0.5;
            float h = hash(id);
            if (h < density) return 0.0;
            float twinkle = 0.55 + 0.45 * sin(uTime * (2.5 + h * 4.0) + h * 99.0);
            float size    = 0.4 + (h - density) / (1.0 - density) * 0.6;
            return smoothstep(0.18 * size, 0.0, length(gv)) * twinkle;
        }

        // ── Mountain silhouette ──────────────────────────────────────────────────
        float mountainHeight(float x) {
            float h = 0.12;
            h += 0.055 * noise(vec2(x * 3.1, 1.0));
            h += 0.030 * noise(vec2(x * 6.7, 2.5));
            h += 0.012 * noise(vec2(x * 14.0, 5.0));
            return h;
        }

        // ── Main ─────────────────────────────────────────────────────────────────
        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;   // 0..1

            // Gyro parallax offsets per layer
            vec2 t1 = uTilt * 0.025;   // far stars
            vec2 t2 = uTilt * 0.055;   // near stars
            vec2 t3 = uTilt * 0.012;   // aurora
            vec2 t4 = uTilt * 0.040;   // mountains

            // ── Sky gradient ────────────────────────────────────────────────────
            vec3 sky = mix(vec3(0.018, 0.01, 0.05), vec3(0.005, 0.02, 0.12), uv.y);

            // ── Stars ──────────────────────────────────────────────────────────
            float s  = starLayer(uv + t1,          38.0, 0.962)
                     + starLayer(uv + t1 + 0.31,   75.0, 0.950) * 0.65
                     + starLayer(uv + t2,          130.0, 0.940) * 0.35;
            float aboveGround = smoothstep(0.12, 0.30, uv.y);
            sky += vec3(0.92, 0.96, 1.0) * s * aboveGround;

            // ── Aurora noise ────────────────────────────────────────────────────
            vec2 au = uv + t3;
            float n1 = fbm(vec2(au.x * 2.4  + uTime * 0.065,  uTime * 0.038));
            float n2 = fbm(vec2(au.x * 1.7  - uTime * 0.048 + 3.3, uTime * 0.030 + 5.8));
            float n3 = fbm(vec2(au.x * 3.8  + uTime * 0.055 + 7.1, uTime * 0.048 + 2.2));

            float baseY = 0.64 + uTilt.y * 0.04;

            // Band 1 – vivid green
            float b1h = baseY        + (n1 - 0.5) * 0.22;
            float b1  = smoothstep(0.065, 0.0, abs(uv.y - b1h));
            b1 *= smoothstep(0.18, 0.50, uv.y);

            // Band 2 – cyan / teal
            float b2h = baseY - 0.06 + (n2 - 0.5) * 0.17;
            float b2  = smoothstep(0.055, 0.0, abs(uv.y - b2h));
            b2 *= smoothstep(0.16, 0.44, uv.y);

            // Band 3 – deep violet
            float b3h = baseY + 0.09 + (n3 - 0.5) * 0.13;
            float b3  = smoothstep(0.042, 0.0, abs(uv.y - b3h));
            b3 *= smoothstep(0.28, 0.62, uv.y);

            vec3 aurora  = vec3(0.0,  0.92, 0.40) * b1 * 1.6;
                aurora  += vec3(0.0,  0.60, 0.90) * b2 * 1.3;
                aurora  += vec3(0.55, 0.0,  0.95) * b3 * 0.9;

            // Soft glow halo around each band
            float glow = (b1 + b2 * 0.8 + b3 * 0.5) * 0.18;
            sky += vec3(0.0, 0.18, 0.10) * glow;

            // ── Ground / mountains ──────────────────────────────────────────────
            float mh = mountainHeight(uv.x + t4.x * 0.5);
            float isGround = step(uv.y, mh);

            // Snow shows faint aurora colour reflection
            float snowGlow = (b1 + b2) * 0.28 * smoothstep(0.0, 0.12, mh - uv.y);
            vec3 ground = vec3(0.008, 0.014, 0.010) + vec3(0.0, 0.04, 0.02) * snowGlow;

            // Thin horizon fog
            float fog = exp(-9.0 * max(0.0, uv.y - mh)) * (1.0 - isGround);
            vec3 fogCol = vec3(0.02, 0.06, 0.04);

            vec3 col = mix(sky + aurora, ground, isGround);
            col = mix(col, fogCol, fog * 0.35);

            // Subtle vignette
            vec2 vg = uv - 0.5;
            col *= 1.0 - dot(vg, vg) * 0.65;

            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()
}
