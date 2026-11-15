package app.dcrux.mains

import app.dcrux.PageTools
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** A computer: answer pages are pictures or PDFs chosen from the disk (a phone photo sent across, or a scanner's output). */
class DesktopPages : PageTools {
    override fun captureTarget(file: File): Any = file
    override fun adoptCapture(raw: File, dir: File): String? { raw.delete(); return null }

    override fun importPages(source: Any, dir: File, room: Int): List<String> {
        val file = source as? File ?: return emptyList()
        return if (file.name.lowercase().endsWith(".pdf")) {
            Loader.loadPDF(file).use { doc ->
                val renderer = PDFRenderer(doc)
                (0 until minOf(doc.numberOfPages, room)).mapNotNull { i ->
                    val box = doc.getPage(i).mediaBox
                    val scale = MainsStore.MAX_EDGE / maxOf(box.width, box.height)
                    save(renderer.renderImage(i, scale), dir)
                }
            }
        } else listOfNotNull(ImageIO.read(file)?.let { save(shrink(it), dir) })
    }

    /** No larger than needed: the long edge at [MainsStore.MAX_EDGE] or less, on a white ground (JPEG has no transparency). */
    private fun shrink(img: BufferedImage): BufferedImage {
        val scale = minOf(1.0, MainsStore.MAX_EDGE.toDouble() / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
            out.createGraphics().apply {
                color = Color.WHITE; fillRect(0, 0, w, h)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(img, 0, 0, w, h, null); dispose()
            }
        }
    }

    private fun save(img: BufferedImage, dir: File): String? = runCatching {
        val rgb = if (img.type == BufferedImage.TYPE_INT_RGB) img else shrink(img)
        val name = "p_${System.nanoTime()}.jpg"
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        File(dir, name).outputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(rgb, null, null), writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.8f })
            }
        }
        writer.dispose()
        name
    }.getOrNull()
}
