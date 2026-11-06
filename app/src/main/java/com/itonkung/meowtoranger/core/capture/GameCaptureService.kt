package com.itonkung.meowtoranger.core.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.itonkung.meowtoranger.MainActivity
import com.itonkung.meowtoranger.core.AppRuntime
import com.itonkung.meowtoranger.modules.friends.FriendSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-authorized full-screen MediaProjection foreground service.
 *
 * The service stops capturing as soon as a previously observed LINE Rangers
 * session loses foreground focus, so it does not keep looking at bank apps.
 * Android 14+ allows only one virtual display per user consent token.
 */
class GameCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.itonkung.meowtoranger.START_CAPTURE"
        const val ACTION_STOP = "com.itonkung.meowtoranger.STOP_CAPTURE"
        const val EXTRA_RESULT = "result_code"
        const val EXTRA_DATA = "result_data"
        const val NOTIFICATION_ID = 8077
        @Volatile var active = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var imageReader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var engine: FriendSession? = null
    private var lastFrameAt = 0L
    private var seenGame = false
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || active) return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        val code = intent.getIntExtra(EXTRA_RESULT, 0)
        if (data == null || code != android.app.Activity.RESULT_OK || !AppRuntime.adb(this).isConnected) {
            AppRuntime.status(this, "เชื่อม Wireless Debugging ก่อนเริ่ม")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, notification())
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(code, data)
                ?: throw IllegalStateException("ไม่สามารถเริ่มการจับภาพหน้าจอ")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (Build.VERSION.SDK_INT >= 34 && width > 1 && height > 1) {
                        mainHandler.post { resizeDisplay(width, height) }
                    }
                }
            }, mainHandler)
            thread = HandlerThread("MeowtoScreen").apply { start() }
            val p = Point()
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(p)
            val width = p.x.coerceAtLeast(100)
            val height = p.y.coerceAtLeast(100)
            openReader(width, height)
            display = proj.createVirtualDisplay(
                "MeowtoGame", width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, mainHandler
            )
            engine = FriendSession(this, AppRuntime.adb(this)) { message ->
                AppRuntime.status(this, message)
                mainHandler.post { stopSelf() }
            }
            active = true
            AppRuntime.status(this, "กำลังเข้าเกม")
        } catch (e: Exception) {
            AppRuntime.status(this, "เริ่มจับภาพไม่ได้: ${e.message ?: "ไม่ทราบสาเหตุ"}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun openReader(width: Int, height: Int) {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                if (!active || stopped) return@setOnImageAvailableListener
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 550L || !busy.compareAndSet(false, true)) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = image.planes[0]
                if (plane.pixelStride != 4 || plane.rowStride <= 0) {
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                val paddedWidth = plane.rowStride / plane.pixelStride
                if (paddedWidth < image.width) {
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                val wide = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                wide.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
                wide.recycle()
                scope.launch {
                    try { handleFrame(cropped) }
                    finally { cropped.recycle(); busy.set(false) }
                }
            } catch (_: Exception) {
                busy.set(false)
            } finally {
                image.close()
            }
        }, Handler(thread!!.looper))
    }

    private suspend fun handleFrame(frame: Bitmap) {
        if (!active || stopped) return
        val game = AppRuntime.adb(this).foregroundIsGame()
        if (!game) {
            if (seenGame) {
                AppRuntime.status(this, "ออกจากเกมแล้ว — หยุดอัตโนมัติ")
                mainHandler.post { stopSelf() }
            } else {
                AppRuntime.status(this, "รอเปิด LINE Rangers")
            }
            return
        }
        seenGame = true
        engine?.onFrame(frame)
    }

    /** Resize without calling createVirtualDisplay again (Android 14+ single-use token). */
    private fun resizeDisplay(width: Int, height: Int) {
        if (stopped || width < 50 || height < 50) return
        val old = imageReader
        if (old?.width == width && old.height == height) return
        openReader(width, height)
        display?.resize(width, height, resources.displayMetrics.densityDpi)
        display?.surface = imageReader!!.surface
    }

    private fun createChannel() {
        val channel = NotificationChannel("meowto_capture", "Meowto Ranger ทำงาน",
            NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, GameCaptureService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "meowto_capture")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Meowto Ranger")
            .setContentText("กำลังช่วยเพื่อนใน LINE Rangers")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "หยุด", stop)
            .build()
    }

    override fun onDestroy() {
        stopped = true
        active = false
        engine?.close()
        engine = null
        imageReader?.setOnImageAvailableListener(null, null)
        display?.release(); display = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
        thread?.quitSafely(); thread = null
        scope.cancel()
        super.onDestroy()
    }
}
