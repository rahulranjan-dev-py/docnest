package com.docnest.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Settings: how new PDFs are made, how scanning works, and the app's version. Each choice is
 * kept as soon as it is made. Opened from a scan ("Change" above Save PDF) it shows only what
 * shapes that PDF.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Sent back when Check for updates is tapped: home runs the check and shows the result. */
        const val RESULT_CHECK_UPDATES = RESULT_FIRST_USER
        private const val EXTRA_PDF_ONLY = "pdfOnly"

        fun intent(context: Context, pdfOnly: Boolean = false): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PDF_ONLY, pdfOnly)
    }

    private lateinit var settings: Settings
    private lateinit var footIn: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)

        val quality = findViewById<MaterialButtonToggleGroup>(R.id.qualityGroup)
        val page = findViewById<MaterialButtonToggleGroup>(R.id.pageGroup)
        val cropOn = findViewById<MaterialSwitch>(R.id.cropOn)
        val searchOn = findViewById<MaterialSwitch>(R.id.searchOn)
        val footOn = findViewById<MaterialSwitch>(R.id.footOn)
        val footGroup = findViewById<View>(R.id.footGroup)
        footIn = findViewById(R.id.footIn)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        if (intent.getBooleanExtra(EXTRA_PDF_ONLY, false)) {
            findViewById<TextView>(R.id.titleText).text = "PDF Options"
            findViewById<TextView>(R.id.scanningLabel).text = "Text and footer"
            findViewById<View>(R.id.cropRow).isVisible = false
            findViewById<View>(R.id.aboutCard).isVisible = false
        }

        // One line under each choice says what the selected option does.
        fun choose(group: MaterialButtonToggleGroup, hint: TextView, hints: Map<Int, String>, keep: (Int) -> Unit) {
            hint.text = hints[group.checkedButtonId]
            group.addOnButtonCheckedListener { _, id, checked ->
                if (checked) {
                    hint.text = hints[id]
                    keep(id)
                }
            }
        }

        quality.check(
            when (settings.quality) {
                Quality.SMALL -> R.id.qSmall
                Quality.STANDARD -> R.id.qStandard
                Quality.HIGH -> R.id.qHigh
            }
        )
        choose(
            quality, findViewById(R.id.qualityHint), mapOf(
                R.id.qSmall to "Smallest file, good for email.",
                R.id.qStandard to "Clear text at a moderate file size.",
                R.id.qHigh to "Sharpest text, largest file.",
            )
        ) { id ->
            settings.quality = when (id) {
                R.id.qSmall -> Quality.SMALL
                R.id.qHigh -> Quality.HIGH
                else -> Quality.STANDARD
            }
        }

        page.check(if (settings.page == PageSize.A4) R.id.pA4 else R.id.pPhoto)
        choose(
            page, findViewById(R.id.pageHint), mapOf(
                R.id.pA4 to "Each photo is fitted onto an A4 page, ready to print.",
                R.id.pPhoto to "Each page is the size of its photo, with no white border.",
            )
        ) { id -> settings.page = if (id == R.id.pPhoto) PageSize.PHOTO else PageSize.A4 }

        cropOn.isChecked = settings.cropToPage
        cropOn.setOnCheckedChangeListener { _, on -> settings.cropToPage = on }
        searchOn.isChecked = settings.searchable
        searchOn.setOnCheckedChangeListener { _, on -> settings.searchable = on }
        footOn.isChecked = settings.footerOn
        footGroup.isVisible = settings.footerOn
        footOn.setOnCheckedChangeListener { _, on ->
            settings.footerOn = on
            footGroup.isVisible = on
        }
        footIn.setText(settings.footer)

        findViewById<TextView>(R.id.versionText).text = "DocNest ${Updater.versionName(this)}"
        findViewById<View>(R.id.updateBtn).setOnClickListener {
            setResult(RESULT_CHECK_UPDATES)
            finish()
        }
    }

    // The footer text is kept when the screen is left, however it is left.
    override fun onPause() {
        settings.footer = footIn.text.toString()
        super.onPause()
    }
}
