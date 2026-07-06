package com.ndovunine.smstourls

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * SmsInterceptorService
 *
 * CarrierMessagingService is USELESS for normal apps — it only fires for
 * carrier-privileged apps (SIM-whitelisted). Dropped entirely.
 *
 * The ONLY reliable way to intercept, block, and delete SMS on Android 4.4+
 * is to become the DEFAULT SMS APP. This service handles writing incoming
 * SMS to the inbox (required of the default SMS app), while applying spam
 * filtering before the message is persisted or notified.
 *
 * Flow:
 *   SmsReceiver (broadcast, priority=999) receives SMS_DELIVER intent
 *     → spam? → drop, abort broadcast, write nothing
 *     → clean? → call this service to write to inbox + forward
 */
class SmsInterceptorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIncomingSms(it) }
        return START_NOT_STICKY
    }

    private fun handleIncomingSms(intent: Intent) {
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            for (sms in messages) {
                val sender      = sms.originatingAddress ?: "Unknown"
                val messageBody = sms.messageBody ?: continue
                val timestamp   = sms.timestampMillis

                if (SpamDetector.isSpam(messageBody)) {
                    val matched = SpamDetector.getMatchedKeywords(messageBody)
                    Log.w(TAG, "SPAM blocked from [$sender]. Keywords: $matched")
                    // Simply do not write to inbox — message ceases to exist
                    continue
                }

                Log.d(TAG, "Clean SMS from [$sender] — writing to inbox and forwarding")

                // 1. Write to inbox (mandatory when you are the default SMS app)
                writeToInbox(sender, messageBody, timestamp)

                // 2. Forward to your forwarding service
                val forwardIntent = Intent(this, SmsForwardService::class.java).apply {
                    putExtra("sms_message", messageBody)
                }
                startService(forwardIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleIncomingSms", e)
        }
    }

    /**
     * Writes the SMS to the system inbox content provider.
     * Required — if you are the default SMS app and you skip this,
     * the message is lost forever (which is exactly what we want for spam,
     * but clean messages must be persisted here).
     */
    private fun writeToInbox(sender: String, body: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)   // unread
                put(Telephony.Sms.SEEN, 0)   // unseen
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Log.d(TAG, "Message from [$sender] written to inbox")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write SMS to inbox", e)
        }
    }

    companion object {
        private const val TAG = "SmsInterceptorService"
    }
}