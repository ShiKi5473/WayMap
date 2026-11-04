package com.waymap.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Ready-made marker bitmaps for [MapIcon] factories. */
object MarkerIcons {

    /**
     * A filled circle marker with a white border and a soft drop shadow, so it stays
     * legible over any basemap. When [ringColor] is non-null an extra thick ring is
     * drawn outside the border to flag a selected marker. [sizePx] is the whole
     * bitmap; the dot is inset to leave room for the shadow and ring.
     */
    fun dot(fill: Int, sizePx: Int, ringColor: Int? = null): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = sizePx / 2f

        val shadowPad = sizePx * 0.08f
        val whiteBorder = sizePx * 0.05f
        val ringWidth = if (ringColor != null) sizePx * 0.10f else 0f
        val dotRadius = c - shadowPad - ringWidth

        // Filled dot, rendered together with its drop shadow.
        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.setShadowLayer(shadowPad, 0f, shadowPad * 0.4f, Color.argb(120, 0, 0, 0))
        canvas.drawCircle(c, c, dotRadius, paint)
        paint.clearShadowLayer()

        // White border hugging the dot edge.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = whiteBorder
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, dotRadius, paint)

        // Selection ring, drawn just outside the white border.
        if (ringColor != null) {
            paint.strokeWidth = ringWidth
            paint.color = ringColor
            canvas.drawCircle(c, c, dotRadius + whiteBorder / 2f + ringWidth / 2f, paint)
        }
        return bmp
    }
}
