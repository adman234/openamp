package io.github.adman234.openamp.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    /** Loudness correction in dB from Plex, when Plex has analysed the track. */
    val gain: Float? = null,
    val albumGain: Float? = null,
)

/** A playlist kept on the phone. With sync on, new tracks on the server are downloaded when the library refreshes. */
@Serializable
data class LocalPlaylist(
    val id: String,
    val title: String,
    val trackIds: List<String>,
    val sync: Boolean = false,
)

/** How often and how recently an album was played on this phone. */
@Serializable
data class AlbumPlays(val albumId: String, val count: Int, val last: Long)

/** The queue and position to come back to after the app or phone restarts. */
@Serializable
data class Resume(
    val ids: List<String> = emptyList(),
    val index: Int = 0,
    val position: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
)

@Serializable
private data class FolderIndex(val tracks: List<LocalTrack> = emptyList(), val playlists: List<LocalPlaylist> = emptyList())

internal val storeJson = Json { ignoreUnknownKeys = true }

internal inline fun <reified T> readJson(file: File, default: T): T =
    runCatching { storeJson.decodeFromString<T>(file.readText()) }.getOrDefault(default)

internal inline fun <reified T> writeJson(file: File, value: T) {
    val tmp = File(file.path + ".tmp")
    tmp.writeText(storeJson.encodeToString(value))
    if (!tmp.renameTo(file)) throw IOException("Could not save ${file.name}")
}

/** Album artwork saved on the phone, for the player, the notification and the widget when offline. */
fun artFile(context: Context, albumId: String): File = File(context.filesDir, "art/$albumId.jpg")

/**
 * The app's record of what is on the phone. It lives in the app's private
 * storage, and a copy is kept inside the download folder so that a fresh
 * install can pick the downloads up again.
 */
class DownloadStore(private val context: Context) {
    private val tracksFile = File(context.filesDir, "downloads.json")
    private val playlistsFile = File(context.filesDir, "playlists.json")

    val tracks = MutableStateFlow(readJson<List<LocalTrack>>(tracksFile, emptyList()))
    val playlists = MutableStateFlow(readJson<List<LocalPlaylist>>(playlistsFile, emptyList()))

    @Synchronized
    private fun setTracks(next: List<LocalTrack>) {
        writeJson(tracksFile, next)
        tracks.value = next
    }

    @Synchronized
    fun upsert(track: LocalTrack) = setTracks(tracks.value.filter { it.id != track.id } + track)

    @Synchronized
    fun remove(ids: Set<String>) = setTracks(tracks.value.filter { it.id !in ids })

    @Synchronized
    fun setGains(gains: Map<String, Pair<Float?, Float?>>) = setTracks(
        tracks.value.map { t -> gains[t.id]?.let { t.copy(gain = it.first, albumGain = it.second) } ?: t }
    )

    @Synchronized
    fun savePlaylist(playlist: LocalPlaylist) {
        val next = playlists.value.filter { it.id != playlist.id } + playlist
        writeJson(playlistsFile, next)
        playlists.value = next
    }

    @Synchronized
    fun removePlaylist(id: String) {
        val next = playlists.value.filter { it.id != id }
        writeJson(playlistsFile, next)
        playlists.value = next
    }

    /** Writes the records into the download folder. Failure is not an error. */
    fun exportIndex(folderUri: String) {
        if (folderUri.isBlank()) return
        runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return
            val file = root.findFile(INDEX_NAME) ?: root.createFile("application/octet-stream", INDEX_NAME) ?: return
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(storeJson.encodeToString(FolderIndex(tracks.value, playlists.value)).toByteArray())
            }
        }
    }

    /**
     * Reads the records left in a download folder by an earlier install and
     * adopts every track whose file is still there. Returns how many were adopted.
     */
    fun importIndex(folderUri: String): Int {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return 0
        val file = root.findFile(INDEX_NAME) ?: return 0
        val index = runCatching {
            context.contentResolver.openInputStream(file.uri)?.use {
                storeJson.decodeFromString<FolderIndex>(it.readBytes().decodeToString())
            }
        }.getOrNull() ?: return 0
        val known = tracks.value.mapTo(HashSet()) { it.id }
        val found = index.tracks.filter { it.id !in known }.filter { track ->
            runCatching { DocumentFile.fromSingleUri(context, Uri.parse(track.uri))?.exists() == true }.getOrDefault(false)
        }
        if (found.isNotEmpty()) synchronized(this) { setTracks(tracks.value + found) }
        val knownPlaylists = playlists.value.mapTo(HashSet()) { it.id }
        index.playlists.filter { it.id !in knownPlaylists }.forEach(::savePlaylist)
        return found.size
    }

    private companion object {
        const val INDEX_NAME = "openamp-index.json"
    }
}

/** Play history kept on the phone, for the home screen. */
class History(dir: File) {
    private val file = File(dir, "history.json")
    val plays = MutableStateFlow(readJson<List<AlbumPlays>>(file, emptyList()))

    @Synchronized
    fun record(albumId: String) {
        val old = plays.value.firstOrNull { it.albumId == albumId }
        val next = plays.value.filter { it.albumId != albumId } +
            AlbumPlays(albumId, (old?.count ?: 0) + 1, System.currentTimeMillis())
        plays.value = next
        runCatching { writeJson(file, next) }
    }
}

class ResumeStore(dir: File) {
    private val file = File(dir, "resume.json")
    fun load(): Resume = readJson(file, Resume())
    fun save(state: Resume) {
        runCatching { writeJson(file, state) }
    }
}
