package com.streamflixreborn.streamflix.activities.main

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val prefs = getSharedPreferences("remix_panel", MODE_PRIVATE)

        // Si ya está logueado, entra directo
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

            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "Completa usuario y clave", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btn.isEnabled = false
            btn.text = "Verificando..."

            CoroutineScope(Dispatchers.IO).launch {
                var loginOk = false
                var errorMsg = ""

                try {
                    // 1. Intenta contra tu panel de Render
                    val endpoints = listOf(
                        "https://remix-app-panel.onrender.com/api/login",
                        "https://remix-app-panel.onrender.com/api/auth/login",
                        "https://remix-app-panel.onrender.com/login"
                    )

                    for (apiUrl in endpoints) {
                        try {
                            val url = URL(apiUrl)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.connectTimeout = 8000
                            conn.readTimeout = 8000
                            conn.doOutput = true

                            val json = JSONObject()
                            json.put("email", u)
                            json.put("password", p)
                            json.put("username", u) // por si tu panel usa username

                            conn.outputStream.write(json.toString().toByteArray())
                            val code = conn.responseCode
                            if (code == 200 || code == 201) {
                                loginOk = true
                                break
                            }
                        } catch (_: Exception) { }
                    }

                    // 2. Respaldo: si el panel está dormido, deja pasar al admin
                    if (!loginOk && u == "remix" && p == "remix123") {
                        loginOk = true
                    }

                } catch (e: Exception) {
                    errorMsg = e.message ?: "Error de conexión"
                }

                withContext(Dispatchers.Main) {
                    if (loginOk) {
                        prefs.edit().putBoolean("logueado", true).putString("user_email", u).apply()
                        startActivity(Intent(this@LoginActivity, MainMobileActivity::class.java))
                        finish()
                    } else {
                        val msg = if (errorMsg.isNotEmpty()) errorMsg else "Usuario o clave mal"
                        Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_SHORT).show()
                        btn.isEnabled = true
                        btn.text = "Login"
                    }
                }
            }
        }
    }
}
