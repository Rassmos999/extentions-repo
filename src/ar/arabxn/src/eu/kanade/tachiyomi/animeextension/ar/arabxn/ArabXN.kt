package eu.kanade.tachiyomi.animeextension.ar.arabxn

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArabXN :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "arabxn").also { it.syncSeedFromPreferences() }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = tasteEngine.rerankPage(super.getPopularAnime(page))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = tasteEngine.rerankPage(super.getLatestUpdates(page))

    override val name = "ArabXN"

    override val baseUrl = "https://www.arabxn.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(CfWebBridgeInterceptor { baseUrl })
        .build()

    private val preferences by getPreferencesLazy()
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/video/" else "$baseUrl/video/page/$page"
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = "article.video-card, article.rowvideo, article"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href*=/watch-]")
            ?: element.selectFirst("a[href]")
        val href = link?.attr("abs:href").orEmpty()
        setUrlWithoutDomain(href)
        val rawTitle = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("h2, h3, strong.title, .row_titleVideo .title, .title")?.text()?.trim()
            ?: link?.text()?.trim().orEmpty()
        val duration = element.selectFirst(
            "div.timevideo1, div.timevideo, div.duration, span.duration, span.time, span.clock, time",
        )?.text()?.trim()?.ifBlank { null }
        title = if (!duration.isNullOrEmpty() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("img.video-card-poster, img")?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String = "nav.catalog-pagination span.is-current ~ a[href], " +
        "nav.pagination span.is-current ~ a[href], .catalog-pagination .is-current ~ a[href], " +
        "div.pagination span.current-page ~ a[href], div.pagination .current ~ a[href], " +
        "a[rel=next], link[rel=next], div.pagination a.next"

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    private fun parseListing(document: Document): AnimesPage {
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.contains("/watch-") && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = tasteEngine.onSearchResult(page, query, getSearchAnimeBase(page, query, filters))

    private suspend fun getSearchAnimeBase(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            val paths = collectFilterPaths(filters, page)
            if (paths.size > 1) {
                val requests = paths.take(MAX_PARALLEL_FILTERS).map { GET("$baseUrl$it", headers) }
                return fetchAndMergePages(requests)
            }
        }
        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    private fun collectFilterPaths(filters: AnimeFilterList, page: Int): List<String> {
        val selectedCats = filters.firstInstanceOrNull<CategoryGroup>()
            ?.state?.filter { it.state }?.map { categoryPath(it.value, page) }.orEmpty()
        val channels = splitMulti(filters.firstInstanceOrNull<ChannelFilter>()?.state)
            .map { channelPath(it, page) }
        val pornstars = splitMulti(filters.firstInstanceOrNull<PornstarFilter>()?.state)
            .map { pornstarPath(it, page) }
        val tags = splitMulti(filters.firstInstanceOrNull<TagFilter>()?.state)
            .map { tagPath(it, page) }
        return (channels + pornstars + tags + selectedCats).distinct()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        if (cleanQuery.isNotBlank()) {
            return GET("$baseUrl${searchPath(cleanQuery, page)}", headers)
        }
        val firstPath = collectFilterPaths(filters, page).firstOrNull()
        return if (firstPath != null) {
            GET("$baseUrl$firstPath", headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = getAnimeDetailsBase(anime).also { tasteEngine.recordDetails(anime, it) }

    private suspend fun getAnimeDetailsBase(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("div.video-details h1, h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()

        author = document.select("a.taga[href*=/pornstar/]")
            .mapNotNull { it.attr("title").ifBlank { it.text() }.trim().takeIf(String::isNotEmpty) }
            .map { it.replace(Regex("""\s*ممتل.*$"""), "").trim() }
            .distinct()
            .joinToString()
            .ifBlank { null }

        genre = document.select("a.taga[href*=/tag/]")
            .mapNotNull { it.attr("title").ifBlank { it.text() }.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        val views = document.selectFirst(".watch-views-value-full")?.text()?.trim()
        val age = document.select("div.video-details p")
            .map { it.text().trim() }
            .firstOrNull { it.startsWith("منذ") || it.contains("ساعة") || it.contains("يوم") }
        description = buildString {
            views?.let { append("المشاهدات: $it\n") }
            age?.let { append(it).append('\n') }
            document.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { append(it) }
        }.trim().ifBlank { null }

        thumbnail_url = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video#my-video")?.attr("poster")
                ?: document.selectFirst("video[poster]")?.attr("abs:poster"),
        )

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        tasteEngine.recordLibraryAnime(anime)
        return getEpisodeListBase(anime)
    }

    private suspend fun getEpisodeListBase(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        val doc = Jsoup.parse(html, fullUrl)
        val dateUpload = parseReleaseDate(
            doc.selectFirst("meta[property=og:video:release_date]")?.attr("content"),
        )
        listOf(
            SEpisode.create().apply {
                name = "Video"
                setUrlWithoutDomain(fullUrl)
                episode_number = 1f
                date_upload = dateUpload
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        val document = Jsoup.parse(html, url)
        val dateUpload = parseReleaseDate(
            document.selectFirst("meta[property=og:video:release_date]")?.attr("content"),
        )
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                setUrlWithoutDomain(url)
                episode_number = 1f
                date_upload = dateUpload
            },
        )
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = getVideoListBase(episode).also { if (it.isNotEmpty()) tasteEngine.recordPlayByUrl(episode.url) }

    private suspend fun getVideoListBase(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        parseVideosFromHtml(html, fullUrl).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseVideosFromHtml(html, url)
    }

    private fun parseVideosFromHtml(html: String, pageUrl: String): List<Video> {
        val document = Jsoup.parse(html, pageUrl)
        // Always use ASCII-safe Referer; raw Arabic in OkHttp Headers throws IllegalArgumentException.
        val safeRef = pageUrl.toHttpUrlOrNull()?.toString() ?: "$baseUrl/"
        val videoHeaders = headersBuilder()
            .set("Referer", safeRef)
            .build()

        val fromSources = document.select("video#my-video source[src], video source[src]")
            .mapNotNull { source ->
                val url = source.attr("abs:src").ifBlank { source.attr("src") }
                if (url.isBlank() || !url.contains(".mp4", ignoreCase = true)) return@mapNotNull null
                val label = source.attr("title").ifBlank { qualityFromUrl(url) }
                Video(url, label, url, headers = videoHeaders)
            }
            .distinctBy { it.url }

        val rawVideos = if (fromSources.isNotEmpty()) {
            fromSources
        } else {
            MP4_URL_REGEX.findAll(html)
                .map { it.value }
                .distinct()
                .map { url -> Video(url, qualityFromUrl(url), url, headers = videoHeaders) }
                .toList()
        }
        return if (preferences.getBoolean("pref_seek_cache", true)) {
            m3u8Server.wrapVideosForCache(
                videos = rawVideos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = safeRef,
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            rawVideos
        }
    }

    private val m3u8Server by lazy { M3u8ServerManager(client) }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> { !it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // =============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("يمكنك اختيار أكثر من تصنيف أو كتابة أكثر من ممثل/تاج بفواصل (,)"),
        CategoryGroup(),
        ChannelFilter(),
        TagFilter(),
        PornstarFilter(),
    )

    private class CategoryCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup :
        AnimeFilter.Group<CategoryCheckBox>(
            "التصنيفات / Categories (اختيار متعدد)",
            CATEGORIES.map { (label, path) -> CategoryCheckBox(label, path) },
        )

    private class ChannelFilter : AnimeFilter.Text("قناة / Channel (e.g. brazzers, bangbros)")

    private class TagFilter : AnimeFilter.Text("تاج / Tag (e.g. milf, سكس-مترجم)")

    private class PornstarFilter : AnimeFilter.Text("ممثل / Pornstar (e.g. angela-white, mia-khalifa)")

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        tasteEngine.attachPreferences(screen)

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_seek_cache"
            title = "تفعيل كاش التقديم والترجيع (Local Seek Cache)"
            summary = "تخزين مقاطع الفيديو مؤقتاً لمدة 20 دقيقة لتسريع التقديم والرجوع لنفس النقطة."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // =============================== Helpers ==============================
    private suspend fun fetchAndMergePages(requests: List<Request>): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            parseListing(resp.asJsoup())
                        }
                    }.getOrNull()
                }
            }
            val pages = futures.mapNotNull { runCatching { it.get(12, TimeUnit.SECONDS) }.getOrNull() }
            mergePages(pages)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun mergePages(pages: List<AnimesPage>): AnimesPage {
        if (pages.isEmpty()) return AnimesPage(emptyList(), false)
        val counts = LinkedHashMap<String, Int>()
        val byUrl = LinkedHashMap<String, SAnime>()
        for (page in pages) {
            val seen = HashSet<String>()
            for (item in page.animes) {
                if (seen.add(item.url)) {
                    counts[item.url] = (counts[item.url] ?: 0) + 1
                    byUrl.putIfAbsent(item.url, item)
                }
            }
        }
        val merged = mutableListOf<SAnime>()
        val added = HashSet<String>()
        counts.entries.filter { it.value > 1 }.sortedByDescending { it.value }.forEach { (url, _) ->
            byUrl[url]?.let {
                merged += it
                added += url
            }
        }
        val maxLen = pages.maxOfOrNull { it.animes.size } ?: 0
        for (i in 0 until maxLen) {
            for (page in pages) {
                val item = page.animes.getOrNull(i) ?: continue
                if (added.add(item.url)) merged += item
            }
        }
        return AnimesPage(merged, pages.any { it.hasNextPage })
    }

    private fun splitMulti(raw: String?): List<String> = raw.orEmpty()
        .split(',', '،')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun searchPath(query: String, page: Int): String {
        val slug = encodePathSegment(query)
        return if (page <= 1) "/search/$slug" else "/search/$slug/$page"
    }

    private fun tagPath(raw: String, page: Int): String {
        val slug = normalizeSlug(raw.removePrefix("tag/").removePrefix("/"))
        return if (page <= 1) "/tag/$slug" else "/tag/$slug/page/$page"
    }

    private fun channelPath(raw: String, page: Int): String {
        val slug = normalizeSlug(raw.removePrefix("channel/").removePrefix("/"))
        return if (page <= 1) "/channel/$slug/" else "/channel/$slug/page/$page"
    }

    private fun pornstarPath(raw: String, page: Int): String {
        val slug = normalizeSlug(raw.removePrefix("pornstar/").removePrefix("/"))
        return if (page <= 1) "/pornstar/$slug" else "/pornstar/$slug/page/$page"
    }

    private fun categoryPath(path: String, page: Int): String {
        val clean = path.trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
        if (clean.isEmpty()) return if (page <= 1) "/" else "/page/$page"
        if (page <= 1) return if (clean.startsWith("/tag/")) clean else "$clean/"
        return "$clean/page/$page"
    }

    private fun encodePathSegment(raw: String): String {
        val cleaned = raw.trim().replace(Regex("""\s+"""), "-")
        return URLEncoder.encode(cleaned, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%2D", "-")
            .replace("%5F", "_")
    }

    private fun normalizeSlug(raw: String): String = raw.trim()
        .replace(Regex("""\s+"""), "-")
        .trim('/')
        .let { encodePathSegment(it) }

    private fun qualityFromUrl(url: String): String {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            "/240p/" in lower -> "240p"
            "/360p/" in lower -> "360p"
            "/480p/" in lower -> "480p"
            "/720p/" in lower -> "720p"
            "/1080p/" in lower -> "1080p"
            else -> "720p HD"
        }
    }

    private fun parseReleaseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { DATE_FORMAT.parse(raw.trim())?.time ?: 0L }.getOrDefault(0L)
    }

    private fun toAbsoluteUrl(url: String): String {
        val raw = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl/${url.trimStart('/')}"
        }
        return raw.toHttpUrlOrNull()?.toString() ?: raw
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

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    private fun Element.getImageUrl(): String? {
        val src = when {
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ").substringBefore(",")
            hasAttr("poster") -> attr("poster")
            else -> attr("src")
        }.ifBlank { attr("src") }.trim()

        if (src.isBlank() || src.startsWith("data:")) return null
        val resolved = when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("http://") || src.startsWith("https://") -> src
            else -> absUrl("data-original").ifBlank { absUrl("data-src").ifBlank { absUrl("src") } }
        }
        return resolved.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed
        }.takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    companion object {
        private const val MAX_PARALLEL_FILTERS = 4
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val MP4_URL_REGEX = Regex(
            """https?://(?:video\.arabxn\.com|arabxn\.xyz|www\.arabxn\.com)[^"'<\s]+\.mp4""",
            RegexOption.IGNORE_CASE,
        )
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "الجودة المفضلة / Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("720", "360", "240", "HD")
        private const val PREF_QUALITY_DEFAULT = "720"

        // Verified canonical live paths on arabxn.com
        private val CATEGORIES = listOf(
            "كل الفيديوهات / All videos" to "/video",
            "سكس مترجم" to "/category/سكس-مترجم",
            "سكس عربي" to "/category/سكس-عربي",
            "سكس مصري" to "/category/سكس-مصري",
            "سكس امهات" to "/category/سكس-امهات",
            "سكس اخوات" to "/category/سكس-اخوات",
            "سكس محارم" to "/category/سكس-محارم",
            "السكس" to "/category/السكس",
            "xnxx" to "/category/xnxx",
            "سكس جماعي مترجم" to "/tag/سكس-جماعي-مترجم",
            "نودز مصري" to "/tag/نودز-مصري",
            "سكس طيز" to "/tag/سكس-طيز",
        )
    }
}
