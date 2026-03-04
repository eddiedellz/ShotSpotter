package com.shotspotter

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class Analyzer(
    private val getRoi: () -> RoiNorm,
    private val onFrameReady: (GrayFrame) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val uprightBitmap = imageProxy.toUprightBitmap()
            val roi = getRoi()
            val roiBitmap = cropToRoi(uprightBitmap, roi)
            val grayPixels = roiBitmap.toGrayscaleByteArray()

            onFrameReady(
                GrayFrame(
                    width = roiBitmap.width,
                    height = roiBitmap.height,
                    pixels = grayPixels,
                    timestampNanos = imageProxy.imageInfo.timestamp,
                    roi = roi
                )
            )
        } finally {
            imageProxy.close()
        }
    }

    private fun cropToRoi(bitmap: Bitmap, roi: RoiNorm): Bitmap {
        val bounds = roi.toPixelBounds(bitmap.width, bitmap.height)
        val cropWidth = bounds.rightExclusive - bounds.left
        val cropHeight = bounds.bottomExclusive - bounds.top
        return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, cropWidth, cropHeight)
    }

    private fun Bitmap.toGrayscaleByteArray(): ByteArray {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        val grayscale = ByteArray(width * height)

        for (i in argb.indices) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            grayscale[i] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
        }

        return grayscale
    }
}
