package com.docnest.app

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home: every PDF DocNest has made, with Scan and Photos along the bottom.
 *
 * Tap a document to open it; star to favourite; ⋮ to share, rename, select or delete. Hold a
 * document to start selecting several, then delete them together. Deleting is undoable: the rows
 * disappear at once, and the files go when the Undo note does (or when this screen is left).
 *
 * A scan that isn't saved yet, or a PDF being made, shows as a card above the list.
 * The launcher opens this screen under its old name, .MainActivity (see the manifest).
 */
class DocumentsActivity : AppCompatActivity(), DocumentAdapter.Listener {

    private lateinit var batch: Batch
    private lateinit var root: View
    private lateinit var normalBar: View
    private lateinit var selectBar: View
    private lateinit var selCount: TextView
    private lateinit var loading: View
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: MaterialButton
    private lateinit var chipAll: Chip
    private lateinit var chipFavourites: Chip
    private lateinit var draftCard: View
    private lateinit var draftTitle: TextView
    private lateinit var draftText: TextView
    private lateinit var draftBtn: MaterialButton
    private lateinit var bottomBar: View
    private lateinit var adapter: DocumentAdapter
    private lateinit var updateBanner: UpdateBanner

    private var all: List<Doc> = emptyList()
    private var shown: List<Doc> = emptyList() // what the list shows now, after the filter and search
    private var typed = "" // what is in the search box
    private var query = "" // the search on screen: [typed], once [textMatches] has caught up with it
    private var textMatches: Set<String> = emptySet() // keys of documents whose text has [query]
    private var searching: Job? = null
    private var loaded = false
    private var loadFailed = false
    private var onlyFavourites = false
    private val selected = LinkedHashSet<String>() // keys; not empty means documents are being selected
    private val pendingDeletes = mutableMapOf<String, Doc>()
    private var undoBar: Snackbar? = null

    // An update waiting for the "install unknown apps" permission.
    private var pendingInstall: File? = null

