package com.shotspotter

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class HoleCandidate(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val score: Float,
    val confidence: Float,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float
)

data class GrayFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val timestampNanos: Long,
    val roi: RoiNorm = RoiNorm.FULL
)

class Detector(
    private val diffThreshold: Int = 28,
    private val minArea: Int = 8,
    private val maxAreaRatio: Float = 0.04f,
    private val minAspectRatio: Float = 0.45f,
    private val maxAspectRatio: Float = 2.2f,
    windowSize: Int = 5,
    requiredPositives: Int = 3
) {

    private val rollingWindow = RollingDetectionWindow(windowSize, requiredPositives)

    fun detect(baseline: GrayFrame, current: GrayFrame): DetectionResult {
        require(baseline.width == current.width && baseline.height == current.height) {
            "Baseline and current frames must have matching dimensions"
        }

        val width = baseline.width
        val height = baseline.height
        val maxArea = (width * height * maxAreaRatio).toInt().coerceAtLeast(minArea)

        val diff = IntArray(width * height)
        val active = BooleanArray(width * height)

        for (i in diff.indices) {
            val d = kotlin.math.abs(
                (current.pixels[i].toInt() and 0xFF) - (baseline.pixels[i].toInt() and 0xFF)
            )
            diff[i] = d
            active[i] = d >= diffThreshold
        }

        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        val candidates = mutableListOf<HoleCandidate>()

        for (start in active.indices) {
            if (!active[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var area = 0
            var sumDiff = 0f
            var sumX = 0f
            var sumY = 0f
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % width
                val y = idx / width

                area++
                val d = diff[idx].toFloat()
                sumDiff += d
                sumX += x
                sumY += y

                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)

                enqueueIfValid(x - 1, y, width, height, active, visited) { idx -> queue[tail++] = idx }
                enqueueIfValid(x + 1, y, width, height, active, visited) { idx -> queue[tail++] = idx }
                enqueueIfValid(x, y - 1, width, height, active, visited) { idx -> queue[tail++] = idx }
                enqueueIfValid(x, y + 1, width, height, active, visited) { idx -> queue[tail++] = idx }
            }

            if (area !in minArea..maxArea) continue

            val blobWidth = (maxX - minX + 1).toFloat()
            val blobHeight = (maxY - minY + 1).toFloat()
            val aspect = blobWidth / blobHeight
            if (aspect < minAspectRatio || aspect > maxAspectRatio) continue

            val meanDiff = sumDiff / area.toFloat()
            val score = meanDiff * sqrt(area.toFloat())

            val centerX = (sumX / area.toFloat()) / width.toFloat()
            val centerY = (sumY / area.toFloat()) / height.toFloat()
            val radiusPixels = sqrt(area.toDouble() / PI).toFloat()
            val normalizedRadius = radiusPixels / max(width, height).toFloat()
            val candidateConfidence = (
                (meanDiff / 255f) * 0.7f +
                    (area.toFloat() / maxArea.toFloat()) * 0.3f
                )
                .coerceIn(0f, 1f)

            candidates += HoleCandidate(
                centerX = centerX,
                centerY = centerY,
                radius = normalizedRadius,
                score = score,
                confidence = candidateConfidence,
                boxLeft = minX.toFloat() / width.toFloat(),
                boxTop = minY.toFloat() / height.toFloat(),
                boxRight = (maxX + 1).toFloat() / width.toFloat(),
                boxBottom = (maxY + 1).toFloat() / height.toFloat()
            )
        }

        val strongest = candidates.maxByOrNull { it.score }
        val framePositive = strongest != null
        val stability = rollingWindow.add(framePositive)
        return DetectionResult(
            candidates = candidates,
            strongest = strongest,
            framePositive = framePositive,
            confirmedDetection = stability.confirmedDetection,
            confidence = stability.confidence
        )
    }

    fun resetStability() {
        rollingWindow.reset()
    }

    private inline fun enqueueIfValid(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        active: BooleanArray,
        visited: BooleanArray,
        enqueue: (Int) -> Unit
    ) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val idx = y * width + x
        if (!active[idx] || visited[idx]) return
        visited[idx] = true
        enqueue(idx)
    }
}

private class RollingDetectionWindow(
    private val size: Int,
    private val requiredPositives: Int
) {
    init {
        require(size > 0) { "Window size must be positive" }
        require(requiredPositives in 1..size) {
            "requiredPositives must be between 1 and window size"
        }
    }

    private val values = ArrayDeque<Boolean>(size)
    private var positives = 0

    @Synchronized
    fun add(isPositive: Boolean): StabilityResult {
        if (values.size == size) {
            val dropped = values.removeFirst()
            if (dropped) positives--
        }

        values.addLast(isPositive)
        if (isPositive) positives++

        val confidence = positives.toFloat() / size.toFloat()
        return StabilityResult(
            confirmedDetection = positives >= requiredPositives,
            confidence = confidence
        )
    }

    @Synchronized
    fun reset() {
        values.clear()
        positives = 0
    }
}

private data class StabilityResult(
    val confirmedDetection: Boolean,
    val confidence: Float
)

data class DetectionResult(
    val candidates: List<HoleCandidate>,
    val strongest: HoleCandidate?,
    val framePositive: Boolean,
    val confirmedDetection: Boolean,
    val confidence: Float
)
