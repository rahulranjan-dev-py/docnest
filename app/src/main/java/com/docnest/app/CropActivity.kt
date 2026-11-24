package com.docnest.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Crops one page of the scan: drag the four corners to the edges of the page, and Done cuts the
 * page out and straightens it. "Whole photo" undoes a crop. The photo itself is never changed,
 * so a page can be cropped again, or uncropped, at any time before it is saved.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val SHOW_EDGE = 1600
        private const val HINT = "Drag the corners to the edges of the page."

        fun intent(context: Context, photo: Photo): Intent =
            Intent(context, CropActivity::class.java).putExtra(EXTRA_ID, photo.id)
    }

    private lateinit var batch: Batch
    private lateinit var cropView: CropView
    private lateinit var hint: TextView
    private lateinit var applyBtn: View
    private var photo: Photo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        batch = Batch.get(this)
        val p = batch.items.firstOrNull { it.id == intent.getStringExtra(EXTRA_ID) }
        if (p == null) {
            finish()
            return
        }
        photo = p
        cropView = findViewById(R.id.cropView)
        hint = findViewById(R.id.cropHint)
        applyBtn = findViewById(R.id.applyBtn)
        findViewById<TextView>(R.id.cropTitle).text = "Crop Page ${batch.items.indexOf(p) + 1}"

        // Shown the way the page is turned; kept the way the photo is stored.
        cropView.setQuad(Crop.toTurned(p.crop ?: Crop.WHOLE, p.rot))
        cropView.onChange = { hint.text = HINT }
        findViewById<View>(R.id.closeBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.wholeBtn).setOnClickListener { cropView.setQuad(Crop.WHOLE) }
        applyBtn.setOnClickListener { apply() }

        lifecycleScope.launch {
            val shown = withContext(Dispatchers.IO) {
                runCatching { Images.load(batch.photoFile(p), SHOW_EDGE, p.rot) }.getOrNull()
            }
            if (shown == null) {
                Toast.makeText(this@CropActivity, "This page's photo couldn't be opened.", Toast.LENGTH_LONG).show()
                finish()
            } else {
                cropView.bitmap = shown
            }
        }
    }

    private fun apply() {
        val p = photo ?: return
        val outline = Crop.tidy(cropView.quad)
        if (outline == null) {
            hint.text = "Move the corners so they go round the page, a corner at each of its corners."
            return
        }
        val crop = Crop.fromTurned(outline, p.rot).takeUnless { Crop.isWhole(it) }
        if (Crop.tag(crop) == Crop.tag(p.crop)) {
            finish()
            return
        }
        applyBtn.isEnabled = false
        lifecycleScope.launch {
            // The page's small picture is made again first, so the page card is right straight away.
            val made = withContext(Dispatchers.IO) {
                runCatching { Images.makeThumb(batch.photoFile(p), crop, batch.thumbFile(p)) }.getOrNull()
            }
            if (made == null) {
                applyBtn.isEnabled = true
                hint.text = "The page couldn't be cropped. Try again."
                return@launch
            }
            p.crop = crop
            batch.save()
            setResult(RESULT_OK)
            finish()
        }
    }
}
