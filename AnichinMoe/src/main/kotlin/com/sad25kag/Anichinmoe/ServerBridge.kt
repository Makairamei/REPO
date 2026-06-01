package com.sad25kag.Anichinmoe

import android.provider.Settings
import java.net.URLEncoder
import com.lagradost.cloudstream3.app

object ServerBridge {
    const val SERVER_URL = "https://faxecez.eu.org"
    const val LICENSE_KEY = "-VP2E0KB8OF"
    const val PLUGIN_NAME = "AnichinMoe"

    fun deviceId(): String {
        val ctx = Anichin.context
        val id = runCatching {
            Settings.Secure.getString(ctx?.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return (id ?: "unknown").ifBlank { "unknown" }
    }

    suspend fun pingLicense(action: String = "OPEN") {
        val devId = URLEncoder.encode(deviceId(), "UTF-8")
        val key = URLEncoder.encode(LICENSE_KEY, "UTF-8")
        val plugin = URLEncoder.encode(PLUGIN_NAME, "UTF-8")
        val act = URLEncoder.encode(action, "UTF-8")
        val url = "$SERVER_URL/api/check-ip?key=$key&device_id=$devId&plugin=$plugin&action=$act"
        runCatching { app.get(url) }
    }
}
