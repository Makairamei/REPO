package com.sad25kag.gerakin21

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.security.MessageDigest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

object LicenseClient {
    private const val TAG = "LicenseClient"
    private const val SERVER_URL = "https://faxecez.eu.org"
    private const val PREF_NAME = "cs_premium"
    private const val PREF_KEY = "license_key"

    private var cachedStatus: String? = null
    private var cacheExpiry: Long = 0L
    private var lastSuccessfulCheck: Long = 0L
    private val actionThrottle = mutableMapOf<String, Long>()
    private var licenseBlocked = false
    private var blockMessage = ""
    private var appContext: Context? = null
    private var pluginSessionToken: String? = null
    private var pluginSessionPlugin: String? = null
    private var pluginSessionExpiry: Long = 0L

    fun init(context: Context, pluginName: String = "plugin") {
        appContext = context.applicationContext
        GlobalScope.launch {
            try { checkLicense(pluginName, "OPEN") } catch (e: Exception) {}
        }
    }

    fun setLicenseKey(context: Context, key: String) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, key.trim()).apply()
        resetCache()
    }

    fun getLicenseKey(): String? {
        return appContext
            ?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun getHardwareHash(): String {
        val hwInfo = "${Build.BOARD}${Build.BRAND}${Build.DEVICE}${Build.HARDWARE}${Build.MANUFACTURER}${Build.MODEL}${Build.PRODUCT}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(hwInfo.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun getDeviceId(): String {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            ?: return "unknown"
        var deviceId = prefs.getString("device_uuid", null)
        if (!deviceId.isNullOrEmpty() && deviceId != "unknown") return deviceId
        var finalAndroidId = "unknown"
        try {
            val aId = Settings.Secure.getString(appContext?.contentResolver, Settings.Secure.ANDROID_ID)
            if (!aId.isNullOrEmpty() && aId != "unknown" && aId.length >= 8) finalAndroidId = aId
        } catch (e: Exception) {}
        val hwHash = getHardwareHash()
        deviceId = "$finalAndroidId-$hwHash"
        prefs.edit().putString("device_uuid", deviceId).apply()
        Log.i(TAG, "Generated persistent hardware device ID: $deviceId")
        return deviceId
    }

    private fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return (if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model").take(100)
    }

    private suspend fun discoverKey(): String? {
        return try {
            val deviceId = getDeviceId()
            val response = app.get("$SERVER_URL/api/discover?device_id=$deviceId").text
            val json = tryParseJson<KeyByIpResponse>(response)
            if (json?.status == "active" && !json.key.isNullOrEmpty()) {
                appContext?.let { setLicenseKey(it, json.key) }
                Log.i(TAG, "Auto-discovered license key via device lookup")
                json.key
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Key discovery failed: ${e.message}")
            null
        }
    }

    suspend fun checkLicense(pluginName: String, action: String = "OPEN", data: String? = null): Boolean {
        val now = System.currentTimeMillis()
        val throttleKey = "$pluginName|$action"
        val throttleMs = when (action.uppercase()) {
            "HOME" -> 60_000L
            "SEARCH" -> 10_000L
            else -> 5_000L
        }
        val lastCheck = actionThrottle[throttleKey] ?: 0L
        if (now - lastCheck < throttleMs && cachedStatus == "active") return true
        actionThrottle[throttleKey] = now

        if (cachedStatus == "active" && now < cacheExpiry && action.uppercase() != "PLAY") {
            logActionAsync(pluginName, action, data)
            return true
        }

        var key = getLicenseKey()
        if (key.isNullOrEmpty()) key = discoverKey()
        if (key.isNullOrEmpty()) {
            licenseBlocked = true
            blockMessage = "Lisensi tidak ditemukan. Pastikan repo URL premium sudah ditambahkan."
            return false
        }

        return try {
            val deviceId = getDeviceId()
            val deviceModel = getDeviceModel()
            val cleanPlugin = pluginName.replace("\"", "")
            val cleanAction = action.replace("\"", "")
            val cleanData = (data ?: "").replace("\"", "")
            val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"$cleanPlugin","action":"$cleanAction","data":"$cleanData"}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post("$SERVER_URL/api/verify_activity", requestBody = body).text
            val json = tryParseJson<CheckResponse>(response)

            if (json?.status == "active" || json?.status == "success") {
                cachedStatus = "active"
                cacheExpiry = 0L
                lastSuccessfulCheck = now
                licenseBlocked = false
                blockMessage = ""
                true
            } else {
                cachedStatus = "error"
                licenseBlocked = true
                blockMessage = json?.message ?: "Lisensi tidak valid atau perangkat diblokir"
                val reason = json?.reason ?: ""
                if (reason == "not_found" || reason == "revoked") {
                    appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.remove(PREF_KEY)?.apply()
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "License check network error: ${e.message}")
            if (cachedStatus == "active" && now < lastSuccessfulCheck + 600_000L) true
            else { licenseBlocked = true; blockMessage = "Tidak dapat memverifikasi lisensi."; false }
        }
    }

    suspend fun requireLicense(pluginName: String, action: String = "OPEN", data: String? = null) {
        if (!checkLicense(pluginName, action, data)) throw RuntimeException("[PREMIUM] $blockMessage")
    }

    fun trackActivity(pluginName: String, action: String, data: String = "") {
        logActionAsync(pluginName, action, data)
    }

    private fun logActionAsync(pluginName: String, action: String, data: String?) {
        val key = getLicenseKey() ?: return
        val deviceId = getDeviceId()
        val deviceModel = getDeviceModel()
        GlobalScope.launch {
            try {
                val cleanPlugin = pluginName.replace("\"", "\\\"")
                val cleanAction = action.replace("\"", "\\\"")
                val cleanData = data?.replace("\"", "\\\"") ?: ""
                val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"$cleanPlugin","action":"$cleanAction","data":"$cleanData"}"""
                val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
                app.post("$SERVER_URL/api/verify_activity", requestBody = body)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log action async: ${e.message}")
            }
        }
    }

    fun isBlocked(): Boolean = licenseBlocked
    fun getBlockMessage(): String = blockMessage

    fun resetCache() {
        cachedStatus = null
        cacheExpiry = 0L
        licenseBlocked = false
        blockMessage = ""
        pluginSessionToken = null
        pluginSessionPlugin = null
        pluginSessionExpiry = 0L
        actionThrottle.clear()
    }

    data class CheckResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("reason") val reason: String? = null
    )

    data class KeyByIpResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("key") val key: String? = null
    )

    data class PluginSessionResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("session_token") val sessionToken: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("expires_in") val expiresIn: Int? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("reason") val reason: String? = null
    )

    data class SelectorConfig(
        val playerSelector: String = ".mobius option",
        val playerAttr: String = "value",
        val useBase64: Boolean = true
    )

    data class SelectorResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("status") val status: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("selectors") val selectors: RawSelector? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("message") val message: String? = null
    )

    data class RawSelector(
        @com.fasterxml.jackson.annotation.JsonProperty("server_selector") val serverSelector: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("value_attr") val valueAttr: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("encoding") val encoding: String? = null
    )

    private val selectorCache = mutableMapOf<String, Pair<SelectorConfig, Long>>()
    private val CACHE_TTL = 0L

    private suspend fun getPluginSessionToken(pluginName: String): String? {
        val now = System.currentTimeMillis()
        if (pluginSessionPlugin == pluginName && !pluginSessionToken.isNullOrEmpty() && now < pluginSessionExpiry - 15_000L) {
            return pluginSessionToken
        }
        var key = getLicenseKey()
        if (key.isNullOrEmpty()) key = discoverKey()
        if (key.isNullOrEmpty()) { licenseBlocked = true; blockMessage = "Lisensi tidak ditemukan."; return null }
        return try {
            val deviceId = getDeviceId()
            val deviceModel = getDeviceModel()
            val cleanPlugin = pluginName.replace("\"", "")
            val jsonPayload = """{"key":"$key","device_id":"$deviceId","device_model":"${deviceModel.replace("\"", "")}","plugin_name":"$cleanPlugin","action":"SESSION","data":""}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post("$SERVER_URL/api/plugin/session", requestBody = body).text
            val json = tryParseJson<PluginSessionResponse>(response)
            if (json?.status == "ok" && !json.sessionToken.isNullOrEmpty()) {
                pluginSessionToken = json.sessionToken
                pluginSessionPlugin = pluginName
                pluginSessionExpiry = now + ((json.expiresIn ?: 300) * 1000L)
                licenseBlocked = false; blockMessage = ""
                json.sessionToken
            } else {
                pluginSessionToken = null; pluginSessionPlugin = null; pluginSessionExpiry = 0L
                licenseBlocked = true; blockMessage = json?.message ?: "Session plugin tidak valid"
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Plugin session error: ${e.message}")
            if (pluginSessionPlugin == pluginName && !pluginSessionToken.isNullOrEmpty() && now < pluginSessionExpiry + 60_000L) pluginSessionToken
            else null
        }
    }

    suspend fun getSelectors(pluginName: String): SelectorConfig? {
        val now = System.currentTimeMillis()
        selectorCache[pluginName]?.let { (cfg, expiry) -> if (now < expiry) return cfg }
        val sessionToken = getPluginSessionToken(pluginName) ?: run { selectorCache.remove(pluginName); return null }
        return try {
            val jsonPayload = """{"plugin_name":"${pluginName.replace("\"", "")}"}"""
            val body = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post(
                "$SERVER_URL/api/selectors",
                headers = mapOf("Authorization" to "Bearer $sessionToken"),
                requestBody = body
            ).text
            val json = tryParseJson<SelectorResponse>(response)
            if (json?.status == "ok" && json.selectors != null) {
                val raw = json.selectors
                val cfg = SelectorConfig(
                    playerSelector = raw.serverSelector?.takeIf { it.isNotBlank() } ?: ".mobius option",
                    playerAttr = raw.valueAttr?.takeIf { it.isNotBlank() } ?: "value",
                    useBase64 = raw.encoding?.lowercase() != "plain"
                )
                selectorCache[pluginName] = Pair(cfg, now + CACHE_TTL)
                licenseBlocked = false; blockMessage = ""
                cfg
            } else {
                licenseBlocked = true; blockMessage = json?.message ?: "Selector plugin tidak tersedia"
                selectorCache.remove(pluginName); null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSelectors network error: ${e.message}")
            selectorCache[pluginName]?.let { (cfg, expiry) -> if (now < expiry + 2 * 60 * 1000L) return cfg }
            null
        }
    }

    fun clearSelectorCache() { selectorCache.clear() }
}
