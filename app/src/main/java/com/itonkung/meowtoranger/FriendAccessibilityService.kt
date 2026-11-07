package com.itonkung.meowtoranger

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Touch automation initiated by a user while the LINE Rangers friends page is
 * visible. The app never uses a game API, reads LINE messages, or bypasses the
 * daily limit. Unrecognized pages enter a non-tapping recovery state instead
 * of deactivating a run; the user can always press the floating Stop button.
 */
class FriendAccessibilityService : AccessibilityService() {
    companion object {
        const val GAME_PACKAGE = "com.linecorp.LGRGS"
        private const val DAILY_LIMIT = 50
        private const val MAX_SWIPES = 110
        private const val MAX_STALE_SCROLLS = 9
        private const val OCR_INTERVAL = 5 // after a verified baseline, verify periodically
        private const val FOCUS_GRACE_MS = 8500L
        private const val NAV_COOLDOWN_MS = 950L
        private const val MAX_GESTURE_ERRORS = 4
        // Enablement is controlled by Android Settings; the app can only disable itself.
        private const val AUTO_OFF_DELAY_MS = 2200L
        @Volatile private var activeService: FriendAccessibilityService? = null
        fun disableForBanking(): Boolean {
            val service = activeService ?: return false
            service.handler.post { service.disableForOtherApps("ปิด Accessibility สำหรับแอปธนาคาร") }
            return true
        }
        // Swipe gestures are followed by screen-settle and button validation.

    }

