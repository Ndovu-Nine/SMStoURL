package com.ndovunine.smstourls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Stub required for default SMS app eligibility. MMS not used in this app. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { /* no-op */ }
}