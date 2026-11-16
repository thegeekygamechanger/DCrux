package app.dcrux.desktop

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Pairs a data folder with other devices from the command line and says what arrived: a way to
 * check sync against a phone without opening the window. Usage: CloudCli <data dir> join <code> | host [seconds] | sync | mark <text> | erase | status
 */
fun main(args: Array<String>) {
    val g = desktopGraph(DesktopPlatform(File(args[0]).apply { mkdirs() }))
    runBlocking {
        when (args.getOrNull(1)) {
            // A 4-character code showing on another device, or a full key.
            "join" -> args.getOrElse(2) { "" }.let { code ->
                println("joined: " + if (app.dcrux.sync.CloudSync.normaliseShort(code) != null) g.cloud.joinShort(code) else g.cloud.join(code))
            }
            // Turns sync on here if it is off, then offers 4-character codes (written to <data dir>/short-code) until one is used or the seconds run out.
            "host" -> {
                if (g.cloud.code() == null) g.cloud.enable()
                val file = File(args[0], "short-code")
                val before = g.db.asks().all().size
                kotlinx.coroutines.withTimeoutOrNull(args.getOrElse(2) { "120" }.toLong() * 1000) {
                    g.cloud.offerShortCodes { code -> file.writeText(code.orEmpty()); println("code: $code") }
                }
                file.delete()
                g.cloud.syncNow()
                println("questions before/after hosting: $before/" + g.db.asks().all().size)
            }
            // Prints the air quality the app would show for a city: CloudCli <dir> aqi <name> <lat> <lon> <region>
            "aqi" -> {
                val w = g.weather.fetch(app.dcrux.data.Place(args[2], args[3].toDouble(), args[4].toDouble(), args.drop(5).joinToString(" ")))
                println("${args[2]}: AQI ${w.aqi} (${app.dcrux.net.WeatherRepo.aqiWord(w.aqi)}), source=${w.aqiSource}, stations=${w.aqiStations}, worst=${w.aqiWorstStation} ${w.aqiWorst}, peak=${w.aqiPeak}, updated=${w.aqiUpdated}, pm2.5=${w.pm25}")
                kotlin.system.exitProcess(0)
            }
            // Sends feedback as the app would: CloudCli <dir> feedback <kind> <email or -> <message...>
            "feedback" -> {
                println("feedback: " + (app.dcrux.net.Feedback.send(g, args[2], args.drop(4).joinToString(" "), args[3].takeIf { it != "-" }.orEmpty()) ?: "sent"))
                kotlin.system.exitProcess(0)
            }
            // What the screens see: the live list of channels, as it first arrives after a start. CloudCli <dir> watch
            "watch" -> {
                val began = System.currentTimeMillis()
                val first = kotlinx.coroutines.withTimeoutOrNull(8000) { g.db.channels().observeAll().first() }
                println("live channel list: " + (first?.map { it.title } ?: "NOTHING ARRIVED in 8 s") + " after ${System.currentTimeMillis() - began} ms")
                kotlin.system.exitProcess(if (first == null) 1 else 0)
            }
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
