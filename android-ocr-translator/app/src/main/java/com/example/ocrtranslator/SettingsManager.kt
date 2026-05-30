package com.example.ocrtranslator

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent app settings via SharedPreferences.
 * Stores the Gemini API key and user-selected target language.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ocr_translator_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_TARGET_LANGUAGE = "target_language"

        // Supported target languages displayed in the UI
        val SUPPORTED_LANGUAGES = listOf(
            "Traditional Chinese",
            "Simplified Chinese",
            "English",
            "Japanese",
            "Korean",
            "French",
            "Spanish",
            "German",
            "Portuguese",
            "Arabic"
        )

        val DEFAULT_LANGUAGE = SUPPORTED_LANGUAGES[0] // Traditional Chinese
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the stored Gemini API key, or empty string if not set. */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** Returns the stored target language, defaulting to Traditional Chinese. */
    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value).apply()

    /** Returns true if a non-empty API key has been configured. */
    fun hasApiKey(): Boolean = apiKey.isNotEmpty()
}
