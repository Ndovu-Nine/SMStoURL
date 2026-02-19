package com.ndovunine.smstourls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle: Bundle? = intent.extras
        if (bundle != null) {
            val pdus = bundle.get("pdus") as Array<*>
            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                val messageBody = sms.messageBody
                val sender = sms.originatingAddress ?: "Unknown"

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