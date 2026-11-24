package eu.kanade.tachiyomi.animeextension.all.xmoviesforyou

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * XMoviesForYou — https://xmoviesforyou.com/
 *
 * - Popular: `/most-viewed` (Top 100 most viewed scenes)
 * - Latest: `/` (`/page/N`)
 * - Search: `/new-search?q={query}&sort={relevance|latest|oldest}&page={N}`
 * - Categories & Studios: `/category/{slug}?page={N}`, `/studio/{slug}?page={N}`
 * - Playback: Primary direct native HLS via `/api/stream/{videoId}?target={target}`
 *   (with required `Origin` + `Referer` headers) plus parallel fallback mirrors
 *   (DoodStream / StreamTape / MixDrop).
 */
class XMoviesForYou :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "XMoviesForYou"

    override val baseUrl = "https://xmoviesforyou.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(fastClient, headers) }
    private val doodExtractor by lazy { DoodExtractor(fastClient) }
    private val mixDropExtractor by lazy { MixDropExtractor(fastClient) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(fastClient) }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-viewed", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup(), page = 1)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val page = PAGE_NUM_REGEX.find(response.request.url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return parseListing(response.asJsoup(), page = page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.trim()
        val selectedCats = filters.filterIsInstance<CategoryGroup>()
            .firstOrNull()?.state?.filter { it.state }?.map { it.slug }.orEmpty()

        if (cleanQuery.isBlank() && selectedCats.size > 1) {
            val requests = selectedCats.take(MAX_PARALLEL_FILTERS).map { slug ->
                categoryRequest(slug, page)
            }
            return fetchAndMergePages(requests, page)
        }

        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        val sort = filters.filterIsInstance<SearchSortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val browse = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.toUriPart().orEmpty()
        val studio = filters.filterIsInstance<StudioFilter>().firstOrNull()?.toUriPart().orEmpty()
        val firstCat = filters.filterIsInstance<CategoryGroup>()
            .firstOrNull()?.state?.firstOrNull { it.state }?.slug.orEmpty()

        if (cleanQuery.isNotBlank()) {
            val builder = "$baseUrl/new-search".toHttpUrl().newBuilder()
                .addQueryParameter("q", cleanQuery)
                .addQueryParameter("sort", sort.ifBlank { "relevance" })
            if (page > 1) builder.addQueryParameter("page", page.toString())
            return GET(builder.build(), headers)
        }

        if (studio.isNotBlank()) {
            val builder = "$baseUrl/studio/$studio".toHttpUrl().newBuilder()
            if (page > 1) builder.addQueryParameter("page", page.toString())
            return GET(builder.build(), headers)
        }

        if (firstCat.isNotBlank()) {
            return categoryRequest(firstCat, page)
        }

        return if (browse == "most-viewed") {
            popularAnimeRequest(page)
        } else {
            latestUpdatesRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val reqUrl = response.request.url
        val page = reqUrl.queryParameter("page")?.toIntOrNull()
            ?: PAGE_NUM_REGEX.find(reqUrl.encodedPath)?.groupValues?.get(1)?.toIntOrNull()
            ?: 1
        return parseListing(response.asJsoup(), page = page)
    }

    private fun categoryRequest(slug: String, page: Int): Request {
        val builder = "$baseUrl/category/$slug".toHttpUrl().newBuilder()
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return GET(builder.build(), headers)
    }

    private fun parseListing(doc: Document, page: Int): AnimesPage {
        // Search page uses `a.card`; main / category / studio / most-viewed use `a.group.flex.flex-col` inside <section> or <main>
        val searchCards = doc.select("a.card")
        val elements = when {
            searchCards.isNotEmpty() -> searchCards
            doc.select("section a.group.flex.flex-col").isNotEmpty() -> doc.select("section a.group.flex.flex-col")
            doc.select("main a.group.flex.flex-col").isNotEmpty() -> doc.select("main a.group.flex.flex-col")
            else -> doc.select("a[data-video-card]")
        }

        val animes = elements.mapNotNull { el ->
            val href = el.absUrl("href").ifBlank { el.attr("href") }.trim()
            if (href.isBlank() || !isVideoPageUrl(href)) return@mapNotNull null
            val img = el.selectFirst("img")
            val title = el.selectFirst("h3.title, h3, h2")?.text()?.trim()?.ifBlank { null }
                ?: img?.attr("alt")?.trim()?.ifBlank { null }
                ?: el.attr("title").trim()
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = img?.extractThumbUrl()
            }
        }.distinctBy { it.url }

        val hasNext = animes.isNotEmpty() && hasNextPage(doc, page)
        return AnimesPage(animes, hasNext)
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        val nextLinks = doc.select("a[href]")
        if (nextLinks.any { it.text().trim().equals("Next", ignoreCase = true) }) return true
        val nextNum = currentPage + 1
        return nextLinks.any { a ->
            val href = a.attr("href")
            href.endsWith("/page/$nextNum") || "page=$nextNum" in href
        }
    }

    private suspend fun fetchAndMergePages(requests: List<Request>, page: Int): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            parseListing(resp.asJsoup(), page = page)
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

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        parseDetailsFromDoc(Jsoup.parse(html, fullUrl), fullUrl)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseDetailsFromDoc(Jsoup.parse(html, url), url)
    }

    private fun parseDetailsFromDoc(doc: Document, pageUrl: String): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(pageUrl)
        title = doc.selectFirst("main h1, h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()

        thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("#player img, video[poster]")?.let { el ->
                el.attr("poster").ifBlank { el.extractThumbUrl().orEmpty() }
            }?.takeIf { it.startsWith("http") }

        val studios = doc.select("a[href^='/studio/']").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }.distinct()
        val stars = doc.select("a[href^='/pornstar/']").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }.distinct()
        val genres = doc.select("a[href^='/category/'], a[href^='/tag/']")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()

        author = studios.joinToString().ifBlank { null }
        artist = stars.joinToString().ifBlank { null }
        genre = genres.joinToString().ifBlank { null }

        description = buildString {
            val metaDesc = doc.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")?.trim().orEmpty()
            if (metaDesc.isNotBlank()) append(metaDesc)
            val extras = buildList {
                if (studios.isNotEmpty()) add("Studio: ${studios.joinToString()}")
                if (stars.isNotEmpty()) add("Stars: ${stars.joinToString()}")
            }
            if (extras.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(extras.joinToString("\n"))
            }
        }.ifBlank { null }

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        buildSingleEpisode(html, fullUrl)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return buildSingleEpisode(html, url)
    }

    private fun buildSingleEpisode(html: String, pageUrl: String): List<SEpisode> {
        val dateStr = DATE_PUBLISHED_REGEX.find(html)?.groupValues?.get(1)
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(pageUrl)
                name = "Full Movie"
                episode_number = 1F
                date_upload = parseIsoDate(dateStr)
            },
        )
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        extractAllVideos(html, fullUrl).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return extractAllVideos(html, url).sort()
    }

    private fun extractAllVideos(html: String, pageUrl: String): List<Video> {
        val videos = mutableListOf<Video>()

        // 1) Primary direct native HLS via `/api/stream/{videoId}?target={target}`
        val videoId = VIDEO_ID_REGEX.find(html)?.groupValues?.get(1)
        val target = TARGET_REGEX.find(html)?.groupValues?.get(1).orEmpty()
        if (!videoId.isNullOrBlank()) {
            val apiUrl = "$baseUrl/api/stream/$videoId".toHttpUrl().newBuilder().apply {
                if (target.isNotBlank()) addQueryParameter("target", target)
            }.build()
            val apiHeaders = headers.newBuilder()
                .set("Accept", "application/json, */*")
                .set("Referer", pageUrl)
                .build()
            val hlsUrl = runCatching {
                fastClient.newCall(GET(apiUrl, apiHeaders)).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    STREAM_URL_JSON_REGEX.find(resp.body.string())?.groupValues?.get(1)
                        ?.replace("\\/", "/")
                }
            }.getOrNull()

            if (!hlsUrl.isNullOrBlank()) {
                val hlsHeaders = headers.newBuilder()
                    .set("Accept", "*/*")
                    .set("Origin", baseUrl)
                    .set("Referer", "$baseUrl/")
                    .build()
                val extracted = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = hlsUrl,
                        referer = "$baseUrl/",
                        masterHeaders = hlsHeaders,
                        videoHeaders = hlsHeaders,
                        videoNameGen = { q -> if (q == "Video") "Direct HLS - HD" else "Direct HLS - $q" },
                    )
                }.getOrDefault(emptyList())
                if (extracted.isNotEmpty()) {
                    videos += extracted
                } else {
                    videos += Video(hlsUrl, "Direct HLS - HD", hlsUrl, headers = hlsHeaders)
                }
            }
        }

        // 2) Fallback host mirrors from `#downloads` (DoodStream / StreamTape / MixDrop)
        val doc = Jsoup.parse(html, pageUrl)
        val mirrorUrls = doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                val lower = href.lowercase(Locale.ROOT)
                if (href.startsWith("http") && MIRROR_HOSTS.any { it in lower }) href else null
            }
            .distinct()

        if (mirrorUrls.isNotEmpty()) {
            val waitMs = if (videos.isNotEmpty()) MIRROR_EXTRA_WAIT_MS else MIRROR_FULL_WAIT_MS
            videos += extractMirrorsInParallel(mirrorUrls, waitMs)
        }

        val distinct = videos.distinctBy { it.videoUrl ?: it.url }
        if (distinct.isEmpty()) {
            throw IOException("No playable video stream found on XMoviesForYou.")
        }
        return distinct
    }

    private fun extractMirrorsInParallel(urls: List<String>, maxWaitMs: Long): List<Video> {
        val pool = Executors.newFixedThreadPool(urls.size.coerceAtMost(3))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            urls.forEach { rawUrl ->
                futures += completion.submit {
                    runCatching { runBlocking { extractMirror(rawUrl) } }.getOrDefault(emptyList())
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs)
            var done = 0
            while (done < futures.size) {
                val rem = deadline - System.nanoTime()
                if (rem <= 0L) break
                val fut = completion.poll(rem, TimeUnit.NANOSECONDS) ?: break
                done++
                val batch = runCatching { fut.get() }.getOrDefault(emptyList())
                if (batch.isNotEmpty()) collected += batch
            }
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
        return collected
    }

    private suspend fun extractMirror(rawUrl: String): List<Video> {
        val lower = rawUrl.lowercase(Locale.ROOT)
        return when {
            DOOD_HOSTS.any { it in lower } -> {
                val embedUrl = rawUrl.replace("/d/", "/e/")
                doodExtractor.videoFromUrl(embedUrl, prefix = "DoodStream - ")?.let(::listOf).orEmpty()
            }

            "streamtape" in lower || "streamta.pe" in lower -> {
                streamTapeExtractor.videoFromUrl(rawUrl, quality = "StreamTape")?.let(::listOf).orEmpty()
            }

            "mixdrop" in lower || "mxdrop" in lower -> {
                val embedUrl = rawUrl.replace("/f/", "/e/")
                mixDropExtractor.videoFromUrl(embedUrl, lang = "MixDrop")
            }

            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return sortedWith(
            compareBy<Video> { !it.quality.contains(pref, ignoreCase = true) }
                .thenBy { if (it.quality.startsWith("Direct HLS", ignoreCase = true)) 0 else 1 }
                .thenByDescending { QUALITY_NUM_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Select categories (multi-select), studio, or search sort"),
        BrowseFilter(),
        SearchSortFilter(),
        StudioFilter(),
        CategoryGroup(),
    )

    private class BrowseFilter :
        UriPartFilter(
            "Section",
            arrayOf(
                Pair("Latest Videos", "latest"),
                Pair("Most Viewed (Top 100)", "most-viewed"),
            ),
        )

    private class SearchSortFilter :
        UriPartFilter(
            "Search Sort",
            arrayOf(
                Pair("Relevance", "relevance"),
                Pair("Latest", "latest"),
                Pair("Oldest", "oldest"),
            ),
        )

    private class StudioFilter :
        UriPartFilter(
            "Studio",
            arrayOf(Pair("All Studios", "")) + LIVE_STUDIOS.toTypedArray(),
        )

    private class CategoryCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup :
        AnimeFilter.Group<CategoryCheckBox>(
            "Categories (multi-select)",
            LIVE_CATEGORIES.map { (label, slug) -> CategoryCheckBox(label, slug) },
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = arrayOf("Direct HLS (Fastest)", "DoodStream", "StreamTape", "MixDrop")
            entryValues = arrayOf("Direct HLS", "DoodStream", "StreamTape", "MixDrop")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================
    private fun isVideoPageUrl(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: "$baseUrl/${url.trimStart('/')}".toHttpUrlOrNull() ?: return false
        if (!http.host.contains("xmoviesforyou.com", ignoreCase = true)) return false
        val path = http.encodedPath.lowercase(Locale.ROOT)
        if (path == "/" || path.isBlank()) return false
        return NON_VIDEO_PREFIXES.none { path.startsWith(it) }
    }

    private fun toAbsoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> "$baseUrl/${url.trimStart('/')}"
    }

    private fun fetchPageCached(url: String): String {
        val now = System.currentTimeMillis()
        pageCache[url]?.let { (ts, body) ->
            if (now - ts < CACHE_TTL_MS) return body
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

    private fun parseIsoDate(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return runCatching {
            val cleaned = iso.substringBefore('.').removeSuffix("Z")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(cleaned)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun Element.extractThumbUrl(): String? {
        val src = attr("data-src").ifBlank { attr("src") }.trim()
        if (src.isBlank() || src.startsWith("data:")) return null
        return when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("http://") || src.startsWith("https://") -> src
            else -> "$baseUrl/${src.trimStart('/')}"
        }
    }

    companion object {
        private const val MAX_PARALLEL_FILTERS = 4
        private const val CACHE_TTL_MS = 3 * 60 * 1000L
        private const val MIRROR_EXTRA_WAIT_MS = 3_500L
        private const val MIRROR_FULL_WAIT_MS = 10_000L

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Direct HLS"

        private val PAGE_NUM_REGEX = Regex("""/page/(\d+)""")
        private val VIDEO_ID_REGEX = Regex("""const\s+videoId\s*=\s*"([^"]+)"""")
        private val TARGET_REGEX = Regex("""const\s+target\s*=\s*"([^"]+)"""")
        private val STREAM_URL_JSON_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")
        private val DATE_PUBLISHED_REGEX = Regex(""""datePublished"\s*:\s*"([^"]+)"""")
        private val QUALITY_NUM_REGEX = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")

        private val DOOD_HOSTS = listOf("playmogo", "dood", "d0o0d", "ds2play", "d-s.io", "myvidplay")
        private val MIRROR_HOSTS = DOOD_HOSTS + listOf("streamtape", "streamta.pe", "mixdrop", "mxdrop")

        private val NON_VIDEO_PREFIXES = listOf(
            "/category/",
            "/categories",
            "/studio/",
            "/studios",
            "/pornstar/",
            "/pornstars",
            "/tag/",
            "/tags",
            "/page/",
            "/pages/",
            "/most-viewed",
            "/new-search",
            "/search",
            "/api/",
        )

        private val LIVE_CATEGORIES = listOf(
            "Brunette" to "brunette",
            "Blonde" to "blonde",
            "Teen" to "teen",
            "MILF" to "milf",
            "Threesome" to "threesome",
            "Interracial" to "interracial",
            "Redhead" to "redhead",
            "Anal" to "anal",
            "Lesbian" to "lesbian",
            "Asian" to "asian",
            "Latina" to "latina",
            "Tattoo" to "tattoo",
            "Orgy" to "orgy",
            "Ebony" to "ebony",
            "Gangbang" to "gangbang",
            "BDSM" to "bdsm",
            "Squirt" to "squirt",
            "Hardcore" to "hardcore",
            "Masturbation" to "masturbation",
            "Kinky" to "kinky",
            "BangBros" to "bangbros",
            "NaughtyAmerica" to "naughtyamerica",
            "TeamSkeet" to "teamskeet",
            "Brazzers" to "brazzers",
            "RealityKings" to "realitykings",
            "21Sextury" to "21sextury",
            "Mofos" to "mofos",
        )

        private val LIVE_STUDIOS = listOf(
            "Blacked" to "blacked",
            "BlackedRaw" to "blackedraw",
            "Vixen" to "vixen",
            "BrazzersExxtra" to "brazzersexxtra",
            "EvilAngel" to "evilangel",
            "PureTaboo" to "puretaboo",
            "MyPervyFamily" to "mypervyfamily",
            "TabooHeat" to "tabooheat",
            "JulesJordan" to "julesjordan",
            "RoccoSiffredi" to "roccosiffredi",
            "Private" to "private",
            "DorcelClub" to "dorcelclub",
            "NubileFilms" to "nubilefilms",
            "ElegantAngel" to "elegantangel",
            "PornWorld" to "pornworld",
            "SexMex" to "sexmex",
            "JapanHDV" to "japanhdv",
            "ATKGirlfriends" to "atkgirlfriends",
            "ImmoralLive" to "immorallive",
            "NewSensations" to "newsensations",
            "Hustler" to "hustler",
            "PenthouseGold" to "penthousegold",
            "CherryPimps" to "cherrypimps",
            "HussiePass" to "hussiepass",
            "LoveHerFeet" to "loveherfeet",
        )
    }
}
