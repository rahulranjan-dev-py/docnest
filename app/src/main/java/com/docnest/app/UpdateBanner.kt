package com.docnest.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The "update available" card under the top bar. Walks through: offer (What's new / Later /
 * Update, or ✕ to skip this version) → downloading (progress, Cancel) → checking → installer.
 * A failed download offers Retry, or Open in browser as a way round.
 */
class UpdateBanner(
    private val activity: AppCompatActivity,
    private val card: View,
    private val install: (File) -> Unit,
    private val note: (text: String, long: Boolean) -> Unit,
) {
    private val title: TextView = card.findViewById(R.id.updateTitle)
    private val detail: TextView = card.findViewById(R.id.updateDetail)
    private val progress: LinearProgressIndicator = card.findViewById(R.id.updateProgress)
    private val notesBtn: MaterialButton = card.findViewById(R.id.updateNotes)
    private val secondary: MaterialButton = card.findViewById(R.id.updateSecondary)
    private val primary: MaterialButton = card.findViewById(R.id.updatePrimary)
    private val closeBtn: View = card.findViewById(R.id.updateClose)
    private val prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private var available: Updater.Release? = null
    private var snoozedTag: String? = null // "Later": hidden until DocNest is next opened
    private var checking: Job? = null
    private var downloading: Job? = null

    init {
        closeBtn.setOnClickListener {
            val r = available ?: return@setOnClickListener
            prefs.edit().putString(KEY_DISMISSED, r.tag).apply()
            render()
        }
        notesBtn.setOnClickListener { available?.let { showNotes(it) } }
    }

    /** Each time DocNest comes to the front: brings back a "Later" and checks GitHub at most once an hour. */
    fun onAppOpened() {
        snoozedTag = null
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) > CHECK_EVERY_MS) check(manual = false) else render()
    }

    /** [manual] (from Settings) also brings back a version the user skipped, and reports "up to date". */
    fun check(manual: Boolean) {
        if (checking?.isActive == true || downloading?.isActive == true) return
        checking = activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.latest() } }
            if (result.isSuccess) prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val newer = result.getOrNull()?.takeIf { it.version > Updater.installedVersion(activity) }
            available = newer
            if (manual) {
                snoozedTag = null
                prefs.edit().remove(KEY_DISMISSED).apply()
                when {
                    newer != null -> Unit // the card says it
                    result.isFailure -> note("Couldn't check for updates. Check your internet connection and try again.", true)
                    else -> note("DocNest is up to date", false)
                }
            }
            render()
        }
    }

    private fun render() {
        if (downloading?.isActive == true) return
        val r = available
        if (r == null || r.tag == prefs.getString(KEY_DISMISSED, null) || r.tag == snoozedTag) {
            card.isVisible = false
            return
        }
        card.isVisible = true
        title.text = "${r.name} is available"
        detail.isVisible = false
        progress.isVisible = false
        closeBtn.isVisible = true
        notesBtn.isVisible = r.notes.isNotBlank()
        secondary.text = "Later"
        secondary.setOnClickListener {
            snoozedTag = r.tag
            render()
        }
        primary.isVisible = true
        primary.text = "Update"
        primary.setOnClickListener { download(r) }
    }

    private fun download(r: Updater.Release) {
        downloading?.cancel()
        title.text = "Downloading ${r.name}"
        detail.isVisible = true
        detail.text = "Starting…"
        progress.isVisible = true
        progress.setProgressCompat(0, false)
        closeBtn.isVisible = false
        notesBtn.isVisible = false
        primary.isVisible = false
        secondary.text = "Cancel"
        secondary.setOnClickListener { downloading?.cancel() }

        downloading = activity.lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(
                        activity, r,
                        progress = { done, total -> activity.runOnUiThread { showProgress(done, total) } },
                        verifying = { activity.runOnUiThread { detail.text = "Checking the download…" } },
                    )
                }
                downloading = null
                render()
                install(apk)
            } catch (e: CancellationException) {
                downloading = null
                render()
                throw e
            } catch (e: Exception) {
                downloading = null
                showFailure(r, (e as? Updater.Failure)?.message ?: "Check your internet connection and try again.")
            }
        }
    }

    private fun showProgress(done: Long, total: Long) {
        if (downloading?.isActive != true) return
        if (total > 0) {
            progress.setProgressCompat((done * 1000 / total).toInt(), true)
            detail.text = String.format(Locale.US, "%.1f of %.1f MB", done / MB, total / MB)
        } else {
            detail.text = String.format(Locale.US, "%.1f MB", done / MB)
        }
    }

    private fun showFailure(r: Updater.Release, reason: String) {
        card.isVisible = true
        title.text = "${r.name} didn't download"
        detail.isVisible = true
        detail.text = reason
        progress.isVisible = false
        closeBtn.isVisible = true
        notesBtn.isVisible = false
        secondary.text = "Open in browser"
        secondary.setOnClickListener { openPage(r) }
        primary.isVisible = true
        primary.text = "Retry"
        primary.setOnClickListener { download(r) }
    }

    private fun showNotes(r: Updater.Release) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("What's New in ${r.name}")
            .setMessage(r.notes)
            .setPositiveButton("Update") { _, _ -> download(r) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openPage(r: Updater.Release) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)))
        } catch (e: ActivityNotFoundException) {
            note("No browser found to open the release page.", true)
        }
    }

    private companion object {
        const val KEY_LAST_CHECK = "lastCheck"
        const val KEY_DISMISSED = "dismissedTag"
        const val CHECK_EVERY_MS = 60 * 60 * 1000L
        const val MB = 1_048_576.0
    }
}
