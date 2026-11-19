package eu.kanade.tachiyomi.animeextension.all.pornhub

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers

/**
 * Extracts playable MP4 and HLS video streams from PornHub video pages.
 *
 * Strategy:
 * - Primary: calls `/video/get_media?s=...` with the exact session cookies emitted by the
 *   `view_video.php` response, yielding direct progressive `.mp4` streams (1080p/720p/480p/240p)
 *   with `start_time=0`, full duration, and native HTTP Range seeking in mpv/ExoPlayer.
 * - Secondary / Fallback: extracts HLS streams directly from `flashvars.mediaDefinitions`,
 *   preferring `ev-h.phncdn.com` (standard `.ts` VOD) over `hv-h.phncdn.com` (fMP4) and passing
 *   `Accept-Encoding: identity` + `Referer` + `Origin` for clean seeking.
 * - Embed fallback: fetches `/embed/{viewkey}` if the main page lacks `flashvars`.
 */
internal class PornHubExtractor(
    private val phClient: PornHubClient,
    private val baseUrlProvider: () -> String,
    private val userAgent: String,
    private val json: Json,
) {
    private val playlistUtils by lazy {
        PlaylistUtils(phClient.client, buildPlayerHeaders())
    }

    fun extractVideos(
        page: PornHubClient.PageResult,
        preferredFormat: String,
        preferredQuality: String,
    ): List<Video> {
        val playerHeaders = buildPlayerHeaders()
        var flashvars = extractFlashvarsObject(page.html)
        var effectiveCookies = page.responseCookies

        // Fallback to lightweight embed page if main HTML did not include flashvars.
        if (flashvars == null) {
            val viewkey = VIEWKEY_REGEX.find(page.finalUrl)?.groupValues?.getOrNull(1)
            if (!viewkey.isNullOrBlank()) {
                val embedUrl = "${baseUrlProvider().trimEnd('/')}/embed/$viewkey"
                runCatching {
                    val embedPage = phClient.fetchPage(
                        GET(embedUrl, buildPageHeaders()),
                        cacheTtlMs = 0L,
                    )
                    flashvars = extractFlashvarsObject(embedPage.html)
                    if (embedPage.responseCookies.isNotEmpty()) {
                        effectiveCookies = embedPage.responseCookies
                    }
                }
            }
        }

        val mediaDefinitions = runCatching {
            flashvars?.get("mediaDefinitions")?.jsonArray
        }.getOrNull()

        val mp4Videos = mutableListOf<Video>()
        val hlsVideos = mutableListOf<Video>()

        if (mediaDefinitions != null) {
            val wantMp4 = preferredFormat != FORMAT_HLS_ONLY
            val wantHls = preferredFormat != FORMAT_MP4_FIRST

            if (wantMp4) {
                mp4Videos += extractMp4Streams(mediaDefinitions, effectiveCookies, playerHeaders)
            }
            if (wantHls || mp4Videos.isEmpty()) {
                hlsVideos += extractHlsStreams(mediaDefinitions, playerHeaders)
            }
            if (preferredFormat == FORMAT_HLS_ONLY && hlsVideos.isEmpty()) {
                mp4Videos += extractMp4Streams(mediaDefinitions, effectiveCookies, playerHeaders)
            }
        }

        // Last-resort regex scan for embedded .m3u8 URLs if flashvars yielded nothing.
        if (mp4Videos.isEmpty() && hlsVideos.isEmpty()) {
            M3U8_FALLBACK_REGEX.findAll(page.html)
                .mapNotNull { resolveUrl(it.value) }
                .distinct()
                .forEach { url ->
                    val res = extractResolution(url)
                    val label = if (res != null) "${res}p (HLS)" else "720p (HLS)"
                    hlsVideos.add(Video(url, label, url, playerHeaders))
                }
        }

        val combined = when (preferredFormat) {
            FORMAT_HLS_ONLY -> if (hlsVideos.isNotEmpty()) hlsVideos else mp4Videos
            FORMAT_MP4_FIRST -> if (mp4Videos.isNotEmpty()) mp4Videos else hlsVideos
            else -> mp4Videos + hlsVideos
        }

        val unique = combined.distinctBy { it.videoUrl ?: it.url }
        return sortVideos(unique, preferredQuality)
    }

    fun extractFlashvarsObject(html: String): JsonObject? {
        val raw = extractFlashvarsJson(html) ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun extractMp4Streams(
        defs: JsonArray,
        pageCookies: Map<String, String>,
        playerHeaders: Headers,
    ): List<Video> {
        val results = mutableListOf<Video>()
        val remoteMediaUrls = mutableListOf<String>()

        for (item in defs) {
            val obj = runCatching { item.jsonObject }.getOrNull() ?: continue
            val format = obj["format"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rawUrl = resolveUrl(obj["videoUrl"]?.jsonPrimitive?.contentOrNull) ?: continue

            if ("/get_media" in rawUrl) {
                if (format.equals("mp4", ignoreCase = true) || format.isBlank()) {
                    remoteMediaUrls.add(rawUrl)
                }
                continue
            }

            if (format.equals("mp4", ignoreCase = true) ||
                rawUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)
            ) {
                val res = parseQualityInt(obj) ?: extractResolution(rawUrl)
                val label = if (res != null) "${res}p (MP4)" else "MP4"
                results.add(Video(rawUrl, label, rawUrl, playerHeaders))
            }
        }

        if (remoteMediaUrls.isNotEmpty()) {
            val mediaHeaders = Headers.Builder()
                .add("User-Agent", userAgent)
                .add("Accept", "application/json, text/javascript, */*; q=0.01")
                .add("Referer", "${baseUrlProvider().trimEnd('/')}/")
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Cookie", phClient.buildCookieHeader(pageCookies))
                .build()

            for (remoteUrl in remoteMediaUrls.distinct()) {
                runCatching {
                    phClient.rawCookieClient.newCall(GET(remoteUrl, mediaHeaders)).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body.string().trim()
                        if (!body.startsWith("[")) return@use
                        val arr = json.parseToJsonElement(body).jsonArray
                        for (entry in arr) {
                            val obj = runCatching { entry.jsonObject }.getOrNull() ?: continue
                            val streamUrl = resolveUrl(obj["videoUrl"]?.jsonPrimitive?.contentOrNull)
                                ?.replace("&amp;", "&")
                                ?: continue
                            if (!streamUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)) {
                                continue
                            }
                            val res = parseQualityInt(obj) ?: extractResolution(streamUrl)
                            val label = if (res != null) "${res}p (MP4)" else "MP4"
                            results.add(Video(streamUrl, label, streamUrl, playerHeaders))
                        }
                    }
                }
            }
        }

        return results
    }

    private fun extractHlsStreams(
        defs: JsonArray,
        playerHeaders: Headers,
    ): List<Video> {
        val results = mutableListOf<Video>()
        val referer = "${baseUrlProvider().trimEnd('/')}/"

        for (item in defs) {
            val obj = runCatching { item.jsonObject }.getOrNull() ?: continue
            val format = obj["format"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val rawUrl = resolveUrl(obj["videoUrl"]?.jsonPrimitive?.contentOrNull) ?: continue
            if ("/get_media" in rawUrl) continue
            if (!format.equals("hls", ignoreCase = true) && ".m3u8" !in rawUrl) continue

            val singleQuality = parseQualityInt(obj)
            if (singleQuality != null) {
                // Single-quality master.m3u8: pass directly without extra HTTP round-trips.
                results.add(Video(rawUrl, "${singleQuality}p (HLS)", rawUrl, playerHeaders))
            } else {
                // Multi-variant master playlist: unpack variants via PlaylistUtils.
                val expanded = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = rawUrl,
                        referer = referer,
                        masterHeaders = playerHeaders,
                        videoHeaders = playerHeaders,
                        videoNameGen = { q -> "$q (HLS)" },
                    )
                }.getOrDefault(emptyList())

                if (expanded.isNotEmpty()) {
                    results.addAll(expanded)
                } else {
                    val res = extractResolution(rawUrl)
                    val label = if (res != null) "${res}p (HLS)" else "HLS"
                    results.add(Video(rawUrl, label, rawUrl, playerHeaders))
                }
            }
        }

        return results
    }

    private fun sortVideos(videos: List<Video>, preferredQuality: String): List<Video> {
        val prefNum = preferredQuality.filter(Char::isDigit).toIntOrNull() ?: 720
        return videos.sortedWith(
            compareBy<Video>(
                { extractResolution(it.quality) != prefNum },
                { !it.quality.contains("MP4", ignoreCase = true) },
                { (it.videoUrl ?: it.url).contains("hv-h.phncdn.com", ignoreCase = true) },
                { -(extractResolution(it.quality) ?: extractResolution(it.videoUrl ?: it.url) ?: 0) },
            ),
        )
    }

    private fun parseQualityInt(obj: JsonObject): Int? {
        val qEl = obj["quality"]
        val qPrimitive = runCatching { qEl?.jsonPrimitive?.contentOrNull }.getOrNull()
        qPrimitive?.filter(Char::isDigit)?.toIntOrNull()?.let { return it }
        return runCatching { obj["height"]?.jsonPrimitive?.intOrNull }.getOrNull()
    }

    private fun extractResolution(text: String): Int? = RESOLUTION_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun resolveUrl(raw: String?): String? {
        val url = raw?.replace("\\/", "/")?.trim() ?: return null
        if (url.isBlank()) return null
        return when {
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "${baseUrlProvider().trimEnd('/')}$url"
            else -> null
        }
    }

    private fun buildPlayerHeaders(): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept-Encoding", "identity")
        .add("Referer", "${baseUrlProvider().trimEnd('/')}/")
        .add("Origin", baseUrlProvider().trimEnd('/'))
        .build()

    private fun buildPageHeaders(): Headers = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", "${baseUrlProvider().trimEnd('/')}/")
        .build()

    private fun extractFlashvarsJson(html: String): String? {
        val match = FLASHVARS_MARKER_REGEX.find(html)
        if (match != null) {
            val brace = html.indexOf('{', match.range.last - 1)
            if (brace >= 0) return extractBalancedObject(html, brace)
        }
        val mdIdx = html.indexOf("\"mediaDefinitions\"")
        if (mdIdx < 0) return null
        val before = html.substring(0, mdIdx)
        val fallback = FLASHVARS_FALLBACK_REGEX.findAll(before).lastOrNull() ?: return null
        val brace = html.indexOf('{', fallback.range.last)
        if (brace < 0) return null
        return extractBalancedObject(html, brace)
    }

    private fun extractBalancedObject(source: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until source.length) {
            val c = source[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true

                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return null
    }

    companion object {
        const val FORMAT_MP4_FIRST = "mp4_first"
        const val FORMAT_ALL = "all"
        const val FORMAT_HLS_ONLY = "hls_only"

        private val VIEWKEY_REGEX = Regex("""viewkey=([^&#]+)""")
        private val RESOLUTION_REGEX = Regex("""(?:^|[^0-9])(2160|1440|1080|720|480|360|240)[pP]?""")
        private val M3U8_FALLBACK_REGEX = Regex("""https?:\\/\\/[^"'\s]+?\.m3u8[^"'\s]*""")
        private val FLASHVARS_MARKER_REGEX = Regex("""var\s+flashvars[_\w]*\s*=\s*\{""")
        private val FLASHVARS_FALLBACK_REGEX = Regex("""var\s+flashvars[_\w]*\s*=\s*""")
    }
}
