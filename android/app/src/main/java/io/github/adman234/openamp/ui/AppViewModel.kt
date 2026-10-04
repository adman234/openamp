package io.github.adman234.openamp.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.adman234.openamp.OpenAmpApp
import io.github.adman234.openamp.data.AlbumRef
import io.github.adman234.openamp.data.Item
import io.github.adman234.openamp.data.LocalPlaylist
import io.github.adman234.openamp.data.LocalTrack
import io.github.adman234.openamp.data.PlaylistRef
import io.github.adman234.openamp.data.Resource
import io.github.adman234.openamp.data.artFile
import io.github.adman234.openamp.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface Screen {
    data object Setup : Screen
    data object Browse : Screen

    /** The albums of one artist or one genre. */
    data class AlbumList(val title: String, val artist: String? = null, val genre: String? = null) : Screen

    /** The tracks of one album or one playlist. */
    data class Tracks(val album: AlbumRef? = null, val playlist: PlaylistRef? = null) : Screen
}

enum class BrowseMode(val label: String) {
    Albums("Albums"), Artists("Artists"), Playlists("Playlists"), Genres("Genres")
}

enum class SortBy(val label: String) {
    Title("Title"), Artist("Artist"), Recent("Recently added"), Year("Year")
}

/** Adds https:// when the scheme is missing. An address that is only a scheme counts as empty. */
fun normalizeUrl(input: String): String {
    val text = input.trim()
    if (text.isEmpty() || text.equals("https://", true) || text.equals("http://", true)) return ""
    return (if ("://" in text) text else "https://$text").trimEnd('/')
}

