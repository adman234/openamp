package io.github.adman234.openamp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

@Serializable
data class Pin(val id: Long, val code: String, val authToken: String? = null)

@Serializable
data class Connection(val uri: String, val local: Boolean = false, val relay: Boolean = false)

@Serializable
data class Resource(
    val name: String,
    val provides: String = "",
    val accessToken: String? = null,
    val connections: List<Connection> = emptyList(),
)

@Serializable
data class Section(val key: String, val type: String = "", val title: String = "")

@Serializable
data class Tag(val tag: String = "")

/** One row of Plex metadata. Albums, tracks and playlists share the shape. */
@Serializable
data class Item(
    val ratingKey: String,
    val title: String = "",
    val parentRatingKey: String? = null,
    val parentTitle: String = "",
    val grandparentTitle: String = "",
    val originalTitle: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val composite: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
    val year: Int? = null,
    val addedAt: Long? = null,
    val leafCount: Int? = null,
    @SerialName("Genre") val genres: List<Tag> = emptyList(),
)

@Serializable
private data class Envelope(@SerialName("MediaContainer") val container: Container)

@Serializable
private data class Container(
    @SerialName("Directory") val sections: List<Section> = emptyList(),
    @SerialName("Metadata") val items: List<Item> = emptyList(),
)

/** An album as the app shows it, whether it came from Plex or from the download records. */
@Serializable
data class AlbumRef(
    val id: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    val year: Int? = null,
    val addedAt: Long = 0,
    val genres: List<String> = emptyList(),
)

data class PlaylistRef(val id: String, val title: String, val thumb: String?, val count: Int)

data class ArtistRef(val name: String, val thumb: String?)

@Serializable
data class TrackGain(val id: String, val gain: Float? = null, val albumGain: Float? = null)

@Serializable
private data class InfoAnswer(val tracks: List<TrackGain> = emptyList())

class PlexApi(private val prefs: Prefs, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private fun request(url: String, token: String?) = Request.Builder().url(url)
        .header("Accept", "application/json")
        .header("X-Plex-Product", PRODUCT)
        .header("X-Plex-Client-Identifier", prefs.clientId)
        .apply { if (token != null) header("X-Plex-Token", token) }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("${req.url.host} answered ${resp.code}")
            resp.body?.string() ?: throw IOException("${req.url.host} sent an empty answer")
        }
    }

    suspend fun createPin(): Pin =
        json.decodeFromString(call(request("$PLEX_TV/pins?strong=true", null).post("".toRequestBody()).build()))

    fun authUrl(pin: Pin): String =
        "https://app.plex.tv/auth#?clientID=${enc(prefs.clientId)}&code=${enc(pin.code)}" +
            "&context%5Bdevice%5D%5Bproduct%5D=$PRODUCT"

    /** Returns the account token once the user has signed in, null until then. */
    suspend fun checkPin(pin: Pin): String? =
        json.decodeFromString<Pin>(call(request("$PLEX_TV/pins/${pin.id}", null).build())).authToken

    suspend fun servers(accountToken: String): List<Resource> =
        json.decodeFromString<List<Resource>>(
            call(request("$PLEX_TV/resources?includeHttps=1&includeRelay=0", accountToken).build())
        ).filter { "server" in it.provides.split(",") }

    private suspend fun server(path: String): Container {
        val base = prefs.plexUrl
        if (base.isBlank()) throw IOException("The Plex address is not set")
        return json.decodeFromString<Envelope>(call(request(base + path, prefs.token).build())).container
    }

    suspend fun albums(): List<AlbumRef> =
        server("/library/sections").sections.filter { it.type == "artist" }.flatMap { section ->
            // type=9 is album.
            server("/library/sections/${section.key}/all?type=9").items
        }.map { album ->
            AlbumRef(
                id = album.ratingKey,
                title = album.title,
                artist = album.parentTitle,
                thumb = album.thumb,
                year = album.year,
                addedAt = album.addedAt ?: 0,
                genres = album.genres.map { it.tag }.filter { it.isNotBlank() },
            )
        }

    /** Artists with their photos. type=8 is artist. */
    suspend fun artists(): List<ArtistRef> =
        server("/library/sections").sections.filter { it.type == "artist" }.flatMap { section ->
            server("/library/sections/${section.key}/all?type=8").items
        }.map { ArtistRef(it.title, it.thumb) }

    /** Tells Plex a track was played, which updates its play count and last played time. */
    suspend fun scrobble(trackId: String) {
        val base = prefs.plexUrl
        if (base.isBlank()) throw IOException("The Plex address is not set")
        call(request("$base/:/scrobble?key=$trackId&identifier=com.plexapp.plugins.library", prefs.token).build())
    }

    /** Loudness gain for downloaded tracks, from the file service. */
    suspend fun gains(ids: List<String>): List<TrackGain> {
        val base = prefs.fileServiceUrl
        if (base.isBlank() || ids.isEmpty()) return emptyList()
        return ids.chunked(200).flatMap { chunk ->
            json.decodeFromString<InfoAnswer>(
                call(request("$base/v1/tracks/info?ids=${chunk.joinToString(",")}", prefs.token).build())
            ).tracks
        }
    }

    suspend fun tracks(albumId: String): List<Item> = server("/library/metadata/$albumId/children").items

    suspend fun playlists(): List<PlaylistRef> = server("/playlists?playlistType=audio").items
        .map { PlaylistRef(it.ratingKey, it.title, it.composite ?: it.thumb, it.leafCount ?: 0) }

    suspend fun playlistTracks(playlistId: String): List<Item> = server("/playlists/$playlistId/items").items

    /** A square copy of the artwork. The token is added as a header by whoever fetches it. */
    fun artUrl(thumb: String?, size: Int = 320): String? {
        val base = prefs.plexUrl
        if (thumb == null || base.isBlank()) return null
        return "$base/photo/:/transcode?width=$size&height=$size&minSize=1&upscale=1&url=${enc(thumb)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val PLEX_TV = "https://plex.tv/api/v2"
        const val PRODUCT = "OpenAmp"
    }
}
