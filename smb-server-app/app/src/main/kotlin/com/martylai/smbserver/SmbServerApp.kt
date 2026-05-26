package com.martylai.smbserver

import android.app.Application
import androidx.multidex.MultiDexApplication

class SmbServerApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Future: init Timber logging, Koin, etc.
    }
}
