package aniyomi.lib.streamwishextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.lib.synchrony.Deobfuscator
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class StreamWishExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val dmcaServersRegex = """dmca\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val mainServersRegex = """main\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val rulesServersRegex = """rules\s*=\s*\[(.*?)]""".toRegex(RegexOption.DOT_MATCHES_ALL)

    suspend fun videosFromUrl(url: String, prefix: String) = videosFromUrl(url) { "$prefix - $it" }

    suspend fun videosFromUrl(
        url: String,
        videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" },
    ): List<Video> = videosFromUrl(url, videoNameGen, m3u8UrlTransformer = null)

    /**
     * Extracts HLS streams from StreamWish / FileLions / EarnVids embeds.
     *
     * When [m3u8UrlTransformer] is provided (typically wrapping URLs via `M3u8ServerManager`),
     * `hls4` (fast relative `/stream/.../master.m3u8` with PNG-prefixed TS segments) is
     * preferred over timing-out `hls2` and 403 `hls3` CDNs.
     */
    suspend fun videosFromUrl(
        url: String,
        videoNameGen: (String) -> String = { quality -> "StreamWish - $quality" },
        m3u8UrlTransformer: ((m3u8Url: String, referer: String, origin: String) -> String)?,
    ): List<Video> {
        val embedUrl = getEmbedUrl(url).toHttpUrl()
        val id = getEmbedId(url)

        val isAbsoluteId = id.startsWith("https://") || id.startsWith("http://")
        val domainsToTry = if (isAbsoluteId) listOf("") else DOMAINS

        for (domain in domainsToTry) {
            val fullUrl = UrlUtils.fixUrl(id, "https://$domain") ?: continue
            try {
                val response = client.newCall(GET(fullUrl, headers)).await()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }

                var activePageUrl = response.request.url.toString()
                val body = response.bodyString()
                if (body.isBlank()) continue
                var doc = Jsoup.parse(body, activePageUrl)

                val scriptElement = doc.selectFirst("body > script[src*=/main.js]")
                if (scriptElement != null) {
                    val scriptUrl = scriptElement.absUrl("src")
                    val scriptContent = client.newCall(GET(scriptUrl, headers)).awaitSuccess().bodyString()

                    val deobfuscatedScript = runCatching { Deobfuscator.deobfuscateScript(scriptContent) }.getOrNull()
                        ?: continue

                    val dmcaServers = extractServerList(dmcaServersRegex, deobfuscatedScript)
                    val mainServers = extractServerList(mainServersRegex, deobfuscatedScript)
                    val rulesServers = extractServerList(rulesServersRegex, deobfuscatedScript)

                    val destination = if (embedUrl.host in rulesServers) {
                        mainServers.randomOrNull()
                    } else {
                        dmcaServers.randomOrNull()
                    } ?: continue

                    val redirectedUrl = embedUrl.newBuilder()
                        .host(destination)
                        .build()
                        .toString()

                    val redirResp = client.newCall(GET(getEmbedUrl(redirectedUrl), headers)).awaitSuccess()
                    activePageUrl = redirResp.request.url.toString()
                    doc = redirResp.useAsJsoup()
                }

                val scriptBody = doc.select("script").map { it.data() }
                    .firstOrNull { it.contains("eval(function(p,a,c") || it.contains("m3u8") || it.contains("hls4") }
                    ?.let { script ->
                        if (script.contains("eval(function(p,a,c")) {
                            JsUnpacker.unpackAndCombine(script)
                        } else {
                            script
                        }
                    } ?: continue

                val subtitleList = extractSubtitles(scriptBody)
                val fixedSubs = if (subtitleList.isNotEmpty()) playlistUtils.fixSubtitles(subtitleList) else emptyList()
                val embedOrigin = activePageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
                    ?: "https://${embedUrl.host}"
                val embedReferer = "$embedOrigin/"

                val candidates = extractMasterCandidates(scriptBody, activePageUrl, preferHls4 = m3u8UrlTransformer != null)
                for (masterUrl in candidates) {
                    val extracted = if (m3u8UrlTransformer != null) {
                        extractProxiedHlsVariants(
                            masterUrl = masterUrl,
                            referer = embedReferer,
                            origin = embedOrigin,
                            videoNameGen = videoNameGen,
                            subtitleList = fixedSubs,
                            m3u8UrlTransformer = m3u8UrlTransformer,
                        )
                    } else {
                        val ref = masterUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: embedReferer
                        runCatching {
                            playlistUtils.extractFromHls(
                                playlistUrl = masterUrl,
                                referer = ref,
                                videoNameGen = videoNameGen,
                                subtitleList = fixedSubs,
                            )
                        }.getOrDefault(emptyList())
                    }
                    if (extracted.isNotEmpty()) return extracted
                }
            } catch (_: Exception) {
                if (isAbsoluteId) return emptyList()
            }
        }

        return emptyList()
    }

    private fun extractMasterCandidates(scriptBody: String, pageUrl: String, preferHls4: Boolean): List<String> {
        val baseHttp = pageUrl.toHttpUrlOrNull()
        fun resolveCandidate(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val unescaped = raw.replace("\\u0026", "&").replace("\\/", "/").trim()
            return when {
                unescaped.startsWith("http://") || unescaped.startsWith("https://") -> unescaped
                unescaped.startsWith("//") -> "https:$unescaped"
                else -> baseHttp?.resolve(unescaped)?.toString()
            }
        }

        val hls4 = resolveCandidate(HLS4_REGEX.find(scriptBody)?.groupValues?.getOrNull(1))
        val hls2 = resolveCandidate(HLS2_REGEX.find(scriptBody)?.groupValues?.getOrNull(1))
        val hls3 = resolveCandidate(HLS3_REGEX.find(scriptBody)?.groupValues?.getOrNull(1))
        val generic = M3U8_REGEX.findAll(scriptBody).mapNotNull { resolveCandidate(it.value) }.toList()

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
        val reqHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", referer)
            .set("Origin", origin)
            .set("Accept-Encoding", "identity")
            .build()

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

    private fun extractServerList(regex: Regex, script: String): List<String> = regex.find(script)?.groupValues?.get(1)
        ?.split(",")
        ?.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

    private fun getEmbedUrl(url: String): String = if (url.contains("/f/")) {
        val videoId = url.substringAfter("/f/")
        "https://streamwish.com/$videoId"
    } else {
        url
    }

    private fun getEmbedId(url: String): String {
        val regex = Regex(""".*/[efd]/([a-zA-Z0-9]+)""")
        val match = regex.find(url)
        return match?.groupValues?.get(1) ?: url
    }

    private fun extractSubtitles(script: String): List<Track> = try {
        if ("tracks" !in script) {
            emptyList()
        } else {
            val subtitleStr = script
                .substringAfter("tracks")
                .substringAfter("[")
                .substringBefore("]")
            val fixedSubtitleStr = FIX_TRACKS_REGEX.replace(subtitleStr) { match ->
                "\"${match.value}\""
            }
            json.decodeFromString<List<TrackDto>>("[$fixedSubtitleStr]")
                .filter { it.kind.equals("captions", true) }
                .map { Track(it.file, it.label ?: "") }
        }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(val file: String, val kind: String, val label: String? = null)

    companion object {
        private val HLS4_REGEX by lazy { Regex("""["']hls4["']\s*:\s*["']([^"']+)["']""") }
        private val HLS2_REGEX by lazy { Regex("""["']hls2["']\s*:\s*["']([^"']+)["']""") }
        private val HLS3_REGEX by lazy { Regex("""["']hls3["']\s*:\s*["']([^"']+)["']""") }
        private val M3U8_REGEX by lazy { Regex("""https?://[^"'\\]+m3u8[^"'\\]*""") }
        private val RESOLUTION_HEIGHT_REGEX by lazy { Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE) }
        private val FIX_TRACKS_REGEX by lazy { Regex("""(?<!")(file|kind|label)(?!")""") }
        private val DOMAINS = listOf(
            "streamwish.com",
            "niramirus.com",
            "medixiru.com",
        )
    }
}
