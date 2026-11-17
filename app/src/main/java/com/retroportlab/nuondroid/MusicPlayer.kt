package com.retroportlab.nuondroid

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes a playlist with Android's own codecs (MP3, AAC/M4A, FLAC, Ogg, Opus, WAV…)
 * and hands 16-bit stereo PCM to the native host, which plays it and shows it to VLM.
 * Runs on its own thread; the host's ring buffer paces it (a full ring = wait).
 */
class MusicPlayer(
    private val tracks: List<File>,
    private var index: Int,
    private val onTrack: (index: Int, file: File) -> Unit,
) {
    @Volatile private var skip = 0          // +1 next, -1 previous
    @Volatile private var quit = false
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ loop() }, "vlm-music").apply { priority = Thread.MAX_PRIORITY - 1; start() }
    }

    fun stop() { quit = true; thread?.interrupt() }
    fun next() { skip = 1 }
    fun previous() { skip = -1 }

    private fun loop() {
        var failures = 0
        while (!quit && tracks.isNotEmpty()) {
            val f = tracks[index]
            onTrack(index, f)
            val ok = runCatching { play(f) }.onFailure { Log.w(TAG, "can't play ${f.name}", it) }.isSuccess
            if (quit) break
            failures = if (ok) 0 else failures + 1
            if (failures >= tracks.size) break            // nothing playable
            val dir = if (skip != 0) skip else 1
            if (skip != 0) Native.vlmFlush()
            skip = 0
            index = Math.floorMod(index + dir, tracks.size)
        }
    }

    /** Plays one file to the end, or until skipped. */
    private fun play(f: File) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.absolutePath)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            require(track >= 0) { "no audio track" }
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            Native.vlmSetRate(rate)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var stereo = ShortArray(0)
            while (!quit && skip == 0) {
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    Native.vlmSetRate(rate)
                } else if (oi >= 0) {
                    val out = c.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset); out.limit(info.offset + info.size)
                    val frames: Int
                    if (float) {
                        val fb = out.asFloatBuffer()
                        frames = fb.remaining() / channels
                        if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                        for (i in 0 until frames) {
                            val l = fb.get(i * channels); val r = if (channels > 1) fb.get(i * channels + 1) else l
                            stereo[i * 2] = (l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            stereo[i * 2 + 1] = (r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                    } else {
                        val sb = out.asShortBuffer()
                        frames = sb.remaining() / channels
                        if (stereo.size < frames * 2) stereo = ShortArray(frames * 2)
                        if (channels == 2) sb.get(stereo, 0, frames * 2)
                        else for (i in 0 until frames) {
                            val l = sb.get(i * channels); val r = if (channels > 1) sb.get(i * channels + 1) else l
                            stereo[i * 2] = l; stereo[i * 2 + 1] = r
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    push(stereo, frames)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun push(pcm: ShortArray, frames: Int) {
        var done = 0
        while (done < frames && !quit && skip == 0) {
            val n = Native.vlmPush(pcm, done, frames - done)
            done += n
            if (n == 0) try { Thread.sleep(10) } catch (e: InterruptedException) { return }
        }
    }

    companion object {
        private const val TAG = "NUONDroid"
        val EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka", "3gp", "amr", "mid", "midi")
    }
}
