package com.docnest.app

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

class MainActivity : AppCompatActivity(), PhotoAdapter.Listener {

    private enum class Kind { PLAIN, OK, ERR }

    /** The last PDF made. Tapping the button again shares it without rebuilding, until something changes. */
    private class Built(val file: File, val key: String)

    private lateinit var batch: Batch
    private lateinit var settings: Settings
    private lateinit var adapter: PhotoAdapter
    private lateinit var root: View
    private lateinit var bar: View
    private lateinit var msg: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var makeBtn: MaterialButton
    private lateinit var countText: TextView
    private lateinit var emptyText: View
    private lateinit var clearBtn: View
    private var busy = false
    private var built: Built? = null

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) addFromGallery(uris)
    }

    private val openCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        refresh()
        val added = r.data?.getIntExtra(CameraActivity.EXTRA_ADDED, 0) ?: 0
        if (added > 0) setMsg("${photos(added)} added from the camera.", Kind.OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        batch = Batch.get(this)
        settings = Settings(this)

        root = findViewById(R.id.root)
        bar = findViewById(R.id.bar)
        msg = findViewById(R.id.msg)
        progress = findViewById(R.id.progress)
        makeBtn = findViewById(R.id.makeBtn)
        countText = findViewById(R.id.countText)
        emptyText = findViewById(R.id.empty)
        clearBtn = findViewById(R.id.clearBtn)

        adapter = PhotoAdapter(batch, this)
        val grid = findViewById<RecyclerView>(R.id.grid)
        val dm = resources.displayMetrics
        grid.layoutManager = GridLayoutManager(this, max(2, (dm.widthPixels / dm.density / 170).toInt()))
        grid.adapter = adapter

        findViewById<View>(R.id.cameraBtn).setOnClickListener {
            openCamera.launch(Intent(this, CameraActivity::class.java))
        }
        findViewById<View>(R.id.galleryBtn).setOnClickListener { pickPhotos.launch("image/*") }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        clearBtn.setOnClickListener { confirmClear() }
        makeBtn.setOnClickListener { onMake() }

        refresh()
        if (savedInstanceState == null && batch.items.isNotEmpty()) {
            setMsg("${photos(batch.items.size)} from your last session are still here.", Kind.OK)
        } else {
            idleMsg()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------- status ----------

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = batch.items.size
        countText.text = "${photos(n)} in this batch"
        emptyText.isVisible = n == 0
        clearBtn.isVisible = n > 0
        makeBtn.isEnabled = !busy && n > 0
        makeBtn.text = if (built?.key == stateKey()) "Share PDF" else "Make PDF"
    }

    private fun setMsg(text: String, kind: Kind = Kind.PLAIN) {
        msg.text = text
        val color = when (kind) {
            Kind.PLAIN -> R.color.ink
            Kind.OK -> R.color.ok
            Kind.ERR -> R.color.accent
        }
        msg.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun idleMsg() {
        val n = batch.items.size
        setMsg(if (n == 0) "Add photos to start." else "${pages(n)} ready to make into a PDF.")
    }

    private fun setProgress(text: String, fraction: Float) {
        progress.isVisible = true
        progress.setProgressCompat((fraction * 1000).toInt(), true)
        setMsg(text)
    }

    private fun setBusy(on: Boolean) {
        busy = on
        listOf(R.id.cameraBtn, R.id.galleryBtn, R.id.settingsBtn, R.id.clearBtn).forEach {
            findViewById<View>(it).isEnabled = !on
        }
        adapter.enabled = !on
        makeBtn.isEnabled = !on && batch.items.isNotEmpty()
        if (!on) progress.isVisible = false
    }

    // ---------- photo cards ----------

    override fun onView(i: Int) = showViewer(batch.items[i])

    override fun onRotate(i: Int) {
        val p = batch.items[i]
        p.rot = (p.rot + 90) % 360
        batch.save()
        refresh()
    }

    override fun onMove(i: Int, by: Int) {
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
        Snackbar.make(root, "Photo ${i + 1} removed.", Snackbar.LENGTH_LONG)
            .setAnchorView(bar)
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
        fun show() {
            title.text = "Photo ${batch.items.indexOf(p) + 1} of ${batch.items.size}"
            lifecycleScope.launch {
                val b = withContext(Dispatchers.IO) { Images.load(batch.photoFile(p), 1600, p.rot, false) }
                img.setImageBitmap(b)
            }
        }
        d.findViewById<View>(R.id.vRot).setOnClickListener {
            onRotate(batch.items.indexOf(p))
            show()
        }
        d.findViewById<View>(R.id.vDel).setOnClickListener {
            d.dismiss()
            onRemove(batch.items.indexOf(p))
        }
        d.findViewById<View>(R.id.vClose).setOnClickListener { d.dismiss() }
        show()
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
            val olderNote = if (older == 0) "" else
                " ${if (older == 1) "1 of them is" else "$older of them are"} from an earlier day. Check before sending."
            when {
                failed > 0 -> setMsg("${photos(added)} added. $failed could not be opened.$olderNote", Kind.ERR)
                older > 0 -> setMsg("${photos(added)} added.$olderNote", Kind.ERR)
                else -> setMsg("${photos(added)} added.", Kind.OK)
            }
        }
    }

    // ---------- settings and clearing ----------

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val quality = v.findViewById<MaterialButtonToggleGroup>(R.id.qualityGroup)
        val colour = v.findViewById<MaterialButtonToggleGroup>(R.id.colorGroup)
        val page = v.findViewById<MaterialButtonToggleGroup>(R.id.pageGroup)
        val nameBox = v.findViewById<TextInputLayout>(R.id.nameBox)
        val nameIn = v.findViewById<EditText>(R.id.nameIn)
        val footOn = v.findViewById<CheckBox>(R.id.footOn)
        val footIn = v.findViewById<EditText>(R.id.footIn)

        quality.check(
            when (settings.quality) {
                Quality.SMALL -> R.id.qSmall
                Quality.STANDARD -> R.id.qStandard
                Quality.HIGH -> R.id.qHigh
            }
        )
        colour.check(if (settings.grey) R.id.cGrey else R.id.cColour)
        page.check(if (settings.page == PageSize.A4) R.id.pA4 else R.id.pPhoto)
        nameIn.setText(settings.name)
        nameBox.helperText = "Leave empty for ${defaultName()}"
        footOn.isChecked = settings.footerOn
        footIn.setText(settings.footer)
        footIn.isEnabled = settings.footerOn
        footOn.setOnCheckedChangeListener { _, on -> footIn.isEnabled = on }

        MaterialAlertDialogBuilder(this)
            .setTitle("PDF settings")
            .setView(v)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
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
                setMsg("PDF settings: ${settings.summary()}.")
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Start a new batch?")
            .setMessage("This removes all ${photos(batch.items.size)} from this phone. PDFs already saved in Downloads are kept.")
            .setPositiveButton("Yes, remove") { _, _ ->
                batch.clear()
                built = null
                refresh()
                setMsg("Batch cleared. Ready for new photos.", Kind.OK)
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
                                ?: error("Photo ${i + 1} could not be opened")
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
                val info = "${pages(n)}, ${fmtBytes(file.length())}"
                setMsg(
                    if (inDownloads) "Saved $name in Downloads ($info)."
                    else "PDF ready ($info). Tap Share PDF to send it.",
                    Kind.OK,
                )
                share(file)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                setBusy(false)
                refresh()
                setMsg("The PDF could not be made. Try the Small file size, or split the batch.", Kind.ERR)
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

private fun photos(n: Int) = if (n == 1) "1 photo" else "$n photos"

private fun pages(n: Int) = if (n == 1) "1 page" else "$n pages"

private fun fmtBytes(b: Long): String =
    if (b >= 1048576) String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else "${max(1, (b / 1024.0).roundToInt())} KB"

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
