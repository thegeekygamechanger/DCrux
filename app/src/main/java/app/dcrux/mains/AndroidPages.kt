package app.dcrux.mains

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import app.dcrux.PageTools
import java.io.File
import java.io.InputStream

/** Android: pages come from the camera (through a FileProvider) or from documents the user picks. */
class AndroidPages(private val context: Context) : PageTools {
    override fun captureTarget(file: File): Any = FileProvider.getUriForFile(context, "${context.packageName}.capture", file)

    override fun adoptCapture(raw: File, dir: File): String? {
        if (!raw.exists()) return null
        val name = if (raw.length() > 0) savePage(shrink { raw.inputStream() }, dir) else null
        raw.delete()
        return name
    }

    override fun importPages(source: Any, dir: File, room: Int): List<String> {
        val uri = source as? Uri ?: return emptyList()
        val type = context.contentResolver.getType(uri).orEmpty()
        return if (type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) pdfPages(uri, dir, room)
        else listOfNotNull(savePage(shrink { context.contentResolver.openInputStream(uri) }, dir))
    }

    private fun pdfPages(uri: Uri, dir: File, limit: Int): List<String> {
        // PdfRenderer needs a seekable file, which not every document provider gives; copy it first.
        val tmp = File(dir, "import_${System.nanoTime()}.pdf")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return emptyList()
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    (0 until minOf(pdf.pageCount, limit)).mapNotNull { i ->
                        pdf.openPage(i).use { page ->
                            val scale = MainsStore.MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            savePage(bmp, dir)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            tmp.delete()
        }
    }

    /** Decodes a picture no larger than needed, upright, with its long edge at [MainsStore.MAX_EDGE] or less. */
    private fun shrink(open: () -> InputStream?): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MainsStore.MAX_EDGE) sample *= 2
        val bmp = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
        val turn = runCatching {
            open()?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val scale = minOf(1f, MainsStore.MAX_EDGE.toFloat() / maxOf(bmp.width, bmp.height))
        if (turn == 0f && scale == 1f) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postScale(scale, scale); postRotate(turn) }, true)
    }.getOrNull()

    private fun savePage(bmp: Bitmap?, dir: File): String? {
        bmp ?: return null
        val name = "p_${System.nanoTime()}.jpg"
        return runCatching {
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            name
        }.getOrNull()
    }
}
