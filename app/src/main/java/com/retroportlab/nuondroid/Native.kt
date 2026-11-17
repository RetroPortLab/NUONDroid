package com.retroportlab.nuondroid

/** JNI surface of libnuondroid.so (see cpp/host.cpp). All but setInput/setOptions/setPaused must run on the GL thread. */
object Native {
    init { System.loadLibrary("nuondroid") }

    external fun init(systemDir: String, saveDir: String, tmpDir: String)
    external fun glCreated()
    external fun glChanged(w: Int, h: Int)
    /** GL thread, just before its context goes away (moving the picture to or from a TV). */
    external fun glDestroyed()
    external fun loadGame(path: String): Boolean
    external fun frame()
    external fun reset()
    external fun unload()
    external fun lastError(): String

    // Safe from any thread.
    external fun setInput(mask: Int)
    external fun setOptions(scanlinesPct: Int, aspectMode: Int, filter: Int)
    external fun setPaused(paused: Boolean)
    /** Emulated 60 Hz fields per drawn frame: 1 = 60 fps, 2 = steady 30 fps (casting). */
    external fun setFieldsPerFrame(n: Int)

    /** [screen fps, game fps, NUON instruction packets per second in millions], updated once a second. */
    external fun getStats(): FloatArray

    // ---- VLM mode (the player firmware's Virtual Light Machine, see VlmFirmware) ----
    /** GL thread, before loadGame(firmware pe). */
    external fun vlmEnable()
    /** Music thread: interleaved stereo PCM; returns frames taken (0 = buffer full, try again shortly). */
    external fun vlmPush(pcm: ShortArray, offsetFrames: Int, frames: Int): Int
    /** Music thread: sample rate of what comes next. */
    external fun vlmSetRate(rate: Int)
    /** Music thread: drop queued music (track skipped). */
    external fun vlmFlush()
    /** Any thread. */
    external fun vlmQueued(): Int
    external fun vlmSetEffect(n: Int)
    /** Current effect, or -1 while VLM is still starting. */
    external fun vlmEffect(): Int
    /** Number of effects (0 while VLM is still starting). */
    external fun vlmEffectCount(): Int
    /** Mean brightness of the last picture, 16 (black) .. 235. */
    external fun vlmLuma(): Int
}
