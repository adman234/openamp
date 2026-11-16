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
    // Null for a library hit DroppedNeedle could not match; such rows are dropped by [DnApi.search].
    @SerialName("musicbrainz_id") val id: String = "",
    val artist: String? = null,
    val year: Int? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    val requested: Boolean = false,
    /** True when DroppedNeedle knows there is no cover to fetch. */
    @SerialName("art_missing") val artMissing: Boolean = false,
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
    /** True while DroppedNeedle is still fetching the rest of a large discography. */
    val warming: Boolean = false,
)

/**
 * One request, live or finished. Requests made from DroppedNeedle's web app
 * can be for a single track, so a row is either an album or a track.
 */
@Serializable
data class DnRequest(
    @SerialName("musicbrainz_id") val id: String,
    @SerialName("request_kind") val kind: String = "album",
    @SerialName("artist_name") val artist: String = "",
    @SerialName("album_title") val album: String = "",
    @SerialName("track_title") val track: String? = null,
    @SerialName("track_release_group_mbid") val trackAlbumId: String? = null,
    val status: String = "",
    /** What the download itself is doing: searching, queued, downloading, importing... */
    @SerialName("task_state") val taskState: String? = null,
    /** Why a live request has not started yet. */
    @SerialName("waiting_reason") val waitingReason: String? = null,
    /** Percent, 0 to 100. */
    val progress: Double? = null,
    @SerialName("error_message") val error: String? = null,
    /** Null when DroppedNeedle cannot tell; an imported album is then taken to be there. */
    @SerialName("in_library") val inLibrary: Boolean? = null,
) {
    val isTrack get() = kind == "track"
    val title get() = if (isTrack) track ?: album else album
    /** Release group to show the cover of. */
    val coverId get() = if (isTrack) trackAlbumId else id
}

@Serializable
private data class DnRequestList(val items: List<DnRequest> = emptyList())

@Serializable
data class DnPin(
    @SerialName("pin_id") val pinId: Long,
    @SerialName("authorize_url") val authUrl: String,
    @SerialName("pin_secret") val secret: String,
)

@Serializable
private data class DnPlexPoll(
    @SerialName("pin_id") val pinId: Long,
    @SerialName("pin_secret") val secret: String,
    val transport: String,
)

@Serializable
private data class DnPollAnswer(val completed: Boolean = false, val token: String? = null)

@Serializable
private data class DnToken(val token: String? = null)

/** `transport` has no default so it is always sent: without "bearer" DroppedNeedle sets a cookie and returns no token. */
@Serializable
private data class DnLogin(val username: String, val password: String, val transport: String)

@Serializable
private data class DnAlbumRequest(
    @SerialName("musicbrainz_id") val id: String,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
)

@Serializable
private data class DnAnswer(val success: Boolean = true, val message: String = "")

@Serializable
private data class DnErrorBody(val code: String = "", val message: String = "")

@Serializable
private data class DnErrorEnvelope(val error: DnErrorBody? = null)

class DnException(val code: Int, message: String) : IOException(message)

/**
 * Talks to a DroppedNeedle v3 server, which does the searching, downloading and
 * filing. The app only asks for things and shows how they are going. What
 * lands in the music folder reaches the app through Plex like everything else.
 */
class DnApi(private val prefs: Prefs, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    private fun request(path: String): Request.Builder {
        val base = prefs.dnUrl
        if (base.isBlank()) throw IOException("The DroppedNeedle address is not set")
        return Request.Builder().url("$base$API$path").header("Accept", "application/json")
            .apply { prefs.dnToken?.let { header("Authorization", "Bearer $it") } }
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val error = runCatching { json.decodeFromString<DnErrorEnvelope>(body).error }.getOrNull()
                throw DnException(
                    resp.code,
                    when {
                        resp.code == 401 -> "DroppedNeedle wants you to sign in again."
                        resp.code == 404 && error == null -> "DroppedNeedle answered 404. Is it version 3 or newer?"
                        !error?.message.isNullOrBlank() -> error!!.message
                        else -> "DroppedNeedle answered ${resp.code}."
                    },
                )
            }
            body
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private inline fun <reified T> body(value: T) = json.encodeToString(value).toRequestBody(jsonType)

    private fun kindQuery(track: Boolean) = if (track) "?kind=track" else ""

    // Sign-in. DroppedNeedle runs the Plex sign-in itself and hands back its own session token.

    suspend fun plexPin(): DnPin = json.decodeFromString(call(request("/auth/plex/start").post("".toRequestBody()).build()))

    /** The session token once the user has signed in, null until then. */
    suspend fun plexPoll(pin: DnPin): String? {
        val answer = json.decodeFromString<DnPollAnswer>(
            call(request("/auth/plex/poll/login").post(body(DnPlexPoll(pin.pinId, pin.secret, "bearer"))).build())
        )
        if (answer.completed && answer.token == null) throw IOException("DroppedNeedle signed you in but sent no session token.")
        return answer.token
    }

    suspend fun login(username: String, password: String): String =
        json.decodeFromString<DnToken>(call(request("/auth/login").post(body(DnLogin(username, password, "bearer"))).build())).token
            ?: throw IOException("DroppedNeedle signed you in but sent no session token.")

    // Search and request

    /** Library hits DroppedNeedle could not match to MusicBrainz have no id to request or open, so they are left out. */
    suspend fun search(query: String): DnSearch {
        val found = json.decodeFromString<DnSearch>(call(request("/search?q=${enc(query)}&limit_artists=8&limit_albums=30").build()))
        return DnSearch(found.artists.filter { it.id.isNotBlank() }, found.albums.filter { it.id.isNotBlank() })
    }

    suspend fun releases(artistId: String): DnReleases =
        json.decodeFromString(call(request("/artists/${enc(artistId)}/releases?limit=200").build()))

    /** Returns DroppedNeedle's own words on what it did with the request. */
    suspend fun requestAlbum(id: String, artist: String?, album: String?, year: Int?): String =
        json.decodeFromString<DnAnswer>(
            call(request("/requests/albums").post(body(DnAlbumRequest(id, artist, album, year))).build())
        ).message

    suspend fun active(): List<DnRequest> = json.decodeFromString<DnRequestList>(call(request("/requests/active").build())).items

    suspend fun past(): List<DnRequest> =
        json.decodeFromString<DnRequestList>(call(request("/requests/history?page_size=30&sort=newest").build())).items

    suspend fun cancel(job: DnRequest) {
        call(request("/requests/active/${enc(job.id)}${kindQuery(job.isTrack)}").delete().build())
    }

    suspend fun retry(job: DnRequest) {
        call(request("/requests/retry/${enc(job.id)}${kindQuery(job.isTrack)}").post("".toRequestBody()).build())
    }

    /** Cover for an album or artist. The session token is added as a header by the image loader. */
    fun cover(result: DnResult, artist: Boolean = false): String? {
        val base = prefs.dnUrl
        if (base.isBlank() || result.id.isBlank() || result.artMissing) return null
        return if (artist) "$base$API/covers/artist/${enc(result.id)}" else albumCover(result.id)
    }

    fun albumCover(releaseGroupId: String?): String? {
        val base = prefs.dnUrl
        if (base.isBlank() || releaseGroupId.isNullOrBlank()) return null
        return "$base$API/covers/release-group/${enc(releaseGroupId)}?size=250"
    }

    companion object {
        /** DroppedNeedle v3's API root. v2's `/api/v1` is gone. */
        const val API = "/api/v3"
    }
}
