package com.itonkung.meowtoranger.core.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * User-initiated updates from the official Meowto Ranger GitHub Releases feed.
 *
 * Android still owns installation confirmation. This is NOT a silent updater.
 * The Android installer enforces matching signing certificates.
 */
class AppUpdater(private val activity: Activity) {
    private val latestUrl = "https://api.github.com/repos/itonkungx-x/Meowto-Ranger/releases/latest"
    private val maxBytes = 150L * 1024L * 1024L

    fun checkForUpdates(silent: Boolean = false) {
        // A background check happens at most once every six hours.
        // The manual "Check update" button always checks immediately.
        val prefs = activity.getSharedPreferences("meowto_updates", Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (silent && now - prefs.getLong("last_successful_check", 0L) < 6L * 60L * 60L * 1000L) return
        if (!silent) toast("กำลังตรวจสอบอัปเดต…")
        Thread {
            try {
                val data = JSONObject(fetchJson(latestUrl))
                if (data.optBoolean("draft", false) || data.optBoolean("prerelease", false)) {
                    throw IllegalStateException("ยังไม่มีรุ่นเผยแพร่")
                }
                prefs.edit().putLong("last_successful_check", System.currentTimeMillis()).apply()
                val tag = data.optString("tag_name").trimStart('v', 'V')
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
                if (compareVersions(tag, installed) <= 0) {
                    if (!silent) toast("แอปเป็นรุ่นล่าสุดแล้ว ($installed)")
                    return@Thread
                }

                val assets = data.optJSONArray("assets") ?: throw IllegalStateException("ไม่มีไฟล์ดาวน์โหลด")
                var downloadUrl = ""
                var apkName = ""
                var digest = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.startsWith("Meowto-Ranger-") && name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        apkName = name
                        digest = asset.optString("digest")
                        break
                    }
                }
                if (downloadUrl.isBlank()) throw IllegalStateException("ยังไม่มี APK สำหรับเวอร์ชัน $tag")
                // Always authenticate the downloaded bytes against the release's SHA-256.
                // Older GitHub releases can omit the asset digest; use the companion
                // checksum asset published by our release workflow in that case.
                val expectedDigest = resolveDigest(assets, apkName, digest)
                val checked = URL(downloadUrl)
                require(checked.protocol == "https" && checked.host.equals("github.com", true) &&
                    checked.path.startsWith("/itonkungx-x/Meowto-Ranger/releases/download/")) {
                    "แหล่งอัปเดตไม่ตรงกับ GitHub ที่กำหนด"
                }
                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("Meowto Ranger $tag")
                        .setMessage("เวอร์ชันปัจจุบัน: $installed\nต้องการดาวน์โหลดและติดตั้งอัปเดตหรือไม่?")
                        .setNegativeButton("ภายหลัง", null)
                        .setPositiveButton("อัปเดต") { _, _ -> downloadAndInstall(downloadUrl, expectedDigest) }
                        .show()
                }
            } catch (e: Exception) {
                if (!silent) toast("ตรวจอัปเดตไม่ได้: ${e.message ?: "เครือข่ายผิดพลาด"}")
            }
        }.start()
    }

    private fun resolveDigest(assets: org.json.JSONArray, apkName: String, apiDigest: String): String {
        val shaPattern = Regex("(?i)^[a-f0-9]{64}$")
        val fromApi = apiDigest.removePrefix("sha256:").trim()
        if (apiDigest.startsWith("sha256:", true) && shaPattern.matches(fromApi)) return fromApi
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == "$apkName.sha256") {
                val checksumUrl = asset.getString("browser_download_url")
                val expected = URL(checksumUrl)
                require(expected.protocol == "https" && expected.host.equals("github.com", true) &&
                    expected.path.startsWith("/itonkungx-x/Meowto-Ranger/releases/download/")) {
                    "แหล่งตรวจสอบไฟล์ไม่ถูกต้อง"
                }
                val sha = fetchJson(checksumUrl).trim().split(Regex("\\s+")).firstOrNull() ?: ""
                if (shaPattern.matches(sha)) return sha
            }
        }
        throw SecurityException("ไม่มี SHA-256 ยืนยันความปลอดภัยของไฟล์อัปเดต")
    }

    private fun downloadAndInstall(link: String, expectedDigest: String) {
        toast("กำลังดาวน์โหลดอัปเดต…")
        Thread {
            try {
                val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
                val apk = File(directory, "Meowto-Ranger-update.apk")
                val temporary = File(directory, "download.part")
                val stream = openHttp(link)
                val hash = MessageDigest.getInstance("SHA-256")
                var received = 0L
                try {
                    stream.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val length = input.read(buffer)
                                if (length < 0) break
                                received += length
                                if (received > maxBytes) throw IllegalStateException("ไฟล์ใหญ่เกินกำหนด")
                                hash.update(buffer, 0, length)
                                output.write(buffer, 0, length)
                            }
                        }
                    }
                } finally {
                    stream.disconnect()
                }
                if (received < 1024L) throw IllegalStateException("ไฟล์ APK ไม่สมบูรณ์")
                val actual = hash.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
                if (!actual.equals(expectedDigest, true)) {
                    throw SecurityException("SHA-256 ไม่ตรงกับไฟล์เผยแพร่ที่ตรวจสอบ")
                }
                if (apk.exists()) apk.delete()
                if (!temporary.renameTo(apk)) throw IllegalStateException("บันทึก APK ไม่สำเร็จ")
                activity.runOnUiThread { installApk(apk) }
            } catch (e: Exception) {
                toast("ดาวน์โหลดล้มเหลว: ${e.message ?: "ไม่ทราบสาเหตุ"}")
            }
        }.start()
    }

    private fun installApk(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            toast("อนุญาตติดตั้งจาก Meowto Ranger แล้วกดอัปเดตใหม่อีกครั้ง")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }

    private fun fetchJson(url: String): String {
        val connection = openHttp(url)
        return try { connection.inputStream.bufferedReader().use { it.readText() } }
        finally { connection.disconnect() }
    }

    private fun openHttp(address: String): HttpURLConnection {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 24000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Meowto-Ranger-Android")
        }
        if (connection.responseCode !in 200..299) {
            val status = connection.responseCode
            connection.disconnect()
            throw IllegalStateException("HTTP $status")
        }
        return connection
    }

    private fun compareVersions(remote: String, local: String): Int {
        val a = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun toast(text: String) {
        activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    }
}
