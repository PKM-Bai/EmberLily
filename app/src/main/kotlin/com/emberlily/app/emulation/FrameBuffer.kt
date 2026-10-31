package com.emberlily.app.emulation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Bitmap ownership stays here; drawing never references an mGBA allocation. */
class FrameBuffer {
    private val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val source = Rect(0, 0, 240, 160)
    @Volatile var invalidate: (() -> Unit)? = null

    @Synchronized fun clear() {
        bitmap.eraseColor(android.graphics.Color.BLACK)
        invalidate?.invoke()
    }

    @Synchronized fun publish(pixels: IntArray) {
        bitmap.setPixels(pixels, 0, 240, 0, 0, 240, 160)
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
            val scale = minOf(width / 240f, height / 160f)
            val left = (width - 240 * scale) / 2
            val top = (height - 160 * scale) / 2
            canvas.drawBitmap(bitmap, source, RectF(left, top, left + 240 * scale, top + 160 * scale), paint)
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}
