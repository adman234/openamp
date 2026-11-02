package io.github.adman234.openamp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.adman234.openamp.OpenAmpApp
import io.github.adman234.openamp.data.LocalTrack
import io.github.adman234.openamp.data.Resume
import io.github.adman234.openamp.data.artFile
import io.github.adman234.openamp.data.readJson
import io.github.adman234.openamp.data.writeJson
import io.github.adman234.openamp.ui.MainActivity
import io.github.adman234.openamp.widget.PlayerWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.pow

/** Builds the player's items from download records, the same way everywhere. */
object Media {
    const val ALBUM_ID = "albumId"
    const val GAIN = "gain"

    fun item(context: Context, t: LocalTrack): MediaItem {
        val uri = Uri.parse(t.uri)
        // A file on the phone, so the notification and lock screen show it with no connection.
        val art = artFile(context, t.albumId).takeIf { it.exists() }?.let(Uri::fromFile)
        val extras = Bundle().apply {
            putString(ALBUM_ID, t.albumId)
            t.gain?.let { putFloat(GAIN, it) }
        }
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
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
}

/**
 * Owns the player. The media session gives the notification, lock screen,
 * Bluetooth and widget controls, and the library half lets Android Auto browse
 * what is downloaded.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var app: OpenAmpApp
    private lateinit var player: ExoPlayer
    private var lastId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            saveResume()
            handler.postDelayed(this, 10_000)
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "leveling") applyGain()
    }

    override fun onCreate() {
        super.onCreate()
        app = application as OpenAmpApp
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Pick up where the last session stopped, paused. Done before the
        // listener is added so that it does not count as a new play.
        val saved = savedQueue()
        if (saved.mediaItems.isNotEmpty()) {
            val resume = app.resume.load()
            player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
            player.shuffleModeEnabled = resume.shuffle
            player.repeatMode = resume.repeat
            player.prepare()
            lastId = player.currentMediaItem?.mediaId
        }
        applyGain()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = lastId
                lastId = mediaItem?.mediaId
                // A track that ran to its end counts as played. A skip does not.
                if (previous != null &&
                    (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                ) scrobble(previous)
                mediaItem?.mediaMetadata?.extras?.getString(Media.ALBUM_ID)?.let(app.history::record)
                applyGain()
                saveResume()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) lastId?.let(::scrobble)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saveTick)
                if (isPlaying) handler.postDelayed(saveTick, 10_000) else saveResume()
            }

            override fun onEvents(player: Player, events: Player.Events) = pushWidget()
        })
        app.prefs.register(prefListener)
        app.sleepTimer.onFire = { player.pause() }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setSessionActivity(open).build()
        flushScrobbles()
        pushWidget()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        app.prefs.unregister(prefListener)
        app.sleepTimer.onFire = null
        app.sleepTimer.cancel()
        saveResume()
        PlayerWidget.now = null
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private fun tracksById(): Map<String, LocalTrack> = app.store.tracks.value.associateBy { it.id }

    private fun savedQueue(): MediaItemsWithStartPosition {
        val resume = app.resume.load()
        val byId = tracksById()
        val current = resume.ids.getOrNull(resume.index)
        // Tracks removed since then drop out, and the position follows the track that was playing.
        val kept = resume.ids.filter { it in byId }
        val index = kept.indexOf(current)
        return MediaItemsWithStartPosition(
            kept.map { Media.item(this, byId.getValue(it)) },
            index.coerceAtLeast(0),
            if (index >= 0) resume.position else 0L,
        )
    }

    private fun saveResume() {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        app.resume.save(
            Resume(ids, player.currentMediaItemIndex, player.currentPosition, player.shuffleModeEnabled, player.repeatMode)
        )
    }

    /** Volume leveling. Each track carries the gain Plex worked out for it; the player can only turn down, so boosts are ignored. */
    private fun applyGain() {
        val extras = player.currentMediaItem?.mediaMetadata?.extras
        val gain = if (app.prefs.leveling && extras?.containsKey(Media.GAIN) == true) extras.getFloat(Media.GAIN) else null
        player.volume = if (gain == null) 1f else 10f.pow(gain / 20f).coerceIn(0.05f, 1f)
    }

    private var lastWidget: PlayerWidget.Now? = null

