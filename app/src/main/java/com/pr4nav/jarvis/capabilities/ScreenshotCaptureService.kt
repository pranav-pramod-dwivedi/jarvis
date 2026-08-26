package com.pr4nav.jarvis.capabilities

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

class ScreenshotCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "jarvis_capture"
        const val NOTIF_ID = 9200
        const val EXTRA_RC = "rc"
        const val EXTRA_DATA = "data"

        @Volatile var lastResultPath: String? = null
        @Volatile var lastError: String? = null
        @Volatile var busy = false

        fun request(ctx: Context, resultCode: Int, data: Intent) {
            busy = true
            lastResultPath = null
            lastError = null
            val i = Intent(ctx, ScreenshotCaptureService::class.java)
                .putExtra(EXTRA_RC, resultCode)
                .putExtra(EXTRA_DATA, data)
            ctx.startForegroundService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var frameConsumed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS screen capture", NotificationManager.IMPORTANCE_LOW)
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("JARVIS capturing screen")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, b.build(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, b.build())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rc = intent?.getIntExtra(EXTRA_RC, -1) ?: -1
        @Suppress("DEPRECATION") val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        Thread {
            try {
                if (data == null) throw IllegalStateException("no consent data")
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(rc, data)
                    ?: throw IllegalStateException("projection rejected by system")
                projection = p
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { cleanup(); stopSelf() }
                }, Handler(Looper.getMainLooper()))
                capture(p)
            } catch (e: Exception) {
                Log.w("JARVIS", "capture failed", e)
                lastError = e.message ?: e.javaClass.simpleName
                ScreenshotCapability.invalidateToken()
            } finally {
                busy = false
                cleanup()
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun capture(p: MediaProjection) {
        val metrics = metrics()
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val latch = java.util.concurrent.CountDownLatch(1)

        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = ir

        ir.setOnImageAvailableListener({ r ->
            if (frameConsumed) return@setOnImageAvailableListener
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            frameConsumed = true
            try {
                val plane = img.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * w
                val bmpRaw = Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
                )
                bmpRaw.copyPixelsFromBuffer(buffer)
                img.close()
                val bmp = crop(bmpRaw, w, h)
                Handler(Looper.getMainLooper()).post {
                    try {
                        lastResultPath = save(bmp)
                        lastError = null
                    } catch (e: Exception) {
                        lastError = e.message
                    }
                    latch.countDown()
                }
            } catch (e: Exception) {
                lastError = e.message
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        vdisplay = p.createVirtualDisplay(
            "jarvis-shot", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, null
        )

        if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
            if (lastResultPath == null && lastError == null) lastError = "capture timed out"
        }
    }

    private fun crop(raw: Bitmap, w: Int, h: Int): Bitmap =
        if (raw.width > w || raw.height > h)
            Bitmap.createBitmap(raw, 0, 0, minOf(w, raw.width), minOf(h, raw.height))
        else raw

    private fun metrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m
    }

    private fun save(bmp: Bitmap): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "jarvis-$stamp.png"
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: throw IllegalStateException("cannot open output stream")
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val out = java.io.File(dir, "JARVIS/$name").apply { parentFile?.mkdirs() }
            java.io.FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.absolutePath
        }
    }

    private fun cleanup() {
        try { reader?.close() } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        reader = null; vdisplay = null; projection = null
    }
}
