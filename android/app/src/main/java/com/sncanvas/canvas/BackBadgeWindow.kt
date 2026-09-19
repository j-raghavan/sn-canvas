package com.sncanvas.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * The back badge (#34): a small floating window over the note a followed link
 * opened, in the look of the firmware's own return badge (a black box with a
 * white label and an outlined arrow), that takes the user back to Canvas in one
 * tap. It can't be a view in Canvas: the note covers Canvas as it opens, and a
 * plugin has no place on a note's screen. The plugin host may draw over other
 * windows, so the badge is its own window, taking touches on itself only.
 *
 * It watches which note is open ([openNote], checked on the main looper, which
 * runs while Canvas is covered, unlike JS timers) and goes once the user is
 * somewhere else ([BackBadgeWatch]). Main thread only.
 */
class BackBadgeWindow(
    private val context: Context,
    private val openNote: () -> String?,
    private val onTap: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Its measures are taken off this 300dpi screen; any other scales them, so it sits and reads the same.
    private val scale = context.resources.displayMetrics.density / REFERENCE_DENSITY
    private var badge: View? = null
    private var verdict: BackBadgeWatch? = null

    private val watch =
        object : Runnable {
            override fun run() {
                val current = verdict ?: return
                if (current.isStillWanted(openNote())) {
                    main.postDelayed(this, WATCH_MS)
                } else {
                    Log.i(TAG, "left the linked note; back badge hidden")
                    hide()
                }
            }
        }

    /** Shows the badge reading [label] over [note], replacing any shown before. */
    fun show(
        label: String,
        note: String,
    ) {
        hide()
        val view = BadgeView(context, label)
        try {
            windowManager.addView(view, layoutParams())
        } catch (error: WindowManager.BadTokenException) {
            // A firmware that stops the plugin host drawing over notes: the sidebar still reopens Canvas.
            Log.w(TAG, "back badge could not be shown: $error")
            return
        } catch (error: SecurityException) {
            Log.w(TAG, "back badge could not be shown: $error")
            return
        }
        badge = view
        verdict = BackBadgeWatch(note, ARRIVAL_CHECKS)
        main.postDelayed(watch, WATCH_MS)
    }

    fun hide() {
        main.removeCallbacks(watch)
        verdict = null
        val view = badge ?: return
        badge = null
        try {
            windowManager.removeView(view)
        } catch (error: IllegalArgumentException) {
            // Already gone with the window it was in.
            Log.w(TAG, "back badge could not be hidden: $error")
        }
    }

    private fun layoutParams() =
        WindowManager
            .LayoutParams(
                (WIDTH_PX * scale).toInt(),
                (HEIGHT_PX * scale).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Touches outside the badge go on to the note.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (LEFT_PX * scale).toInt()
                y = (TOP_PX * scale).toInt()
            }

    private inner class BadgeView(
        context: Context,
        private val label: String,
    ) : View(context) {
        private val box = Paint().apply { color = Color.BLACK }
        private val text =
            TextPaint().apply {
                color = Color.WHITE
                textSize = LABEL_SIZE_PX
                isAntiAlias = true
            }
        private val arrowFill = Paint().apply { color = Color.WHITE }
        private val arrowEdge =
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = ARROW_EDGE_PX
                isAntiAlias = true
            }
        private val boxRect = RectF()
        private val arrow = Path()

        override fun onDraw(canvas: Canvas) {
            // Drawn in the reference screen's measures, scaled to this one.
            canvas.scale(scale, scale)
            val width = WIDTH_PX.toFloat()
            val height = HEIGHT_PX.toFloat()
            boxRect.set(0f, 0f, width - ARROW_OVERHANG_PX, height)
            canvas.drawRoundRect(boxRect, CORNER_PX, CORNER_PX, box)
            // The firmware's arrow: pointing left, its tail running past the box's right edge.
            val tipX = width - ARROW_TIP_FROM_RIGHT_PX
            // A long note name (the device names new ones by date and time) ends in an ellipsis before the arrow.
            val shown = TextUtils.ellipsize(label, text, tipX - LABEL_LEFT_PX - LABEL_GAP_PX, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, LABEL_LEFT_PX, height / 2f + LABEL_DROP_PX, text)
            val midY = height / 2f
            arrow.reset()
            ARROW.forEachIndexed { index, (dx, dy) ->
                if (index == 0) arrow.moveTo(tipX + dx, midY + dy) else arrow.lineTo(tipX + dx, midY + dy)
            }
            arrow.close()
            canvas.drawPath(arrow, arrowFill)
            canvas.drawPath(arrow, arrowEdge)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                hide()
                onTap()
            }
            return true
        }
    }

    private companion object {
        const val TAG = "SNCANVAS"
        const val WATCH_MS = 500L

        /** How long a linked note may take to open before the badge gives up on it: three seconds, as ReturnTrip waits. */
        const val ARRIVAL_CHECKS = 6

        // Measured off the firmware's own badge on a 1920x2560, 300dpi screen (density 1.875), moved right of the
        // note's toolbar, which it would cover.
        const val REFERENCE_DENSITY = 1.875f
        const val LEFT_PX = 130
        const val TOP_PX = 25
        const val WIDTH_PX = 360
        const val HEIGHT_PX = 85
        const val CORNER_PX = 10f
        const val LABEL_SIZE_PX = 44f
        const val LABEL_LEFT_PX = 40f
        const val LABEL_GAP_PX = 12f
        const val LABEL_DROP_PX = 15f
        const val ARROW_OVERHANG_PX = 30f
        const val ARROW_TIP_FROM_RIGHT_PX = 95f
        const val ARROW_EDGE_PX = 5f

        // The arrow's corners, from its tip.
        val ARROW =
            listOf(0f to 0f, 36f to -30f, 36f to -12f, 80f to -12f, 80f to 12f, 36f to 12f, 36f to 30f)
    }
}
