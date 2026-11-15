package app.dcrux

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.dcrux.data.AndroidSecureStore
import app.dcrux.data.Prefs
import app.dcrux.data.openAndroidDb
import app.dcrux.update.UpdateManager
import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import app.dcrux.net.Http
import kotlinx.coroutines.launch

private val Context.store by preferencesDataStore("dcrux_settings")

class DCruxApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set
    lateinit var platform: AndroidPlatform
        private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        graph = Graph(
            platform = platform,
            secure = AndroidSecureStore(this),
            prefs = Prefs(store),
            keys = BuildKeys(BuildConfig.KA, BuildConfig.KB, BuildConfig.PX, BuildConfig.SA, BuildConfig.SB),
            openDb = { openAndroidDb(this) },
            makeUpdates = { UpdateManager(it, this) },
        )
        graph.cloud.start()
        graph.scope.launch {
            val s = graph.prefs.get()
            platform.scheduleDailySync(s.dailySync, s.wifiOnly)
            // Catch up when the daily job was delayed (battery saver, phone off).
            graph.updates.cleanUp()
            // A quiet look at GitHub for a newer build, at most once a day.
            launch { graph.updates.check() }
            if (s.dailySync && System.currentTimeMillis() - s.lastSync > 12 * 3_600_000L) graph.sync.run()
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .crossfade(true)
            .build()
}
