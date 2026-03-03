package com.shotspotter

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class Analyzer(
    private val outputWidth: Int = 320,
    private val outputHeight: Int = 240,
    private val onFrameReady: (GrayFrame) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height

            if (!buffer.hasArray() && !buffer.isDirect) {
                return
            }

            val out = ByteArray(outputWidth * outputHeight)
            for (oy in 0 until outputHeight) {
                val sy = (oy * height) / outputHeight
                for (ox in 0 until outputWidth) {
                    val sx = (ox * width) / outputWidth
                    val index = sy * rowStride + sx * pixelStride
                    out[oy * outputWidth + ox] = buffer.get(index)
                }
            }

            onFrameReady(
                GrayFrame(
                    width = outputWidth,
                    height = outputHeight,
                    pixels = out,
                    timestampNanos = image.imageInfo.timestamp
                )
            )
        } finally {
            image.close()
        }
    }
}
