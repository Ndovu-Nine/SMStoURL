package com.ndovunine.smstourls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException


class SmsForwardService : Service() {

    private val client:OkHttpClient by lazy { getUnsafeOkHttpClient() }
    private val gson = Gson()
    private val prefs by lazy { getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE) }

    companion object {
        const val ACTION_RETRY_MESSAGE = "com.ndovunine.smstourls.RETRY_MESSAGE"
        const val ACTION_FAILED_UPDATED = "com.ndovunine.smstourls.FAILED_UPDATED"
        const val EXTRA_RETRY_MESSAGE = "retry_message"
        private const val RETRY_INTERVAL_MS = 1 * 60 * 1000L // 1 minutes
        private const val MAX_SENT_IDS = 500 // Keep only the last 500 sent IDs
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )

            // Install the all-trusting trust manager
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }  // accept any hostname
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }


    private var retryHandler: Handler? = null
    private var retryRunnable: Runnable? = null
    private val NOTIF_ID = 1
    private val channelId = "SmsForwarderChannel"

    private var forwardedCount: Int
        get() = prefs.getInt("forwarded_count", 0)
        set(value) = prefs.edit().putInt("forwarded_count", value).apply()


    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val count = forwardedCount
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("SMS to URLs")
                .setContentText("$count SMS forwarded")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SMS to URLs")
                .setContentText("$count SMS forwarded")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
        notificationManager.notify(NOTIF_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Create notification channel on API 26+ (safe to do here)
        createNotificationChannel()
        // Do NOT call startForeground() here — it will be called in onStartCommand()
        // to avoid ForegroundServiceStartNotAllowedException on Android 14+
        // when the system restarts the service from background.
        startRetryLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Call startForeground() here instead of onCreate() so that when the
        // system restarts the service from background, we can catch the
        // ForegroundServiceStartNotAllowedException gracefully.
        if (!isForeground) {
            try {
                enterForeground()
            } catch (e: RuntimeException) {
                // On Android 14+ (API 34+), ForegroundServiceStartNotAllowedException
                // is thrown if the background time limit is exhausted.
                // Gracefully stop instead of crashing.
                android.util.Log.e("SmsForwardService", "Failed to start foreground service", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (intent?.action) {
            ACTION_RETRY_MESSAGE -> {
                val message = intent.getStringExtra(EXTRA_RETRY_MESSAGE)
                if (!message.isNullOrBlank()) {
                    // Remove from failed list before retrying
                    removeFailedMessage(message)
                    forwardMessage(message)
                }
            }
            else -> {
                val message = intent?.getStringExtra("sms_message")
                if (!message.isNullOrBlank()) {
                    forwardMessage(message)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the retry handler to prevent leaks
        retryHandler?.removeCallbacksAndMessages(null)
        retryHandler = null
        retryRunnable = null
    }

    private var isForeground = false

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId, "SMS to URLs",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun enterForeground() {
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("SMS to URLs")
                .setContentText("Forwarding SMS to saved URLs")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SMS to URLs")
                .setContentText("Forwarding SMS to saved URLs")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
        startForeground(1, notification)
        isForeground = true
    }

    private fun removeFailedMessage(message: String) {
        val failed = loadFailedMessages().toMutableList()
        failed.remove(message)
        prefs.edit().putString("failed_messages", gson.toJson(failed)).apply()
        broadcastFailedUpdated()
    }

    private fun broadcastFailedUpdated() {
        val intent = Intent(ACTION_FAILED_UPDATED)
        sendBroadcast(intent)
    }


    /**
     * Best-effort retrieval of the last known device location.
     * Returns a Pair(latitude, longitude) or null if unavailable.
     */
    private fun getLastKnownLocation(): Pair<Double, Double>? {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }

            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

            // Try GPS first (most accurate)
            var bestLocation: Location? = null
            try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    if (bestLocation == null || it.time > bestLocation!!.time) {
                        bestLocation = it
                    }
                }
            } catch (_: Exception) { }

            // Fall back to network provider
            try {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                    if (bestLocation == null || it.time > bestLocation!!.time) {
                        bestLocation = it
                    }
                }
            } catch (_: Exception) { }

            // On Android 11+, also try PASSIVE_PROVIDER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let {
                        if (bestLocation == null || it.time > bestLocation!!.time) {
                            bestLocation = it
                        }
                    }
                } catch (_: Exception) { }
            }

            return bestLocation?.let { Pair(it.latitude, it.longitude) }
        } catch (e: Exception) {
            android.util.Log.e("SmsForwardService", "Error getting location", e)
            return null
        }
    }

    private fun forwardMessage(message: String, sender: String = "Unknown") {
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val email = settings.getString("email", "") ?: ""
        val urls = settings.getString("urls", "") ?: ""

        if (urls.isEmpty()) return

        // Get location coordinates (best-effort)
        val location = getLastKnownLocation()
        val latitude = location?.first
        val longitude = location?.second

        val payload = mutableMapOf<String, Any?>(
            "message" to message,
            "sender" to sender,
            "email" to email,
            "latitude" to latitude,
            "longitude" to longitude
        )
        val json = gson.toJson(payload)

        val urlList = urls.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (url in urlList) {
            // Create a fresh RequestBody for each URL to avoid "closed" IllegalStateException
            val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), json)
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    android.util.Log.e("SmsForwardService", "Failed to forward to $url", e)
                    saveFailedMessage(message, sender) // store for retry
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        android.util.Log.w("SmsForwardService", "HTTP ${response.code} from $url")
                        saveFailedMessage(message, sender)
                    }
                    else{
                        forwardedCount += 1   // count up
                        updateNotification()
                    }
                    response.close()
                }
            })
        }
    }

    /** Save failed message into SharedPreferences */
    private fun saveFailedMessage(message: String) {
        val failed = loadFailedMessages().toMutableList()
        if (!failed.contains(message)) {
            failed.add(message)
            prefs.edit().putString("failed_messages", gson.toJson(failed)).apply()
            broadcastFailedUpdated()
        }
    }
    private fun saveFailedMessage(message: String, sender: String) {
        val failed = loadFailedMessages().toMutableList()
        val entry = gson.toJson(mapOf("message" to message, "sender" to sender))
        if (!failed.contains(entry)) {
            failed.add(entry)
            prefs.edit().putString("failed_messages", gson.toJson(failed)).apply()
        }
    }


    /** Load failed list */
    private fun loadFailedMessages(): List<String> {
        val json = prefs.getString("failed_messages", "[]")
        return gson.fromJson(json, Array<String>::class.java).toList()
    }

    /**
     * Retry all failed messages.
     * Handles both:
     *  - Plain string messages (saved by saveFailedMessage(message))
     *  - JSON object entries (saved by saveFailedMessage(message, sender))
     */
    private fun retryFailedMessages() {
        val failed = loadFailedMessages().toMutableList()
        for (entry in failed) {
            try {
                // Try parsing as JSON object first (sender-aware format)
                val map = gson.fromJson(entry, Map::class.java)
                val msg = map["message"] as? String
                val sender = map["sender"] as? String ?: "Unknown"
                if (msg != null) {
                    forwardMessage(msg, sender)
                } else {
                    // If "message" key is missing, treat the whole entry as the message
                    forwardMessage(entry)
                }
            } catch (e: Exception) {
                // Not a JSON object — treat as plain text message
                forwardMessage(entry)
            }
        }
        prefs.edit().putString("failed_messages", "[]").apply()
    }

    /** Retry loop every 30 minutes using Handler (main thread) instead of daemon timer thread */
    private fun startRetryLoop() {
        retryHandler = Handler(Looper.getMainLooper())
        retryRunnable = object : Runnable {
            override fun run() {
                try {
                    retryFailedMessages()
                    syncLastInboxMessages()
                } catch (e: Exception) {
                    android.util.Log.e("SmsForwardService", "Error in retry loop", e)
                }
                // Re-schedule for next interval
                retryHandler?.postDelayed(this, RETRY_INTERVAL_MS)
            }
        }
        // Run immediately, then every 30 minutes
        retryHandler?.post(retryRunnable!!)
    }

    /** Query last 100 SMS from inbox and forward unsent ones */
    private fun syncLastInboxMessages() {
        try {
            val uriSms = Uri.parse("content://sms/inbox")
            val cursor: Cursor? = contentResolver.query(
                uriSms,
                arrayOf("_id", "address", "body", "date"),
                null,
                null,
                "date DESC LIMIT 100"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val sender = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))

                    if (!prefs.getBoolean("sent_$id", false)) {
                        forwardMessage(body, sender)
                        prefs.edit().putBoolean("sent_$id", true).apply()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsForwardService", "Error syncing inbox", e)
        } finally {
            // Prune old sent_* entries to prevent SharedPreferences bloat
            pruneSentIds()
        }
    }

    /**
     * Prune old sent_* entries from SharedPreferences to prevent unbounded growth.
     * Keeps only the most recent MAX_SENT_IDS entries.
     */
    private fun pruneSentIds() {
        try {
            val allEntries = prefs.all
            val sentIds = allEntries.keys
                .filter { it.startsWith("sent_") }
                .map { it.removePrefix("sent_").toLongOrNull() }
                .filterNotNull()
                .sortedDescending()

            if (sentIds.size > MAX_SENT_IDS) {
                val idsToRemove = sentIds.drop(MAX_SENT_IDS)
                val editor = prefs.edit()
                for (id in idsToRemove) {
                    editor.remove("sent_$id")
                }
                editor.apply()
                android.util.Log.d("SmsForwardService", "Pruned ${idsToRemove.size} old sent_* entries")
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsForwardService", "Error pruning sent IDs", e)
        }
    }
}
