package com.retroportlab.nuondroid

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Tiny settings store backed by one properties file, re-read on every access.
 * The emulator runs in its own process (so each launch gets a clean core), and
 * SharedPreferences is not coherent across processes - a plain file is.
 */
class Prefs private constructor(private val file: File) {
    private fun load() = Properties().also { p -> runCatching { file.inputStream().use { input -> p.load(input) } } }

    @Synchronized
    private fun save(edit: (Properties) -> Unit) {
        val p = load()
        edit(p)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        tmp.renameTo(file)
    }

    fun getString(key: String, def: String): String = load().getProperty(key, def)
    fun putString(key: String, value: String) = save { it.setProperty(key, value) }

    fun getInt(key: String, def: Int) = getString(key, "").toIntOrNull() ?: def
    fun putInt(key: String, value: Int) = putString(key, value.toString())

    fun getLong(key: String, def: Long) = getString(key, "").toLongOrNull() ?: def
    fun putLong(key: String, value: Long) = putString(key, value.toString())

    fun getBool(key: String, def: Boolean) = when (getString(key, "")) { "true" -> true; "false" -> false; else -> def }
    fun putBool(key: String, value: Boolean) = putString(key, value.toString())

    var gamesDir: String
        get() = getString("gamesDir", "")
        set(v) = putString("gamesDir", v)

    /** Index into SCANLINE_NAMES. */
    var scanlines: Int
        get() = getInt("scanlines", 0).coerceIn(0, SCANLINE_NAMES.size - 1)
        set(v) = putInt("scanlines", v)

    /** Index into SCREEN_NAMES. (v0.01-v0.04 had a third value, "4:3 sharp", now Upscaling = Sharp pixels.) */
    var aspect: Int
        get() = getInt("aspect", 0).let { if (it == 2) 0 else it }.coerceIn(0, SCREEN_NAMES.size - 1)
        set(v) = putInt("aspect", v)

    /** Index into UPSCALE_NAMES. */
    var upscale: Int
        get() = getInt("upscale", if (getInt("aspect", 0) == 2) 1 else 0).coerceIn(0, UPSCALE_NAMES.size - 1)
        set(v) = putInt("upscale", v)

    /** FPS / speed overlay while playing. */
    var showFps: Boolean
        get() = getBool("showFps", false)
        set(v) = putBool("showFps", v)

    /**
     * Run straight-line NUON code as pre-decoded blocks (nuance.cfg DynamicCompiler)
     * instead of one packet at a time. Faster; switch off if a game misbehaves.
     */
    var blockMode: Boolean
        get() = getBool("blockMode", true)
        set(v) = putBool("blockMode", v)

    /** The core's own approximate CRT post-process shader (nuance.cfg UseCRTshader). */
    var coreCrt: Boolean
        get() = getBool("coreCrt", false)
        set(v) = putBool("coreCrt", v)

    /** Folder with music for VLM mode. */
    var musicDir: String
        get() = getString("musicDir", "")
        set(v) = putString("musicDir", v)

    /** VLM: effect to start with (the last one shown), and whether effects change by themselves. */
    var vlmEffect: Int
        get() = getInt("vlmEffect", 90)
        set(v) = putInt("vlmEffect", v)

    var vlmAuto: Boolean
        get() = getBool("vlmAuto", true)
        set(v) = putBool("vlmAuto", v)

    /** Music (VLM): true = R2 / L2 skip tracks (R1 / L1 toggle auto effects), false = the other way round. */
    var vlmTriggerSkip: Boolean
        get() = getBool("vlmTriggerSkip", true)
        set(v) = putBool("vlmTriggerSkip", v)

    /** Music (VLM): track and artist on screen. Index into [TRACK_NAMES]. */
    var trackName: Int
        get() = getInt("trackName", 1).coerceIn(0, TRACK_NAMES.size - 1)
        set(v) = putInt("trackName", v)

    /** While casting to a TV: true = picture on the TV only, handheld screen dark. */
    var tvOnly: Boolean
        get() = getBool("tvOnly", true)
        set(v) = putBool("tvOnly", v)

    companion object {
        val SCANLINE_NAMES = arrayOf("Off", "Light", "Strong")
        val TRACK_NAMES = arrayOf("Off", "When the track changes", "Always")
        val SCANLINE_PCT = intArrayOf(0, 30, 60)
        val SCREEN_NAMES = arrayOf("4:3", "Stretch")
        val UPSCALE_NAMES = arrayOf("Soft", "Sharp pixels", "Smooth HD")

        fun of(ctx: Context) = Prefs(File(ctx.filesDir, "settings.properties"))
    }
}
