package com.almog.radioclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
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
import com.almog.radioclip.security.SecureLink
import kotlinx.coroutines.Dispatchers
import com.almog.radioclip.routing.SyncRouter
import com.almog.radioclip.transport.ble.BleEngine
import com.almog.radioclip.transport.wifi.WifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var transportsStarted = false
    private val historyLimit = 200

    lateinit var bleEngine: BleEngine
        private set

    lateinit var wifiEngine: WifiEngine
        private set

    lateinit var router: SyncRouter
        private set

    lateinit var secureLink: SecureLink
        private set

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val _transferError = MutableStateFlow<String?>(null)
    val transferError: StateFlow<String?> = _transferError.asStateFlow()

    fun clearTransferError() {
        _transferError.value = null
    }

    fun setPairingPhrase(phrase: String) {
        serviceScope.launch(Dispatchers.Default) {
            try {
                secureLink.configurePhrase(phrase)
                wifiEngine.onPairingChanged()
                _pairingError.value = null
                _transferError.value = null
            } catch (e: Exception) {
                _pairingError.value = e.message ?: "Unable to configure pairing"
            }
        }
    }

    fun forgetPairingPhrase() {
        secureLink.forgetPhrase()
        wifiEngine.onPairingChanged()
        setClipboardSyncEnabled(false)
        pendingLocalClipboardText = null
        lastRemoteClipboardText = null
        lastDeliveredClipboardText = null
        _pairingError.value = null
        _transferError.value = null
    }

    private val _history = MutableStateFlow<List<Payload>>(emptyList())
    val history: StateFlow<List<Payload>> = _history.asStateFlow()

    // Clipboard access is sensitive and often restricted for background apps.
    // Manual text transfer remains available without enabling this setting.
    private val _clipboardSyncEnabled = MutableStateFlow(false)
    val clipboardSyncEnabled: StateFlow<Boolean> = _clipboardSyncEnabled.asStateFlow()

    private val _clipboardSyncStatus = MutableStateFlow("Off")
    val clipboardSyncStatus: StateFlow<String> = _clipboardSyncStatus.asStateFlow()

    private var clipboardManager: ClipboardManager? = null
    @Volatile private var windowFocused = false
    private var clipboardListenerRegistered = false
    @Volatile private var lastDeliveredClipboardText: String? = null
    @Volatile private var lastRemoteClipboardText: String? = null
    @Volatile private var pendingLocalClipboardText: String? = null
    private val clipboardSendMutex = Mutex()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Clipboard reads are permitted only while an activity has focus (Android 10+).
        if (windowFocused) inspectFocusedClipboard()
    }

    fun setClipboardSyncEnabled(enabled: Boolean) {
        _clipboardSyncEnabled.value = enabled
        getSharedPreferences("radioclip", MODE_PRIVATE).edit()
            .putBoolean("clipboard_sync_enabled", enabled).apply()

        if (!enabled) {
            pendingLocalClipboardText = null
            _clipboardSyncStatus.value = "Off"
        } else {
            _clipboardSyncStatus.value = if (windowFocused) {
                "Watching clipboard while RadioClip is in front"
            } else {
                "Paused: Android blocks background clipboard reads"
            }
            if (windowFocused) inspectFocusedClipboard()
        }
    }

    /** Called by MainActivity only while its Android window really has input focus. */
    fun setWindowFocused(focused: Boolean) {
        windowFocused = focused
        val manager = clipboardManager ?: return
        if (focused && !clipboardListenerRegistered) {
            manager.addPrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = true
        } else if (!focused && clipboardListenerRegistered) {
            manager.removePrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = false
        }
        if (_clipboardSyncEnabled.value) {
            if (focused) {
                _clipboardSyncStatus.value = "Watching clipboard while RadioClip is in front"
                inspectFocusedClipboard()
                flushPendingClipboard()
            } else {
                _clipboardSyncStatus.value = "Paused: Android blocks background clipboard reads"
            }
        }
    }

    /**
     * Explicit user-triggered clipboard transfer. Unlike automatic sync this
     * is allowed even when the background auto-sync preference is disabled.
     */
    fun syncClipboardNow() {
        if (!windowFocused) {
            _clipboardSyncStatus.value = "Keep RadioClip open while sending clipboard"
            return
        }
        if (!secureLink.configured.value) {
            _clipboardSyncStatus.value = "Set up your pairing code first"
            return
        }
        val manager = clipboardManager
        if (manager == null) {
            _clipboardSyncStatus.value = "Clipboard service is unavailable"
            return
        }
        try {
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                _clipboardSyncStatus.value = "Clipboard is empty"
                return
            }
            val description = clip.description
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            ) {
                _clipboardSyncStatus.value = "Sensitive clipboard skipped for safety"
                return
            }
            val text = clip.getItemAt(0).text?.toString()
            if (text.isNullOrBlank()) {
                _clipboardSyncStatus.value = "No plain text found; try copying text first"
                return
            }
            if (text.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
                _clipboardSyncStatus.value = "Clipboard too large; share it as a file"
                return
            }
            pendingLocalClipboardText = text
            flushPendingClipboard(force = true)
        } catch (e: SecurityException) {
            _clipboardSyncStatus.value = "Clipboard permission denied; paste text into Send"
        } catch (e: Exception) {
            Log.w(TAG, "Manual clipboard send failed", e)
            _clipboardSyncStatus.value = "Cannot read clipboard; paste text into Send"
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioSyncService = this@RadioSyncService
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        _clipboardSyncEnabled.value = getSharedPreferences("radioclip", MODE_PRIVATE)
            .getBoolean("clipboard_sync_enabled", false)
        createNotificationChannel()

        secureLink = SecureLink(applicationContext)
        bleEngine = BleEngine(applicationContext, serviceScope, secureLink)
        wifiEngine = WifiEngine(applicationContext, serviceScope, secureLink)
        router = SyncRouter(bleEngine, wifiEngine, serviceScope)
        restoreReceivedFiles()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        _clipboardSyncStatus.value = if (_clipboardSyncEnabled.value) {
            "Paused: Android blocks background clipboard reads"
        } else "Off"

        // A copied item that failed while disconnected is kept for retry when a
        // peer becomes available, rather than silently being marked as synced.
        serviceScope.launch {
            combine(
                bleEngine.connectedPeers,
                wifiEngine.connectedPeers,
                secureLink.configured,
                _clipboardSyncEnabled
            ) { ble, wifi, paired, enabled ->
                enabled && paired && (ble.isNotEmpty() || wifi.isNotEmpty())
            }.distinctUntilChanged().collect { connected ->
                if (connected) flushPendingClipboard()
            }
        }

        // Observe incoming payloads from peers
        serviceScope.launch {
            router.incomingPayloads.collect { payload ->
                // Don't log clipboard content or filenames in plaintext.
                Log.d(TAG, "Incoming payload received over ${payload.transportType}")
                _history.value = (listOf(payload) + _history.value).take(historyLimit)

                // If incoming payload is text, update local clipboard without re-broadcasting
                if (payload is Payload.Text && _clipboardSyncEnabled.value &&
                    secureLink.configured.value) {
                    applyToLocalClipboard(payload.content)
                } else if (payload is Payload.File) {
                    // Trigger notification alert when receiving a file
                    showFileReceivedNotification(payload)
                }
            }
        }
    }

    /** Restore received files after a process restart without persisting clipboard text. */
    private fun restoreReceivedFiles() {
        val directory = java.io.File(filesDir, "received_files")
        val storedFiles = directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() >= 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.take(historyLimit)
            ?.map { file ->
                val visibleName = if (file.name.length > 37 &&
                    file.name[36] == '-') file.name.substring(37) else file.name
                val ext = visibleName.substringAfterLast('.', "")
                val type = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
                Payload.File(
                    id = if (file.name.length >= 36) file.name.take(36)
                        else java.util.UUID.randomUUID().toString(),
                    timestamp = file.lastModified(),
                    fileName = visibleName,
                    mimeType = type,
                    sizeBytes = file.length(),
                    localPath = file.absolutePath,
                    senderDeviceName = "Saved to this device"
                )
            }
            ?.toList()
            ?: emptyList()
        _history.value = storedFiles
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

        if (!transportsStarted) {
            transportsStarted = true
            serviceScope.launch {
                try {
                    bleEngine.start()
                } catch (e: Exception) {
                    Log.e(TAG, "BLE startup failed", e)
                }
                try {
                    wifiEngine.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Wi-Fi Direct startup failed", e)
                }
            }
        }

        return START_STICKY
    }

    /** Only reads plain text while the RadioClip UI has input focus. */
    private fun inspectFocusedClipboard() {
        if (!windowFocused || !_clipboardSyncEnabled.value || !secureLink.configured.value) return
        val manager = clipboardManager ?: return
        try {
            val clip = manager.primaryClip ?: return
            val description = clip.description
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            ) {
                _clipboardSyncStatus.value = "Sensitive clipboard skipped"
                return
            }
            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString() ?: return
            if (text.isBlank() || text == lastDeliveredClipboardText ||
                text == lastRemoteClipboardText || text == pendingLocalClipboardText
            ) return
            if (text.toByteArray(Charsets.UTF_8).size > 1024 * 1024) {
                _clipboardSyncStatus.value = "Clipboard text exceeds 1 MB; use file sharing"
                return
            }
            pendingLocalClipboardText = text
            flushPendingClipboard()
        } catch (e: SecurityException) {
            _clipboardSyncStatus.value = "Clipboard access denied by Android"
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect clipboard", e)
            _clipboardSyncStatus.value = "Unable to read clipboard"
        }
    }

    /** Transport completion must be confirmed before a copy is considered synced. */
    private fun flushPendingClipboard(force: Boolean = false) {
        if ((!force && !_clipboardSyncEnabled.value) || !secureLink.configured.value) return
        serviceScope.launch {
            clipboardSendMutex.withLock {
                while ((force || _clipboardSyncEnabled.value) && secureLink.configured.value) {
                    val text = pendingLocalClipboardText ?: break
                    _clipboardSyncStatus.value = "Sending clipboard..."
                    val payload = Payload.Text(content = text, isOutgoing = true)
                    val result = router.route(payload)
                    val transport = result.getOrNull() ?: payload.transportType
                    _history.value = (listOf(
                        payload.copy(
                            transportType = transport,
                            isFailed = result.isFailure
                        )
                    ) + _history.value).take(historyLimit)
                    if (result.isSuccess) {
                        lastDeliveredClipboardText = text
                        if (pendingLocalClipboardText == text) pendingLocalClipboardText = null
                        _clipboardSyncStatus.value = "Clipboard delivered to peer"
                    } else {
                        _clipboardSyncStatus.value = "Not delivered: connect a paired device, then retry"
                        Log.w(TAG, "Clipboard transfer not delivered: ${result.exceptionOrNull()?.message}")
                        break
                    }
                }
            }
        }
    }

    private fun applyToLocalClipboard(text: String) {
        lastRemoteClipboardText = text
        // A foreground service isn't a privileged clipboard-reading service.
        // Writing can also vary by Android implementation; received text remains
        // in history with the explicit Copy action if this fails.
        android.os.Handler(mainLooper).post {
            try {
                clipboardManager?.setPrimaryClip(ClipData.newPlainText("RadioClip Sync", text))
            } catch (e: Exception) {
                Log.w(TAG, "Incoming clipboard cannot be applied automatically", e)
            }
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
            _transferError.value = null
            val result = when (transportType) {
                TransportType.BLE_GATT -> bleEngine.connect(peerId)
                TransportType.WIFI_DIRECT_SOCKET -> wifiEngine.connect(peerId)
            }
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "Unknown connection error"
                _transferError.value = "Connection failed: $reason"
                Log.w(TAG, "Connection failed: $reason")
            }
        }
    }

    fun sendPayload(payload: Payload) {
        serviceScope.launch {
            val result = router.route(payload)
            val actualTransport = result.getOrNull() ?: payload.transportType
            val record = when (payload) {
                is Payload.Text -> payload.copy(
                    transportType = actualTransport, isOutgoing = true, isFailed = result.isFailure
                )
                is Payload.File -> payload.copy(
                    transportType = actualTransport, isOutgoing = true, isFailed = result.isFailure
                )
            }
            _history.value = (listOf(record) + _history.value).take(historyLimit)

            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "The other device did not confirm receipt"
                _transferError.value = "Transfer failed: $reason"
                Log.w(TAG, "Failed to route payload: $reason")
            } else {
                _transferError.value = null
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
        if (clipboardListenerRegistered) {
            clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = false
        }
        // Let transport cleanup complete before cancelling the service scope.
        serviceScope.launch {
            try {
                bleEngine.stop()
                wifiEngine.stop()
            } finally {
                serviceJob.cancel()
            }
        }
    }
}
