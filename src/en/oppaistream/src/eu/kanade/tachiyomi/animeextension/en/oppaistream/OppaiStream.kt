package eu.kanade.tachiyomi.animeextension.en.oppaistream

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.M3u8ServerManager
import eu.kanade.tachiyomi.animeextension.en.oppaistream.dto.AnilistResponseDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_EXCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState.Companion.STATE_INCLUDE
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class OppaiStream :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Oppai Stream"

    override val lang = "en"

    override val baseUrl = "https://oppai.stream"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val preferences by getPreferencesLazy()

    private val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val streamProxyClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(streamProxyClient) }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = buildSearchPost(page = page, order = "views")

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = buildSearchPost(page = page, order = "uploaded")

    override fun latestUpdatesParse(response: Response) = searchAnimeParse(response)

    // =============================== Search ===============================
    override fun getFilterList() = FILTERS

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var order = "views"
        val genresInclude = mutableListOf<String>()
        val genresExclude = mutableListOf<String>()
        val studios = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> order = filter.selectedValue()

                is GenreListFilter -> {
                    filter.state.forEach { genreState ->
                        when (genreState.state) {
                            STATE_INCLUDE -> genresInclude.add(genreState.value)
                            STATE_EXCLUDE -> genresExclude.add(genreState.value)
                        }
                    }
                }

                is StudioListFilter -> {
                    filter.state.filter { it.state }.forEach { studios.add(it.value) }
                }

                else -> {}
            }
        }

        return buildSearchPost(
            page = page,
            query = query.trim(),
            order = order,
            genres = genresInclude.joinToString(","),
            blacklist = genresExclude.joinToString(","),
            studio = studios.joinToString(","),
        )
    }

    private fun buildSearchPost(
        page: Int,
        query: String = "",
        order: String = "views",
        genres: String = "",
        blacklist: String = "",
        studio: String = "",
    ): Request {
        val body = FormBody.Builder()
            .add("text", query)
            .add("order", order)
            .add("page", page.toString())
            .add("limit", SEARCH_LIMIT.toString())
            .add("genres", genres)
            .add("blacklist", blacklist)
            .add("studio", studio)
            .add("classes", "smaller-episode-gap")
            .build()

        val searchHeaders = headersBuilder()
            .set("Referer", "$baseUrl/search")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        return POST("$baseUrl/$SEARCH_API_PATH", searchHeaders, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        // Live POST /api/search.php returns `<a class="episode ...">` cards directly;
        // keep legacy selector as fallback.
        val elements = document.select("a.episode[href*=/watch/], div.episode-shown > div > a")
        val anime = elements.mapNotNull(::searchAnimeFromElement).distinctBy { it.url }

        val totalResults = document.selectFirst(".results-num")?.text()?.trim()?.toIntOrNull()
        val hasNextPage = when {
            anime.isEmpty() -> false
            totalResults != null -> elements.size >= SEARCH_LIMIT && totalResults > SEARCH_LIMIT
            else -> elements.size >= SEARCH_LIMIT
        }
        return AnimesPage(anime, hasNextPage)
    }

    private fun searchAnimeFromElement(element: Element): SAnime? {
        val rawHref = element.attr("href").ifBlank { element.attr("exur") }.trim()
        if (rawHref.isBlank()) return null
        val canonicalHref = rawHref.substringBefore("/from/").fixLink()

        val rawTitle = element.selectFirst("p.episode-title, .title-ep")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img.cover, img.cover-img-in, img")?.attr("alt")
                ?.substringBefore(" Episode ")
                ?.trim()
            ?: return null
        val cleanTitle = rawTitle.replace(TITLE_CLEANUP_REGEX, "").trim()
        val duration = element.selectFirst(".ep-time p, .ep-time")?.text()?.trim()

        return SAnime.create().apply {
            setUrlWithoutDomain(canonicalHref)
            title = if (!duration.isNullOrBlank()) "[$duration] $cleanTitle" else cleanTitle
            thumbnail_url = element.selectFirst("img.cover, img.cover-img-in, img")?.let { img ->
                img.absUrl("src").ifBlank { img.absUrl("data-src") }
            }?.takeIf { it.startsWith("http") }
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        parseDetails(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseDetails(Jsoup.parse(html, url))
    }

    private fun parseDetails(document: Document): SAnime = SAnime.create().apply {
        val rawTitle = document.selectFirst("h1#episode-title, div.episode-info > h1, h1")?.text()?.trim().orEmpty()
        val cleanName = rawTitle
            .replace(EP_SUFFIX_REGEX, "")
            .substringBefore(" Ep ")
            .trim()
            .ifBlank { rawTitle }
        title = cleanName

        description = document.selectFirst("div.description")?.text()?.substringBeforeLast(" Watch ")?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()

        genre = document.select("div.tags a, a[href*='genres=']")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        val studios = document.select("div.episode-info a.red, a[href*='studio=']")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
        artist = studios.joinToString().ifBlank { null }

        val useAnilistCover = preferences.getBoolean(PREF_ANILIST_COVER_KEY, PREF_ANILIST_COVER_DEFAULT)
        val thumbnailPair = if (useAnilistCover && cleanName.isNotBlank()) {
            val sanitizedTitle = cleanName.replace(Regex("[^a-zA-Z0-9\\s!.:\"]"), " ")
            runCatching { fetchThumbnailUrlByTitle(sanitizedTitle) }.getOrNull()
        } else {
            null
        }

        val matchedStudio = thumbnailPair?.second?.find { it in studios }
        thumbnail_url = matchedStudio?.let { thumbnailPair.first }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
            ?: document.selectFirst("video#episode")?.attr("poster")?.takeIf { it.startsWith("http") }
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        parseEpisodes(Jsoup.parse(html, fullUrl), fullUrl)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseEpisodes(Jsoup.parse(html, url), url)
    }

    private fun parseEpisodes(doc: Document, pageUrl: String): List<SEpisode> {
        val byUrl = LinkedHashMap<String, SEpisode>()

        // New layout: `a.episode.other-episodes` cards on watch page
        doc.select("a.episode.other-episodes[href*=/watch/], div.more-same-eps .in-main-gr > a").forEach { el ->
            val href = el.attr("href").ifBlank { el.attr("exur") }.trim()
            if (href.isBlank()) return@forEach
            val canonical = href.substringBefore("/from/").fixLink()
            val numStr = el.attr("epid").trim()
                .ifBlank { el.selectFirst("p.episode-number, font.ep")?.text()?.trim().orEmpty() }
                .ifBlank { "1" }
            val epNum = numStr.toFloatOrNull() ?: 1F
            val ep = SEpisode.create().apply {
                setUrlWithoutDomain(canonical)
                name = "Episode $numStr"
                episode_number = epNum
                scanlator = el.selectFirst(".ep-time p, h6 > a")?.text()?.trim()
            }
            byUrl.putIfAbsent(ep.url, ep)
        }

        // Always ensure current watch episode is included
        val currentEpStr = VAR_EP_REGEX.find(doc.html())?.groupValues?.get(1)?.trim()
            ?: doc.selectFirst("h1#episode-title font")?.text()?.trim()
            ?: "1"
        val currentCanonical = pageUrl.substringBefore("/from/")
        val currentEpisode = SEpisode.create().apply {
            setUrlWithoutDomain(currentCanonical)
            name = "Episode $currentEpStr"
            episode_number = currentEpStr.toFloatOrNull() ?: 1F
        }
        if (byUrl.values.none { it.episode_number == currentEpisode.episode_number }) {
            byUrl.putIfAbsent(currentEpisode.url, currentEpisode)
        }

        return byUrl.values.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        parseVideos(Jsoup.parse(html, fullUrl), html).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseVideos(Jsoup.parse(html, url), html).sort()
    }

    private fun parseVideos(doc: Document, html: String): List<Video> {
        val subtitles = doc.select("track[kind=captions], track[kind=subtitles]").mapNotNull { track ->
            val src = track.absUrl("src").ifBlank { track.attr("src") }.trim()
            if (src.isBlank()) null else Track(src, track.attr("label").ifBlank { "English" })
        }

        val mediaHeaders = headersBuilder()
            .set("Accept", "*/*")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val videos = mutableListOf<Video>()

        // 1) Current oppai.stream watch.js player variables (`storageBase`, `folder`, `layout`, `resList`, `ep`)
        val folder = VAR_FOLDER_REGEX.find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        if (!folder.isNullOrBlank()) {
            val cdnBase = VAR_STORAGE_BASE_REGEX.find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.trimEnd('/')
                ?.ifBlank { null }
                ?: DEFAULT_CDN_BASE
            val layout = VAR_LAYOUT_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epNum = VAR_EP_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val paddedEp = epNum.toString().padStart(2, '0')
            val encodedFolder = folder.split('/').joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }
            val episodeBasePath = "$cdnBase/$encodedFolder"

            val resRaw = VAR_RES_LIST_REGEX.find(html)?.groupValues?.get(1).orEmpty()
            val resList = RES_ITEM_REGEX.findAll(resRaw).map { it.groupValues[1].lowercase() }.toList()
                .ifEmpty { listOf("4k", "1080", "720") }

            if (layout == 2) {
                for (r in resList) {
                    val resFolder = if (r == "4k") "4K" else "${r}p"
                    val qualityLabel = if (r == "4k") "2160p" else "${r}p"
                    val hlsUrl = "$episodeBasePath/$resFolder/dash$paddedEp/master.m3u8"
                    val dashUrl = "$episodeBasePath/$resFolder/dash$paddedEp/manifest.mpd"
                    if (r != "4k" && isReachableUrl(hlsUrl, mediaHeaders)) {
                        videos += Video(hlsUrl, "$qualityLabel - HLS", hlsUrl, mediaHeaders, subtitles)
                    }
                    if (isReachableUrl(dashUrl, mediaHeaders)) {
                        videos += Video(dashUrl, "$qualityLabel - DASH", dashUrl, mediaHeaders, subtitles)
                    }
                }
            } else {
                // Layout 1: direct ep.mp4 / ep.webm + dash/manifest.mpd
                val mp4Url = "$episodeBasePath/ep.mp4"
                val webmUrl = "$episodeBasePath/ep.webm"
                val dashUrl = "$episodeBasePath/dash/manifest.mpd"
                val preferMp4 = html.contains("\"4k\":\"mp4\"", ignoreCase = true) ||
                    html.contains("\"4k\": \"mp4\"", ignoreCase = true)
                val primaryDirect = if (preferMp4) mp4Url else webmUrl
                val secondaryDirect = if (preferMp4) webmUrl else mp4Url

                when {
                    isReachableUrl(primaryDirect, mediaHeaders) ->
                        videos += Video(primaryDirect, "2160p - Direct", primaryDirect, mediaHeaders, subtitles)

                    isReachableUrl(secondaryDirect, mediaHeaders) ->
                        videos += Video(secondaryDirect, "2160p - Direct", secondaryDirect, mediaHeaders, subtitles)
                }
                if (isReachableUrl(dashUrl, mediaHeaders)) {
                    videos += Video(dashUrl, "2160p - DASH", dashUrl, mediaHeaders, subtitles)
                }
            }
        }

        // 2) Legacy fallback: `var availableres = {...}`
        if (videos.isEmpty()) {
            val script = doc.selectFirst("script:containsData(var availableres)")?.data()
            if (!script.isNullOrBlank()) {
                val block = script.substringAfter("var availableres = {").substringBefore('}')
                block.split(',').forEach { entry ->
                    val cleaned = entry.replace("\"", "").replace("\\u0026", "&").replace("\\/", "/").replace("\\", "").trim()
                    val parts = cleaned.split(':', limit = 2)
                    if (parts.size == 2 && parts[1].startsWith("http")) {
                        val fixedRes = if (parts[0].trim().equals("4k", true)) "2160p" else "${parts[0].trim()}p"
                        videos += Video(parts[1].trim(), fixedRes, parts[1].trim(), mediaHeaders, subtitles)
                    }
                }
            }
        }

        val distinct = videos.distinctBy { it.videoUrl ?: it.url }
        if (distinct.isEmpty()) {
            throw IOException("No playable video streams found on Oppai Stream.")
        }

        val useSeekCache = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
        return if (useSeekCache) {
            m3u8Server.wrapVideosForCache(
                videos = distinct,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = "$baseUrl/",
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            distinct
        }
    }

    private fun isReachableUrl(url: String, reqHeaders: okhttp3.Headers): Boolean = runCatching {
        val headReq = Request.Builder().url(url).headers(reqHeaders).head().build()
        probeClient.newCall(headReq).execute().use { resp ->
            resp.isSuccessful || resp.code == 206
        }
    }.getOrDefault(false)

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending { it.quality.contains("HLS", ignoreCase = true) || it.quality.contains("Direct", ignoreCase = true) },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched HLS segments and MP4 blocks locally (20 min TTL) for instant skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ANILIST_COVER_KEY
            title = PREF_ANILIST_COVER_TITLE
            summary = PREF_ANILIST_COVER_SUMMARY
            setDefaultValue(PREF_ANILIST_COVER_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_COVER_QUALITY_KEY
            title = PREF_COVER_QUALITY_TITLE
            entries = PREF_COVER_QUALITY_ENTRIES
            entryValues = PREF_COVER_QUALITY_VALUES
            setDefaultValue(PREF_COVER_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun toAbsoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$baseUrl/${url.trimStart('/')}"
    }

    private fun fetchPageCached(url: String): String {
        val now = System.currentTimeMillis()
        pageCache[url]?.let { (ts, body) ->
            if (now - ts < PAGE_CACHE_TTL_MS) return body
        }
        val html = client.newCall(GET(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body.string()
        }
        cachePage(url, html)
        return html
    }

    private fun cachePage(url: String, html: String) {
        if (pageCache.size > 30) pageCache.clear()
        pageCache[url] = System.currentTimeMillis() to html
    }

    private fun fetchThumbnailUrlByTitle(title: String): Pair<String, List<String>>? {
        val query = """
            query {
                Media(search: "$title", type: ANIME, isAdult: true) {
                    coverImage {
                        extraLarge
                        large
                    }
                    studios {
                        nodes {
                            name
                        }
                    }
                }
            }
        """.trimIndent()

        val requestBody = FormBody.Builder()
            .add("query", query)
            .build()

        val request = POST("https://graphql.anilist.co", body = requestBody)
        val response = probeClient.newCall(request).execute()
        return parseThumbnailUrlFromObject(response.parseAs<AnilistResponseDto>())
    }

    private fun parseThumbnailUrlFromObject(obj: AnilistResponseDto): Pair<String, List<String>>? {
        val media = obj.data.media ?: return null
        val coverURL = when (preferences.getString(PREF_COVER_QUALITY_KEY, PREF_COVER_QUALITY_DEFAULT)) {
            "extraLarge" -> media.coverImage.extraLarge
            else -> media.coverImage.large
        }
        return Pair(coverURL, media.studios.names)
    }

    private fun String.fixLink(): String = this.replace(Regex("(?<=\\?e=)(.*?)(?=&f=)")) {
        URLEncoder.encode(it.groupValues[1], "UTF-8")
    }

    companion object {
        private const val SEARCH_API_PATH = "api/search.php"
        private const val DEFAULT_CDN_BASE = "https://s1.myspacecat.pictures"
        private const val SEARCH_LIMIT = 36
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val TITLE_CLEANUP_REGEX = Regex("""\s+\d+$""")
        private val EP_SUFFIX_REGEX = Regex("""\s+Episode\s+\d+.*$""", RegexOption.IGNORE_CASE)
        private val VAR_FOLDER_REGEX = Regex("""var\s+folder\s*=\s*"([^"]+)"""")
        private val VAR_STORAGE_BASE_REGEX = Regex("""var\s+storageBase\s*=\s*"([^"]+)"""")
        private val VAR_LAYOUT_REGEX = Regex("""var\s+layout\s*=\s*(\d+)""")
        private val VAR_EP_REGEX = Regex("""var\s+ep\s*=\s*"?(\d+)"?""")
        private val VAR_RES_LIST_REGEX = Regex("""var\s+resList\s*=\s*\[([^\]]*)]""")
        private val RES_ITEM_REGEX = Regex(""""([^"]+)"""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "2160p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"

        private const val PREF_ANILIST_COVER_KEY = "preferred_anilist_cover"
        private const val PREF_ANILIST_COVER_TITLE = "Use Anilist as cover source - Beta"
        private const val PREF_ANILIST_COVER_DEFAULT = true
        private const val PREF_ANILIST_COVER_SUMMARY = "Enables fetching covers from Anilist in the details page."

        private const val PREF_COVER_QUALITY_KEY = "preferred_cover_quality"
        private const val PREF_COVER_QUALITY_TITLE = "Preferred Anilist cover quality - Beta"
        private const val PREF_COVER_QUALITY_DEFAULT = "extraLarge"
        private val PREF_COVER_QUALITY_ENTRIES = arrayOf("Extra Large", "Large")
        private val PREF_COVER_QUALITY_VALUES = arrayOf("extraLarge", "large")
    }
}
