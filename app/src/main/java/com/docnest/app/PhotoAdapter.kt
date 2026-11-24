package com.docnest.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The page cards (tap to view; rotate, crop or delete below), followed by one "Add pages" tile.
 * Each card shows its page as it will look in the PDF: cropped, turned and in the chosen [look].
 * Reordering is by hold-and-drag, set up in ReviewActivity.
 */
class PhotoAdapter(
    private val batch: Batch,
    private val scope: CoroutineScope,
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onView(i: Int)
        fun onRotate(i: Int)
        fun onCrop(i: Int)
        fun onRemove(i: Int)

        /** The "Add pages" tile; [anchor] is where its menu opens. */
        fun onAdd(anchor: View)
    }

    /** False while a PDF is being made or photos are being added. */
    var enabled = true
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    /** How the pages will look in the PDF. Other looks than Original are worked out in the background. */
    var look = ColourMode.COLOUR
        set(v) {
            if (field == v) return
            field = v
            // Pictures still being made for the look that was showing are no longer wanted.
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            notifyItemRangeChanged(0, batch.items.size)
        }

    // Small pictures, as photographed and in each look. Pictures are turned as images, not as
    // views, so a turned page still fits its page-shaped frame. At most a sixth of the memory
    // the app may use, so phones with little memory keep fewer.
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(48L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val jobs = HashMap<String, Job>()

    // Two pages at a time: quick enough, and leaves the phone responsive while scrolling.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(2)

    private fun uprightKey(p: Photo) = "plain|${p.id}|${Crop.tag(p.crop)}"
    private fun plainKey(p: Photo) = "plain|${p.shapeKey()}"
    private fun lookKey(p: Photo, mode: ColourMode) = "look|${mode.name}|${p.shapeKey()}"

    /** Lets a new photo hand over the small picture it already has in memory. */
    fun putThumb(p: Photo, b: Bitmap) {
        cache.put(uprightKey(p), b)
    }

    /** The best picture of [p] there is right now, for the viewer to show while it makes a large one. */
    fun cached(p: Photo): Bitmap? = cache.get(lookKey(p, look)) ?: plainThumb(p)

    /** The page's small picture as photographed (cropped and turned), or null if its file is gone. */
    private fun plainThumb(p: Photo): Bitmap? {
        cache.get(plainKey(p))?.let { return it }
        val upright = cache.get(uprightKey(p))
            ?: BitmapFactory.decodeFile(batch.thumbFile(p).path)?.also { cache.put(uprightKey(p), it) }
            ?: return null
        if (p.rot == 0) return upright
        val turn = Matrix().apply { postRotate(p.rot.toFloat()) }
        return Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, turn, true)
            .also { cache.put(plainKey(p), it) }
    }

    /** The page in the current look, or null while it is being made (the card is updated when it is). */
    private fun lookThumb(p: Photo): Bitmap? {
        val mode = look
        val key = lookKey(p, mode)
        cache.get(key)?.let { return it }
        if (key !in jobs) {
            val photo = batch.photoFile(p)
            val rot = p.rot
            val crop = p.crop?.copyOf()
            jobs[key] = scope.launch {
                val made = withContext(worker) {
                    runCatching { Images.preview(photo, LOOK_WORK_EDGE, LOOK_SHOW_EDGE, rot, mode, crop) }.getOrNull()
                }
                jobs.remove(key)
                if (made != null) {
                    cache.put(key, made)
                    // Only the picture changes: the card stays the same view, even mid-drag.
                    val i = batch.items.indexOfFirst { lookKey(it, look) == key }
                    if (i >= 0) notifyItemChanged(i, PICTURE)
                }
            }
        }
        return null
    }

    /** A page card. */
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.thumbBox)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val tab: TextView = v.findViewById(R.id.tab)
        val blurBadge: View = v.findViewById(R.id.blurBadge)
        val rot: View = v.findViewById(R.id.rotBtn)
        val crop: View = v.findViewById(R.id.cropBtn)
        val del: View = v.findViewById(R.id.delBtn)
    }

    /** The "Add pages" tile. */
    class AddHolder(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = batch.items.size + 1

    override fun getItemViewType(position: Int) = if (position < batch.items.size) PAGE else ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == ADD) {
            val h = AddHolder(inflater.inflate(R.layout.item_add_pages, parent, false))
            h.itemView.setOnClickListener { if (enabled) listener.onAdd(it) }
            return h
        }
        val h = Holder(inflater.inflate(R.layout.item_photo, parent, false))
        fun click(v: View, action: (Int) -> Unit) = v.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (enabled && i != RecyclerView.NO_POSITION && i < batch.items.size) action(i)
        }
        click(h.box) { listener.onView(it) }
        click(h.rot) { listener.onRotate(it) }
        click(h.crop) { listener.onCrop(it) }
        click(h.del) { listener.onRemove(it) }
        return h
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (h is Holder && payloads.isNotEmpty() && payloads.all { it == PICTURE }) {
            h.thumb.setImageBitmap(picture(batch.items[position]))
        } else {
            onBindViewHolder(h, position)
        }
    }

    // Until the page is ready in the chosen look, it shows as photographed.
    private fun picture(p: Photo): Bitmap? =
        if (look == ColourMode.COLOUR) plainThumb(p) else lookThumb(p) ?: plainThumb(p)

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (h !is Holder) {
            h.itemView.isEnabled = enabled
            h.itemView.alpha = if (enabled) 1f else 0.38f
            return
        }
        val p = batch.items[position]
        val n = position + 1
        h.thumb.setImageBitmap(picture(p))
        h.tab.text = n.toString()
        h.blurBadge.visibility = if (p.blurry) View.VISIBLE else View.GONE
        h.box.contentDescription = if (p.blurry) "View page $n. It may be blurry." else "View page $n"
        h.rot.contentDescription = "Rotate page $n"
        h.crop.contentDescription = "Crop page $n"
        h.del.contentDescription = "Delete page $n"
        for (v in listOf(h.box, h.rot, h.crop, h.del)) v.isEnabled = enabled
        for (v in listOf(h.rot, h.crop, h.del)) v.alpha = if (enabled) 1f else 0.38f
    }

    private companion object {
        const val PAGE = 0
        const val ADD = 1

        // A change that is only the page's picture, now ready in the chosen look.
        const val PICTURE = "picture"

        // Looks are worked out at half the stored size (quick to read, close to Small quality)
        // and shown a little sharper than the cards need on most phones.
        const val LOOK_WORK_EDGE = Images.STORE_EDGE / 2
        const val LOOK_SHOW_EDGE = 540
    }
}
