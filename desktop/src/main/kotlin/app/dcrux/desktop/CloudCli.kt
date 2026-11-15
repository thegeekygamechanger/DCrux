package app.dcrux.desktop

import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Pairs a data folder with other devices from the command line and says what arrived: a way to
 * check sync against a phone without opening the window. Usage: CloudCli <data dir> join <code> | sync | mark <text> | erase | status
 */
fun main(args: Array<String>) {
    val g = desktopGraph(DesktopPlatform(File(args[0]).apply { mkdirs() }))
    runBlocking {
        when (args.getOrNull(1)) {
            "join" -> println("joined: " + g.cloud.join(args.getOrElse(2) { "" }))
            "sync" -> g.cloud.syncNow()
            // Ends sync for this code and erases the stored copy (after a test pairing).
            "erase" -> g.cloud.disable(eraseCloud = true)
            // Adds a question from this side, to see it arrive on the other device.
            "mark" -> {
                val v = g.db.videos().withNotes().firstOrNull()
                g.db.asks().insert(app.dcrux.data.Ask(articleId = "video:" + (v?.id ?: "none"), question = args.drop(2).joinToString(" "), answer = "Added from the computer to check sync.", askedAt = System.currentTimeMillis(), topic = v?.title.orEmpty()))
                g.cloud.syncNow()
            }
            else -> {}
        }
        g.cloud.start()
        val s = g.cloud.status.value
        println("sync on: ${g.cloud.code() != null}, error: ${s.error}")
        println("channels: " + g.db.channels().all().joinToString { it.title })
        println("videos with notes: " + g.db.videos().withNotes().joinToString { it.title.take(40) + " [summary=" + (it.summary != null) + ", detailed=" + (it.detailed != null) + "]" })
        println("questions: " + g.db.asks().all().joinToString(" | ") { it.question.take(50) + " @ " + it.topic.take(30) })
        println("tests: " + g.db.tests().all().mapNotNull { r -> app.dcrux.mains.MainsStore.decode(g, r) }.joinToString { it.title + " " + it.scoreText + " (" + it.kind + ")" })
        println("settings: exams=" + g.prefs.get().exams + " language=" + g.prefs.get().language + " city=" + g.prefs.get().place?.name)
    }
    kotlin.system.exitProcess(0)
}
