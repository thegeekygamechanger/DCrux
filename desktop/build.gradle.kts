import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

// DCrux for Linux and Windows. The screens and the app's logic are the Android app's own source
// files (../app/src/main/java), compiled here unchanged; only what differs between a phone and a
// computer lives in this module, behind the Platform interface.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
}

val appVersion = "1.4.7"

// Android-only files: each has a counterpart of the same name and package in this module, or is not needed here.
val androidOnly = listOf(
    "app/dcrux/DCruxApp.kt", "app/dcrux/MainActivity.kt", "app/dcrux/AndroidPlatform.kt",
    "app/dcrux/ai/AiWorkService.kt", "app/dcrux/audio/AndroidAudio.kt", "app/dcrux/audio/MindFlowService.kt",
    "app/dcrux/data/AndroidDb.kt", "app/dcrux/data/AndroidSecureStore.kt", "app/dcrux/lock/AndroidBiometrics.kt",
    "app/dcrux/mains/AndroidPages.kt", "app/dcrux/pdf/**", "app/dcrux/sync/SyncWorker.kt", "app/dcrux/ui/Back.kt",
    "app/dcrux/ui/news/BrowserScreen.kt", "app/dcrux/ui/theme/Fonts.kt", "app/dcrux/ui/video/YouTubePlayer.kt",
    "app/dcrux/update/UpdateManager.kt",
)

// The DCrux service this build talks to, from the git-ignored secrets.properties, masked the same way as in the Android app.
val secretProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun maskParts(key: String): Pair<String, String> {
    val bytes = key.toByteArray()
    val pad = ByteArray(bytes.size)
    var block = MessageDigest.getInstance("SHA-256").digest("dcrux-pad".toByteArray())
    for (i in bytes.indices) {
        if (i % 32 == 0 && i > 0) block = MessageDigest.getInstance("SHA-256").digest(block)
        pad[i] = block[i % 32]
    }
    val mixed = ByteArray(bytes.size) { (bytes[it].toInt() xor pad[it].toInt()).toByte() }
    val enc = Base64.getEncoder()
    return enc.encodeToString(mixed) to enc.encodeToString(pad)
}
val serviceUrl: String = (System.getenv("DCRUX_PROXY_URL") ?: secretProps.getProperty("DCRUX_PROXY_URL") ?: "").trim().trimEnd('/')
val serviceSecret = maskParts(if (serviceUrl.isEmpty()) "" else (System.getenv("DCRUX_APP_SECRET") ?: secretProps.getProperty("DCRUX_APP_SECRET") ?: "").trim())

val generatedDir = layout.buildDirectory.dir("generated/buildinfo")
val writeBuildInfo by tasks.registering {
    val out = generatedDir
    val url = serviceUrl; val sa = serviceSecret.first; val sb = serviceSecret.second; val version = appVersion
    inputs.property("url", url); inputs.property("sa", sa); inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("app/dcrux/desktop/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package app.dcrux.desktop\n\n/** Written by the build; never committed. */\ninternal object BuildInfo {\n" +
                "    const val VERSION = \"$version\"\n    const val PX = \"$url\"\n    const val SA = \"$sa\"\n    const val SB = \"$sb\"\n}\n"
        )
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets["main"].kotlin.srcDir("../app/src/main/java")
    sourceSets["main"].kotlin.srcDir(writeBuildInfo)
    sourceSets["main"].kotlin.exclude(androidOnly)
}
// The fonts are the Android app's own files.
sourceSets["main"].resources.srcDir("../app/src/main/res")
sourceSets["main"].resources.srcDir("icons")
sourceSets["main"].resources.include("font/**", "icon.png")

ksp { arg("room.generateKotlin", "true") }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences-core:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.jsoup:jsoup:1.24.1")
    // QR codes for pairing devices: drawn on one, read from a photo on the other. Apache-2.0, no network, no Play services.
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
}

compose.desktop {
    application {
        mainClass = "app.dcrux.desktop.MainKt"
        // A lighter, quicker start (measured on Linux: about 420 MB down to about 290 MB, first frame in about
        // 1.2 s instead of 2.5 s). Nothing here changes what the app does or how it draws:
        // - The heap starts small, may grow to 512 MB, and is handed back when it empties. Left alone, the JVM
        //   takes a slice of the machine's memory up front and lets the heap grow to a quarter of it.
        // - The collector stays the default low-pause one (G1), with a small young area and two helper threads.
        // - Memory the C library is holding but no longer using is returned every few seconds (Linux).
        // - Classes come ready-made from an archive (resources/dcrux.jsa) instead of being read and checked from
        //   the jars at every start. The archive only works in the folder it was made in, so the app writes its
        //   own, once, in the background after its first start in a new place or after an update (see
        //   StartArchive.kt). Until it exists, or if it does not match, the JVM starts the old way without a word.
        //   It is mapped where it was written, so its pages are shared with the file.
        jvmArgs += listOf(
            "-Xms32m", "-Xmx512m", "-XX:MaxNewSize=32m", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20",
            "-XX:G1PeriodicGCInterval=8000", "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1",
            "-XX:TrimNativeHeapInterval=5000",
            "-XX:SharedArchiveFile=\$APPDIR/resources/dcrux.jsa", "-XX:+UnlockDiagnosticVMOptions", "-XX:ArchiveRelocationMode=0",
            // Shown by the launcher the instant the app is started, before any of the app has loaded, and taken down
            // by itself when the app's window appears (icons/splash.png, drawn by scripts/make_splash.py).
            "-splash:\$APPDIR/resources/splash.png",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.AppImage)
            packageName = "DCrux"
            packageVersion = appVersion
            description = "The daily crux of current affairs for UPSC and State PSC aspirants"
            vendor = "Harsh Kumar"
            licenseFile.set(rootProject.file("LICENSE"))
            // Files placed beside the app in every kind of installer: the list of classes its archive is made from,
            // and the start-up picture.
            appResourcesRootDir.set(layout.buildDirectory.dir("cds-resources"))
            modules("java.sql", "java.desktop", "java.naming", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")
            linux { shortcut = true; menuGroup = "Education"; iconFile.set(project.file("icons/icon.png")) }
            windows {
                shortcut = true; menu = true; menuGroup = "DCrux"; perUserInstall = true
                // Stays the same for every version, so a newer installer replaces the older app instead of sitting beside it.
                upgradeUuid = "6f0d6c0e-7a2b-4a53-9d55-2b5f6f0a1c41"
                iconFile.set(project.file("icons/icon.ico"))
            }
        }
    }
}

