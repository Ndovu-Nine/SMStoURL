package com.ndovunine.smstourls

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.ndovunine.smstourls.ui.theme.SmstourlsTheme

import android.Manifest
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

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


    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnStart = findViewById<Button>(R.id.btnStartService)
        /*val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)*/
        enableEdgeToEdge()
        /*setContent {
            SmstourlsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }*/

        btnStart.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnSettings.setOnClickListener {
            /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }else{
                
            }*/
            startActivity(Intent(this, SettingsActivity::class.java))

        }

        /*btnStart.setOnClickListener {
            val intent = Intent(this, SmsForwardService::class.java)
            startForegroundService(intent)
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, SmsForwardService::class.java)
            stopService(intent)
        }*/
        // Start foreground service
        /*val intent = Intent(this, SmsForwardService::class.java)
        startForegroundService(intent)*/
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
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SmstourlsTheme {
        Greeting("Android")
    }
}
