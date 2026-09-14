package com.snsupercanvas.canvas

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * [TextMeasurer] on Android's StaticLayout, in world units: text laid out at
 * its world font size and width. [TextPainter] lays text out through the same
 * [textLayout], scaled to the zoom, so what is measured is what is drawn.
 */
internal class AndroidTextMeasurer : TextMeasurer {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)

    override fun height(
        text: String,
        width: Double,
        fontSize: Double,
    ): Double {
        paint.textSize = fontSize.toFloat()
        return textLayout(text, paint, width.toFloat()).height.toDouble()
    }
}

/** How SuperCanvas lays text out, for measuring and for drawing alike. */
internal fun textLayout(
    text: String,
    paint: TextPaint,
    width: Float,
): StaticLayout =
    StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, TextElements.LINE_SPACING.toFloat())
        .setIncludePad(false)
        .build()
