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
 *    only short 8-bit snapshots, 20 a second. They are stretched into a continuous
 *    signal; VLM's beat detection and spectrum still follow the music, a bit coarser.
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
    private var lastVisT = 0L
    private var silentSince = 0L
    private var capSilentSince = 0L
    private var visHeard = false
    private var told = ""
    private var pcm = ShortArray(0)
    private var window = ByteArray(0)
    private var winPos = 0
    private var winDir = 1

    /** Main thread. [projection] null = Visualizer only. */
    fun start(projection: MediaProjection?) {
        Native.vlmSetLive(true)
        startVisualizer()
        if (projection != null && Build.VERSION.SDK_INT >= 29 && startCapture(projection)) {
            say("Listening to music playing on this device")
        } else if (vis != null) {
            useVis = true
            Native.vlmSetRate(visRate)
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
            if (n <= 0) { if (n < 0) break else continue }
            var peak = capPeak
            for (i in 0 until n) { val v = Math.abs(buf[i].toInt()); if (v > peak) peak = v }
            capPeak = peak
            Native.vlmPush(buf, 0, n / 2)    // a full buffer just drops this block
        }
    }

    // ------------------------------------------------------------- visualizer --
    private fun startVisualizer() {
        vis = runCatching {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                visRate = (samplingRate / 1000).takeIf { it in 8000..192000 } ?: 44100
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer, wave: ByteArray, rate: Int) = onWave(wave)
                    override fun onFftDataCapture(v: Visualizer, fft: ByteArray, rate: Int) {}
                }, Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }
        }.onFailure { Log.w(TAG, "Visualizer unavailable", it) }.getOrNull()
    }

    /** ~20 times a second, on the main thread. */
    private fun onWave(wave: ByteArray) {
        if (quit) return
        val now = SystemClock.uptimeMillis()
        var level = 0
        for (b in wave) { val d = Math.abs((b.toInt() and 0xFF) - 128); if (d > level) level = d }
        val heard = level > 3

        if (!useVis) {
            // Capture running: is it hearing anything?
            val cap = capPeak; capPeak = 0
            if (cap > 64) { capSilentSince = now; visHeard = false; silentSince = now }
            else if (heard) visHeard = true
            if (now - capSilentSince > 2000 && visHeard) {
                // The playing app doesn't allow capture: use the Visualizer.
                Log.i(TAG, "playback capture silent, Visualizer hears music: switching")
                useVis = true
                runCatching { record?.stop() }
                Native.vlmSetRate(visRate)
                lastVisT = 0
            }
        }
        if (useVis) feedVisualizer(wave, now)

        if (heard || (!useVis && capSilentSince == now)) silentSince = now
        if (silentSince == 0L) silentSince = now
        if (now - silentSince > 3000) say("No music yet: start it playing in Spotify or another app")
        else say("Listening to music playing on this device")
    }

    /**
     * Turns 20 snapshots a second (each ~23 ms of an 8-bit waveform) into the steady
     * stream VLM expects: each snapshot is played back and forth until the next one
     * arrives, so there are no clicks at the joins.
     */
    private fun feedVisualizer(wave: ByteArray, now: Long) {
        window = wave.copyOf()
        val dt = if (lastVisT == 0L) 50L else (now - lastVisT).coerceIn(0, 200)
        lastVisT = now
        val frames = (visRate * dt / 1000).toInt()
        if (frames <= 0 || window.isEmpty()) return
        if (pcm.size < frames * 2) pcm = ShortArray(frames * 2)
        val len = window.size
        if (winPos >= len) winPos = len - 1
        for (i in 0 until frames) {
            val s = (((window[winPos].toInt() and 0xFF) - 128) * 256).toShort()
            pcm[i * 2] = s; pcm[i * 2 + 1] = s
            winPos += winDir
            if (winPos >= len) { winPos = len - 2; winDir = -1 }
            else if (winPos < 0) { winPos = 1; winDir = 1 }
        }
        Native.vlmPush(pcm, 0, frames)
    }

    companion object { private const val TAG = "NUONDroid" }
}
