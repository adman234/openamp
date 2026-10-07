package io.github.adman234.openamp.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.adman234.openamp.OpenAmpApp
import io.github.adman234.openamp.data.AlbumRef
import io.github.adman234.openamp.data.DnActive
import io.github.adman234.openamp.data.DnException
import io.github.adman234.openamp.data.DnPast
import io.github.adman234.openamp.data.DnReleases
import io.github.adman234.openamp.data.DnResult
import io.github.adman234.openamp.data.DnSearch
import io.github.adman234.openamp.data.ArtistRef
import io.github.adman234.openamp.data.Item
import io.github.adman234.openamp.data.LocalPlaylist
import io.github.adman234.openamp.data.LocalTrack
import io.github.adman234.openamp.data.PlaylistRef
import io.github.adman234.openamp.data.Resource
import io.github.adman234.openamp.data.artFile
import io.github.adman234.openamp.playback.Media
import io.github.adman234.openamp.playback.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface Screen {
    data object Setup : Screen
    data object Browse : Screen
    data object Downloads : Screen

    /** Search the catalogue and request music through DroppedNeedle. */
    data object Requests : Screen

    /** One catalogue artist's releases, each of which can be requested. */
    data class RequestArtist(val id: String, val name: String) : Screen

    /** Every album of one home screen row, reached with "See all". */
    data class Section(val mode: BrowseMode) : Screen

    /** One artist's photo and albums. */
    data class Artist(val name: String, val thumb: String?) : Screen

    /** The tracks of one album or one playlist. */
    data class Tracks(val album: AlbumRef? = null, val playlist: PlaylistRef? = null) : Screen
}

enum class BrowseMode(val label: String) {
    Home("Home"), Albums("Albums"), Artists("Artists"), Playlists("Playlists"),
    RecentlyPlayed("Recently played"), MostPlayed("Most played"), Downloads("Downloads"), RecentlyAdded("Recently added"),
}

enum class SortBy(val label: String) {
    Title("Title"), Artist("Artist"), Recent("Recently added"), Year("Year")
}

/** What a long press opened the menu for. */
sealed interface MenuTarget {
    data class OfAlbum(val album: AlbumRef) : MenuTarget
    data class OfTrack(val source: Screen.Tracks, val item: Item?, val local: LocalTrack?) : MenuTarget
}

