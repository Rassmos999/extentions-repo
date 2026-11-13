package eu.kanade.tachiyomi.animeextension.all.pornhub

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class PornHub :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "all"

    override val supportsLatest = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    private val htmlCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val freshThumbnails = ConcurrentHashMap<String, String>()
    private val sessionCookies = ConcurrentHashMap<String, String>(REQUIRED_COOKIES)

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::cacheAndRetryPages)
        .rateLimitHost(baseUrl.toHttpUrl(), 1, 1.seconds)
        .addNetworkInterceptor(::ensureDesktopCookies)
        .build()

    private fun ensureDesktopCookies(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host.endsWith("phncdn.com")) {
            val builder = request.newBuilder()
            if (request.header("Referer") == null) {
                builder.header("Referer", "$baseUrl/")
            }
            return chain.proceed(builder.build())
        }
        if (!host.endsWith("pornhub.com")) return chain.proceed(request)

        REQUIRED_COOKIES.forEach { (k, v) -> sessionCookies[k] = v }
        val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val response = chain.proceed(
            request.newBuilder()
                .header("Cookie", cookieHeader)
                .build(),
        )

        val rawSetCookies = response.headers("Set-Cookie")
        for (sc in rawSetCookies) {
            val pair = sc.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            val lower = name.lowercase()
            if (lower in BLOCKED_COOKIES || value.equals("deleted", ignoreCase = true) || value.isBlank()) {
                continue
            }
            if (name !in REQUIRED_COOKIES) {
                sessionCookies[name] = value
            }
        }

        // Strip challenge cookies so the host CookieJar cannot lock subsequent requests into the JS gate.
        val cleanSetCookies = rawSetCookies.filterNot { sc ->
            val lowerName = sc.substringBefore('=').trim().lowercase()
            lowerName in BLOCKED_COOKIES
        }
        val newHeaders = response.headers.newBuilder().removeAll("Set-Cookie").apply {
            cleanSetCookies.forEach { add("Set-Cookie", it) }
        }.build()
        return response.newBuilder().headers(newHeaders).build()
    }

    /**
     * Caches full HTML responses for video details and listing/search pages, retries transient
     * HTTP 403/429 and ~1.4 KB JS challenge cutoffs with clean session rotation, and refreshes
     * expired phncdn thumbnails.
     */
    private fun cacheAndRetryPages(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (host.contains("pix-") && host.endsWith("phncdn.com")) {
            val reqUrl = request.url.toString()
            val freshUrl = extractThumbnailKey(reqUrl)?.let { freshThumbnails[it] }
            val initialReq = if (freshUrl != null && freshUrl != reqUrl && isThumbnailExpired(reqUrl)) {
                request.newBuilder().url(freshUrl).header("Referer", "$baseUrl/").build()
            } else {
                request.newBuilder().header("Referer", "$baseUrl/").build()
            }
            var resp = chain.proceed(initialReq)
            if ((resp.code == 403 || resp.code == 410 || resp.code == 470 || resp.code == 472) &&
                freshUrl != null &&
                freshUrl != initialReq.url.toString()
            ) {
                resp.close()
                resp = chain.proceed(request.newBuilder().url(freshUrl).header("Referer", "$baseUrl/").build())
            }
            return resp
        }

        if (!host.endsWith("pornhub.com")) return chain.proceed(request)

        val urlStr = request.url.toString()
        val isVideoPage = "/view_video.php" in urlStr
        val isCacheable = request.method == "GET" && "/get_media" !in urlStr
        val cacheTtl = if (isVideoPage) HTML_CACHE_TTL_MS else BROWSE_CACHE_TTL_MS
        if (isCacheable) {
            htmlCache[urlStr]?.let { (ts, cachedHtml) ->
                if (System.currentTimeMillis() - ts < cacheTtl) {
                    return Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(cachedHtml.toResponseBody("text/html; charset=UTF-8".toMediaType()))
                        .build()
                }
                htmlCache.remove(urlStr)
            }
        }

        var response = chain.proceed(request)
        repeat(REFUSED_RETRIES) { attempt ->
            val shouldRetry = response.code in REFUSED_CODES || isChallengePage(response)
            if (!shouldRetry) return@repeat
            response.close()
            sessionCookies.clear()
            sessionCookies.putAll(REQUIRED_COOKIES)
            network.client.connectionPool.evictAll()
            Thread.sleep(REFUSED_BACKOFF_MS * (attempt + 1))
            response = chain.proceed(request)
        }
        if (response.code in REFUSED_CODES) {
            val code = response.code
            response.close()
            throw IOException(
                "PornHub refused this connection (HTTP $code). " +
                    "If a VPN or WARP is on, turn it off (or switch the 1.1.1.1 app to DNS-only mode) and retry.",
            )
        }
        if (isChallengePage(response)) {
            response.close()
            throw IOException(
                "PornHub temporarily rate-limited this connection. Wait a few seconds and tap Retry.",
            )
        }
        if (isCacheable && response.isSuccessful) {
            val bodyStr = response.body.string()
            if (bodyStr.length > 5000) {
                if (htmlCache.size > 60) htmlCache.clear()
                htmlCache[urlStr] = System.currentTimeMillis() to bodyStr
            }
            return response.newBuilder()
                .body(bodyStr.toResponseBody("text/html; charset=UTF-8".toMediaType()))
                .build()
        }
        return response
    }

    private fun isChallengePage(response: Response): Boolean {
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.isNotEmpty() && !contentType.contains("html", ignoreCase = true)) return false
        val preview = runCatching { response.peekBody(4096).string() }.getOrNull() ?: return false
        return preview.length < 4000 &&
            (preview.contains("leastFactor") || preview.contains("onload=\"go()\""))
    }

    private fun extractThumbnailKey(url: String): String? = THUMB_KEY_REGEX.find(url)?.value

    private fun isThumbnailExpired(url: String): Boolean {
        val exp = THUMB_EXP_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
        return exp <= (System.currentTimeMillis() / 1000L) + 60L
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Cookie", "platform=pc; age_verified=1; accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; accessPH=1; hasVisited=1; cookiesBannerSeen=1; cookieConsent=3")
        .add("Referer", "$baseUrl/")

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page, sort = "ht"), headers)

    override fun popularAnimeSelector(): String = "li.pcVideoListItem:not(.mockNsfwThumb), li.videoBox:not(.noVideo), ul#videoCategory li.videoblock, ul#videoSearchResult li.videoblock, li.videoWrapper:not(.noVideo), div.positionRelative.singleVideo, li[data-video-id]"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.linkVideoThumb, div.phimage a, a.imageLink, a.js-videoPreview, a[href*=/view_video]")
        val href = link?.attr("abs:href")
            ?: element.selectFirst("a[href*=/view_video]")?.attr("abs:href")
            ?: ""
        setUrlWithoutDomain(href)
        val rawTitle = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("span.title a, div.title a, a.thumbnailTitle")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("span.title a, div.title a, a.thumbnailTitle")?.text()?.ifBlank { null }
            ?: element.text().orEmpty()
        val duration = element.selectFirst("span.duration, var.duration, .duration, span.video-duration")?.text()?.trim().orEmpty()
        title = if (duration.isNotBlank() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("div.phimage img, img")?.let { img ->
            img.attr("data-image").ifBlank { null }
                ?: img.attr("data-highres").ifBlank { null }
                ?: img.attr("data-poster").ifBlank { null }
                ?: img.attr("poster").ifBlank { null }
                ?: img.attr("data-mediumthumb").ifBlank { null }
                ?: img.attr("data-src").ifBlank { null }
                ?: img.attr("data-thumb_url").ifBlank { null }
                ?: img.attr("data-smallthumb").ifBlank { null }
                ?: img.attr("src").ifBlank { null }
        }
        thumbnail_url?.let { thumb ->
            extractThumbnailKey(thumb)?.let { key -> freshThumbnails[key] = thumb }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next:not(.page_disabled):not(.disabled) a, a.page_next:not(.disabled), a[rel=next]:not(.disabled), link[rel=next], a:has(i.ph-icon-chevron-right):not(.disabled), div.pagination li.active + li a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .filter { el ->
                el.parents().none { parent ->
                    parent.id() in EXCLUDED_LIST_IDS ||
                        parent.hasClass("dropdownHottestVideos") ||
                        parent.hasClass("dropdownReccomendedVideos") ||
                        parent.hasClass("dropdownPlaylistVideos") ||
                        parent.hasClass("premiumStyle")
                }
            }
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page, sort = "cm"), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.replace(DURATION_PREFIX_REGEX, "").trim()
        if (
            cleanQuery.equals("view video.php", ignoreCase = true) ||
            cleanQuery.equals("view_video.php", ignoreCase = true)
        ) {
            return AnimesPage(emptyList(), false)
        }
        return super.getSearchAnime(page, cleanQuery, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "ht"
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val pornstar = filters.firstInstanceOrNull<PornstarFilter>()?.selected.orEmpty()
        val duration = filters.firstInstanceOrNull<DurationFilter>()?.selected.orEmpty()
        val hd = filters.firstInstanceOrNull<HDFilter>()?.selected.orEmpty()
        val cleanQuery = query.replace(DURATION_PREFIX_REGEX, "").trim().lowercase()

        // Pornstar takes precedence (path-based browse).
        if (pornstar.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("pornstar/$pornstar/videos")
                .apply {
                    if (sort in PORNSTAR_SORTS) addQueryParameter("o", sort)
                }
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        // Keyword search (`/video/search` 404s on `o=ht` and `o=cm`; omission = Relevance, `mr` = Most Recent).
        if (cleanQuery.isNotBlank()) {
            val searchSort = when (sort) {
                "ht" -> ""
                "cm" -> "mr"
                else -> sort
            }
            val filterCategory = when (category) {
                "path:teen" -> "37"
                "path:babe" -> "5"
                "path:hentai" -> "36"
                else -> category.takeIf { !it.startsWith("path:") }
            }
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("video/search")
                .addEncodedQueryParameter("search", URLEncoder.encode(cleanQuery, "UTF-8"))
                .apply {
                    if (searchSort.isNotBlank()) addQueryParameter("o", searchSort)
                    if (page > 1) addQueryParameter("page", page.toString())
                    if (!filterCategory.isNullOrBlank()) addQueryParameter("filter_category", filterCategory)
                    applyDuration(duration)
                    if (hd.isNotBlank()) addQueryParameter("hd", "1")
                }
                .build()
            return GET(url, headers)
        }

        // Category browse without query.
        if (category.isNotBlank()) {
            return GET(browseUrl(page, sort = sort, duration = duration, category = category, hd = hd), headers)
        }

        return GET(browseUrl(page, sort = sort, duration = duration, hd = hd), headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.title span.inlineFree, h1.title, span.inlineFree")
            ?.text()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()

        author = document.selectFirst(
            "div.video-detailed-info div.userInfo a.bolded, " +
                "div.userInfo .usernameBadgesWrapper a, " +
                "div.usernameWrap a.bolded, " +
                "div.userInfo a[href*=/model/], " +
                "div.userInfo a[href*=/pornstar/], " +
                "div.userInfo a[href*=/channels/]",
        )?.text()

        description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        genre = document.select(
            "div.categoriesWrapper a, div.tagsWrapper a, " +
                "div.pornstarsWrapper a, a.js-categoryLink, a.js-tagLink",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        thumbnail_url = document.selectFirst("meta[name=twitter:image], meta[property=twitter:image]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        thumbnail_url?.let { thumb ->
            extractThumbnailKey(thumb)?.let { key -> freshThumbnails[key] = thumb }
        }

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            url = anime.url
            episode_number = 1F
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        val videoList = mutableListOf<Video>()
        val userAgent = headers["User-Agent"]
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val videoHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Accept-Encoding", "identity")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .add("Cookie", REQUIRED_COOKIE_HEADER)
            .build()
        val playerHeaders = Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Accept-Encoding", "identity")
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .build()

        // Extract flashvars JSON: search script tags for "mediaDefinitions"
        // or regex "var flashvars... = {".
        val flashvarsJson = extractFlashvars(html)
        val mediaDefinitions: JsonArray? = try {
            flashvarsJson?.let { json.parseToJsonElement(it).jsonObject["mediaDefinitions"]?.jsonArray }
        } catch (_: Exception) {
            null
        }

        fun resolveUrl(raw: String?): String? {
            val url = raw?.replace("\\/", "/")?.trim() ?: return null
            if (url.isBlank()) return null
            return when {
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$baseUrl$url"
                else -> null
            }
        }

        fun processDefinitions(defs: JsonArray, depth: Int = 0) {
            if (depth > 2) return
            val remoteMediaUrls = mutableListOf<String>()
            for (item in defs) {
                val obj: JsonObject = try {
                    item.jsonObject
                } catch (_: Exception) {
                    continue
                }
                val format = obj["format"]?.jsonPrimitive?.contentOrNull
                val videoUrl = resolveUrl(obj["videoUrl"]?.jsonPrimitive?.contentOrNull)
                val qualityElement = obj["quality"]
                val qualityStr: String? = try {
                    qualityElement?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) {
                    null
                }
                val height = try {
                    obj["height"]?.jsonPrimitive?.intOrNull
                } catch (_: Exception) {
                    null
                }

                if (videoUrl != null && "/get_media" in videoUrl) {
                    remoteMediaUrls.add(videoUrl)
                    continue
                }

                if (format == "hls" && videoUrl != null) {
                    if (".m3u8" in videoUrl) {
                        // Master playlist: fetch and parse variants.
                        try {
                            client.newCall(GET(videoUrl, videoHeaders)).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("master fetch failed")
                                val playlist = resp.body.string()
                                val lines = playlist.lines()
                                var found = false
                                val basePrefix = videoUrl.substringBeforeLast("/")
                                for (i in lines.indices) {
                                    val line = lines[i].trim()
                                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                                        val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                        val next = lines.getOrNull(i + 1)?.trim() ?: continue
                                        if (next.isBlank() || next.startsWith("#")) continue
                                        val variantUrl = when {
                                            next.startsWith("http") -> next
                                            next.startsWith("/") -> "$baseUrl$next"
                                            else -> "$basePrefix/$next"
                                        }
                                        val label = if (h != null) "${h}p (HLS)" else "${qualityStr ?: "720"}p (HLS)"
                                        videoList.add(Video(variantUrl, label, variantUrl, playerHeaders))
                                        found = true
                                    }
                                }
                                if (!found) throw Exception("no variants")
                            }
                        } catch (_: Exception) {
                            val fallbackUrl = if ("/master.m3u8" in videoUrl && !qualityStr.isNullOrBlank()) {
                                videoUrl.replace("/master.m3u8", "/index-v1-a1.m3u8")
                            } else {
                                videoUrl
                            }
                            videoList.add(Video(fallbackUrl, "${qualityStr ?: "720"}p (HLS)", fallbackUrl, playerHeaders))
                        }
                        continue
                    }
                    // Non-m3u8 hls entry: add directly.
                    videoList.add(Video(videoUrl, "HLS ${qualityStr ?: "720"}p", videoUrl, playerHeaders))
                }

                if (format == "mp4" && videoUrl != null && "/get_media" !in videoUrl) {
                    val label = if (!qualityStr.isNullOrBlank()) {
                        "${qualityStr}p"
                    } else if (height != null) {
                        "${height}p"
                    } else {
                        "MP4"
                    }
                    videoList.add(Video(videoUrl, "MP4 $label", videoUrl, playerHeaders))
                }
            }

            if (videoList.isEmpty() && depth == 0) {
                for (remoteUrl in remoteMediaUrls) {
                    try {
                        client.newCall(GET(remoteUrl, videoHeaders)).execute().use { resp ->
                            if (!resp.isSuccessful) return@use
                            val body = resp.body.string().trim()
                            if (!body.startsWith("[")) return@use
                            val arr = try {
                                json.parseToJsonElement(body).jsonArray
                            } catch (_: Exception) {
                                null
                            }
                            if (arr != null) processDefinitions(arr, depth + 1)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        if (mediaDefinitions != null) {
            processDefinitions(mediaDefinitions)
        }

        // Fallback: fetch lightweight embed page if videoList is still empty
        if (videoList.isEmpty()) {
            val viewkey = Regex("(?<=viewkey=)[^&]+").find(pageUrl)?.value
            if (viewkey != null) {
                try {
                    val embedUrl = "$baseUrl/embed/$viewkey"
                    client.newCall(GET(embedUrl, headers)).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val embedHtml = resp.body.string()
                            val embedFlashvars = extractFlashvars(embedHtml)
                            val embedDefs = try {
                                embedFlashvars?.let { json.parseToJsonElement(it).jsonObject["mediaDefinitions"]?.jsonArray }
                            } catch (_: Exception) {
                                null
                            }
                            if (embedDefs != null) {
                                processDefinitions(embedDefs)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback: search html for master.m3u8 URLs.
        if (videoList.isEmpty()) {
            Regex("""https?:\\/\\/[^"'\\s]+?\\.m3u8[^"'\\s]*""")
                .findAll(html)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .forEach { url ->
                    val resolved = resolveUrl(url) ?: return@forEach
                    videoList.add(Video(resolved, "HLS 720p", resolved, playerHeaders))
                }
        }

        if (videoList.isEmpty()) {
            throw Exception("Host list empty: no playable streams found (page: $pageUrl)")
        }

        return videoList.sort().distinctBy { it.url to it.quality }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { !it.quality.contains(quality, ignoreCase = true) },
                { !it.quality.contains("HLS", ignoreCase = true) },
                { -(QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0) },
            ),
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply in SEARCH (empty query OK). Not on Popular tab."),
        SortFilter(),
        CategoryFilter(),
        PornstarFilter(),
        DurationFilter(),
        HDFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                Pair("Hot", "ht"),
                Pair("Most Relevant", "mr"),
                Pair("Most Viewed", "mv"),
                Pair("Top Rated", "tr"),
                Pair("Newest", "cm"),
                Pair("Longest", "lg"),
            ),
        )

    private class DurationFilter :
        UriPartFilter(
            "Duration",
            arrayOf(
                Pair("Any", ""),
                Pair("Under 10 min", "max:10"),
                Pair("10+ min", "min:10"),
                Pair("20+ min", "min:20"),
                Pair("30+ min", "min:30"),
            ),
        )

    private class HDFilter :
        UriPartFilter(
            "Quality",
            arrayOf(
                Pair("Any", ""),
                Pair("HD only", "hd"),
            ),
        )

    private class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Any", ""),
                Pair("18-25 (Teen)", "path:teen"),
                Pair("60FPS", "105"),
                Pair("Amateur", "3"),
                Pair("Anal", "35"),
                Pair("Arab", "98"),
                Pair("Asian", "1"),
                Pair("Babe", "path:babe"),
                Pair("Babysitter (18+)", "89"),
                Pair("BBW", "6"),
                Pair("Behind The Scenes", "141"),
                Pair("Big Ass", "4"),
                Pair("Big Dick", "7"),
                Pair("Big Tits", "8"),
                Pair("Bisexual Male", "76"),
                Pair("Blonde", "9"),
                Pair("Blowjob", "13"),
                Pair("Bondage", "10"),
                Pair("Brazilian", "102"),
                Pair("British", "96"),
                Pair("Brunette", "11"),
                Pair("Bukkake", "14"),
                Pair("Cartoon", "86"),
                Pair("Casting", "90"),
                Pair("Celebrity", "12"),
                Pair("Closed Captions", "732"),
                Pair("College (18+)", "path:college"),
                Pair("Compilation", "57"),
                Pair("Cosplay", "241"),
                Pair("Creampie", "15"),
                Pair("Cuckold", "242"),
                Pair("Cumshot", "16"),
                Pair("Czech", "100"),
                Pair("Double Penetration", "72"),
                Pair("Ebony", "17"),
                Pair("Euro", "55"),
                Pair("Exclusive", "115"),
                Pair("Feet", "93"),
                Pair("Female Orgasm", "502"),
                Pair("Fetish", "18"),
                Pair("Fingering", "592"),
                Pair("Fisting", "19"),
                Pair("French", "94"),
                Pair("Funny", "32"),
                Pair("Gaming", "881"),
                Pair("Gangbang", "80"),
                Pair("German", "95"),
                Pair("Handjob", "20"),
                Pair("Hardcore", "21"),
                Pair("HD Porn", "38"),
                Pair("Hentai", "path:hentai"),
                Pair("Indian", "101"),
                Pair("Interracial", "25"),
                Pair("Italian", "97"),
                Pair("Japanese", "111"),
                Pair("Korean", "103"),
                Pair("Latina", "26"),
                Pair("Lesbian", "27"),
                Pair("Massage", "78"),
                Pair("Masturbation", "22"),
                Pair("Mature", "28"),
                Pair("MILF", "29"),
                Pair("Muscular Men", "512"),
                Pair("Music", "121"),
                Pair("Old/Young (18+)", "181"),
                Pair("Orgy", "2"),
                Pair("Parody", "201"),
                Pair("Party", "53"),
                Pair("Pissing", "211"),
                Pair("Podcast", "891"),
                Pair("Pornstar", "path:pornstar"),
                Pair("POV", "41"),
                Pair("Public", "24"),
                Pair("Pussy Licking", "131"),
                Pair("Reality", "31"),
                Pair("Red Head", "42"),
                Pair("Role Play", "81"),
                Pair("Romantic", "522"),
                Pair("Rough Sex", "67"),
                Pair("Russian", "99"),
                Pair("School (18+)", "88"),
                Pair("Small Tits", "59"),
                Pair("Smoking", "91"),
                Pair("Solo Female", "492"),
                Pair("Solo Male", "92"),
                Pair("Squirt", "69"),
                Pair("Step Fantasy", "444"),
                Pair("Strap On", "542"),
                Pair("Striptease", "33"),
                Pair("Tattooed Women", "562"),
                Pair("Threesome", "65"),
                Pair("Toys", "23"),
                Pair("Transgender", "83"),
                Pair("Verified Amateurs", "138"),
                Pair("Verified Couples", "482"),
                Pair("Verified Models", "139"),
                Pair("Vintage", "43"),
                Pair("Webcam", "61"),
            ),
        )

    private class PornstarFilter :
        UriPartFilter(
            "Pornstar",
            arrayOf(
                Pair("Any", ""),
                Pair("Angela White", "angela-white"),
                Pair("Riley Reid", "riley-reid"),
                Pair("Mia Khalifa", "mia-khalifa"),
                Pair("Brandi Love", "brandi-love"),
                Pair("Abella Danger", "abella-danger"),
                Pair("Lana Rhoades", "lana-rhoades"),
                Pair("Adriana Chechik", "adriana-chechik"),
                Pair("Sasha Grey", "sasha-grey"),
                Pair("Lisa Ann", "lisa-ann"),
                Pair("Eva Elfie", "eva-elfie"),
                Pair("Nicole Aniston", "nicole-aniston"),
                Pair("Lena Paul", "lena-paul"),
                Pair("Violet Myers", "violet-myers"),
                Pair("Autumn Falls", "autumn-falls"),
                Pair("Alexis Texas", "alexis-texas"),
                Pair("Asa Akira", "asa-akira"),
                Pair("Madison Ivy", "madison-ivy"),
                Pair("Kendra Lust", "kendra-lust"),
                Pair("Ava Addams", "ava-addams"),
                Pair("Elsa Jean", "elsa-jean"),
                Pair("Dillion Harper", "dillion-harper"),
                Pair("Leah Gotti", "leah-gotti"),
                Pair("Mia Malkova", "mia-malkova"),
                Pair("Johnny Sins", "johnny-sins"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        val selected: String get() = vals[state].second
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // =============================== Helpers ==============================

    private fun browseUrl(
        page: Int,
        sort: String = "ht",
        duration: String = "",
        category: String = "",
        hd: String = "",
    ): String {
        val isPathCategory = category.startsWith("path:")
        val effectiveSort = when {
            category.isNotBlank() && (sort == "ht" || sort == "mr") -> ""
            category.isBlank() && sort == "mr" -> "ht"
            else -> sort
        }
        return baseUrl.toHttpUrl().newBuilder().apply {
            if (isPathCategory) {
                addPathSegments("categories/${category.removePrefix("path:")}")
            } else {
                addPathSegment("video")
                if (category.isNotBlank()) addQueryParameter("c", category)
                if (hd.isNotBlank()) addQueryParameter("hd", "1")
            }
            if (effectiveSort.isNotBlank()) addQueryParameter("o", effectiveSort)
            addQueryParameter("page", page.toString())
            applyDuration(duration)
        }.build().toString()
    }

    private fun okhttp3.HttpUrl.Builder.applyDuration(duration: String) {
        when {
            duration.startsWith("min:") ->
                addQueryParameter("min_duration", duration.removePrefix("min:"))

            duration.startsWith("max:") ->
                addQueryParameter("max_duration", duration.removePrefix("max:"))
        }
    }

    private fun extractFlashvars(html: String): String? {
        val marker = Regex("""var\s+flashvars[_\w]*\s*=\s*\{""")
        val match = marker.find(html) ?: run {
            // Fallback: search script tags for mediaDefinitions.
            if (!html.contains("mediaDefinitions")) return null
            val idx = html.indexOf("\"mediaDefinitions\"")
            if (idx < 0) return null
            // Walk backwards to find enclosing object start is unreliable;
            // search for nearest "flashvars" assignment before it.
            val sub = html.substring(0, idx)
            val m2 = Regex("""var\s+flashvars[_\w]*\s*=\s*""").findAll(sub).lastOrNull() ?: return null
            val brace = html.indexOf('{', m2.range.last)
            if (brace < 0) return null
            return extractBalanced(html, brace, '{', '}')
        }
        val braceStart = html.indexOf('{', match.range.last - 1)
        if (braceStart < 0) return null
        return extractBalanced(html, braceStart, '{', '}')
    }

    private fun extractBalanced(source: String, start: Int, open: Char, close: Char): String? {
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

                open -> depth++

                close -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    companion object {
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val DURATION_PREFIX_REGEX = Regex("""^\[[^\]]+\]\s*""")
        private val THUMB_KEY_REGEX = Regex("""/videos/\d+/\d+/\d+/""")
        private val THUMB_EXP_REGEX = Regex("""(?:validto|exp)=(\d+)""")

        private const val HTML_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val BROWSE_CACHE_TTL_MS = 90 * 1000L

        private val REFUSED_CODES = setOf(403, 429)
        private const val REFUSED_RETRIES = 3
        private const val REFUSED_BACKOFF_MS = 2500L

        private val BLOCKED_COOKIES = setOf(
            "htjschalref",
            "rp",
            "key",
            "htjf-mobile",
            "accessagedisclaimerph",
        )
        private val REQUIRED_COOKIES = mapOf(
            "platform" to "pc",
            "age_verified" to "1",
            "accessAgeDisclaimerPH" to "1",
            "accessAgeDisclaimerUK" to "1",
            "accessPH" to "1",
            "hasVisited" to "1",
            "cookiesBannerSeen" to "1",
            "cookieConsent" to "3",
        )
        private val REQUIRED_COOKIE_HEADER = REQUIRED_COOKIES.entries.joinToString("; ") { "${it.key}=${it.value}" }
        private val EXCLUDED_LIST_IDS = setOf(
            "hottestMenuSection",
            "recommMenuSection",
            "bottomVideos",
            "uploadedVideosSection",
        )
        private val PORNSTAR_SORTS = setOf("mv", "tr", "lg")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "240p")
    }
}