    private enum class Phase { NAVIGATE, SEARCH, SCROLL_SETTLE, WAIT_CONFIRM, RECOGNIZING, WAIT_SUCCESS, WAIT_CLOSE }
    private enum class Scene { FRIENDS, FRIENDS_OTHER_TAB, HOME, BACK_PAGE, MODAL, UNKNOWN }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("auto", Context.MODE_PRIVATE) }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var overlayCount: TextView? = null
    private var overlayDetail: TextView? = null
    private var overlayGo: TextView? = null
    private var overlayStop: TextView? = null

    private var running = false
    private var hasSeenGame = false
    private var autoOffPending: Runnable? = null
    private var epoch = 0
    private var phase = Phase.NAVIGATE
    private var currentGamePackage = ""
    private var lastGameSeen = 0L
    private var screenshotPending = false
    private var gesturePending = false
    private var screenW = 0
    private var screenH = 0
    private var screenshotFails = 0
    private var quotaFailures = 0
    private var used: Int? = null
    private var maxQuota = DAILY_LIMIT
    private var sent = 0
    private var swipes = 0
    private var waitTicks = 0
    private var scrollStall = 0
    private var previousSignature: IntArray? = null
    private var settleStartedAt = 0L
    private var settleSamples = 0
    private var settleStableSamples = 0
    private var settleSignature: IntArray? = null
    private var attemptedX = -1f
    private var pendingHelpX = -1f
    private var navActions = 0
    private var recoveries = 0
    private var unknownFrames = 0
    private var navNextActionAt = 0L
    private var lastNavScene: Scene? = null
    private var sameNavActions = 0
    private var gestureErrors = 0
    private var lastObservedScene: Scene? = null
    private var stableSceneFrames = 0
    private var lastStatus = ""
    private val homeReference by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.home_friend_button_reference)
    }
    private val skippedSlots = mutableListOf<Float>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        hasSeenGame = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        saveState("พร้อมใช้งาน · ออกจากเกมแล้วจะปิดสิทธิ์อัตโนมัติ")
    }

    private fun cancelAutoOff() {
        autoOffPending?.let(handler::removeCallbacks)
        autoOffPending = null
    }

    private fun scheduleAutoOff() {
        if (!hasSeenGame) return
        cancelAutoOff()
        val pending = Runnable {
            autoOffPending = null
            val foreground = rootInActiveWindow?.packageName?.toString().orEmpty()
            // Do not disable due to transient System UI overlays or notification shade.
            if (foreground == GAME_PACKAGE || foreground.isBlank() ||
                foreground == "com.android.systemui" || foreground == "com.google.android.permissioncontroller") return@Runnable
            disableForOtherApps("ออกจาก LINE Rangers · ปิด Accessibility อัตโนมัติ")
        }
        autoOffPending = pending
        handler.postDelayed(pending, AUTO_OFF_DELAY_MS)
    }

    private fun disableForOtherApps(reason: String) {
        cancelAutoOff()
        running = false
        epoch++
        handler.removeCallbacksAndMessages(null)
        screenshotPending = false
        gesturePending = false
        saveState(reason)
        hideOverlay()
        disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Content-change events belong to views, not foreground applications.
        // In particular, Android's temporary system windows must NOT abort a run.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = event.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: return
        if (pkg == packageName) {
            // Returning to Meowto after playing is also leaving the game.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scheduleAutoOff()
            return
        }
        if (pkg == "com.kasikorn.retail.mbanking.wap") {
            // The bank must see the service genuinely switched off, not just paused.
            disableForOtherApps("เปิด K PLUS · ปิด Accessibility")
            return
        }
        if (pkg == GAME_PACKAGE) {
            cancelAutoOff()
            hasSeenGame = true
            currentGamePackage = pkg
            lastGameSeen = SystemClock.elapsedRealtime()
            showOverlay()
        } else {
            currentGamePackage = pkg
            if (!running) hideOverlay()
            // Do not leave a screenshot-capable service enabled in other apps.
            // The user's banking app checks the actual Accessibility setting.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) scheduleAutoOff()
        }
    }

    override fun onInterrupt() {
        // Android can interrupt accessibility gestures temporarily (e.g. a touch
        // or a system overlay). Do not deactivate a user-initiated run.
        if (running) {
            enterRecovery()
            statusOnce("รอระบบพร้อม")
            enqueue(1200)
        }
    }
    override fun onDestroy() {
        if (activeService === this) activeService = null
        running = false
        epoch++
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        try { recognizer.close() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density + 0.5f).toInt()
    private fun bg(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(text: String, color: Int, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }

    private fun showOverlay() {
        if (overlay != null) return
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = bg(Color.rgb(23, 25, 31), 16, Color.rgb(67, 69, 77))
            elevation = dp(4).toFloat()
        }
        val count = label("— / 50", Color.WHITE, 14f, true).apply {
            minWidth = dp(53)
        }
        val detail = label("พร้อม", Color.rgb(255, 175, 57), 10f, true).apply {
            setPadding(dp(5), 0, dp(8), 0)
        }
        val go = label("▶", Color.rgb(24, 20, 16), 19f, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = bg(Color.rgb(255, 173, 37), 12)
            setOnClickListener { startRun() }
        }
        val stop = label("■", Color.WHITE, 17f, true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = bg(Color.rgb(197, 53, 62), 12)
            setOnClickListener { stopRun("ผู้ใช้กดหยุด") }
        }
        overlayCount = count
        overlayDetail = detail
        overlayGo = go
        overlayStop = stop
        line.addView(count)
        line.addView(detail)
        line.addView(go)
        line.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(64)
        }
        try {
            windowManager?.addView(line, lp)
            overlay = line
            updateOverlay()
        } catch (_: Exception) {
            overlay = null
            overlayCount = null
            overlayDetail = null
            overlayGo = null
            overlayStop = null
        }
    }

    private fun hideOverlay() {
        overlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) { } }
        overlay = null
        overlayCount = null
        overlayDetail = null
        overlayGo = null
        overlayStop = null
    }
    private fun updateOverlay() {
        overlayCount?.text = "${used?.toString() ?: "?"}/$maxQuota"
        overlayDetail?.text = when {
            !running -> "พร้อม"
            phase == Phase.NAVIGATE -> "↩ กลับหน้าเพื่อน"
            phase == Phase.RECOGNIZING -> "◌ ตรวจเลข"
            phase == Phase.SCROLL_SETTLE -> "◌ รอเลื่อน"
            else -> "● ทำงาน"
        }
        overlayDetail?.setTextColor(if (running) Color.rgb(107, 233, 148) else Color.rgb(255, 175, 57))
        overlayGo?.visibility = if (running) View.GONE else View.VISIBLE
        overlayStop?.visibility = if (running) View.VISIBLE else View.GONE
    }
    private fun saveState(message: String) {
        lastStatus = message
        prefs.edit().putInt("current_count", used ?: -1)
            .putInt("current_max", maxQuota)
            .putInt("session_sent", sent)
            .putBoolean("running", running)
            .putString("last_status", message)
            .putLong("last_updated", System.currentTimeMillis())
            .apply()
        updateOverlay()
    }
    private fun statusOnce(message: String) {
        if (lastStatus != message) saveState(message)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun startRun() {
        if (running) return
        val pkg = rootInActiveWindow?.packageName?.toString() ?: currentGamePackage
        if (pkg != GAME_PACKAGE) {
            toast("เปิด LINE Rangers และเข้าเมนู เพื่อน ก่อน")
            return
        }
        if (!prefs.getBoolean("consent", false)) {
            toast("กรุณายืนยันการส่งข้อความ LINE ในแอปก่อน")
            return
        }
        running = true
        epoch++
        handler.removeCallbacksAndMessages(null)
        phase = Phase.NAVIGATE
        used = null
        maxQuota = DAILY_LIMIT
        sent = 0
        swipes = 0
        waitTicks = 0
        scrollStall = 0
        previousSignature = null
        clearSwipeSettling()
        skippedSlots.clear()
        pendingHelpX = -1f
        quotaFailures = 0
        screenshotFails = 0
        navActions = 0
        recoveries = 0
        unknownFrames = 0
        navNextActionAt = 0L
        lastNavScene = null
        sameNavActions = 0
        gestureErrors = 0
        lastObservedScene = null
        stableSceneFrames = 0
        screenshotPending = false
        gesturePending = false
        lastGameSeen = SystemClock.elapsedRealtime()
        saveState("กำลังเข้าหน้าเพื่อน")
        enqueue(250)
    }

    private fun stopRun(reason: String) {
        val old = running
        running = false
        epoch++
        handler.removeCallbacksAndMessages(null)
        screenshotPending = false
        gesturePending = false
        pendingHelpX = -1f
        // Consent remains valid when manually restarting the same authorized
        // session from the floating button. The user can revoke it in-app.
        saveState(reason)
        if (old) toast("$reason • ส่งสำเร็จ $sent ครั้ง")
    }

    private fun enqueue(ms: Long) {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, ms)
    }
    private val pollRunnable = Runnable { capture() }

    private fun capture() {
        if (!running || screenshotPending || gesturePending) return
        val topPkg = rootInActiveWindow?.packageName?.toString()
        if (topPkg == GAME_PACKAGE) lastGameSeen = SystemClock.elapsedRealtime()
        else {
            // Keep the run armed while an Android permission dialog, animation,
            // or another app temporarily owns focus. Never tap off-game.
            if (SystemClock.elapsedRealtime() - lastGameSeen >= FOCUS_GRACE_MS)
                statusOnce("รอกลับเข้าเกม")
            enqueue(700)
            return
        }
        screenshotPending = true
        val myEpoch = epoch
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    screenshotPending = false
                    val buffer = result.hardwareBuffer
                    var bitmap: Bitmap? = null
                    try {
                        if (!running || myEpoch != epoch) return
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("No bitmap")
                        bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.recycle()
                        val screen = bitmap ?: error("Bitmap copy failed")
                        screenshotFails = 0
                        screenW = screen.width
                        screenH = screen.height
                        advance(screen, myEpoch)
                    } catch (e: Exception) {
                        if (running && myEpoch == epoch) {
                            statusOnce("รอภาพหน้าจอ")
                            enqueue(1100)
                        }
                    } finally {
                        bitmap?.recycle()
                        buffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    screenshotPending = false
                    if (!running || myEpoch != epoch) return
                    screenshotFails++
                    // Some devices throttle consecutive Accessibility screenshots.
                    if (screenshotFails >= 6) {
                        statusOnce("รอการจับภาพ")
                        enqueue(2200)
                    } else enqueue(500L + screenshotFails * 160L)
                }
            })
        } catch (e: Exception) {
            screenshotPending = false
            statusOnce("รอการจับภาพ")
            enqueue(2200)
        }
    }

    private fun advance(frame: Bitmap, myEpoch: Int) {
        if (!running || epoch != myEpoch) return
        if (frame.width.toFloat() / frame.height !in 1.72f..2.65f) {
            statusOnce("รอเกมแนวนอน")
            enqueue(900)
            return
        }
        val scene = detectScene(frame)
        stableSceneFrames = if (scene == lastObservedScene) stableSceneFrames + 1 else 1
        lastObservedScene = scene
        // Navigation transitions can briefly resemble the friends screen.
        // Require consecutive consistent frames before acting on the friends UI.
        if (scene == Scene.FRIENDS && stableSceneFrames < 2 &&
            (phase == Phase.NAVIGATE || phase == Phase.SEARCH)) {
            enqueue(220)
            return
        }
        // Recover from a wrong tap without losing the user's one-time consent.
        // Never touch an unknown dialog: it may have an unrelated action.
        if (phase != Phase.NAVIGATE && phase != Phase.RECOGNIZING &&
            scene != Scene.FRIENDS && scene != Scene.MODAL) {
            if (scene == Scene.UNKNOWN) {
                unknownFrames++
                if (unknownFrames >= 7) {
                    enterRecovery()
                    statusOnce("รอหน้าเกมชัดเจน")
                    enqueue(800)
                } else enqueue(420)
                return
            }
            enterRecovery()
        }
        when (phase) {
            Phase.NAVIGATE -> { navigate(scene); return }
            Phase.SCROLL_SETTLE -> {
                // Do not tap after swiping until several subsequent frames
                // confirm the friends list has stopped moving. Gesture callback
                // already enforces prefs.getLong("remote_swipe_rest_ms", 850L) before the first sample.
                if (scene != Scene.FRIENDS) {
                    enterRecovery()
                    navigate(scene)
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val signature = motionSignature(frame)
                val old = settleSignature
                settleSamples++
                settleStableSamples = if (old != null && similarSignature(old, signature))
                    settleStableSamples + 1 else 0
                settleSignature = signature
                // Prefer 2 stable comparisons (3 snapshots ~0.8 seconds apart).
                // After a bounded timeout, fall back only if the same FRIENDS
                // scene has remained continuously visible for 3+ frames.
                val stable = settleStableSamples >= 2
                val timeout = now - settleStartedAt >= prefs.getLong("remote_settle_timeout_ms_v11", 2800L) &&
                    settleSamples >= 4 && stableSceneFrames >= 3
                if ((stable || timeout) && stableSceneFrames >= 2) {
                    phase = Phase.SEARCH
                    pendingHelpX = -1f
                    settleSignature = null
                    saveState("รายชื่อหยุดแล้ว — กำลังตรวจปุ่ม")
                    enqueue(260)
                } else {
                    statusOnce("กำลังรอรายชื่อหยุด")
                    enqueue(prefs.getLong("remote_stability_probe_ms_v11", 320L))
                }
                return
            }
            Phase.SEARCH -> {
                if (scene == Scene.MODAL) {
                    phase = Phase.NAVIGATE
                    statusOnce("รอปิดหน้าต่าง")
                    enqueue(900)
                    return
                }
                if (scene != Scene.FRIENDS) { enterRecovery(); navigate(scene); return }
                unknownFrames = 0
                val candidates = helpButtons(frame).filter { px ->
                    skippedSlots.none { abs(it - px) < 0.040f }
                }
                if (candidates.isNotEmpty()) {
                    val candidate = candidates.first()
                    // Fast swipes can leave cards animating after the scene becomes
                    // recognizable. Only tap when the same button appears twice.
                    if (pendingHelpX < 0f || abs(candidate - pendingHelpX) > .017f) {
                        pendingHelpX = candidate
                        enqueue(280)
                        return
                    }
                    attemptedX = candidate
                    pendingHelpX = -1f
                    phase = Phase.WAIT_CONFIRM
                    waitTicks = 0
                    saveState("กำลังเปิดหน้าต่างช่วยเหลือ")
                    tap(attemptedX, .688f, 280)
                } else {
                    pendingHelpX = -1f
                    val currentSignature = screenSignature(frame)
                    if (previousSignature != null &&
                        similarSignature(previousSignature!!, currentSignature)) scrollStall++
                    else scrollStall = 0
                    // Short swipe can fail to move a card carousel. Retry with
                    // a larger drag before assuming we reached the last page.
                    if (swipes >= MAX_SWIPES || scrollStall >= MAX_STALE_SCROLLS) {
                        stopRun("เลื่อนสุดรายการหรือไม่พบเพื่อนเพิ่มเติม")
                        return
                    }
                    previousSignature = currentSignature
                    skippedSlots.clear()
                    swipes++
                    phase = Phase.SCROLL_SETTLE
                    clearSwipeSettling()
                    settleStartedAt = SystemClock.elapsedRealtime()
                    val retryLong = scrollStall >= 2
                    statusOnce(if (retryLong) "ปัดซ้ำเพื่อข้ามจุดที่ติด (${swipes})"
                        else "กำลังเลื่อนหาเพื่อน (${swipes})")
                    val rest = prefs.getLong("remote_swipe_rest_ms_v11", 650L).coerceIn(500L, 900L)
                    if (retryLong) swipe(.86f, .52f, .16f, .52f, rest)
                    else swipe(.82f, .52f, .25f, .52f, rest)
                }
            }
            Phase.WAIT_CONFIRM -> {
                if (scene == Scene.MODAL && sendDialog(frame)) {
                    waitTicks = 0
                    // Reading the actual number first is mandatory. Once established,
                    // success confirmation increments the displayed count, and OCR is
                    // used again after every 5 completed sends and near the limit.
                    val mustRead = used == null || sent % OCR_INTERVAL == 0 ||
                        (used ?: 0) >= maxQuota - 3
                    if (mustRead) {
                        phase = Phase.RECOGNIZING
                        saveState("กำลังตรวจโควต้าในเกม")
                        recognizeQuota(frame, myEpoch, alternate = quotaFailures % 2 == 1)
                    } else {
                        sendConfirmed()
                    }
                } else if (scene == Scene.MODAL) {
                    // Unknown modal: do not guess which button sends a message.
                    phase = Phase.NAVIGATE
                    statusOnce("รอปิดหน้าต่าง")
                    enqueue(900)
                } else {
                    waitTicks++
                    if (waitTicks >= 14) {
                        skippedSlots.add(attemptedX)
                        phase = Phase.SEARCH
                        waitTicks = 0
                        saveState("ข้ามเพื่อนที่เปิดหน้าต่างไม่สำเร็จ")
                        enqueue(290)
                    } else enqueue(320)
                }
            }
            Phase.RECOGNIZING -> { /* OCR callback controls the next action */ }
            Phase.WAIT_SUCCESS -> {
                if (scene == Scene.MODAL && !sendDialog(frame) && successDialog(frame)) {
                    sent++
                    used = used?.plus(1)?.coerceAtMost(maxQuota)
                    skippedSlots.add(attemptedX)
                    phase = Phase.WAIT_CLOSE
                    waitTicks = 0
                    saveState("ส่งสำเร็จ $sent ครั้งในรอบนี้")
                    tap(.500f, .756f, 250)
                } else {
                    waitTicks++
                    if (waitTicks >= 18) {
                        // The send might have succeeded. Do not send it again
                        // until we regain a known page and OCR the real quota.
                        skippedSlots.add(attemptedX)
                        enterRecovery()
                        statusOnce("กำลังตรวจผลการส่ง")
                        enqueue(650)
                    } else enqueue(350)
                }
            }
            Phase.WAIT_CLOSE -> {
                if (scene == Scene.FRIENDS) {
                    if ((used ?: -1) >= maxQuota) {
                        stopRun("ครบโควต้าวันนี้ ${used}/${maxQuota}")
                    } else {
                        phase = Phase.SEARCH
                        waitTicks = 0
                        // Immediately move to the next unsent friend. Do not wait
                        // for the just-sent button to change colour on the server.
                        enqueue(230)
                    }
                } else {
                    waitTicks++
                    if (waitTicks >= 14) {
                        // The success message was already observed and counted.
                        // Navigate safely rather than changing running to false.
                        enterRecovery()
                        statusOnce("รอกลับหน้าเพื่อน")
                        enqueue(700)
                    } else enqueue(330)
                }
            }
        }
    }

    /** Navigation is intentionally page-aware. It never assumes that fixed
     *  coordinates belong to a help button outside the friends screen. */
    private fun navigate(scene: Scene) {
        when (scene) {
            Scene.FRIENDS -> {
                phase = Phase.SEARCH
                navActions = 0
                sameNavActions = 0
                lastNavScene = null
                navNextActionAt = 0L
                unknownFrames = 0
                waitTicks = 0
                previousSignature = null
                skippedSlots.clear()
                pendingHelpX = -1f
                clearSwipeSettling()
                saveState("กำลังช่วยเพื่อน")
                enqueue(130)
            }
            Scene.FRIENDS_OTHER_TAB -> {
                if (!countNavigation(scene)) return
                saveState("เปิดแท็บเพื่อน")
                tap(.385f, .195f, 390)
            }
            Scene.HOME -> {
                if (!countNavigation(scene)) return
                saveState("เปิดหน้าเพื่อน")
                tap(.574f, .885f, 600)
            }
            Scene.BACK_PAGE -> {
                if (!countNavigation(scene)) return
                saveState("ย้อนกลับ")
                // The round yellow back arrow is shared by team and subpages.
                tap(.085f, .049f, 480)
            }
            Scene.MODAL -> {
                // Unknown dialog: keep the run active but never tap it.
                statusOnce("รอปิดหน้าต่าง")
                enqueue(900)
            }
            Scene.UNKNOWN -> {
                // Loading screens and camera animations may be unrecognizable
                // for several seconds. Recover automatically when recognizable.
                unknownFrames++
                if (unknownFrames >= 7) statusOnce("รอหน้าเกมชัดเจน")
                enqueue(if (unknownFrames >= 7) 950 else 430)
            }
        }
    }

    private fun enterRecovery() {
        if (phase == Phase.NAVIGATE) return
        recoveries++
        phase = Phase.NAVIGATE
        // Preserve running=true, so Play need only be pressed once. Do not
        // assume the interrupted message succeeded: verify quota on return.
        used = null
        quotaFailures = 0
        waitTicks = 0
        unknownFrames = 0
        previousSignature = null
        clearSwipeSettling()
        skippedSlots.clear()
        pendingHelpX = -1f
        statusOnce("กำลังกลับหน้าเพื่อน")
    }

    private fun countNavigation(scene: Scene): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < navNextActionAt) {
            enqueue((navNextActionAt - now).coerceAtLeast(250))
            return false
        }
        if (lastNavScene == scene) sameNavActions++
        else sameNavActions = 0
        lastNavScene = scene
        navActions++
        unknownFrames = 0
        // Never hammer the same button during loading. A stuck page is
        // retried progressively, not treated as a fatal application stop.
        val delay = when {
            sameNavActions >= 7 -> 5000L
            sameNavActions >= 3 -> 2400L
            else -> NAV_COOLDOWN_MS
        }
        navNextActionAt = now + delay
        if (sameNavActions >= 7) statusOnce("กำลังกู้คืนหน้าเพื่อน")
        return true
    }

    private fun detectScene(b: Bitmap): Scene {
        if (isModal(b)) return Scene.MODAL
        if (isFriendsScene(b)) {
            return if (friendsTab(b)) Scene.FRIENDS else Scene.FRIENDS_OTHER_TAB
        }
        if (isHomeScene(b)) return Scene.HOME
        if (hasYellowBackArrow(b)) return Scene.BACK_PAGE
        return Scene.UNKNOWN
    }

    private fun isFriendsScene(b: Bitmap): Boolean {
        val points = floatArrayOf(
            .17f, .10f, .25f, .10f, .45f, .08f, .65f, .10f,
            .74f, .13f, .24f, .30f, .56f, .32f, .79f, .30f
        )
        var hits = 0
        for (i in points.indices step 2) {
            val c = pixel(b, points[i], points[i + 1])
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            if (r > 110 && g > 80 && bl > 40 && r > g * .95 &&
                g > bl * 1.15 && r > bl * 1.33) hits++
        }
        return hits >= 5
    }

    private fun hasYellowBackArrow(b: Bitmap): Boolean {
        var hits = 0
        var sampled = 0
        // Circular yellow back button is in the upper-left on team/subpages.
        for (iy in 0..10) for (ix in 0..10) {
            val c = pixel(b, .066f + ix * .0039f, .012f + iy * .0084f)
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            if (r > 155 && g > 110 && bl < 100 && r > g * 1.05) hits++
            sampled++
        }
        return hits >= sampled * .15
    }

    private fun isHomeScene(b: Bitmap): Boolean {
        val ref = homeReference
        var best = 1000.0
        // 18x18 fixed-interface template; three offsets tolerate small
        // letterboxing/rounding differences on different Android screens.
        for (dx in intArrayOf(-1, 0, 1)) for (dy in intArrayOf(-1, 0, 1)) {
            var difference = 0L
            for (iy in 0 until ref.height) for (ix in 0 until ref.width) {
                val px = (.53516f + (ix + .5f) * .07422f / ref.width + dx * .003f)
                val py = (.80383f + (iy + .5f) * .15929f / ref.height + dy * .006f)
                val a = pixel(b, px, py)
                val c = ref.getPixel(ix, iy)
                difference += abs(Color.red(a) - Color.red(c))
                difference += abs(Color.green(a) - Color.green(c))
                difference += abs(Color.blue(a) - Color.blue(c))
            }
            best = min(best, difference.toDouble() / (ref.width * ref.height * 3))
        }
        return best < 31
    }

    private fun sendConfirmed() {
        val value = used ?: run { stopRun("ไม่มีข้อมูลโควต้าที่ตรวจสอบแล้ว"); return }
        if (value >= maxQuota) { stopRun("ครบโควต้าประจำวัน"); return }
        phase = Phase.WAIT_SUCCESS
        waitTicks = 0
        saveState("กำลังส่งคะแนน ${value + 1}/${maxQuota}")
        // The next tap sends a real LINE message to the selected friend.
        tap(.558f, .797f, 480)
    }

    private fun recognizeQuota(frame: Bitmap, myEpoch: Int, alternate: Boolean) {
        if (!running || epoch != myEpoch) return
        // Narrow ROI isolates the yellow/white digits and excludes Thai text.
        val x = (frame.width * .540f).roundToInt().coerceIn(0, frame.width - 2)
        val y = (frame.height * .628f).roundToInt().coerceIn(0, frame.height - 2)
        val w = (frame.width * .103f).roundToInt().coerceIn(1, frame.width - x)
        val h = (frame.height * .087f).roundToInt().coerceIn(1, frame.height - y)
        val crop = Bitmap.createBitmap(frame, x, y, w, h)
        val factor = if (alternate) 4 else 3
        val scaled = Bitmap.createScaledBitmap(crop, w * factor, h * factor, true)
        crop.recycle()
        if (alternate) {
            // A colour-independent high-contrast second pass improves recognition
            // when orange/white digits overlap wooden UI texture.
            val px = IntArray(scaled.width * scaled.height)
            scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            for (i in px.indices) {
                val r = Color.red(px[i]); val g = Color.green(px[i]); val b = Color.blue(px[i])
                val brightWhite = min(r, min(g, b)) > 165
                val yellow = r > 180 && g > 145 && b < 165 && r > b * 1.4
                px[i] = if (brightWhite || yellow) Color.BLACK else Color.WHITE
            }
            scaled.setPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        }
        recognizer.process(InputImage.fromBitmap(scaled, 0))
            .addOnSuccessListener { output ->
                if (!running || epoch != myEpoch || phase != Phase.RECOGNIZING) return@addOnSuccessListener
                val quota = parseQuota(output.text)
                if (quota != null) {
                    val expected = used
                    // Guard against OCR reading a visually similar but wrong digit.
                    if (expected != null && quota.first != expected) {
                        retryQuota("ตัวเลขในเกมไม่ตรงกับจำนวนที่ส่งสำเร็จ", alternate)
                    } else {
                        used = quota.first
                        maxQuota = quota.second
                        quotaFailures = 0
                        saveState("ตรวจโควต้าในเกม ${used}/${maxQuota}")
                        sendConfirmed()
                    }
                } else retryQuota("อ่านตัวเลขโควต้าไม่ชัด", alternate)
            }
            .addOnFailureListener {
                if (running && epoch == myEpoch && phase == Phase.RECOGNIZING)
                    retryQuota("OCR ประมวลผลไม่สำเร็จ", alternate)
            }
            .addOnCompleteListener { scaled.recycle() }
    }

    private fun retryQuota(reason: String, alternate: Boolean) {
        quotaFailures++
        // Retry on the same dialog with a new screenshot and different threshold.
        // Do not disable Play on an unclear OCR frame. Leave the run active,
        // do not send the message, and retry at a low rate on the same dialog.
        phase = Phase.WAIT_CONFIRM
        waitTicks = 0
        statusOnce(if (quotaFailures >= 4) "รออ่านโควต้าให้ชัด" else "$reason — ตรวจใหม่")
        enqueue(if (quotaFailures >= 4) 1700 else if (alternate) 400 else 350)
    }

    internal fun parseQuota(raw: String): Pair<Int, Int>? {
        val normalized = raw.uppercase()
            .replace('O', '0').replace('Q', '0')
            .replace('I', '1').replace('L', '1')
            .replace('S', '5')
            .replace('\\', '/').replace('|', '/')
        // Slash can be lost at low resolution, but no inferred characters beyond
        // the separate number and exact daily maximum are accepted.
        val matches = Regex("(?<!\\d)(\\d{1,2})\\s*[/]\\s*(50)(?!\\d)").findAll(normalized).toList()
        val direct = if (matches.size == 1) matches[0].groupValues[1].toIntOrNull() else null
        val digitOnly = normalized.filter { it.isDigit() }
        val inferred = if (direct == null && digitOnly.length in 3..4 && digitOnly.endsWith("50"))
            digitOnly.dropLast(2).toIntOrNull() else null
        val number = direct ?: inferred ?: return null
        return if (number in 0..DAILY_LIMIT) number to DAILY_LIMIT else null
    }

    private fun pixel(b: Bitmap, x: Float, y: Float): Int = b.getPixel(
        (x * b.width).toInt().coerceIn(0, b.width - 1),
        (y * b.height).toInt().coerceIn(0, b.height - 1)
    )
    private fun lum(c: Int): Int = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
    private fun green(c: Int): Boolean = Color.green(c) > 108 &&
        Color.green(c) > Color.red(c) * 1.50 &&
        Color.green(c) > Color.blue(c) * 1.20 && Color.green(c) - Color.red(c) > 53
    private fun cyan(c: Int): Boolean = Color.blue(c) > 135 &&
        Color.green(c) > 123 && Color.blue(c) > Color.red(c) * 1.35
    private fun isModal(b: Bitmap): Boolean = lum(pixel(b, .20f, .28f)) < 98 && lum(pixel(b, .20f, .10f)) < 98

    private fun friendsTab(b: Bitmap): Boolean {
        var hits = 0
        for (iy in 0..14) for (ix in 0..25) {
            val c = pixel(b, .350f + ix * .00265f, .149f + iy * .0052f)
            if (Color.red(c) > 175 && Color.green(c) > 115 && Color.blue(c) < 120 &&
                Color.green(c) > Color.blue(c) * 1.30) hits++
        }
        return hits >= 26
    }
    private fun sendDialog(b: Bitmap): Boolean {
        var hits = 0
        for (iy in 0..10) for (ix in 0..13)
            if (cyan(pixel(b, .390f + ix * .006f, .762f + iy * .006f))) hits++
        return hits >= 36
    }
    private fun successDialog(b: Bitmap): Boolean {
        var hits = 0
        for (iy in 0..9) for (ix in 0..14)
            if (green(pixel(b, .448f + ix * .007f, .735f + iy * .007f))) hits++
        return hits >= 35
    }
    private fun helpButtons(b: Bitmap): List<Float> {
        val y = (b.height * .720f).toInt().coerceIn(0, b.height - 1)
        val from = (b.width * .080f).toInt()
        val to = (b.width * .935f).toInt()
        val minWidth = (b.width * .067f).toInt()
        val buttons = mutableListOf<Float>()
        var runStart = -1
        var x = from
        while (x <= to + 2) {
            val isHit = x <= to && green(b.getPixel(x, y))
            if (isHit && runStart < 0) runStart = x
            if (!isHit && runStart >= 0) {
                if (x - runStart >= minWidth) {
                    val mx = (x + runStart) / (2f * b.width)
                    if (green(pixel(b, mx, .655f)) && green(pixel(b, mx, .705f))) buttons.add(mx)
                }
                runStart = -1
            }
            x += 2
        }
        return buttons
    }
    private fun clearSwipeSettling() {
        settleStartedAt = 0L
        settleSamples = 0
        settleStableSamples = 0
        settleSignature = null
    }

    private fun motionSignature(b: Bitmap): IntArray {
        // Track card/avatar areas rather than animated screen edges. This is
        // used solely to ensure motion ceased; it never authorizes a tap alone.
        val xs = floatArrayOf(.14f, .23f, .33f, .43f, .53f, .63f, .73f, .84f)
        val ys = floatArrayOf(.34f, .42f, .51f, .61f)
        return IntArray(xs.size * ys.size) { n ->
            val c = pixel(b, xs[n % xs.size], ys[n / xs.size])
            ((Color.red(c) / 24) shl 8) or ((Color.green(c) / 24) shl 4) or
                (Color.blue(c) / 24)
        }
    }

    private fun similarSignature(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        // Allow two tiny changes for animated avatars but not a moving list.
        var changes = 0
        for (i in a.indices) if (a[i] != b[i]) changes++
        return changes <= 2
    }

    private fun screenSignature(b: Bitmap): IntArray {
        // Deliberately ignore animated edges; sample the static avatar artwork.
        val xs = floatArrayOf(.13f, .29f, .45f, .61f, .77f, .93f)
        return IntArray(xs.size * 3) { n ->
            val i = n / 3
            val yy = .345f + (n % 3) * .061f
            val c = pixel(b, xs[i], yy)
            ((Color.red(c) / 36) shl 8) or ((Color.green(c) / 36) shl 4) or (Color.blue(c) / 36)
        }
    }

    private fun tap(x: Float, y: Float, delayMs: Long) {
        val path = Path().apply { moveTo(screenW * x, screenH * y) }
        dispatch(path, 80, delayMs)
    }
    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float, delayMs: Long) {
        val path = Path().apply {
            moveTo(screenW * x0, screenH * y0)
            lineTo(screenW * x1, screenH * y1)
        }
        dispatch(path, prefs.getLong("remote_swipe_duration_ms_v11", 480L).coerceIn(400L, 850L), delayMs)
    }
    private fun dispatch(path: Path, duration: Long, delayMs: Long) {
        if (!running || gesturePending) return
        gesturePending = true
        val myEpoch = epoch
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gesturePending = false
                    gestureErrors = 0
                    if (running && epoch == myEpoch) enqueue(delayMs)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturePending = false
                    if (running && epoch == myEpoch) {
                        gestureErrors++
                        enterRecovery()
                        statusOnce("รอการแตะเสร็จ")
                        enqueue(if (gestureErrors >= MAX_GESTURE_ERRORS) 2200 else 700)
                    }
                }
            }, handler)
        if (!accepted) {
            gesturePending = false
            gestureErrors++
            enterRecovery()
            statusOnce("รอ Android อนุญาตแตะ")
            enqueue(if (gestureErrors >= MAX_GESTURE_ERRORS) 2500 else 800)
        }
    }
}
