package com.docnest.app

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Quality(val edge: Int, val jpegQuality: Int, val label: String) {
    SMALL(1280, 60, "Small"),
    STANDARD(1700, 72, "Standard"),
    HIGH(2300, 84, "High"),
}

private fun today(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

/** Today as 03.11.2026, used in the footer. */
fun dateDots() = today("dd.MM.yyyy")

fun defaultName() = "Scan_" + today("dd-MM-yyyy")

/** PDF settings, remembered between uses. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var quality: Quality
        get() = Quality.entries.firstOrNull { it.name == prefs.getString("quality", null) } ?: Quality.STANDARD
        set(v) = prefs.edit { putString("quality", v.name) }

    var grey: Boolean
        get() = prefs.getBoolean("grey", false)
        set(v) = prefs.edit { putBoolean("grey", v) }

    var page: PageSize
        get() = PageSize.entries.firstOrNull { it.name == prefs.getString("page", null) } ?: PageSize.A4
        set(v) = prefs.edit { putString("page", v.name) }

    var footerOn: Boolean
        get() = prefs.getBoolean("footerOn", false)
        set(v) = prefs.edit { putBoolean("footerOn", v) }

    var footer: String
        get() = prefs.getString("footer", "") ?: ""
        set(v) = prefs.edit { putString("footer", v) }

    // Always Scan_<date>.pdf; a second PDF on the same day becomes "Scan_<date> (1).pdf" in Downloads.
    fun fileName() = defaultName() + ".pdf"

    /** The footer line without the page number, or null when the footer is off. */
    fun footerLine(): String? {
        if (!footerOn) return null
        val t = asciiOnly(footer)
        return (if (t.isNotEmpty()) "$t   |   " else "") + dateDots()
    }

    fun summary(): String =
        quality.label + " · " + (if (grey) "B&W" else "Colour") + " · " +
            (if (page == PageSize.A4) "A4" else "Photo size") + (if (footerOn) " · Footer" else "")
}
