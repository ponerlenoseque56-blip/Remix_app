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

        if (prefs.getBoolean("logueado", false)) {
            startActivity(Intent(this, MainMobileActivity::class.java))
            finish()
            return
        }

        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btn = findViewById<Button>(R.id.btn_login)

        btn.setOnClickListener {
            val email = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Completa email y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btn.isEnabled = false
            btn.text = "Entrando..."

            CoroutineScope(Dispatchers.IO).launch {
                var ok = false
                var msgError = "Email o contraseña incorrecta"

                // RUTAS QUE USA TU PANEL DE RENDER
                val urls = listOf(
                    "https://remix-app-panel.onrender.com/api/auth/login",
                    "https://remix-app-panel.onrender.com/api/users/login",
                    "https://remix-app-panel.onrender.com/api/login"
                )

                for (apiUrl in urls) {
                    try {
                        val conn = URL(apiUrl).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.doOutput = true

                        val body = JSONObject()
                        body.put("email", email)
                        body.put("password", pass)

                        conn.outputStream.use { it.write(body.toString().toByteArray()) }

                        val code = conn.responseCode
                        val response = try {
                            conn.inputStream.bufferedReader().readText()
                        } catch (e: Exception) {
                            conn.errorStream?.bufferedReader()?.readText() ?: ""
                        }

                        // Si tu panel devuelve 200, es login correcto
                        if (code == 200 || code == 201) {
                            // si devuelve token lo guardamos
                            try {
                                val json = JSONObject(response)
                                val token = json.optString("token", "")
                                if (token.isNotEmpty()) {
                                    prefs.edit().putString("panel_token", token).apply()
                                }
                            } catch (_: Exception) {}
                            ok = true
                            break
                        } else {
                            // si es 401 es clave mal
                            if (response.isNotEmpty()) msgError = response
                        }
                    } catch (_: Exception) {
                        // Render a veces está dormido la primera vez, probamos siguiente URL
                    }
                }

                withContext(Dispatchers.Main) {
                    if (ok) {
                        prefs.edit().putBoolean("logueado", true).putString("user_email", email).apply()
                        startActivity(Intent(this@LoginActivity, MainMobileActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, msgError, Toast.LENGTH_LONG).show()
                        btn.isEnabled = true
                        btn.text = "Entrar"
                    }
                }
            }
        }
    }
}
