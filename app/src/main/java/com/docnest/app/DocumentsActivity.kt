package com.docnest.app

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "My documents": every PDF DocNest has made. Tap to open; star to favourite; ⋮ to share, rename
 * or delete. Deleting is undoable: the row disappears at once, and the file goes when the Undo
 * note does (or when this screen closes).
 */
class DocumentsActivity : AppCompatActivity(), DocumentAdapter.Listener {

    private lateinit var root: View
    private lateinit var loading: View
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: MaterialButton
    private lateinit var chipAll: Chip
    private lateinit var chipFavourites: Chip
    private lateinit var adapter: DocumentAdapter

    private var all: List<Doc> = emptyList()
    private var typed = "" // what is in the search box
    private var query = "" // the search on screen: [typed], once [textMatches] has caught up with it
    private var textMatches: Set<String> = emptySet() // keys of documents whose text has [query]
    private var searching: Job? = null
    private var loaded = false
    private var loadFailed = false
    private var onlyFavourites = false
    private val pendingDeletes = mutableMapOf<String, Doc>()
    private var undoBar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_documents)
        root = findViewById(R.id.root)
        loading = findViewById(R.id.loading)
        empty = findViewById(R.id.empty)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptyBody = findViewById(R.id.emptyBody)
        emptyAction = findViewById(R.id.emptyAction)
        chipAll = findViewById(R.id.chipAll)
        chipFavourites = findViewById(R.id.chipFavourites)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<EditText>(R.id.searchIn).doAfterTextChanged {
            typed = it?.toString()?.trim()?.lowercase().orEmpty()
            search()
        }
        findViewById<ChipGroup>(R.id.filter).setOnCheckedStateChangeListener { _, ids ->
            onlyFavourites = R.id.chipFavourites in ids
            render()
        }
        adapter = DocumentAdapter(lifecycleScope, this, this)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // Reloaded every time the screen comes back, so files renamed or deleted in another app show
        // correctly, and again when a PDF being made in the background is finished.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PdfJob.state.map { it as? PdfJob.Done }.distinctUntilChanged().collect { load() }
            }
        }
    }

    override fun onStop() {
        commitAllDeletes()
        undoBar?.dismiss()
        super.onStop()
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
        chipAll.text = "All (${present.size})"
        chipFavourites.text = "Favourites (${starred.size})"
        val filtered = if (onlyFavourites) starred else present
        // Search looks at names, and inside PDFs that were made searchable.
        val shown = if (query.isEmpty()) filtered else filtered.filter { d ->
            d.name.lowercase().contains(query) || d.key in textMatches
        }
        adapter.submit(shown, favs)

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
                "PDFs you make in DocNest appear here.",
                "Scan pages",
            ) {
                startActivity(Intent(this, CameraActivity::class.java))
                finish()
            }
        }
    }

    private fun showEmpty(title: String, body: String, action: String, onAction: () -> Unit) {
        emptyTitle.text = title
        emptyBody.text = body
        emptyAction.text = action
        emptyAction.setOnClickListener { onAction() }
    }

    private fun note(text: String, long: Boolean = false) {
        Snackbar.make(root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT).show()
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
            Snackbar.make(root, "Removed from favourites", Snackbar.LENGTH_LONG)
                .setAction("Undo") {
                    Library.setFavourite(this, doc.key, true)
                    render()
                }
                .show()
        }
    }

    override fun onMenu(doc: Doc, anchor: View) {
        PopupMenu(this, anchor).apply {
            inflate(R.menu.document_actions)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.actionShare -> share(doc)
                    R.id.actionRename -> askRename(doc)
                    R.id.actionDelete -> delete(doc)
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

    private fun delete(doc: Doc) {
        pendingDeletes[doc.key] = doc
        render()
        undoBar = Snackbar.make(root, "Deleted ${doc.name}", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                pendingDeletes.remove(doc.key)
                render()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) commitDelete(doc.key)
                }
            })
            .also { it.show() }
    }

    private fun commitDelete(key: String) {
        val doc = pendingDeletes.remove(key) ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Library.delete(this@DocumentsActivity, doc) } }
            if (result.isSuccess) {
                all = all.filter { it.key != key }
            } else {
                note((result.exceptionOrNull() as? Library.Failure)?.message ?: "Couldn't delete ${doc.name}.", long = true)
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
}
