package com.giadinhsk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Giữ kết nối WebSocket tới server. GPS chỉ được bật khi website gửi yêu cầu "locate",
 * nên lúc không ai xem thì gần như không tốn pin.
 */
class LocationService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var wakeLock: PowerManager.WakeLock
    private val main = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var running = false
    private var locating = false
    private var retryDelayMs = MIN_RETRY_MS
    private val reconnect = Runnable { connect() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Có mạng lại thì kết nối ngay, không chờ hết thời gian thử lại.
            main.post {
                if (running && socket == null) {
                    main.removeCallbacks(reconnect)
                    retryDelayMs = MIN_RETRY_MS
                    connect()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GiaDinhSK:locate")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !prefs.enabled || !hasLocationPermission()) {
            shutdown()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.status_connecting)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        if (!running) {
            running = true
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        closeSocket()
        super.onDestroy()
    }

    private fun shutdown() {
        closeSocket()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeSocket() {
        if (running) {
            running = false
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
        }
        main.removeCallbacks(reconnect)
        socket?.close(1000, "stop")
        socket = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun connect() {
        if (!running || socket != null) return
        val url = "${prefs.server}/ws/device".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("id", prefs.deviceId)
            ?.addQueryParameter("name", prefs.name)
            ?.addQueryParameter("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            ?.build()
        if (url == null) {
            setStatus(getString(R.string.status_bad_url))
            return
        }
        setStatus(getString(R.string.status_connecting))
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private fun scheduleReconnect(reason: String) {
        socket = null
        if (!running) return
        setStatus(getString(R.string.status_disconnected, reason, retryDelayMs / 1000))
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                retryDelayMs = MIN_RETRY_MS
                setStatus(getString(R.string.status_ready))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val type = try {
                JSONObject(text).optString("type")
            } catch (_: Exception) {
                return
            }
            if (type == "locate") main.post { locate(webSocket) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post { if (socket === webSocket) scheduleReconnect(reason.ifEmpty { "server đóng kết nối" }) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post { if (socket === webSocket) scheduleReconnect(t.message ?: t.javaClass.simpleName) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun locate(ws: WebSocket) {
        if (locating || !hasLocationPermission()) return
        locating = true
        // Giữ CPU thức trong lúc chờ GPS, kể cả khi màn hình tắt.
        wakeLock.acquire(LOCATE_TIMEOUT_MS + 10_000)
        setStatus(getString(R.string.status_locating))

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(LOCATE_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(5_000)
            .build()

        fun finish(loc: Location?) {
            locating = false
            if (wakeLock.isHeld) wakeLock.release()
            if (loc != null) {
                ws.send(locationJson(loc).toString())
                setStatus(getString(R.string.status_sent, timeFormat.format(Date())))
            } else {
                ws.send(JSONObject().put("type", "error").put("message", "Không lấy được GPS").toString())
                setStatus(getString(R.string.status_no_gps))
            }
        }

        // Không lấy được vị trí mới thì dùng vị trí gần nhất mà máy còn nhớ.
        fun fallback() = fused.lastLocation.addOnCompleteListener { finish(if (it.isSuccessful) it.result else null) }

        fused.getCurrentLocation(request, null)
            .addOnSuccessListener { loc -> if (loc != null) finish(loc) else fallback() }
            .addOnFailureListener { fallback() }
    }

    private fun locationJson(loc: Location) = JSONObject().apply {
        put("type", "location")
        put("lat", loc.latitude)
        put("lng", loc.longitude)
        if (loc.hasAccuracy()) put("accuracy", loc.accuracy.toDouble())
        if (loc.hasSpeed()) put("speed", loc.speed.toDouble())
        batteryLevel()?.let { put("battery", it) }
        put("time", loc.time)
    }

    private fun setStatus(text: String) {
        prefs.status = text
        if (running) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun batteryLevel(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    private fun createChannel() {
        // IMPORTANCE_MIN: thông báo thu gọn, không hiện icon trên thanh trạng thái.
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setColor(ContextCompat.getColor(this, R.color.pink))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "location_bg"
        private const val NOTIFICATION_ID = 1
        private const val MIN_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val LOCATE_TIMEOUT_MS = 30_000L
        const val ACTION_STOP = "com.giadinhsk.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
