package com.docnest.app

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A scan before it is saved ("New PDF"): choose the look, check the pages (drag to reorder,
 * rotate, delete, add more), then Save PDF. When the PDF is made a sheet offers Share, Open and
 * Done. The pages stay for as long as this screen does, so the PDF can be made again with
 * another look; once the screen is left, a saved scan is finished (see [Batch.clearIfSaved]).
 */
class ReviewActivity : AppCompatActivity(), PhotoAdapter.Listener {

    companion object {
        /** Open the camera (or the page scanner) straight away. */
        const val START_SCAN = "scan"

        /** Open the photo picker straight away. */
        const val START_PHOTOS = "photos"
        private const val EXTRA_START = "start"

        fun intent(context: Context, start: String? = null): Intent =
            Intent(context, ReviewActivity::class.java).putExtra(EXTRA_START, start)
    }

    private lateinit var batch: Batch
    private lateinit var settings: Settings
    private lateinit var adapter: PhotoAdapter
    private lateinit var root: View
    private lateinit var dock: View
    private lateinit var countText: TextView
    private lateinit var lookGroup: MaterialButtonToggleGroup
    private lateinit var lookHint: TextView
    private lateinit var statusRow: View
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var optionsRow: View
    private lateinit var optionsText: TextView
    private lateinit var changeBtn: View
    private lateinit var makeBtn: MaterialButton
    private lateinit var discardBtn: View
    private lateinit var cancelBtn: View
    private var savedSheet: BottomSheetDialog? = null
    private var adding = false // photos are being added to the scan

    // The PDF is made by PdfJob, outside this screen, so it carries on when the screen is left.
    private val making get() = PdfJob.running
    private val busy get() = adding || making

