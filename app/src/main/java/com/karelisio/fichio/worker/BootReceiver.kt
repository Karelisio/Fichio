package com.karelisio.fichio.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules background work (trash purge, search index) after a reboot,
 * since periodic WorkManager jobs do not survive a device restart otherwise.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkScheduler.schedule(context)
    }
}
