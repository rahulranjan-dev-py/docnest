package com.docnest.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** The numbered photo cards: tap to view, then rotate, move earlier, move later, remove. */
class PhotoAdapter(
    private val batch: Batch,
    private val listener: Listener,
) : RecyclerView.Adapter<PhotoAdapter.Holder>() {

    interface Listener {
        fun onView(i: Int)
        fun onRotate(i: Int)
        fun onMove(i: Int, by: Int)
        fun onRemove(i: Int)
    }

    /** False while a PDF is being made or photos are being added. */
    var enabled = true
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    private val thumbs = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Lets the camera hand over a thumbnail it already has in memory. */
    fun putThumb(p: Photo, b: Bitmap) {
        thumbs.put(p.id, b)
    }

    private fun thumb(p: Photo): Bitmap? =
        thumbs.get(p.id) ?: BitmapFactory.decodeFile(batch.thumbFile(p).path)?.also { thumbs.put(p.id, it) }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.thumbBox)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val rot: ImageButton = v.findViewById(R.id.rotBtn)
        val up: ImageButton = v.findViewById(R.id.upBtn)
        val down: ImageButton = v.findViewById(R.id.downBtn)
        val del: ImageButton = v.findViewById(R.id.delBtn)
    }

    override fun getItemCount() = batch.items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val h = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))
        fun click(v: View, action: (Int) -> Unit) = v.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (enabled && i != RecyclerView.NO_POSITION) action(i)
        }
        click(h.box) { listener.onView(it) }
        click(h.rot) { listener.onRotate(it) }
        click(h.up) { listener.onMove(it, -1) }
        click(h.down) { listener.onMove(it, 1) }
        click(h.del) { listener.onRemove(it) }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val p = batch.items[position]
        val n = position + 1
        h.thumb.setImageBitmap(thumb(p))
        h.thumb.rotation = p.rot.toFloat()
        h.badge.text = n.toString()
        h.box.contentDescription = "View photo $n"
        setOn(h.box, enabled)
        setOn(h.rot, enabled)
        setOn(h.up, enabled && position > 0)
        setOn(h.down, enabled && position < batch.items.size - 1)
        setOn(h.del, enabled)
    }

    private fun setOn(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ImageButton) v.alpha = if (on) 1f else 0.25f
    }
}
