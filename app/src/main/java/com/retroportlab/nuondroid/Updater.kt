package com.retroportlab.nuondroid

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub releases for a newer NUONDroid and installs it
 * (same scheme as JagDroid).
 *
 * Releases are tagged "v0.01" and so on, with the APK attached as an asset.
 * The download goes to the app's cache and is handed to the system installer,
 * which asks the player to confirm. Android only accepts the update if it is
 * signed with the same key as the installed app.
 */
object Updater {
    const val REPO = "RetroPortLab/NUONDroid"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"

    /** A published release that has an APK attached. */
    class Release(
        val tag: String, val name: String, val notes: String,
        val apkUrl: String, val apiUrl: String, val pageUrl: String, val apkSize: Long,
    )

    /** Return false to cancel. */
    fun interface Progress { fun update(done: Long, total: Long): Boolean }

    /** Version numbers from a name like "0.01-experimental" or a tag like "v0.01": [0, 1]. */
    fun versionParts(v: String?): IntArray {
        if (v == null) return IntArray(0)
        var s = v.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val end = s.indexOfFirst { !(it.isDigit() || it == '.') }.let { if (it < 0) s.length else it }
        return s.substring(0, end).split('.').filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
    }

    /** True if version a is newer than version b. */
    fun newer(a: String?, b: String?): Boolean {
        val x = versionParts(a)
        val y = versionParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 60000
        setRequestProperty("User-Agent", "NUONDroid-updater")
        instanceFollowRedirects = true   // asset downloads redirect to GitHub's storage host
    }

    /** The newest release with an APK, or null if there are none. Call from a background thread. */
    fun latest(): Release? {
        val c = open(API)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw IOException("No releases have been published yet")
                403 -> throw IOException("GitHub is limiting requests right now; try again later")
                else -> throw IOException("GitHub replied $code")
            }
            val buf = ByteArrayOutputStream()
            c.inputStream.use { it.copyTo(buf) }
            val list = JSONArray(buf.toString("UTF-8"))
            var best: Release? = null
            for (i in 0 until list.length()) {
                val r = list.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (k in 0 until assets.length()) {
                    val a = assets.getJSONObject(k)
                    if (!a.optString("name").lowercase().endsWith(".apk")) continue
                    val tag = r.optString("tag_name")
                    val rel = Release(
                        tag = tag,
                        name = r.optString("name", tag),
                        notes = r.optString("body", ""),
                        apkUrl = a.optString("browser_download_url"),
                        apiUrl = a.optString("url"),          // the API route, a different path to the same file
                        pageUrl = r.optString("html_url", RELEASES_PAGE),
                        apkSize = a.optLong("size"),
                    )
                    if (best == null || newer(rel.tag, best.tag)) best = rel
                    break
                }
            }
            return best
        } catch (e: JSONException) {
            throw IOException("Couldn't read GitHub's reply")
        } finally {
            c.disconnect()
        }
    }

    /** Downloads the release's APK into the cache. Returns null if cancelled. */
    fun download(ctx: Context, r: Release, p: Progress?): File? {
        var last: IOException? = null
        for (attempt in 1..4) {
            try {
                return downloadOnce(ctx, r, p, attempt == 2 && r.apiUrl.isNotEmpty())
            } catch (e: IOException) {
                last = e
                try { Thread.sleep(3000L * attempt) } catch (ie: InterruptedException) { break }
            }
        }
        val why = last?.message ?: "no details"
        throw IOException("Download failed: ${last?.javaClass?.simpleName} - $why (tried 4 times)")
    }

    private fun downloadOnce(ctx: Context, r: Release, p: Progress?, viaApi: Boolean): File? {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "NUONDroid-${r.tag}.apk")
        val c = open(if (viaApi) r.apiUrl else r.apkUrl)
        if (viaApi) c.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (c.responseCode != 200) throw IOException("Download failed (${c.responseCode})")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.apkSize
            var done = 0L
            c.inputStream.use { input ->
                FileOutputStream(out).use { o ->
                    val b = ByteArray(65536)
                    while (true) {
                        val n = input.read(b)
                        if (n <= 0) break
                        o.write(b, 0, n)
                        done += n
                        if (p != null && !p.update(done, total)) { o.close(); out.delete(); return null }
                    }
                }
            }
            if (total > 0 && done != total) { out.delete(); throw IOException("Download was cut short") }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** Whether Android lets NUONDroid install apps; if not, the player has to allow it once in Settings. */
    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
    }

    /** Hands the APK to the system installer; it shows its own confirmation, then restarts into the new version. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            FileInputStream(apk).use { input ->
                s.openWrite("nuondroid.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 65536)
                    s.fsync(out)
                }
            }
            val result = Intent(ctx, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, result, flags)
            s.commit(pending.intentSender)
        }
    }
}
