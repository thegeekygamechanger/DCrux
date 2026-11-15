import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing is optional and never committed: put keystore.properties next to
// this file's parent (storeFile, storePassword, keyAlias, keyPassword).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Optional built-in Claude key for a personal build: CLAUDE_API_KEY in the git-ignored
// secrets.properties. It is only lightly masked inside the APK, so never publish an APK
// built this way. Without it the app asks for a key in Settings.
val secretProps = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// -PnoBakedKey=true builds the public APK: no key inside, the app asks each user for their own.
val bakedKey: String = if (project.hasProperty("noBakedKey")) "" else (secretProps.getProperty("CLAUDE_API_KEY") ?: "").trim()
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
val masked = maskParts(bakedKey)
// The DCrux service (server/): where it lives, and the secret the app signs its requests with.
// A build with these and no baked key talks to the service and never asks the user for a key.
val serviceUrl: String = (secretProps.getProperty("DCRUX_PROXY_URL") ?: "").trim().trimEnd('/')
val serviceSecret = maskParts(if (serviceUrl.isEmpty()) "" else (secretProps.getProperty("DCRUX_APP_SECRET") ?: "").trim())

android {
    namespace = "app.dcrux"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "app.dcrux"
        minSdk = 24
        targetSdk = 36
        // -PtestVersionCode / -PtestVersionName build an older-looking APK for testing in-app updates.
        versionCode = (project.findProperty("testVersionCode") as String?)?.toInt() ?: 10
        versionName = (project.findProperty("testVersionName") as String?) ?: "1.4.1"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "KA", "\"${masked.first}\"")
        buildConfigField("String", "KB", "\"${masked.second}\"")
        buildConfigField("String", "PX", "\"$serviceUrl\"")
        buildConfigField("String", "SA", "\"${serviceSecret.first}\"")
        buildConfigField("String", "SB", "\"${serviceSecret.second}\"")
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.media:media:1.7.0")

    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.jsoup:jsoup:1.24.1")
    // QR codes for pairing devices: drawn on one, read from a photo on the other. Apache-2.0, no network, no Play services.
    implementation("com.google.zxing:core:3.5.4")
}
