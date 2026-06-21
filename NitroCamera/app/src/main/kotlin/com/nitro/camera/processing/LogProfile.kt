package com.nitro.camera.processing

import android.hardware.camera2.params.TonemapCurve
import kotlin.math.log10
import kotlin.math.pow

/**
 * Logarithmic tone curves for video capture.
 *
 * LOG profiles compress highlights and shadows into a flat, low-contrast
 * recording that preserves the maximum dynamic range from the sensor.
 * The flat footage is then graded in post using a LUT.
 *
 * We provide three profiles of increasing flatness:
 *   - STANDARD : gentle S-curve, best-looking straight out of camera
 *   - CINE_D   : Sony Cine-D approximation, moderate dynamic range boost
 *   - S_LOG3   : Sony S-Log3 approximation, maximum DR (requires LUT in post)
 */
object LogProfile {

    enum class Profile { STANDARD, CINE_D, S_LOG3 }

    fun tonemapCurve(profile: Profile): TonemapCurve {
        val points = when (profile) {
            Profile.STANDARD -> standardCurve()
            Profile.CINE_D   -> cineDCurve()
            Profile.S_LOG3   -> sLog3Curve()
        }
        // Camera2 expects the same curve for R, G, B
        return TonemapCurve(points, points, points)
    }

    // ── Standard: gentle contrast S-curve ────────────────────────────────────

    private fun standardCurve(): FloatArray = buildCurve { x ->
        // Smooth S-curve: shadows lifted, highlights rolled off
        when {
            x < 0.003f -> x * 12.92f
            else -> 1.055f * x.pow(1f / 2.2f) - 0.055f
        }.let { it * 0.92f + 0.04f }   // lift blacks slightly
    }

    // ── Cine-D: moderate log, ~12 stops DR ───────────────────────────────────

    private fun cineDCurve(): FloatArray = buildCurve { x ->
        if (x <= 0f) 0.0208f
        else {
            val logVal = log10(x.toDouble() * 10.0 + 0.09).toFloat()
            (logVal * 0.38f + 0.45f).coerceIn(0f, 1f)
        }
    }

    // ── S-Log3: maximum DR, 14+ stops ────────────────────────────────────────

    private fun sLog3Curve(): FloatArray = buildCurve { x ->
        if (x >= 0.01125f) {
            ((420f + log10(((x + 0.01f) / (0.18f + 0.01f)).toDouble()).toFloat() * 261.5f) / 1023f)
                .coerceIn(0f, 1f)
        } else {
            (x * (171.2102946929f - 95f) / 0.01125f + 95f) / 1023f
        }
    }

    // ── Curve builder ─────────────────────────────────────────────────────────

    private fun buildCurve(samples: Int = 64, fn: (Float) -> Float): FloatArray {
        val pts = FloatArray(samples * 2)
        for (i in 0 until samples) {
            val x = i.toFloat() / (samples - 1).toFloat()
            pts[i * 2]     = x
            pts[i * 2 + 1] = fn(x).coerceIn(0f, 1f)
        }
        return pts
    }
}
