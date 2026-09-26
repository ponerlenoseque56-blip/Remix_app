package com.tanasi.streamflix.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tanasi.streamflix.R
import com.tanasi.streamflix.activities.main.MainMobileActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var etNombre: TextInputEditText
    private lateinit var etCorreo: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etNombre = findViewById(R.id.etNombre)
        etCorreo = findViewById(R.id.etCorreo)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)

        btnLogin.setOnClickListener {
            val nombre = etNombre.text.toString().trim()
            val correo = etCorreo.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (nombre.isEmpty()) {
                etNombre.error = "Ingresá tu nombre"
                return@setOnClickListener
            }
            if (correo.isEmpty() || !correo.contains("@")) {
                etCorreo.error = "Correo no válido"
                return@setOnClickListener
            }
            if (pass.length < 4) {
                etPassword.error = "Mínimo 4 caracteres"
                return@setOnClickListener
            }

            // Guardamos sesión simple
            val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
            prefs.edit().apply {
                putString("nombre", nombre)
                putString("correo", correo)
                putBoolean("isLogged", true)
                apply()
            }

            Toast.makeText(this, "Bienvenido $nombre", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainMobileActivity::class.java))
            finish()
        }
    }
}
