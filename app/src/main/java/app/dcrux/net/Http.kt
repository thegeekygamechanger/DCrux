package app.dcrux.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    const val APP_UA = "DCrux/1.0 (Android app; https://github.com/thegeekygamechanger/DCrux)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                // Wikimedia asks every client to say who it is, and refuses anonymous ones.
                // Image loads (which set no user agent of their own) get the app's identity too.
                val wikimedia = req.url.host.endsWith("wikimedia.org") || req.url.host.endsWith("wikipedia.org")
                if (wikimedia || req.header("User-Agent") == null) {
                    chain.proceed(req.newBuilder().header("User-Agent", APP_UA).build())
                } else chain.proceed(req)
            }
            .build()
    }

    /** For server-sent events: no overall deadline, generous gap between chunks. */
    val streaming: OkHttpClient by lazy {
        // Page photos can take a while to upload on a slow connection, hence the patient write timeout.
        client.newBuilder().readTimeout(240, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-IN,en;q=0.9")
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} from ${req.url.host}")
            res.body.string()
        }
    }
}
