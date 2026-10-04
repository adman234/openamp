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

/** One row of Plex metadata. Albums and tracks share the shape. */
@Serializable
data class Item(
    val ratingKey: String,
    val title: String = "",
    val parentTitle: String = "",
    val grandparentTitle: String = "",
    val originalTitle: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val index: Int? = null,
    val parentIndex: Int? = null,
)

@Serializable
private data class Envelope(@SerialName("MediaContainer") val container: Container)

@Serializable
private data class Container(
    @SerialName("Directory") val sections: List<Section> = emptyList(),
    @SerialName("Metadata") val items: List<Item> = emptyList(),
)

/** An album as the app shows it, whether it came from Plex or from the download records. */
data class AlbumRef(val id: String, val title: String, val artist: String, val thumb: String?)

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
        }.map { AlbumRef(it.ratingKey, it.title, it.parentTitle, it.thumb) }

    suspend fun tracks(albumId: String): List<Item> = server("/library/metadata/$albumId/children").items

    /** A small square copy of the artwork. The token is added as a header by the image loader. */
    fun artUrl(thumb: String?): String? {
        val base = prefs.plexUrl
        if (thumb == null || base.isBlank()) return null
        return "$base/photo/:/transcode?width=320&height=320&minSize=1&upscale=1&url=${enc(thumb)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val PLEX_TV = "https://plex.tv/api/v2"
        const val PRODUCT = "OpenAmp"
    }
}
