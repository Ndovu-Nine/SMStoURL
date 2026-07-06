package com.ndovunine.smstourls

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        const val EXTRA_RETRY_BODY = "retry_body"
        private const val RETRY_INTERVAL_MS = 1 * 60 * 1000L // 1 minutes
        private const val MAX_SENT_IDS = 2000 // Keep only the last 500 sent IDs
    }

    // TypeToken for deserializing the server PAYLOAD response
    private val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
    private val messagesListType = object : TypeToken<List<Map<String, Any?>>>() {}.type

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
    private var isStopping = false
    private val NOTIF_ID = 1
    private val channelId = "SmsForwarderChannel"

    private var forwardedCount: Int
        get() = prefs.getInt("forwarded_count", 0)
        set(value) = prefs.edit().putInt("forwarded_count", value).apply()

    private var failedCount: Int
        get() = prefs.getInt("failed_count", 0)
        set(value) = prefs.edit().putInt("failed_count", value).apply()


    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val forwarded = forwardedCount
        val failed = failedCount
        val contentText = if (failed > 0) {
            "$forwarded forwarded, $failed failed"
        } else {
            "$forwarded SMS forwarded"
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("SMS to URLs")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SMS to URLs")
                .setContentText(contentText)
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
                stopServiceSafely()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Do NOT schedule a restart if the service is stopping — this would
        // resurrect the service while Android expects it to die, causing
        // ForegroundServiceDidNotStopInTimeException.
        if (isStopping) {
            Log.d("SmsForwardService", "Service is stopping — skipping alarm restart")
            return
        }
        // If the user swipes the app away, schedule an alarm to restart the service
        // after a short delay. This keeps SMS forwarding alive even when the app
        // UI is dismissed.
        try {
            val restartIntent = Intent(this, SmsForwardService::class.java)
            val pendingIntent = PendingIntent.getService(
                this,
                0,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerTime = SystemClock.elapsedRealtime() + 30_000 // 30 seconds
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
            Log.d("SmsForwardService", "Scheduled restart via AlarmManager after task removal")
        } catch (e: Exception) {
            Log.e("SmsForwardService", "Failed to schedule restart on task removal", e)
        }
    }

    override fun onDestroy() {
        isStopping = true
        // Stop the retry handler to prevent leaks
        retryHandler?.removeCallbacksAndMessages(null)
        retryHandler = null
        retryRunnable = null
        // Safety net: remove foreground notification to satisfy Android's timeout requirement
        stopForegroundSafely()
        super.onDestroy()
    }

    private var isForeground = false

    /**
     * Call stopForeground() then stopSelf() to satisfy Android's requirement that
     * foreground services remove their notification before stopping.
     * Sets isStopping flag to prevent retry loop from re-scheduling.
     */
    private fun stopServiceSafely() {
        isStopping = true
        stopForegroundSafely()
        stopSelf()
    }

    /** Remove the foreground notification if currently in foreground. */
    private fun stopForegroundSafely() {
        try {
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
        } catch (_: Exception) { }
    }

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

    private fun removeFailedMessage(displayText: String) {
        val failed = loadFailedMessages().toMutableList()
        failed.removeAll { entry ->
            try {
                val failedEntry = gson.fromJson(entry, FailedMessageEntry::class.java)
                FailedMessageEntry.displayText(failedEntry) == displayText
            } catch (_: Exception) {
                entry == displayText  // fallback for legacy plain strings
            }
        }
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
        val json: String
        try {
            json = gson.toJson(payload)
        } catch (e: Exception) {
            Log.e("SmsForwardService", "Failed to serialize payload to JSON", e)
            saveFailedMessage(message, sender, "Serialization error: ${e.message}")
            return
        }

        val urlList = urls.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (url in urlList) {
            @Suppress("DEPRECATION")
            val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), json)
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("SmsForwardService", "Failed to forward to $url", e)
                    saveFailedMessage(message, sender, e.message ?: "Network error", isPermanent = false)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val reason = extractFailureReason(response)
                            val permanent = isServerProvidedReason(reason)
                            Log.w("SmsForwardService", "HTTP ${response.code} from $url: $reason")
                            saveFailedMessage(message, sender, reason, isPermanent = permanent)
                        } else {
                            // Check if the server PAYLOAD indicates failure despite HTTP 200
                            val responseBody = response.body?.string()
                            if (responseBody != null) {
                                try {
                                    val payloadMap = gson.fromJson<Map<String, Any?>>(responseBody, payloadType)
                                    val success = payloadMap["success"] as? Boolean ?: true
                                    if (!success) {
                                        val reason = extractErrorFromPayload(payloadMap)
                                        Log.w("SmsForwardService", "Server reported failure from $url: $reason")
                                        saveFailedMessage(message, sender, reason, isPermanent = true)
                                    } else {
                                        forwardedCount += 1
                                        updateNotification()
                                    }
                                } catch (_: Exception) {
                                    // Can't parse JSON — assume success since HTTP was OK
                                    forwardedCount += 1
                                    updateNotification()
                                }
                            } else {
                                forwardedCount += 1
                                updateNotification()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SmsForwardService", "Error processing response from $url", e)
                        saveFailedMessage(message, sender, "Response processing error: ${e.message}")
                    } finally {
                        try {
                            response.close()
                        } catch (_: Exception) { }
                    }
                }
            })
        }
    }

    /**
     * Extract a human-readable failure reason from an HTTP error response.
     * Attempts to parse the body as a server PAYLOAD first, falls back to HTTP status.
     */
    private fun extractFailureReason(response: Response): String {
        return try {
            val body = response.body?.string()
            if (body != null) {
                val payloadMap = gson.fromJson<Map<String, Any?>>(body, payloadType)
                val reason = extractErrorFromPayload(payloadMap)
                if (reason != "Server error") reason else "HTTP ${response.code}"
            } else {
                "HTTP ${response.code}"
            }
        } catch (_: Exception) {
            "HTTP ${response.code}"
        }
    }

    /**
     * Extract error details from a PAYLOAD map:
     *   1. Check root "error" field
     *   2. Check first message's sent_result_message / delivery_result_message
     *   3. Fall back to "Server error"
     */
    private fun extractErrorFromPayload(payload: Map<String, Any?>): String {
        // Try root-level error field
        val error = payload["error"] as? String
        if (!error.isNullOrBlank()) return error

        // Try messages array for per-message failure details
        val messagesRaw = payload["messages"]
        if (messagesRaw is List<*>) {
            val messagesJson = gson.toJson(messagesRaw)
            try {
                val messages: List<Map<String, Any?>> = gson.fromJson(messagesJson, messagesListType)
                for (msg in messages) {
                    val sentResultMsg = msg["sent_result_message"] as? String
                    if (!sentResultMsg.isNullOrBlank()) return sentResultMsg

                    val deliveryResultMsg = msg["delivery_result_message"] as? String
                    if (!deliveryResultMsg.isNullOrBlank()) return deliveryResultMsg
                }
            } catch (_: Exception) { }
        }

        return "Server error"
    }

    /**
     * Returns true if the reason was provided by the server (not a generic
     * client-side or network error). Server reasons indicate permanent failures
     * that should NOT be auto-retried.
     */
    private fun isServerProvidedReason(reason: String): Boolean {
        return reason != "Unknown error"
                && reason != "Server error"
                && !reason.startsWith("HTTP ")
                && reason != "Serialization error"
                && reason != "Network error"
                && reason != "Response processing error"
    }

    /** Save failed message as structured FailedMessageEntry JSON */
    private fun saveFailedMessage(
        message: String,
        sender: String = "Unknown",
        reason: String = "Unknown error",
        isPermanent: Boolean = false
    ) {
        try {
            val entry = FailedMessageEntry.fromMessage(message, sender, reason, isPermanent)
            val entryJson = gson.toJson(entry)
            val failed = loadFailedMessages().toMutableList()
            if (!failed.contains(entryJson)) {
                failed.add(entryJson)
                prefs.edit().putString("failed_messages", gson.toJson(failed)).apply()
                failedCount = failed.filter { entryStr ->
                    try {
                        gson.fromJson(entryStr, FailedMessageEntry::class.java).let { true }
                    } catch (_: Exception) { true }
                }.size
                updateNotification()
                broadcastFailedUpdated()
            }
        } catch (e: Exception) {
            Log.e("SmsForwardService", "Error saving failed message", e)
        }
    }

    /** Load failed list (JSON strings of FailedMessageEntry) */
    private fun loadFailedMessages(): List<String> {
        return try {
            val json = prefs.getString("failed_messages", "[]") ?: "[]"
            gson.fromJson(json, Array<String>::class.java).toList()
        } catch (e: Exception) {
            Log.e("SmsForwardService", "Error loading failed messages", e)
            emptyList()
        }
    }

    /**
     * Retry all non-permanent failed messages.
     * Handles:
     *  - FailedMessageEntry JSON (current format) — skips if isPermanent=true
     *  - Legacy map JSON {"message":..., "sender":...} (old format)
     *  - Plain string messages (oldest format)
     *
     * Permanent entries (server-provided reasons) are preserved forever
     * and can only be retried manually by the user.
     */
    private fun retryFailedMessages() {
        val failed = loadFailedMessages().toMutableList()
        val remaining = mutableListOf<String>()

        for (entry in failed) {
            try {
                // Try parsing as FailedMessageEntry first
                val failedEntry = gson.fromJson(entry, FailedMessageEntry::class.java)
                if (failedEntry.body.isNotBlank()) {
                    if (failedEntry.isPermanent) {
                        // Server reason — keep forever, only user can retry
                        remaining.add(entry)
                        continue
                    }
                    forwardMessage(failedEntry.body, failedEntry.sender)
                    continue
                }
            } catch (_: Exception) { }

            // Legacy or plain string — retry normally (not permanent)
            try {
                val map = gson.fromJson(entry, Map::class.java)
                val msg = map["message"] as? String
                val sender = map["sender"] as? String ?: "Unknown"
                if (msg != null) {
                    forwardMessage(msg, sender)
                } else {
                    forwardMessage(entry)
                }
            } catch (_: Exception) {
                forwardMessage(entry)
            }
        }

        // Preserve permanent entries; clear only the retried ones
        prefs.edit().putString("failed_messages", gson.toJson(remaining)).apply()
        failedCount = remaining.size
        updateNotification()
    }

    /** Retry loop every 30 minutes using Handler (main thread) instead of daemon timer thread */
    private fun startRetryLoop() {
        retryHandler = Handler(Looper.getMainLooper())
        retryRunnable = object : Runnable {
            override fun run() {
                // Bail early if the service is shutting down to avoid keeping
                // the process alive past Android's foreground service timeout.
                if (isStopping) return
                try {
                    retryFailedMessages()
                    syncLastInboxMessages()
                } catch (e: Exception) {
                    android.util.Log.e("SmsForwardService", "Error in retry loop", e)
                }
                // Re-schedule for next interval only if still alive
                if (!isStopping) {
                    retryHandler?.postDelayed(this, RETRY_INTERVAL_MS)
                }
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
