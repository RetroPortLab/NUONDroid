package com.retroportlab.nuondroid

/** JNI surface of libnuondroid.so (see cpp/host.cpp). All but setInput/setOptions/setPaused must run on the GL thread. */
object Native {
    init { System.loadLibrary("nuondroid") }

    external fun init(systemDir: String, saveDir: String, tmpDir: String)
    external fun glCreated()
    external fun glChanged(w: Int, h: Int)
    external fun loadGame(path: String): Boolean
    external fun frame()
    external fun reset()
    external fun unload()
    external fun lastError(): String

    // Safe from any thread.
    external fun setInput(mask: Int)
    external fun setOptions(scanlinesPct: Int, aspectMode: Int, filter: Int)
    external fun setPaused(paused: Boolean)

    /** [screen fps, game fps, NUON instruction packets per second in millions], updated once a second. */
    external fun getStats(): FloatArray
}
