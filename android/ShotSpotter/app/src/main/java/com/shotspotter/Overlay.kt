package com.shotspotter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun ShotOverlay(
    candidates: List<HoleCandidate>,
    strongest: HoleCandidate?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        candidates.forEach { candidate ->
            val center = Offset(candidate.centerX * size.width, candidate.centerY * size.height)
            val radiusPx = candidate.radius * size.minDimension
            val isStrongest = strongest == candidate
            drawCircle(
                color = if (isStrongest) Color(0xFFFF3B30) else Color(0xFF00E5FF),
                radius = radiusPx,
                center = center,
                style = Stroke(width = if (isStrongest) 8f else 4f)
            )
        }
    }
}
