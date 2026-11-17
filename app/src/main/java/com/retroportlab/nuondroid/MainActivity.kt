package com.retroportlab.nuondroid

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** Library, settings and update screens - the same design and controls as JagDroid. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var screen = SCREEN_LIBRARY

    // updates
    private var foundUpdate: Updater.Release? = null   // a newer release found this session
    @Volatile private var updateBusy = false
    @Volatile private var updateCancel = false

    /** One game in the library. */
    class Game(val file: File) {
        val title: String
        val tags: String

        init {
            val ext = file.extension.lowercase(Locale.US)
            // An extracted disc is a NUON/nuon.run inside a folder named after the game.
            var base = if (ext in LOOSE_EXTENSIONS) {
                val parent = file.parentFile
                if (parent != null && parent.name.equals("NUON", true)) parent.parentFile?.name ?: parent.name
                else parent?.name ?: file.nameWithoutExtension
            } else file.nameWithoutExtension
            // Download sites often turn spaces into underscores.
            if (!base.contains(' ') && base.contains('_')) base = base.replace('_', ' ').replace(Regex(" +"), " ").trim()
            val p = base.indexOf(" (")
            title = (if (p > 0) base.substring(0, p) else base).trim()
            val t = if (p > 0) base.substring(p + 1).replace(")(", ", ").replace(") (", ", ")
                .replace("(", "").replace(")", "").trim() else ""
            val kind = when (ext) {
                "iso", "img" -> "Disc image"
                "chd" -> "CHD"
                "zip" -> "ZIP"
                "mds", "mdf" -> "Alcohol image"
                else -> "Folder"
            }
            tags = if (t.isEmpty()) kind else "$t, $kind"
        }

        val key: String get() = file.absolutePath.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
    }

    // ------------------------------------------------------------ lifecycle --
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        ui = Ui(this)
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)
        hideSystemUi()
        // The library is drawn in onResume, which also runs right after this.
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        // Back from the all-files-access screen, or from a game: refresh what's shown.
        if (screen == SCREEN_LIBRARY) showLibrary()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    // ------------------------------------------------------------- library --
    private fun showLibrary() {
        screen = SCREEN_LIBRARY
        val list = LinearLayout(this)
        ui.page(root, "v" + versionName(), list,
            ui.pill("Music") { showMusic() },
            ui.pill("Games folder") { pickFolder() },
            ui.pill("Settings") { showSettings() })

        if (!hasStorageAccess()) {
            list.addView(ui.sectionTitle("Welcome"))
            list.addView(intro("NUONDroid reads your NUON disc images (.iso, .chd, .mds/.mdf, .zip, or an extracted NUON folder) "
                    + "straight from storage, so it needs permission to access all files."))
            val allow = ui.row("Allow file access", "Opens Android's settings; switch NUONDroid on, then come back", null) { requestStorageAccess() }
            list.addView(allow)
            allow.requestFocus()
            return
        }

        val folder = prefs.gamesDir
        if (folder.isEmpty()) {
            list.addView(ui.sectionTitle("Welcome"))
            list.addView(intro("Choose the folder that holds your NUON games (.iso, .chd, .mds/.mdf or .zip disc images, "
                    + "or extracted discs with a NUON folder inside) and they'll appear here. The BIOS is built in."))
            val choose = ui.row("Choose games folder", "Pick the folder once; NUONDroid remembers it", null) { pickFolder() }
            list.addView(choose)
            choose.requestFocus()
            return
        }

        if (foundUpdate != null) list.addView(updateBanner()) else autoCheckForUpdate(list)
        val scanning = ui.text("Looking for games…", 15f, Ui.SUBTEXT, false).apply { setPadding(ui.dp(4f), ui.dp(16f), 0, 0) }
        list.addView(scanning)
        Thread {
            val games = scanFolder(File(folder))
            handler.post { if (screen == SCREEN_LIBRARY && list.isAttachedToWindow) fillLibrary(list, games, folder) }
        }.start()
    }

    private fun intro(s: String): TextView = ui.text(s, 15f, Ui.SUBTEXT, false).apply {
        setPadding(ui.dp(4f), 0, ui.dp(4f), ui.dp(12f))
    }

    private fun scanFolder(dir: File): List<Game> {
        val out = mutableListOf<Game>()
        scanDir(dir, 0, out)
        return out.sortedBy { it.title.lowercase(Locale.US) }
    }

    /** Disc images anywhere a few levels down; loose executables only inside a NUON folder (one per extracted disc). */
    private fun scanDir(dir: File, depth: Int, out: MutableList<Game>) {
        val entries = dir.listFiles() ?: return
        val names = entries.map { it.name.lowercase(Locale.US) }.toSet()
        for (f in entries) {
            if (f.isDirectory) { if (depth < 3) scanDir(f, depth + 1, out); continue }
            val name = f.name.lowercase(Locale.US)
            if (name.contains("bios")) continue
            // Alcohol 120% images come as a .mds + .mdf pair: list the game once, by its .mds.
            if (name.endsWith(".mdf") && name.dropLast(4) + ".mds" in names) continue
            when (f.extension.lowercase(Locale.US)) {
                in IMAGE_EXTENSIONS -> out += Game(f)
                in LOOSE_EXTENSIONS -> if (name == "nuon.run" || dir.name.equals("NUON", true) && name.endsWith(".cd")) out += Game(f)
            }
        }
    }

    private fun fillLibrary(list: LinearLayout, games: List<Game>, folder: String) {
        list.removeAllViews()
        foundUpdate?.let { list.addView(updateBanner()) }
        if (games.isEmpty()) {
            list.addView(ui.sectionTitle("No games found"))
            list.addView(ui.text("$folder has no NUON games (.iso, .chd, .mds/.mdf, .zip or a NUON/nuon.run folder), "
                    + "or access to it was lost.", 15f, Ui.SUBTEXT, false))
            val r = ui.row("Choose games folder", null, null) { pickFolder() }
            list.addView(r)
            r.requestFocus()
            return
        }
        var first: View? = null
        // Continue playing: the most recent few games that are still in the folder.
        val recent = recentKeys().mapNotNull { k -> games.firstOrNull { it.key == k } }
        if (recent.isNotEmpty()) {
            list.addView(ui.sectionTitle("Continue playing"))
            for (g in recent.take(3)) {
                val r = gameRow(g)
                list.addView(r)
                if (first == null) first = r
            }
        }
        list.addView(ui.sectionTitle("All games  ·  ${games.size}"))
        for (g in games) {
            val r = gameRow(g)
            list.addView(r)
            if (first == null) first = r
        }
        first?.requestFocus()
    }

    private fun gameRow(g: Game): View {
        val sub = StringBuilder(g.tags)
        val last = prefs.getLong("played_" + g.key, 0)
        if (last > 0) sub.append("  ·  Played ")
            .append(DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        return ui.row(g.title, sub.toString(), null) { launch(g) }
    }

    private fun recentKeys(): List<String> = prefs.getString("recent", "").split("\n").filter { it.isNotEmpty() }

    private fun markPlayed(key: String) {
        val r = recentKeys().toMutableList()
        r.remove(key)
        r.add(0, key)
        while (r.size > 8) r.removeAt(r.size - 1)
        prefs.putString("recent", r.joinToString("\n"))
        prefs.putLong("played_$key", System.currentTimeMillis())
    }

    private fun launch(g: Game) {
        markPlayed(g.key)
        startActivity(Intent(this, EmulatorActivity::class.java)
            .putExtra(EmulatorActivity.EXTRA_GAME, g.file.absolutePath)
            .putExtra(EmulatorActivity.EXTRA_TITLE, g.title))
    }

    // --------------------------------------------------------------- music --
    /** Music (VLM): the NUON player's Virtual Light Machine, from the player's own firmware, with your music. */
    private fun showMusic() {
        screen = SCREEN_MUSIC
        val list = LinearLayout(this)
        ui.page(root, "Music", list, ui.pill("Music folder") { pickMusicFolder() }, ui.pill("Back") { showLibrary() })

        if (!VlmFirmware.installed(this)) {
            list.addView(ui.sectionTitle("Virtual Light Machine"))
            list.addView(intro("Play your music with VLM-2, Jeff Minter's light synthesiser from the NUON DVD players. "
                    + "NUONDroid runs the real thing from a player's firmware, which you supply once: the Samsung DVD-N501 "
                    + "firmware update (nuon.zip, or the NUON.CD file inside it)."))
            val r = ui.row("Install player firmware", "Pick nuon.zip or NUON.CD", null) { pickFirmware() }
            list.addView(r)
            r.requestFocus()
            return
        }
        if (!hasStorageAccess()) {
            val r = ui.row("Allow file access", "Needed to read your music", null) { requestStorageAccess() }
            list.addView(r); r.requestFocus(); return
        }
        val folder = prefs.musicDir
        if (folder.isEmpty()) {
            list.addView(ui.sectionTitle("Virtual Light Machine"))
            list.addView(intro("Choose the folder with your music (MP3, FLAC, Ogg, M4A, WAV…). "
                    + "While it plays: A / B change the effect, X / Y pick one at random, R1 / L1 skip tracks, "
                    + "Select + Start for the menu."))
            val r = ui.row("Choose music folder", null, null) { pickMusicFolder() }
            list.addView(r)
            r.requestFocus()
            return
        }
        val scanning = ui.text("Looking for music…", 15f, Ui.SUBTEXT, false).apply { setPadding(ui.dp(4f), ui.dp(16f), 0, 0) }
        list.addView(scanning)
        Thread {
            val tracks = mutableListOf<File>()
            scanMusic(File(folder), 0, tracks)
            tracks.sortBy { it.absolutePath.lowercase(Locale.US) }
            handler.post { if (screen == SCREEN_MUSIC && list.isAttachedToWindow) fillMusic(list, tracks, folder) }
        }.start()
    }

    private fun scanMusic(dir: File, depth: Int, out: MutableList<File>) {
        val entries = dir.listFiles() ?: return
        for (f in entries) {
            if (f.isDirectory) { if (depth < 4 && !f.name.startsWith(".")) scanMusic(f, depth + 1, out); continue }
            if (f.extension.lowercase(Locale.US) in MusicPlayer.EXTENSIONS) out += f
        }
    }

    private fun fillMusic(list: LinearLayout, tracks: List<File>, folder: String) {
        list.removeAllViews()
        if (tracks.isEmpty()) {
            list.addView(ui.sectionTitle("No music found"))
            list.addView(intro("$folder has no music files NUONDroid can play."))
            val r = ui.row("Choose music folder", null, null) { pickMusicFolder() }
            list.addView(r); r.requestFocus(); return
        }
        list.addView(ui.sectionTitle("Virtual Light Machine"))
        val all = ui.row("Play all", "${tracks.size} tracks", null) { playMusic(tracks, 0) }
        list.addView(all)
        list.addView(ui.row("Shuffle", null, null) { playMusic(tracks.shuffled(), 0) })
        list.addView(ui.sectionTitle("Tracks"))
        val base = File(folder)
        for ((i, f) in tracks.withIndex()) {
            val rel = f.parentFile?.relativeTo(base)?.path.orEmpty()
            list.addView(ui.row(f.nameWithoutExtension, rel.ifEmpty { null }, null) { playMusic(tracks, i) })
        }
        all.requestFocus()
    }

    private fun playMusic(tracks: List<File>, start: Int) {
        startActivity(Intent(this, EmulatorActivity::class.java)
            .putExtra(EmulatorActivity.EXTRA_VLM, true)
            .putStringArrayListExtra(EmulatorActivity.EXTRA_TRACKS, ArrayList(tracks.map { it.absolutePath }))
            .putExtra(EmulatorActivity.EXTRA_START, start))
    }

    private fun pickMusicFolder() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_MUSIC) }
        catch (e: Exception) { toast("No folder picker available on this device") }
    }

    private fun pickFirmware() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FIRMWARE)
        } catch (e: Exception) { toast("No file picker available on this device") }
    }

    private fun installFirmware(uri: Uri) {
        toast("Installing firmware…")
        Thread {
            val err = VlmFirmware.install(this, uri)
            handler.post {
                toast(err ?: "Player firmware installed")
                if (screen == SCREEN_MUSIC) showMusic() else if (screen == SCREEN_SETTINGS) showSettings()
            }
        }.start()
    }

    // ------------------------------------------------------------ settings --
    private fun showSettings() {
        screen = SCREEN_SETTINGS
        val list = LinearLayout(this)
        ui.page(root, "Settings", list, ui.pill("Back") { showLibrary() })

        list.addView(ui.sectionTitle("Library"))
        val folder = prefs.gamesDir
        val first = ui.row("Games folder", folder.ifEmpty { "Not chosen" }, "Change") { pickFolder() }
        list.addView(first)
        list.addView(ui.row("File access", if (hasStorageAccess()) "Allowed" else "Not allowed: games can't be read",
            if (hasStorageAccess()) null else "Allow") { if (!hasStorageAccess()) requestStorageAccess() })

        list.addView(ui.sectionTitle("Music (VLM)"))
        list.addView(ui.row("Player firmware", if (VlmFirmware.installed(this)) "Samsung DVD-N501 (installed)" else "Not installed: VLM needs it",
            if (VlmFirmware.installed(this)) "Replace" else "Install") { pickFirmware() })
        list.addView(ui.row("Music folder", prefs.musicDir.ifEmpty { "Not chosen" }, "Change") { pickMusicFolder() })

        list.addView(ui.sectionTitle("Display"))
        list.addView(cycleRow("Scanlines", "CRT-style lines between the picture's rows", "scanlines", Prefs.SCANLINE_NAMES))
        list.addView(cycleRow("Screen", "4:3 fills the height; Stretch fills the whole screen", "aspect", Prefs.SCREEN_NAMES))
        list.addView(cycleRow("Upscaling", "Soft: plain smoothing. Sharp pixels: crisp, even pixels. Smooth HD: bicubic, for a clean high-res look",
            "upscale", Prefs.UPSCALE_NAMES))
        list.addView(toggleRow("Show FPS", "Screen and game frame rates, and NUON speed, in the corner while playing", "showFps", false))
        list.addView(toggleRow("Core CRT shader", "NuanceResurrection's own CRT effect. Heavier on the GPU; takes effect next game",
            "coreCrt", false))

        list.addView(ui.sectionTitle("Performance"))
        list.addView(toggleRow("Faster CPU mode", "Runs NUON code in pre-decoded blocks. Turn off if a game misbehaves; takes effect next game",
            "blockMode", true))

        list.addView(sectionAndControls())

        list.addView(ui.sectionTitle("Updates"))
        lateinit var check: View
        check = ui.row("Check for updates", "Looks for a newer NUONDroid on GitHub and installs it",
            foundUpdate?.let { it.tag + " available" } ?: "Check") { checkForUpdate(check) }
        list.addView(check)
        list.addView(toggleRow("Check automatically", "Look for a new version when NUONDroid starts (at most twice a day)",
            "autoUpdate", true))

        list.addView(ui.sectionTitle("About"))
        list.addView(ui.row("NUONDroid " + versionName(),
            "A NUON emulator for Android, built on NuanceResurrection.\n"
                + "There's no ARM JIT yet, so games run on the interpreter; some will run below full speed.", null) { })
        first.requestFocus()
    }

    private fun sectionAndControls(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(ui.sectionTitle("Controls"))
        box.addView(ui.row("Button layout",
            "A = A   ·   B = B   ·   X = C-Left   ·   Y = C-Up   ·   L2 = C-Down   ·   R2 = C-Right\n"
                + "Right stick = C buttons   ·   L1 R1 = L R   ·   Select = NUON   ·   Select + Start = menu", null) { })
        return box
    }

    private fun cycleRow(title: String, sub: String, key: String, names: Array<String>): View {
        val cur = prefs.getInt(key, 0).coerceIn(0, names.size - 1)
        lateinit var row: View
        row = ui.row(title, sub, names[cur]) {
            val n = (prefs.getInt(key, 0) + 1) % names.size
            prefs.putInt(key, n)
            ui.setRowValue(row, names[n])
        }
        return row
    }

    private fun toggleRow(title: String, sub: String, key: String, def: Boolean): View {
        lateinit var row: View
        row = ui.row(title, sub, if (prefs.getBool(key, def)) "On" else "Off") {
            val n = !prefs.getBool(key, def)
            prefs.putBool(key, n)
            ui.setRowValue(row, if (n) "On" else "Off")
        }
        return row
    }

    // ------------------------------------------------------------- updates --
    /** On the library screen: quietly look for a new release now and then, and show a banner if there is one. */
    private fun autoCheckForUpdate(list: LinearLayout) {
        if (!prefs.getBool("autoUpdate", true) || updateBusy) return
        val last = prefs.getLong("updateChecked", 0)
        if (System.currentTimeMillis() - last < 12 * DateUtils.HOUR_IN_MILLIS) return
        prefs.putLong("updateChecked", System.currentTimeMillis())
        Thread {
            try {
                val r = Updater.latest()
                if (r != null && Updater.newer(r.tag, versionName())) handler.post {
                    foundUpdate = r
                    if (screen == SCREEN_LIBRARY && list.isAttachedToWindow) list.addView(updateBanner(), 0)
                }
            } catch (ignored: Exception) {
                // offline or GitHub unreachable: try again next time
            }
        }.start()
    }

    private fun updateBanner(): View {
        val r = foundUpdate!!
        val row = ui.row("Update available: ${r.tag}", "You have v${versionName()}. Select to see what's new and install.",
            "Update") { showUpdate(r) }
        ui.pillValue(row, Ui.ACCENT)
        return row
    }

    /** Settings > Check for updates. */
    private fun checkForUpdate(row: View) {
        if (updateBusy) return
        updateBusy = true
        ui.setRowValue(row, "Checking…")
        Thread {
            var msg: String
            var found: Updater.Release? = null
            try {
                val r = Updater.latest()
                msg = when {
                    r == null -> "No releases yet"
                    Updater.newer(r.tag, versionName()) -> { found = r; r.tag + " available" }
                    else -> "Up to date"
                }
            } catch (e: Exception) {
                msg = if (e is java.net.UnknownHostException) "No internet" else "Couldn't check"
                e.message?.let { d -> handler.post { toast(d) } }
            }
            val f = found
            handler.post {
                updateBusy = false
                prefs.putLong("updateChecked", System.currentTimeMillis())
                if (f != null) { foundUpdate = f; showUpdate(f) }
                else if (row.isAttachedToWindow) ui.setRowValue(row, msg)
            }
        }.start()
    }

    /** What's new in a release, with a button to download and install it. */
    private fun showUpdate(r: Updater.Release) {
        screen = SCREEN_UPDATE
        updateCancel = false
        val list = LinearLayout(this)
        ui.page(root, "Update", list, ui.pill("Back") { updateCancel = true; showSettings() })
        list.addView(ui.sectionTitle("NUONDroid " + r.tag))
        val size = if (r.apkSize > 0) String.format(Locale.US, "  ·  download %.1f MB", r.apkSize / 1048576.0) else ""
        list.addView(ui.text("You have v${versionName()}$size", 14f, Ui.SUBTEXT, false).apply {
            setPadding(ui.dp(4f), 0, ui.dp(4f), ui.dp(10f))
        })
        lateinit var go: View
        go = ui.row("Download and install", "Your games and settings are kept", null) { downloadAndInstall(r, go) }
        list.addView(go)
        list.addView(ui.row("Open the releases page", Updater.RELEASES_PAGE, null) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) } catch (e: Exception) { toast("No web browser found") }
        })
        if (r.notes.isNotBlank()) {
            list.addView(ui.sectionTitle("What's new"))
            val notes = r.notes.replace("\r", "").replace(Regex("(?m)^#+\\s*"), "").replace("**", "").trim()
            list.addView(ui.text(notes, 15f, Ui.TEXT, false).apply {
                setPadding(ui.dp(4f), 0, ui.dp(4f), ui.dp(12f))
                setLineSpacing(0f, 1.15f)
            })
        }
        go.requestFocus()
    }

    private fun downloadAndInstall(r: Updater.Release, row: View) {
        if (updateBusy) { updateCancel = true; return }   // pressing again cancels the download
        if (!Updater.canInstall(this)) {
            toast("Allow NUONDroid to install apps, then press Back and select Download again")
            try { Updater.openInstallPermission(this) } catch (e: Exception) { toast("Open Android Settings > Apps > NUONDroid > Install unknown apps") }
            return
        }
        updateBusy = true
        updateCancel = false
        ui.setRowTitle(row, "Downloading…  (select again to cancel)")
        Thread {
            var error: String? = null
            var apk: File? = null
            try {
                var lastUi = 0L
                apk = Updater.download(this, r) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 200) {
                        lastUi = now
                        val t = if (total > 0) String.format(Locale.US, "Downloading…  %d%%  (select again to cancel)", done * 100 / total)
                        else String.format(Locale.US, "Downloading…  %.1f MB", done / 1048576.0)
                        handler.post { ui.setRowTitle(row, t) }
                    }
                    !updateCancel
                }
                apk?.let { Updater.install(this, it) }
            } catch (e: Exception) {
                error = e.message ?: "Download failed"
            }
            val err = error
            val cancelled = apk == null && err == null
            handler.post {
                updateBusy = false
                if (!row.isAttachedToWindow) return@post
                when {
                    err != null -> { ui.setRowTitle(row, "Download and install"); toast(err) }
                    cancelled -> ui.setRowTitle(row, "Download and install")
                    else -> ui.setRowTitle(row, "Installing…  confirm on the next screen")
                }
            }
        }.start()
    }

    // ----------------------------------------------------------- storage ----
    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    // The core opens games with plain fopen() (ISOs are gigabytes and are read in
    // place), so it needs real file paths - that means all-files access on Android 11+.
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    private fun pickFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
        } catch (e: Exception) {
            toast("No folder picker available on this device")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_FIRMWARE) { data?.data?.let { installFirmware(it) }; return }
        if (requestCode == REQ_MUSIC) {
            val p = data?.data?.let { treeUriToPath(it) }
            if (p == null) toast("Couldn't use that folder. Pick one on internal storage or an SD card.")
            else { prefs.musicDir = p; if (screen == SCREEN_SETTINGS) showSettings() else showMusic() }
            return
        }
        if (requestCode != REQ_FOLDER) return
        val path = data?.data?.let { treeUriToPath(it) }
        if (path == null) {
            toast("Couldn't use that folder. Pick one on internal storage or an SD card.")
            return
        }
        prefs.gamesDir = path
        showLibrary()
    }

    /** content://…/tree/primary%3ARoms%2Fnuon -> /storage/emulated/0/Roms/nuon, SD cards -> /storage/<id>/… */
    private fun treeUriToPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep < 0) return null
        val volume = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    // --------------------------------------------------------------- input --
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val up = e.action == KeyEvent.ACTION_UP
        // Menus: A / Enter clicks the focused item, B / Back goes back.
        if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (up) currentFocus?.performClick()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
            if (up) when (screen) {
                SCREEN_SETTINGS, SCREEN_MUSIC -> showLibrary()
                SCREEN_UPDATE -> { updateCancel = true; showSettings() }
                else -> if (code == KeyEvent.KEYCODE_BACK) finish()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BUTTON_START) return true
        return super.dispatchKeyEvent(e)
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val SCREEN_LIBRARY = 0
        private const val SCREEN_SETTINGS = 1
        private const val SCREEN_UPDATE = 2
        private const val SCREEN_MUSIC = 3
        private const val REQ_FOLDER = 42
        private const val REQ_MUSIC = 43
        private const val REQ_FIRMWARE = 44
        // What the core's ResolveGameFile / loaders accept.
        private val IMAGE_EXTENSIONS = setOf("iso", "chd", "zip", "img", "mds", "mdf")
        private val LOOSE_EXTENSIONS = setOf("run", "cof", "nuon", "cd")
    }
}
