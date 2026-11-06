package com.itonkung.meowtoranger.core.config

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Only numeric, range-checked settings can be changed remotely.
 * No remote code, scripting, custom URLs, or commands are executed.
 */
class RemoteConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("auto", Context.MODE_PRIVATE)
    private val source =
        "https://raw.githubusercontent.com/itonkungx-x/Meowto-Ranger/main/config/friend-points.json"

    fun refreshInBackground() {
        Thread {
            val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Meowto-Ranger-Android")
            }
            try {
                if (connection.responseCode != 200) return@Thread
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (root.optInt("schema") != 1) return@Thread
                val values = root.optJSONObject("friendPoints") ?: return@Thread
                val swipe = values.optLong("swipeDurationMs", 720L)
                val rest = values.optLong("afterSwipeRestMs", 850L)
                val probe = values.optLong("stableProbeMs", 410L)
                val timeout = values.optLong("settleTimeoutMs", 3200L)
                if (swipe !in 400..1800 || rest !in 450..2500 ||
                    probe !in 250..1200 || timeout !in 2000..6500) return@Thread
                prefs.edit()
                    .putLong("remote_swipe_duration_ms", swipe)
                    .putLong("remote_swipe_rest_ms", rest)
                    .putLong("remote_stability_probe_ms", probe)
                    .putLong("remote_settle_timeout_ms", timeout)
                    .apply()
            } catch (_: Exception) {
                // Cached settings and local defaults remain active if offline.
            } finally {
                connection.disconnect()
            }
        }.start()
    }
}
