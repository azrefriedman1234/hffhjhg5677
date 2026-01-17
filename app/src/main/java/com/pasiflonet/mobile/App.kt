package com.pasiflonet.mobile

import com.pasiflonet.mobile.utils.CrashLogger

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // האתחול עבר ל-MainActivity.checkApiAndInit() 
        // כדי לאפשר הזנת API ID ו-Hash ידנית
    }
}
