package com.ndovunine.smstourls

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.gson.Gson


class MainActivity : ComponentActivity() {


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.RECEIVE_SMS] == true &&
                    permissions[Manifest.permission.READ_SMS] == true &&
                    permissions[Manifest.permission.INTERNET] == true
            if (granted) {
                startSmsService()
            }
        }

    private lateinit var failedReceiver: BroadcastReceiver
    private lateinit var listView: ListView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listFailedMessages)  // Moved here from class property

        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnStart = findViewById<Button>(R.id.btnStartService)

        btnStart.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshList()

        // Create and store the receiver
        failedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshList()
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

        // Auto-start the foreground service as soon as the app opens
        checkAndRequestPermissions()
    }

    fun refreshList() {
        val prefs = getSharedPreferences("sms_forwarder", Context.MODE_PRIVATE)
        val json = prefs.getString("failed_messages", "[]")
        val messages = Gson().fromJson(json, Array<String>::class.java).toMutableList()

        val adapter = object : android.widget.ArrayAdapter<String>(
            this, R.layout.item_failed_message, R.id.tvMessage, messages
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_failed_message, parent, false)
                val tvMessage = view.findViewById<android.widget.TextView>(R.id.tvMessage)
                val btnRetry = view.findViewById<Button>(R.id.btnRetry)
                val msg = messages[position]
                tvMessage.text = msg
                btnRetry.setOnClickListener {
                    val intent = Intent(this@MainActivity, SmsForwardService::class.java).apply {
                        action = SmsForwardService.ACTION_RETRY_MESSAGE
                        putExtra(SmsForwardService.EXTRA_RETRY_MESSAGE, msg)
                    }
                    ContextCompat.startForegroundService(this@MainActivity, intent)
                    messages.removeAt(position)
                    notifyDataSetChanged()
                }
                return view
            }
        }
        listView.adapter = adapter
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::failedReceiver.isInitialized) {
            unregisterReceiver(failedReceiver)
        }
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
            // Show a dialog explaining why, then:
            DefaultSmsAppHelper.promptSetAsDefault(this)
        }
    }
}