package com.docnest.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * The words recognised in searchable PDFs, in a full-text index (SQLite FTS4) so My documents
 * can search inside thousands of documents without reading their text into memory. One row per
 * document, under the document's [Doc.key]. Every call touches the disk: keep them off the main
 * thread.
 */
object SearchIndex {
    private const val TABLE = "doc_text"

    private class Helper(context: Context) : SQLiteOpenHelper(context, "search.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            // unicode61 matches whatever the capitals or accents: "resume" finds "Résumé".
            db.execSQL("CREATE VIRTUAL TABLE $TABLE USING fts4(doc, body, notindexed=doc, tokenize=unicode61)")
        }

        override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) = Unit
    }

    @Volatile
    private var helper: Helper? = null

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } })
            .writableDatabase

    /** Sets the text of [key]'s document, replacing what was there. Null or blank: it has none. */
    fun put(context: Context, key: String, text: String?) {
        val db = db(context)
        db.beginTransaction()
        try {
            db.delete(TABLE, "doc = ?", arrayOf(key))
            if (!text.isNullOrBlank()) {
                db.insert(TABLE, null, ContentValues().apply { put("doc", key); put("body", text) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun remove(context: Context, key: String) = put(context, key, null)

    /** The text moves with a document whose key changed. */
    fun rename(context: Context, from: String, to: String) {
        if (from == to) return
        val db = db(context)
        db.beginTransaction()
        try {
            db.delete(TABLE, "doc = ?", arrayOf(to))
            db.update(TABLE, ContentValues().apply { put("doc", to) }, "doc = ?", arrayOf(from))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Keys of the documents whose text has every word of [query]; the words may be unfinished. */
    fun search(context: Context, query: String): Set<String> {
        val match = matchQuery(query) ?: return emptySet()
        val keys = HashSet<String>()
        db(context).rawQuery("SELECT doc FROM $TABLE WHERE body MATCH ?", arrayOf(match)).use { cur ->
            while (cur.moveToNext()) keys += cur.getString(0)
        }
        return keys
    }

    /**
     * What was typed, as an FTS query: "Tax inv" becomes "tax* inv*", so every word must be there
     * and each may be the start of a longer one. Punctuation is dropped (the index splits words
     * there too) and lower case keeps AND, OR and NOT from being read as commands.
     * Null when nothing searchable was typed.
     */
    fun matchQuery(query: String): String? =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }

    /**
     * Before the index, each document's text was a file in files/text. Moves the text of [docs]
     * into the index; once the folder is empty it is removed and this does nothing more.
     */
    fun importOldFiles(context: Context, docs: List<Doc>) {
        val dir = File(context.filesDir, "text")
        if (!dir.isDirectory) return
        for (d in docs) {
            val f = File(dir, d.key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")
            if (!f.exists()) continue
            put(context, d.key, f.readText())
            f.delete()
        }
        if (dir.list().isNullOrEmpty()) dir.delete()
    }
}
