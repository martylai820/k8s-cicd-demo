package com.example.ocrtranslator

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Installs a global uncaught-exception handler that persists the stack trace
 * to SharedPreferences before the process dies. [MainActivity] reads and
 * displays it on next launch so crashes are diagnosable without logcat.
 */
class App : Application() {

    companion object {
        const val PREFS_NAME = "ocr_translator_prefs"
        const val KEY_LAST_CRASH = "last_crash"
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, sw.toString().take(6000))
                    .commit() // synchronous — the process is about to die
            } catch (ignored: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
