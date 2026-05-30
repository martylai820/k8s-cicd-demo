package com.example.ocrtranslator

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for [MainActivity].
 * Survives configuration changes and holds the UI state for the start/stop button.
 */
class MainViewModel : ViewModel() {

    private val _isServiceRunning = MutableStateFlow(false)

    /** Emits true when [ScreenCaptureService] is active. */
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    /** Update the running state (called from Activity based on service lifecycle). */
    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}
