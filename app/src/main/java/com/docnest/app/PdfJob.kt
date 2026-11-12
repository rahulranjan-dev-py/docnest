package com.docnest.app

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Makes the PDF outside any screen, so a long one carries on while DocNest is in the background
 * or the phone is locked. One PDF at a time. Screens show [state]; [PdfService] keeps Android
 * from pausing the work and shows it in the notification shade.
 */
object PdfJob {
    /** One page for the PDF: a stored photo and how far it is turned. */
    class Page(val photo: File, val rot: Int)

    /**
     * Everything a PDF is made from, copied when Save PDF is tapped. [key]: see [Done.key].
     * [pagesKey] is [Batch.pagesKey] at that moment, so the scan can be marked as saved.
     */
    class Request(
        val pages: List<Page>,
        val pagesKey: String,
        val quality: Quality,
        val colour: ColourMode,
        val page: PageSize,
        val footer: String?,
        val name: String,
        val searchable: Boolean,
        val key: String,
    )

    sealed interface State

    data object Idle : State

    class Running(val text: String, val fraction: Float) : State

    /**
     * The PDF is made. [key] says what it was made from, so the same pages and settings can be
     * shared again without rebuilding. [saved] is null when it couldn't be filed in My documents.
     * [textMissing]: it was meant to be searchable, but no page could be read.
     */
    class Done(val file: File, val key: String, val saved: Doc?, val pages: Int, val textMissing: Boolean) : State {
        /** Set once a screen has told the user. */
        @Volatile
        var seen = false
    }

    /** Stays until a screen has told the user, then [reset]. */
    data object Failed : State

    private val _state = MutableStateFlow<State>(Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val running get() = _state.value is Running

    /** Starts making a PDF, unless one is being made. Call on the main thread. */
    fun start(context: Context, request: Request) {
        if (running || request.pages.isEmpty()) return
        val app = context.applicationContext
        _state.value = Running(stepText(request, 0), 0f)
        job = scope.launch { make(app, request) }
        // If Android won't start the service, the PDF is still made as long as DocNest stays open.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, PdfService::class.java)) }
    }

    /** Stops the PDF being made; the pages stay in the batch. */
    fun cancel() {
        job?.cancel()
    }

    /** Forgets a finished or failed PDF. */
    fun reset() {
        if (!running) _state.value = Idle
    }

    private fun stepText(r: Request, i: Int) =
        (if (r.searchable) "Reading page" else "Preparing page") + " ${i + 1} of ${r.pages.size}…"

    private suspend fun make(context: Context, r: Request) {
        val n = r.pages.size
        // 1-bit text needs every pixel to stay smooth, and is small anyway, so B&W ignores the size setting.
        val edge = if (r.colour == ColourMode.BW) Images.STORE_EDGE else r.quality.edge
        val dir = File(context.cacheDir, "pdf")
        var recognizer: TextRecognizer? = null
        // Keeps the processor running with the screen off: until the PDF is done, or an hour at most.
        val awake = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocNest:pdf")
                .apply { acquire(60 * 60 * 1000L) }
        }.getOrNull()
        try {
            val allText = StringBuilder()
            var unread = 0 // pages where text recognition itself failed
            // A text reader that won't start is treated like one that can't read yet: the PDF is
            // still made, without its text.
            if (r.searchable) recognizer = runCatching { Ocr.client() }.getOrNull()
            dir.deleteRecursively()
            dir.mkdirs()
            val file = File(dir, r.name)
            BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
                val pdf = PdfWriter(out, n, r.page, r.footer)
                r.pages.forEachIndexed { i, p ->
                    currentCoroutineContext().ensureActive()
                    _state.value = Running(stepText(r, i), i.toFloat() / n)
                    val bmp = Images.load(p.photo, edge, p.rot, r.colour)
                        ?: error("Page ${i + 1} could not be opened")
                    val lines = recognizer?.let { Ocr.lines(it, bmp) }
                    if (r.searchable && lines == null) unread++
                    lines?.forEach { allText.append(it.text).append('\n') }
                    if (r.colour == ColourMode.BW) {
                        pdf.addBitonalPage(Enhance.bitonal(bmp), lines.orEmpty())
                    } else {
                        pdf.addPage(Images.jpeg(bmp, r.quality.jpegQuality), bmp.width, bmp.height, lines.orEmpty())
                    }
                    bmp.recycle()
                }
                pdf.finish()
            }
            currentCoroutineContext().ensureActive()
            _state.value = Running("Saving to My documents…", 1f)
            val saved = runCatching { Library.save(context, file, allText.toString().takeIf { it.isNotBlank() }) }.getOrNull()
            if (saved != null) Batch.get(context).markSaved(r.pagesKey)
            _state.value = Done(file, r.key, saved, n, textMissing = r.searchable && unread == n)
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            _state.value = Idle
            throw e
        } catch (e: Throwable) {
            dir.deleteRecursively()
            _state.value = Failed
        } finally {
            recognizer?.close()
            runCatching { if (awake?.isHeld == true) awake.release() }
        }
    }
}
