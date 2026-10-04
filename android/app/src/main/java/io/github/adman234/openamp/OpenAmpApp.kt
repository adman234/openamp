package io.github.adman234.openamp

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.github.adman234.openamp.data.DownloadStore
import io.github.adman234.openamp.data.Downloader
import io.github.adman234.openamp.data.History
import io.github.adman234.openamp.data.ResumeStore
import io.github.adman234.openamp.data.PlexApi
import io.github.adman234.openamp.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OpenAmpApp : Application(), ImageLoaderFactory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var plex: PlexApi
        private set
    lateinit var store: DownloadStore
        private set
    lateinit var downloader: Downloader
        private set
    lateinit var history: History
        private set
    lateinit var resume: ResumeStore
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // A lossy copy is converted on the server before the first byte arrives.
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
        plex = PlexApi(prefs, http)
        store = DownloadStore(this)
        history = History(filesDir)
        resume = ResumeStore(filesDir)
        downloader = Downloader(this, prefs, plex, http, store, scope)
    }

    // Artwork comes from Plex, which wants the token. It goes in a header and
    // only to the Plex host, never into the image URL.
    override fun newImageLoader(): ImageLoader {
        val client = http.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val token = prefs.token
            val plexHost = prefs.plexUrl.toHttpUrlOrNull()?.host
            if (token != null && req.url.host == plexHost) {
                chain.proceed(req.newBuilder().header("X-Plex-Token", token).build())
            } else {
                chain.proceed(req)
            }
        }.build()
        return ImageLoader.Builder(this).okHttpClient(client).build()
    }
}
