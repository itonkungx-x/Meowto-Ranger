package com.itonkung.meowtoranger.core.input

import android.content.Context
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.roundToInt

/**
 * Embedded Android 11+ Wireless Debugging client; no additional Android app,
 * root access, or AccessibilityService is needed.
 *
 * Pairing still requires the user to authorize the device in Android Developer
 * Options. No attempt is made to hide or bypass Android or banking security.
 */
class WirelessAdb(private val context: Context) {
    companion object {
        const val GAME_PACKAGE = "com.linecorp.LGRGS"
        private const val LOOPBACK = "127.0.0.1"
    }

    private val prefs = context.getSharedPreferences("meowto_wireless_adb", Context.MODE_PRIVATE)
    private val keys by lazy { FileKeyProvider(File(context.noBackupFilesDir, "meowto_adbkey")) }
    @Volatile private var client: AdbClient? = null

    val isConnected get() = client?.isClosed == false

    val lastPort: Int get() = prefs.getInt("connect_port", 0)

    suspend fun pair(pairingPort: Int, code: String): Unit = withContext(Dispatchers.IO) {
        require(pairingPort in 1024..65535) { "พอร์ตจับคู่ไม่ถูกต้อง" }
        require(Regex("^[0-9]{6}$").matches(code)) { "รหัสจับคู่ต้องมี 6 หลัก" }
        withTimeout(25_000) {
            val result = AdbClient.pairTls(LOOPBACK, pairingPort, code, keys)
            result.getOrThrow()
        }
        // Do not save or log the ephemeral pairing code.
    }

    suspend fun connect(port: Int): Unit = withContext(Dispatchers.IO) {
        require(port in 1024..65535) { "พอร์ตเชื่อมต่อไม่ถูกต้อง" }
        client?.close()
        client = null
        val newClient = withTimeout(25_000) {
            AdbClient.connectTls(LOOPBACK, port, keys)
        }
        // Verify against a harmless command before marking the connection ready.
        val result = newClient.shell("getprop ro.product.model")
        if (!result.isSuccess) {
            newClient.close()
            throw IllegalStateException("คำสั่งตรวจสอบการเชื่อมต่อไม่สำเร็จ")
        }
        client = newClient
        prefs.edit().putInt("connect_port", port).apply()
    }

    suspend fun foregroundIsGame(): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext false
        try {
            val result = withTimeout(4500L) {
                c.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'")
            }
            val text = result.stdoutText
            result.isSuccess && text.contains(GAME_PACKAGE) &&
                !text.contains("com.kasikorn", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /** Reject all input when the foreground application is not LINE Rangers. */
    suspend fun tap(x: Int, y: Int): Boolean {
        if (!foregroundIsGame()) return false
        val c = client ?: return false
        return try {
            withTimeout(6_000L) {
                c.shell("input tap ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)}").isSuccess
            }
        } catch (_: Exception) { false }
    }

    suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long): Boolean {
        if (!foregroundIsGame()) return false
        val c = client ?: return false
        val dur = durationMs.coerceIn(450L, 1800L)
        return try {
            withTimeout(7_000L) {
                c.shell("input swipe ${x0.coerceAtLeast(0)} ${y0.coerceAtLeast(0)} " +
                    "${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} $dur").isSuccess
            }
        } catch (_: Exception) { false }
    }

    fun disconnect() {
        client?.close()
        client = null
    }
}