fun validUrl(input: String): Boolean {
    val url = normalizeUrl(input)
    return url.startsWith("https://", ignoreCase = true) && url.toHttpUrlOrNull() != null
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenAmpApp
    private val prefs = app.prefs
    private val plex = app.plex

    val local = app.store.tracks
    val localPlaylists = app.store.playlists
    val download = app.downloader.status

    var signedIn by mutableStateOf(prefs.token != null)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var servers by mutableStateOf<List<Resource>>(emptyList())
        private set
    var serverName by mutableStateOf(prefs.serverName)
        private set
    var plexUrl by mutableStateOf(prefs.plexUrl)
        private set
    var fileUrl by mutableStateOf(prefs.fileServiceUrl)
        private set
    var folderUri by mutableStateOf(prefs.folderUri)
        private set
    var quality by mutableStateOf(prefs.quality)
        private set
    var allowMobile by mutableStateOf(prefs.allowMobile)
        private set

    val configured: Boolean
        get() = signedIn && validUrl(plexUrl) && validUrl(fileUrl) && folderUri.isNotBlank()

    private var stack by mutableStateOf<List<Screen>>(listOf(if (configured) Screen.Browse else Screen.Setup))
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var mode by mutableStateOf(BrowseMode.Albums)
    var sort by mutableStateOf(SortBy.Title)
    var query by mutableStateOf("")
    var onlyDownloaded by mutableStateOf(false)

    var albums by mutableStateOf<List<AlbumRef>>(emptyList())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    var tracks by mutableStateOf<List<Item>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var nowId by mutableStateOf<String?>(null)
        private set
    var nowTitle by mutableStateOf("")
        private set
    var nowArtist by mutableStateOf("")
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set

    private var accountToken: String? = null
    private val controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(app, token).buildAsync()
        controllerFuture.addListener({
            controller = runCatching { controllerFuture.get() }.getOrNull()?.also { c ->
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = readPlayer(player)
                })
                readPlayer(c)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun readPlayer(player: Player) {
        val item = player.currentMediaItem
        nowId = item?.mediaId
        nowTitle = item?.mediaMetadata?.title?.toString().orEmpty()
        nowArtist = item?.mediaMetadata?.artist?.toString().orEmpty()
        isPlaying = player.isPlaying
        shuffle = player.shuffleModeEnabled
        repeatMode = player.repeatMode
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
    }

    // Navigation

    fun open(next: Screen) {
        stack = stack + next
    }

    fun back() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun finishSetup() {
        settleUrls()
        if (configured) stack = listOf(Screen.Browse)
    }

    // Sign-in and settings

    fun signIn(openBrowser: (String) -> Unit) {
        if (signingIn) return
        signingIn = true
        error = null
        viewModelScope.launch {
            try {
                val pin = plex.createPin()
                openBrowser(plex.authUrl(pin))
                // The PIN lasts a few minutes. Poll until the user finishes in the browser.
                repeat(150) {
                    delay(2_000)
                    val token = plex.checkPin(pin)
                    if (token != null) {
                        accountToken = token
                        prefs.token = token
                        signedIn = true
                        loadServers(token)
                        return@launch
                    }
                }
                error = "Plex sign-in timed out. Try again."
            } catch (e: Exception) {
                error = "Plex sign-in failed: ${e.message}"
            } finally {
                signingIn = false
            }
        }
    }

    private suspend fun loadServers(token: String) {
        servers = plex.servers(token)
        servers.singleOrNull()?.let(::pickServer)
    }

    fun pickServer(server: Resource) {
        // A server hands out its own token. For the owner it matches the account token.
        prefs.token = server.accessToken ?: accountToken ?: prefs.token
        serverName = server.name
        prefs.serverName = server.name
        if (plexUrl.isBlank()) {
            val remote = server.connections.firstOrNull { !it.local && !it.relay } ?: server.connections.firstOrNull()
            remote?.let { updatePlexUrl(it.uri) }
        }
    }

    fun signOut() {
        prefs.token = null
        accountToken = null
        signedIn = false
        servers = emptyList()
        albums = emptyList()
        playlists = emptyList()
        stack = listOf(Screen.Setup)
    }

    // The text fields hold what was typed. Only a valid https address is saved.
    fun updatePlexUrl(v: String) {
        plexUrl = v
        prefs.plexUrl = if (validUrl(v)) normalizeUrl(v) else ""
    }

    fun updateFileUrl(v: String) {
        fileUrl = v
        prefs.fileServiceUrl = if (validUrl(v)) normalizeUrl(v) else ""
    }

    /** Tidies both address fields once typing is over. */
    fun settleUrls() {
        plexUrl = normalizeUrl(plexUrl)
        fileUrl = normalizeUrl(fileUrl)
    }

    fun updateFolder(uri: Uri) { folderUri = uri.toString(); prefs.folderUri = folderUri }
    fun updateQuality(v: String) { quality = v; prefs.quality = v }
    fun updateAllowMobile(v: Boolean) { allowMobile = v; prefs.allowMobile = v }

    // Library

    /** Artwork saved on the phone when there is some, otherwise the copy on Plex. */
    fun art(albumId: String?, thumb: String?): Any? {
        if (albumId != null) artFile(app, albumId).takeIf { it.exists() }?.let { return it }
        return plex.artUrl(thumb)
    }

    fun loadLibrary() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                albums = plex.albums()
                playlists = runCatching { plex.playlists() }.getOrDefault(emptyList())
            } catch (e: Exception) {
                error = "Could not reach Plex, showing downloads only. ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun openAlbum(album: AlbumRef) {
        val target = Screen.Tracks(album = album)
        tracks = emptyList()
        open(target)
        if (local.value.any { it.albumId == album.id }) app.downloader.fetchArt(album)
        viewModelScope.launch {
            // Offline this fails, and the screen falls back to the downloaded tracks.
            val loaded = runCatching { plex.tracks(album.id) }.getOrNull() ?: return@launch
            if (screen == target) tracks = loaded
        }
    }

    fun openPlaylist(playlist: PlaylistRef) {
        val target = Screen.Tracks(playlist = playlist)
        tracks = emptyList()
        open(target)
        viewModelScope.launch {
            val loaded = runCatching { plex.playlistTracks(playlist.id) }.getOrNull() ?: return@launch
            if (screen == target) tracks = loaded
            // Keep a downloaded playlist's saved track list in step with the server.
            if (localPlaylists.value.any { it.id == playlist.id }) savePlaylist(playlist, loaded)
        }
    }

    private fun savePlaylist(playlist: PlaylistRef, items: List<Item>) {
        runCatching { app.store.savePlaylist(LocalPlaylist(playlist.id, playlist.title, items.map { it.ratingKey })) }
    }

    private fun albumOf(item: Item): AlbumRef =
        albums.firstOrNull { it.id == item.parentRatingKey }
            ?: AlbumRef(
                id = item.parentRatingKey ?: "unknown",
                title = item.parentTitle.ifBlank { "Unknown album" },
                artist = item.grandparentTitle,
                thumb = item.parentThumb ?: item.thumb,
            )

    /** Downloads tracks from the open album or playlist. */
    fun download(source: Screen.Tracks, items: List<Item>) {
        if (items.isEmpty()) return
        source.playlist?.let { savePlaylist(it, tracks) }
        val label = source.album?.title ?: source.playlist?.title ?: "tracks"
        app.downloader.enqueue(label, items.map { (source.album ?: albumOf(it)) to it }, quality)
    }

    // Playback

    fun play(queue: List<LocalTrack>, startIndex: Int) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        c.setMediaItems(queue.map(::mediaItem), startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() {
        controller?.seekToNext()
    }

    /** Restarts the track, or goes to the one before when already near its start. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Off, then repeat the whole queue, then repeat one track. */
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun mediaItem(t: LocalTrack): MediaItem {
        val uri = Uri.parse(t.uri)
        // The session loads this for the notification and lock screen. A file on
        // the phone works with no connection.
        val art = artFile(app, t.albumId).takeIf { it.exists() }?.let(Uri::fromFile)
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setAlbumArtist(t.albumArtist)
                    .setArtworkUri(art)
                    .build()
            )
            .build()
    }
}
