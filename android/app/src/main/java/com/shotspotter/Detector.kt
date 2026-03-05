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
    private val diffThreshold: Int = 20,
    private val darkThreshold: Int = 80,
    private val minArea: Int = 6,
    private val maxArea: Int = 600,
    private val minCircularity: Float = 0.35f,
    private val downsampleFactor: Int = 2,
    windowSize: Int = 5,
    requiredPositives: Int = 3
) {

    private val rollingWindow = RollingDetectionWindow(windowSize, requiredPositives)

    fun prepareFrame(frame: GrayFrame): PreparedFrame {
        val factor = downsampleFactor.coerceAtLeast(1)
        val downsampled = if (factor == 1) {
            frame.pixels.copyOf()
        } else {
            downsample(frame, factor)
        }
        return PreparedFrame(
            width = frame.width / factor,
            height = frame.height / factor,
            pixels = downsampled,
            timestampNanos = frame.timestampNanos,
            roi = frame.roi
        )
    }

    fun detect(baseline: PreparedFrame, current: GrayFrame): DetectionResult {
        val currentPrepared = prepareFrame(current)
        return detect(baseline, currentPrepared)
    }

    fun detect(baseline: PreparedFrame, current: PreparedFrame): DetectionResult {
        require(baseline.width == current.width && baseline.height == current.height) {
            "Baseline and current frames must have matching dimensions"
        }

        val width = baseline.width
        val height = baseline.height

        val diff = ByteArray(width * height)
        val active = BooleanArray(width * height)
        val frameDarkThreshold = resolveDarkThreshold(current.pixels)

        for (i in baseline.pixels.indices) {
            val d = kotlin.math.abs(
                (current.pixels[i].toInt() and 0xFF) - (baseline.pixels[i].toInt() and 0xFF)
            )
            val isDark = (current.pixels[i].toInt() and 0xFF) <= frameDarkThreshold
            diff[i] = d.toByte()
            active[i] = d >= diffThreshold && isDark
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
            var perimeter = 0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % width
                val y = idx / width

                area++
                val d = (diff[idx].toInt() and 0xFF).toFloat()
                sumDiff += d
                sumX += x
                sumY += y

                perimeter += boundaryEdges(x, y, width, height, active)

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

            if (perimeter <= 0) continue
            val circularity = ((4f * PI.toFloat() * area.toFloat()) / (perimeter * perimeter).toFloat())
            if (circularity < minCircularity) continue

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

    private fun resolveDarkThreshold(currentPixels: ByteArray): Int {
        if (darkThreshold >= 0) {
            return darkThreshold
        }

        var sum = 0L
        for (px in currentPixels) {
            sum += (px.toInt() and 0xFF).toLong()
        }
        val mean = sum.toFloat() / currentPixels.size.toFloat()
        return (mean * 0.6f).toInt().coerceIn(25, 120)
    }

    private fun boundaryEdges(x: Int, y: Int, width: Int, height: Int, active: BooleanArray): Int {
        var edges = 0
        if (x == 0 || !active[y * width + (x - 1)]) edges++
        if (x == width - 1 || !active[y * width + (x + 1)]) edges++
        if (y == 0 || !active[(y - 1) * width + x]) edges++
        if (y == height - 1 || !active[(y + 1) * width + x]) edges++
        return edges
    }

    private fun downsample(frame: GrayFrame, factor: Int): ByteArray {
        val outWidth = frame.width / factor
        val outHeight = frame.height / factor
        val out = ByteArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val srcX = x * factor
                val srcY = y * factor
                out[y * outWidth + x] = frame.pixels[srcY * frame.width + srcX]
            }
        }
        return out
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

data class PreparedFrame(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val timestampNanos: Long,
    val roi: RoiNorm
)

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
