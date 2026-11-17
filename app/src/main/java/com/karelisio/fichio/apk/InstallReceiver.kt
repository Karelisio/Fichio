package com.karelisio.fichio.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PackageInstaller session status callback: launches the system confirmation when user action
 * is pending and publishes success / failure through [ApkInstaller.state].
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ApkInstaller.onStatus(context, intent)
    }
}
