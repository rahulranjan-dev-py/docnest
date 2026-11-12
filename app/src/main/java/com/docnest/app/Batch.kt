package com.docnest.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One page of the batch. [blurry]: the sharpness check thinks it may be out of focus. */
class Photo(val id: String, var rot: Int = 0, var blurry: Boolean = false)

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
        items.forEach { arr.put(JSONObject().put("id", it.id).put("rot", it.rot).put("blurry", it.blurry)) }
        val tmp = File(index.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
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

    /** Which pages, and which way up each is: what a PDF is made from. */
    fun pagesKey(): String = items.joinToString(",") { "${it.id}:${it.rot}" }

    /** Remembers that the pages in [key] are now in a saved PDF. Safe from any thread. */
    fun markSaved(key: String) = prefs.edit { putString(KEY_SAVED, key) }

    /**
     * A scan whose pages haven't changed since they were saved as a PDF is finished: this clears
     * it and says so. Home calls it, so the pages are still there for another go (a different
     * look, say) for as long as the scan's own screen stays open.
     */
    fun clearIfSaved(): Boolean {
        if (items.isEmpty() || prefs.getString(KEY_SAVED, null) != pagesKey()) return false
        clear()
        return true
    }

    private fun load() {
        runCatching {
            val arr = JSONArray(index.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Photo(o.getString("id"), o.optInt("rot", 0), o.optBoolean("blurry", false))
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
