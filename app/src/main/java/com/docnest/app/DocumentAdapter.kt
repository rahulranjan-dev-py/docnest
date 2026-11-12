package com.docnest.app

import android.content.Context
import android.content.res.ColorStateList
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

/**
 * Rows of "My documents": page-1 thumbnail, name, date / pages / size, star and ⋮.
 * While documents are being selected, the star and ⋮ give way to a tick and a tap selects.
 */
class DocumentAdapter(
    private val scope: CoroutineScope,
    private val context: Context,
    private val listener: Listener,
) : RecyclerView.Adapter<DocumentAdapter.Holder>() {

    interface Listener {
        fun onOpen(doc: Doc)
        fun onStar(doc: Doc, on: Boolean)
        fun onMenu(doc: Doc, anchor: View)

        /** A long press, or a tap while selecting: add [doc] to the selection or take it out. */
        fun onSelect(doc: Doc)
    }

    private var docs: List<Doc> = emptyList()
    private var favourites: Set<String> = emptySet()
    private var selected: Set<String> = emptySet()
    private val selecting get() = selected.isNotEmpty()
    private val thumbWidth = (56 * context.resources.displayMetrics.density).toInt()
    private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    // Page 1 and the page count, made once per version of a file (its size changes when it does).
    private val previews = object : LruCache<String, Library.Preview>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Library.Preview) = value.firstPage?.byteCount ?: 1
    }

    private fun previewKey(d: Doc) = "${d.key}:${d.size}"

    fun submit(list: List<Doc>, favs: Set<String>, picked: Set<String>) {
        docs = list
        favourites = favs
        selected = picked
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val row: MaterialCardView = v.findViewById(R.id.row)
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val star: MaterialButton = v.findViewById(R.id.star)
        val more: View = v.findViewById(R.id.more)
        val check: ImageView = v.findViewById(R.id.check)
        var loading: Job? = null
    }

    override fun getItemCount() = docs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val h = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_document, parent, false))
        fun doc() = docs.getOrNull(h.bindingAdapterPosition)
        h.row.setOnClickListener { doc()?.let { if (selecting) listener.onSelect(it) else listener.onOpen(it) } }
        h.row.setOnLongClickListener {
            doc()?.let(listener::onSelect)
            true
        }
        h.more.setOnClickListener { v -> doc()?.let { listener.onMenu(it, v) } }
        h.star.setOnClickListener { v ->
            val d = doc() ?: return@setOnClickListener
            val on = d.key !in favourites
            // A small pop when starring; turning it off needs no fanfare.
            if (on) {
                v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
                    .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
            }
            listener.onStar(d, on)
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val d = docs[position]
        val title = d.name.removeSuffix(".pdf")
        val fav = d.key in favourites
        val picked = d.key in selected
        h.name.text = title
        h.row.contentDescription = when {
            !selecting -> "Open $title"
            picked -> "$title, selected"
            else -> "$title, not selected"
        }
        h.star.visibility = if (selecting) View.GONE else View.VISIBLE
        h.more.visibility = h.star.visibility
        h.check.visibility = if (selecting) View.VISIBLE else View.GONE
        h.check.setImageResource(if (picked) R.drawable.ic_check_circle else R.drawable.ic_circle)
        h.check.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(h.check, if (picked) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOutline)
        )
        h.row.setCardBackgroundColor(
            MaterialColors.getColor(h.row, if (picked) MaterialR.attr.colorPrimaryContainer else MaterialR.attr.colorSurfaceContainerLowest)
        )
        h.row.strokeColor =
            MaterialColors.getColor(h.row, if (picked) AppCompatR.attr.colorPrimary else MaterialR.attr.colorOutlineVariant)
        h.more.contentDescription = "More options for $title"
        h.star.setIconResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star)
        h.star.iconTint = ColorStateList.valueOf(
            MaterialColors.getColor(h.star, if (fav) MaterialR.attr.colorSecondary else MaterialR.attr.colorOnSurfaceVariant)
        )
        h.star.contentDescription = if (fav) "Remove $title from favourites" else "Add $title to favourites"

        val cached = previews.get(previewKey(d))
        h.meta.text = meta(d, cached?.pages ?: 0)
        h.thumb.setImageBitmap(cached?.firstPage)
        h.loading?.cancel()
        if (cached == null) {
            h.loading = scope.launch {
                val made = withContext(Dispatchers.IO) { Library.preview(context, d, thumbWidth) }
                previews.put(previewKey(d), made)
                if (docs.getOrNull(h.bindingAdapterPosition)?.key == d.key) {
                    h.thumb.setImageBitmap(made.firstPage)
                    h.meta.text = meta(d, made.pages)
                }
            }
        }
    }

    private fun meta(d: Doc, pageCount: Int): String =
        listOfNotNull(dateFormat.format(Date(d.added)), pageCount.takeIf { it > 0 }?.let(::pages), fmtBytes(d.size))
            .joinToString(", ")
}
