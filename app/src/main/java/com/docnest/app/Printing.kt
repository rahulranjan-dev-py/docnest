package com.docnest.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Printing a PDF from My documents, through Android's own print screen: it lists the printers
 * on the Wi-Fi and offers "Save as PDF". DocNest only hands the PDF over.
 */
object Printing {

    /** Opens the print screen for [doc]. False when this phone can't print at all. */
    fun print(activity: Activity, doc: Doc): Boolean {
        val manager = activity.getSystemService(PrintManager::class.java) ?: return false
        manager.print(doc.name.removeSuffix(".pdf"), Adapter(activity.applicationContext, doc), null)
        return true
    }

    /** Copies [doc]'s PDF, unchanged, to [out]. */
    fun copy(context: Context, doc: Doc, out: OutputStream) {
        val input = context.contentResolver.openInputStream(doc.uri) ?: throw IOException("Can't open ${doc.name}")
        input.use { it.copyTo(out) }
        out.flush()
    }

    /** The PDF is already made of pages, so there is nothing to lay out: it goes as it is. */
    private class Adapter(private val context: Context, private val doc: Doc) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(doc.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            // Paper size and the like don't change the PDF, so only the first layout is news.
            callback.onLayoutFinished(info, oldAttributes == null)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            // Copied off the main thread; the print screen waits for the callback. The print
            // service owns the file descriptor, so it is flushed here but not closed.
            Thread {
                try {
                    copy(context, doc, FileOutputStream(destination.fileDescriptor))
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                    } else {
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    }
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }.start()
        }
    }
}
