package com.example.ocrtranslator

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import kotlin.math.abs

/**
 * Manages two floating WindowManager layers:
 *  1. A draggable button the user taps to trigger screen capture + translation.
 *  2. A full-screen [TranslationOverlayView] drawn on top of the live screen.
 *
 * Uses applicationContext for inflation to avoid Material3 theme-resolution
 * failures that occur with ContextThemeWrapper in a WindowManager/Service context.
 */
class FloatingOverlay(
    context: Context,
    private val onCaptureRequested: () -> Unit
) {
    companion object {
        private const val TAG = "FloatingOverlay"
        private const val DRAG_THRESHOLD = 8f
    }

    private val appContext: Context = context.applicationContext
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ── FAB ───────────────────────────────────────────────────────────────────

    private var fabView: View? = null
    private var fabParams: WindowManager.LayoutParams? = null
    private var fabButton: ImageButton? = null
    private var fabSpinner: ProgressBar? = null

    private var isDragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0

    // ── Translation overlay ───────────────────────────────────────────────────

    private var translationOverlay: TranslationOverlayView? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun show() {
        if (fabView != null) return

        val root: View
        try {
            root = LayoutInflater.from(appContext).inflate(R.layout.overlay_fab, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate overlay_fab layout", e)
            Toast.makeText(appContext, "懸浮按鈕初始化失敗：${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        fabButton = root.findViewById(R.id.fabCapture)
        fabSpinner = root.findViewById(R.id.fabLoading)
        fabView = root

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }
        fabParams = lp
        root.setOnTouchListener { v, event -> handleFabTouch(v, event) }

        try {
            windowManager.addView(root, lp)
            Log.i(TAG, "FAB overlay added successfully")
            Toast.makeText(
                appContext,
                "✓ 懸浮按鈕已出現！找螢幕左側紫色圓形按鈕，點它開始翻譯",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add FAB overlay to WindowManager", e)
            Toast.makeText(appContext, "懸浮視窗失敗（無權限？）：${e.message}", Toast.LENGTH_LONG).show()
            fabView = null
            fabButton = null
            fabSpinner = null
        }
    }

    /** Dims the button and shows a spinner while the Gemini API call is in flight. */
    fun showLoading() {
        fabButton?.apply {
            isEnabled = false
            alpha = 0.45f
        }
        fabSpinner?.visibility = View.VISIBLE
    }

    /** Restores the button to its interactive state. */
    fun hideLoading() {
        fabButton?.apply {
            isEnabled = true
            alpha = 1f
        }
        fabSpinner?.visibility = View.GONE
    }

    fun showTranslations(blocks: List<TextBlock>) {
        dismissTranslationOverlay()

        val dm = appContext.resources.displayMetrics
        val overlay = TranslationOverlayView(appContext).apply {
            setBlocks(blocks)
            onDismiss = { dismissTranslationOverlay() }
        }
        translationOverlay = overlay

        val lp = WindowManager.LayoutParams(
            dm.widthPixels,
            dm.heightPixels,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try {
            windowManager.addView(overlay, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add translation overlay", e)
        }
    }

    fun showError(message: String) {
        hideLoading()
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }

    fun dismiss() {
        dismissTranslationOverlay()
        fabView?.let {
            try { windowManager.removeView(it) } catch (ignored: Exception) {}
            fabView = null
            fabButton = null
            fabSpinner = null
        }
        fabParams = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun dismissTranslationOverlay() {
        translationOverlay?.let {
            try { windowManager.removeView(it) } catch (ignored: Exception) {}
            translationOverlay = null
        }
    }

    private fun handleFabTouch(v: View, event: MotionEvent): Boolean {
        val lp = fabParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                initialTouchX = event.rawX; initialTouchY = event.rawY
                initialParamX = lp.x; initialParamY = lp.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                    isDragging = true
                }
                if (isDragging) {
                    lp.x = (initialParamX + dx).toInt()
                    lp.y = (initialParamY + dy).toInt()
                    try { windowManager.updateViewLayout(v, lp) } catch (ignored: Exception) {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) onCaptureRequested()
            }
        }
        return true
    }
}
