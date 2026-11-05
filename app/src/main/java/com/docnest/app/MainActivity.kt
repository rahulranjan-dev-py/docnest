package com.docnest.app

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity(), PhotoAdapter.Listener {

    /** The last PDF made. Tapping the button again shares it without rebuilding, until something changes. */
    private class Built(val file: File, val key: String)

    private lateinit var batch: Batch
    private lateinit var settings: Settings
    private lateinit var adapter: PhotoAdapter
    private lateinit var root: View
    private lateinit var dock: View
    private lateinit var listHeader: View
    private lateinit var countText: TextView
    private lateinit var emptyView: View
    private lateinit var statusRow: View
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var makeBtn: MaterialButton
    private lateinit var cameraBtn: MaterialButton
    private lateinit var galleryBtn: MaterialButton
    private lateinit var settingsBtn: View
    private lateinit var clearBtn: View
    private var busy = false
    private var built: Built? = null

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) addFromGallery(uris)
    }

    private val openCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        refresh()
        val added = r.data?.getIntExtra(CameraActivity.EXTRA_ADDED, 0) ?: 0
        if (added > 0) note("${pages(added)} added")
    }

    // Hold a page to pick it up, drag it to its new place, let go.
    private val dragger = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
    ) {
        override fun isLongPressDragEnabled() = !busy

        override fun onMove(
            rv: RecyclerView,
            from: RecyclerView.ViewHolder,
            to: RecyclerView.ViewHolder,
        ): Boolean {
            val i = from.bindingAdapterPosition
            val j = to.bindingAdapterPosition
            if (i == RecyclerView.NO_POSITION || j == RecyclerView.NO_POSITION) return false
            batch.items.add(j, batch.items.removeAt(i))
            adapter.notifyItemMoved(i, j)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            batch.save()
            rv.post { refresh() } // renumbers the tabs after the drop
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        batch = Batch.get(this)
        settings = Settings(this)

        root = findViewById(R.id.root)
        dock = findViewById(R.id.dock)
        listHeader = findViewById(R.id.listHeader)
        countText = findViewById(R.id.countText)
        emptyView = findViewById(R.id.empty)
        statusRow = findViewById(R.id.statusRow)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        makeBtn = findViewById(R.id.makeBtn)
        cameraBtn = findViewById(R.id.cameraBtn)
        galleryBtn = findViewById(R.id.galleryBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        clearBtn = findViewById(R.id.clearBtn)

        adapter = PhotoAdapter(batch, this)
        val grid = findViewById<RecyclerView>(R.id.grid)
        val dm = resources.displayMetrics
        grid.layoutManager = GridLayoutManager(this, max(2, (dm.widthPixels / dm.density / 160).toInt()))
        grid.adapter = adapter
        dragger.attachToRecyclerView(grid)

        cameraBtn.setOnClickListener { openCamera.launch(Intent(this, CameraActivity::class.java)) }
        galleryBtn.setOnClickListener { pickPhotos.launch("image/*") }
        settingsBtn.setOnClickListener { showSettings() }
        clearBtn.setOnClickListener { confirmClear() }
        makeBtn.setOnClickListener { onMake() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- screen state ----------

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = batch.items.size
        countText.text = pages(n)
        listHeader.isVisible = n > 0
        emptyView.isVisible = n == 0
        makeBtn.isVisible = n > 0
        makeBtn.text = if (built?.key == stateKey()) "Share PDF" else "Make PDF"
        // Scan is the main action until there are pages; then Make PDF takes over and Scan steps back.
        styleAsMain(cameraBtn, n == 0)
        updateEnabled()
    }

    private fun styleAsMain(b: MaterialButton, main: Boolean) {
        val bg = MaterialColors.getColor(b, if (main) AppCompatR.attr.colorPrimary else MaterialR.attr.colorSecondaryContainer)
        val fg = MaterialColors.getColor(b, if (main) MaterialR.attr.colorOnPrimary else MaterialR.attr.colorOnSecondaryContainer)
        val onSurface = MaterialColors.getColor(b, MaterialR.attr.colorOnSurface)
        fun states(on: Int, off: Int) = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(off, on),
        )
        b.backgroundTintList = states(bg, ColorUtils.setAlphaComponent(onSurface, 31))
        val text = states(fg, ColorUtils.setAlphaComponent(onSurface, 97))
        b.setTextColor(text)
        b.iconTint = text
    }

    private fun updateEnabled() {
        cameraBtn.isEnabled = !busy
        galleryBtn.isEnabled = !busy
        makeBtn.isEnabled = !busy && batch.items.isNotEmpty()
        settingsBtn.isEnabled = !busy
        clearBtn.isEnabled = !busy
    }

    private fun setBusy(on: Boolean) {
        busy = on
        adapter.enabled = !on
        statusRow.isVisible = on
        updateEnabled()
    }

    private fun setProgress(text: String, fraction: Float) {
        statusText.text = text
        progress.setProgressCompat((fraction * 1000).toInt(), true)
    }

    /** A short note above the dock, gone after a few seconds. */
    private fun note(text: String, long: Boolean = false) {
        Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
            .setAnchorView(dock)
            .show()
    }

    // ---------- page cards ----------

    override fun onView(i: Int) = showViewer(batch.items[i])

    override fun onRotate(i: Int) {
        val p = batch.items[i]
        p.rot = (p.rot + 90) % 360
        batch.save()
        refresh()
    }

    private fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in batch.items.indices) return
        Collections.swap(batch.items, i, j)
        batch.save()
        refresh()
    }

    override fun onRemove(i: Int) {
        val p = batch.items.removeAt(i)
        batch.save()
        refresh()
        Snackbar.make(root, "Page ${i + 1} deleted", Snackbar.LENGTH_LONG)
            .setAnchorView(dock)
            .setAction("Undo") {
                if (batch.photoFile(p).exists()) {
                    batch.items.add(min(i, batch.items.size), p)
                    batch.save()
                    refresh()
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) batch.deleteFiles(p)
                }
            })
            .show()
    }

    private fun showViewer(p: Photo) {
        val d = Dialog(this, R.style.Theme_DocNest_Viewer)
        d.setContentView(R.layout.dialog_viewer)
        val img = d.findViewById<ImageView>(R.id.viewImg)
        val title = d.findViewById<TextView>(R.id.viewTitle)
        val earlier = d.findViewById<View>(R.id.vEarlier)
        val later = d.findViewById<View>(R.id.vLater)

        fun showPlace() {
            val i = batch.items.indexOf(p)
            title.text = "Page ${i + 1} of ${batch.items.size}"
            img.contentDescription = "Page ${i + 1}"
            earlier.isEnabled = i > 0
            later.isEnabled = i < batch.items.size - 1
            earlier.alpha = if (earlier.isEnabled) 1f else 0.38f
            later.alpha = if (later.isEnabled) 1f else 0.38f
        }

        fun showImage() {
            lifecycleScope.launch {
                val b = withContext(Dispatchers.IO) { Images.load(batch.photoFile(p), 1600, p.rot, false) }
                img.setImageBitmap(b)
            }
        }

        earlier.setOnClickListener {
            move(batch.items.indexOf(p), -1)
            showPlace()
        }
        later.setOnClickListener {
            move(batch.items.indexOf(p), 1)
            showPlace()
        }
        d.findViewById<View>(R.id.vRot).setOnClickListener {
            onRotate(batch.items.indexOf(p))
            showImage()
        }
        d.findViewById<View>(R.id.vDel).setOnClickListener {
            d.dismiss()
            onRemove(batch.items.indexOf(p))
        }
        d.findViewById<View>(R.id.vClose).setOnClickListener { d.dismiss() }
        showPlace()
        showImage()
        d.show()
    }

    // ---------- adding from the gallery ----------

    private fun addFromGallery(uris: List<Uri>) {
        setBusy(true)
        lifecycleScope.launch {
            setProgress("Reading ${photos(uris.size)}…", 0f)
            val exifs = withContext(Dispatchers.IO) {
                uris.map { u ->
                    runCatching { contentResolver.openInputStream(u)!!.use { Images.readExif(it) } }
                        .getOrElse { Images.Exif(0, 0L) }
                }
            }
            // In the order the photos were taken; photos without a date keep their picked order, at the end.
            val order = uris.indices.sortedWith(
                compareBy<Int>({ exifs[it].taken.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }, { it })
            )
            val today = startOfToday()
            val older = exifs.count { it.taken in 1 until today }
            var failed = 0
            order.forEachIndexed { k, i ->
                setProgress("Adding photo ${k + 1} of ${order.size}…", k.toFloat() / order.size)
                val p = batch.newPhoto()
                val thumb = withContext(Dispatchers.IO) {
                    runCatching {
                        Images.store(
                            { contentResolver.openInputStream(uris[i])!! },
                            exifs[i].degrees, batch.photoFile(p), batch.thumbFile(p),
                        )
                    }.getOrNull()
                }
                if (thumb == null) {
                    failed++
                    batch.deleteFiles(p)
                } else {
                    adapter.putThumb(p, thumb)
                    batch.items.add(p)
                    batch.save()
                    refresh()
                }
            }
            setBusy(false)
            refresh()
            val added = order.size - failed
            when {
                failed > 0 -> note("${pages(added)} added. ${photos(failed)} couldn't be opened.", long = true)
                older > 0 -> note("${pages(added)} added, including $older taken before today.", long = true)
                else -> note("${pages(added)} added")
            }
        }
    }

    // ---------- settings and clearing ----------

    private fun showSettings() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        sheet.setContentView(v)

        val quality = v.findViewById<MaterialButtonToggleGroup>(R.id.qualityGroup)
        val colour = v.findViewById<MaterialButtonToggleGroup>(R.id.colorGroup)
        val page = v.findViewById<MaterialButtonToggleGroup>(R.id.pageGroup)
        val nameBox = v.findViewById<TextInputLayout>(R.id.nameBox)
        val nameIn = v.findViewById<EditText>(R.id.nameIn)
        val footOn = v.findViewById<MaterialSwitch>(R.id.footOn)
        val footBox = v.findViewById<View>(R.id.footBox)
        val footIn = v.findViewById<EditText>(R.id.footIn)

        // One line under each choice says what the selected option does.
        fun explain(group: MaterialButtonToggleGroup, hint: TextView, hints: Map<Int, String>) {
            hint.text = hints[group.checkedButtonId]
            group.addOnButtonCheckedListener { _, id, checked -> if (checked) hint.text = hints[id] }
        }

        quality.check(
            when (settings.quality) {
                Quality.SMALL -> R.id.qSmall
                Quality.STANDARD -> R.id.qStandard
                Quality.HIGH -> R.id.qHigh
            }
        )
        colour.check(if (settings.grey) R.id.cGrey else R.id.cColour)
        page.check(if (settings.page == PageSize.A4) R.id.pA4 else R.id.pPhoto)
        explain(
            quality, v.findViewById(R.id.qualityHint), mapOf(
                R.id.qSmall to "Smallest file, good for email.",
                R.id.qStandard to "Clear text at a moderate file size.",
                R.id.qHigh to "Sharpest text, largest file.",
            )
        )
        explain(
            colour, v.findViewById(R.id.colourHint), mapOf(
                R.id.cColour to "Keeps the photos in full colour.",
                R.id.cGrey to "Crisper text on paper, and a smaller file.",
            )
        )
        explain(
            page, v.findViewById(R.id.pageHint), mapOf(
                R.id.pA4 to "Each photo is fitted onto an A4 page, ready to print.",
                R.id.pPhoto to "Each page is the size of its photo, with no white border.",
            )
        )
        nameIn.setText(settings.name)
        nameBox.helperText = "Leave empty to use ${defaultName()}"
        footOn.isChecked = settings.footerOn
        footBox.isVisible = settings.footerOn
        footOn.setOnCheckedChangeListener { _, on -> footBox.isVisible = on }
        footIn.setText(settings.footer)
        v.findViewById<View>(R.id.doneBtn).setOnClickListener { sheet.dismiss() }

        sheet.setOnDismissListener {
            settings.quality = when (quality.checkedButtonId) {
                R.id.qSmall -> Quality.SMALL
                R.id.qHigh -> Quality.HIGH
                else -> Quality.STANDARD
            }
            settings.grey = colour.checkedButtonId == R.id.cGrey
            settings.page = if (page.checkedButtonId == R.id.pPhoto) PageSize.PHOTO else PageSize.A4
            settings.name = nameIn.text.toString().trim()
            settings.footerOn = footOn.isChecked
            settings.footer = footIn.text.toString()
            refresh()
        }
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear This Batch?")
            .setMessage("This deletes all ${pages(batch.items.size)} from DocNest. PDFs you already saved stay in Downloads.")
            .setPositiveButton("Clear batch") { _, _ ->
                batch.clear()
                built = null
                refresh()
                note("Batch cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- making and sending the PDF ----------

    private fun stateKey(): String =
        batch.items.joinToString(",") { "${it.id}:${it.rot}" } +
            "|" + settings.summary() + "|" + settings.footerLine() + "|" + settings.fileName()

    private fun onMake() {
        if (busy || batch.items.isEmpty()) return
        val b = built
        if (b != null && b.key == stateKey() && b.file.exists()) share(b.file) else makePdf()
    }

    private fun makePdf() {
        val list = batch.items.toList()
        val n = list.size
        val quality = settings.quality
        val grey = settings.grey
        val page = settings.page
        val footer = settings.footerLine()
        val name = settings.fileName()
        val key = stateKey()
        setBusy(true)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "pdf")
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val f = File(dir, name)
                    BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { out ->
                        val pdf = PdfWriter(out, n, page, footer)
                        list.forEachIndexed { i, p ->
                            withContext(Dispatchers.Main) { setProgress("Preparing page ${i + 1} of $n…", i.toFloat() / n) }
                            val bmp = Images.load(batch.photoFile(p), quality.edge, p.rot, grey)
                                ?: error("Page ${i + 1} could not be opened")
                            pdf.addPage(Images.jpeg(bmp, quality.jpegQuality), bmp.width, bmp.height)
                            bmp.recycle()
                        }
                        pdf.finish()
                    }
                    f
                }
                setProgress("Saving to Downloads…", 1f)
                val inDownloads = withContext(Dispatchers.IO) { saveToDownloads(file) }
                built = Built(file, key)
                setBusy(false)
                refresh()
                val size = fmtBytes(file.length())
                note(
                    if (inDownloads) "Saved $name to Downloads ($size)" else "PDF ready ($size). Tap Share PDF to send it.",
                    long = true,
                )
                share(file)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                setBusy(false)
                refresh()
                note("Couldn't make the PDF. Try Small quality, or make it from fewer pages.", long = true)
            }
        }
    }

    /** Copies the PDF into the phone's Downloads folder (Android 10 and newer). */
    private fun saveToDownloads(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share PDF"))
    }
}

fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"

private fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"

private fun fmtBytes(b: Long): String =
    if (b >= 1048576) String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else "${max(1, (b / 1024.0).roundToInt())} KB"

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
