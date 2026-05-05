package com.ndovunine.smstourls

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.widget.Toast

/**
 * Helper to prompt the user to set this app as the default SMS app.
 *
 * Call promptSetAsDefault() from MainActivity — ideally show a rationale
 * dialog first explaining why this gives better spam blocking.
 */
object DefaultSmsAppHelper {

    fun isDefaultSmsApp(context: Context): Boolean {
        return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    }

    fun promptSetAsDefault(activity: Activity) {
        if (isDefaultSmsApp(activity)) {
            Toast.makeText(activity, "Already the default SMS app", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
        }
        activity.startActivity(intent)
    }

    /**
     * Call this to restore the previous default SMS app when the user
     * wants to stop using this app as default.
     */
    fun relinquishDefault(activity: Activity) {
        // Android handles this via the same ACTION_CHANGE_DEFAULT flow —
        // direct the user to Settings > Default Apps > SMS
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        activity.startActivity(intent)
    }
}