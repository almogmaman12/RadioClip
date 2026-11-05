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
import android.os.Handler
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.almog.radioclip.MainActivity
import com.almog.radioclip.R
import com.almog.radioclip.core.ClipPolicy
import com.almog.radioclip.core.Crypto
import com.almog.radioclip.core.DiagLog
import com.almog.radioclip.core.FileRetention
import com.almog.radioclip.core.Format
import com.almog.radioclip.core.RecentIds
import com.almog.radioclip.core.Settings
import com.almog.radioclip.model.Payload
import com.almog.radioclip.model.TransportType
import com.almog.radioclip.routing.SyncRouter
import com.almog.radioclip.security.PairingStore
import com.almog.radioclip.settings.SettingsStore
import com.almog.radioclip.transport.ble.BleEngine
import com.almog.radioclip.transport.wifi.WifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

class RadioSyncService : Service() {

    companion object {
        private const val TAG = "RadioSyncService"
        const val CHANNEL_ID = "radioclip_sync_channel"
        const val FILE_ALERT_CHANNEL_ID = "radioclip_files_channel"
        const val TEXT_CHANNEL_ID = "radioclip_text_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_SEND_FILE_FROM_NOTIFICATION = "ACTION_SEND_FILE_FROM_NOTIFICATION"
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val MAX_HISTORY = 200

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
            context.stopService(Intent(context, RadioSyncService::class.java))
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val binder = LocalBinder()

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var pairing: PairingStore
        private set

    lateinit var bleEngine: BleEngine
        private set

    lateinit var wifiEngine: WifiEngine
        private set

    lateinit var router: SyncRouter
        private set

    private val _history = MutableStateFlow<List<Payload>>(emptyList())
    val history: StateFlow<List<Payload>> = _history.asStateFlow()

    // Short messages for the UI to show as toasts (send failures, pairing result, connection hints).
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val recentIds = RecentIds()
    private var clipboardManager: ClipboardManager? = null
    @Volatile private var lastSyncedClipboardText: String? = null
    private var lastStatusText: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChanged()
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioSyncService = this@RadioSyncService
    }

    private val settings: Settings get() = settingsStore.settings.value

    private fun addToHistory(payload: Payload) {
        _history.value = (listOf(payload) + _history.value).take(MAX_HISTORY)
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        DiagLog.sink = { tag, message -> Log.d(tag, message) }
        createNotificationChannels()

        settingsStore = SettingsStore.get(applicationContext)
        pairing = PairingStore(applicationContext)

        val notice: (String) -> Unit = { _messages.tryEmit(it) }
        bleEngine = BleEngine(
            applicationContext, serviceScope,
            keyProvider = { pairing.key },
            settingsProvider = { settingsStore.settings.value },
            nodeId = settingsStore.nodeId,
            onNotice = notice
        )
        wifiEngine = WifiEngine(
            applicationContext, serviceScope,
            keyProvider = { pairing.key },
            settingsProvider = { settingsStore.settings.value },
            onNotice = notice
        )
        router = SyncRouter(bleEngine, wifiEngine, serviceScope) { settingsStore.settings.value.useWifiDirect }

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)

        // Observe incoming payloads from peers
        serviceScope.launch {
            router.incomingPayloads.collect { payload ->
                // The same text can arrive over Bluetooth and Wi-Fi; handle it once.
                if (!recentIds.firstSeen(payload.id)) return@collect
                DiagLog.d(TAG, "Received ${payload.javaClass.simpleName} from ${payload.senderDeviceName}")
                addToHistory(payload)
                when (payload) {
                    is Payload.Text -> handleIncomingText(payload)
                    is Payload.File -> showFileReceivedNotification(payload)
                }
            }
        }

        // React to settings that need action right away.
        serviceScope.launch {
            var previous: Settings? = null
            settingsStore.settings.collect { current ->
                val old = previous
                previous = current
                if (old == null) return@collect
                if (current.useWifiDirect != old.useWifiDirect) {
                    if (current.useWifiDirect) wifiEngine.start() else wifiEngine.stop()
                }
                if (current.bleMode != old.bleMode) bleEngine.applySettings()
                if (current.announcedName(Build.MODEL ?: "") != old.announcedName(Build.MODEL ?: "")) {
                    bleEngine.announceName()
                }
                if (current.autoDeleteDays != old.autoDeleteDays) cleanupOldFiles()
            }
        }

