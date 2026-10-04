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
import io.github.adman234.openamp.data.LocalTrack
import io.github.adman234.openamp.data.Resource
import io.github.adman234.openamp.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Setup : Screen
    data object Albums : Screen
    data class Album(val album: AlbumRef) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenAmpApp
    private val prefs = app.prefs
    private val plex = app.plex

    val local = app.store.tracks
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
        get() = signedIn && plexUrl.isNotBlank() && fileUrl.isNotBlank() && folderUri.isNotBlank()

    var screen by mutableStateOf<Screen>(if (configured) Screen.Albums else Screen.Setup)

    var albums by mutableStateOf<List<AlbumRef>>(emptyList())
        private set
    var tracks by mutableStateOf<List<Item>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var nowPlaying by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
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
        isPlaying = player.isPlaying
        nowPlaying = player.currentMediaItem?.let { item ->
            listOfNotNull(item.mediaMetadata.title, item.mediaMetadata.artist).joinToString(" · ")
        }
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
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
    }

    fun updatePlexUrl(v: String) { plexUrl = v; prefs.plexUrl = v }
    fun updateFileUrl(v: String) { fileUrl = v; prefs.fileServiceUrl = v }
    fun updateFolder(uri: Uri) { folderUri = uri.toString(); prefs.folderUri = folderUri }
    fun updateQuality(v: String) { quality = v; prefs.quality = v }
    fun updateAllowMobile(v: Boolean) { allowMobile = v; prefs.allowMobile = v }

    // Library

    fun artUrl(thumb: String?): String? = plex.artUrl(thumb)

    fun loadAlbums() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                albums = plex.albums().sortedBy { it.title.lowercase() }
            } catch (e: Exception) {
                error = "Could not reach Plex, showing downloads only. ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun openAlbum(album: AlbumRef) {
        tracks = emptyList()
        screen = Screen.Album(album)
        viewModelScope.launch {
            try {
                val loaded = plex.tracks(album.id)
                if ((screen as? Screen.Album)?.album?.id == album.id) tracks = loaded
            } catch (e: Exception) {
                // Offline: the screen falls back to the downloaded tracks.
            }
        }
    }

    fun download(album: AlbumRef, items: List<Item>) {
        if (items.isNotEmpty()) app.downloader.enqueue(album, items, quality)
    }

    // Playback

    fun play(queue: List<LocalTrack>, startIndex: Int) {
        val c = controller ?: return
        c.setMediaItems(queue.map(::mediaItem), startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    private fun mediaItem(t: LocalTrack): MediaItem {
        val uri = Uri.parse(t.uri)
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
                    .build()
            )
            .build()
    }
}
