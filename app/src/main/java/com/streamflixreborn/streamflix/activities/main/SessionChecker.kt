package com.streamflixreborn.streamflix.activities.main

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

object SessionChecker {
    fun verificar(context: Context, onExpired: () -> Unit) {
        val prefs = context.getSharedPreferences("remix_prefs", Context.MODE_PRIVATE)
        val email = prefs.getString("email", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        if (email.isEmpty()) return
        
        Thread {
            try {
                val url = URL("https://remix-app-panel.onrender.com/api/check-login")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val body = "{\"email\":\"$email\",\"password\":\"$pass\",\"username\":\"$email\"}"
                conn.outputStream.write(body.toByteArray())
                val resp = conn.inputStream.bufferedReader().readText()
                if (!resp.contains("\"ok\":true")) {
                    onExpired()
                }
            } catch (e: Exception) {
            }
        }.start()
    }
}
