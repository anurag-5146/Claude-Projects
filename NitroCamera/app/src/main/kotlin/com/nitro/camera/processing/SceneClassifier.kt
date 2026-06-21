package com.nitro.camera.processing

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

private const val TAG = "SceneClassifier"

enum class Scene(val label: String, val emoji: String) {
    PORTRAIT("Portrait", "👤"),
    LANDSCAPE("Landscape", "🌄"),
    FOOD("Food", "🍜"),
    NIGHT("Night", "🌙"),
    MACRO("Macro", "🔬"),
    GENERAL("Auto", "📷")
}

data class SceneProcessingParams(
    val sharpenAmount: Float = 0.3f,
    val saturationBoost: Float = 1.0f,
    val contrastBoost: Float = 1.0f,
    val shadowLift: Float = 0.0f,
    val warmthShift: Float = 0.0f,        // positive = warmer, negative = cooler
    val noiseReductionStrength: Float = 0.3f,
    val enablePortraitBokeh: Boolean = false
)

/**
 * Heuristic scene classifier — no model file required.
 *
 * Uses image statistics (brightness, saturation, contrast, aspect content)
 * to classify the scene and return tuned processing parameters.
 *
 * Replace [classifyHeuristic] with a TFLite MobileNetV3 model when available
 * by placing `scene_classifier.tflite` in assets — the slot is reserved.
 */
object SceneClassifier {

    suspend fun classify(bitmap: Bitmap): Pair<Scene, Float> = withContext(Dispatchers.Default) {
        runCatching { classifyHeuristic(bitmap) }.getOrElse {
            Log.w(TAG, "Classification failed: ${it.message}")
            Pair(Scene.GENERAL, 0.5f)
        }
    }

    fun paramsFor(scene: Scene): SceneProcessingParams = when (scene) {
        Scene.PORTRAIT -> SceneProcessingParams(
            sharpenAmount = 0.25f,
            saturationBoost = 1.05f,
            contrastBoost = 1.05f,
            shadowLift = 0.08f,
            warmthShift = 0.04f,
            noiseReductionStrength = 0.35f,
            enablePortraitBokeh = true
        )
        Scene.LANDSCAPE -> SceneProcessingParams(
            sharpenAmount = 0.55f,
            saturationBoost = 1.15f,
            contrastBoost = 1.10f,
            shadowLift = 0.0f,
            warmthShift = 0.0f,
            noiseReductionStrength = 0.15f
        )
        Scene.FOOD -> SceneProcessingParams(
            sharpenAmount = 0.45f,
            saturationBoost = 1.20f,
            contrastBoost = 1.08f,
            shadowLift = 0.05f,
            warmthShift = 0.06f,
            noiseReductionStrength = 0.20f
        )
        Scene.NIGHT -> SceneProcessingParams(
            sharpenAmount = 0.20f,
            saturationBoost = 1.0f,
            contrastBoost = 1.05f,
            shadowLift = 0.12f,
            warmthShift = -0.02f,
            noiseReductionStrength = 0.65f
        )
        Scene.MACRO -> SceneProcessingParams(
            sharpenAmount = 0.70f,
            saturationBoost = 1.10f,
            contrastBoost = 1.05f,
            shadowLift = 0.0f,
            warmthShift = 0.0f,
            noiseReductionStrength = 0.10f
        )
        Scene.GENERAL -> SceneProcessingParams()
    }

    // ── Heuristic classifier ──────────────────────────────────────────────────

    private fun classifyHeuristic(bitmap: Bitmap): Pair<Scene, Float> {
        val mat = bitmap.toMat()
        val hsv = Mat(); Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_BGR2HSV)
        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels)

        val meanBrightness = Core.mean(channels[2]).`val`[0]          // V channel
        val meanSaturation = Core.mean(channels[1]).`val`[0]          // S channel

        // Contrast: std-dev of value channel
        val stdDev = MatOfDouble(); val meanM = MatOfDouble()
        Core.meanStdDev(channels[2], meanM, stdDev)
        val contrast = stdDev.toArray()[0]

        // Green dominance: mean of green channel vs red/blue (landscape heuristic)
        val bgrChannels = mutableListOf<Mat>()
        Core.split(mat, bgrChannels)
        val meanB = Core.mean(bgrChannels[0]).`val`[0]
        val meanG = Core.mean(bgrChannels[1]).`val`[0]
        val meanR = Core.mean(bgrChannels[2]).`val`[0]
        val greenDominance = meanG - ((meanR + meanB) / 2.0)

        listOf(channels, bgrChannels).flatten().forEach { it.release() }
        listOf(mat, hsv, stdDev, meanM).forEach { it.release() }

        return when {
            meanBrightness < 55.0 -> Pair(Scene.NIGHT, 0.80f)
            contrast > 70.0 && greenDominance > 15.0 -> Pair(Scene.LANDSCAPE, 0.72f)
            meanSaturation > 130.0 && meanBrightness > 130.0 -> Pair(Scene.FOOD, 0.65f)
            contrast < 35.0 && meanBrightness > 100.0 -> Pair(Scene.PORTRAIT, 0.60f)
            contrast > 80.0 && meanSaturation < 80.0 -> Pair(Scene.MACRO, 0.60f)
            else -> Pair(Scene.GENERAL, 0.55f)
        }
    }
}
