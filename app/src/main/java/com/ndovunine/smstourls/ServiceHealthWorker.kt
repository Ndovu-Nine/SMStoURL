package com.ndovunine.smstourls

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Periodic WorkManager worker that checks if SmsForwardService is running.
 * If the service has been killed by the system, it will be restarted.
 */
class ServiceHealthWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "Health check: ensuring SmsForwardService is alive")
            val serviceIntent = Intent(applicationContext, SmsForwardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(applicationContext, serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
            Log.d(TAG, "Health check completed: SmsForwardService started/restarted")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
            Result.retry()
        }
    }
}