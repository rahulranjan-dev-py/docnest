package com.docnest.app

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
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
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
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
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity(), PhotoAdapter.Listener {

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
    private lateinit var cancelBtn: View
    private lateinit var updateBanner: UpdateBanner
    private var adding = false // photos are being added to the batch

    // The PDF is made by PdfJob, outside this screen, so it carries on when the screen is left.
    private val making get() = PdfJob.running
    private val busy get() = adding || making

    /** The last PDF made. Tapping the button again shares it without rebuilding, until something changes. */
    private val built get() = PdfJob.state.value as? PdfJob.Done

    private val pickPhotos = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) addFromGallery(uris)
    }

    // "Crop to page edges": Google's scanner finds, crops and straightens each page, and lets the
    // user drag the corners. Its pages then join the batch like photos, in the order scanned.
    private val pageScanner = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val uris = GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.map { it.imageUri }.orEmpty()
        if (uris.isNotEmpty()) addFromGallery(uris, scanned = true)
    }

    private val openCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        refresh()
        val added = r.data?.getIntExtra(CameraActivity.EXTRA_ADDED, 0) ?: 0
        if (added > 0) note("${pages(added)} added")
    }

    // Whatever the answer, the PDF is made: the permission only decides whether its notifications show.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startPdf() }

    // An update waiting for the "install unknown apps" permission.
    private var pendingInstall: File? = null

    private val askInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingInstall
        if (apk != null && Updater.canInstall(this)) install(apk)
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
        cancelBtn = findViewById(R.id.cancelBtn)

        adapter = PhotoAdapter(batch, this)
        val grid = findViewById<RecyclerView>(R.id.grid)
        val dm = resources.displayMetrics
        grid.layoutManager = GridLayoutManager(this, max(2, (dm.widthPixels / dm.density / 160).toInt()))
        grid.adapter = adapter
        dragger.attachToRecyclerView(grid)

        cameraBtn.setOnClickListener { if (settings.cropToPage) scanPages() else openOwnCamera() }
        galleryBtn.setOnClickListener { pickPhotos.launch("image/*") }
        settingsBtn.setOnClickListener { showSettings() }
        findViewById<View>(R.id.documentsBtn).setOnClickListener { openDocuments() }
        clearBtn.setOnClickListener { confirmClear() }
        makeBtn.setOnClickListener { onMake() }
        cancelBtn.setOnClickListener { PdfJob.cancel() }

        updateBanner = UpdateBanner(this, findViewById(R.id.updateBanner), ::install) { text, long -> note(text, long) }
        refresh()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { followPdf() } }
    }

    /** Shows how far the PDF is and, once, how it ended. Runs whenever this screen is showing. */
    private suspend fun followPdf() {
        // A PDF that finishes while this screen watches goes straight to the share screen. One
        // that finished while DocNest was away is only announced: Share PDF is there for later.
        var watched = false
        PdfJob.state.collect { s ->
            when (s) {
                is PdfJob.Running -> {
                    watched = true
                    setProgress(s.text, s.fraction)
                    showBusy()
                    return@collect
                }
                is PdfJob.Done -> if (!s.seen) {
                    s.seen = true
                    announce(s)
                    if (watched) share(s.file)
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

    private fun announce(d: PdfJob.Done) {
        PdfService.clearResult(this)
        val size = fmtBytes(d.file.length())
        when {
            d.saved == null -> note("PDF ready ($size), but it couldn't be saved. Tap Share PDF to send it.", long = true)
            d.textMissing -> note(
                "Saved ${d.saved.name}, but without searchable text: the text reader is still downloading. Try again in a few minutes.",
                long = true, action = "View" to ::openDocuments,
            )
            else -> note("Saved ${d.saved.name} ($size)", long = true, action = "View" to ::openDocuments)
        }
    }

    override fun onStart() {
        super.onStart()
        updateBanner.onAppOpened()
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

    /** A short note above the dock, gone after a few seconds, with an optional button. */
    private fun note(text: String, long: Boolean = false, action: Pair<String, () -> Unit>? = null) {
        val bar = Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
            .setAnchorView(dock)
        if (action != null) bar.setAction(action.first) { action.second() }
        bar.show()
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

    // ---------- adding from the gallery ----------

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

    /** Adds photos to the batch. Gallery picks go in the order they were taken; [scanned] pages keep their order. */
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

    // ---------- settings and clearing ----------

    private fun showSettings() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        sheet.setContentView(v)

        val quality = v.findViewById<MaterialButtonToggleGroup>(R.id.qualityGroup)
        val colour = v.findViewById<ChipGroup>(R.id.colorGroup)
        val page = v.findViewById<MaterialButtonToggleGroup>(R.id.pageGroup)
        val cropOn = v.findViewById<MaterialSwitch>(R.id.cropOn)
        val searchOn = v.findViewById<MaterialSwitch>(R.id.searchOn)
        val footOn = v.findViewById<MaterialSwitch>(R.id.footOn)
        val footGroup = v.findViewById<View>(R.id.footGroup)
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
        colour.check(
            when (settings.colour) {
                ColourMode.COLOUR -> R.id.cColour
                ColourMode.DOCUMENT -> R.id.cDocument
                ColourMode.VIVID -> R.id.cVivid
                ColourMode.BW -> R.id.cBw
            }
        )
        page.check(if (settings.page == PageSize.A4) R.id.pA4 else R.id.pPhoto)
        explain(
            quality, v.findViewById(R.id.qualityHint), mapOf(
                R.id.qSmall to "Smallest file, good for email.",
                R.id.qStandard to "Clear text at a moderate file size.",
                R.id.qHigh to "Sharpest text, largest file.",
            )
        )
        val lookHints = mapOf(
            R.id.cColour to "Keeps the photos exactly as taken.",
            R.id.cDocument to "Even lighting, white paper and crisper text, like a scanner.",
            R.id.cVivid to "Like Document, with stronger colour and darker text.",
            R.id.cBw to "Sharp black text on white, and the smallest files. Best for text without photos.",
        )
        val lookHint = v.findViewById<TextView>(R.id.colourHint)
        lookHint.text = lookHints[colour.checkedChipId]
        colour.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let { lookHint.text = lookHints[it] } }
        cropOn.isChecked = settings.cropToPage
        searchOn.isChecked = settings.searchable
        explain(
            page, v.findViewById(R.id.pageHint), mapOf(
                R.id.pA4 to "Each photo is fitted onto an A4 page, ready to print.",
                R.id.pPhoto to "Each page is the size of its photo, with no white border.",
            )
        )
        footOn.isChecked = settings.footerOn
        footGroup.isVisible = settings.footerOn
        footOn.setOnCheckedChangeListener { _, on -> footGroup.isVisible = on }
        footIn.setText(settings.footer)
        v.findViewById<TextView>(R.id.versionText).text = "Version ${Updater.versionName(this)}"
        v.findViewById<View>(R.id.updateBtn).setOnClickListener {
            sheet.dismiss()
            updateBanner.check(manual = true)
        }
        v.findViewById<View>(R.id.doneBtn).setOnClickListener { sheet.dismiss() }

        sheet.setOnDismissListener {
            settings.quality = when (quality.checkedButtonId) {
                R.id.qSmall -> Quality.SMALL
                R.id.qHigh -> Quality.HIGH
                else -> Quality.STANDARD
            }
            settings.colour = when (colour.checkedChipId) {
                R.id.cDocument -> ColourMode.DOCUMENT
                R.id.cVivid -> ColourMode.VIVID
                R.id.cBw -> ColourMode.BW
                else -> ColourMode.COLOUR
            }
            settings.page = if (page.checkedButtonId == R.id.pPhoto) PageSize.PHOTO else PageSize.A4
            settings.cropToPage = cropOn.isChecked
            settings.searchable = searchOn.isChecked
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
                PdfJob.reset()
                refresh()
                note("Batch cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- updates (checking and downloading live in UpdateBanner) ----------

    private fun install(apk: File) {
        if (!Updater.canInstall(this)) {
            pendingInstall = apk
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow Updates")
                .setMessage("To install updates, turn on “Allow from this source” for DocNest, then come back.")
                .setPositiveButton("Open settings") { _, _ -> askInstallPermission.launch(Updater.permissionIntent(this)) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        pendingInstall = null
        startActivity(Updater.installIntent(this, apk))
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

    private fun openDocuments() {
        startActivity(Intent(this, DocumentsActivity::class.java))
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
