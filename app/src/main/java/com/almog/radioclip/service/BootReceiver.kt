package com.almog.radioclip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.almog.radioclip.settings.SettingsStore

/** Starts the sync service after the phone boots, if the user turned that on in Settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore.get(context).settings.value.startOnBoot) return
        try {
            RadioSyncService.start(context)
        } catch (e: Exception) {
            // Some Android versions refuse to start a foreground service in the background.
            Log.w("BootReceiver", "Could not start RadioClip after boot", e)
        }
    }
}
