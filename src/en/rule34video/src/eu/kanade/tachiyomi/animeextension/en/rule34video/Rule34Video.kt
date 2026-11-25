package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.M3u8ServerManager
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelMapNotNullBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Rule34Video :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = true

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client: OkHttpClient = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val m3u8Server by lazy { M3u8ServerManager(client) }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false)) {
        val uploaderId = preferences.getString(PREF_UPLOADER_ID_KEY, "") ?: ""
        if (uploaderId.isNotBlank()) {
            val url = "$baseUrl/members/$uploaderId/videos/?mode=async&function=get_block&block_id=list_videos_uploaded_videos&sort_by=&from_videos=$page"
            Log.d("Rule34Video", "Loading uploader videos: $uploaderId page=$page")
            GET(url, headers)
        } else {
            GET("$baseUrl/most-popular/$page/", headers)
        }
    } else {
        GET("$baseUrl/most-popular/$page/", headers)
    }

    override fun popularAnimeSelector() = "div.item.thumb"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.th")!!.attr("href"))
        val baseTitle = element.selectFirst("a.th div.thumb_title")!!.text()
        val duration = element.selectFirst("span.duration, span.time, div.duration, time, div.time, div.duration_text, div.time_length, span.badge")?.text()?.trim()
        title = if (!duration.isNullOrEmpty()) "[$duration] $baseTitle" else baseTitle
        thumbnail_url = element.getImageUrl()
    }

    private fun Element.getImageUrl(): String? {
        val img = if (tagName() == "img") this else selectFirst("img")
        return img?.let {
            it.attr("abs:data-original").ifEmpty {
                it.attr("abs:data-src").ifEmpty {
                    it.attr("abs:poster").ifEmpty {
                        it.attr("abs:src")
                    }
                }
            }
        }?.takeIf { it.isNotBlank() }?.let { url ->
            when {
                url.startsWith("//") -> "https:$url"
                else -> url
            }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.item.pager.next a, ul.pagination li.next a, div.pager a.next, a[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/latest-updates/$page/", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    private inline fun <reified R> AnimeFilterList.getUriPart() = (find { it is R } as? UriPartFilter)?.toUriPart() ?: ""

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val slug = url.pathSegments.getOrNull(2)
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$slug", filters)
        }

        val selectedCats = filters.filterIsInstance<CategoryGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { it.flagId }.orEmpty()
        if (selectedCats.size > 1) {
            val requests = selectedCats.take(MAX_PARALLEL_FILTERS).map { catId ->
                buildSearchRequest(page, query, filters, overrideCategoryId = catId)
            }
            return fetchAndMergePages(requests)
        }

        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val selectedCat = filters.filterIsInstance<CategoryGroup>().firstOrNull()
            ?.state?.firstOrNull { it.state }?.flagId.orEmpty()
        return buildSearchRequest(page, query, filters, overrideCategoryId = selectedCat)
    }

    private fun buildSearchRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
        overrideCategoryId: String,
    ): Request {
        val orderFilter = filters.getUriPart<OrderFilter>()
        val sortType = when (orderFilter) {
            "latest-updates" -> "post_date"
            "most-popular" -> "video_viewed"
            "top-rated" -> "rating"
            else -> ""
        }

        val tagFilter = (filters.find { it is TagFilter } as? TagFilter)?.state.orEmpty().trim()
        if (tagFilter.isNotBlank()) {
            val ajaxUrl = "$baseUrl/search_ajax.php".toHttpUrl().newBuilder()
                .addQueryParameter("tag", tagFilter)
                .build()
            runCatching {
                client.newCall(GET(ajaxUrl, headers)).execute().use { resp ->
                    tagDocument = resp.asJsoup()
                }
            }
        }

        val tagSearch = filters.getUriPart<TagSearch>()

        if (query.isNotEmpty() && query.startsWith(PREFIX_SEARCH)) {
            val newQuery = query.removePrefix(PREFIX_SEARCH).dropLastWhile { it.isDigit() }
            return GET("$baseUrl/search/$newQuery", headers)
        }

        val basePath = if (query.isNotBlank()) {
            "$baseUrl/search/${query.trim().replace(Regex("\\s+"), "-")}/"
        } else {
            "$baseUrl/search/"
        }

        val url = basePath.toHttpUrl().newBuilder().apply {
            if (overrideCategoryId.isNotBlank()) addQueryParameter("flag1", overrideCategoryId)
            if (sortType.isNotBlank()) addQueryParameter("sort_by", sortType)
            addQueryParameter("from_videos", page.toString())
            if (tagSearch.isNotBlank()) addQueryParameter("tag_ids", "all,$tagSearch")
        }.build()

        return GET(url, headers)
    }

    private suspend fun fetchAndMergePages(requests: List<Request>): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            searchAnimeParse(resp)
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

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.title_video")?.text().toString()

        val infoItems = document.select("div.vp-meta .item_info > span, div.info.row div.item_info > span")
        val detailRows = document.select("div.row")

        val artistElement = detailRows.select("div.col:has(div.label:contains(Artist)) a.item span.name").firstOrNull()
        author = artistElement?.text().orEmpty()

        description = buildString {
            (document.selectFirst("div.vp-desc")?.let { it.selectFirst("em") ?: it }?.html() ?: detailRows.select("div.row:has(div.label > em) > div.label > em").html())
                .replace("<br>", "\n")
                .let { text ->
                    append(text)
                }
            append("\n\n")

            infoItems.getOrNull(0)?.text()?.let {
                append("Uploaded: $it\n")
            }

            val artist = detailRows.select("div.col:has(div.label:contains(Artist)) a.item span.name")
                .eachText()
                .joinToString()
            if (artist.isNotEmpty()) {
                append("Artists: $artist\n")
            }

            val categories = detailRows.select("div.col:has(div.label:contains(Categories)) a.item span")
                .eachText()
                .joinToString()
            if (categories.isNotEmpty()) {
                append("Categories: $categories\n")
            }

            val uploader = detailRows.select("div.col:has(div.label:contains(Uploaded by)) a.item").text()
            if (uploader.isNotEmpty()) {
                append("Uploader: $uploader\n")
            }

            infoItems.getOrNull(1)?.text()?.let {
                val views = it.substringBefore(" ").replace(",", "")
                append("Views: $views\n")
            }
            infoItems.getOrNull(2)?.text()?.let { append("Duration: $it\n") }
            document.select(DOWNLOAD_LINK_SELECTOR)
                .mapNotNull { QUALITY_REGEX.find(it.text())?.value }
                .joinToString()
                .also { append("Quality: $it") }
        }

        genre = document.select("div:has(> div.label:contains(Tags)) a.tag_item:not(:contains(Suggest))")
            .eachText()
            .joinToString()

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "Video"
        },
    )

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()
    override fun episodeListSelector() = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        extractVideosFromHtml(html, fullUrl).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        cachePage(pageUrl, html)
        return extractVideosFromHtml(html, pageUrl).sort()
    }

    private fun extractVideosFromHtml(html: String, pageUrl: String): List<Video> {
        val parsedPageUrl = pageUrl.toHttpUrl()
        val videoHeaders = headersBuilder()
            .apply {
                val cookies = client.cookieJar.loadForRequest(parsedPageUrl)
                    .filterNot { it.name in listOf("__ddgid_", "__ddgmark_") }
                    .joinToString("; ") { "${it.name}=${it.value}" }
                val xsrfToken = cookies.split("XSRF-TOKEN=").getOrNull(1)?.substringBefore(";")?.replace("%3D", "=")
                xsrfToken?.let { add("X-XSRF-TOKEN", it) }
                if (cookies.isNotBlank()) add("Cookie", cookies)
                add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                add("Referer", pageUrl)
                add("Accept-Language", "en-US,en;q=0.5")
            }.build()

        val document = Jsoup.parse(html, pageUrl)

        val rawVideos = document.select(DOWNLOAD_LINK_SELECTOR)
            .parallelMapNotNullBlocking { element ->
                val originalUrl = element.attr("href")
                val url = noRedirectClient.newCall(GET(originalUrl, videoHeaders)).execute()
                    .use { it.headers["location"] }
                    ?: return@parallelMapNotNullBlocking null
                val quality = QUALITY_REGEX.find(element.text())?.value ?: element.text().substringAfter(" ")
                Video(url, quality, url, videoHeaders)
            }

        val useSeekCache = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
        return if (useSeekCache) {
            m3u8Server.wrapVideosForCache(
                videos = rawVideos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = pageUrl,
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            rawVideos
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: return this
        return sortedWith(
            compareByDescending<Video> { it.quality == quality }
                .thenByDescending { QUALITY_NUM_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_UPLOADER_FILTER_ENABLED_KEY
            title = "Filter by Uploader"
            summary = "Load videos only from the specified uploader ID."
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_UPLOADER_ID_KEY
            title = "Uploader ID"
            summary = "Enter the ID of the uploader (e.g., 98965). Requires \"Filter by Uploader\" to be enabled."
            dialogTitle = "Enter Uploader ID"
            setOnPreferenceChangeListener { _, newValue ->
                newValue?.toString().isNullOrBlank().not()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = entries
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched MP4 blocks locally (20 min TTL) for instant skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================
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

    // ============================== Filters ===============================
    private var tagDocument = Document("")

    private fun tagsResults(document: Document): Array<Pair<String, String>> {
        val tagList = mutableListOf(Pair("<Select>", ""))
        tagList.addAll(
            document.select("div.item").mapNotNull {
                val tagValue = it.selectFirst("input")?.attr("value") ?: return@mapNotNull null
                val tagName = it.selectFirst("label")?.text() ?: return@mapNotNull null
                Pair(tagName, tagValue)
            },
        )
        return tagList.toTypedArray()
    }

    override fun getFilterList(): AnimeFilterList = if (preferences.getBoolean(PREF_UPLOADER_FILTER_ENABLED_KEY, false) &&
        preferences.getString(PREF_UPLOADER_ID_KEY, "")?.isNotBlank() == true
    ) {
        AnimeFilterList()
    } else {
        AnimeFilterList(
            OrderFilter(),
            CategoryGroup(CATEGORIES.map { CategoryCheckBox(it.first, it.second) }),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Enter a \"tag\", click \"filter\" then \"reset\" to load tags."),
            TagFilter(),
            TagSearch(tagsResults(tagDocument)),
        )
    }

    private class TagFilter : AnimeFilter.Text("Tag search prefix", "")

    private class TagSearch(results: Array<Pair<String, String>>) : UriPartFilter("Tag Filter", results)

    private class CategoryCheckBox(name: String, val flagId: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup(items: List<CategoryCheckBox>) : AnimeFilter.Group<CategoryCheckBox>("Categories (multi-select)", items)

    private class OrderFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Latest", "latest-updates"),
                Pair("Most Viewed", "most-popular"),
                Pair("Top Rated", "top-rated"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"
        private const val MAX_PARALLEL_FILTERS = 4
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private const val DOWNLOAD_LINK_SELECTOR = "a.tag_item_download[href*=get_file], div.label:contains(Download) ~ a.tag_item"
        private val QUALITY_REGEX = Regex("""\d{3,4}p""")
        private val QUALITY_NUM_REGEX = Regex("""(\d{3,4})p""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p", "360p")

        private const val PREF_UPLOADER_FILTER_ENABLED_KEY = "uploader_filter_enabled"
        private const val PREF_UPLOADER_ID_KEY = "uploader_id"
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"

        private val CATEGORIES = listOf(
            "Straight" to "2109",
            "Futa" to "15",
            "Gay" to "192",
            "Music" to "4747",
            "Iwara" to "1821",
        )
    }
}
