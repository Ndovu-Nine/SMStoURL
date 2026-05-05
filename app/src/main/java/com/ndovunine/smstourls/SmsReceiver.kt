package com.ndovunine.smstourls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    private val TAG = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {

        // Handle the two different SMS intents:
        //
        // SMS_DELIVER_ACTION  → fired ONLY at the default SMS app. Use this to
        //                       write to inbox. You have full control here.
        //
        // SMS_RECEIVED_ACTION → fired at ALL apps with RECEIVE_SMS permission.
        //                       abortBroadcast() works here (priority=999),
        //                       but the default SMS app uses DELIVER, not this.
        //
        // When your app IS the default SMS app, handle DELIVER and ignore RECEIVED.
        // When your app is NOT the default SMS app, use RECEIVED + abortBroadcast().

        when (intent.action) {

            Telephony.Sms.Intents.SMS_DELIVER_ACTION -> {
                // We are the default SMS app — full control
                handleSmsDeliver(context, intent)
            }

            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                // We are NOT the default SMS app — best-effort abort
                handleSmsReceived(intent)
            }
        }
    }

    /**
     * Called when we ARE the default SMS app.
     * Spam → silently discard (never written to inbox, no notification).
     * Clean → pass to SmsInterceptorService to write to inbox + forward.
     */
    private fun handleSmsDeliver(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        for (sms in messages) {
            val sender      = sms.originatingAddress ?: "Unknown"
            val messageBody = sms.messageBody ?: continue

            if (SpamDetector.isSpam(messageBody)) {
                Log.w(TAG, "SPAM silently discarded from [$sender] (default app mode)")
                // Do nothing — message is gone, no inbox write, no notification
                continue
            }

            val serviceIntent = Intent(context, SmsInterceptorService::class.java).apply {
                action = intent.action
                putExtras(intent)
            }
            context.startService(serviceIntent)
            break // service handles all PDUs in the intent at once
        }
    }

    /**
     * Called when we are NOT the default SMS app.
     * Uses abortBroadcast() as best-effort — may not work on all OEMs/Android 10+.
     */
    private fun handleSmsReceived(intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        for (sms in messages) {
            val sender      = sms.originatingAddress ?: "Unknown"
            val messageBody = sms.messageBody ?: continue

            if (SpamDetector.isSpam(messageBody)) {
                Log.w(TAG, "SPAM — attempting abortBroadcast() from [$sender]")
                abortBroadcast()
                return
            }
        }
    }
}