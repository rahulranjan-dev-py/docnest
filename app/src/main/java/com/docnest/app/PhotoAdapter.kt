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

/**
 * The page cards (tap to view, rotate or delete below), followed by one "Add pages" tile.
 * Reordering is by hold-and-drag, set up in ReviewActivity.
 */
class PhotoAdapter(
    private val batch: Batch,
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onView(i: Int)
        fun onRotate(i: Int)
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

    // Thumbnails are rotated as images, not as views, so a turned page still fits its page-shaped frame.
    private val thumbs = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(p: Photo, rot: Int = p.rot) = "${p.id}:$rot"

    /** Lets a new photo hand over the thumbnail it already has in memory. */
    fun putThumb(p: Photo, b: Bitmap) {
        thumbs.put(key(p, 0), b)
    }

    private fun thumb(p: Photo): Bitmap? {
        thumbs.get(key(p))?.let { return it }
        val upright = thumbs.get(key(p, 0))
            ?: BitmapFactory.decodeFile(batch.thumbFile(p).path)?.also { thumbs.put(key(p, 0), it) }
            ?: return null
        if (p.rot == 0) return upright
        val turn = Matrix().apply { postRotate(p.rot.toFloat()) }
        return Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, turn, true)
            .also { thumbs.put(key(p), it) }
    }

    /** A page card. */
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.thumbBox)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val tab: TextView = v.findViewById(R.id.tab)
        val blurBadge: View = v.findViewById(R.id.blurBadge)
        val rot: View = v.findViewById(R.id.rotBtn)
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
        click(h.del) { listener.onRemove(it) }
        return h
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        if (h !is Holder) {
            h.itemView.isEnabled = enabled
            h.itemView.alpha = if (enabled) 1f else 0.38f
            return
        }
        val p = batch.items[position]
        val n = position + 1
        h.thumb.setImageBitmap(thumb(p))
        h.tab.text = n.toString()
        h.blurBadge.visibility = if (p.blurry) View.VISIBLE else View.GONE
        h.box.contentDescription = if (p.blurry) "View page $n. It may be blurry." else "View page $n"
        h.rot.contentDescription = "Rotate page $n"
        h.del.contentDescription = "Delete page $n"
        for (v in listOf(h.box, h.rot, h.del)) v.isEnabled = enabled
        h.rot.alpha = if (enabled) 1f else 0.38f
        h.del.alpha = h.rot.alpha
    }

    private companion object {
        const val PAGE = 0
        const val ADD = 1
    }
}
