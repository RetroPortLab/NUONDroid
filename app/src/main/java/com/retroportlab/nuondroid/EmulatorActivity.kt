package com.retroportlab.nuondroid

import android.app.Activity
import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Runs in its own process (":emu", see the manifest). The core keeps its state in
 * globals and was never meant to be re-initialised, so each game gets a fresh
 * process and we kill it on exit.
 */
class EmulatorActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private val pad = PadState()
    private var gamePath = ""
    private var gameTitle = ""
    private var started = false

    private var menuOpen = false
    private var pauseMenu: View? = null
    private var selectHeld = false
    private var startHeld = false
    private var swallowUps = 0   // releases of the keys that opened the pause menu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        prefs = Prefs.of(this)
        ui = Ui(this)
        gamePath = intent.getStringExtra(EXTRA_GAME) ?: run { finish(); return }
        gameTitle = intent.getStringExtra(EXTRA_TITLE) ?: File(gamePath).nameWithoutExtension

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            preserveEGLContextOnPause = true
            setRenderer(Renderer())
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        fpsView = ui.text("", 13f, Ui.TEXT, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(ui.dp(8f), ui.dp(4f), ui.dp(8f), ui.dp(4f))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(6f).toFloat() }
            visibility = View.GONE
        }
        root.addView(fpsView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = ui.dp(12f); topMargin = ui.dp(12f)
        })
        setContentView(root)
        hideSystemUi()
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // The core paces itself by the real clock (it emulates up to each 60 Hz
            // field, then returns a frame). Waiting for vsync in eglSwapBuffers on top
            // of that is time the NUON isn't running, so don't: the compositor still
            // shows whole frames.
            EGL14.eglSwapInterval(EGL14.eglGetCurrentDisplay(), 0)
            // Emulation runs on this thread; ask to keep it on a fast core.
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (e: Exception) {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (ignored: Exception) {}
            }
            Native.glCreated()
            if (started) return
            started = true

            val system = BiosInstaller.install(this@EmulatorActivity, prefs.coreCrt, prefs.blockMode)
            val saves = File(getExternalFilesDir(null) ?: filesDir, "saves").apply { mkdirs() }
            val tmp = File(cacheDir, "tmp").apply { deleteRecursively(); mkdirs() }
            Native.init(system.absolutePath, saves.absolutePath, tmp.absolutePath)
            applyDisplayOptions()

            if (!Native.loadGame(gamePath)) {
                val why = Native.lastError()
                runOnUiThread {
                    Toast.makeText(this@EmulatorActivity, "Could not start $gameTitle: $why", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = Native.glChanged(width, height)

        override fun onDrawFrame(gl: GL10?) = Native.frame()
    }

    private fun applyDisplayOptions() = Native.setOptions(Prefs.SCANLINE_PCT[prefs.scanlines], prefs.aspect, prefs.upscale)

    // ------------------------------------------------------------- FPS overlay --
    private lateinit var fpsView: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val fpsUpdater = object : Runnable {
        override fun run() {
            val s = Native.getStats()
            // Screen fps stays near 60 because the core paces itself by the clock;
            // the game's own frame rate is the number that shows slowdown.
            val game = if (s[1] >= 0.5f) String.format(Locale.US, "%.0f", s[1]) else "-"
            fpsView.text = String.format(Locale.US, "FPS %.0f  ·  game %s  ·  NUON %.1fM/s", s[0], game, s[2])
            uiHandler.postDelayed(this, 500)
        }
    }

    private fun applyFpsOverlay() {
        uiHandler.removeCallbacks(fpsUpdater)
        if (prefs.showFps) {
            fpsView.visibility = View.VISIBLE
            uiHandler.post(fpsUpdater)
        } else {
            fpsView.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------- pause menu --
    private fun openPauseMenu() {
        if (menuOpen) return
        menuOpen = true
        pad.clear()
        Native.setInput(0)
        Native.setPaused(true)

        val dim = FrameLayout(this).apply { setBackgroundColor(0xB0000000.toInt()); isClickable = true }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20f), ui.dp(20f), ui.dp(20f), ui.dp(20f))
            background = ui.rounded(Ui.BG, 0x40FFFFFF)
        }
        panel.addView(ui.text(gameTitle, 20f, Ui.TEXT, true))
        panel.addView(ui.text("Paused", 13f, Ui.SUBTEXT, false).apply { setPadding(0, 0, 0, ui.dp(12f)) })

        val resume = ui.row("Resume", null, null) { closePauseMenu() }
        panel.addView(resume)
        panel.addView(ui.row("Reset game", null, null) {
            glView.queueEvent { Native.reset() }
            closePauseMenu()
        })
        lateinit var scan: View
        scan = ui.row("Scanlines", null, Prefs.SCANLINE_NAMES[prefs.scanlines]) {
            prefs.scanlines = (prefs.scanlines + 1) % Prefs.SCANLINE_NAMES.size
            applyDisplayOptions()
            ui.setRowValue(scan, Prefs.SCANLINE_NAMES[prefs.scanlines])
        }
        panel.addView(scan)
        lateinit var upscaleRow: View
        upscaleRow = ui.row("Upscaling", null, Prefs.UPSCALE_NAMES[prefs.upscale]) {
            prefs.upscale = (prefs.upscale + 1) % Prefs.UPSCALE_NAMES.size
            applyDisplayOptions()
            ui.setRowValue(upscaleRow, Prefs.UPSCALE_NAMES[prefs.upscale])
        }
        lateinit var fpsRow: View
        fpsRow = ui.row("Show FPS", null, if (prefs.showFps) "On" else "Off") {
            prefs.showFps = !prefs.showFps
            applyFpsOverlay()
            ui.setRowValue(fpsRow, if (prefs.showFps) "On" else "Off")
        }
        lateinit var screenRow: View
        screenRow = ui.row("Screen", null, Prefs.SCREEN_NAMES[prefs.aspect]) {
            prefs.aspect = (prefs.aspect + 1) % Prefs.SCREEN_NAMES.size
            applyDisplayOptions()
            ui.setRowValue(screenRow, Prefs.SCREEN_NAMES[prefs.aspect])
        }
        panel.addView(screenRow)
        panel.addView(upscaleRow)
        panel.addView(fpsRow)
        panel.addView(ui.row("Quit to library", null, null) { finish() })

        val sv = ScrollView(this).apply { addView(panel, FrameLayout.LayoutParams(ui.dp(360f), -2)) }
        dim.addView(sv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))
        pauseMenu = dim
        resume.requestFocus()
    }

    private fun closePauseMenu() {
        if (!menuOpen) return
        pauseMenu?.let { root.removeView(it) }
        pauseMenu = null
        menuOpen = false
        pad.clear()
        Native.setInput(0)
        Native.setPaused(false)
    }

    // --------------------------------------------------------------- input --
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        val up = e.action == KeyEvent.ACTION_UP

        if (menuOpen && swallowUps > 0 && up && (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT)) {
            swallowUps--
            return true
        }
        if (menuOpen) {
            // Menu: A / Enter clicks the focused item; B / Back / Start closes it.
            if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (up) currentFocus?.performClick()
                return true
            }
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_START) {
                if (up) closePauseMenu()
                return true
            }
            if (code == KeyEvent.KEYCODE_BUTTON_SELECT) return true
            return super.dispatchKeyEvent(e)   // d-pad moves focus
        }

        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld = down
        if (code == KeyEvent.KEYCODE_BUTTON_START) startHeld = down
        if (selectHeld && startHeld) {
            selectHeld = false; startHeld = false; swallowUps = 2
            openPauseMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (up) openPauseMenu()
            return true
        }
        if (e.repeatCount > 0 && pad.handles(code)) return true
        if (pad.onKey(code, down)) {
            Native.setInput(pad.mask)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (!menuOpen && (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            && e.action == MotionEvent.ACTION_MOVE) {
            pad.onMotion(e)
            Native.setInput(pad.mask)
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // --------------------------------------------------------- lifecycle ----
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        applyFpsOverlay()
        glView.onResume()
        if (!menuOpen) Native.setPaused(false)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(fpsUpdater)
        Native.setPaused(true)
        Native.setInput(0)
        if (isFinishing) glView.queueEvent { Native.unload() }   // runs on the GL thread, before it pauses
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fresh process per game: unload already ran on the GL thread in onPause.
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_TITLE = "title"
    }
}
