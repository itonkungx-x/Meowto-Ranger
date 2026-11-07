package com.itonkung.meowtoranger

import com.itonkung.meowtoranger.core.update.AppUpdater
import com.itonkung.meowtoranger.core.config.RemoteConfig
import com.itonkung.meowtoranger.modules.ModuleRegistry
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Compact mobile UI: count, activity status, two controls. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("auto", MODE_PRIVATE) }
    private val updater = Handler(Looper.getMainLooper())
    private val white = Color.rgb(248, 247, 244)
    private val muted = Color.rgb(161, 169, 184)
    private val amber = Color.rgb(255, 174, 51)
    private val base = Color.rgb(15, 17, 22)
    private var serviceInfo: TextView? = null
    private var counterInfo: TextView? = null
    private var actionInfo: TextView? = null
    private var progress: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = base
        window.navigationBarColor = base
        buildScreen()
        RemoteConfig(this).refreshInBackground()
    }
    override fun onResume() { super.onResume(); updater.post(refresh) }
    override fun onPause() { updater.removeCallbacks(refresh); super.onPause() }
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            updater.postDelayed(this, 1100)
        }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
    private fun box(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
    private fun accent() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(255, 194, 64), Color.rgb(250, 134, 39))).apply {
        cornerRadius = dp(16).toFloat()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    private fun button(title: String, primary: Boolean, click: () -> Unit): LinearLayout {
        val layout = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(17), dp(12), dp(17))
            background = if (primary) accent() else box(Color.rgb(36, 40, 48), 16, Color.rgb(62, 68, 78))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        layout.addView(text(title, 17f, if (primary) Color.rgb(35, 25, 17) else white, true).apply {
            gravity = Gravity.CENTER
        })
        return layout
    }
    private fun buildScreen() {
        val content = column().apply {
            setPadding(dp(22), dp(28), dp(22), dp(22))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(base)
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.rangers_cat_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(amber, 17)
            clipToOutline = true
        }
        head.addView(icon, LinearLayout.LayoutParams(dp(68), dp(68)))
        val heading = column().apply { setPadding(dp(15), 0, 0, 0) }
        heading.addView(text("MEOWTO", 23f, white, true))
        heading.addView(text("RANGER · v0.10", 18f, amber, true))
        head.addView(heading)

        content.addView(head)
        content.addView(text("AUTOMATION MODULES", 11f, muted, true), margin(16, 8))
        ModuleRegistry.modules.forEach { module ->
            content.addView(text(module.title + "  ·  " + module.subtitle, 13f, white),
                margin(0, 7))
        }

        val status = text("ตรวจสอบบริการ...", 12f, amber, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = box(Color.rgb(40, 43, 45), 11)
        }
        serviceInfo = status
        content.addView(status, margin(19, 17))

        val card = column().apply {
            setPadding(dp(22), dp(22), dp(22), dp(24))
            background = box(Color.rgb(29, 33, 42), 20, Color.rgb(62, 65, 77))
        }
        card.addView(text("วันนี้", 14f, muted))
        val count = text("— / 50", 46f, white, true).apply {
            setPadding(0, dp(8), 0, dp(13))
        }
        counterInfo = count
        card.addView(count)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 50
            progressTintList = ColorStateList.valueOf(amber)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(66, 71, 80))
        }
        progress = bar
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(7)))
        val action = text("พร้อมใช้งาน", 13f, muted).apply {
            setPadding(0, dp(14), 0, 0)
            maxLines = 2
        }
        actionInfo = action
        card.addView(action)
        content.addView(card)

        content.addView(button("▶   เปิด LINE Rangers", true) { authorizeThenOpen() }, margin(20, 11))
        content.addView(button("เปิดสิทธิ์ Accessibility", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(button("ปิด Accessibility ทันที (ก่อนใช้ K PLUS)", false) {
            if (FriendAccessibilityService.disableForBanking()) {
                Toast.makeText(this, "ปิดสิทธิ์แล้ว · เปิด K PLUS ได้", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Accessibility ปิดอยู่แล้ว", Toast.LENGTH_SHORT).show()
            }
        }, margin(10, 0))
        content.addView(text("ระบบจะปิด Accessibility เองเมื่อออกจาก LINE Rangers แต่ Android ต้องให้คุณเปิดสิทธิ์กลับผ่านการตั้งค่า", 12f, muted), margin(12, 10))

        content.addView(button("ตรวจสอบอัปเดต", false) {
            AppUpdater(this).checkForUpdates()
        }, margin(10, 5))
        content.addView(text("กด ▶ ในเกมเพื่อเริ่ม · กด ■ เพื่อหยุด · ออกจากเกมเพื่อปิดสิทธิ์", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(21), 0, 0)
        })
        content.addView(text("ยกเลิกการอนุญาตส่ง LINE", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
            setOnClickListener {
                prefs.edit().putBoolean("consent", false).apply()
                Toast.makeText(this@MainActivity, "ยกเลิกการอนุญาตแล้ว", Toast.LENGTH_SHORT).show()
            }
        })
    }
    private fun refreshStatus() {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.name?.contains("FriendAccessibilityService") == true &&
                it.resolveInfo?.serviceInfo?.packageName == packageName }
        serviceInfo?.text = if (enabled) "● พร้อมทำงาน" else "● เปิด Accessibility ก่อน"
        serviceInfo?.setTextColor(if (enabled) Color.rgb(114, 235, 157) else amber)
        val value = prefs.getInt("current_count", -1)
        val maximum = prefs.getInt("current_max", 50).coerceAtLeast(1)
        counterInfo?.text = "${if (value < 0) "—" else value.toString()} / $maximum"
        actionInfo?.text = prefs.getString("last_status", "พร้อมใช้งาน") ?: "พร้อมใช้งาน"
        progress?.max = maximum
        progress?.progress = value.coerceAtLeast(0)
    }
    private fun authorizeThenOpen() {
        AlertDialog.Builder(this)
            .setTitle("ส่งข้อความ LINE ถึงเพื่อน")
            .setMessage("เมื่อกด ▶ ในเกม แอปจะกดช่วยเหลือและส่งข้อความ LINE ถึงเพื่อนโดยอัตโนมัติจนเต็มโควต้า สามารถกด ■ เพื่อหยุดได้ตลอด")
            .setPositiveButton("อนุญาตและเปิดเกม") { _, _ ->
                prefs.edit().putBoolean("consent", true).apply()
                val intent = packageManager.getLaunchIntentForPackage(FriendAccessibilityService.GAME_PACKAGE)
                if (intent != null) startActivity(intent)
                else Toast.makeText(this, "ไม่พบ LINE Rangers", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }
}