    private fun pushWidget() {
        val meta = player.currentMediaItem?.mediaMetadata
        val now = meta?.let {
            PlayerWidget.Now(
                it.title?.toString().orEmpty(),
                it.artist?.toString().orEmpty(),
                it.extras?.getString(Media.ALBUM_ID),
                player.isPlaying,
            )
        }
        if (now == lastWidget) return
        lastWidget = now
        PlayerWidget.now = now
        PlayerWidget.refresh(this)
    }

    // Plays are reported to Plex. With no connection they wait in a file and go out later.

    private val scrobbleFile by lazy { File(filesDir, "scrobbles.json") }

    private fun scrobble(trackId: String) {
        if (!app.prefs.reportPlays) return
        synchronized(scrobbleFile) {
            runCatching { writeJson(scrobbleFile, readJson<List<String>>(scrobbleFile, emptyList()) + trackId) }
        }
        flushScrobbles()
    }

    private fun flushScrobbles() {
        app.scope.launch(Dispatchers.IO) {
            while (true) {
                val next = synchronized(scrobbleFile) { readJson<List<String>>(scrobbleFile, emptyList()).firstOrNull() } ?: break
                if (runCatching { app.plex.scrobble(next) }.isFailure) break
                synchronized(scrobbleFile) {
                    runCatching { writeJson(scrobbleFile, readJson<List<String>>(scrobbleFile, emptyList()).drop(1)) }
                }
            }
        }
    }

    // Android Auto and other browsers see what is on the phone: albums and saved playlists.

    private fun children(parentId: String): List<MediaItem> {
        val tracks = app.store.tracks.value
        return when {
            parentId == ROOT -> listOf(Media.folder("albums", "Albums"), Media.folder("playlists", "Playlists"))
            parentId == "albums" -> tracks.groupBy { it.albumId }.values
                .map { it.first() }.sortedBy { it.album.lowercase() }
                .map { Media.folder("album:${it.albumId}", it.album, it.albumArtist) }
            parentId == "playlists" -> app.store.playlists.value.sortedBy { it.title.lowercase() }
                .map { Media.folder("playlist:${it.id}", it.title) }
            parentId.startsWith("album:") -> albumTracks(parentId.removePrefix("album:")).map { Media.item(this, it) }
            parentId.startsWith("playlist:") -> {
                val byId = tracks.associateBy { it.id }
                app.store.playlists.value.firstOrNull { it.id == parentId.removePrefix("playlist:") }
                    ?.trackIds.orEmpty().mapNotNull { byId[it] }.map { Media.item(this, it) }
            }
            else -> emptyList()
        }
    }

    private fun albumTracks(albumId: String): List<LocalTrack> =
        app.store.tracks.value.filter { it.albumId == albumId }.sortedWith(compareBy({ it.disc }, { it.index }))

    /** Items from a controller arrive without their file location. It is restored from the download records. */
    private fun resolve(item: MediaItem, byId: Map<String, LocalTrack>): MediaItem =
        byId[item.mediaId]?.let { Media.item(this, it) }
            ?: item.buildUpon().setUri(item.requestMetadata.mediaUri).build()

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Media.folder(ROOT, "OpenAmp"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children(parentId)), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val track = tracksById()[mediaId]
            return Futures.immediateFuture(
                if (track != null) LibraryResult.ofItem(Media.item(this@PlaybackService, track), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val byId = tracksById()
            return Futures.immediateFuture(mediaItems.map { resolve(it, byId) }.toMutableList())
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val byId = tracksById()
            // A single track picked in Android Auto plays its whole album from that track.
            val only = mediaItems.singleOrNull()
            val picked = only?.takeIf { it.requestMetadata.mediaUri == null }?.let { byId[it.mediaId] }
            if (picked != null) {
                val album = albumTracks(picked.albumId)
                return Futures.immediateFuture(
                    MediaItemsWithStartPosition(
                        album.map { Media.item(this@PlaybackService, it) },
                        album.indexOfFirst { it.id == picked.id }.coerceAtLeast(0),
                        0L,
                    )
                )
            }
            return Futures.immediateFuture(
                MediaItemsWithStartPosition(mediaItems.map { resolve(it, byId) }, startIndex, startPositionMs)
            )
        }

        /** A play button pressed while nothing is loaded (widget, headset, car) continues the last queue. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = Futures.immediateFuture(savedQueue())
    }

    private companion object {
        const val ROOT = "root"
    }
}
