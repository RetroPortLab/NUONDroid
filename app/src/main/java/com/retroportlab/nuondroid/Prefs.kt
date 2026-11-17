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

    /** Index into SCREEN_NAMES. */
    var aspect: Int
        get() = getInt("aspect", 0).coerceIn(0, SCREEN_NAMES.size - 1)
        set(v) = putInt("aspect", v)

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

    companion object {
        val SCANLINE_NAMES = arrayOf("Off", "Light", "Strong")
        val SCANLINE_PCT = intArrayOf(0, 30, 60)
        val SCREEN_NAMES = arrayOf("4:3", "Stretch", "4:3 sharp")

        fun of(ctx: Context) = Prefs(File(ctx.filesDir, "settings.properties"))
    }
}
