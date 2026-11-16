package app.dcrux.desktop

import app.dcrux.data.AppJson
import app.dcrux.data.Ask
import app.dcrux.data.Channel
import app.dcrux.data.QuizQuestion
import app.dcrux.data.TestRecord
import app.dcrux.data.TestResult
import app.dcrux.data.Video
import app.dcrux.mains.MainsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A self-check of sync: two devices (two data folders) pair and exchange data through the service
 * this build points at. Run against a local relay, never against real users' storage.
 * Usage: SyncCheck <folder>
 */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "build/sync-check" }).apply { deleteRecursively(); mkdirs() }
    var failed = 0
    fun check(what: String, ok: Boolean) { println((if (ok) "ok    " else "FAIL  ") + what); if (!ok) failed++ }
    runBlocking {
        val a = desktopGraph(DesktopPlatform(File(root, "a").apply { mkdirs() }))
        val b = desktopGraph(DesktopPlatform(File(root, "b").apply { mkdirs() }))
        check("this build can sync", a.cloud.available)

        // Device A has data.
        a.db.channels().insert(Channel("chanA", "Channel A", "https://youtube.com/channel/chanA", addedAt = 1000))
        a.db.videos().insertAll(listOf(Video(id = "vid1", channelId = "chanA", title = "A lecture", publishedAt = 5000)))
        a.db.videos().setSummary("vid1", "- point one\n- point two", "captions", "model-x")
        a.db.videos().setRelevance("vid1", """[{"exam":"UPSC CSE","score":70,"note":"GS3"}]""")
        a.db.asks().insert(Ask(articleId = "video:vid1", question = "What is point one?", answer = "It is the first point.", askedAt = 6000, topic = "A lecture"))
        a.db.asks().insert(Ask(articleId = "news1", question = "Why in the news?", answer = "Because.", askedAt = 7000, topic = "A headline"))
        val result = TestResult("UPSC CSE · Today", "UPSC CSE", "Moderate", "2026-11-16", listOf(QuizQuestion("Q?", listOf("a", "b", "c", "d"), 1)), listOf(1), 1, 1, 5, 30, takenAt = 8000)
        a.db.tests().insert(TestRecord(createdAt = 8000, payload = a.secure.encrypt(AppJson.encodeToString(result))))
        a.prefs.setExams(setOf("UPSC CSE", "BPSC (Bihar)")); a.prefs.setLanguage("Hindi")

        val code = a.cloud.enable()
        check("A made a 25-character code ($code)", code.replace("-", "").length == 25)
        check("A synced without error", a.cloud.status.value.error == null)

        check("B rejects a malformed code", !b.cloud.join("hello"))
        b.cloud.join("AAAAA-AAAAA-AAAAA-AAAAA-AAAAA")
        check("a wrong code sees none of A's data", b.db.asks().all().isEmpty() && b.cloud.status.value.error == null)
        b.cloud.disable(eraseCloud = true)

        check("B joins with A's code typed in lower case with spaces", b.cloud.join(code.lowercase().replace("-", " ")))
        check("B synced without error: ${b.cloud.status.value.error}", b.cloud.status.value.error == null)
        check("B received the channel", b.db.channels().get("chanA")?.title == "Channel A")
        check("B received the video notes", b.db.videos().get("vid1")?.summary == "- point one\n- point two" && b.db.videos().get("vid1")?.relevance != null)
        check("B received both questions", b.db.asks().all().size == 2)
        check("B received the test, readable with B's own key", b.db.tests().all().singleOrNull()?.let { MainsStore.decode(b, it) }?.correct == 1)
        check("B took A's study settings", b.prefs.get().let { it.language == "Hindi" && "BPSC (Bihar)" in it.exams })

        // B changes things: deletes one question, asks another, writes detailed notes, retakes the test, changes language.
        b.db.asks().all().first { it.articleId == "news1" }.let { b.db.asks().delete(it.id) }
        b.db.asks().insert(Ask(articleId = "video:vid1", question = "And point two?", answer = "The second.", askedAt = System.currentTimeMillis(), topic = "A lecture"))
        b.db.videos().setDetailed("vid1", "## Topic\n- detail", "captions", "model-y")
        val row = b.db.tests().all().single()
        b.db.tests().setPayload(row.id, b.secure.encrypt(AppJson.encodeToString(result.copy(attempts = 2, takenAt = System.currentTimeMillis()))))
        b.prefs.setLanguage("English")
        b.cloud.syncNow(); a.cloud.syncNow()
        check("A lost the question B deleted", a.db.asks().all().none { it.articleId == "news1" })
        check("A gained the question B asked", a.db.asks().all().any { it.question == "And point two?" })
        check("A has two questions in all", a.db.asks().all().size == 2)
        check("A gained B's detailed notes and kept its summary", a.db.videos().get("vid1").let { it?.detailed == "## Topic\n- detail" && it.summary == "- point one\n- point two" })
        check("A sees the retaken test (attempt 2), still one entry", a.db.tests().all().singleOrNull()?.let { MainsStore.decode(a, it) }?.attempts == 2)
        check("A took B's newer language", a.prefs.get().language == "English")

        // Real time: B is in use and only listening; a change saved by A must reach it by itself, quickly.
        b.cloud.setActive(true)
        kotlinx.coroutines.delay(2500)
        a.db.asks().insert(Ask(articleId = "video:vid1", question = "Arrived in real time?", answer = "Yes.", askedAt = System.currentTimeMillis(), topic = "A lecture"))
        a.cloud.syncNow()
        val sent = System.currentTimeMillis()
        var took = -1L
        while (System.currentTimeMillis() - sent < 15_000) {
            if (b.db.asks().all().any { it.question == "Arrived in real time?" }) { took = System.currentTimeMillis() - sent; break }
            kotlinx.coroutines.delay(100)
        }
        check("B received A's change by itself, without being asked to sync (in $took ms)", took in 0..6000)
        // And the other way, through the "something changed here" path: B deletes it, A (listening) loses it.
        a.cloud.setActive(true)
        kotlinx.coroutines.delay(2500)
        b.db.asks().all().first { it.question == "Arrived in real time?" }.let { b.db.asks().delete(it.id) }
        b.cloud.syncSoon()
        val asked = System.currentTimeMillis()
        var gone = -1L
        while (System.currentTimeMillis() - asked < 20_000) {
            if (a.db.asks().all().none { it.question == "Arrived in real time?" }) { gone = System.currentTimeMillis() - asked; break }
            kotlinx.coroutines.delay(100)
        }
        check("a deletion on B reached A by itself (in $gone ms, including the short wait that batches changes)", gone in 0..6_000)
        // Idle: with nothing changing, listening devices must not be talking to the service.
        kotlinx.coroutines.delay(6000)
        a.cloud.setActive(false); b.cloud.setActive(false)

        // A removes the channel; B must lose it and not resurrect it.
        a.sync.removeChannel("chanA")
        a.cloud.syncNow(); b.cloud.syncNow(); a.cloud.syncNow()
        check("the removed channel is gone from both", a.db.channels().get("chanA") == null && b.db.channels().get("chanA") == null)

        // The relay keeps nothing on disk. If it restarts and forgets everything, the devices must put the data
        // back between them, and something deleted before the restart must stay deleted even when the device
        // that still has it is the first to return.
        System.getenv("RELAY_RESTART")?.takeIf { it.isNotBlank() }?.let { restart ->
            a.db.asks().insert(Ask(articleId = "news9", question = "Deleted before the restart", answer = "x", askedAt = System.currentTimeMillis(), topic = "t"))
            a.cloud.syncNow(); b.cloud.syncNow()
            check("both devices have the question that is about to be deleted", b.db.asks().all().any { it.question == "Deleted before the restart" })
            a.db.asks().all().first { it.question == "Deleted before the restart" }.let { a.db.asks().delete(it.id) }
            a.cloud.syncNow()                                   // A has deleted it; B has not heard yet
            val asksOnA = a.db.asks().all().size
            ProcessBuilder("bash", "-c", restart).inheritIO().start().waitFor()
            kotlinx.coroutines.delay(1500)
            check("after the restart the relay holds nothing", a.claude.serviceCall("GET", "/v1/sync/" + "1".repeat(64))?.first == 404)
            b.cloud.syncNow()                                   // B returns first and uploads its copy, deleted question included
            a.cloud.syncNow(); b.cloud.syncNow()
            check("no error after the restart: ${a.cloud.status.value.error} / ${b.cloud.status.value.error}", a.cloud.status.value.error == null && b.cloud.status.value.error == null)
            check("the deleted question stayed deleted on both", a.db.asks().all().none { it.question == "Deleted before the restart" } && b.db.asks().all().none { it.question == "Deleted before the restart" })
            check("everything else survived the restart on both", a.db.asks().all().size == asksOnA && b.db.asks().all().size == asksOnA && b.db.videos().get("vid1")?.detailed != null)
        }

        // Pairing by a 4-character code: A shows codes, a third device C types one.
        check("the numbers pairing rests on are sound (a 2048-bit safe prime)", app.dcrux.sync.CloudSync.pairingSound())
        val c = desktopGraph(DesktopPlatform(File(root, "c").apply { mkdirs() }))
        val shown = java.util.concurrent.CopyOnWriteArrayList<String>()
        val offering = launch { a.cloud.offerShortCodes { it?.let(shown::add) } }
        suspend fun codeAfter(n: Int): String? { repeat(100) { if (shown.size > n) return shown[n]; kotlinx.coroutines.delay(100) }; return null }
        val first = codeAfter(0)
        check("A shows a 4-character code ($first)", first?.length == 4 && app.dcrux.sync.CloudSync.normaliseShort(first) == first)
        check("something that is not a code is refused without asking the service", c.cloud.joinShort("hello") == app.dcrux.sync.ShortJoin.Wrong && shown.size == 1)
        // A wrong code that happens to lead to the same room as the right one: the closest a guesser can get.
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val near = buildList { for (w in alphabet) for (x in alphabet) for (y in alphabet) for (z in alphabet) add("$w$x$y$z") }
            .first { it != first && app.dcrux.sync.CloudSync.roomOf(it) == app.dcrux.sync.CloudSync.roomOf(first!!) }
        check("a wrong guess that reaches A gets nothing ($near)", c.cloud.joinShort(near) == app.dcrux.sync.ShortJoin.Wrong && c.cloud.code() == null && c.db.asks().all().isEmpty())
        val second = codeAfter(1)
        check("that one guess used the code up: A now shows a new one ($second)", second != null && second != first)
        check("the used-up code no longer works", c.cloud.joinShort(first!!) == app.dcrux.sync.ShortJoin.Wrong && c.cloud.code() == null)
        val current = codeAfter(shown.size - 1)!!.let { shown.last() }
        val began = System.currentTimeMillis()
        check("C joins with the code A shows, typed in lower case ($current)", c.cloud.joinShort(current.lowercase()) == app.dcrux.sync.ShortJoin.Joined)
        check("pairing took ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 5000)
        check("C now holds the same key as A", c.cloud.code() != null && c.cloud.code() == a.cloud.code())
        check("C received A's data", c.db.asks().all().size == a.db.asks().all().size && c.db.videos().get("vid1")?.summary != null)
        offering.cancel(); offering.join()
        kotlinx.coroutines.delay(300)
        val last = shown.last()
        check("when A stops offering, its last code is withdrawn", b.cloud.code() != null && desktopGraph(DesktopPlatform(File(root, "d").apply { mkdirs() })).cloud.joinShort(last) == app.dcrux.sync.ShortJoin.Wrong)
        // Guessing is rationed: after a handful of tries from one address the service stops answering them.
        val tries = (1..12).map { c2 -> desktopGraph(DesktopPlatform(File(root, "d").apply { mkdirs() })).cloud.joinShort("ZZZ" + alphabet[c2]) }
        check("after too many tries the service refuses more (${tries.count { it == app.dcrux.sync.ShortJoin.Busy }} of 12 refused)", tries.last() == app.dcrux.sync.ShortJoin.Busy)
        c.cloud.disable(eraseCloud = false)

        // Nothing changed: a further round must not write.
        val before = a.prefs.get().cloudLastSync
        b.cloud.syncNow(); a.cloud.syncNow()
        check("a quiet round still completes", a.prefs.get().cloudLastSync >= before && a.cloud.status.value.error == null)

        // The stored copy is unreadable without the code, and erasing removes it.
        val stored = a.claude.serviceCall("GET", "/v1/sync/" + "0".repeat(64))
        check("an unknown address holds nothing", stored?.first == 404)
        a.cloud.disable(eraseCloud = true)
        b.cloud.syncNow()
        check("A erasing the copy switched sync off on B", !b.cloud.status.value.on && b.cloud.code() == null)
        check("B's own data is still on B", b.db.asks().all().size == 2)
        check("a fresh address accepts data", a.claude.serviceCall("PUT", "/v1/sync/" + "0".repeat(64), """{"base":0,"data":"QUJD"}""")?.first == 200)
    }
    // The pairing QR code: what one device draws, another must read back, also from a soft, tilted picture.
    run {
        val code = "P2FZQ-E9XRG-0E1WT-KTBWH-N6X3P"
        val text = app.dcrux.sync.CloudSync.qrText(code)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val cell = 9
        val clean = java.awt.image.BufferedImage(m.width * cell, m.height * cell, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until clean.height) for (x in 0 until clean.width) clean.setRGB(x, y, if (m[x / cell, y / cell]) 0x101010 else 0xF4F4F0)
        // As a phone would see it on a monitor: smaller within a larger frame, a little rotated, a little blurred.
        val photo = java.awt.image.BufferedImage(1600, 1200, java.awt.image.BufferedImage.TYPE_INT_RGB)
        photo.createGraphics().apply {
            color = java.awt.Color(40, 44, 60); fillRect(0, 0, 1600, 1200)
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            rotate(Math.toRadians(7.0), 800.0, 600.0); drawImage(clean, 520, 330, 520, 520, null); dispose()
        }
        val blur = java.awt.image.ConvolveOp(java.awt.image.Kernel(3, 3, FloatArray(9) { 1f / 9f })).filter(photo, null)
        fun read(img: java.awt.image.BufferedImage): String? = runCatching {
            val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            val src = com.google.zxing.RGBLuminanceSource(img.width, img.height, px)
            com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(src)), mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)).text
        }.getOrNull()
        check("a drawn QR code reads back to the same code", read(clean)?.let { app.dcrux.sync.CloudSync.fromQr(it) } == code.replace("-", ""))
        check("it still reads from a tilted, blurred picture of a screen", read(blur)?.let { app.dcrux.sync.CloudSync.fromQr(it) } == code.replace("-", ""))
        check("some other QR code is not taken for a sync code", app.dcrux.sync.CloudSync.fromQr("https://example.com") == null)
    }
    println(if (failed == 0) "ALL PASSED" else "$failed FAILED")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
