package com.itonkung.meowtoranger.modules.friends

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itonkung.meowtoranger.R
import com.itonkung.meowtoranger.core.AppRuntime
import com.itonkung.meowtoranger.core.input.WirelessAdb
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screenshot-only LINE Rangers automation. It cannot tap outside the verified
 * LINE Rangers foreground app. The full run must be started by the user.
 *
 * Friend messages are sent ONLY after a detected friend help button, a verified
 * send confirmation dialog, and a readable 0/50 quota baseline. Uncertain
 * screens are never tapped.
 */
class FriendSession(
    private val context: Context,
    private val adb: WirelessAdb,
    private val onFinished: (String) -> Unit,
) {
    private enum class Scene { FRIENDS, OTHER_FRIEND_TAB, HOME, BACK, MODAL, UNKNOWN }
    private enum class Step { NAVIGATE, SEARCH, SCROLL_SETTLE, WAIT_CONFIRM, WAIT_SUCCESS, WAIT_CLOSE }
    private val prefs = context.getSharedPreferences("auto", Context.MODE_PRIVATE)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val homeReference by lazy {
        BitmapFactory.decodeResource(context.resources, R.drawable.home_friend_button_reference)
    }
    private var running = true
    private var step = Step.NAVIGATE
    private var sent = 0
    private var used: Int? = null
    private var swipes = 0
    private var pendingX = -1f
    private var confirmX = -1f
    private var lastScene = Scene.UNKNOWN
    private var sceneFrames = 0
    private var lastSignature: IntArray? = null
    private var scrollSignature: IntArray? = null
    private var settleSamples = 0
    private var stallCount = 0
    private var readFailures = 0
    private var nextAt = 0L
    private var settleStarted = 0L
    private var waitStarted = 0L
    private val skipped = mutableListOf<Float>()

    init {
        prefs.edit().putInt("current_max", 50).putInt("current_count", -1).apply()
        status("กำลังเข้าเมนูเพื่อน")
    }

    fun close() {
        running = false
        recognizer.close()
    }

    private fun status(message: String) = AppRuntime.status(context, message)

    private fun finish(reason: String) {
        if (!running) return
        running = false
        status(reason)
        onFinished(reason)
    }

    suspend fun onFrame(frame: Bitmap) {
        if (!running) return
        if (frame.width.toFloat() / frame.height !in 1.72f..2.65f) {
            status("รอเกมแนวนอน")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextAt) return
        val scene = detectScene(frame)
        sceneFrames = if (scene == lastScene) sceneFrames + 1 else 1
        lastScene = scene

        if (step != Step.NAVIGATE && scene != Scene.FRIENDS && scene != Scene.MODAL) {
            step = Step.NAVIGATE
            pendingX = -1f
            confirmX = -1f
            status("กำลังกู้คืนหน้าเพื่อน")
        }
        if (scene == Scene.UNKNOWN) {
            status("รอหน้าจอชัดเจน")
            return
        }
        when (step) {
            Step.NAVIGATE -> {
                if (sceneFrames < 2) return
                when (scene) {
                    Scene.FRIENDS -> {
                        step = Step.SEARCH
                        pendingX = -1f
                        skipped.clear()
                        status("กำลังช่วยเพื่อน")
                    }
                    Scene.OTHER_FRIEND_TAB -> performTap(.385f, .195f, frame, 950L)
                    Scene.HOME -> performTap(.574f, .885f, frame, 1500L)
                    Scene.BACK -> performTap(.085f, .049f, frame, 1200L)
                    Scene.MODAL, Scene.UNKNOWN -> status("รอปิดหน้าต่างเกม")
                }
            }
            Step.SEARCH -> {
                if (scene != Scene.FRIENDS || sceneFrames < 2) return
                val buttons = helpButtons(frame).filter { x -> skipped.none { abs(it - x) < .04f } }
                if (buttons.isNotEmpty()) {
                    val candidate = buttons.first()
                    // Verify a candidate on two subsequent frames before tapping.
                    if (pendingX < 0f || abs(candidate - pendingX) > .017f) {
                        pendingX = candidate
                        nextAt = now + 400L
                        return
                    }
                    confirmX = candidate
                    pendingX = -1f
                    step = Step.WAIT_CONFIRM
                    waitStarted = now
                    if (performTap(candidate, .688f, frame, 350L)) status("ตรวจหน้าต่างส่งคะแนน")
                } else {
                    pendingX = -1f
                    val signature = frameSignature(frame)
                    stallCount = if (lastSignature != null && signature.contentEquals(lastSignature)) stallCount + 1 else 0
                    lastSignature = signature
                    if (stallCount >= 4 || swipes >= 100) {
                        finish("ไม่พบเพื่อนที่ช่วยเหลือได้เพิ่มเติม")
                        return
                    }
                    swipes++
                    step = Step.SCROLL_SETTLE
                    scrollSignature = null
                    settleSamples = 0
                    settleStarted = now
                    val duration = prefs.getLong("remote_swipe_duration_ms", 720L).coerceIn(450L, 1800L)
                    val ok = adb.swipe(
                        (frame.width * .80f).roundToInt(), (frame.height * .52f).roundToInt(),
                        (frame.width * .34f).roundToInt(), (frame.height * .52f).roundToInt(), duration
                    )
                    if (!ok) {
                        step = Step.NAVIGATE
                        status("ไม่สามารถปัดเกมได้ — รอตรวจใหม่")
                    } else {
                        skipped.clear()
                        settleStarted = SystemClock.elapsedRealtime()
                        nextAt = settleStarted + prefs.getLong("remote_swipe_rest_ms", 850L)
                        status("รอรายชื่อหยุด")
                    }
                }
            }
            Step.SCROLL_SETTLE -> {
                if (scene != Scene.FRIENDS) {
                    step = Step.NAVIGATE
                    return
                }
                val signature = motionSignature(frame)
                settleSamples = if (scrollSignature != null && similar(scrollSignature!!, signature))
                    settleSamples + 1 else 0
                scrollSignature = signature
                if (settleSamples >= 2 && sceneFrames >= 2) {
                    step = Step.SEARCH
                    pendingX = -1f
                    status("ตรวจปุ่มช่วยเหลือ")
                    nextAt = now + 350L
                } else {
                    nextAt = now + prefs.getLong("remote_stability_probe_ms", 410L)
                    // If motion never settles, do not tap guessed positions.
                    if (now - settleStarted >= 6500L) status("รอรายชื่อหยุดนิ่ง")
                }
            }
            Step.WAIT_CONFIRM -> {
                if (scene == Scene.MODAL && sendDialog(frame)) {
                    if (used == null || sent % 5 == 0 || used!! >= 47) {
                        val quota = readQuota(frame)
                        if (quota == null) {
                            readFailures++
                            status("กำลังอ่านโควต้าใหม่")
                            nextAt = SystemClock.elapsedRealtime() + if (readFailures >= 4) 1600L else 500L
                            return
                        }
                        if (used != null && quota != used) {
                            readFailures++
                            status("โควต้าไม่ตรง — ตรวจใหม่")
                            nextAt = SystemClock.elapsedRealtime() + 1200L
                            return
                        }
                        used = quota
                        readFailures = 0
                        prefs.edit().putInt("current_count", quota).apply()
                    }
                    val value = used ?: return
                    if (value >= 50) { finish("ครบโควต้า 50/50"); return }
                    step = Step.WAIT_SUCCESS
                    waitStarted = now
                    if (performTap(.558f, .797f, frame, 650L)) status("ส่งคะแนน ${value + 1}/50")
                } else if (scene == Scene.MODAL) {
                    status("รอหน้าต่างยืนยันที่ถูกต้อง")
                } else if (now - waitStarted > 7000L) {
                    skipped.add(confirmX)
                    step = Step.SEARCH
                    status("ข้ามตำแหน่งที่เปิดไม่สำเร็จ")
                }
            }
            Step.WAIT_SUCCESS -> {
                if (scene == Scene.MODAL && !sendDialog(frame) && successDialog(frame)) {
                    sent++
                    used = (used ?: 0).plus(1).coerceAtMost(50)
                    prefs.edit().putInt("current_count", used!!).apply()
                    skipped.add(confirmX)
                    step = Step.WAIT_CLOSE
                    waitStarted = now
                    performTap(.500f, .756f, frame, 500L)
                    status("ส่งสำเร็จ $sent ครั้ง")
                } else if (now - waitStarted > 10000L) {
                    // A failed timeout could have already sent a real LINE
                    // message. Never send another without re-checking the UI.
                    skipped.add(confirmX)
                    step = Step.NAVIGATE
                    status("ตรวจผลการส่ง")
                }
            }
            Step.WAIT_CLOSE -> {
                if (scene == Scene.FRIENDS) {
                    if ((used ?: 0) >= 50) finish("ครบโควต้า 50/50")
                    else { step = Step.SEARCH; status("กำลังช่วยเพื่อน") }
                } else if (scene == Scene.MODAL && successDialog(frame) &&
                    now - waitStarted >= 1400L) {
                    performTap(.500f, .756f, frame, 700L)
                } else if (now - waitStarted > 6500L) step = Step.NAVIGATE
            }
        }
    }

    private suspend fun performTap(x: Float, y: Float, frame: Bitmap, delayMs: Long): Boolean {
        val ok = adb.tap((frame.width * x).roundToInt(), (frame.height * y).roundToInt())
        nextAt = SystemClock.elapsedRealtime() + delayMs
        if (!ok) status("ยังแตะไม่ได้ — ตรวจหน้าเกม")
        return ok
    }

    private suspend fun readQuota(b: Bitmap): Int? {
        val x = (b.width * .540f).roundToInt().coerceIn(0, b.width - 2)
        val y = (b.height * .628f).roundToInt().coerceIn(0, b.height - 2)
        val w = (b.width * .103f).roundToInt().coerceIn(1, b.width - x)
        val h = (b.height * .087f).roundToInt().coerceIn(1, b.height - y)
        val cut = Bitmap.createBitmap(b, x, y, w, h)
        val scaled = Bitmap.createScaledBitmap(cut, w * 3, h * 3, true)
        cut.recycle()
        return try {
            val raw = suspendCancellableCoroutine<String?> { continuation ->
                recognizer.process(InputImage.fromBitmap(scaled, 0))
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            }
            parseQuota(raw ?: "")
        } finally { scaled.recycle() }
    }

    private fun parseQuota(raw: String): Int? {
        val n = raw.uppercase().replace('O', '0').replace('I', '1')
            .replace('L', '1').replace('S', '5').replace('|', '/')
        val match = Regex("(?<![0-9])([0-9]{1,2})\\s*[/]\\s*50(?![0-9])").find(n)
        val direct = match?.groupValues?.get(1)?.toIntOrNull()
        val digits = n.filter(Char::isDigit)
        val fallback = if (digits.length in 3..4 && digits.endsWith("50"))
            digits.dropLast(2).toIntOrNull() else null
        return (direct ?: fallback)?.takeIf { it in 0..50 }
    }

    private fun detectScene(b: Bitmap): Scene {
        if (isModal(b)) return Scene.MODAL
        if (isFriendsScene(b)) return if (friendsTab(b)) Scene.FRIENDS else Scene.OTHER_FRIEND_TAB
        if (isHome(b)) return Scene.HOME
        if (hasBack(b)) return Scene.BACK
        return Scene.UNKNOWN
    }

    private fun isFriendsScene(b: Bitmap): Boolean {
        val xy = floatArrayOf(.17f,.10f,.25f,.10f,.45f,.08f,.65f,.10f,
            .74f,.13f,.24f,.30f,.56f,.32f,.79f,.30f)
        var hits = 0
        for (i in xy.indices step 2) {
            val c = pixel(b, xy[i], xy[i + 1])
            val r = Color.red(c); val g = Color.green(c); val blue = Color.blue(c)
            if (r > 110 && g > 80 && blue > 40 && r > g * .95 &&
                g > blue * 1.15 && r > blue * 1.33) hits++
        }
        return hits >= 5
    }

    private fun isHome(b: Bitmap): Boolean {
        val ref = homeReference
        var best = 999.0
        for (dx in -1..1) for (dy in -1..1) {
            var d = 0L
            for (iy in 0 until ref.height) for (ix in 0 until ref.width) {
                val x = .53516f + (ix + .5f) * .07422f / ref.width + dx * .003f
                val y = .80383f + (iy + .5f) * .15929f / ref.height + dy * .006f
                val a = pixel(b, x, y); val c = ref.getPixel(ix, iy)
                d += abs(Color.red(a)-Color.red(c)) +
                    abs(Color.green(a)-Color.green(c)) + abs(Color.blue(a)-Color.blue(c))
            }
            best = min(best, d.toDouble() / (ref.width * ref.height * 3))
        }
        return best < 31
    }

    private fun hasBack(b: Bitmap): Boolean {
        var hits = 0
        for (iy in 0..10) for (ix in 0..10) {
            val c = pixel(b, .066f + ix * .0039f, .012f + iy * .0084f)
            if (Color.red(c) > 155 && Color.green(c) > 110 &&
                Color.blue(c) < 100 && Color.red(c) > Color.green(c) * 1.05) hits++
        }
        return hits >= 19
    }

    private fun pixel(b: Bitmap, x: Float, y: Float): Int =
        b.getPixel((x * b.width).toInt().coerceIn(0,b.width-1),
            (y * b.height).toInt().coerceIn(0,b.height-1))

    private fun green(c: Int): Boolean = Color.green(c) > 108 &&
        Color.green(c) > Color.red(c) * 1.5 &&
        Color.green(c) > Color.blue(c) * 1.2 &&
        Color.green(c) - Color.red(c) > 53

    private fun isModal(b: Bitmap): Boolean =
        ((Color.red(pixel(b,.20f,.28f)) + Color.green(pixel(b,.20f,.28f)) +
            Color.blue(pixel(b,.20f,.28f)))/3 < 98) &&
        ((Color.red(pixel(b,.20f,.10f)) + Color.green(pixel(b,.20f,.10f)) +
            Color.blue(pixel(b,.20f,.10f)))/3 < 98)

    private fun friendsTab(b: Bitmap): Boolean {
        var hits = 0
        for (iy in 0..14) for (ix in 0..25) {
            val c = pixel(b, .350f + ix * .00265f, .149f + iy * .0052f)
            if (Color.red(c)>175 && Color.green(c)>115 && Color.blue(c)<120 &&
                Color.green(c)>Color.blue(c)*1.30) hits++
        }
        return hits >= 26
    }

    private fun sendDialog(b: Bitmap): Boolean {
        var hits = 0
        for (iy in 0..10) for (ix in 0..13) {
            val c=pixel(b,.390f+ix*.006f,.762f+iy*.006f)
            if (Color.blue(c)>135 && Color.green(c)>123 &&
                Color.blue(c)>Color.red(c)*1.35) hits++
        }
        return hits>=36
    }

    private fun successDialog(b: Bitmap): Boolean {
        var hits=0
        for (iy in 0..9) for(ix in 0..14) {
            if (green(pixel(b,.448f+ix*.007f,.735f+iy*.007f))) hits++
        }
        return hits>=35
    }

    private fun helpButtons(b: Bitmap): List<Float> {
        val y=(b.height*.720f).toInt().coerceIn(0,b.height-1)
        val from=(b.width*.08f).toInt();val to=(b.width*.935f).toInt()
        val minWidth=(b.width*.067f).toInt()
        val buttons=mutableListOf<Float>()
        var start=-1
        var x=from
        while (x<=to+2) {
            val hit=x<=to&&green(b.getPixel(x,y))
            if(hit && start<0) start=x
            if(!hit&&start>=0){
                if(x-start>=minWidth){
                    val mid=(x+start)/(2f*b.width)
                    if(green(pixel(b,mid,.655f))&&green(pixel(b,mid,.705f))) buttons.add(mid)
                }
                start=-1
            }
            x+=2
        }
        return buttons
    }

    private fun frameSignature(b: Bitmap): IntArray {
        val xs=floatArrayOf(.13f,.29f,.45f,.61f,.77f,.93f)
        return IntArray(xs.size*3){ n ->
            val c=pixel(b,xs[n/3],.345f+(n%3)*.061f)
            (Color.red(c)/36 shl 8) or (Color.green(c)/36 shl 4) or (Color.blue(c)/36)
        }
    }

    private fun motionSignature(b: Bitmap): IntArray {
        val xs=floatArrayOf(.14f,.23f,.33f,.43f,.53f,.63f,.73f,.84f)
        val ys=floatArrayOf(.34f,.42f,.51f,.61f)
        return IntArray(xs.size*ys.size){n->
            val c=pixel(b,xs[n%xs.size],ys[n/xs.size])
            (Color.red(c)/24 shl 8) or (Color.green(c)/24 shl 4) or (Color.blue(c)/24)
        }
    }
    private fun similar(a:IntArray,b:IntArray):Boolean =
        a.size==b.size && a.indices.count{a[it]!=b[it]}<=2
}
