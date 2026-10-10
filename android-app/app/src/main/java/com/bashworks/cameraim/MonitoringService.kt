package com.bashworks.cameraim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class MonitoringService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private lateinit var store: SettingsStore
    private var socket: WebSocket? = null
    private var running = false
    private var settings: ConnectionSettings? = null
    private val reconnect = Runnable { if (running) connect() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            store.touchMonitoring()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring("Zaustavljeno od strane korisnika")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        val candidate = ConnectionSettings(
            baseUrl = intent.getStringExtra(EXTRA_URL).orEmpty(),
            machineId = intent.getStringExtra(EXTRA_MACHINE).orEmpty(),
            readToken = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        )
        try {
            settings = candidate.copy(baseUrl = MonitoringProtocol.normalizeBaseUrl(candidate.baseUrl))
            require(candidate.machineId.matches(Regex("^[A-Za-z0-9._-]{1,80}$")))
            require(candidate.readToken.isNotBlank())
        } catch (_: IllegalArgumentException) {
            stopMonitoring("Neispravna podešavanja veze")
            return START_NOT_STICKY
        }

        store.save(settings!!)
        running = true
        store.setMonitoring(true, "Povezivanje")
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        val notification = monitoringNotification("Povezivanje sa serverom…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MONITORING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(MONITORING_NOTIFICATION_ID, notification)
        }
        connect()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(heartbeat)
        socket?.cancel()
        socket = null
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopMonitoring("Android je prekinuo foreground praćenje")
    }

    private fun connect() {
        val current = settings ?: return
        handler.removeCallbacks(reconnect)
        socket?.cancel()
        val cursor = store.cursor(current.machineId)
        val request = Request.Builder()
            .url(MonitoringProtocol.websocketUrl(current.baseUrl, current.machineId, cursor))
            .header("Authorization", "Bearer " + current.readToken)
            .build()
        updateMonitoring("Povezivanje…")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!running || socket !== webSocket) return
                store.setMonitoring(true, "Povezano")
                updateMonitoring("Povezano — čekam događaje")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!running || socket !== webSocket) return
                try {
                    val event = MonitoringProtocol.parseEventEnvelope(text) ?: return
                    if (event.serverSequence <= store.cursor(current.machineId)) return
                    store.saveCursor(current.machineId, event.serverSequence)
                    handleEvent(event)
                } catch (_: Exception) {
                    updateMonitoring("Primljen je neispravan odgovor servera")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running) return
        store.setMonitoring(true, "Veza prekinuta — ponovni pokušaj")
        updateMonitoring("Veza prekinuta — ponovni pokušaj za 5 s")
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, 5_000)
    }

    private fun handleEvent(event: EventMessage) {
        when (event.kind) {
            "completed" -> showEvent("Posao je završen", event.detail ?: "Kontroler je potvrdio završetak.", event)
            "alarm" -> showEvent("CNC alarm", event.detail ?: "Mašina je prijavila alarm.", event)
            "disconnected" -> showEvent("Veza sa mašinom je prekinuta", event.detail ?: "Posao nije označen kao završen.", event)
            "reconnected" -> updateMonitoring("Veza sa mašinom je ponovo uspostavljena")
            else -> updateMonitoring("Poslednji događaj: " + event.kind)
        }
    }

    private fun updateMonitoring(text: String) {
        if (running) store.setMonitoring(true, text)
        getSystemService(NotificationManager::class.java)
            .notify(MONITORING_NOTIFICATION_ID, monitoringNotification(text))
    }

    private fun monitoringNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitoringService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_cameraim)
            .setContentTitle("G-BASHWARE CamerAim")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Zaustavi", stopIntent).build())
            .build()
    }

    private fun showEvent(title: String, text: String, event: EventMessage) {
        val openIntent = PendingIntent.getActivity(
            this,
            event.serverSequence.toInt(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_cameraim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(event.serverSequence.hashCode(), notification)
    }

    private fun stopMonitoring(detail: String) {
        running = false
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(heartbeat)
        socket?.close(1000, "Stopped by user")
        socket = null
        store.setMonitoring(false, detail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                getString(R.string.monitoring_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                getString(R.string.event_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        private const val ACTION_START = "com.bashworks.cameraim.START"
        private const val ACTION_STOP = "com.bashworks.cameraim.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_MACHINE = "machine"
        private const val EXTRA_TOKEN = "token"
        private const val CHANNEL_MONITORING = "monitoring"
        private const val CHANNEL_EVENTS = "events"
        private const val MONITORING_NOTIFICATION_ID = 100

        fun start(context: Context, settings: ConnectionSettings) {
            val intent = Intent(context, MonitoringService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, settings.baseUrl)
                .putExtra(EXTRA_MACHINE, settings.machineId)
                .putExtra(EXTRA_TOKEN, settings.readToken)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitoringService::class.java).setAction(ACTION_STOP))
        }
    }
}
