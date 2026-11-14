package app.dcrux.pdf

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.dcrux.data.AppJson
import app.dcrux.data.TestResult
import app.dcrux.graph
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * Hands a test-result PDF to another app as a stream. The PDF is generated at the moment
 * the receiving app reads it and written straight into a pipe; there is no file.
 * The provider is not exported: an app can read only the one URI it was explicitly shared.
 */
class PdfShareProvider : ContentProvider() {
    override fun onCreate() = true

    private fun idOf(uri: Uri): Long = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: throw FileNotFoundException("Unknown document")

    private fun load(id: Long): TestResult {
        val g = context!!.graph
        val record = runBlocking { g.db.tests().get(id) } ?: throw FileNotFoundException("No such result")
        val json = g.secure.decrypt(record.payload) ?: throw FileNotFoundException("Result cannot be read on this device")
        return AppJson.decodeFromString(json)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val result = load(idOf(uri))
        val pipe = ParcelFileDescriptor.createPipe()
        val ctx = context!!.applicationContext
        thread(name = "dcrux-pdf") {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { ResultPdf.write(ctx, result, it) } }
        }
        return pipe[0]
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) uri.lastPathSegment ?: "DCrux-test.pdf" else null })
        }
    }

    override fun getType(uri: Uri): String = "application/pdf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun share(context: Context, id: Long) {
            val uri = Uri.parse("content://app.dcrux.pdf/test/$id/DCrux-test-$id.pdf")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("DCrux test result", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share result as PDF").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}
