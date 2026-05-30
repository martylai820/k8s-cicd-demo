package com.example.ocrtranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that:
 *  1. Creates a [MediaProjection] virtual display to capture the screen.
 *  2. Shows a draggable floating overlay button via [FloatingOverlay].
 *  3. On user tap: grabs the latest [ImageReader] frame, converts it to a [Bitmap],
 *     sends it to [GeminiApiClient], and displays the translation in [FloatingOverlay].
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"

        private const val ACTION_START = "com.example.ocrtranslator.START"
        private const val ACTION_STOP = "com.example.ocrtranslator.STOP"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** True while the service is actively running. Read from [MainActivity.onResume]. */
        @Volatile
        var isRunning = false
            private set

        fun buildStartIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        fun buildStopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
    }

    // ── Dependencies ───────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settingsManager: SettingsManager
    private lateinit var geminiClient: GeminiApiClient
    private lateinit var floatingOverlay: FloatingOverlay

    // ── MediaProjection state ──────────────────────────────────────────────────

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Screen metrics cached at startup
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // ── Service lifecycle ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        geminiClient = GeminiApiClient()

        floatingOverlay = FloatingOverlay(this) {
            // Called when the user taps the floating capture button
            captureAndTranslate()
        }

        createNotificationChannel()
        resolveScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        floatingOverlay.dismiss()
        tearDownProjection()
        super.onDestroy()
    }

    // ── Start / Stop handlers ──────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Invalid MediaProjection data; stopping service.")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, null)

        setupImageReader()
        floatingOverlay.show()

        Log.i(TAG, "Screen capture service started. Screen: ${screenWidth}x${screenHeight}")
    }

    private fun handleStop() {
        Log.i(TAG, "Stop command received")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── MediaProjection setup ──────────────────────────────────────────────────

    private fun setupImageReader() {
        // Capture at a reduced resolution to keep API payload small
        val captureWidth = screenWidth.coerceAtMost(1080)
        val captureHeight = (screenHeight * (captureWidth.toFloat() / screenWidth)).toInt()

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            captureWidth,
            captureHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun tearDownProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped externally")
            isRunning = false
            floatingOverlay.dismiss()
            tearDownProjection()
            stopSelf()
        }
    }

    // ── Screen capture + translation ───────────────────────────────────────────

    /**
     * Acquires the latest frame from [ImageReader], converts it to a [Bitmap],
     * and calls the Gemini API for OCR/translation.
     */
    private fun captureAndTranslate() {
        val reader = imageReader
        if (reader == null) {
            floatingOverlay.showError(getString(R.string.error_not_ready))
            return
        }

        val apiKey = settingsManager.apiKey
        if (apiKey.isBlank()) {
            floatingOverlay.showError(getString(R.string.error_api_key_empty))
            return
        }

        floatingOverlay.showLoading()

        serviceScope.launch(Dispatchers.IO) {
            val bitmap = acquireLatestBitmap(reader)
            if (bitmap == null) {
                launch(Dispatchers.Main) {
                    floatingOverlay.showError(getString(R.string.error_capture_failed))
                }
                return@launch
            }

            val result = geminiClient.ocrAndTranslate(
                bitmap = bitmap,
                apiKey = apiKey,
                targetLanguage = settingsManager.targetLanguage
            )
            bitmap.recycle()

            launch(Dispatchers.Main) {
                when (result) {
                    is GeminiApiClient.Result.Success -> floatingOverlay.showResult(result.text)
                    is GeminiApiClient.Result.Error -> floatingOverlay.showError(result.message)
                }
            }
        }
    }

    /**
     * Tries to acquire the most recent [Image] from [reader] and converts it to a [Bitmap].
     * Returns null if no frame is available yet or on any error.
     */
    private fun acquireLatestBitmap(reader: ImageReader): Bitmap? {
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: run {
                Log.w(TAG, "No image available in reader")
                return null
            }

            val planes = image.planes
            if (planes.isEmpty()) return null

            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to exact screen size, removing any row-padding artefacts
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                    bitmap.recycle()
                }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring image", e)
            null
        } finally {
            image?.close()
        }
    }

    // ── Notification helpers ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            buildStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_translate)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    private fun resolveScreenMetrics() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDensity = dm.densityDpi
    }
}
