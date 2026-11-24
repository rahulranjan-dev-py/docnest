package com.docnest.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One page of the scan. [rot]: turned clockwise by this many degrees. [blurry]: the sharpness
 * check thinks it may be out of focus. [crop]: the part of the photo to keep (see [Crop]), or
 * null for all of it.
 */
class Photo(val id: String, var rot: Int = 0, var blurry: Boolean = false, var crop: FloatArray? = null) {
    /** What the page looks like: which photo, which way up, how cropped. Changes when any of them does. */
    fun shapeKey(): String = "$id:$rot" + (crop?.let { ":" + Crop.tag(it) } ?: "")
}

/**
 * The scan in progress: its photos, in PDF order. They stay on the phone until the scan is saved
 * as a PDF and left, or discarded.
 * One shared instance; change [items] on the main thread only, then call [save].
 */
class Batch private constructor(context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val index = File(context.filesDir, "batch.json")
    private val prefs = context.getSharedPreferences("batch", Context.MODE_PRIVATE)
    val items = mutableListOf<Photo>()

    fun photoFile(p: Photo) = File(dir, "${p.id}.jpg")
    fun thumbFile(p: Photo) = File(dir, "${p.id}_t.jpg")

    fun newPhoto() = Photo(System.currentTimeMillis().toString(36) + (1000..9999).random())

    fun save() {
        val arr = JSONArray()
        items.forEach { p ->
            val o = JSONObject().put("id", p.id).put("rot", p.rot).put("blurry", p.blurry)
            p.crop?.let { c -> o.put("crop", JSONArray().apply { c.forEach { put(it.toDouble()) } }) }
            arr.put(o)
        }
        val tmp = File(index.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }

    /** Puts [new] where [old] was (a page taken again), and deletes [old]'s files. */
    fun replace(old: Photo, new: Photo) {
        val i = items.indexOfFirst { it.id == old.id }
        if (i >= 0) items[i] = new else items.add(new)
        save()
        deleteFiles(old)
    }

    /** Deletes the files of a photo that is no longer in the batch. */
    fun deleteFiles(p: Photo) {
        photoFile(p).delete()
        thumbFile(p).delete()
    }

    fun clear() {
        items.clear()
        dir.listFiles()?.forEach { it.delete() }
        save()
        prefs.edit { remove(KEY_SAVED) }
    }

    /** Which pages, which way up and how cropped: what a PDF is made from. */
    fun pagesKey(): String = items.joinToString(",") { it.shapeKey() }

    /** Remembers that the pages in [key] are now in a saved PDF. Safe from any thread. */
    fun markSaved(key: String) = prefs.edit { putString(KEY_SAVED, key) }

    /**
     * A scan whose pages haven't changed since they were saved as a PDF is finished: this clears
     * it and says so. Home calls it, so the pages are still there for another go (a different
     * look, say) for as long as the scan's own screen stays open.
     */
    fun clearIfSaved(): Boolean {
        if (!isSaved()) return false
        clear()
        return true
    }

    /** True when these pages, exactly as they are now, are already in a saved PDF. */
    fun isSaved(): Boolean = items.isNotEmpty() && prefs.getString(KEY_SAVED, null) == pagesKey()

    private fun load() {
        runCatching {
            val arr = JSONArray(index.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val crop = o.optJSONArray("crop")?.let { a -> Crop.parse((0 until a.length()).map { a.optDouble(it) }) }
                val p = Photo(o.getString("id"), o.optInt("rot", 0), o.optBoolean("blurry", false), crop)
                if (photoFile(p).exists() && thumbFile(p).exists()) items.add(p)
            }
        }
        // Remove leftovers, such as a photo removed just before the app was closed.
        val keep = items.flatMap { listOf(photoFile(it).name, thumbFile(it).name) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    companion object {
        private const val KEY_SAVED = "savedPages"

        @Volatile
        private var instance: Batch? = null

        fun get(context: Context): Batch = instance ?: synchronized(this) {
            instance ?: Batch(context.applicationContext).also {
                it.load()
                instance = it
            }
        }
    }
}
