package com.retroportlab.nuondroid

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * The player firmware VLM mode runs: the playback engine ("pe") of a NUON DVD
 * player, which has the Virtual Light Machine built in. Not bundled - the user
 * installs it from the player's own firmware update.
 *
 * Supported: Samsung DVD-N501 update (nuon.zip / NUON/NUON.CD, BIOS 1.3.52, 2001).
 * The update carries the player's flash image verbatim; in it a NUONROM-DISK
 * holds `pe` as a COFF whose sections are raw-deflate compressed. We store it
 * unpacked so the core can load it like any COFF.
 */
object VlmFirmware {
    private const val PE_TEXT = 0x805A0000L
    private const val PE_TEXT_SIZE = 432888          // unpacked text of the supported build
    private val DISK_MAGIC = "NUONROM-DISK".toByteArray(Charsets.US_ASCII)

    fun file(ctx: Context) = File(ctx.filesDir, "vlm/pe.cof")
    fun installed(ctx: Context) = file(ctx).length() > PE_TEXT_SIZE

    /** Installs from a picked file. Returns null on success, or what went wrong. */
    fun install(ctx: Context, uri: Uri): String? {
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return "Couldn't read that file"
        val pe = extract(bytes).getOrElse { return it.message }
        val out = file(ctx)
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, "pe.tmp")
        tmp.writeBytes(pe)
        return if (tmp.renameTo(out)) null else "Couldn't save the firmware"
    }

    /** The unpacked pe COFF from a firmware update (zip or NUON.CD), or why not. */
    fun extract(bytes: ByteArray): Result<ByteArray> {
        val candidates = mutableListOf<ByteArray>()
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            runCatching {
                ZipInputStream(bytes.inputStream()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (!e.isDirectory) readAll(z).let { if (indexOf(it, DISK_MAGIC, 0) >= 0) candidates += it }
                    }
                }
            }
        } else candidates += bytes
        if (candidates.none { indexOf(it, DISK_MAGIC, 0) >= 0 })
            return Result.failure(Exception("No NUON player firmware found in that file. Pick the Samsung N501 update (nuon.zip or NUON.CD)."))
        for (c in candidates) {
            val pe = findPe(c) ?: continue
            unpack(c, pe)?.let { return Result.success(it) }
        }
        return Result.failure(Exception("That firmware isn't a supported version. VLM mode works with the Samsung DVD-N501 update (BIOS 1.3.52)."))
    }

    private fun readAll(s: InputStream): ByteArray {
        val o = ByteArrayOutputStream()
        val b = ByteArray(65536)
        while (true) { val n = s.read(b); if (n < 0) break; o.write(b, 0, n) }
        return o.toByteArray()
    }

    private fun indexOf(d: ByteArray, pat: ByteArray, from: Int): Int {
        outer@ for (i in from..d.size - pat.size) {
            for (j in pat.indices) if (d[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun u32(d: ByteArray, o: Int) = ByteBuffer.wrap(d, o, 4).int.toLong() and 0xFFFFFFFFL

    /** Offset of the pe COFF: block-aligned after a NUONROM-DISK header, first section "text" at 805A0000. */
    private fun findPe(d: ByteArray): Int? {
        var disk = indexOf(d, DISK_MAGIC, 0)
        while (disk >= 0) {
            for (k in 1 until 64) {
                val o = disk + k * 0x10000
                if (o + 64 > d.size) break
                if (u16(d, o) != 0x0120) continue
                val nsec = u16(d, o + 2); val opt = u16(d, o + 16)
                if (nsec !in 10..80) continue
                val s = o + 20 + opt
                if (s + 44 > d.size) continue
                val name = String(d, s, 8, Charsets.US_ASCII).trimEnd('\u0000')
                if (name == "text" && u32(d, s + 8) == PE_TEXT) return o
            }
            disk = indexOf(d, DISK_MAGIC, disk + 1)
        }
        return null
    }

    /** COFF with every deflated section inflated (the same as tools' unpackcoff.py). */
    private fun unpack(d: ByteArray, base: Int): ByteArray? {
        val nsec = u16(d, base + 2); val opt = u16(d, base + 16)
        val hdrEnd = 20 + opt + 44 * nsec
        val datas = ArrayList<ByteArray?>()
        val heads = ArrayList<ByteArray>()
        var textSize = -1
        for (i in 0 until nsec) {
            val h = base + 20 + opt + 44 * i
            if (h + 44 > d.size) return null
            heads += d.copyOfRange(h, h + 44)
            val size = u32(d, h + 16).toInt(); val ptr = u32(d, h + 20).toInt()
            var data: ByteArray? = null
            if (ptr != 0 && size > 0) {
                val start = base + ptr
                if (start + size > d.size) return null
                data = inflateRaw(d, start, minOf(size + 16, d.size - start)) ?: d.copyOfRange(start, start + size)
            }
            if (i == 0) textSize = data?.size ?: -1
            datas += data
        }
        if (textSize != PE_TEXT_SIZE) return null
        val out = ByteArrayOutputStream()
        val hdr = d.copyOfRange(base, base + 20 + opt)
        ByteBuffer.wrap(hdr, 8, 8).putInt(0).putInt(0)      // no symbols
        out.write(hdr)
        var pos = hdrEnd
        for (i in 0 until nsec) {
            val h = heads[i].copyOf()
            val data = datas[i]
            val bb = ByteBuffer.wrap(h)
            if (data != null) { bb.putInt(16, data.size); bb.putInt(20, pos); pos += data.size } else bb.putInt(20, 0)
            bb.putInt(24, 0); bb.putInt(28, 0); bb.putInt(32, 0); bb.putInt(36, 0)
            out.write(h)
        }
        for (data in datas) if (data != null) out.write(data)
        return out.toByteArray()
    }

    private fun inflateRaw(d: ByteArray, off: Int, len: Int): ByteArray? {
        val inf = Inflater(true)
        return try {
            inf.setInput(d, off, len)
            val o = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o.write(buf, 0, n)
            }
            if (inf.finished() && o.size() > 0) o.toByteArray() else null
        } catch (e: Exception) { null } finally { inf.end() }
    }
}