    private val askInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingInstall
        if (apk != null && Updater.canInstall(this)) install(apk)
    }

    private val openSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == SettingsActivity.RESULT_CHECK_UPDATES) updateBanner.check(manual = true)
    }

    // Back leaves selecting before it leaves the app.
    private val stopSelecting = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        batch = Batch.get(this)
        root = findViewById(R.id.root)
        normalBar = findViewById(R.id.normalBar)
        selectBar = findViewById(R.id.selectBar)
        selCount = findViewById(R.id.selCount)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyBody = findViewById(R.id.emptyBody)
        emptyAction = findViewById(R.id.emptyAction)
        chipAll = findViewById(R.id.chipAll)
        chipFavourites = findViewById(R.id.chipFavourites)
        draftCard = findViewById(R.id.draftCard)
        draftTitle = findViewById(R.id.draftTitle)
        draftText = findViewById(R.id.draftText)
        draftBtn = findViewById(R.id.draftBtn)
        bottomBar = findViewById(R.id.bottomBar)

        findViewById<View>(R.id.settingsBtn).setOnClickListener { openSettings.launch(SettingsActivity.intent(this)) }
        findViewById<View>(R.id.closeSelBtn).setOnClickListener { clearSelection() }
        findViewById<View>(R.id.selectAllBtn).setOnClickListener {
            selected += shown.map { it.key }
            render()
        }
        findViewById<View>(R.id.deleteSelBtn).setOnClickListener { deleteSelected() }
        findViewById<EditText>(R.id.searchIn).doAfterTextChanged {
            typed = it?.toString()?.trim()?.lowercase().orEmpty()
            search()
        }
        findViewById<ChipGroup>(R.id.filter).setOnCheckedStateChangeListener { _, ids ->
            onlyFavourites = R.id.chipFavourites in ids
            render()
        }
        draftCard.setOnClickListener { openScan(null) }
        draftBtn.setOnClickListener { openScan(null) }
        findViewById<View>(R.id.scanBtn).setOnClickListener { openScan(ReviewActivity.START_SCAN) }
        findViewById<View>(R.id.photosBtn).setOnClickListener { openScan(ReviewActivity.START_PHOTOS) }

        adapter = DocumentAdapter(lifecycleScope, this, this)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        onBackPressedDispatcher.addCallback(this, stopSelecting)

        updateBanner = UpdateBanner(this, findViewById(R.id.updateBanner), ::install) { text, long -> note(text, long) }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { followPdf() } }
    }

    override fun onStart() {
        super.onStart()
        updateBanner.onAppOpened()
    }

    override fun onStop() {
        commitAllDeletes()
        undoBar?.dismiss()
        super.onStop()
    }

    /** Opens the scan in progress; [start] also starts the camera or the photo picker there. */
    private fun openScan(start: String?) {
        // While a PDF is being made its pages mustn't change: show how far it is instead.
        startActivity(ReviewActivity.intent(this, start.takeUnless { PdfJob.running }))
    }

    // ---------- the scan in progress, and the PDF being made ----------

    /**
     * Runs whenever this screen is showing. Keeps the card above the list in step with the PDF
     * being made, says how a PDF ended if its own screen wasn't there to say it, and reloads the
     * list: every time the screen comes back (files may have been renamed or deleted in another
     * app) and again when a PDF is finished.
     */
    private suspend fun followPdf() {
        var listed: PdfJob.State? = null // the state the list was last loaded for
        PdfJob.state.collect { s ->
            when (s) {
                is PdfJob.Done -> if (!s.seen) {
                    s.seen = true
                    PdfService.clearResult(this)
                    val saved = s.saved
                    if (saved == null) {
                        note("The PDF was made, but couldn't be saved. Open the scan and tap Share PDF.", long = true)
                    } else {
                        note("Saved ${saved.name} (${fmtBytes(s.file.length())})", long = true, action = "Share" to { share(saved) })
                    }
                }
                PdfJob.Failed -> {
                    PdfJob.reset()
                    PdfService.clearResult(this)
                    note("Couldn't make the PDF. Open the scan to try again.", long = true)
                }
                else -> Unit
            }
            // Pages that are in a saved PDF, unchanged, are a finished scan.
            if (s !is PdfJob.Running) batch.clearIfSaved()
            showDraft()
            if (listed == null || (s is PdfJob.Done && s !== listed)) {
                listed = s
                load()
            }
        }
    }

    private fun showDraft() {
        val s = PdfJob.state.value
        val n = batch.items.size
        draftCard.isVisible = selected.isEmpty() && (s is PdfJob.Running || n > 0)
        if (s is PdfJob.Running) {
            draftTitle.text = "Making your PDF"
            draftText.text = s.text
            draftBtn.text = "View"
        } else {
            draftTitle.text = "Unfinished scan"
            draftText.text = "${pages(n)}, not saved yet"
            draftBtn.text = "Continue"
        }
    }

    // ---------- loading and showing ----------

    private fun load() {
        if (!loaded) loading.isVisible = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Library.list(this@DocumentsActivity).also { docs ->
                        runCatching { SearchIndex.importOldFiles(this@DocumentsActivity, docs) }
                    }
                }
            }
            loaded = true
            loading.isVisible = false
            loadFailed = result.isFailure
            all = result.getOrDefault(emptyList())
            search()
        }
    }

    /** Looks [typed] up in the text of searchable PDFs, off the main thread, then shows the result. */
    private fun search() {
        searching?.cancel()
        val q = typed
        searching = lifecycleScope.launch {
            val found = if (q.isEmpty()) emptySet() else withContext(Dispatchers.IO) {
                runCatching { SearchIndex.search(this@DocumentsActivity, q) }.getOrDefault(emptySet())
            }
            query = q
            textMatches = found
            render()
        }
    }

    private fun render() {
        val favs = Library.favourites(this)
        val present = all.filter { it.key !in pendingDeletes }
        val starred = present.filter { it.key in favs }
        chipAll.text = "All ${present.size}"
        chipFavourites.text = "Favourites ${starred.size}"
        val filtered = if (onlyFavourites) starred else present
        // Search looks at names, and inside PDFs that were made searchable.
        shown = if (query.isEmpty()) filtered else filtered.filter { d ->
            d.name.lowercase().contains(query) || d.key in textMatches
        }
        // A document that is gone (deleted here or in another app) can't stay selected.
        selected.retainAll(present.map { it.key }.toSet())
        adapter.submit(shown, favs, selected.toSet())

        val selecting = selected.isNotEmpty()
        normalBar.isVisible = !selecting
        selectBar.isVisible = selecting
        selCount.text = "${selected.size} selected"
        bottomBar.isVisible = !selecting
        stopSelecting.isEnabled = selecting
        showDraft()

        empty.isVisible = loaded && shown.isEmpty()
        when {
            query.isNotEmpty() && filtered.isNotEmpty() -> showEmpty(
                "No matches for “$query”",
                "Search looks in document names, and for words that start this way in searchable PDFs.",
                "Clear search",
            ) { findViewById<EditText>(R.id.searchIn).setText("") }
            loadFailed -> showEmpty(
                "Couldn't load your documents",
                "Check that your phone has some free space, then try again.",
                "Try again",
            ) { load() }
            onlyFavourites && present.isNotEmpty() -> showEmpty(
                "No favourites yet",
                "Tap the star on a document to keep it here.",
                "Show all documents",
            ) { chipAll.isChecked = true }
            else -> showEmpty(
                "No documents yet",
                "Tap Scan to photograph your pages, or Photos to use pictures you already have.",
                null,
            ) {}
        }
    }

    private fun showEmpty(title: String, body: String, action: String?, onAction: () -> Unit) {
        emptyTitle.text = title
        emptyBody.text = body
        emptyAction.isVisible = action != null
        emptyAction.text = action
        emptyAction.setOnClickListener { onAction() }
    }

    /** A short note above the bottom bar, gone after a few seconds, with an optional button. */
    private fun note(text: String, long: Boolean = false, action: Pair<String, () -> Unit>? = null) {
        val bar = Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        if (bottomBar.isVisible) bar.setAnchorView(bottomBar)
        if (action != null) bar.setAction(action.first) { action.second() }
        bar.show()
    }

    // ---------- row actions ----------

    override fun onOpen(doc: Doc) {
        try {
            startActivity(Library.openIntent(doc))
        } catch (e: ActivityNotFoundException) {
            note("No app on this phone can open PDFs. Install a PDF viewer, such as Google Drive.", long = true)
        }
    }

    override fun onStar(doc: Doc, on: Boolean) {
        Library.setFavourite(this, doc.key, on)
        render()
        // Un-starring in the Favourites view makes the row vanish, so offer a way back.
        if (!on && onlyFavourites) {
            note("Removed from favourites", long = true, action = "Undo" to {
                Library.setFavourite(this, doc.key, true)
                render()
            })
        }
    }

    override fun onMenu(doc: Doc, anchor: View) {
        PopupMenu(this, anchor).apply {
            inflate(R.menu.document_actions)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.actionShare -> share(doc)
                    R.id.actionRename -> askRename(doc)
                    R.id.actionSelect -> onSelect(doc)
                    R.id.actionDelete -> delete(listOf(doc))
                }
                true
            }
            show()
        }
    }

    private fun share(doc: Doc) {
        try {
            startActivity(Library.shareIntent(doc))
        } catch (e: ActivityNotFoundException) {
            note("No app on this phone can share PDFs.", long = true)
        }
    }

    // ---------- selecting several ----------

    override fun onSelect(doc: Doc) {
        if (!selected.remove(doc.key)) selected += doc.key
        render()
    }

    private fun clearSelection() {
        selected.clear()
        render()
    }

    /** One document goes at once, with Undo. Several are confirmed first, and can still be undone. */
    private fun deleteSelected() {
        val docs = all.filter { it.key in selected }
        if (docs.size <= 1) {
            delete(docs)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${docs.size} Documents?")
            .setMessage("They are deleted from this phone, not only from DocNest.")
            .setPositiveButton("Delete") { _, _ -> delete(docs) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- rename ----------

    private fun askRename(doc: Doc) {
        val v = layoutInflater.inflate(R.layout.dialog_rename, null)
        val box = v.findViewById<TextInputLayout>(R.id.nameBox)
        val input = v.findViewById<EditText>(R.id.nameIn)
        val current = doc.name.removeSuffix(".pdf")
        input.setText(current)
        input.selectAll()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Rename Document")
            .setView(v)
            .setPositiveButton("Rename", null) // wired below, so a bad name keeps the dialog open
            .setNegativeButton("Cancel", null)
            .create()

        // Checked as you type: the Rename button only works for a name that can be used.
        fun check(): Boolean {
            val problem = Library.nameProblem(input.text.toString())
            box.error = problem
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = problem == null
            return problem == null
        }

        fun confirm() {
            if (!check()) return
            val name = input.text.toString().trim()
            dialog.dismiss()
            if (name != current) rename(doc, name)
        }

        input.doAfterTextChanged { check() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                confirm()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { confirm() }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun rename(doc: Doc, name: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Library.rename(this@DocumentsActivity, doc, name) } }
            result.onSuccess { renamed ->
                all = all.map { if (it.key == doc.key) renamed else it }
                render()
                note("Renamed to ${renamed.name}")
            }.onFailure {
                note((it as? Library.Failure)?.message ?: "Couldn't rename ${doc.name}. Try again.", long = true)
            }
        }
    }

    // ---------- delete, with Undo ----------

    private fun delete(docs: List<Doc>) {
        if (docs.isEmpty()) return
        docs.forEach { pendingDeletes[it.key] = it }
        selected.clear()
        render()
        val keys = docs.map { it.key }
        val text = if (docs.size == 1) "Deleted ${docs[0].name}" else "Deleted ${docs.size} documents"
        undoBar = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
            .setAnchorView(bottomBar)
            .setAction("Undo") {
                keys.forEach { pendingDeletes.remove(it) }
                render()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) commitDeletes(keys)
                }
            })
            .also { it.show() }
    }

    private fun commitDeletes(keys: List<String>) {
        val docs = keys.mapNotNull { pendingDeletes.remove(it) }
        if (docs.isEmpty()) return
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                docs.mapNotNull { d -> runCatching { Library.delete(this@DocumentsActivity, d) }.exceptionOrNull()?.let { d to it } }
            }
            val kept = failed.map { it.first.key }.toSet()
            all = all.filter { d -> d.key in kept || docs.none { it.key == d.key } }
            when {
                failed.size == 1 -> {
                    val (doc, why) = failed[0]
                    note((why as? Library.Failure)?.message ?: "Couldn't delete ${doc.name}.", long = true)
                }
                failed.size > 1 -> note("${failed.size} documents couldn't be deleted. Delete them in your Files app.", long = true)
            }
            render()
        }
    }

    /** Leaving the screen ends the Undo window: delete what's still waiting, off the main thread. */
    private fun commitAllDeletes() {
        if (pendingDeletes.isEmpty()) return
        val docs = pendingDeletes.values.toList()
        pendingDeletes.clear()
        val app = applicationContext
        all = all.filter { d -> docs.none { it.key == d.key } }
        Thread { docs.forEach { runCatching { Library.delete(app, it) } } }.start()
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
}
