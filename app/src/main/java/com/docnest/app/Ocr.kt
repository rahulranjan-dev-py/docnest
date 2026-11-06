package com.docnest.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * On-device text recognition (Google ML Kit, Latin script) for searchable PDFs. The model comes
 * through Google Play services, which downloads it the first time; until then recognition fails.
 */
object Ocr {
    fun client(): TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * The lines of text on [bmp], with their boxes in [bmp]'s pixels. An empty list means no text
     * was found; null means recognition itself failed. Blocks, so call it off the main thread.
     */
    fun lines(recognizer: TextRecognizer, bmp: Bitmap): List<TextLine>? = try {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
        result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            TextLine(line.text, box.left, box.top, box.right, box.bottom)
        }
    } catch (e: Exception) {
        null
    }
}
