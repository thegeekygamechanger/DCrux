package app.dcrux.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import app.dcrux.R
import app.dcrux.data.Dates
import app.dcrux.data.TestResult
import java.io.OutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lays a test result out as A4 pages. The same drawing commands produce the PDF that is
 * shared and the pages previewed in the app, and both are made on demand in memory:
 * no PDF is ever written to the phone's storage.
 */
object ResultPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    private class Fonts(context: Context) {
        val regular: Typeface = ResourcesCompat.getFont(context, R.font.inter_regular) ?: Typeface.DEFAULT
        val semi: Typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
        val head: Typeface = ResourcesCompat.getFont(context, R.font.heros_bold) ?: Typeface.DEFAULT_BOLD
    }

    private fun paint(tf: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf; textSize = size; this.color = color
    }

    private fun star(cx: Float, cy: Float, r: Float): Path = Path().apply {
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val rad = if (i % 2 == 0) r else r * 0.45f
            val x = cx + (rad * cos(a)).toFloat()
            val y = cy + (rad * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun layout(context: Context, r: TestResult): List<List<(Canvas) -> Unit>> {
        val f = Fonts(context)
        val ink = Color.rgb(28, 27, 32)
        val soft = Color.rgb(96, 92, 104)
        val pages = mutableListOf<MutableList<(Canvas) -> Unit>>(mutableListOf())
        var y = 0f

        fun newPage() { pages += mutableListOf<(Canvas) -> Unit>(); y = M }

        class Piece(val sl: StaticLayout, val indent: Float, val after: Float, val bg: Int?)

        fun piece(s: String, p: TextPaint, indent: Float = 0f, after: Float = 6f, bg: Int? = null): Piece? {
            if (s.isBlank()) return null
            val width = (W - 2 * M - indent).toInt()
            val sl = StaticLayout.Builder.obtain(s, 0, s.length, p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()
            return Piece(sl, indent, after, bg)
        }

        fun place(pc: Piece) {
            if (y + pc.sl.height > H - M - 24) newPage()
            val top = y
            val x = M + pc.indent
            val width = pc.sl.width
            pages.last() += { c ->
                if (pc.bg != null) c.drawRoundRect(RectF(x - 6, top - 3, x + width + 6, top + pc.sl.height + 3), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pc.bg })
                c.save(); c.translate(x, top); pc.sl.draw(c); c.restore()
            }
            y += pc.sl.height + pc.after
        }

        fun text(s: String, p: TextPaint, indent: Float = 0f, after: Float = 6f, bg: Int? = null) {
            piece(s, p, indent, after, bg)?.let(::place)
        }

        /** Places the pieces together: a block that fits on a page is never split across two. */
        fun block(pieces: List<Piece?>) {
            val list = pieces.filterNotNull()
            val total = list.sumOf { (it.sl.height + it.after).toDouble() }.toFloat()
            if (y + total > H - M - 24 && total < H - 2 * M - 40) newPage()
            list.forEach(::place)
        }

        // Header band on the first page.
        pages.last() += { c ->
            val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, W.toFloat(), 130f,
                    intArrayOf(Color.rgb(46, 58, 158), Color.rgb(69, 83, 201), Color.rgb(106, 120, 224)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(0f, 0f, W.toFloat(), 130f, band)
            c.drawText("DCrux", M, 52f, paint(f.head, 30f, Color.WHITE))
            c.drawText(if (r.isMains) "Mains answer evaluation" else "Daily current affairs test", M, 74f, paint(f.regular, 12f, Color.WHITE))
            val score = r.scoreText.replace("/", " / ")
            val scorePaint = paint(f.head, 34f, Color.WHITE)
            c.drawText(score, W - M - scorePaint.measureText(score), 60f, scorePaint)
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until 5) {
                starPaint.color = if (i < r.stars) Color.rgb(255, 226, 122) else Color.argb(90, 255, 255, 255)
                c.drawPath(star(W - M - 110f + i * 24f, 88f, 10f), starPaint)
            }
        }
        y = 150f
        text(r.title, paint(f.head, 18f, ink), after = 4f)
        val pct = r.percent
        text(
            "${r.exam}  ·  ${r.difficulty}  ·  News of ${Dates.prettyDay(r.dayKey)}  ·  Taken ${Dates.dateTime(r.takenAt)}  ·  " +
                "$pct%  ·  ${r.durationSec / 60} min ${r.durationSec % 60} s",
            paint(f.regular, 10f, soft), after = 16f,
        )

        val qPaint = paint(f.semi, 12f, ink)
        val oPaint = paint(f.regular, 11f, ink)
        val ePaint = paint(f.regular, 10f, soft)
        val hPaint = paint(f.semi, 10.5f, Color.rgb(46, 58, 158))
        r.mains.forEachIndexed { i, a ->
            val q = a.question
            val e = a.eval
            block(listOf(
                piece("Q${i + 1}.  ${q.q}", qPaint, after = 4f),
                piece(listOf("${q.marks} marks", "${q.words} words", q.paper, q.topic, if (q.relevance >= 0) "relevance ${q.relevance}/100" else "").filter { it.isNotBlank() }.joinToString("  ·  "), ePaint, after = 6f),
            ))
            if (e == null) {
                text("${a.note.ifBlank { "Not attempted" }}. 0 of ${q.marks} marks.", oPaint, indent = 14f, after = 10f)
            } else {
                val took = if (a.seconds > 0) "   ·   written in ${a.seconds / 60} min ${a.seconds % 60} s" else ""
                text("Marks: ${app.dcrux.data.fmtMarks(e.marks)} / ${q.marks}$took   ·   about ${e.wordCount} words   ·   ${e.legibility}% of the handwriting read", paint(f.semi, 11f, ink), indent = 14f, after = 6f)
                if (!e.isAnswer) text(e.problem, oPaint, indent = 14f)
                text(e.verdict, oPaint, indent = 14f, after = 8f)
                e.breakdown.forEach { c ->
                    text("${c.name}: ${app.dcrux.data.fmtMarks(c.score)} / ${app.dcrux.data.fmtMarks(c.outOf)}" + if (c.comment.isNotBlank()) "  —  ${c.comment}" else "", ePaint, indent = 14f, after = 4f)
                }
                if (e.marked.isNotEmpty()) {
                    y += 4f
                    text("Marked copy", hPaint, indent = 14f, after = 4f)
                    var lastPage = 0
                    e.marked.forEach { m ->
                        if (m.page != lastPage) { lastPage = m.page; text("Page ${m.page}", ePaint, indent = 22f, after = 3f) }
                        val (sign, color) = when (m.mark) {
                            "good" -> "✓" to Color.rgb(0, 110, 60)
                            "wrong" -> "✗" to Color.rgb(170, 30, 30)
                            "weak" -> "–" to soft
                            else -> "~" to Color.rgb(150, 100, 0)
                        }
                        block(listOf(
                            piece((if (m.section.isNotBlank()) "${m.section}:  " else "") + m.text, oPaint, indent = 22f, after = 2f),
                            piece("$sign  ${m.remark}", paint(f.semi, 10.5f, color), indent = 30f, after = 6f),
                        ))
                    }
                }
                fun list(title: String, items: List<String>, numbered: Boolean = false) {
                    if (items.isEmpty()) return
                    y += 4f
                    text(title, hPaint, indent = 14f, after = 3f)
                    items.forEachIndexed { n, t -> text((if (numbered) "${n + 1}.  " else "•  ") + t, oPaint, indent = 22f, after = 3f) }
                }
                list("Fix these first", e.improvements, numbered = true)
                list("Mistakes to correct", e.errors.map { "You wrote “${it.written}”: ${it.issue}" })
                list("What a top answer adds", e.missing)
                list("What worked", e.strengths)
                list("Structure", listOfNotNull(e.structure.takeIf { it.isNotBlank() }))
                list("How a topper would lay it out", e.outline, numbered = true)
                list("Time", listOfNotNull(e.timeAdvice.takeIf { it.isNotBlank() }))
                list("In the exam hall", e.examTips)
                y += 4f
                text("What Crux AI read from the pages", hPaint, indent = 14f, after = 3f)
                text(e.transcript, ePaint, indent = 22f, after = 4f)
            }
            y += 14f
        }
        r.questions.forEachIndexed { i, q ->
            val given = r.answers.getOrElse(i) { -1 }
            val yours = if (given in q.options.indices) "${'A' + given}" else "not answered"
            val verdict = paint(f.semi, 10f, if (given == q.answer) Color.rgb(0, 110, 60) else Color.rgb(170, 30, 30))
            block(
                listOf(piece("Q${i + 1}.  ${q.q}", qPaint, after = 6f)) +
                    q.options.mapIndexed { j, o ->
                        val mark = when {
                            j == q.answer -> Color.rgb(205, 242, 219)
                            j == given -> Color.rgb(255, 218, 214)
                            else -> null
                        }
                        piece("${'A' + j})  $o", oPaint, indent = 14f, after = 5f, bg = mark)
                    } +
                    listOf(
                        piece("Your answer: $yours   ·   Correct: ${'A' + q.answer}" + if (q.topic.isNotBlank()) "   ·   ${q.topic}" else "", verdict, indent = 14f, after = 4f),
                        piece(q.explanation, ePaint, indent = 14f, after = 3f),
                        piece(if (q.sourceTitle.isNotBlank()) "Source: ${q.sourceTitle}" else "", ePaint, indent = 14f, after = 3f),
                    )
            )
            y += 12f
        }

        val total = pages.size
        pages.forEachIndexed { i, ops ->
            ops += { c ->
                c.drawText(
                    if (r.isMains) "Made with DCrux · read and marked by Crux AI as an estimate to learn from; not an official score"
                    else "Made with DCrux · questions written by Crux AI from the day's headlines; verify before relying on them",
                    M, H - 22f, paint(f.regular, 8f, soft),
                )
                c.drawText("${i + 1} / $total", W - M - 24f, H - 22f, paint(f.regular, 8f, soft))
            }
        }
        return pages
    }

    /** Streams the PDF to [out]; the caller decides where the bytes go (a share pipe). */
    fun write(context: Context, r: TestResult, out: OutputStream) {
        val doc = PdfDocument()
        try {
            layout(context, r).forEachIndexed { i, ops ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, i + 1).create())
                ops.forEach { it(page.canvas) }
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** The same pages as bitmaps, for the in-app viewer. */
    fun bitmaps(context: Context, r: TestResult, scale: Float = 2f): List<Bitmap> =
        layout(context, r).map { ops ->
            Bitmap.createBitmap((W * scale).toInt(), (H * scale).toInt(), Bitmap.Config.ARGB_8888).also { bmp ->
                val c = Canvas(bmp)
                c.drawColor(Color.WHITE)
                c.scale(scale, scale)
                ops.forEach { it(c) }
            }
        }
}
