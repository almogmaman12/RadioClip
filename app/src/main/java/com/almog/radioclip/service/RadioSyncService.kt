package com.almog.radioclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.almog.radioclip.MainActivity
import com.almog.radioclip.R
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.routing.SyncRouter
import com.almog.radioclip.transport.ble.BleEngine
import com.almog.radioclip.transport.wifi.WifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RadioSyncService : Service() {

    companion object {
        private const val TAG = "RadioSyncService"
        const val CHANNEL_ID = "radioclip_sync_channel"
        const val FILE_ALERT_CHANNEL_ID = "radioclip_files_channel"
        const val NOTIFICATION_ID = 1001

        // Singleton instance pattern so UI always shares exactly the same history and state
        var activeInstance: RadioSyncService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, RadioSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RadioSyncService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val binder = LocalBinder()

    lateinit var bleEngine: BleEngine
        private set

    lateinit var wifiEngine: WifiEngine
        private set

    lateinit var router: SyncRouter
        private set

    private val _history = MutableStateFlow<List<Payload>>(emptyList())
    val history: StateFlow<List<Payload>> = _history.asStateFlow()

    private var clipboardManager: ClipboardManager? = null
    private var lastSyncedClipboardText: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChanged()
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioSyncService = this@RadioSyncService
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()

        bleEngine = BleEngine(applicationContext, serviceScope)
        wifiEngine = WifiEngine(applicationContext, serviceScope)
        router = SyncRouter(bleEngine, wifiEngine, serviceScope)

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)

        // Observe incoming payloads from peers
        serviceScope.launch {
            router.incomingPayloads.collect { payload ->
                Log.d(TAG, "Incoming payload collected in service: $payload")
                _history.value = listOf(payload) + _history.value

                // If incoming payload is text, update local clipboard without re-broadcasting
                if (payload is Payload.Text) {
                    applyToLocalClipboard(payload.content)
                } else if (payload is Payload.File) {
                    // Trigger notification alert when receiving a file
                    showFileReceivedNotification(payload)
                }
            }
        }
    }

    private fun showFileReceivedNotification(file: Payload.File) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            val fileObj = file.localPath?.let { java.io.File(it) }
            if (fileObj != null && fileObj.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@RadioSyncService,
                    "${packageName}.fileprovider",
                    fileObj
                )
                setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setClass(this@RadioSyncService, MainActivity::class.java)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        val pendingViewIntent = PendingIntent.getActivity(
            this,
            file.id.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sender = file.senderDeviceName ?: "Nearby Peer"
        val sizeFormatted = "${(file.sizeBytes / 1024.0).toInt()} KB"

        val notification = NotificationCompat.Builder(this, FILE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("File Received: ${file.fileName}")
            .setContentText("From $sender ($sizeFormatted) • Tap to open")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Received \"${file.fileName}\" from $sender\nSize: $sizeFormatted\nTap to open file."))
            .setContentIntent(pendingViewIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_launcher_foreground, "Open File", pendingViewIntent)
            .build()

        manager.notify(file.id.hashCode(), notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()

        serviceScope.launch {
            bleEngine.start()
            wifiEngine.start()
        }

        return START_STICKY
    }

    private fun handleClipboardChanged() {
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount > 0) {
            val text = clip.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank() && text != lastSyncedClipboardText) {
                lastSyncedClipboardText = text
                Log.d(TAG, "Auto-detected clipboard change: ${text.take(30)}")
                sendPayload(Payload.Text(content = text, isOutgoing = true))
            }
        }
    }

    private fun applyToLocalClipboard(text: String) {
        lastSyncedClipboardText = text
        val clip = ClipData.newPlainText("RadioClip Sync", text)
        android.os.Handler(mainLooper).post {
            clipboardManager?.setPrimaryClip(clip)
        }
    }

    private fun startAsForegroundService() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val syncChannel = NotificationChannel(
                CHANNEL_ID,
                "RadioClip Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps peer-to-peer clipboard & file sync active"
            }

            val fileChannel = NotificationChannel(
                FILE_ALERT_CHANNEL_ID,
                "RadioClip File Transfers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when new files are received"
                enableVibration(true)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(syncChannel)
            manager.createNotificationChannel(fileChannel)
        }
    }

    private fun createNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Quick Send File from the persistent notification
        val sendFileIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_SEND_FILE_FROM_NOTIFICATION"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sendFilePendingIntent = PendingIntent.getActivity(
            this,
            1,
            sendFileIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RadioClip Active")
            .setContentText("P2P BLE & Wi-Fi Direct sync running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "Send File", sendFilePendingIntent)
            .build()
    }

    fun connectToPeer(transportType: TransportType, peerId: String) {
        serviceScope.launch {
            when (transportType) {
                TransportType.BLE_GATT -> bleEngine.connect(peerId)
                TransportType.WIFI_DIRECT_SOCKET -> wifiEngine.connect(peerId)
            }
        }
    }

    fun sendPayload(payload: Payload) {
        serviceScope.launch {
            val result = router.route(payload)
            val actualTransport = result.getOrNull() ?: payload.transportType
            val record = when (payload) {
                is Payload.Text -> payload.copy(transportType = actualTransport, isOutgoing = true)
                is Payload.File -> payload.copy(transportType = actualTransport, isOutgoing = true)
            }
            // Always add to history for user visibility, whether routed immediately or queued
            _history.value = listOf(record) + _history.value

            if (result.isFailure) {
                Log.w(TAG, "Failed to route payload: ${result.exceptionOrNull()?.message}")
            } else {
                Log.d(TAG, "Payload routed successfully over $actualTransport")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) {
            activeInstance = null
        }
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        serviceScope.launch {
            bleEngine.stop()
            wifiEngine.stop()
        }
        serviceJob.cancel()
    }
}
