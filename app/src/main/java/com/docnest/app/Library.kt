package com.docnest.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File

/** A PDF made by DocNest. [key] stays the same through a rename, so favourites follow the file. */
class Doc(val key: String, val uri: Uri, val name: String, val size: Long, val added: Long)

/**
 * "My documents": the PDFs DocNest has saved. On Android 10 and newer they are the app's own files
 * in Downloads, read straight from MediaStore, so renaming or deleting here changes the real file
 * and PDFs made before this screen existed show up too. Android 8 and 9 have no shared Downloads
 * for apps, so there they live in DocNest's own Documents folder.
 */
object Library {
    private const val PREFS = "library"
    private const val KEY_FAVOURITES = "favourites"
    private const val PDF = "application/pdf"

    /** Something the user can act on; the message is shown as is. */
    class Failure(message: String) : Exception(message)

    private val usesMediaStore get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun folder(context: Context) =
        (context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(context.filesDir, "Documents"))
            .apply { mkdirs() }

    // ---------- listing ----------

    /** Newest first. Call off the main thread. */
    fun list(context: Context): List<Doc> = if (usesMediaStore) listDownloads(context) else listFolder(context)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listDownloads(context: Context): List<Doc> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
        val docs = mutableListOf<Doc>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf(context.packageName, PDF), "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                docs += Doc(
                    key = "ms:$id",
                    uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    name = cur.getString(1) ?: "Untitled.pdf",
                    size = cur.getLong(2),
                    added = cur.getLong(3) * 1000,
                )
            }
        }
        return docs
    }

    private fun listFolder(context: Context): List<Doc> =
        (folder(context).listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { fileDoc(context, it) }

    private fun fileDoc(context: Context, f: File) = Doc(
        key = "file:${f.name}",
        uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f),
        name = f.name,
        size = f.length(),
        added = f.lastModified(),
    )

    // ---------- saving a new PDF ----------

    /** Files a freshly made PDF into the library and returns it, or null if it couldn't be saved. */
    fun save(context: Context, pdf: File): Doc? = if (usesMediaStore) saveToDownloads(context, pdf) else saveToFolder(context, pdf)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(context: Context, pdf: File): Doc? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, pdf.name)
            put(MediaStore.MediaColumns.MIME_TYPE, PDF)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> pdf.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // Android may have added " (1)" to avoid a clash, so read back the name it kept.
            val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: pdf.name
            Doc("ms:${ContentUris.parseId(uri)}", uri, name, pdf.length(), System.currentTimeMillis())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveToFolder(context: Context, pdf: File): Doc? {
        val target = freeName(folder(context), pdf.name)
        return runCatching { pdf.copyTo(target) }.getOrNull()?.let { fileDoc(context, it) }
    }

    /** "Scan.pdf", or "Scan (1).pdf", "Scan (2).pdf"… if that name is taken. */
    private fun freeName(dir: File, name: String): File {
        val base = name.removeSuffix(".pdf")
        var f = File(dir, name)
        var n = 1
        while (f.exists()) f = File(dir, "$base (${n++}).pdf")
        return f
    }

    // ---------- rename, delete, favourite ----------

    private val badChars = Regex("[\\\\/:*?\"<>|]")

    /** Why [name] can't be used, or null when it's fine. [name] is without ".pdf". */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Enter a name"
        badChars.containsMatchIn(name) -> "Names can't contain \\ / : * ? \" < > |"
        name.length > 100 -> "Use 100 characters or fewer"
        else -> null
    }

    /** Renames [doc] to [newName] (without ".pdf") and returns it with its new name. Off the main thread. */
    fun rename(context: Context, doc: Doc, newName: String): Doc {
        val fileName = newName.trim().removeSuffix(".pdf") + ".pdf"
        if (usesMediaStore) {
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) }
                if (context.contentResolver.update(doc.uri, values, null, null) == 0) throw Failure(CANT_CHANGE)
            } catch (e: SecurityException) {
                throw Failure(CANT_CHANGE)
            }
            val kept = context.contentResolver.query(doc.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: fileName
            return Doc(doc.key, doc.uri, kept, doc.size, doc.added)
        }
        val from = File(folder(context), doc.name)
        val to = File(folder(context), fileName)
        if (to.exists() && !to.equals(from)) throw Failure("A document called $fileName already exists")
        if (!from.renameTo(to)) throw Failure(CANT_CHANGE)
        val renamed = fileDoc(context, to)
        if (isFavourite(context, doc.key)) {
            setFavourite(context, doc.key, false)
            setFavourite(context, renamed.key, true)
        }
        return renamed
    }

    /** Deletes [doc] from the phone. Off the main thread. */
    fun delete(context: Context, doc: Doc) {
        val gone = if (usesMediaStore) {
            try {
                context.contentResolver.delete(doc.uri, null, null) > 0
            } catch (e: SecurityException) {
                false
            }
        } else {
            File(folder(context), doc.name).delete()
        }
        if (!gone) throw Failure(CANT_CHANGE)
        setFavourite(context, doc.key, false)
    }

    private const val CANT_CHANGE = "DocNest can't change this file any more. Rename or delete it in your Files app."

    fun favourites(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_FAVOURITES, emptySet()) ?: emptySet()

    fun isFavourite(context: Context, key: String) = key in favourites(context)

    fun setFavourite(context: Context, key: String, on: Boolean) {
        val now = favourites(context).toMutableSet()
        if (on) now += key else now -= key
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_FAVOURITES, now) }
    }

    // ---------- opening, sharing, previews ----------

    fun openIntent(doc: Doc): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(doc.uri, PDF)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(doc: Doc): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType(PDF)
            .putExtra(Intent.EXTRA_STREAM, doc.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Share PDF",
    )

    class Preview(val firstPage: Bitmap?, val pages: Int)

    /** Page 1 at [width] px wide, and the page count. Off the main thread. */
    fun preview(context: Context, doc: Doc, width: Int): Preview = try {
        context.contentResolver.openFileDescriptor(doc.uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Preview(bmp, pdf.pageCount)
                }
            }
        }
    } catch (e: Exception) {
        Preview(null, 0)
    }
}
