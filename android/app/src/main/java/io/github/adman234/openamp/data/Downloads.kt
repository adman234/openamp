package io.github.adman234.openamp.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import io.github.adman234.openamp.OpenAmpApp
import io.github.adman234.openamp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One track waiting to be downloaded. The queue is saved, so it survives the app closing. */
@Serializable
data class DownloadTask(
    val album: AlbumRef,
    val item: Item,
    val quality: String,
    val attempts: Int = 0,
    val failed: Boolean = false,
    val error: String? = null,
) {
    val id: String get() = item.ratingKey
}

data class DownloadProgress(val trackId: String, val bytes: Long, val total: Long)

class Downloader(
    private val context: Context,
    private val prefs: Prefs,
    private val plex: PlexApi,
    private val http: OkHttpClient,
    private val store: DownloadStore,
    private val scope: CoroutineScope,
) {
    private val queueFile = File(context.filesDir, "queue.json")

    val queue = MutableStateFlow(readJson<List<DownloadTask>>(queueFile, emptyList()))
    val progress = MutableStateFlow<DownloadProgress?>(null)
    val paused = MutableStateFlow(false)

    /** Why the queue is waiting, when it is. */
    val notice = MutableStateFlow<String?>(null)

    private val running = Mutex()

    @Volatile
    private var currentCall: Call? = null

    @Synchronized
    private fun setQueue(next: List<DownloadTask>) {
        queue.value = next
        runCatching { writeJson(queueFile, next) }
    }

    /** Each track comes with the album it belongs to, which decides its folder and artwork. */
    fun enqueue(tracks: List<Pair<AlbumRef, Item>>, quality: String) {
        val have = store.tracks.value.mapTo(HashSet()) { it.id }
        synchronized(this) {
            val waiting = queue.value.mapTo(HashSet()) { it.id }
            val fresh = tracks.filter { it.second.ratingKey !in have && waiting.add(it.second.ratingKey) }
            // Asking again for a failed track gives it a fresh start.
            val asked = tracks.mapTo(HashSet()) { it.second.ratingKey }
            setQueue(
                queue.value.map { if (it.failed && it.id in asked) it.copy(failed = false, attempts = 0, error = null) else it } +
                    fresh.map { DownloadTask(it.first, it.second, quality) }
            )
        }
        kick()
    }

    fun retryFailed() {
        setQueue(queue.value.map { it.copy(failed = false, attempts = 0, error = null) })
        kick()
    }

    fun cancel(trackId: String) {
        setQueue(queue.value.filter { it.id != trackId })
        if (progress.value?.trackId == trackId) currentCall?.cancel()
    }

    fun cancelAll() {
        setQueue(emptyList())
        currentCall?.cancel()
    }

    fun setPaused(value: Boolean) {
        paused.value = value
        if (value) currentCall?.cancel() else kick()
    }

    /** Starts the foreground service that works through the queue, if there is anything to do. */
    fun kick() {
        if (queue.value.none { !it.failed }) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: Exception) {
            // The system refuses a service start from the background. Run inside the app instead.
            scope.launch { drain {} }
        }
    }

    /** Saves the album artwork if it is not on the phone yet. Failure is not an error. */
    fun fetchArt(album: AlbumRef) {
        scope.launch(Dispatchers.IO) { ensureArt(album) }
    }

    /** Deletes downloaded files and their records. */
    fun remove(tracks: List<LocalTrack>) {
        scope.launch(Dispatchers.IO) {
            tracks.forEach { t ->
                runCatching { DocumentFile.fromSingleUri(context, Uri.parse(t.uri))?.delete() }
            }
            store.remove(tracks.mapTo(HashSet()) { it.id })
            store.exportIndex(prefs.folderUri)
        }
    }

    /** Works through the queue until it is empty or only failed tasks are left. */
    suspend fun drain(onStatus: (String) -> Unit) {
        if (!running.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                while (true) {
                    if (paused.value) {
                        onStatus("Downloads paused")
                        paused.first { !it }
                        continue
                    }
                    val task = queue.value.firstOrNull { !it.failed } ?: break
                    if (!networkAllowed()) {
                        notice.value = "Waiting for Wi-Fi. Mobile data downloads are turned off."
                        onStatus("Waiting for Wi-Fi")
                        delay(5_000)
                        continue
                    }
                    notice.value = null
                    onStatus("Downloading ${task.item.title}, ${queue.value.count { !it.failed }} left")
                    try {
                        ensureArt(task.album)
                        store.upsert(fetch(task))
                        setQueue(queue.value.filter { it.id != task.id })
                    } catch (e: Exception) {
                        val stillWanted = queue.value.any { it.id == task.id }
                        if (stillWanted && !paused.value) {
                            // A real failure, not a pause or a cancel. Three tries, then it waits for a retry.
                            val attempts = task.attempts + 1
                            val message = e.message ?: e.javaClass.simpleName
                            setQueue(queue.value.map {
                                if (it.id == task.id) it.copy(attempts = attempts, failed = attempts >= 3, error = message) else it
                            })
                            if (attempts < 3) delay(3_000L * attempts)
                        }
                    } finally {
                        progress.value = null
                        currentCall = null
                    }
                }
                store.exportIndex(prefs.folderUri)
            }
        } finally {
            notice.value = null
            running.unlock()
        }
    }

    private fun networkAllowed(): Boolean {
        if (prefs.allowMobile) return true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun ensureArt(album: AlbumRef) {
        val file = artFile(context, album.id)
        if (file.exists()) return
        val url = plex.artUrl(album.thumb, 600) ?: return
        val token = prefs.token ?: return
        runCatching {
            http.newCall(Request.Builder().url(url).header("X-Plex-Token", token).build()).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) return
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.outputStream().use { body.byteStream().copyTo(it) }
                tmp.renameTo(file)
            }
        }
    }

    /**
     * Downloads to a partial file in the app's cache, then copies it into the
     * download folder. A partial file left by an earlier attempt is continued
     * with a Range request.
     */
    private fun fetch(task: DownloadTask): LocalTrack {
        val track = task.item
        val album = task.album
        val token = prefs.token ?: throw IOException("Not signed in to Plex")
        val part = File(context.cacheDir, "downloads/${track.ratingKey}.${task.quality}.part")
        part.parentFile?.mkdirs()
        val have = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url("${prefs.fileServiceUrl}/v1/tracks/${track.ratingKey}/file?quality=${task.quality}")
            .header("X-Plex-Token", token)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val call = http.newCall(request)
        currentCall = call
        return call.execute().use { resp ->
            if (resp.code == 416) {
                // The partial file no longer matches what the server has.
                part.delete()
                throw IOException("Restarting \"${track.title}\" from the beginning")
            }
            if (!resp.isSuccessful) throw IOException("The file service answered ${resp.code} for \"${track.title}\"")
            val body = resp.body ?: throw IOException("The file service sent an empty answer")
            val resumed = resp.code == 206
            val start = if (resumed) have else 0L
            val total = start + body.contentLength().coerceAtLeast(0)

            FileOutputStream(part, resumed).use { out ->
                val buffer = ByteArray(64 * 1024)
                var written = start
                val input = body.byteStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    progress.value = DownloadProgress(track.ratingKey, written, total)
                }
            }
            if (total > 0 && part.length() != total) throw IOException("\"${track.title}\" arrived incomplete")

            val ext = resp.header("X-OpenAmp-Ext") ?: "bin"
            val root = DocumentFile.fromTreeUri(context, Uri.parse(prefs.folderUri))
                ?: throw IOException("The download folder is not available")
            val dir = root.dir(safeName(album.artist.ifBlank { "Unknown artist" })).dir(safeName(album.title))
            val disc = track.parentIndex ?: 1
            val index = track.index ?: 0
            val number = if (disc > 1) "$disc-%02d".format(index) else "%02d".format(index)
            val name = "${safeName("$number ${track.title}")}.$ext"
            dir.findFile(name)?.delete()
            // A generic type stops the storage provider from appending its own extension.
            val file = dir.createFile("application/octet-stream", name)
                ?: throw IOException("Could not create $name in the download folder")
            val size = try {
                context.contentResolver.openOutputStream(file.uri)?.use { out -> part.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Could not write $name")
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            part.delete()
            LocalTrack(
                id = track.ratingKey,
                title = track.title,
                artist = track.originalTitle ?: track.grandparentTitle.ifBlank { album.artist },
                album = album.title,
                albumArtist = album.artist,
                albumId = album.id,
                disc = disc,
                index = index,
                uri = file.uri.toString(),
                quality = task.quality,
                ext = ext,
                size = size,
                sourceSize = resp.header("X-OpenAmp-Source-Size")?.toLongOrNull() ?: 0,
                sourceMtime = resp.header("X-OpenAmp-Source-Mtime")?.toLongOrNull() ?: 0,
                thumb = album.thumb,
                year = album.year,
                genres = album.genres,
                gain = resp.header("X-OpenAmp-Gain")?.toFloatOrNull(),
                albumGain = resp.header("X-OpenAmp-Album-Gain")?.toFloatOrNull(),
            )
        }
    }

    private fun DocumentFile.dir(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)
            ?: throw IOException("Could not create the folder $name")

    private fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trimEnd('.').take(120).ifBlank { "_" }
}

/** Keeps downloads going while the app is closed. It stops itself when the queue is done. */
class DownloadService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION, notification("Starting downloads"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION, notification("Starting downloads"))
        }
        if (job?.isActive != true) {
            val app = application as OpenAmpApp
            job = app.scope.launch {
                app.downloader.drain { text -> manager.notify(NOTIFICATION, notification(text)) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // Android 15 stops a data sync service after six hours in a day. The queue is saved and continues next time the app opens.
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("OpenAmp")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION = 2
    }
}
