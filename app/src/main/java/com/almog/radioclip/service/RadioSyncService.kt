package com.almog.radioclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.almog.radioclip.MainActivity
import com.almog.radioclip.R
import com.almog.radioclip.model.Payload
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

    inner class LocalBinder : Binder() {
        fun getService(): RadioSyncService = this@RadioSyncService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        bleEngine = BleEngine(applicationContext)
        wifiEngine = WifiEngine(applicationContext)
        router = SyncRouter(bleEngine, wifiEngine, serviceScope)

        // Observe incoming payloads
        serviceScope.launch {
            router.incomingPayloads.collect { payload ->
                _history.value = listOf(payload) + _history.value
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()

        serviceScope.launch {
            bleEngine.start()
            wifiEngine.start()
        }

        return START_STICKY
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RadioClip Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps peer-to-peer clipboard & file sync active"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RadioClip Active")
            .setContentText("P2P BLE & Wi-Fi Direct sync running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun sendPayload(payload: Payload) {
        serviceScope.launch {
            val result = router.route(payload)
            if (result.isSuccess) {
                _history.value = listOf(payload) + _history.value
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            bleEngine.stop()
            wifiEngine.stop()
        }
        serviceJob.cancel()
    }

    companion object {
        const val CHANNEL_ID = "radioclip_sync_channel"
        const val NOTIFICATION_ID = 1001

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
}
