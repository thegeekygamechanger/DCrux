package app.dcrux

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import app.dcrux.net.Http
import app.dcrux.sync.SyncManager
import kotlinx.coroutines.launch

class DCruxApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        graph.scope.launch {
            val s = graph.prefs.get()
            SyncManager.schedule(this@DCruxApp, s.dailySync, s.wifiOnly)
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
