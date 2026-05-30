package com.example.ocrtranslator

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper

/**
 * Manages two floating views anchored to the WindowManager:
 *  1. A small draggable FAB-style button the user taps to trigger a capture.
 *  2. A result card that slides in with the OCR / translation text.
 *
 * Call [show] to attach the button, [dismiss] to remove everything.
 *
 * A [ContextThemeWrapper] is used so that Material 3 theme attributes
 * (colorPrimary, colorSurface, etc.) resolve correctly outside of any Activity.
 */
class FloatingOverlay(
    context: Context,
    private val onCaptureRequested: () -> Unit
) {

    companion object {
        private const val TAG = "FloatingOverlay"
        /** Minimum movement in px before a touch is treated as a drag. */
        private const val DRAG_THRESHOLD = 8f
    }

    /** Themed context for inflating Material 3 layouts from a Service. */
    private val themedContext: Context =
        ContextThemeWrapper(context, R.style.Theme_OCRTranslator)

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    // ── Floating action button ───────────────────────────────────────────────

    private var fabView: View? = null
    private var fabParams: WindowManager.LayoutParams? = null

    // Drag state
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDragging = false

    // ── Result card ──────────────────────────────────────────────────────────

    private var resultView: View? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Inflates and attaches the floating capture button to the screen.
     * Safe to call multiple times — subsequent calls are no-ops if already shown.
     */
    fun show() {
        if (fabView != null) return

        val inflater = LayoutInflater.from(themedContext)
        val fab = inflater.inflate(R.layout.overlay_fab, null)
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

    /**
     * Shows a loading spinner in the result card, replacing any previous result.
     */
    fun showLoading() {
        ensureResultView()
        resultView?.let { rv ->
            rv.findViewById<ProgressBar>(R.id.progressBar).visibility = View.VISIBLE
            rv.findViewById<ScrollView>(R.id.scrollView).visibility = View.GONE
            rv.findViewById<TextView>(R.id.tvResult).text = ""
            rv.visibility = View.VISIBLE
        }
    }

    /**
     * Populates the result card with [text] and makes it visible.
     */
    fun showResult(text: String) {
        ensureResultView()
        resultView?.let { rv ->
            rv.findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
            val scrollView = rv.findViewById<ScrollView>(R.id.scrollView)
            scrollView.visibility = View.VISIBLE
            rv.findViewById<TextView>(R.id.tvResult).text = text
            scrollView.post { scrollView.scrollTo(0, 0) }
            rv.visibility = View.VISIBLE
        }
    }

    /**
     * Shows an error message in the result card.
     */
    fun showError(message: String) {
        ensureResultView()
        resultView?.let { rv ->
            rv.findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
            val scrollView = rv.findViewById<ScrollView>(R.id.scrollView)
            scrollView.visibility = View.VISIBLE
            rv.findViewById<TextView>(R.id.tvResult).text =
                themedContext.getString(R.string.error_prefix, message)
            rv.visibility = View.VISIBLE
        }
    }

    /**
     * Hides the result card without removing the FAB.
     */
    fun hideResult() {
        resultView?.visibility = View.GONE
    }

    /**
     * Removes all overlay views from the WindowManager.
     */
    fun dismiss() {
        fabView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            fabView = null
        }
        resultView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            resultView = null
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun ensureResultView() {
        if (resultView != null) return

        val inflater = LayoutInflater.from(themedContext)
        val rv = inflater.inflate(R.layout.overlay_translation, null)
        resultView = rv

        val displayWidth = themedContext.resources.displayMetrics.widthPixels
        val cardWidth = (displayWidth * 0.92f).toInt()

        val lp = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        rv.visibility = View.GONE

        // Close button inside the card
        rv.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener {
            hideResult()
        }

        try {
            windowManager.addView(rv, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add result overlay", e)
        }
    }

    /**
     * Touch listener that distinguishes a drag from a click on the FAB.
     * Returns true to consume the event.
     */
    private fun handleFabTouch(v: View, event: MotionEvent): Boolean {
        val lp = fabParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                initialParamX = lp.x
                initialParamY = lp.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (!isDragging &&
                    (kotlin.math.abs(dx) > DRAG_THRESHOLD ||
                            kotlin.math.abs(dy) > DRAG_THRESHOLD)
                ) {
                    isDragging = true
                }
                if (isDragging) {
                    lp.x = (initialParamX + dx).toInt()
                    lp.y = (initialParamY + dy).toInt()
                    try {
                        windowManager.updateViewLayout(v, lp)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateViewLayout failed", e)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Treat as a tap — trigger capture
                    onCaptureRequested()
                }
            }
        }
        return true
    }
}
