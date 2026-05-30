package com.example.ocrtranslator

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.abs

/**
 * Manages two floating WindowManager layers:
 *  1. A draggable FAB the user taps to trigger screen capture + translation.
 *  2. A full-screen [TranslationOverlayView] drawn on top of the live screen,
 *     with each text block replaced by its translation — like Google Lens.
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
    private val themedContext: Context = ContextThemeWrapper(context, R.style.Theme_OCRTranslator)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ── FAB ───────────────────────────────────────────────────────────────────

    private var fabView: View? = null
    private var fabParams: WindowManager.LayoutParams? = null
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

        val fab = LayoutInflater.from(themedContext).inflate(R.layout.overlay_fab, null)
        fabView = fab

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
        fab.setOnTouchListener { v, event -> handleFabTouch(v, event) }

        try {
            windowManager.addView(fab, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add FAB overlay", e)
        }
    }

    /** Dims the FAB and shows a spinner while the Gemini API call is in flight. */
    fun showLoading() {
        fabView?.apply {
            findViewById<FloatingActionButton>(R.id.fabCapture)?.apply {
                isEnabled = false
                alpha = 0.45f
            }
            findViewById<ProgressBar>(R.id.fabLoading)?.visibility = View.VISIBLE
        }
    }

    /** Restores the FAB to its interactive state. */
    fun hideLoading() {
        fabView?.apply {
            findViewById<FloatingActionButton>(R.id.fabCapture)?.apply {
                isEnabled = true
                alpha = 1f
            }
            findViewById<ProgressBar>(R.id.fabLoading)?.visibility = View.GONE
        }
    }

    /**
     * Shows a full-screen overlay with each [TextBlock] drawn directly over
     * the corresponding text region. Tap anywhere to dismiss.
     */
    fun showTranslations(blocks: List<TextBlock>) {
        dismissTranslationOverlay()

        val dm = themedContext.resources.displayMetrics
        val overlay = TranslationOverlayView(themedContext).apply {
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
            try { windowManager.removeView(it) } catch (_: Exception) {}
            fabView = null
        }
        fabParams = null
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun dismissTranslationOverlay() {
        translationOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
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
                    try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) onCaptureRequested()
            }
        }
        return true
    }
}
