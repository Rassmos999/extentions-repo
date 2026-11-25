package aniyomi.lib.vidhideextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class VidHideExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    suspend fun videosFromUrl(
        url: String,
        videoNameGen: (String) -> String = { quality -> "VidHide - $quality" },
    ): List<Video> = videosFromUrl(url, videoNameGen, m3u8UrlTransformer = null)

    /**
     * Extracts HLS streams from VidHide / EarnVids / StreamHG embeds.
     *
     * When [m3u8UrlTransformer] is supplied, prefers `hls4` (`/stream/.../master.m3u8`)
     * routed through `M3u8ServerManager` so PNG-prefixed TS segments are stripped and
     * cached while avoiding timing-out `hls2` / 403 `hls3` CDNs.
     */
    suspend fun videosFromUrl(
        url: String,
        videoNameGen: (String) -> String = { quality -> "VidHide - $quality" },
        m3u8UrlTransformer: ((m3u8Url: String, referer: String, origin: String) -> String)?,
    ): List<Video> {
        val (script, finalUrl) = fetchAndExtractScript(url) ?: return emptyList()
        val subtitleList = extractSubtitles(script, finalUrl)

        if (m3u8UrlTransformer != null) {
            val embedOrigin = finalUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                ?: url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                ?: ""
            val embedReferer = if (embedOrigin.isNotBlank()) "$embedOrigin/" else url
            val candidates = extractOrderedCandidates(script, finalUrl, preferHls4 = true)
            for (masterUrl in candidates) {
                val extracted = extractProxiedHlsVariants(
                    masterUrl = masterUrl,
                    referer = embedReferer,
                    origin = embedOrigin,
                    videoNameGen = videoNameGen,
                    subtitleList = subtitleList,
                    m3u8UrlTransformer = m3u8UrlTransformer,
                )
                if (extracted.isNotEmpty()) return extracted
            }
            return emptyList()
        }

        val playlists = extractOrderedCandidates(script, finalUrl, preferHls4 = false)
        return playlists.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                referer = finalUrl,
                videoNameGen = videoNameGen,
                subtitleList = subtitleList,
            )
        }
    }

    private suspend fun fetchAndExtractScript(url: String): Pair<String, String>? {
        val resp = client.newCall(GET(url, headers)).awaitSuccess()
        val finalUrl = resp.request.url.toString()
        val unpacked = resp.useAsJsoup()
            .select("script")
            .find { it.html().contains("eval(function(p,a,c,k,e,d)") }
            ?.html()
            ?.let(::autoUnpacker)
            ?: return null
        return unpacked to finalUrl
    }

    private fun extractOrderedCandidates(script: String, baseUrl: String, preferHls4: Boolean): List<String> {
        val baseHttp = baseUrl.toHttpUrlOrNull()
        fun resolve(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val unescaped = raw.replace("\\u0026", "&").replace("\\/", "/").trim()
            return UrlUtils.fixUrl(unescaped, baseUrl) ?: baseHttp?.resolve(unescaped)?.toString()
        }

        val hls4 = resolve(HLS4_REGEX.find(script)?.groupValues?.getOrNull(1))
        val hls2 = resolve(HLS2_REGEX.find(script)?.groupValues?.getOrNull(1))
        val hls3 = resolve(HLS3_REGEX.find(script)?.groupValues?.getOrNull(1))
        val generic = sourceRegex.findAll(script).mapNotNull { resolve(it.groupValues[1]) }.toList()

        val ordered = if (preferHls4) {
            listOfNotNull(hls4, hls2, hls3) + generic
        } else {
            listOfNotNull(hls2, hls3) + generic + listOfNotNull(hls4)
        }
        return ordered.distinct()
    }

    private fun extractProxiedHlsVariants(
        masterUrl: String,
        referer: String,
        origin: String,
        videoNameGen: (String) -> String,
        subtitleList: List<Track>,
        m3u8UrlTransformer: (m3u8Url: String, referer: String, origin: String) -> String,
    ): List<Video> {
        val reqHeaders = headers.newBuilder().apply {
            set("Accept", "*/*")
            if (referer.isNotBlank()) set("Referer", referer)
            if (origin.isNotBlank()) set("Origin", origin)
            set("Accept-Encoding", "identity")
        }.build()

        val playlistBody = runCatching {
            client.newCall(GET(masterUrl, reqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string().removePrefix("\uFEFF")
            }
        }.getOrNull() ?: return emptyList()

        if (!playlistBody.trimStart().startsWith("#EXTM3U")) return emptyList()

        val baseHttp = masterUrl.toHttpUrlOrNull()
        val lines = playlistBody.lines().map(String::trim)
        val variants = mutableListOf<Pair<Int, Video>>()

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val label = if (height > 0) "${height}p" else "HD"
            val localUrl = m3u8UrlTransformer(resolved, referer, origin)
            variants += height to Video(
                url = localUrl,
                quality = videoNameGen(label),
                videoUrl = localUrl,
                subtitleTracks = subtitleList,
            )
        }

        if (variants.isNotEmpty()) {
            return variants.sortedByDescending { it.first }.map { it.second }.distinctBy { it.videoUrl }
        }

        val localUrl = m3u8UrlTransformer(masterUrl, referer, origin)
        return listOf(
            Video(
                url = localUrl,
                quality = videoNameGen("HD"),
                videoUrl = localUrl,
                subtitleTracks = subtitleList,
            ),
        )
    }

    private fun extractSubtitles(script: String, baseUrl: String): List<Track> = try {
        if ("tracks" !in script) {
            emptyList()
        } else {
            val subtitleStr = script
                .substringAfter("tracks")
                .substringAfter("[")
                .substringBefore("]")
            json.decodeFromString<List<TrackDto>>("[$subtitleStr]")
                .filter { it.kind.equals("captions", true) }
                .mapNotNull {
                    UrlUtils.fixUrl(it.file, baseUrl)?.let { url ->
                        Track(url, it.label ?: "")
                    }
                }
        }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(
        val file: String,
        val kind: String,
        val label: String? = null,
    )

    companion object {
        private val HLS4_REGEX = Regex("""["']hls4["']\s*:\s*["']([^"']+)["']""")
        private val HLS2_REGEX = Regex("""["']hls2["']\s*:\s*["']([^"']+)["']""")
        private val HLS3_REGEX = Regex("""["']hls3["']\s*:\s*["']([^"']+)["']""")
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val sourceRegex = Regex(""""((?:https?:/)?/[^"]*m3u8[^"]*)"""")
    }
}
