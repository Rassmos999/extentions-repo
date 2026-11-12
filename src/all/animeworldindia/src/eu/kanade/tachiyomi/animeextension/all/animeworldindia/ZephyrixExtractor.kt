package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient

class ZephyrixExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, serverLabel: String = "Zephyrix"): List<Video> {
        val dataId = url.substringAfter("/video/", "")
            .substringBefore("?")
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        return runCatching {
            val host = url.substringBefore("/video/")
            val apiUrl = "$host/player/index.php?data=$dataId&do=getVideo"
            val body = FormBody.Builder()
                .add("hash", dataId)
                .add("r", "$siteUrl/")
                .build()
            val reqHeaders = headers.newBuilder()
                .set("Referer", url)
                .set("Origin", host)
                .set("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = client.newCall(POST(apiUrl, reqHeaders, body)).execute()
            val payload = response.parseAs<ZephyrixResponse>()
            val playlistUrl = payload.securedLink?.takeIf { it.isNotBlank() }
                ?: payload.videoSource?.takeIf { it.isNotBlank() }
                ?: return emptyList()

            playlistUtils.extractFromHls(
                playlistUrl = playlistUrl,
                referer = "$host/",
                videoNameGen = { quality -> "$serverLabel - $quality" },
            )
        }.getOrElse { emptyList() }
    }

    @Serializable
    private data class ZephyrixResponse(
        val hls: Boolean = false,
        val videoSource: String? = null,
        val securedLink: String? = null,
    )
}
