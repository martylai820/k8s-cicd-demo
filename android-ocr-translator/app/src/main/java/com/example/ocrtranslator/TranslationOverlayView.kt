package com.example.ocrtranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen transparent overlay that draws translation blocks directly over
 * the original text positions, like Google Lens translate mode.
 *
 * Coordinates from [TextBlock] are fractions of the image dimensions (0.0–1.0)
 * and map directly to this view's pixel dimensions.
 */
class TranslationOverlayView(context: Context) : View(context) {

    var onDismiss: (() -> Unit)? = null

    private var blocks: List<TextBlock> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 251, 210)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A73E8")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D1A")
        typeface = Typeface.DEFAULT_BOLD
    }

    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val hintTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }

    fun setBlocks(newBlocks: List<TextBlock>) {
        blocks = newBlocks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()

        if (blocks.isEmpty()) {
            drawCenteredMessage(canvas, context.getString(R.string.no_text_detected), vw, vh)
            return
        }

        blocks.forEach { block ->
            drawBlock(canvas, block, vw, vh)
        }

        drawDismissHint(canvas, vw)
    }

    private fun drawBlock(canvas: Canvas, block: TextBlock, vw: Float, vh: Float) {
        val left = (block.x * vw).coerceIn(0f, vw - 10f)
        val top = (block.y * vh).coerceIn(0f, vh - 10f)
        val bw = (block.w * vw).coerceAtLeast(50f)
        val bh = (block.h * vh).coerceAtLeast(20f)
        val right = (left + bw).coerceAtMost(vw)
        val bottom = (top + bh).coerceAtMost(vh)

        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
        canvas.drawRoundRect(rect, 10f, 10f, borderPaint)

        val availW = ((right - left) - 14f).toInt().coerceAtLeast(1)
        val tp = TextPaint(textPaint).apply {
            textSize = ((bottom - top) * 0.62f).coerceIn(24f, 52f)
        }

        val sl = StaticLayout.Builder
            .obtain(block.translation, 0, block.translation.length, tp, availW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()

        canvas.save()
        // Vertically centre the text inside the block
        canvas.translate(left + 7f, top + ((bottom - top) - sl.height) / 2f)
        sl.draw(canvas)
        canvas.restore()
    }

    private fun drawDismissHint(canvas: Canvas, vw: Float) {
        val hint = context.getString(R.string.tap_to_dismiss)
        val tw = hintTextPaint.measureText(hint)
        val hPad = 20f
        val vPad = 12f
        val hintH = 40f
        val top = 28f

        canvas.drawRoundRect(
            vw / 2f - tw / 2f - hPad,
            top,
            vw / 2f + tw / 2f + hPad,
            top + hintH + vPad * 2f,
            (hintH + vPad * 2f) / 2f,
            (hintH + vPad * 2f) / 2f,
            hintBgPaint
        )
        canvas.drawText(hint, vw / 2f, top + vPad + hintH * 0.75f, hintTextPaint)
    }

    private fun drawCenteredMessage(canvas: Canvas, msg: String, vw: Float, vh: Float) {
        val tw = hintTextPaint.measureText(msg)
        val hPad = 24f; val vPad = 16f; val hintH = 44f
        val cx = vw / 2f; val cy = vh / 2f
        canvas.drawRoundRect(
            cx - tw / 2f - hPad, cy - hintH / 2f - vPad,
            cx + tw / 2f + hPad, cy + hintH / 2f + vPad,
            16f, 16f, hintBgPaint
        )
        canvas.drawText(msg, cx, cy + hintH * 0.3f, hintTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onDismiss?.invoke()
        }
        return true
    }
}
