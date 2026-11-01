package com.emberlily.app.emulation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Bitmap ownership stays here; drawing never references an mGBA allocation. */
class FrameBuffer {
    private var bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val source = Rect(0, 0, 240, 160)
    @Volatile var invalidate: (() -> Unit)? = null
    @Volatile var viewportWidth: Int = 0
        private set
    @Volatile var viewportHeight: Int = 0
        private set

    fun resizeViewport(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
    }

    @Synchronized fun clear() {
        bitmap.eraseColor(android.graphics.Color.BLACK)
        invalidate?.invoke()
    }

    @Synchronized fun publish(pixels: IntArray, width: Int = 240, height: Int = 160) {
        require(width in 240..640 && height in 160..480 && pixels.size == width * height)
        if (bitmap.width != width || bitmap.height != height) {
            // Do not recycle a bitmap that a hardware Canvas may still hold for an in-flight draw.
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            source.set(0, 0, width, height)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        invalidate?.invoke()
    }

    @Synchronized fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val saved = canvas.save()
        try {
            // AndroidView can draw on a canvas whose clip includes Compose siblings.
            // Clear only the game viewport, never the companion's Material surface.
            canvas.clipRect(0, 0, width, height)
            canvas.drawColor(android.graphics.Color.BLACK)
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val left = (width - bitmap.width * scale) / 2
            val top = (height - bitmap.height * scale) / 2
            canvas.drawBitmap(bitmap, source, RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), paint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}
