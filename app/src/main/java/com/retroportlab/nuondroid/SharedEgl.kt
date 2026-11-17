package com.retroportlab.nuondroid

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/**
 * One GLES 3 context for the whole emulator process, handed from one GLSurfaceView to
 * the next. Moving the picture between the handheld and a TV swaps the view (and so
 * the GL thread and surface) but keeps the context, so the core's textures, shaders
 * and framebuffer survive. A GLSurfaceView releases its context when it pauses
 * without preserveEGLContextOnPause; here that only unbinds it.
 */
object SharedEgl : GLSurfaceView.EGLContextFactory {
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    private var context: EGLContext? = null

    /** True if the last createContext made a new context (so GL state must be rebuilt). */
    @Volatile var fresh = false
        private set

    @Synchronized
    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        context?.let { fresh = false; return it }
        val c = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE))
        context = c
        fresh = true
        return c
    }

    @Synchronized
    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
        // Kept for the next view; it goes with the process (each game gets a fresh one).
    }
}
