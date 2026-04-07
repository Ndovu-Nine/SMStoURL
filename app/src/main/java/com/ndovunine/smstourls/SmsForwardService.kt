package com.ndovunine.smstourls

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Timer
import kotlin.concurrent.fixedRateTimer


class SmsForwardService : Service() {

    private val client:OkHttpClient by lazy { getUnsafeOkHttpClient() }
    private val gson = Gson()
    private val prefs by lazy { getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE) }

    // Example: You can later load these from SharedPreferences
    private val urls = listOf(
        "http://yoururl:port/sms",
        "https://fivayapi.ndovunine.com/api/transaction/addSMS"
    )

    // Email is set by user in settings (later we’ll add UI)
    private val email = "email@domain.com"

    companion object {
        const val ACTION_RETRY_MESSAGE = "com.ndovunine.smstourls.RETRY_MESSAGE"
        const val ACTION_FAILED_UPDATED = "com.ndovunine.smstourls.FAILED_UPDATED"
        const val EXTRA_RETRY_MESSAGE = "retry_message"
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


    private var retryTimer: Timer? = null
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
                .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("SMS to URLs")
                .setContentText("$count SMS forwarded")
                .setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
        notificationManager.notify(NOTIF_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        startRetryLoop()
    }

    /*override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Receive SMS message from intent
        val message = intent?.getStringExtra("sms_message")
        val sender = intent?.getStringExtra("sms_sender") ?: "Unknown"
        if (!message.isNullOrBlank()) {
            forwardMessage(message,sender)
        }
        return START_STICKY
    }*/

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundService() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        var notification: Notification


        val channelId = "SmsForwarderChannel"
        val channelName = "SMS to URLs"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            if(notificationManager!=null){
                notificationManager.createNotificationChannel(channel)
            }

            notification = Notification.Builder(this, channelId)
                .setContentTitle("SMS to URLs")
                .setContentText("Forwarding SMS to saved URLs")
                .setSmallIcon(R.mipmap.sym_def_app_icon) // Ensure this icon exists in res/drawable
                .build()
        }
        else{
            notification = Notification.Builder(this)
                .setContentTitle("SMS to URLs")
                .setContentText("Forwarding SMS to saved URLs")
                .setSmallIcon(R.mipmap.sym_def_app_icon) // Ensure this icon exists in res/drawable
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }

        startForeground(1, notification)
    }


    private fun forwardMessage(message: String, sender: String = "Unknown") {
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val email = settings.getString("email", "") ?: ""
        val urls = settings.getString("urls", "") ?: ""

        if (urls.isEmpty()) return

        val payload = mapOf("message" to message,"sender" to sender, "email" to email)
        val json = gson.toJson(payload)

        val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), json)
        val urlList = urls.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        for (url in urlList) {
            val request = Request.Builder().url(url).post(requestBody).build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    saveFailedMessage(message,sender) // store for retry
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        saveFailedMessage(message,sender)
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
    private fun retryFailedMessages() {
        val failed = loadFailedMessages().toMutableList()
        for (entry in failed) {
            val map = gson.fromJson(entry, Map::class.java)
            forwardMessage(map["message"] as String, map["sender"] as? String ?: "Unknown")
        }
        prefs.edit().putString("failed_messages", "[]").apply()
    }

    /** Retry loop every 30 minutes */
    private fun startRetryLoop() {
        retryTimer = fixedRateTimer("retry", true, 0L, 30 * 60 * 1000) {
            retryFailedMessages()
            // Also check inbox last 100 SMS
            syncLastInboxMessages()
        }
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
                "date DESC LIMIT 5"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val sender = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"  // ← add
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))

                    if (!prefs.getBoolean("sent_$id", false)) {
                        forwardMessage(body, sender)  // ← pass sender
                        prefs.edit().putBoolean("sent_$id", true).apply()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