/** One row of the play queue. uid stays the same when rows are reordered. */
data class QueueEntry(val uid: String, val title: String, val artist: String, val albumId: String?)

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
    val history = app.history.plays
    val downloadQueue = app.downloader.queue
    val downloadProgress = app.downloader.progress
    val downloadsPaused = app.downloader.paused
    val downloadNotice = app.downloader.notice

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
    var folderNotice by mutableStateOf<String?>(null)
        private set
    var quality by mutableStateOf(prefs.quality)
        private set
    var allowMobile by mutableStateOf(prefs.allowMobile)
        private set
    var theme by mutableStateOf(prefs.theme)
        private set
    var leveling by mutableStateOf(prefs.leveling)
        private set
    var grid by mutableStateOf(prefs.grid)
        private set
    var reportPlays by mutableStateOf(prefs.reportPlays)
        private set
    var menuModes by mutableStateOf(prefs.menuModes)
        private set

    /** The browse menu's entries. Home is always there. */
    val visibleModes: List<BrowseMode>
        get() = BrowseMode.entries.filter { it == BrowseMode.Home || it.name in menuModes }

    val configured: Boolean
        get() = signedIn && validUrl(plexUrl) && validUrl(fileUrl) && folderUri.isNotBlank()

    private var stack by mutableStateOf<List<Screen>>(listOf(if (configured) Screen.Browse else Screen.Setup))
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var mode by mutableStateOf(BrowseMode.Home)
    var sort by mutableStateOf(SortBy.Title)
    var query by mutableStateOf("")
    var onlyDownloaded by mutableStateOf(false)
    var menu by mutableStateOf<MenuTarget?>(null)

    // Requests through DroppedNeedle
    var dnUrl by mutableStateOf(prefs.dnUrl)
        private set
    var dnSignedIn by mutableStateOf(prefs.dnToken != null && prefs.dnUrl.isNotBlank())
        private set
    var dnSigningIn by mutableStateOf(false)
        private set
    var dnBusy by mutableStateOf(false)
        private set
    var dnError by mutableStateOf<String?>(null)
        private set
    var dnNotice by mutableStateOf<String?>(null)
        private set
    var dnQuery by mutableStateOf("")
    var dnResults by mutableStateOf<DnSearch?>(null)
        private set
    var dnReleases by mutableStateOf<DnReleases?>(null)
        private set
    var dnActive by mutableStateOf<List<DnActive>>(emptyList())
        private set
    var dnPast by mutableStateOf<List<DnPast>>(emptyList())
        private set

    /** Albums requested from this screen, so their buttons change at once. */
    var dnRequested by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The request page is offered once a DroppedNeedle address has been entered. */
    val dnReady: Boolean get() = validUrl(dnUrl)

    var albums by mutableStateOf<List<AlbumRef>>(emptyList())
        private set
    var artists by mutableStateOf<List<ArtistRef>>(emptyList())
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
    var nowAlbum by mutableStateOf("")
        private set
    var nowAlbumId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var queue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var queueIndex by mutableStateOf(0)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set

    /** The main colour of the playing album's artwork, as ARGB, for the full player's background. */
    var artColor by mutableStateOf<Int?>(null)
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
                    override fun onEvents(player: Player, events: Player.Events) =
                        readPlayer(player, events.contains(Player.EVENT_TIMELINE_CHANGED))
                })
                readPlayer(c, true)
            }
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            while (true) {
                controller?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0)
                    durationMs = it.duration.coerceAtLeast(0)
                }
                delay(500)
            }
        }
        // A queue left over from last time continues now that the app is open.
        app.downloader.kick()
    }

    private fun readPlayer(player: Player, timelineChanged: Boolean) {
        val meta = player.currentMediaItem?.mediaMetadata
        val albumId = meta?.extras?.getString(Media.ALBUM_ID)
        nowId = player.currentMediaItem?.mediaId
        nowTitle = meta?.title?.toString().orEmpty()
        nowArtist = meta?.artist?.toString().orEmpty()
        nowAlbum = meta?.albumTitle?.toString().orEmpty()
        isPlaying = player.isPlaying
        shuffle = player.shuffleModeEnabled
        repeatMode = player.repeatMode
        queueIndex = player.currentMediaItemIndex
        if (timelineChanged) {
            val seen = HashMap<String, Int>()
            queue = (0 until player.mediaItemCount).map { i ->
                val item = player.getMediaItemAt(i)
                val n = seen.merge(item.mediaId, 1, Int::plus)
                QueueEntry(
                    "${item.mediaId}#$n",
                    item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty(),
                    item.mediaMetadata.extras?.getString(Media.ALBUM_ID),
                )
            }
        }
        if (albumId != nowAlbumId) {
            nowAlbumId = albumId
            readArtColor(albumId)
        }
    }

    private fun readArtColor(albumId: String?) {
        viewModelScope.launch {
            artColor = withContext(Dispatchers.IO) {
                val file = albumId?.let { artFile(app, it) }?.takeIf { it.exists() } ?: return@withContext null
                runCatching {
                    val small = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })
                    val tiny = Bitmap.createScaledBitmap(small, 16, 16, true)
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    for (x in 0 until 16) for (y in 0 until 16) {
                        val p = tiny.getPixel(x, y)
                        r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                    }
                    Color.rgb((r / 256).toInt(), (g / 256).toInt(), (b / 256).toInt())
                }.getOrNull()
            }
        }
    }

    override fun onCleared() {
        MediaController.releaseFuture(controllerFuture)
    }

    // Navigation

    fun open(next: Screen) {
        if (screen != next) stack = stack + next
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
        artists = emptyList()
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

    /** Choosing a folder that an earlier install downloaded into brings those downloads back. */
    fun updateFolder(uri: Uri) {
        folderUri = uri.toString()
        prefs.folderUri = folderUri
        folderNotice = "Looking for earlier downloads in this folder."
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { app.store.importIndex(folderUri) }.getOrDefault(0) }
            folderNotice = when (found) {
                0 -> null
                1 -> "Found 1 downloaded track in this folder."
                else -> "Found $found downloaded tracks in this folder."
            }
            fetchMissingArt()
        }
    }

    fun updateQuality(v: String) { quality = v; prefs.quality = v }
    fun updateAllowMobile(v: Boolean) { allowMobile = v; prefs.allowMobile = v; if (v) app.downloader.kick() }
    fun updateTheme(v: String) { theme = v; prefs.theme = v }
    fun updateGrid(v: Boolean) { grid = v; prefs.grid = v }
    fun updateReportPlays(v: Boolean) { reportPlays = v; prefs.reportPlays = v }

    fun setMenuMode(item: BrowseMode, on: Boolean) {
        if (item == BrowseMode.Home) return
        menuModes = if (on) menuModes + item.name else menuModes - item.name
        prefs.menuModes = menuModes
        if (!on && mode == item) mode = BrowseMode.Home
    }

    fun updateLeveling(v: Boolean) {
        leveling = v
        prefs.leveling = v
        if (v) viewModelScope.launch { fillGains() }
    }

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
                artists = runCatching { plex.artists() }.getOrDefault(emptyList())
                playlists = runCatching { plex.playlists() }.getOrDefault(emptyList())
            } catch (e: Exception) {
                error = "Could not reach Plex, showing downloads only. ${e.message}"
                return@launch
            } finally {
                loading = false
            }
            fetchMissingArt()
            runCatching { syncPlaylists() }
            if (leveling) fillGains()
        }
    }

    /** After a reinstall the records come back from the folder but the artwork does not. */
    private fun fetchMissingArt() {
        local.value.groupBy { it.albumId }.values.map { it.first() }
            .filter { !artFile(app, it.albumId).exists() }
            .forEach { app.downloader.fetchArt(AlbumRef(it.albumId, it.album, it.albumArtist, it.thumb)) }
    }

    /** Tracks downloaded before leveling existed get their gain from the file service. */
    private suspend fun fillGains() {
        val missing = local.value.filter { it.gain == null }.map { it.id }
        if (missing.isEmpty()) return
        val gains = runCatching { plex.gains(missing) }.getOrNull() ?: return
        val found = gains.filter { it.gain != null }.associate { it.id to (it.gain to it.albumGain) }
        if (found.isNotEmpty()) withContext(Dispatchers.IO) { runCatching { app.store.setGains(found) } }
    }

    /** Playlists marked to stay in sync get their new tracks queued for download. */
    private suspend fun syncPlaylists() {
        for (saved in localPlaylists.value.filter { it.sync }) {
            val items = runCatching { plex.playlistTracks(saved.id) }.getOrNull() ?: continue
            app.store.savePlaylist(saved.copy(trackIds = items.map { it.ratingKey }))
            enqueue(items.map { albumOf(it) to it })
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
            // Keep a saved playlist's track list in step with the server.
            localPlaylists.value.firstOrNull { it.id == playlist.id }?.let { saved ->
                runCatching { app.store.savePlaylist(saved.copy(trackIds = loaded.map { it.ratingKey })) }
            }
        }
    }

    fun openArtist(name: String) {
        if (name.isBlank()) return
        open(Screen.Artist(name, artists.firstOrNull { it.name == name }?.thumb))
    }

    /** The album a track belongs to, from the library when loaded, otherwise from the track's own details. */
    fun albumOf(item: Item): AlbumRef =
        albums.firstOrNull { it.id == item.parentRatingKey }
            ?: AlbumRef(
                id = item.parentRatingKey ?: "unknown",
                title = item.parentTitle.ifBlank { "Unknown album" },
                artist = item.grandparentTitle,
                thumb = item.parentThumb ?: item.thumb,
            )

    fun albumTracks(albumId: String): List<LocalTrack> =
        local.value.filter { it.albumId == albumId }.sortedWith(compareBy({ it.disc }, { it.index }))

    // Downloads

    private fun enqueue(pairs: List<Pair<AlbumRef, Item>>) {
        if (pairs.isNotEmpty()) app.downloader.enqueue(pairs, quality)
    }

    /** Downloads tracks from the open album or playlist. */
    fun download(source: Screen.Tracks, items: List<Item>) {
        if (items.isEmpty()) return
        source.playlist?.let { p ->
            val saved = localPlaylists.value.firstOrNull { it.id == p.id }
            runCatching {
                app.store.savePlaylist(LocalPlaylist(p.id, p.title, tracks.map { it.ratingKey }, saved?.sync ?: false))
            }
        }
        enqueue(items.map { (source.album ?: albumOf(it)) to it })
    }

    /** Downloads a whole album without opening it. */
    fun downloadAlbum(album: AlbumRef) {
        viewModelScope.launch {
            val items = runCatching { plex.tracks(album.id) }.getOrNull()
            if (items == null) error = "Could not reach Plex to download \"${album.title}\"."
            else enqueue(items.map { album to it })
        }
    }

    fun removeDownloads(tracks: List<LocalTrack>) {
        if (tracks.isNotEmpty()) app.downloader.remove(tracks)
    }

    fun setPlaylistSync(playlist: PlaylistRef, on: Boolean) {
        val saved = localPlaylists.value.firstOrNull { it.id == playlist.id }
        val ids = if (tracks.isNotEmpty()) tracks.map { it.ratingKey } else saved?.trackIds.orEmpty()
        runCatching { app.store.savePlaylist(LocalPlaylist(playlist.id, playlist.title, ids, on)) }
        if (on) enqueue(tracks.map { albumOf(it) to it })
    }

    fun pauseDownloads(paused: Boolean) = app.downloader.setPaused(paused)
    fun cancelDownload(trackId: String) = app.downloader.cancel(trackId)
    fun cancelAllDownloads() = app.downloader.cancelAll()
    fun retryDownloads() = app.downloader.retryFailed()

    // Requests through DroppedNeedle

    fun updateDnUrl(v: String) {
        dnUrl = v
        prefs.dnUrl = if (validUrl(v)) normalizeUrl(v) else ""
    }

    fun settleDnUrl() {
        dnUrl = normalizeUrl(dnUrl)
    }

    private fun dnDropSession() {
        prefs.dnToken = null
        dnSignedIn = false
    }

    /** Runs one call to DroppedNeedle with the busy line and error text handled in one place. */
    private fun dnRun(block: suspend () -> Unit) {
        viewModelScope.launch {
            dnBusy = true
            dnError = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DnException) {
                if (e.code == 401) dnDropSession()
                dnError = e.message
            } catch (e: Exception) {
                dnError = "Could not reach DroppedNeedle. ${e.message}"
            } finally {
                dnBusy = false
            }
        }
    }

    /** DroppedNeedle runs the Plex sign-in itself. The app opens the page it gives and waits for the result. */
    fun dnSignInPlex(openBrowser: (String) -> Unit) {
        if (dnSigningIn) return
        dnSigningIn = true
        dnError = null
        viewModelScope.launch {
            try {
                val pin = app.dn.plexPin()
                openBrowser(pin.authUrl)
                repeat(150) {
                    delay(2_000)
                    val token = app.dn.plexPoll(pin)
                    if (token != null) {
                        prefs.dnToken = token
                        dnSignedIn = true
                        return@launch
                    }
                }
                dnError = "Sign-in timed out. Try again."
            } catch (e: DnException) {
                dnError = if (e.code == 403) "DroppedNeedle does not allow this Plex account." else e.message
            } catch (e: Exception) {
                dnError = "Could not reach DroppedNeedle. ${e.message}"
            } finally {
                dnSigningIn = false
            }
        }
    }

    fun dnSignInPassword(username: String, password: String) = dnRun {
        prefs.dnToken = app.dn.login(username.trim(), password)
        dnSignedIn = true
    }

    fun dnSignOut() {
        dnDropSession()
        dnResults = null
        dnActive = emptyList()
        dnPast = emptyList()
    }

    fun dnSearch() {
        val query = dnQuery.trim()
        dnNotice = null
        if (query.isEmpty()) {
            dnResults = null
            return
        }
        dnRun {
            val found = app.dn.search(query)
            dnResults = DnSearch(found.artists.distinctBy { it.id }, found.albums.distinctBy { it.id })
        }
    }

    fun dnClearSearch() {
        dnQuery = ""
        dnResults = null
        dnNotice = null
        dnError = null
    }

    fun dnOpenArtist(id: String, name: String) {
        dnReleases = null
        dnNotice = null
        open(Screen.RequestArtist(id, name))
        dnRun { dnReleases = app.dn.releases(id) }
    }

    fun dnRequest(id: String, artist: String?, album: String?, year: Int?) = dnRun {
        val answer = app.dn.requestAlbum(id, artist, album, year)
        dnRequested = dnRequested + id
        dnNotice = answer.ifBlank { "Requested." }
        dnRefresh()
    }

    /** Fetches the request lists quietly. When a request leaves the active list, the Plex library is read again. */
    fun dnRefresh() {
        if (!dnSignedIn) return
        viewModelScope.launch {
            try {
                val before = dnActive.mapTo(HashSet()) { it.id }
                val active = app.dn.active().distinctBy { it.id }
                dnActive = active
                dnPast = app.dn.past().distinctBy { it.id }
                val now = active.mapTo(HashSet()) { it.id }
                if (before.any { it !in now }) loadLibrary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DnException) {
                if (e.code == 401) dnDropSession()
            } catch (e: Exception) {
                // A missed refresh is retried a few seconds later.
            }
        }
    }

    fun dnCancel(id: String) = dnRun {
        app.dn.cancel(id)
        dnRefresh()
    }

    fun dnRetry(id: String) = dnRun {
        app.dn.retry(id)
        dnNotice = "Trying again."
        dnRefresh()
    }

    fun dnCover(result: DnResult, artist: Boolean = false): String? = app.dn.cover(result, artist)
    fun dnAlbumCover(id: String?): String? = app.dn.albumCover(id)

    // Playback

    fun play(tracks: List<LocalTrack>, startIndex: Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.setMediaItems(tracks.map { Media.item(app, it) }, startIndex.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    /** Puts tracks right after the one that is playing. */
    fun playNext(tracks: List<LocalTrack>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) return play(tracks, 0)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { Media.item(app, it) })
    }

    /** Puts tracks at the end of the queue. */
    fun addToQueue(tracks: List<LocalTrack>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) return play(tracks, 0)
        c.addMediaItems(tracks.map { Media.item(app, it) })
    }

    fun jumpTo(index: Int) {
        controller?.let { it.seekTo(index, 0L); it.play() }
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from != to) controller?.moveMediaItem(from, to)
    }

    fun removeFromQueue(index: Int) {
        if (index >= 0) controller?.removeMediaItem(index)
    }

    fun clearQueue() {
        controller?.clearMediaItems()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        positionMs = ms
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
}
