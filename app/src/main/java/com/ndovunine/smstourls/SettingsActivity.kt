package com.ndovunine.smstourls

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val edtEmail = findViewById<EditText>(R.id.edtEmail)
        val edtUrls = findViewById<EditText>(R.id.edtUrls)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        // Load saved values
        edtEmail.setText(prefs.getString("email", ""))
        edtUrls.setText(prefs.getString("urls", ""))

        btnSave.setOnClickListener {
            val email = edtEmail.text.toString().trim()
            val urls = edtUrls.text.toString().trim()

            prefs.edit()
                .putString("email", email)
                .putString("urls", urls)
                .apply()

            finish() // close settings
        }
    }
}