package com.docnest.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executors

/**
 * Shoots photo after photo with no confirm screen in between.
 * Each shot is saved into the scan in the background while the next one is taken.
 * Opened with [retakeIntent], it takes one photo to replace a page, then closes.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ADDED = "added"

        /** After a retake: the new photo's id. */
        const val EXTRA_NEW_ID = "newId"
        private const val EXTRA_REPLACE = "replace"
        private const val EXTRA_PAGE = "page"

        /** Takes one photo to put in place of [photo], which is page [page] of the scan. */
        fun retakeIntent(context: Context, photo: Photo, page: Int): Intent =
            Intent(context, CameraActivity::class.java).putExtra(EXTRA_REPLACE, photo.id).putExtra(EXTRA_PAGE, page)
    }

    private lateinit var batch: Batch
    private lateinit var preview: PreviewView
    private lateinit var flash: View
    private lateinit var countText: TextView
    private lateinit var status: TextView
    private lateinit var lastThumb: ImageView
    private lateinit var shutter: View
    private lateinit var torchBtn: MaterialButton
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    // Saves one photo at a time, in the order they were taken.
    private val saver = Executors.newSingleThreadExecutor()
    private var pending = 0
    private var added = 0
    private var closing = false
    private var torchOn = false

    // Retaking one page: its photo's id, and its page number for the labels.
    private var replaceId: String? = null
    private var replacePage = 0

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Allow camera access to scan pages.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // The screen stays upright, so the motion sensor tells the camera which way up each photo is.
    // Held flat over a desk, the last known direction is kept.
    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        batch = Batch.get(this)

        preview = findViewById(R.id.preview)
        flash = findViewById(R.id.flash)
        countText = findViewById(R.id.countText)
        status = findViewById(R.id.status)
        lastThumb = findViewById(R.id.lastThumb)
        shutter = findViewById(R.id.shutter)
        torchBtn = findViewById(R.id.torchBtn)

        replaceId = intent.getStringExtra(EXTRA_REPLACE)
        replacePage = intent.getIntExtra(EXTRA_PAGE, 0)
        if (replaceId != null) {
            findViewById<View>(R.id.doneBtn).visibility = View.INVISIBLE
            status.text = "Take the new photo of page $replacePage"
        }

        shutter.isEnabled = false
        shutter.setOnClickListener { shoot() }
        findViewById<View>(R.id.doneBtn).setOnClickListener { close() }
        findViewById<View>(R.id.closeBtn).setOnClickListener { close() }
        torchBtn.setOnClickListener { setTorch(!torchOn) }
        preview.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                focusAt(e.x, e.y)
                v.performClick()
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
        updateCount()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        orientation.enable()
    }

    override fun onStop() {
        orientation.disable()
        super.onStop()
    }

    override fun onDestroy() {
        saver.shutdown()
        super.onDestroy()
    }

    // The volume buttons also take a photo, for one-handed shooting.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // 4:3 at about 8 MP: plenty for newsprint, since photos are kept at 2300 px anyway.
            val captureSize = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val previewSize = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val pv = Preview.Builder().setResolutionSelector(previewSize).build()
            pv.setSurfaceProvider(preview.surfaceProvider)
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(captureSize)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                imageCapture = ic
                shutter.isEnabled = !closing
            } catch (e: Exception) {
                status.text = "The camera couldn't start. Close DocNest and open it again."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
        torchBtn.contentDescription = if (on) "Turn light off" else "Turn light on"
        torchBtn.backgroundTintList = ColorStateList.valueOf(if (on) Color.WHITE else Color.TRANSPARENT)
        torchBtn.iconTint = ColorStateList.valueOf(if (on) Color.BLACK else Color.WHITE)
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = preview.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    private fun shoot() {
        val ic = imageCapture ?: return
        if (closing) return
        // A retake is one photo: the next shot waits until this one has failed.
        if (replaceId != null && pending > 0) return
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val p = batch.newPhoto()
        val shot = File(cacheDir, "shot_${p.id}.jpg")
        pending++
        updateCount()
        flash.alpha = 0.7f
        flash.animate().alpha(0f).setDuration(180).start()

        val options = ImageCapture.OutputFileOptions.Builder(shot).build()
        ic.takePicture(options, saver, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // On the saver thread: make it upright, shrink it and add it to the batch.
                val exif = runCatching { shot.inputStream().use { Images.readExif(it) } }
                    .getOrElse { Images.Exif(0, 0L) }
                val stored = runCatching {
                    Images.store({ shot.inputStream() }, exif.degrees, batch.photoFile(p), batch.thumbFile(p))
                }.getOrNull()
                shot.delete()
                runOnUiThread { saved(p, stored) }
            }

            override fun onError(exception: ImageCaptureException) {
                shot.delete()
                runOnUiThread { saved(p, null) }
            }
        })
    }

    private fun saved(p: Photo, stored: Images.Stored?) {
        pending--
        val replacing = replaceId
        if (replacing != null && stored != null) {
            p.blurry = stored.blurry
            val old = batch.items.firstOrNull { it.id == replacing }
            if (old != null) {
                batch.replace(old, p)
            } else {
                batch.items.add(p)
                batch.save()
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_NEW_ID, p.id))
            finish()
            return
        }
        if (stored != null) {
            p.blurry = stored.blurry
            batch.items.add(p)
            batch.save()
            added++
            lastThumb.setImageBitmap(stored.thumb)
            status.text = if (stored.blurry) {
                "Page ${batch.items.size} may be blurry. Hold steady and take it again."
            } else {
                "Keep going, or tap Done when you're finished"
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ADDED, added))
        } else {
            batch.deleteFiles(p)
            status.text = "That photo didn't save. Take it again."
        }
        updateCount()
        if (closing && pending == 0) finish()
    }

    private fun updateCount() {
        if (replaceId != null) {
            countText.text = "Retake page $replacePage" + if (pending > 0) ", saving" else ""
            return
        }
        val n = batch.items.size
        countText.text = pages(n) + if (pending > 0) ", saving $pending" else ""
    }

    // Waits for photos still being saved, then goes back to the batch.
    private fun close() {
        if (pending == 0) {
            finish()
            return
        }
        closing = true
        shutter.isEnabled = false
        status.text = "Saving the last photos…"
    }
}
