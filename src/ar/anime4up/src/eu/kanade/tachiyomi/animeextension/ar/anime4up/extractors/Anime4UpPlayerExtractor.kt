package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Handles the site's own "Anime4up-S1/S2" players (vnxplayer), which embed a
 * tokenized HLS master playlist in an inline `let streamUrl = "..."` script.
 */
class Anime4UpPlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, serverName: String): List<Video> {
        val origin = url.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val pageHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .build()
        val html = client.newCall(GET(url, pageHeaders)).execute().use { it.body.string() }

        val streamUrl = STREAM_URL_REGEX.find(html)?.groupValues?.get(1)
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val subtitles = TRACK_REGEX.findAll(html.substringAfter("const tracks", "")).map {
            Track(it.groupValues[1].replace("\\/", "/"), it.groupValues[2])
        }.toList()

        val streamHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", origin)
            .build()

        return playlistUtils.extractFromHls(
            streamUrl,
            referer = url,
            masterHeaders = streamHeaders,
            videoHeaders = streamHeaders,
            videoNameGen = { "$serverName - $it" },
            subtitleList = subtitles,
        )
    }

    companion object {
        private val STREAM_URL_REGEX = Regex("""let\s+streamUrl\s*=\s*"([^"]+)"""")
        private val TRACK_REGEX = Regex(""""file"\s*:\s*"([^"]+)"[^}]*?"label"\s*:\s*"([^"]+)"""")
    }
}
