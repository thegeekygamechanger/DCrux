package app.dcrux.desktop

import app.dcrux.data.AppJson
import app.dcrux.data.Ask
import app.dcrux.data.Channel
import app.dcrux.data.QuizQuestion
import app.dcrux.data.TestRecord
import app.dcrux.data.TestResult
import app.dcrux.data.Video
import app.dcrux.mains.MainsStore
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

        // A removes the channel; B must lose it and not resurrect it.
        a.sync.removeChannel("chanA")
        a.cloud.syncNow(); b.cloud.syncNow(); a.cloud.syncNow()
        check("the removed channel is gone from both", a.db.channels().get("chanA") == null && b.db.channels().get("chanA") == null)

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
    println(if (failed == 0) "ALL PASSED" else "$failed FAILED")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
