package com.retroportlab.nuondroid

import android.app.Activity
import android.view.ViewGroup
import android.view.Display
import android.hardware.display.DisplayManager
import android.content.Context
import android.content.Intent
import android.app.Presentation
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

    // VLM mode: the player firmware's light show, driven by the user's music.
    private var vlm = false
    private var music: MusicPlayer? = null
    private var live: LiveAudio? = null       // music from another app (Spotify…) instead of files
    private var liveMode = false
    private var tracks: List<File> = emptyList()
    private var effect = -1               // effect we asked for
    private var dark = 0                  // consecutive half-seconds of black picture
    private var lastChange = 0L           // when the effect last changed (auto change)
    private var effectDir = 1
    private lateinit var infoView: TextView
    private lateinit var trackView: TextView      // track / artist (Music)
    private var trackText = ""
    private var stopNowPlaying: (() -> Unit)? = null

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
        vlm = intent.getBooleanExtra(EXTRA_VLM, false)
        if (vlm) {
            liveMode = intent.getBooleanExtra(EXTRA_LIVE, false)
            tracks = (intent.getStringArrayListExtra(EXTRA_TRACKS) ?: arrayListOf()).map { File(it) }
            if ((tracks.isEmpty() && !liveMode) || !VlmFirmware.installed(this)) { finish(); return }
            gamePath = VlmFirmware.file(this).absolutePath
            gameTitle = "Music (VLM)"
        } else {
            gamePath = intent.getStringExtra(EXTRA_GAME) ?: run { finish(); return }
            gameTitle = intent.getStringExtra(EXTRA_TITLE) ?: File(gamePath).nameWithoutExtension
        }

        glView = newGlView(this)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        tvNote = ui.text("", 16f, Ui.SUBTEXT, false).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(tvNote, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        fpsView = ui.text("", 13f, Ui.TEXT, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(ui.dp(8f), ui.dp(4f), ui.dp(8f), ui.dp(4f))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(6f).toFloat() }
            visibility = View.GONE
        }
        root.addView(fpsView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = ui.dp(12f); topMargin = ui.dp(12f)
        })
        infoView = ui.text("", 15f, Ui.TEXT, true).apply {
            setPadding(ui.dp(12f), ui.dp(8f), ui.dp(12f), ui.dp(8f))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8f).toFloat() }
            visibility = View.GONE
        }
        root.addView(infoView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = ui.dp(16f); bottomMargin = ui.dp(16f)
        })
        trackView = ui.text("", 15f, Ui.TEXT, true).apply {
            setPadding(ui.dp(12f), ui.dp(8f), ui.dp(12f), ui.dp(8f))
            gravity = Gravity.END
            maxWidth = ui.dp(360f)
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8f).toFloat() }
            visibility = View.GONE
        }
        root.addView(trackView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = ui.dp(16f); bottomMargin = ui.dp(16f)
        })
        setContentView(root)
        hideSystemUi()
    }

    // ---------------------------------------------------------------- TV ----
    // When the system casts to a TV (Chromecast / Google TV screen cast) or a display
    // is plugged in, Android offers it as a presentation display. With "TV only" the
    // picture moves there and the handheld screen goes dark (menu still works here);
    // otherwise the system just mirrors the handheld.
    private var tvNote: TextView? = null
    private var presentation: Presentation? = null
    private var currentTvId: Int? = null      // display the picture is on, null = handheld
    private val displayManager by lazy { getSystemService(DISPLAY_SERVICE) as DisplayManager }

    private fun newGlView(ctx: Context) = GLSurfaceView(ctx).apply {
        setEGLContextFactory(SharedEgl)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(Renderer())
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    private fun tvDisplay(): Display? =
        if (!prefs.tvOnly) null
        else displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.isValid }

    /** A cast / wireless display rather than one on a cable (HDMI, USB-C), which can do 60 fps. */
    private fun isWireless(d: Display): Boolean {
        val n = d.name.lowercase()
        return !(n.contains("hdmi") || n.contains("usb") || n.contains("displayport") || n.contains("dp "))
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = updateTv()
        override fun onDisplayRemoved(id: Int) = updateTv()
        override fun onDisplayChanged(id: Int) {}
    }

    /** Puts the picture on the TV, or back on the handheld, to match what's connected. */
    fun updateTv() {
        if (isFinishing || isDestroyed) return
        val want = tvDisplay()
        val wantId = want?.displayId
        // Casting streams about 30 fps: draw a steady 30 (two fields per frame) rather
        // than 60 the encoder drops unevenly. The emulation still runs at full speed.
        val casting = TvCast.castingTo(this) != null ||
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).any { isWireless(it) }
        Native.setFieldsPerFrame(if (casting) 2 else 1)
        val settled = if (wantId == null) currentTvId == null else currentTvId == wantId && presentation?.isShowing == true
        if (settled) return

        // Retire the current view. Its GL thread lets go of the shared context (see
        // SharedEgl), which the new view's thread then picks up: same context, new
        // surface, so neither the core nor the host loses anything.
        val old = glView
        old.preserveEGLContextOnPause = false
        old.onPause()
        (old.parent as? ViewGroup)?.removeView(old)
        presentation?.let { runCatching { it.dismiss() } }
        presentation = null

        if (want != null) {
            val p = Presentation(this, want)
            p.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val tvRoot = FrameLayout(p.context).apply { setBackgroundColor(0xFF000000.toInt()) }
            glView = newGlView(p.context)
            // Draw at the NUON's own 480 lines (widened to the TV's shape) and let the
            // display hardware scale up: there's no more detail to show, and the
            // handheld has less to draw while it also encodes the cast stream.
            val mode = want.mode
            val tvW = maxOf(mode.physicalWidth, mode.physicalHeight)
            val tvH = minOf(mode.physicalWidth, mode.physicalHeight)
            if (tvH > 480) glView.holder.setFixedSize((480 * tvW / tvH + 1) and 1.inv(), 480)
            tvRoot.addView(glView, FrameLayout.LayoutParams(-1, -1))
            moveTrackView(tvRoot)      // the track name goes with the picture
            p.setContentView(tvRoot)
            p.setOnDismissListener { uiHandler.post { updateTv() } }
            try {
                p.show()
                presentation = p
            } catch (e: WindowManager.InvalidDisplayException) {
                moveTrackView(root)
                (glView.parent as? ViewGroup)?.removeView(glView)
                glView = newGlView(this)
                root.addView(glView, 0, FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            glView = newGlView(this)
            root.addView(glView, 0, FrameLayout.LayoutParams(-1, -1))
            moveTrackView(root)
        }
        val onTv = presentation != null
        currentTvId = presentation?.display?.displayId
        tvNote?.text = if (onTv) "Showing on ${want?.name ?: "TV"}\n\nSelect + Start for the menu" else ""
        tvNote?.visibility = if (onTv) View.VISIBLE else View.GONE
        // Dim the handheld's backlight while it shows nothing.
        window.attributes = window.attributes.apply {
            screenBrightness = if (onTv) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (!menuOpen) glView.onResume()
    }

    private fun moveTrackView(to: FrameLayout) {
        if (trackView.parent === to) return
        (trackView.parent as? ViewGroup)?.removeView(trackView)
        to.addView(trackView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = ui.dp(16f); bottomMargin = ui.dp(16f)
        })
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
            // A view taking over the shared context (moved to or from a TV) finds all
            // GL state in place; only a newly made context needs it rebuilt.
            if (!started || SharedEgl.fresh) Native.glCreated()
            if (started) return
            started = true

            val system = BiosInstaller.install(this@EmulatorActivity, prefs.coreCrt, prefs.blockMode)
            val saves = File(getExternalFilesDir(null) ?: filesDir, "saves").apply { mkdirs() }
            val tmp = File(cacheDir, "tmp").apply { deleteRecursively(); mkdirs() }
            Native.init(system.absolutePath, saves.absolutePath, tmp.absolutePath)
            applyDisplayOptions()
            if (vlm) Native.vlmEnable()

            if (Native.loadGame(gamePath)) {
                if (vlm) runOnUiThread { startMusic() }
            } else {
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

    // ------------------------------------------------------------ VLM mode --
    private fun startMusic() {
        if (music != null || live != null) return
        if (liveMode) { startLive(); return }
        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, tracks.size - 1)
        music = MusicPlayer(tracks, start) { _, f ->
            val (title, artist) = NowPlaying.fromFile(f)       // music thread: reads the file's tags
            runOnUiThread { setTrack(title, artist) }
        }
            .also { it.start() }
        lastChange = System.currentTimeMillis()
        uiHandler.post(vlmWatcher)
    }

    /** Music from another app: capture it if the user allowed screen capture, else the Visualizer. */
    private fun startLive() {
        val l = LiveAudio(this) { msg -> runOnUiThread { showInfo(msg) } }
        live = l
        lastChange = System.currentTimeMillis()
        uiHandler.post(vlmWatcher)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_PROJ_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJ_DATA)
        val code = intent.getIntExtra(EXTRA_PROJ_CODE, 0)
        if (data != null) CaptureService.start(this, code, data) { p -> if (live === l) l.start(p) }
        else l.start(null)
        watchNowPlaying()
    }

    /** Live music: the playing app's track and artist, if the user allowed it (notification access). */
    private fun watchNowPlaying() {
        if (!liveMode || live == null || stopNowPlaying != null || !NowPlaying.allowed(this)) return
        stopNowPlaying = NowPlaying.watch(this) { t, a -> setTrack(t, a) }
    }

    private val hideTrack = Runnable { if (prefs.trackName != 2) trackView.visibility = View.GONE }

    /** A new track started (title null = unknown). */
    private fun setTrack(title: String?, artist: String?) {
        trackText = when {
            title.isNullOrBlank() -> ""
            artist.isNullOrBlank() -> "\u266A  $title"
            else -> "\u266A  $title\n$artist"
        }
        showTrack(true)
    }

    /** Off: never. When the track changes: for 6 s after a change. Always: while known. */
    private fun showTrack(changed: Boolean) {
        trackView.text = trackText
        uiHandler.removeCallbacks(hideTrack)
        val mode = prefs.trackName
        val show = trackText.isNotEmpty() && (mode == 2 || (mode == 1 && changed))
        trackView.visibility = if (show) View.VISIBLE else View.GONE
        if (show && mode == 1) uiHandler.postDelayed(hideTrack, 6000)
    }

    private fun cycleTrackName(): String {
        prefs.trackName = (prefs.trackName + 1) % Prefs.TRACK_NAMES.size
        showTrack(prefs.trackName != 0)
        return Prefs.TRACK_NAMES[prefs.trackName]
    }

    private fun nextTrack() { music?.next(); live?.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
    private fun previousTrack() { music?.previous(); live?.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

    private fun stopLive() {
        stopNowPlaying?.invoke(); stopNowPlaying = null
        live?.stop(); live = null
        CaptureService.stop(this)
    }

    private fun effectCount() = Native.vlmEffectCount()

    private fun setEffect(n: Int, announce: Boolean) {
        val count = effectCount()
        if (count <= 0) return
        effect = Math.floorMod(n, count)
        Native.vlmSetEffect(effect)
        dark = 0
        lastChange = System.currentTimeMillis()
        if (announce) showInfo("Effect ${effect + 1} of $count")
    }

    private fun stepEffect(dir: Int) { effectDir = dir; setEffect(effect + dir, true) }

    private fun randomEffect() {
        val count = effectCount()
        if (count > 0) { effectDir = 1; setEffect((0 until count).random(), true) }
    }

    /** Twice a second: first effect once VLM is up, skip effects that stay black, auto change. */
    private val vlmWatcher = object : Runnable {
        override fun run() {
            if (!menuOpen) {
                val count = effectCount()
                if (count > 0 && effect < 0) setEffect(prefs.vlmEffect.coerceIn(0, count - 1), false)
                else if (count > 0) {
                    // Some effects need a particular input (or are transitions) and draw nothing here.
                    dark = if (Native.vlmLuma() <= 18) dark + 1 else 0
                    if (dark >= 8) setEffect(effect + effectDir, false)
                    else if (prefs.vlmAuto && System.currentTimeMillis() - lastChange > AUTO_MS) setEffect(effect + 1, false)
                }
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    private val hideInfo = Runnable { infoView.visibility = View.GONE }
    private fun showInfo(s: String) {
        infoView.text = s
        infoView.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideInfo)
        uiHandler.postDelayed(hideInfo, 3000)
    }

    /** VLM controls; returns true if the key was used. */
    private fun vlmKey(code: Int, up: Boolean): Boolean {
        val action: (() -> Unit)? = when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_RIGHT -> { { stepEffect(1) } }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_LEFT -> { { stepEffect(-1) } }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> { { randomEffect() } }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_DOWN -> { { nextTrack() } }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_UP -> { { previousTrack() } }
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> { { showInfo("Track name: " + cycleTrackName()) } }
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_L2 -> { {
                prefs.vlmAuto = !prefs.vlmAuto
                lastChange = System.currentTimeMillis()
                showInfo(if (prefs.vlmAuto) "Effects change by themselves" else "Effects stay until you change them")
            } }
            else -> null
        }
        if (action == null) return false
        if (up) action()
        return true
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
        if (vlm) {
            panel.addView(ui.row("Next track", null, null) { nextTrack(); closePauseMenu() })
            panel.addView(ui.row("Next effect", null, null) { closePauseMenu(); stepEffect(1) })
            lateinit var autoRow: View
            autoRow = ui.row("Change effects by themselves", null, if (prefs.vlmAuto) "On" else "Off") {
                prefs.vlmAuto = !prefs.vlmAuto
                lastChange = System.currentTimeMillis()
                ui.setRowValue(autoRow, if (prefs.vlmAuto) "On" else "Off")
            }
            panel.addView(autoRow)
            lateinit var trackRow: View
            trackRow = ui.row("Show track name", null, Prefs.TRACK_NAMES[prefs.trackName]) { ui.setRowValue(trackRow, cycleTrackName()) }
            panel.addView(trackRow)
            if (liveMode && !NowPlaying.allowed(this)) {
                panel.addView(ui.row("Track names from Spotify",
                    "Android only shares what another app is playing if you turn on notification access for NUONDroid", "Allow") {
                    NowPlaying.openSettings(this)
                })
            }
            panel.addView(ui.row("Controls",
                "A / B: next / previous effect  ·  X / Y: random effect\nR1 / L1: next / previous track  ·  L2 / R2: auto change on/off\nL3 / R3: track name off / on change / always", null) { })
        } else panel.addView(ui.row("Reset game", null, null) {
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
        panel.addView(ui.row("Cast to TV", TvCast.status(this), null) { TvCast.openPicker(this) })
        lateinit var tvRow: View
        tvRow = ui.row("While casting", "TV only: the handheld screen goes dark. Both: the TV mirrors the handheld.",
            if (prefs.tvOnly) "TV only" else "Both") {
            prefs.tvOnly = !prefs.tvOnly
            ui.setRowValue(tvRow, if (prefs.tvOnly) "TV only" else "Both")
            updateTv()
        }
        panel.addView(tvRow)
        panel.addView(ui.row(if (vlm) "Stop music" else "Quit to library", null, null) { finish() })

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
        if (vlm) {
            if (e.repeatCount == 0 && vlmKey(code, up)) return true
            if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) return true
            return super.dispatchKeyEvent(e)
        }
        if (e.repeatCount > 0 && pad.handles(code)) return true
        if (pad.onKey(code, down)) {
            Native.setInput(pad.mask)
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (vlm) return super.dispatchGenericMotionEvent(e)
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
        displayManager.registerDisplayListener(displayListener, uiHandler)
        updateTv()
        watchNowPlaying()
        hideSystemUi()
        applyFpsOverlay()
        glView.onResume()
        if (!menuOpen) Native.setPaused(false)
    }

    override fun onPause() {
        displayManager.unregisterDisplayListener(displayListener)
        uiHandler.removeCallbacks(fpsUpdater)
        Native.setPaused(true)
        Native.setInput(0)
        if (isFinishing) { music?.stop(); stopLive() }
        if (isFinishing) glView.queueEvent { Native.unload() }   // runs on the GL thread, before it pauses
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        presentation?.let { runCatching { it.dismiss() } }
        if (effect >= 0) prefs.vlmEffect = effect
        music?.stop()
        stopLive()
        uiHandler.removeCallbacks(vlmWatcher)
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
        const val EXTRA_VLM = "vlm"
        const val EXTRA_TRACKS = "tracks"
        const val EXTRA_START = "start"
        const val EXTRA_LIVE = "live"
        const val EXTRA_PROJ_CODE = "projCode"
        const val EXTRA_PROJ_DATA = "projData"
        private const val AUTO_MS = 45_000L
    }
}