    /** The last PDF made. Tapping the button again offers it without rebuilding, until something changes. */
    private val built get() = PdfJob.state.value as? PdfJob.Done

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) addFromGallery(uris) else leaveIfEmpty()
    }

    // "Crop to page edges": Google's scanner finds, crops and straightens each page, and lets the
    // user drag the corners. Its pages then join the scan like photos, in the order scanned.
    private val pageScanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val uris = if (r.resultCode != RESULT_OK) emptyList() else
            GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }.orEmpty()
        if (uris.isNotEmpty()) addFromGallery(uris, scanned = true) else leaveIfEmpty()
    }

    private val openCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        refresh()
        val added = r.data?.getIntExtra(CameraActivity.EXTRA_ADDED, 0) ?: 0
        if (added > 0) note("${pages(added)} added") else leaveIfEmpty()
    }

    private val openOptions = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    // Whatever the answer, the PDF is made: the permission only decides whether its notifications show.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startPdf() }

    // Hold a page to pick it up, drag it to its new place, let go. The "Add pages" tile stays put.
    private val dragger = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
    ) {
        override fun isLongPressDragEnabled() = !busy

        override fun getDragDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
            if (vh is PhotoAdapter.Holder) super.getDragDirs(rv, vh) else 0

        override fun canDropOver(rv: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            target is PhotoAdapter.Holder

        override fun onMove(
            rv: RecyclerView,
            from: RecyclerView.ViewHolder,
            to: RecyclerView.ViewHolder,
        ): Boolean {
            val i = from.bindingAdapterPosition
            val j = to.bindingAdapterPosition
            if (i !in batch.items.indices || j !in batch.items.indices) return false
            batch.items.add(j, batch.items.removeAt(i))
            adapter.notifyItemMoved(i, j)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            batch.save()
            rv.post { refresh() } // renumbers the pages after the drop
        }
    })

    private val lookHints = mapOf(
        R.id.lOriginal to "Keeps the photos exactly as taken.",
        R.id.lDocument to "Even lighting, white paper and crisper text, like a scanner.",
        R.id.lVivid to "Like Document, with stronger colour and darker text.",
        R.id.lBw to "Sharp black text on white, and the smallest files. Best for text without photos.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_review)
        batch = Batch.get(this)
        settings = Settings(this)

        root = findViewById(R.id.root)
        dock = findViewById(R.id.dock)
        countText = findViewById(R.id.countText)
        lookGroup = findViewById(R.id.lookGroup)
        lookHint = findViewById(R.id.lookHint)
        statusRow = findViewById(R.id.statusRow)
        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)
        optionsRow = findViewById(R.id.optionsRow)
        optionsText = findViewById(R.id.optionsText)
        changeBtn = findViewById(R.id.changeBtn)
        makeBtn = findViewById(R.id.makeBtn)
        discardBtn = findViewById(R.id.discardBtn)
        cancelBtn = findViewById(R.id.cancelBtn)

        adapter = PhotoAdapter(batch, this)
        val grid = findViewById<RecyclerView>(R.id.grid)
        val dm = resources.displayMetrics
        grid.layoutManager = GridLayoutManager(this, max(2, (dm.widthPixels / dm.density / 160).toInt()))
        grid.adapter = adapter
        dragger.attachToRecyclerView(grid)

        // The look is kept as soon as it is chosen, and is the same for the next scan.
        lookGroup.check(
            when (settings.colour) {
                ColourMode.COLOUR -> R.id.lOriginal
                ColourMode.DOCUMENT -> R.id.lDocument
                ColourMode.VIVID -> R.id.lVivid
                ColourMode.BW -> R.id.lBw
            }
        )
        lookHint.text = lookHints[lookGroup.checkedButtonId]
        lookGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            settings.colour = when (id) {
                R.id.lDocument -> ColourMode.DOCUMENT
                R.id.lVivid -> ColourMode.VIVID
                R.id.lBw -> ColourMode.BW
                else -> ColourMode.COLOUR
            }
            lookHint.text = lookHints[id]
            refresh()
        }

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        discardBtn.setOnClickListener { confirmDiscard() }
        changeBtn.setOnClickListener { openOptions.launch(SettingsActivity.intent(this, pdfOnly = true)) }
        makeBtn.setOnClickListener { onMake() }
        cancelBtn.setOnClickListener { PdfJob.cancel() }

        refresh()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { followPdf() } }

        // Scan and Photos on the home screen come here first, so pages are added in one place.
        if (savedInstanceState == null && !busy) {
            when (intent.getStringExtra(EXTRA_START)) {
                START_SCAN -> scan()
                START_PHOTOS -> pickPhotos.launch("image/*")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        savedSheet?.dismiss()
        super.onDestroy()
    }

    /** Shows how far the PDF is and, once, how it ended. Runs whenever this screen is showing. */
    private suspend fun followPdf() {
        PdfJob.state.collect { s ->
            when (s) {
                is PdfJob.Running -> {
                    setProgress(s.text, s.fraction)
                    showBusy()
                    return@collect
                }
                is PdfJob.Done -> if (!s.seen) {
                    s.seen = true
                    PdfService.clearResult(this)
                    showSaved(s)
                }
                PdfJob.Failed -> {
                    PdfJob.reset()
                    PdfService.clearResult(this)
                    note("Couldn't make the PDF. Try Small quality, or make it from fewer pages.", long = true)
                }
                PdfJob.Idle -> Unit
            }
            showBusy()
            refresh()
        }
    }

    // ---------- screen state ----------

    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = batch.items.size
        countText.text = if (n == 0) "No pages yet" else "${pages(n)} · hold and drag to reorder"
        discardBtn.isVisible = n > 0
        optionsRow.isVisible = n > 0
        makeBtn.isVisible = n > 0
        makeBtn.text = if (built?.key == stateKey()) "Share PDF" else "Save PDF"
        optionsText.text = settings.optionsLine()
        updateEnabled()
    }

    private fun updateEnabled() {
        makeBtn.isEnabled = !busy && batch.items.isNotEmpty()
        changeBtn.isEnabled = !busy
        discardBtn.isEnabled = !busy
        lookGroup.children.forEach { it.isEnabled = !busy }
    }

    /** Locks the pages and buttons while photos are being added or a PDF is being made. */
    private fun showBusy() {
        if (adapter.enabled == busy) adapter.enabled = !busy
        statusRow.isVisible = busy
        cancelBtn.isVisible = making
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

    /** Back to My documents when a scan was started but nothing was added to it. */
    private fun leaveIfEmpty() {
        if (batch.items.isEmpty() && !adding) finish()
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

    override fun onAdd(anchor: View) {
        PopupMenu(this, anchor).apply {
            inflate(R.menu.add_pages)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.addScan -> scan()
                    R.id.addPhotos -> pickPhotos.launch("image/*")
                }
                true
            }
            show()
        }
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
                val b = withContext(Dispatchers.IO) { Images.load(batch.photoFile(p), 1600, p.rot) }
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

    // ---------- adding pages ----------

    private fun scan() {
        if (settings.cropToPage) scanPages() else openOwnCamera()
    }

    private fun openOwnCamera() {
        openCamera.launch(Intent(this, CameraActivity::class.java))
    }

    /** Google's page scanner (basic mode: crop, rotate, reorder; DocNest applies its own looks). */
    private fun scanPages() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> pageScanner.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                note("Page cropping isn't available on this phone, so the regular camera opened.", long = true)
                openOwnCamera()
            }
    }

    /** Adds photos to the scan. Gallery picks go in the order they were taken; [scanned] pages keep their order. */
    private fun addFromGallery(uris: List<Uri>, scanned: Boolean = false) {
        adding = true
        showBusy()
        lifecycleScope.launch {
            setProgress("Reading ${photos(uris.size)}…", 0f)
            val exifs = withContext(Dispatchers.IO) {
                uris.map { u ->
                    runCatching { contentResolver.openInputStream(u)!!.use { Images.readExif(it) } }
                        .getOrElse { Images.Exif(0, 0L) }
                }
            }
            // In the order the photos were taken; photos without a date keep their picked order, at the end.
            val order = if (scanned) uris.indices.toList() else uris.indices.sortedWith(
                compareBy<Int>({ exifs[it].taken.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }, { it })
            )
            val today = startOfToday()
            val older = if (scanned) 0 else exifs.count { it.taken in 1 until today }
            var failed = 0
            order.forEachIndexed { k, i ->
                setProgress("Adding photo ${k + 1} of ${order.size}…", k.toFloat() / order.size)
                val p = batch.newPhoto()
                val stored = withContext(Dispatchers.IO) {
                    runCatching {
                        Images.store(
                            { contentResolver.openInputStream(uris[i])!! },
                            exifs[i].degrees, batch.photoFile(p), batch.thumbFile(p),
                        )
                    }.getOrNull()
                }
                if (stored == null) {
                    failed++
                    batch.deleteFiles(p)
                } else {
                    p.blurry = stored.blurry
                    adapter.putThumb(p, stored.thumb)
                    batch.items.add(p)
                    batch.save()
                    refresh()
                }
            }
            adding = false
            showBusy()
            refresh()
            val added = order.size - failed
            when {
                failed > 0 -> note("${pages(added)} added. ${photos(failed)} couldn't be opened.", long = true)
                older > 0 -> note("${pages(added)} added, including $older taken before today.", long = true)
                else -> note("${pages(added)} added")
            }
        }
    }

    // ---------- discarding ----------

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard This Scan?")
            .setMessage("This deletes all ${pages(batch.items.size)} of it from DocNest. PDFs you already saved stay in My documents.")
            .setPositiveButton("Discard") { _, _ ->
                batch.clear()
                PdfJob.reset()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- making and sending the PDF ----------

    private fun stateKey(): String =
        batch.pagesKey() + "|" + settings.summary() + "|" + settings.footerLine() + "|" + settings.fileName()

    private fun onMake() {
        if (busy || batch.items.isEmpty()) return
        val b = built
        if (b != null && b.key == stateKey() && b.file.exists()) showSaved(b) else makePdf()
    }

    private fun makePdf() {
        // Android 13+ shows the progress and "PDF ready" notifications only with permission; asked once.
        val ask = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) {
            settings.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPdf()
        }
    }

    private fun startPdf() {
        if (busy || batch.items.isEmpty()) return
        PdfJob.start(
            this,
            PdfJob.Request(
                pages = batch.items.map { PdfJob.Page(batch.photoFile(it), it.rot) },
                pagesKey = batch.pagesKey(),
                quality = settings.quality,
                colour = settings.colour,
                page = settings.page,
                footer = settings.footerLine(),
                name = settings.fileName(),
                searchable = settings.searchable,
                key = stateKey(),
            ),
        )
    }

    /**
     * The sheet that slides up when the PDF is made: Share, Open, or Done. Done goes back to
     * My documents; swiping the sheet away stays here, to make the PDF again another way.
     */
    private fun showSaved(d: PdfJob.Done) {
        savedSheet?.dismiss()
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_saved, null)
        sheet.setContentView(v)
        val saved = d.saved
        val size = fmtBytes(d.file.length())

        v.findViewById<TextView>(R.id.savedTitle).text = if (saved != null) "PDF Saved" else "PDF Ready"
        v.findViewById<TextView>(R.id.savedName).text = saved?.name ?: d.file.name
        v.findViewById<TextView>(R.id.savedMeta).text =
            if (saved != null) "${pages(d.pages)} · $size · ${Library.savedIn}"
            else "${pages(d.pages)} · $size. It couldn't be saved to My documents, so share it now to keep it."
        v.findViewById<View>(R.id.savedNote).isVisible = saved != null && d.textMissing

        v.findViewById<View>(R.id.shareBtn).setOnClickListener { share(d.file) }
        v.findViewById<View>(R.id.openBtn).apply {
            isVisible = saved != null
            setOnClickListener {
                try {
                    startActivity(Library.openIntent(saved!!))
                } catch (e: ActivityNotFoundException) {
                    // A toast: a note on the screen behind would be hidden by the sheet.
                    Toast.makeText(context, "No app on this phone can open PDFs. Install a PDF viewer, such as Google Drive.", Toast.LENGTH_LONG).show()
                }
            }
        }
        v.findViewById<View>(R.id.doneBtn).setOnClickListener {
            sheet.dismiss()
            finish()
        }
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
        savedSheet = sheet
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

fun fmtBytes(b: Long): String =
    if (b >= 1048576) String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else "${max(1, (b / 1024.0).roundToInt())} KB"

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
