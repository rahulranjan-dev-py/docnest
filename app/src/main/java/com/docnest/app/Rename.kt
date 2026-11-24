package com.docnest.app

import android.content.DialogInterface
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks for a new name for [doc], then renames it. The name is checked as it is typed, and the
 * Rename button only works for a name that can be used. [done] gets the renamed document;
 * [failed] a message to show when it couldn't be renamed.
 */
fun AppCompatActivity.askRename(doc: Doc, failed: (String) -> Unit, done: (Doc) -> Unit) {
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
        if (name == current) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Library.rename(this@askRename, doc, name) } }
            result.onSuccess(done).onFailure {
                failed((it as? Library.Failure)?.message ?: "Couldn't rename ${doc.name}. Try again.")
            }
        }
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
