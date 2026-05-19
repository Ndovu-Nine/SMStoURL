package com.ndovunine.smstourls

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Stub required for default SMS app eligibility. Sending SMS not used in this app. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
