package com.opencode.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Prefs.autoStart(c) && Prefs.hasAny(c)) {
            try {
                c.startForegroundService(Intent(c, SseService::class.java).setAction(SseService.ACTION_START))
            } catch (_: Exception) {
            }
        }
    }
}
