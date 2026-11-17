package com.retroportlab.nuondroid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Android only lets an app hear other apps' audio from a foreground service of type
 * mediaProjection, with the user's screen-capture consent. This is that service: it
 * shows the "listening" notification and turns the consent into a MediaProjection
 * for [LiveAudio]. Runs in the emulator's process, like LiveAudio.
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music (VLM)", NotificationManager.IMPORTANCE_LOW))
        }
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NUONDroid")
            .setContentText("VLM is listening to your music")
            .setOngoing(true)
            .build()
        var projection: MediaProjection? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(1, n)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            if (data != null && code == Activity.RESULT_OK) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projection = mpm.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            Log.w("NUONDroid", "media projection unavailable", e)
        }
        current = projection
        val cb = onReady; onReady = null
        cb?.invoke(projection)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { current?.stop() }
        current = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "vlm-live"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private var current: MediaProjection? = null
        private var onReady: ((MediaProjection?) -> Unit)? = null

        /** Main thread. Calls [ready] (main thread) with the projection, or null if it couldn't be had. */
        fun start(ctx: Context, code: Int, data: Intent, ready: (MediaProjection?) -> Unit) {
            onReady = ready
            val i = Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                Log.w("NUONDroid", "can't start capture service", e)
                onReady = null
                ready(null)
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CaptureService::class.java)) }
    }
}
