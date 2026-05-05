package com.ndovunine.smstourls

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.widget.Toast

/**
 * Helper to prompt the user to set this app as the default SMS app.
 *
 * On Android 10+ (API 29+), uses RoleManager which is the official way
 * to request the default SMS role. Falls back to the old
 * ACTION_CHANGE_DEFAULT intent on older versions.
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            promptSetAsDefaultRoleManager(activity)
        } else {
            promptSetAsDefaultLegacy(activity)
        }
    }

    /**
     * Android 10+ (API 29+): Use RoleManager to request the default SMS role.
     * This is the only way your app will appear in the system chooser on
     * Android 15 and later.
     */
    private fun promptSetAsDefaultRoleManager(activity: Activity) {
        val roleManager = activity.getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        ) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            activity.startActivityForResult(intent, REQUEST_CODE_DEFAULT_SMS)
        }
    }

    /**
     * Pre-Android 10: Use the legacy ACTION_CHANGE_DEFAULT intent.
     */
    private fun promptSetAsDefaultLegacy(activity: Activity) {
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

    private const val REQUEST_CODE_DEFAULT_SMS = 1001
}
