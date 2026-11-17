package com.retroportlab.nuondroid

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import java.io.File

/**
 * Android only tells an app what another app is playing (Spotify's track and artist)
 * if the user turns on its "notification access". This empty listener is what they
 * turn on; NUONDroid reads no notifications, only the media sessions it unlocks.
 */
class NowPlayingListener : NotificationListenerService()

/** Track and artist: from a file's tags, or from the app that's playing (see [NowPlayingListener]). */
object NowPlaying {
    fun fromFile(f: File): Pair<String, String?> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val artist = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim()?.takeIf { it.isNotEmpty() }
            (title ?: f.nameWithoutExtension) to artist
        } catch (e: Exception) {
            f.nameWithoutExtension to null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun component(ctx: Context) = ComponentName(ctx, NowPlayingListener::class.java)

    fun allowed(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == component(ctx) }
    }

    fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    /**
     * Spotify's own "Device Broadcast Status" (a switch in Spotify's settings) announces
     * each new track to any app, with no special access. Returns a stop function.
     */
    fun watchSpotify(ctx: Context, onChange: (title: String?, artist: String?) -> Unit): () -> Unit {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val t = i.getStringExtra("track") ?: return
                onChange(t, i.getStringExtra("artist"))
            }
        }
        val f = android.content.IntentFilter("com.spotify.music.metadatachanged")
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(r, f)
        } catch (e: Exception) { return {} }
        return { runCatching { ctx.unregisterReceiver(r) }; Unit }
    }

    /**
     * Follows whichever app is playing and reports its track (main thread), or null
     * when nothing is known. Returns a stop function. Does nothing without access.
     */
    fun watch(ctx: Context, onChange: (title: String?, artist: String?) -> Unit): () -> Unit {
        if (!allowed(ctx)) return {}
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return {}
        val main = Handler(Looper.getMainLooper())
        var controller: MediaController? = null
        var last: Pair<String?, String?>? = null
        fun report(m: MediaMetadata?) {
            val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val a = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            val now = t to a
            if (now != last) { last = now; onChange(t, a) }
        }
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(m: MediaMetadata?) = report(m)
            override fun onPlaybackStateChanged(state: PlaybackState?) { pick() }
        }
        val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list, controller, cb)?.let { controller = it; report(it.metadata) } }
        fun pickNow() {
            val list = runCatching { msm.getActiveSessions(component(ctx)) }.getOrNull() ?: return
            choose(list, controller, cb)?.let { controller = it; report(it.metadata) }
        }
        pickFn = ::pickNow
        try {
            msm.addOnActiveSessionsChangedListener(sessions, component(ctx), main)
            pickNow()
        } catch (e: Exception) {
            Log.w("NUONDroid", "media sessions unavailable", e)
            return {}
        }
        return {
            runCatching { msm.removeOnActiveSessionsChangedListener(sessions) }
            controller?.unregisterCallback(cb)
            pickFn = null
        }
    }

    private var pickFn: (() -> Unit)? = null
    private fun pick() { pickFn?.invoke() }

    /** Prefers a session that's playing; moves the callback to it. Returns the new one, or null if unchanged. */
    private fun choose(list: List<MediaController>?, current: MediaController?, cb: MediaController.Callback): MediaController? {
        if (list.isNullOrEmpty()) return null
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.first()
        if (current != null && best.sessionToken == current.sessionToken) return null
        current?.unregisterCallback(cb)
        best.registerCallback(cb, Handler(Looper.getMainLooper()))
        return best
    }
}
