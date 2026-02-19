package com.ndovunine.smstourls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val bundle: Bundle? = intent.extras
        if (bundle != null) {
            val pdus = bundle.get("pdus") as Array<*>
            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                val messageBody = sms.messageBody
                val sender = sms.originatingAddress ?: "Unknown"

                // ---------------------------------------------------------------
                // SPAM CHECK
                // ---------------------------------------------------------------
                if (SpamDetector.isSpam(messageBody)) {
                    val matched = SpamDetector.getMatchedKeywords(messageBody)
                    Log.w(TAG, "SPAM intercepted from [$sender]. Keywords: $matched")

                    // 1. Abort the broadcast — stops the system SMS app from:
                    //    - storing the message in the inbox
                    //    - playing a notification sound
                    //    - showing a notification
                    //    This ONLY works when android:priority is high enough in the manifest.
                    //    May not work on all devices
                    abortBroadcast()

                    Log.i(TAG, "Broadcast aborted for spam message from [$sender]")

                    // 2. No need to forward — just return
                    return
                }

                // Send SMS to foreground service
                val serviceIntent = Intent(context, SmsForwardService::class.java).apply {
                    putExtra("sms_message", messageBody)
                    putExtra("sms_sender", sender)
                }
                context.startService(serviceIntent)
            }
        }
    }
}