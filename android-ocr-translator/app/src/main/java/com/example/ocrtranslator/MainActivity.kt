package com.example.ocrtranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ocrtranslator.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Main entry point of the OCR Translator app.
 *
 * Responsibilities:
 *  - Allow the user to enter and persist their Gemini API key.
 *  - Allow the user to select a target translation language.
 *  - Request SYSTEM_ALERT_WINDOW and POST_NOTIFICATIONS permissions.
 *  - Launch the MediaProjection consent dialog and start/stop [ScreenCaptureService].
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsManager: SettingsManager

    // ViewModel holds UI state across rotation
    private val viewModel: MainViewModel by viewModels()

    // ── Permission / result launchers ──────────────────────────────────────────

    /** Requests POST_NOTIFICATIONS permission (Android 13+). */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showToast(getString(R.string.notification_permission_denied))
            }
        }

    /** Opens the "Draw over other apps" settings screen. */
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!Settings.canDrawOverlays(this)) {
                showToast(getString(R.string.overlay_permission_required))
            } else {
                // Permission just granted — re-check whether we can start now
                attemptStartCapture()
            }
        }

    /** Receives the MediaProjection consent result. */
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                Log.d(TAG, "MediaProjection consent granted")
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "MediaProjection consent denied")
                showToast(getString(R.string.media_projection_denied))
                viewModel.setServiceRunning(false)
            }
        }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)

        setupApiKeyField()
        setupLanguageSpinner()
        setupStartStopButton()
        observeViewModel()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Reflect live service state in case user navigated away and back
        val running = ScreenCaptureService.isRunning
        viewModel.setServiceRunning(running)
    }

    // ── UI Setup ───────────────────────────────────────────────────────────────

    private fun setupApiKeyField() {
        // Pre-populate from saved settings
        binding.etApiKey.setText(settingsManager.apiKey)
    }

    private fun setupLanguageSpinner() {
        val languages = SettingsManager.SUPPORTED_LANGUAGES
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerLanguage.adapter = adapter

        // Restore previously selected language
        val savedIndex = languages.indexOf(settingsManager.targetLanguage)
        if (savedIndex >= 0) binding.spinnerLanguage.setSelection(savedIndex)

        binding.spinnerLanguage.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>, view: View?, position: Int, id: Long
                ) {
                    settingsManager.targetLanguage = languages[position]
                }

                override fun onNothingSelected(parent: AdapterView<*>) = Unit
            }
    }

    private fun setupStartStopButton() {
        binding.btnStartStop.setOnClickListener {
            // Persist the API key first
            val apiKey = binding.etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                binding.tilApiKey.error = getString(R.string.error_api_key_empty)
                return@setOnClickListener
            }
            binding.tilApiKey.error = null
            settingsManager.apiKey = apiKey

            if (viewModel.isServiceRunning.value) {
                stopCaptureService()
            } else {
                attemptStartCapture()
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isServiceRunning.collect { running ->
                    updateButtonState(running)
                }
            }
        }
    }

    // ── Permissions & Service Control ─────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Checks SYSTEM_ALERT_WINDOW permission and then initiates MediaProjection flow.
     * Called when the user taps Start.
     */
    private fun attemptStartCapture() {
        if (!Settings.canDrawOverlays(this)) {
            showToast(getString(R.string.requesting_overlay_permission))
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        viewModel.setServiceRunning(true)
        val intent = ScreenCaptureService.buildStartIntent(
            context = this,
            resultCode = resultCode,
            data = data
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCaptureService() {
        viewModel.setServiceRunning(false)
        val intent = ScreenCaptureService.buildStopIntent(this)
        startService(intent)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun updateButtonState(running: Boolean) {
        binding.btnStartStop.text = getString(
            if (running) R.string.stop_capture else R.string.start_capture
        )
        binding.btnStartStop.setIconResource(
            if (running) R.drawable.ic_stop else R.drawable.ic_translate
        )
        binding.tvStatus.text = getString(
            if (running) R.string.status_running else R.string.status_idle
        )
        binding.tvStatus.setTextColor(
            getColor(if (running) R.color.md_green_600 else R.color.md_grey_600)
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
