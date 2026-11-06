package com.itonkung.meowtoranger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.itonkung.meowtoranger.core.AppRuntime
import com.itonkung.meowtoranger.core.capture.GameCaptureService
import com.itonkung.meowtoranger.core.config.RemoteConfig
import com.itonkung.meowtoranger.core.input.WirelessAdb
import com.itonkung.meowtoranger.core.update.AppUpdater
import com.itonkung.meowtoranger.modules.ModuleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Main app: no AccessibilityService or companion app. */
class MainActivity : Activity() {
    companion object { private const val REQUEST_CAPTURE = 4032 }
    private val base = Color.rgb(15, 17, 22)
    private val surface = Color.rgb(29, 33, 42)
    private val white = Color.rgb(246, 247, 250)
    private val muted = Color.rgb(164, 170, 184)
    private val amber = Color.rgb(255, 177, 60)
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adb by lazy { AppRuntime.adb(this) }
    private val prefs by lazy { getSharedPreferences("auto", MODE_PRIVATE) }
    private var connection: TextView? = null
    private var detail: TextView? = null
    private var count: TextView? = null
    private var bar: ProgressBar? = null
    private var pairingPort: EditText? = null
    private var pairingCode: EditText? = null
    private var connectPort: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = base
        window.navigationBarColor = base
        buildScreen()
        RemoteConfig(this).refreshInBackground()
        AppUpdater(this).checkForUpdates(silent = true)
    }
    override fun onResume() {
        super.onResume()
        AppUpdater(this).resumePendingInstall()
        ui.post(refresh)
    }
    override fun onPause() { ui.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private val refresh = object : Runnable {
        override fun run() {
            connection?.text = when {
                GameCaptureService.active -> "● กำลังทำงาน"
                adb.isConnected -> "● เชื่อมต่อ Wireless Debugging แล้ว"
                else -> "● ยังไม่เชื่อมต่อ"
            }
            connection?.setTextColor(if (adb.isConnected) Color.rgb(107,227,147) else amber)
            val value = prefs.getInt("current_count", -1)
            count?.text = "${if (value < 0) "—" else value.toString()} / 50"
            bar?.progress = value.coerceAtLeast(0)
            detail?.text = prefs.getString("last_status", "พร้อมใช้งาน")
            ui.postDelayed(this, 1000L)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun box(color: Int, radius: Int, border: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(1), border)
        }
    private fun accent() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(255,199,77), Color.rgb(246,131,43))
    ).apply { cornerRadius = dp(15).toFloat() }
    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) =
        text(value, 16f, if (primary) Color.rgb(40,25,13) else white, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(17), dp(12), dp(17))
            background = if (primary) accent() else box(Color.rgb(44,48,58), 15)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    private fun field(hint: String, value: String = "") = EditText(this).apply {
        setSingleLine(true)
        setText(value)
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 15f
        setTextColor(white)
        setHintTextColor(muted)
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = box(Color.rgb(42, 46, 55), 12, Color.rgb(60, 65, 76))
    }

    private fun buildScreen() {
        val content = column().apply { setPadding(dp(21),dp(25),dp(21),dp(27)) }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(base)
            isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
        val heading = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.rangers_cat_icon)
            scaleType=ImageView.ScaleType.CENTER_CROP
            background=box(amber, 16)
            clipToOutline=true
        }
        heading.addView(icon, LinearLayout.LayoutParams(dp(66), dp(66)))
        val title = column().apply { setPadding(dp(15),0,0,0) }
        title.addView(text("MEOWTO RANGER",22f,white,true))
        title.addView(text("Automation Hub · v0.9",12f,amber))
        heading.addView(title)
        content.addView(heading)

        val dashboard = column().apply {
            setPadding(dp(18),dp(17),dp(18),dp(18))
            background=box(surface,19,Color.rgb(59,64,75))
        }
        val state = text("● ยังไม่เชื่อมต่อ",12f,amber,true)
        connection=state
        dashboard.addView(state)
        dashboard.addView(text("คะแนนมิตรภาพ",13f,muted),margin(16,0))
        val counter=text("— / 50",43f,white,true)
        count=counter
        dashboard.addView(counter,margin(7,10))
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=50
            progressTintList=ColorStateList.valueOf(amber)
            progressBackgroundTintList=ColorStateList.valueOf(Color.rgb(73,76,86))
        }
        bar=progress
        dashboard.addView(progress, LinearLayout.LayoutParams(-1,dp(7)))
        val status=text("พร้อมใช้งาน",13f,muted).apply { maxLines=2 }
        detail=status
        dashboard.addView(status,margin(12,0))
        content.addView(dashboard,margin(19,0))
        content.addView(button("▶  เริ่มช่วยเพื่อน",true) { startGameCapture() },margin(13,5))
        content.addView(button("■  หยุดการทำงาน") {
            stopService(Intent(this,GameCaptureService::class.java))
            AppRuntime.status(this,"หยุดแล้ว")
        },margin(0,11))

        val pairing = column().apply {
            setPadding(dp(17),dp(16),dp(17),dp(17))
            background=box(surface,19,Color.rgb(59,64,75))
        }
        pairing.addView(text("เชื่อมต่อครั้งแรก",15f,white,true))
        pairing.addView(text("ตั้งค่า → สำหรับนักพัฒนา → Wireless Debugging",12f,muted),margin(6,0))
        pairing.addView(button("เปิดตัวเลือกนักพัฒนา") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        },margin(12,5))
        val fields=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val port=field("พอร์ตจับคู่")
        val code=field("รหัส 6 หลัก")
        pairingPort=port;pairingCode=code
        fields.addView(port,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(6)})
        fields.addView(code,LinearLayout.LayoutParams(0,-2,1f))
        pairing.addView(fields,margin(5,0))
        pairing.addView(button("จับคู่") { pairDevice() },margin(9,0))
        val cp=field("พอร์ตเชื่อมต่อ",adb.lastPort.takeIf{it>0}?.toString() ?: "")
        connectPort=cp
        pairing.addView(cp,margin(13,0))
        pairing.addView(button("เชื่อมต่อ") { connectDevice() },margin(8,0))
        pairing.addView(text("ไม่ต้องติดตั้ง Shizuku · อาจต้องเปิด Wireless Debugging ใหม่หลังรีสตาร์ต",
            12f,muted),margin(11,0))
        content.addView(pairing)

        content.addView(text("โมดูล",15f,white,true),margin(18,6))
        ModuleRegistry.modules.forEach { module ->
            content.addView(text("•  ${module.title}  ·  ${module.subtitle}",13f,muted),margin(3,0))
        }
        content.addView(button("ตรวจสอบอัปเดต") {
            AppUpdater(this).checkForUpdates()
        },margin(22,5))
        content.addView(text("การจับภาพหน้าจอต้องยืนยันกับ Android ทุกครั้งที่เริ่ม",
            12f,muted).apply { gravity=Gravity.CENTER },margin(10,8))
    }

    private fun pairDevice() {
        val port=pairingPort?.text?.toString()?.toIntOrNull()
        val code=pairingCode?.text?.toString()?.trim().orEmpty()
        if (port==null || !Regex("[0-9]{6}").matches(code)) {
            toast("กรอกพอร์ตจับคู่และรหัส 6 หลักจาก Wireless Debugging")
            return
        }
        toast("กำลังจับคู่…")
        scope.launch {
            try { adb.pair(port,code); toast("จับคู่สำเร็จ · กรอกพอร์ตเชื่อมต่อแล้วกดเชื่อมต่อ") }
            catch(e:Exception) { toast("จับคู่ไม่สำเร็จ: ${e.message}") }
            finally { pairingCode?.text?.clear() }
        }
    }

    private fun connectDevice() {
        val port=connectPort?.text?.toString()?.toIntOrNull()
        if(port==null){toast("กรอกพอร์ตจากหน้าหลัก Wireless Debugging");return}
        toast("กำลังเชื่อมต่อ…")
        scope.launch {
            try { adb.connect(port); toast("เชื่อมต่อสำเร็จ") }
            catch(e:Exception){toast("เชื่อมต่อไม่สำเร็จ: ${e.message}")}
        }
    }

    private fun startGameCapture() {
        if(!adb.isConnected){toast("เชื่อมต่อ Wireless Debugging ก่อน");return}
        if(GameCaptureService.active){toast("กำลังทำงานอยู่แล้ว");return}
        if(packageManager.getLaunchIntentForPackage(WirelessAdb.GAME_PACKAGE)==null){
            toast("ไม่พบ LINE Rangers");return
        }
        AlertDialog.Builder(this)
            .setTitle("ส่งคะแนนมิตรภาพ")
            .setMessage("Meowto Ranger จะช่วยเหลือเพื่อนและกดส่งข้อความ LINE ตามโควต้าของเกมโดยอัตโนมัติ จำกัดเฉพาะ LINE Rangers และหยุดทันทีเมื่อออกจากเกม")
            .setPositiveButton("อนุญาตและเริ่ม") { _, _ ->
                val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val request=if(Build.VERSION.SDK_INT>=34)
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
                startActivityForResult(request,REQUEST_CAPTURE)
            }
            .setNegativeButton("ยกเลิก",null)
            .show()
    }

    @Deprecated("Deprecated in Android, used for API 30+ MediaProjection consent")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=REQUEST_CAPTURE) return
        if(resultCode!=RESULT_OK||data==null){toast("ยังไม่ได้อนุญาตจับภาพ");return}
        val service=Intent(this,GameCaptureService::class.java).apply {
            action=GameCaptureService.ACTION_START
            putExtra(GameCaptureService.EXTRA_RESULT,resultCode)
            putExtra(GameCaptureService.EXTRA_DATA,data)
        }
        startForegroundService(service)
        packageManager.getLaunchIntentForPackage(WirelessAdb.GAME_PACKAGE)?.let { startActivity(it) }
    }

    private fun toast(message:String) =
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
}