// ---- The class archive -------------------------------------------------------------------------
// What ships is the list of classes to put in it (resources/classes.lst); the app makes the archive from that
// list on the user's machine. The list is two lists joined: cds/classes.lst, recorded from a real start of the
// app in its window (refresh it now and then: start the packaged app with
// JAVA_TOOL_OPTIONS=-XX:DumpLoadedClassList=<file>, use it for a minute, close it), and a short off-screen tour
// made at build time, which keeps up with classes added since. Names that no longer exist are skipped.
val cdsDir = layout.buildDirectory.dir("cds")
val cdsTour = tasks.register<JavaExec>("cdsTour") {
    group = "build"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.dcrux.desktop.RenderKt")
    val dir = cdsDir.get().asFile
    // First screen, past the tour, Settings and its sync sheet, then Papers: the screens most people open first.
    args = listOf(File(dir, "data").path, File(dir, "out").path, "nosync", "wait:1500", "134,1716", "wait:1200", "720,1760", "wait:1200", "480,1080", "wait:1200", "480,300", "wait:800", "312,1760", "wait:1500", "tour")
    jvmArgs("-Djava.awt.headless=true", "-Drender.size=960x1840x2", "-Xmx512m", "-XX:DumpLoadedClassList=" + File(dir, "tour.lst").path)
    isIgnoreExitValue = true
    doFirst { dir.deleteRecursively(); dir.mkdirs() }
}
val classList = tasks.register("classList") {
    group = "build"
    dependsOn(cdsTour)
    val recorded = file("cds/classes.lst")
    val tour = cdsDir.map { it.file("tour.lst") }
    val out = layout.buildDirectory.file("cds-resources/common/classes.lst")
    // The start-up picture travels the same way, as a file beside the app.
    val splash = file("icons/splash.png")
    inputs.files(recorded, splash)
    outputs.file(out)
    doLast {
        // Class names once each (the line numbers of a recording are dropped, since two recordings number
        // differently), with the notes about lambdas, which refer to classes by name.
        val seen = HashSet<String>()
        val lines = listOf(recorded, tour.get().asFile).filter { it.exists() }.flatMap { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("@")) it else it.substringBefore(' ') }
            .filter { seen.add(it) }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
        splash.copyTo(File(out.get().asFile.parentFile, "splash.png"), overwrite = true)
        logger.lifecycle("Class list: ${lines.count { !it.startsWith("@") }} classes")
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(classList) }

// Draws the app off-screen and saves pictures of it (see Render.kt): ./gradlew :desktop:render -Pshots="data out sync wait:3000 home"
tasks.register<JavaExec>("render") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.dcrux.desktop.RenderKt")
    args = ((project.findProperty("shots") as String?) ?: "").split(' ').filter { it.isNotBlank() }
    jvmArgs("-Djava.awt.headless=true", "-Drender.size=" + ((project.findProperty("size") as String?) ?: "960x1840x2"))
}

// Two simulated devices pair and exchange data through the service this build points at (see SyncCheck.kt).
// Point the build at a local relay first: DCRUX_PROXY_URL=http://127.0.0.1:<port> DCRUX_APP_SECRET=<its secret>.
tasks.register<JavaExec>("syncCheck") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.dcrux.desktop.SyncCheckKt")
    args = listOf(layout.buildDirectory.dir("sync-check").get().asFile.path)
    jvmArgs("-Djava.awt.headless=true")
}

// Checks search against a small library of titles (see SearchCheck.kt). Needs no network.
tasks.register<JavaExec>("searchCheck") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.dcrux.desktop.SearchCheckKt")
    args = listOf(layout.buildDirectory.dir("search-check").get().asFile.path)
    jvmArgs("-Djava.awt.headless=true")
}

// Pairs a data folder from the command line (see CloudCli.kt): ./gradlew :desktop:cloud -Pcli="<data dir> join <code>"
tasks.register<JavaExec>("cloud") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.dcrux.desktop.CloudCliKt")
    args = ((project.findProperty("cli") as String?) ?: "").split(' ').filter { it.isNotBlank() }
    jvmArgs("-Djava.awt.headless=true")
}

// The app icon (desktop/icons/icon.png and icon.ico: the black mark centred on a white tile) is drawn by
// scripts/make_icon.py; run it again if the mark changes.
