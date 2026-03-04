package com.shotspotter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

data class RoiNorm(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun translate(dx: Float, dy: Float): RoiNorm {
        val clampedDx = when {
            left + dx < 0f -> -left
            right + dx > 1f -> 1f - right
            else -> dx
        }
        val clampedDy = when {
            top + dy < 0f -> -top
            bottom + dy > 1f -> 1f - bottom
            else -> dy
        }

        return copy(
            left = left + clampedDx,
            right = right + clampedDx,
            top = top + clampedDy,
            bottom = bottom + clampedDy
        )
    }

    fun toPixelBounds(width: Int, height: Int): RoiPixelBounds {
        val l = (left * width).toInt().coerceIn(0, width - 1)
        val t = (top * height).toInt().coerceIn(0, height - 1)
        val r = (right * width).toInt().coerceIn(l + 1, width)
        val b = (bottom * height).toInt().coerceIn(t + 1, height)
        return RoiPixelBounds(left = l, top = t, rightExclusive = r, bottomExclusive = b)
    }

    companion object {
        val DEFAULT = RoiNorm(left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f)
        val FULL = RoiNorm(left = 0f, top = 0f, right = 1f, bottom = 1f)
    }
}

data class RoiPixelBounds(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int
)

@Composable
fun ShotOverlay(
    roi: RoiNorm,
    candidates: List<HoleCandidate>,
    strongest: HoleCandidate?,
    confirmedDetection: Boolean,
    detectionConfidence: Float,
    frameTimeMs: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val roiLeftPx = roi.left * size.width
        val roiTopPx = roi.top * size.height
        val roiWidthPx = roi.width * size.width
        val roiHeightPx = roi.height * size.height
        val indicatorColor = if (confirmedDetection) Color(0xFF34C759) else Color(0xFFFF3B30)

        drawRect(
            color = Color(0xFFFFC107),
            topLeft = Offset(roiLeftPx, roiTopPx),
            size = Size(roiWidthPx, roiHeightPx),
            style = Stroke(width = 4f)
        )

        drawRect(
            color = indicatorColor,
            topLeft = Offset(roiLeftPx, roiTopPx),
            size = Size(roiWidthPx, roiHeightPx),
            style = Stroke(width = 8f)
        )

        candidates.forEach { candidate ->
            val center = Offset(
                x = roiLeftPx + candidate.centerX * roiWidthPx,
                y = roiTopPx + candidate.centerY * roiHeightPx
            )
            val radiusPx = candidate.radius * maxOf(roiWidthPx, roiHeightPx)
            val isStrongest = strongest == candidate
            val boxTopLeft = Offset(
                x = roiLeftPx + candidate.boxLeft * roiWidthPx,
                y = roiTopPx + candidate.boxTop * roiHeightPx
            )
            val boxSize = Size(
                width = (candidate.boxRight - candidate.boxLeft) * roiWidthPx,
                height = (candidate.boxBottom - candidate.boxTop) * roiHeightPx
            )

            drawRect(
                color = if (isStrongest) Color(0xFF34C759) else Color(0xFF00E5FF),
                topLeft = boxTopLeft,
                size = boxSize,
                style = Stroke(width = if (isStrongest) 5f else 3f)
            )

            drawCircle(
                color = if (isStrongest) Color(0xFFFF3B30) else Color(0xFF00E5FF),
                radius = radiusPx,
                center = center,
                style = Stroke(width = if (isStrongest) 8f else 4f)
            )
        }

        val label = buildString {
            append("Confidence ")
            append(String.format(Locale.US, "%.2f", detectionConfidence))
            strongest?.let {
                append(" | Candidate ")
                append(String.format(Locale.US, "%.2f", it.confidence))
            }
            append(" | Frame ")
            append(String.format(Locale.US, "%.1fms", frameTimeMs))
        }

        drawContext.canvas.nativeCanvas.apply {
            drawText(
                label,
                24f,
                52f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 38f
                    isFakeBoldText = true
                }
            )
        }
    }
}

@Composable
fun TargetRoiOverlay(
    roi: RoiNorm,
    onRoiChange: (RoiNorm) -> Unit,
    dragEnabled: Boolean,
    showOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(roi, dragEnabled) {
                    if (!dragEnabled) return@pointerInput
                    var isDragging = false
                    detectDragGestures(
                        onDragStart = { start ->
                            val width = size.width
                            val height = size.height
                            val leftPx = roi.left * width
                            val topPx = roi.top * height
                            val rightPx = roi.right * width
                            val bottomPx = roi.bottom * height
                            isDragging = start.x in leftPx..rightPx && start.y in topPx..bottomPx
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, dragAmount ->
                            if (!isDragging) return@detectDragGestures
                            change.consume()
                            val dxNorm = dragAmount.x / size.width
                            val dyNorm = dragAmount.y / size.height
                            onRoiChange(roi.translate(dxNorm, dyNorm))
                        }
                    )
                }
        ) {
            if (!showOverlay) return@Canvas
            val topLeft = Offset(roi.left * size.width, roi.top * size.height)
            val roiSize = Size(roi.width * size.width, roi.height * size.height)
            drawRect(
                color = Color(0xFFFFC107),
                topLeft = topLeft,
                size = roiSize,
                style = Stroke(width = 5f)
            )
        }

        if (showOverlay) {
            Text(
                text = "Target Area",
                color = Color(0xFFFFC107),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset {
                    IntOffset(
                        x = (roi.left * canvasSize.width).roundToInt() + with(density) { 8.dp.roundToPx() },
                        y = ((roi.top * canvasSize.height).roundToInt() - with(density) { 24.dp.roundToPx() })
                            .coerceAtLeast(0)
                    )
                }
            )
        }
    }
}
