package com.martylai.smbserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.martylai.smbserver.MainActivity
import com.martylai.smbserver.R
import com.martylai.smbserver.data.ServerConfig
import com.martylai.smbserver.server.Smb2Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.parcelize.Parcelize

// ─────────────────────────────────────────────────────────────────────────────
// Parcelable wrapper for ServerConfig — safe to pass via Service intents
// ─────────────────────────────────────────────────────────────────────────────
@Parcelize
data class ServerConfigParcel(
    val port: Int,
    val shareName: String,
    val serverName: String,
    val sharePath: String,
    val requireAuth: Boolean,
    val username: String,
    val password: String,
    val readOnly: Boolean
) : Parcelable {
    fun toServerConfig() = ServerConfig(
        port, shareName, serverName, sharePath,
        requireAuth, username, password, readOnly
    )
}

fun ServerConfig.toParcel() = ServerConfigParcel(
    port, shareName, serverName, sharePath,
    requireAuth, username, password, readOnly
)

/**
 * Foreground Service that hosts the SMB2 server.
 * Keeps the server alive even when the App is in background.
 */
class SmbServerService : Service() {

    companion object {
        const val ACTION_START  = "com.martylai.smbserver.START"
        const val ACTION_STOP   = "com.martylai.smbserver.STOP"
        const val EXTRA_CONFIG  = "config_parcel"

        const val CHANNEL_ID    = "smb_server_channel"
        const val NOTIF_ID      = 1001

        // Static state the UI can poll
        @Volatile var instance: SmbServerService? = null
        @Volatile var isRunning: Boolean = false
        @Volatile var currentPort: Int = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: Smb2Server? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val config = intent.getParcelableExtra<ServerConfigParcel>(EXTRA_CONFIG)
                    ?.toServerConfig() ?: return START_NOT_STICKY
                startServer(config)
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun startServer(config: ServerConfig) {
        server?.stop()

        val powerMgr = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerMgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmbServer::WakeLock")
        @Suppress("WakelockTimeout")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)  // max 12 hours

        val smb = Smb2Server(config)
        val result = smb.start()

        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message ?: "Unknown error"
            startForeground(NOTIF_ID, buildNotification("啟動失敗: $err", config.port))
            stopSelf()
            return
        }

        server = smb
        isRunning = true
        currentPort = config.port
        startForeground(NOTIF_ID, buildNotification("SMB 伺服器運行中", config.port))
    }

    private fun stopServer() {
        server?.stop()
        server = null
        isRunning = false
        currentPort = 0
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMB 伺服器",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SMB 檔案分享伺服器狀態"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SmbServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMB Server — Port $port")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_server_notification, "停止", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
