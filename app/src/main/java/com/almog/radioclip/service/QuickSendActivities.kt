package com.almog.radioclip.service

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.almog.radioclip.core.FileNames
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.transport.wifi.WifiEngine
import java.io.File

/**
 * Base for the small see-through screens that send something and close again: connects to the
 * sync service (starting it if needed), waits until [canRun], runs [perform] once, then finishes.
 */
abstract class QuickServiceActivity : ComponentActivity() {
    private var service: RadioSyncService? = null
    private var bound = false
    private var done = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? RadioSyncService.LocalBinder)?.getService()
            tryRun()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Extra condition before running (for example "window has focus"). */
    protected open fun canRun(): Boolean = true

    protected abstract fun perform(service: RadioSyncService)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadioSyncService.start(this)
        bound = bindService(Intent(this, RadioSyncService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finishWith("RadioClip could not start")
    }

    protected fun tryRun() {
        val s = service ?: return
        if (done || !canRun()) return
        done = true
        perform(s)
    }

    protected fun finishWith(message: String) {
        runOnUiThread {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** Reports the outcome of a send to the user and closes. */
    protected fun reportResult(result: Result<TransportType>, what: String) {
        val failure = result.exceptionOrNull()
        finishWith(
            when {
                failure != null -> "Could not send: ${failure.message}"
                result.getOrNull() == TransportType.BLE_GATT -> "$what sent over Bluetooth"
                else -> "$what sent over Wi-Fi Direct"
            }
        )
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}

/** Opened from the notification's "Send clipboard" button. */
class SendClipboardActivity : QuickServiceActivity() {
    private var hasFocus = false

    // Android only lets an app read the clipboard while it has window focus, so wait for it.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            this.hasFocus = true
            tryRun()
        }
    }

    override fun canRun(): Boolean = hasFocus

    override fun perform(service: RadioSyncService) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        if (text.isNullOrBlank()) {
            finishWith("The clipboard is empty")
            return
        }
        service.sendPayload(Payload.Text(content = text, isOutgoing = true)) { reportResult(it, "Clipboard") }
    }
}

/** Receives "Share" from other apps (text, links, files) and sends it to the paired devices. */
class ShareReceiverActivity : QuickServiceActivity() {

    override fun perform(service: RadioSyncService) {
        val shared = intent
        if (shared?.action != Intent.ACTION_SEND) {
            finishWith("Nothing to send")
            return
        }

        val stream = streamUri(shared)
        val text = shared.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        when {
            stream != null -> sendSharedFile(service, stream, shared.type)
            !text.isNullOrBlank() ->
                service.sendPayload(Payload.Text(content = text, isOutgoing = true)) { reportResult(it, "Text") }
            else -> finishWith("Nothing to send")
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(shared: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            shared.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    /**
     * The access Android grants to a shared file ends when this screen closes, but sending can take
     * a while. So the file is copied into RadioClip's own cache first (cleaned up automatically).
     */
    private fun sendSharedFile(service: RadioSyncService, uri: Uri, intentType: String?) {
        Toast.makeText(applicationContext, "Preparing file…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                var name = "shared_file"
                var size = -1L
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
                if (size > WifiEngine.MAX_FILE_BYTES) {
                    finishWith("That file is too large to send (limit 1 GB)")
                    return@Thread
                }

                val dir = File(cacheDir, "outgoing")
                val copy = FileNames.reserveUniqueFile(dir, name)
                val input = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Can't open the file")
                input.use { source -> copy.outputStream().use { source.copyTo(it) } }

                val mime = contentResolver.getType(uri) ?: intentType ?: "application/octet-stream"
                runOnUiThread {
                    service.sendPayload(
                        Payload.File(
                            fileName = copy.name,
                            mimeType = mime,
                            sizeBytes = copy.length(),
                            localPath = copy.absolutePath
                        )
                    ) { reportResult(it, "File") }
                }
            } catch (e: Exception) {
                finishWith("Could not read the file: ${e.message}")
            }
        }.start()
    }
}
