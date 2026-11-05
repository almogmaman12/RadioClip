package com.almog.radioclip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Handles the "Copy" button on a received-text notification. */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val copied = RadioSyncService.activeInstance?.copyHistoryText(id) == true
        Toast.makeText(
            context,
            if (copied) "Copied" else "That text is no longer available",
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val ACTION_COPY = "com.almog.radioclip.action.COPY_TEXT"
        const val EXTRA_ID = "payload_id"
    }
}
