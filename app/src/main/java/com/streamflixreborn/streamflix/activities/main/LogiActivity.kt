package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.streamflixreborn.streamflix.R

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val prefs = getSharedPreferences("remix_panel", MODE_PRIVATE)
        
        // Si ya entro una vez, entra directo al panel
        if (prefs.getBoolean("logueado", false)) {
            startActivity(Intent(this, MainMobileActivity::class.java))
            finish()
            return
        }

        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btn = findViewById<Button>(R.id.btn_login)

        btn.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString().trim()

            if (u == "remix" && p == "remix123") {
                prefs.edit().putBoolean("logueado", true).apply()
                startActivity(Intent(this, MainMobileActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Usuario o clave mal", Toast.LENGTH_SHORT).show()
            }
        }
    }
}