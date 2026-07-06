package com.ndovunine.smstourls

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.RECEIVE_SMS] == true &&
                    permissions[Manifest.permission.READ_SMS] == true &&
                    permissions[Manifest.permission.INTERNET] == true
            if (granted) {
                // After foreground permissions are granted, request background location
                requestBackgroundLocation()
            }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "Background location permission granted (Allow all the time)")
            } else {
                Log.d(TAG, "Background location permission denied")
            }
            startSmsService()
        }

    private val smsRoleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (DefaultSmsAppHelper.isDefaultSmsApp(this)) {
                Toast.makeText(this, "Default SMS app set successfully", Toast.LENGTH_SHORT).show()
                startSmsService()
            } else {
                Toast.makeText(this, "Default SMS app not set — spam blocking may not work", Toast.LENGTH_LONG).show()
            }
        }

    private lateinit var failedReceiver: BroadcastReceiver
    private lateinit var listView: ListView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            listView = findViewById(R.id.listFailedMessages)

            val btnSettings = findViewById<Button>(R.id.btnSettings)
            val btnStart = findViewById<Button>(R.id.btnStartService)

            btnStart.setOnClickListener {
                checkAndRequestPermissions()
            }

            btnSettings.setOnClickListener {
                try {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open SettingsActivity", e)
                    Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                }
            }

            refreshList()

            // Create and store the receiver
            failedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        refreshList()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in failedReceiver onReceive", e)
                    }
                }
            }

            // Register with the required flag for Android 12+
            val intentFilter = IntentFilter(SmsForwardService.ACTION_FAILED_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(failedReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            } else {
                @SuppressLint("UnspecifiedRegisterReceiverFlag")
                registerReceiver(failedReceiver, intentFilter)
            }

            // Schedule periodic health check to restart the service if killed
            scheduleServiceHealthCheck()

            // Auto-start the foreground service as soon as the app opens
            checkAndRequestPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in onCreate", e)
            Toast.makeText(this, "App initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun refreshList() {
        try {
            val prefs = getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)
            val json = prefs.getString("failed_messages", "[]") ?: "[]"
            val rawEntries = Gson().fromJson(json, Array<String>::class.java).toMutableList()

            // Convert raw JSON strings to display text
            val displayTexts = rawEntries.map { entry ->
                try {
                    val failedEntry = Gson().fromJson(entry, FailedMessageEntry::class.java)
                    if (failedEntry.body.isNotBlank()) {
                        FailedMessageEntry.displayText(failedEntry)
                    } else {
                        entry // fallback for non-FailedMessageEntry strings
                    }
                } catch (_: Exception) {
                    // Legacy format (plain string or old map JSON) — show as-is
                    try {
                        val map = Gson().fromJson(entry, Map::class.java)
                        val msg = map["message"] as? String ?: entry
                        msg
                    } catch (_: Exception) {
                        entry
                    }
                }
            }.toMutableList()

            val adapter = object : android.widget.ArrayAdapter<String>(
                this, R.layout.item_failed_message, R.id.tvMessage, displayTexts
            ) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_failed_message, parent, false)
                    val tvMessage = view.findViewById<android.widget.TextView>(R.id.tvMessage)
                    val btnRetry = view.findViewById<Button>(R.id.btnRetry)
                    val displayText = displayTexts[position]
                    tvMessage.text = displayText
                    btnRetry.setOnClickListener {
                        val intent = Intent(this@MainActivity, SmsForwardService::class.java).apply {
                            action = SmsForwardService.ACTION_RETRY_MESSAGE
                            putExtra(SmsForwardService.EXTRA_RETRY_MESSAGE, displayText)
                        }
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                        displayTexts.removeAt(position)
                        notifyDataSetChanged()
                    }
                    return view
                }
            }
            listView.adapter = adapter
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing failed messages list", e)
            Toast.makeText(this, "Could not load failed messages", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Schedule a periodic WorkManager task to check if SmsForwardService is alive.
     * If the service has been killed, it will be restarted.
     */
    private fun scheduleServiceHealthCheck() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val healthCheckRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "ServiceHealthCheck",
                ExistingPeriodicWorkPolicy.KEEP,
                healthCheckRequest
            )
            Log.d(TAG, "Service health check scheduled (every 15 minutes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule service health check", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::failedReceiver.isInitialized) {
            unregisterReceiver(failedReceiver)
        }
    }

    /**
     * Request background location permission on Android 10+.
     * This is what triggers the "Allow all the time" / "Allow only while using the app" dialog.
     * Must be called AFTER foreground location permission is already granted.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Request background location separately — this shows the "Allow all the time" option
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        // No background location needed or already granted — proceed
        startSmsService()
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.RECEIVE_SMS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.READ_SMS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.INTERNET)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.SEND_SMS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) neededPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            startSmsService()
        }
    }

    private fun startSmsService() {
        val serviceIntent = Intent(this, SmsForwardService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        if (!DefaultSmsAppHelper.isDefaultSmsApp(this)) {
            requestDefaultSmsRole()
        }
    }

    private fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            Log.d(TAG, "RoleManager SMS role available: ${roleManager.isRoleAvailable(RoleManager.ROLE_SMS)}")
            Log.d(TAG, "RoleManager SMS role held: ${roleManager.isRoleHeld(RoleManager.ROLE_SMS)}")
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                smsRoleLauncher.launch(intent)
            }
        } else {
            // Pre-Android 10: use legacy fallback
            DefaultSmsAppHelper.promptSetAsDefault(this)
        }
    }
}
