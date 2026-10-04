package io.github.adman234.openamp.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** One downloaded track. sourceSize and sourceMtime describe the file on the share, for Sync. */
@Serializable
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: String,
    val disc: Int,
    val index: Int,
    val uri: String,
    val quality: String,
    val ext: String,
    val size: Long,
    val sourceSize: Long,
    val sourceMtime: Long,
    val thumb: String? = null,
)

/** The app's record of what is on the phone. A JSON file for the spike; a database comes with the full library index. */
class DownloadStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    val tracks = MutableStateFlow(load())

    private fun load(): List<LocalTrack> =
        runCatching { json.decodeFromString<List<LocalTrack>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(track: LocalTrack) {
        val next = tracks.value.filter { it.id != track.id } + track
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(next))
        if (!tmp.renameTo(file)) throw IOException("Could not save the download records")
        tracks.value = next
    }
}

data class DownloadStatus(
    val label: String,
    val done: Int,
    val total: Int,
    val failed: Int = 0,
    val message: String? = null,
    val active: Boolean = true,
)

class Downloader(
    private val context: Context,
    private val prefs: Prefs,
    private val http: OkHttpClient,
    private val store: DownloadStore,
    private val scope: CoroutineScope,
) {
    val status = MutableStateFlow<DownloadStatus?>(null)
    private val queue = Mutex()

    fun enqueue(album: AlbumRef, tracks: List<Item>, quality: String) {
        scope.launch(Dispatchers.IO) { queue.withLock { run(album, tracks, quality) } }
    }

    private suspend fun run(album: AlbumRef, tracks: List<Item>, quality: String) {
        var done = 0
        var failed = 0
        var lastError: String? = null
        for (track in tracks) {
            status.value = DownloadStatus(album.title, done, tracks.size, failed)
            while (!networkAllowed()) {
                status.value = DownloadStatus(
                    album.title, done, tracks.size, failed,
                    "Waiting for Wi-Fi. Mobile data downloads are turned off.",
                )
                delay(5_000)
            }
            try {
                store.upsert(fetch(album, track, quality))
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        status.value = DownloadStatus(album.title, done, tracks.size, failed, lastError, active = false)
    }

    private fun networkAllowed(): Boolean {
        if (prefs.allowMobile) return true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun fetch(album: AlbumRef, track: Item, quality: String): LocalTrack {
        val token = prefs.token ?: throw IOException("Not signed in to Plex")
        val request = Request.Builder()
            .url("${prefs.fileServiceUrl}/v1/tracks/${track.ratingKey}/file?quality=$quality")
            .header("X-Plex-Token", token)
            .build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("The file service answered ${resp.code} for \"${track.title}\"")
            val body = resp.body ?: throw IOException("The file service sent an empty answer")
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
                context.contentResolver.openOutputStream(file.uri)?.use { out -> body.byteStream().copyTo(out) }
                    ?: throw IOException("Could not write $name")
            } catch (e: Exception) {
                file.delete()
                throw e
            }
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
                quality = quality,
                ext = ext,
                size = size,
                sourceSize = resp.header("X-OpenAmp-Source-Size")?.toLongOrNull() ?: 0,
                sourceMtime = resp.header("X-OpenAmp-Source-Mtime")?.toLongOrNull() ?: 0,
                thumb = album.thumb,
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
