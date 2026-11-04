package com.docnest.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Photo(val id: String, var rot: Int = 0)

/**
 * Today's photos, in PDF order. They stay on the phone until the batch is cleared.
 * One shared instance; change [items] on the main thread only, then call [save].
 */
class Batch private constructor(context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }
    private val index = File(context.filesDir, "batch.json")
    val items = mutableListOf<Photo>()

    fun photoFile(p: Photo) = File(dir, "${p.id}.jpg")
    fun thumbFile(p: Photo) = File(dir, "${p.id}_t.jpg")

    fun newPhoto() = Photo(System.currentTimeMillis().toString(36) + (1000..9999).random())

    fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("rot", it.rot)) }
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
    }

    private fun load() {
        runCatching {
            val arr = JSONArray(index.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Photo(o.getString("id"), o.optInt("rot", 0))
                if (photoFile(p).exists() && thumbFile(p).exists()) items.add(p)
            }
        }
        // Remove leftovers, such as a photo removed just before the app was closed.
        val keep = items.flatMap { listOf(photoFile(it).name, thumbFile(it).name) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    companion object {
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