        // Keep the ongoing notification's text up to date: paired? connected to whom?
        serviceScope.launch {
            combine(bleEngine.connectedPeers, wifiEngine.connectedPeers, pairing.isPaired) { ble, wifi, paired ->
                val names = (ble + wifi).map { it.name }.distinct()
                when {
                    !paired -> "Not paired – open RadioClip and set a pairing code"
                    names.isEmpty() -> "Looking for your devices…"
                    else -> "Connected: " + names.joinToString(", ")
                }
            }.distinctUntilChanged().collect { text ->
                lastStatusText = text
                (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.notify(NOTIFICATION_ID, createNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()
        cleanupOldFiles()

        // Engine start() calls are idempotent, so repeated onStartCommand calls are harmless
        // (and retry Bluetooth if permissions were granted after the first start).
        serviceScope.launch {
            bleEngine.start()
            if (settings.useWifiDirect) wifiEngine.start()
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- clipboard

    private fun handleClipboardChanged() {
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.text?.toString()
            val sensitive = clip.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

            when (ClipPolicy.decide(text, lastSyncedClipboardText, sensitive, settings)) {
                ClipPolicy.SendDecision.SEND -> {
                    lastSyncedClipboardText = text
                    DiagLog.d(TAG, "Clipboard changed (${text?.length ?: 0} characters), sending")
                    // Automatic sync stays quiet when nobody is connected or we are not paired yet.
                    sendPayload(Payload.Text(content = text!!, isOutgoing = true), notifyFailure = false)
                }
                ClipPolicy.SendDecision.SKIP_SENSITIVE ->
                    DiagLog.d(TAG, "Clipboard marked sensitive (password?), not sent")
                ClipPolicy.SendDecision.SKIP_TOO_LARGE ->
                    _messages.tryEmit("The copied text is too large to sync (limit ${ClipPolicy.MAX_TEXT_BYTES / 1024} KB)")
                else -> Unit
            }
        } catch (e: SecurityException) {
            DiagLog.d(TAG, "Android didn't allow reading the clipboard right now")
        }
    }

    /** Puts [text] on this phone's clipboard without it being sent out again. */
    fun copyToClipboard(text: String, hidePreview: Boolean = false) {
        lastSyncedClipboardText = text
        Handler(mainLooper).post {
            val clip = ClipData.newPlainText("RadioClip", text)
            if (hidePreview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
            }
            clipboardManager?.setPrimaryClip(clip)
        }
    }

    /** Copies a received text from the history by its id (used by the notification's Copy button). */
    fun copyHistoryText(id: String): Boolean {
        val item = _history.value.firstOrNull { it.id == id } as? Payload.Text ?: return false
        copyToClipboard(item.content)
        return true
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    private fun handleIncomingText(payload: Payload.Text) {
        val s = settings
        if (s.clipboardAutoApply) {
            copyToClipboard(payload.content, hidePreview = s.clipboardHidePreview)
            if (s.notifyOnText) showTextNotification(payload, withCopyButton = false)
        } else {
            // The user wants to decide: show it with a Copy button instead of touching the clipboard.
            showTextNotification(payload, withCopyButton = true)
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "RadioClip Sync Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps peer-to-peer clipboard & file sync active"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(FILE_ALERT_CHANNEL_ID, "RadioClip File Transfers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications when new files are received"
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(TEXT_CHANNEL_ID, "RadioClip Received Text", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications when text arrives from another device"
            }
        )
    }

    private fun showTextNotification(payload: Payload.Text, withCopyButton: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val sender = payload.senderDeviceName ?: "a nearby device"

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // The lock screen only shows that something arrived, never the text itself.
        val publicVersion = NotificationCompat.Builder(this, TEXT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Text from $sender")
            .build()

        val builder = NotificationCompat.Builder(this, TEXT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Text from $sender")
            .setContentText(ClipPolicy.preview(payload.content, 80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ClipPolicy.preview(payload.content, 400)))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)

        if (withCopyButton) {
            val copy = PendingIntent.getBroadcast(
                this, payload.id.hashCode(),
                Intent(this, CopyReceiver::class.java).apply {
                    action = CopyReceiver.ACTION_COPY
                    putExtra(CopyReceiver.EXTRA_ID, payload.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_launcher_foreground, "Copy", copy)
        }
        manager.notify(payload.id.hashCode(), builder.build())
    }

    private fun showFileReceivedNotification(file: Payload.File) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            val fileObj = file.localPath?.let { File(it) }
            if (fileObj != null && fileObj.exists()) {
                val uri = FileProvider.getUriForFile(this@RadioSyncService, "${packageName}.fileprovider", fileObj)
                setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setClass(this@RadioSyncService, MainActivity::class.java)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        val pendingViewIntent = PendingIntent.getActivity(
            this, file.id.hashCode(), viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sender = file.senderDeviceName ?: "Nearby Peer"
        val size = Format.bytes(file.sizeBytes)

        val notification = NotificationCompat.Builder(this, FILE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("File Received: ${file.fileName}")
            .setContentText("From $sender ($size) • Tap to open")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Received \"${file.fileName}\" from $sender\nSize: $size\nTap to open file."))
            .setContentIntent(pendingViewIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_launcher_foreground, "Open File", pendingViewIntent)
            .build()

        manager.notify(file.id.hashCode(), notification)
    }

    private fun startAsForegroundService() {
        val notification = createNotification(lastStatusText ?: "Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Send whatever is on the clipboard right now. Android only lets an app that is on screen
        // read the clipboard, so this opens a see-through screen for a split second to do it.
        val sendClipboard = PendingIntent.getActivity(
            this, 2,
            Intent(this, SendClipboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sendFile = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_SEND_FILE_FROM_NOTIFICATION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RadioClip")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_launcher_foreground, "Send clipboard", sendClipboard)
            .addAction(R.drawable.ic_launcher_foreground, "Send file", sendFile)
            .build()
    }

    // ---------------------------------------------------------------- actions for the UI

    fun connectToPeer(transportType: TransportType, peerId: String) {
        serviceScope.launch {
            val result = when (transportType) {
                TransportType.BLE_GATT -> bleEngine.connect(peerId)
                TransportType.WIFI_DIRECT_SOCKET -> wifiEngine.connect(peerId)
            }
            result.exceptionOrNull()?.let { _messages.tryEmit("Could not connect: ${it.message}") }
        }
    }

    fun rescanBluetooth() = bleEngine.rescan()

    fun rescanWifi() = wifiEngine.startPeerDiscovery()

    /**
     * Sends a payload to connected peers. Only payloads that were actually delivered are added to
     * the history; failures are reported through [messages] when [notifyFailure] is true.
     * [onResult] is called (on a background thread) with the outcome.
     */
    fun sendPayload(
        payload: Payload,
        notifyFailure: Boolean = true,
        onResult: ((Result<TransportType>) -> Unit)? = null
    ) {
        serviceScope.launch {
            recentIds.firstSeen(payload.id) // so an echo of our own message is ignored
            val result = router.route(payload)
            val actualTransport = result.getOrNull()

            if (actualTransport == null) {
                val reason = result.exceptionOrNull()?.message ?: "unknown error"
                DiagLog.d(TAG, "Send failed: $reason")
                if (notifyFailure) _messages.tryEmit("Could not send: $reason")
            } else {
                val record = when (payload) {
                    is Payload.Text -> payload.copy(transportType = actualTransport, isOutgoing = true)
                    is Payload.File -> payload.copy(transportType = actualTransport, isOutgoing = true)
                }
                addToHistory(record)
                DiagLog.d(TAG, "Sent ${payload.javaClass.simpleName} over $actualTransport")
            }
            onResult?.invoke(result)
        }
    }

    /** Sets the shared pairing code (key derivation is CPU-heavy, so this runs off the main thread). */
    fun setPairingCode(code: String) {
        serviceScope.launch(Dispatchers.Default) {
            val ok = pairing.setCode(code)
            if (ok) {
                bleEngine.onPairingChanged()
                wifiEngine.onPairingChanged()
            }
            _messages.tryEmit(
                if (ok) "Paired: clipboard text and files are now encrypted"
                else "Pairing code must be at least ${Crypto.MIN_CODE_LENGTH} characters"
            )
        }
    }

    fun clearPairing() {
        pairing.clear()
        bleEngine.onPairingChanged()
        _messages.tryEmit("Pairing removed: nothing will be sent or accepted until you pair again")
    }

    private fun cleanupOldFiles() {
        val days = settings.autoDeleteDays
        serviceScope.launch {
            val received = getExternalFilesDir("received") ?: File(filesDir, "received")
            val removed = FileRetention.cleanup(received, days)
            // Temporary copies made when sharing a file to RadioClip never need to stay.
            FileRetention.cleanup(File(cacheDir, "outgoing"), 1)
            if (removed > 0) DiagLog.d(TAG, "Auto-delete removed $removed old file(s)")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance == this) activeInstance = null
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)

        // Stop the radios first and only then cancel the service scope. Cancelling right away
        // would kill the stop() coroutines before they ran, leaking the GATT server,
        // advertising and the Wi-Fi Direct group.
        val ble = bleEngine
        val wifi = wifiEngine
        val job = serviceJob
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ble.stop()
                wifi.stop()
            } finally {
                job.cancel()
            }
        }
    }
}
