package com.retroportlab.nuondroid

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Music another app is playing (Spotify, YouTube Music, a radio app…), fed to VLM.
 * NUONDroid doesn't play it again: the other app keeps playing it as usual.
 *
 * Two ways in, best first:
 *  - Playback capture (Android 10+, needs the screen-capture consent): the real
 *    stereo audio. Apps can opt out of it, and some music apps do.
 *  - Android's Visualizer on the whole output: works with nearly every app, but gives
 *    only 8-bit mono. Polled for its newest samples every 15 ms, it still gives an
 *    unbroken signal, so VLM's beat detection works about as well as with capture.
 * When capture hears nothing while the Visualizer hears music (an app that opted
 * out), it switches to the Visualizer by itself.
 */
class LiveAudio(private val ctx: Context, private val status: (String) -> Unit) {
    @Volatile private var quit = false
    @Volatile private var useVis = false
    @Volatile private var capPeak = 0           // loudest captured sample since the last check
    private var record: AudioRecord? = null
    private var capThread: Thread? = null
    private var vis: Visualizer? = null
    private var visRate = 44100
    private var silentSince = 0L
    private var capSilentSince = 0L
    private var visHeard = false
    private var told = ""
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pcm = ShortArray(0)

    /** Main thread. [projection] null = Visualizer only. */
    fun start(projection: MediaProjection?) {
        Native.vlmSetLive(true)
        startVisualizer()
        if (projection != null && Build.VERSION.SDK_INT >= 29 && startCapture(projection)) {
            // Android allows one screen capture at a time: starting a cast (screen
            // mirroring to a Chromecast) ends ours. Carry on with the Visualizer.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { switchToVisualizer("screen capture ended") } }
            }, main)
            say("Listening to music playing on this device")
        } else if (vis != null) {
            Native.vlmSetRate(visRate)
            useVis = true
            say("Listening to music playing on this device")
        } else {
            say("This device doesn't let NUONDroid hear other apps")
        }
    }

    fun stop() {
        quit = true
        runCatching { record?.stop() }
        capThread?.interrupt()
        capThread?.join(500)
        runCatching { record?.release() }
        record = null
        visThread?.interrupt()
        visThread?.join(500)
        runCatching { vis?.enabled = false }
        runCatching { vis?.release() }
        vis = null
    }

    /** Skips tracks in whatever app is playing, like headphone buttons do. */
    fun mediaKey(code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun say(s: String) { if (s != told) { told = s; status(s) } }

    // ---------------------------------------------------------------- capture --
    @SuppressLint("MissingPermission", "NewApi")
    private fun startCapture(projection: MediaProjection): Boolean = runCatching {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val rate = 48000
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, 4 * 4096))
            .setAudioPlaybackCaptureConfig(cfg).build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        Native.vlmSetRate(rate)
        r.startRecording()
        record = r
        capSilentSince = SystemClock.uptimeMillis()
        capThread = Thread({ captureLoop(r) }, "vlm-live").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        true
    }.onFailure { Log.w(TAG, "playback capture unavailable", it) }.getOrDefault(false)

    private fun captureLoop(r: AudioRecord) {
        val buf = ShortArray(960 * 2)      // 20 ms
        while (!quit && !useVis) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) {
                if (n < 0) { main.post { switchToVisualizer("capture error $n") }; break }
                continue
            }
            var peak = capPeak
            for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
            capPeak = peak
            Native.vlmPush(buf, 0, n / 2)    // a full buffer just drops this block
        }
    }

    // ------------------------------------------------------------- visualizer --
    private var visThread: Thread? = null

    private fun startVisualizer() {
        vis = runCatching {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                visRate = (samplingRate / 1000).takeIf { it in 8000..192000 } ?: 44100
                enabled = true
            }
        }.onFailure { Log.w(TAG, "Visualizer unavailable", it) }.getOrNull()
        val v = vis ?: return
        visThread = Thread({ pollLoop(v) }, "vlm-vis").apply { priority = Thread.MAX_PRIORITY - 1; start() }
    }

    /**
     * Asks the Visualizer for the newest samples every 15 ms (its own callbacks come only
     * 20 times a second, each a 23 ms snapshot, which loses most drum hits in between).
     * Each answer ends at "now", so its last 15 ms of samples continue the previous one:
     * VLM gets an unbroken 8-bit signal, as good for beat detection as the real thing.
     */
    private fun pollLoop(v: Visualizer) {
        val wave = ByteArray(v.captureSize)
        var lastNs = 0L
        while (!quit) {
            try { Thread.sleep(15) } catch (e: InterruptedException) { break }
            val ok = runCatching { v.getWaveForm(wave) == Visualizer.SUCCESS }.getOrDefault(false)
            val nowNs = System.nanoTime()
            if (!ok) { lastNs = 0; continue }
            onWave(wave, SystemClock.uptimeMillis())
            if (useVis) {
                val frames = if (lastNs == 0L) 0 else ((nowNs - lastNs) * visRate / 1_000_000_000L).toInt().coerceAtMost(visRate / 5)
                if (frames > 0) feedVisualizer(wave, frames)
            }
            lastNs = nowNs
        }
    }

    /** Poll thread, ~65 times a second. */
    private fun onWave(wave: ByteArray, now: Long) {
        var level = 0
        for (b in wave) { val d = Math.abs((b.toInt() and 0xFF) - 128); if (d > level) level = d }
        val heard = level > 3

        if (!useVis) {
            // Capture running: is it hearing anything?
            val cap = capPeak; capPeak = 0
            if (cap > 64) { capSilentSince = now; visHeard = false; silentSince = now }
            else if (heard) visHeard = true
            // The playing app doesn't allow capture: use the Visualizer.
            if (now - capSilentSince > 2000 && visHeard) main.post { switchToVisualizer("capture silent, Visualizer hears music") }
        }

        if (heard || (!useVis && capSilentSince == now)) silentSince = now
        if (silentSince == 0L) silentSince = now
        if (now - silentSince > 3000) say("No music yet: start it playing in Spotify or another app")
        else say("Listening to music playing on this device")
    }

    /** Main thread. */
    private fun switchToVisualizer(why: String) {
        if (useVis || quit) return
        Log.i(TAG, "live audio: $why, using the Visualizer")
        runCatching { record?.stop() }
        if (vis == null) startVisualizer()
        if (vis == null) { say("Lost the music: Android stopped NUONDroid hearing it"); return }
        Native.vlmSetRate(visRate)
        useVis = true
    }

    /** The newest [frames] samples of the window (repeating its start if the poll was late). */
    private fun feedVisualizer(wave: ByteArray, frames: Int) {
        if (pcm.size < frames * 2) pcm = ShortArray(frames * 2)
        val len = wave.size
        for (i in 0 until frames) {
            val k = len - frames + i
            val b = wave[if (k >= 0) k else Math.floorMod(k, len)]
            val s = (((b.toInt() and 0xFF) - 128) * 256).toShort()
            pcm[i * 2] = s; pcm[i * 2 + 1] = s
        }
        Native.vlmPush(pcm, 0, frames)
    }

    companion object { private const val TAG = "NUONDroid" }
}
