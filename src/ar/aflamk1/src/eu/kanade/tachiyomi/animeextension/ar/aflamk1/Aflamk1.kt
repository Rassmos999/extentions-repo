package eu.kanade.tachiyomi.animeextension.ar.aflamk1

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Aflamk1 — KVS tube (aflamk1.net).
 *
 * Listings: most-popular / latest-updates / top-rated
 * Search: /search/?q=…&from_videos=N
 * Categories: /categories/{slug}/[page]/ (multi-select supported)
 * Streams: flashvars video_url (plain get_file + v-acctoken; function/0 if present)
 */
class Aflamk1 :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Aflamk1"

    override val baseUrl = "https://www.aflamk1.net"

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

    override fun popularAnimeRequest(page: Int): Request = GET(pagedPath("most-popular", page), headers)

    override fun popularAnimeSelector(): String = "div.list-videos div.item > a, div.item > a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(cleanUrl(element.attr("abs:href")))
        val rawTitle = element.attr("title").ifBlank {
            element.selectFirst("strong.title")?.text().orEmpty()
        }.trim()
        val parentCard = element.parent() ?: element
        val duration = parentCard.selectFirst(
            "div.duration, span.duration, div.time, span.time, span.clock, time, div.timevideo1, span.label",
        )?.text()?.trim()?.ifBlank { null }
        title = if (!duration.isNullOrEmpty() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("img.thumb, img")?.let { img ->
            img.attr("abs:data-original").ifBlank { null }
                ?: img.attr("abs:data-src").ifBlank { null }
                ?: img.attr("abs:data-webp").ifBlank { null }
                ?: img.attr("abs:src").takeUnless {
                    it.contains("data:image") || it.endsWith(".gif")
                }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination li.next a, a[rel=next], div.pagination a.next"

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(
        response.asJsoup(),
        hasFromVideos = response.request.url.queryParameter("from_videos") != null,
    )

    private fun parseListing(document: Document, hasFromVideos: Boolean): AnimesPage {
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = when {
            document.selectFirst(popularAnimeNextPageSelector()) != null -> true
            hasFromVideos -> animes.size >= 16
            else -> false
        } && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(pagedPath("latest-updates", page), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) {
            val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
            val reqs = collectFilterRequests(page, sort, filters)
            if (reqs.size > 1) {
                return fetchAndMergePages(reqs.take(MAX_PARALLEL_FILTERS))
            }
        }
        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    private fun collectFilterRequests(page: Int, sort: String, filters: AnimeFilterList): List<Request> {
        val sortBy = mapSortBy(sort)
        val tagReqs = splitMulti(filters.firstInstanceOrNull<TagFilter>()?.state).map { tag ->
            val slug = slugify(tag)
            val base = if (page <= 1) "$baseUrl/tags/$slug/" else "$baseUrl/tags/$slug/$page/"
            val url = if (sortBy.isNotBlank()) base.toHttpUrl().newBuilder().addQueryParameter("sort_by", sortBy).build().toString() else base
            GET(url, headers)
        }
        val catReqs = filters.firstInstanceOrNull<CategoryGroup>()
            ?.state?.filter { it.state }
            ?.map { cat ->
                val base = if (page <= 1) "$baseUrl/${cat.value}/" else "$baseUrl/${cat.value}/$page/"
                val url = if (sortBy.isNotBlank()) base.toHttpUrl().newBuilder().addQueryParameter("sort_by", sortBy).build().toString() else base
                GET(url, headers)
            }.orEmpty()
        return tagReqs + catReqs
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val firstTag = splitMulti(filters.firstInstanceOrNull<TagFilter>()?.state).firstOrNull().orEmpty()

        if (query.isNotBlank()) {
            val fullQuery = if (firstTag.isNotBlank()) "${query.trim()} $firstTag" else query.trim()
            val sortBy = mapSortBy(sort)
            val url = "$baseUrl/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", fullQuery)
                .apply {
                    if (sortBy.isNotBlank()) addQueryParameter("sort_by", sortBy)
                    if (page > 1) addQueryParameter("from_videos", page.toString())
                }
                .build()
            return GET(url, headers)
        }

        val firstFilter = collectFilterRequests(page, sort, filters).firstOrNull()
        return when {
            firstFilter != null -> firstFilter
            sort.isNotBlank() -> GET(pagedPath(sort, page), headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = cleanUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val flash = document.html()
        title = FLASH_TITLE.find(flash)?.groupValues?.get(1)
            ?: document.selectFirst("h1, meta[property=og:title]")?.let {
                it.attr("content").ifBlank { it.text() }
            }.orEmpty()
        val cats = FLASH_CATS.find(flash)?.groupValues?.get(1).orEmpty()
        val tags = FLASH_TAGS.find(flash)?.groupValues?.get(1).orEmpty()
        genre = listOf(cats, tags)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()
            .ifBlank {
                document.select("a[href*=/categories/], a[href*=/tags/], a[href*=/tag/]")
                    .eachText().joinToString()
            }
        description = document.selectFirst("meta[property=og:description], div.info .description, div.block-details")
            ?.let { el -> el.attr("content").ifBlank { el.text() } }
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img.thumb[src*=videos_screenshots], video[poster]")?.let {
                it.attr("abs:src").ifBlank { it.attr("abs:poster") }
            }
        status = SAnime.COMPLETED
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(cleanUrl(anime.url), headers)

    override fun episodeListRequest(anime: SAnime): Request = GET(cleanUrl(anime.url), headers)

    private fun cleanUrl(url: String): String {
        val clean = url.trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return if ('?' !in clean && !clean.endsWith("/")) "$clean/" else clean
        }
        val withoutQuery = clean.substringBefore('?')
        val query = clean.substringAfter('?', "")
        val path = withoutQuery.removePrefix(baseUrl).trim('/')
        if (path.isEmpty()) return "$baseUrl/"
        val builder = baseUrl.toHttpUrl().newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            if (segment.contains("%")) {
                builder.addEncodedPathSegment(segment)
            } else {
                builder.addPathSegment(segment)
            }
        }
        // aflamk1.net redirects any video URL without a trailing slash back to homepage!
        builder.addPathSegment("")
        if (query.isNotEmpty()) {
            builder.encodedQuery(query)
        }
        return builder.build().toString()
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = cleanUrl(anime.url)
        listOf(
            SEpisode.create().apply {
                name = "Video"
                setUrlWithoutDomain(fullUrl)
                episode_number = 1f
                date_upload = System.currentTimeMillis()
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            episode_number = 1f
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = cleanUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        parseVideosFromHtml(html).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseVideosFromHtml(html)
    }

    private fun parseVideosFromHtml(html: String): List<Video> {
        val license = LICENSE_CODE.find(html)?.groupValues?.get(1)
        val videoHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        val pairs = mutableListOf<Pair<String, String>>()
        FLASH_URL.findAll(html).forEach { m ->
            val key = m.groupValues[1]
            val url = m.groupValues[2]
            if (url.isBlank() || url == "1") return@forEach
            if (!url.contains("get_file") && !url.startsWith("function") && !url.contains(".mp4") &&
                !url.contains(".m3u8")
            ) {
                return@forEach
            }
            val quality = Regex("""${Regex.escape(key)}_text:\s*'([^']*)'""")
                .find(html)?.groupValues?.get(1)?.ifBlank { null }
                ?: qualityFromUrl(url)
                ?: if (html.contains("video_url_hd: '1'") && key == "video_url") "HD" else "MP4"
            pairs += quality to url
        }

        if (pairs.isEmpty()) {
            GET_FILE.findAll(html).map { it.value }.distinct().forEach { url ->
                pairs += (qualityFromUrl(url) ?: "MP4") to url
            }
        }

        val videos = pairs.mapNotNull { (quality, raw) ->
            val resolved = try {
                if (raw.startsWith("function")) {
                    if (license == null) return@mapNotNull null
                    decryptHash(raw, license)
                } else {
                    raw
                }
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (resolved.isBlank()) return@mapNotNull null
            Video(resolved, quality, resolved, headers = videoHeaders)
        }.distinctBy { it.url }

        if (videos.isEmpty()) {
            throw IOException("No playable streams found (flashvars missing or private video)")
        }
        return if (preferences.getBoolean("pref_seek_cache", true)) {
            m3u8Server.wrapVideosForCache(
                videos = videos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = videoHeaders["Referer"] ?: "$baseUrl/",
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            videos
        }
    }

    private val m3u8Server by lazy { M3u8ServerManager(client) }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> { !it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending {
                    QUALITY_NUM.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    // =============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("يمكنك اختيار أكثر من تصنيف أو كتابة أكثر من تاج بفواصل (,)"),
        SortFilter(),
        CategoryGroup(),
        TagFilter(),
    )

    private class SortFilter :
        UriSelectFilter(
            "الترتيب / Sort",
            arrayOf(
                "الأكثر شعبية (Most popular)" to "most-popular",
                "الأحدث (Latest updates)" to "latest-updates",
                "الأعلى تقييماً (Top rated)" to "top-rated",
            ),
        )

    private class CategoryCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup :
        AnimeFilter.Group<CategoryCheckBox>(
            "التصنيفات / Categories (اختيار متعدد)",
            CATEGORIES.map { (label, path) -> CategoryCheckBox(label, path) },
        )

    private class TagFilter : AnimeFilter.Text("تاج / Tag (افصل بفاصلة ,)")

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
                            parseListing(resp.asJsoup(), req.url.queryParameter("from_videos") != null)
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

    private fun mapSortBy(sort: String): String = when (sort) {
        "latest-updates" -> "post_date"
        "most-popular" -> "video_viewed"
        "top-rated" -> "rating"
        else -> ""
    }

    private fun splitMulti(raw: String?): List<String> = raw.orEmpty()
        .split(',', '،')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun pagedPath(section: String, page: Int): String = if (page <= 1) "$baseUrl/$section/" else "$baseUrl/$section/$page/"

    private fun slugify(raw: String): String = raw.trim().replace(Regex("""\s+"""), "-").trim('-')

    private fun qualityFromUrl(url: String): String? = QUALITY_FROM_URL.find(url)?.groupValues?.get(1)?.let { "${it}p" }

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

    private fun decryptHash(videoUrl: String, licenseCode: String, hashRange: Int = 16): String {
        val parts = videoUrl.split("/").toMutableList()
        require(parts.size > 7) { "Unexpected KVS URL shape" }
        val hashFull = parts[7]
        val convertLen = 2 * hashRange
        var hash = hashFull.take(convertLen)
        val nonConvert = hashFull.drop(convertLen)
        val seed = calcSeed(licenseCode, hashRange)
        val seedArray = seed.map { it.toString() }
        for (k in hash.length - 1 downTo 0) {
            val hashArray = hash.map { it.toString() }.toMutableList()
            var l = k
            for (m in k until seedArray.size) l += seedArray[m].toInt()
            while (l >= hashArray.size) l -= hashArray.size
            val n = StringBuilder()
            for (o in hashArray.indices) {
                n.append(
                    when (o) {
                        k -> hashArray[l]
                        l -> hashArray[k]
                        else -> hashArray[o]
                    },
                )
            }
            hash = n.toString()
        }
        parts[7] = hash + nonConvert
        return parts.drop(2).joinToString("/").replaceFirst(":///", "://")
    }

    private fun calcSeed(licenseCode: String, hashRange: Int): String {
        val licenseCodeArray = licenseCode.map { it.toString() }
        val fb = StringBuilder()
        for (c in licenseCodeArray) {
            if (c == "$") continue
            val v = c.toInt()
            fb.append(if (v != 0) c else "1")
        }
        val f = fb.toString()
        val j = f.length / 2
        val k = f.substring(0, j + 1).toInt()
        val l = f.substring(j).toInt()
        var g = abs(l - k)
        var fi = g
        g = abs(k - l)
        fi += g
        fi *= 2
        val s = fi.toString()
        val fArray = s.map { it.toString() }
        val i = hashRange / 2 + 2
        val m = StringBuilder()
        for (g2 in 0..j) {
            for (h in 1..4) {
                var n = licenseCodeArray[g2 + h].toInt() + fArray[g2].toInt()
                if (n >= i) n -= i
                m.append(n)
            }
        }
        return m.toString()
    }

    companion object {
        private const val MAX_PARALLEL_FILTERS = 4
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val FLASH_TITLE = Regex("""video_title:\s*'((?:\\'|[^'])*)'""")
        private val FLASH_CATS = Regex("""video_categories:\s*'((?:\\'|[^'])*)'""")
        private val FLASH_TAGS = Regex("""video_tags:\s*'((?:\\'|[^'])*)'""")
        private val LICENSE_CODE = Regex("""license_code:\s*'([^']+)'""")
        private val FLASH_URL = Regex("""(video_url|video_alt_url\d*):\s*'((?:\\'|[^'])*)'""")
        private val GET_FILE = Regex("""https?://[^"'\\\s]+/get_file/[^"'\\\s]+""")
        private val QUALITY_FROM_URL = Regex("""_(\d{3,4})p\.(?:mp4|m3u8)""", RegexOption.IGNORE_CASE)
        private val QUALITY_NUM = Regex("""(\d{3,4})""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240", "HD", "MP4")

        // All 8 verified live categories from https://www.aflamk1.net/categories/
        private val CATEGORIES = listOf(
            "سكس مترجم" to "categories/سكس-مترجم",
            "سكس محارم" to "categories/سكس-محارم-مترجم",
            "سكس اخوات" to "categories/سكس-اخوات-مترجم",
            "سكس امهات" to "categories/سكس-امهات-مترجم",
            "سكس جماعي" to "categories/سكس-جماعى-مترجم",
            "سكس محارم الاب" to "categories/سكس-محارم-الاب-مترجم",
            "سكس شيميل" to "categories/سكس-شيميل-مترجم",
            "سكس سحاق" to "categories/سكس-سحاق-مترجم",
        )
    }
}
