package io.github.adman234.openamp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** One artist or album found in DroppedNeedle's search of the MusicBrainz catalogue. */
@Serializable
data class DnResult(
    val title: String = "",
    @SerialName("musicbrainz_id") val id: String = "",
    val artist: String? = null,
    val year: Int? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    val requested: Boolean = false,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("album_thumb_url") val albumThumbUrl: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    val disambiguation: String? = null,
    @SerialName("type_info") val typeInfo: String? = null,
)

@Serializable
data class DnSearch(val artists: List<DnResult> = emptyList(), val albums: List<DnResult> = emptyList())

/** One release in an artist's discography. */
@Serializable
data class DnRelease(
    val id: String? = null,
    val title: String? = null,
    val year: Int? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    val requested: Boolean = false,
)

@Serializable
data class DnReleases(
    val albums: List<DnRelease> = emptyList(),
    val eps: List<DnRelease> = emptyList(),
    val singles: List<DnRelease> = emptyList(),
)

/** A request DroppedNeedle is still working on. */
@Serializable
data class DnActive(
    @SerialName("musicbrainz_id") val id: String,
    @SerialName("artist_name") val artist: String = "",
    @SerialName("album_title") val album: String = "",
    val status: String = "",
    val progress: Double? = null,
    @SerialName("download_status") val downloadStatus: String? = null,
    @SerialName("error_message") val error: String? = null,
)

/** A request that has finished, one way or another. */
@Serializable
data class DnPast(
    @SerialName("musicbrainz_id") val id: String,
    @SerialName("artist_name") val artist: String = "",
    @SerialName("album_title") val album: String = "",
    val status: String = "",
    @SerialName("in_library") val inLibrary: Boolean = false,
)

@Serializable
private data class DnActiveList(val items: List<DnActive> = emptyList())

@Serializable
private data class DnPastList(val items: List<DnPast> = emptyList())

@Serializable
data class DnPin(@SerialName("pin_id") val pinId: Long, @SerialName("auth_url") val authUrl: String)

@Serializable
private data class DnPoll(val completed: Boolean = false, val token: String? = null)

@Serializable
private data class DnToken(val token: String)

@Serializable
private data class DnLogin(val username: String, val password: String)

@Serializable
private data class DnAlbumRequest(
    @SerialName("musicbrainz_id") val id: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
)

@Serializable
private data class DnAnswer(val success: Boolean = true, val message: String = "", val detail: String? = null)

class DnException(val code: Int, message: String) : IOException(message)

/**
 * Talks to a DroppedNeedle server, which does the searching, downloading and
 * filing. The app only asks for things and shows how they are going. What
 * lands in the music folder reaches the app through Plex like everything else.
 */
class DnApi(private val prefs: Prefs, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    private fun request(path: String): Request.Builder {
        val base = prefs.dnUrl
        if (base.isBlank()) throw IOException("The DroppedNeedle address is not set")
        return Request.Builder().url("$base/api/v1$path").header("Accept", "application/json")
            .apply { prefs.dnToken?.let { header("Authorization", "Bearer $it") } }
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching { json.decodeFromString<DnAnswer>(body).detail }.getOrNull()
                throw DnException(
                    resp.code,
                    when (resp.code) {
                        401 -> "DroppedNeedle wants you to sign in again."
                        else -> detail ?: "DroppedNeedle answered ${resp.code}."
                    },
                )
            }
            body
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private inline fun <reified T> body(value: T) = json.encodeToString(value).toRequestBody(jsonType)

    // Sign-in. DroppedNeedle runs the Plex sign-in itself and hands back its own session token.

    suspend fun plexPin(): DnPin = json.decodeFromString(call(request("/auth/plex/pin").post("".toRequestBody()).build()))

    /** The session token once the user has signed in, null until then. */
    suspend fun plexPoll(pin: DnPin): String? =
        json.decodeFromString<DnPoll>(call(request("/auth/plex/poll?pin_id=${pin.pinId}").build())).token

    suspend fun login(username: String, password: String): String =
        json.decodeFromString<DnToken>(call(request("/auth/login").post(body(DnLogin(username, password))).build())).token

    // Search and request

    suspend fun search(query: String): DnSearch =
        json.decodeFromString(call(request("/search?q=${enc(query)}&limit_artists=8&limit_albums=30").build()))

    suspend fun releases(artistId: String): DnReleases =
        json.decodeFromString(call(request("/artists/${enc(artistId)}/releases?limit=200").build()))

    /** Returns DroppedNeedle's own words on what it did with the request. */
    suspend fun requestAlbum(id: String, artist: String?, album: String?, year: Int?): String =
        json.decodeFromString<DnAnswer>(
            call(request("/requests/new").post(body(DnAlbumRequest(id, artist, album, year))).build())
        ).message

    suspend fun active(): List<DnActive> = json.decodeFromString<DnActiveList>(call(request("/requests/active").build())).items

    suspend fun past(): List<DnPast> =
        json.decodeFromString<DnPastList>(call(request("/requests/history?page_size=30&sort=newest").build())).items

    suspend fun cancel(id: String) {
        call(request("/requests/active/${enc(id)}").delete().build())
    }

    suspend fun retry(id: String) {
        call(request("/requests/retry/${enc(id)}").post("".toRequestBody()).build())
    }

    /** Cover for an album or artist. The session token is added as a header by the image loader. */
    fun cover(result: DnResult, artist: Boolean = false): String? {
        val base = prefs.dnUrl
        if (base.isBlank()) return null
        val given = result.coverUrl ?: result.albumThumbUrl ?: result.thumbUrl
        return when {
            given == null -> if (artist) "$base/api/v1/covers/artist/${result.id}" else albumCover(result.id)
            given.startsWith("/") -> base + given
            else -> given
        }
    }

    fun albumCover(releaseGroupId: String?): String? {
        val base = prefs.dnUrl
        if (base.isBlank() || releaseGroupId.isNullOrBlank()) return null
        return "$base/api/v1/covers/release-group/$releaseGroupId?size=250"
    }
}
