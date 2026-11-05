package com.docnest.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the public GitHub Releases of DocNest.
 * Each release is tagged v1.<build number>, and that build number is the app's versionCode.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/rahulranjan-dev-py/docnest/releases/latest"
    private const val TIMEOUT = 15_000

    class Release(val version: Int, val name: String, val apkUrl: String, val size: Long)

    fun installedVersion(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT
        readTimeout = TIMEOUT
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
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    val name = json.optString("name").ifBlank { "DocNest $tag" }
                    return Release(version, name, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the release's APK, reporting progress from 0 to 1. Call off the main thread. */
    fun download(context: Context, r: Release, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()
        val apk = File(dir, "DocNest-${r.version}.apk")
        val conn = open(r.apkUrl)
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "Download failed: HTTP ${conn.responseCode}" }
            val total = if (r.size > 0) r.size else conn.contentLengthLong
            conn.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
            check(r.size <= 0 || apk.length() == r.size) { "Download incomplete" }
            return apk
        } finally {
            conn.disconnect()
        }
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
