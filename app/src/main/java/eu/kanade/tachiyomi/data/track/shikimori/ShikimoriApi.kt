package eu.kanade.tachiyomi.data.track.shikimori

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class ShikimoriApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    interceptor: ShikimoriInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track, userId: String): Track {
        return withIOContext {
            with(json) {
                val payload = buildJsonObject {
                    putJsonObject("user_rate") {
                        put("user_id", userId)
                        put("target_id", track.remote_id)
                        put("target_type", "Manga")
                        put("chapters", track.last_chapter_read.toInt())
                        put("score", track.score.toInt())
                        put("status", track.toShikimoriStatus())
                    }
                }
                authClient.newCall(
                    POST(
                        "$API_URL/v2/user_rates",
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).awaitSuccess()
                    .parseAs<JsonObject>()
                    .let {
                        // save id of the entry for possible future delete request
                        track.library_id = it["id"]!!.jsonPrimitive.long
                    }
                track
            }
        }
    }

    suspend fun updateLibManga(track: Track, userId: String): Track = addLibManga(track, userId)

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            authClient
                .newCall(DELETE("$API_URL/v2/user_rates/${track.libraryId}"))
                .awaitSuccess()
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val url = "$API_URL/mangas".toUri().buildUpon()
                .appendQueryParameter("order", "popularity")
                .appendQueryParameter("search", search)
                .appendQueryParameter("limit", "20")
                .build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<JsonArray>()
                    .let { response ->
                        response.map {
                            jsonToSearch(it.jsonObject)
                        }
                    }
            }
        }
    }

    private fun jsonToSearch(obj: JsonObject): TrackSearch {
        return TrackSearch.create(trackId).apply {
            remote_id = obj["id"]!!.jsonPrimitive.long
            title = obj["name"]!!.jsonPrimitive.content
            total_chapters = obj["chapters"]!!.jsonPrimitive.long
            cover_url = BASE_URL + obj["image"]!!.jsonObject["preview"]!!.jsonPrimitive.content
            summary = ""
            score = obj["score"]!!.jsonPrimitive.double
            tracking_url = BASE_URL + obj["url"]!!.jsonPrimitive.content
            publishing_status = obj["status"]!!.jsonPrimitive.content
            publishing_type = obj["kind"]!!.jsonPrimitive.content
            start_date = obj["aired_on"]!!.jsonPrimitive.contentOrNull ?: ""
        }
    }

    private fun jsonToTrack(obj: JsonObject, mangas: JsonObject): Track {
        return Track.create(trackId).apply {
            title = mangas["name"]!!.jsonPrimitive.content
            remote_id = obj["id"]!!.jsonPrimitive.long
            total_chapters = mangas["chapters"]!!.jsonPrimitive.long
            library_id = obj["id"]!!.jsonPrimitive.long
            last_chapter_read = obj["chapters"]!!.jsonPrimitive.double
            score = obj["score"]!!.jsonPrimitive.int.toDouble()
            status = toTrackStatus(obj["status"]!!.jsonPrimitive.content)
            tracking_url = BASE_URL + mangas["url"]!!.jsonPrimitive.content
        }
    }

    suspend fun findLibManga(track: Track, userId: String): Track? {
        return withIOContext {
            val urlMangas = "$API_URL/mangas".toUri().buildUpon()
                .appendPath(track.remote_id.toString())
                .build()
            val mangas = with(json) {
                authClient.newCall(GET(urlMangas.toString()))
                    .awaitSuccess()
                    .parseAs<JsonObject>()
            }

            val url = "$API_URL/v2/user_rates".toUri().buildUpon()
                .appendQueryParameter("user_id", userId)
                .appendQueryParameter("target_id", track.remote_id.toString())
                .appendQueryParameter("target_type", "Manga")
                .build()
            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<JsonArray>()
                    .let { response ->
                        if (response.size > 1) {
                            throw Exception("Too much mangas in response")
                        }
                        val entry = response.map {
                            jsonToTrack(it.jsonObject, mangas)
                        }
                        entry.firstOrNull()
                    }
            }
        }
    }

    suspend fun getCurrentUser(): Int {
        return with(json) {
            authClient.newCall(GET("$API_URL/users/whoami"))
                .awaitSuccess()
                .parseAs<JsonObject>()
                .let {
                    it["id"]!!.jsonPrimitive.int
                }
        }
    }

    suspend fun accessToken(code: String): OAuth {
        return withIOContext {
            with(json) {
                client.newCall(accessTokenRequest(code))
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    private fun accessTokenRequest(code: String) = POST(
        OAUTH_URL,
        body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URL)
            .build(),
    )

    companion object {
        private const val CLIENT_ID = "PB9dq8DzI405s7wdtwTdirYqHiyVMh--djnP7lBUqSA"
        private const val CLIENT_SECRET = "NajpZcOBKB9sJtgNcejf8OB9jBN1OYYoo-k4h2WWZus"

        private const val BASE_URL = "https://shikimori.one"
        private const val API_URL = "$BASE_URL/api"
        private const val OAUTH_URL = "$BASE_URL/oauth/token"
        private const val LOGIN_URL = "$BASE_URL/oauth/authorize"

        private const val REDIRECT_URL = "mihon://shikimori-auth"

        fun authUrl(): Uri = LOGIN_URL.toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URL)
            .appendQueryParameter("response_type", "code")
            .build()

        fun refreshTokenRequest(token: String) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", token)
                .build(),
        )
    }
}
