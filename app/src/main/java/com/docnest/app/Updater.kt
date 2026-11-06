package com.docnest.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from DocNest's public GitHub Releases. Releases are tagged v1.<number>, and that
 * number is the app's versionCode; copies of DocNest from 1.4 and 1.5 compare it the same way.
 * Each release carries the APK and a SHA256SUMS.txt that the download is checked against.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/rahulranjan-dev-py/docnest/releases/latest"
    private const val TIMEOUT = 15_000

    class Release(
        val version: Int,
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String,
        val size: Long,
        val checksumsUrl: String?,
    )

    /** A problem the user can act on; the message is shown as is. */
    class Failure(message: String) : Exception(message)

    fun installedVersion(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT
        readTimeout = TIMEOUT
        useCaches = false
        setRequestProperty("User-Agent", "DocNest")
    }

    /** The newest release with an APK attached, or null when there is none. Call off the main thread. */
    fun latest(): Release? {
        val conn = open(LATEST)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val version = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            val files = (0 until assets.length()).map { assets.getJSONObject(it) }
            // Releases carry the APK twice (versioned, and DocNest-latest.apk for a fixed link); prefer the versioned one.
            val apks = files.filter { it.getString("name").endsWith(".apk") }
            val apk = apks.firstOrNull { !it.getString("name").endsWith("-latest.apk") } ?: apks.firstOrNull() ?: return null
            val sums = files.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }
            return Release(
                version = version,
                tag = tag,
                name = json.optString("name").ifBlank { "DocNest $tag" },
                notes = json.optString("body").trim(),
                pageUrl = json.optString("html_url"),
                apkUrl = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
                checksumsUrl = sums?.getString("browser_download_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads the release's APK into the cache, reporting bytes done and total, then checks it
     * against the release's SHA256SUMS.txt. A file that doesn't match is deleted, never installed.
     * Cancelling the calling coroutine stops the transfer. Call off the main thread.
     */
    suspend fun download(
        context: Context,
        r: Release,
        progress: (done: Long, total: Long) -> Unit,
        verifying: () -> Unit,
    ): File {
        val dir = File(context.cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()
        val apk = File(dir, "DocNest-${r.version}.apk")
        val partial = File(dir, apk.name + ".part")
        try {
            val conn = open(r.apkUrl)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Failure("GitHub replied ${conn.responseCode}. Try again later.")
                }
                val total = if (r.size > 0) r.size else conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    partial.outputStream().buffered(1 shl 16).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done, total)
                        }
                    }
                }
                if (total > 0 && done != total) throw Failure("The download stopped early. Try again.")
            } finally {
                conn.disconnect()
            }

            // When the release publishes checksums, only a matching file may be installed: an
            // unreadable checksum file is a failure to retry, never a quiet "unverified".
            val expected = r.checksumsUrl?.let { url ->
                expectedHash(url, r.apkUrl.substringAfterLast('/'))
                    ?: throw Failure("Couldn't read the release's checksum. Try again.")
            }
            if (expected != null) {
                verifying()
                if (!sha256(partial).equals(expected, ignoreCase = true)) {
                    throw Failure("The download was damaged, so it wasn't installed. Try again.")
                }
            }
            if (!partial.renameTo(apk)) throw Failure("Couldn't save the download. Free up some space and try again.")
            return apk
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
    }

    /** The hash listed for [fileName] in a `sha256sum`-style file, or null. */
    private fun expectedHash(url: String, fileName: String): String? {
        val conn = open(url)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            return conn.inputStream.bufferedReader().useLines { lines ->
                lines.map { it.trim().split(Regex("\\s+"), limit = 2) }
                    .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
                    ?.get(0)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Android asks once per app before it may install updates. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context) =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
