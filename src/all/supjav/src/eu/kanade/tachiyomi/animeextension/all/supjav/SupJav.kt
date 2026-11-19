package eu.kanade.tachiyomi.animeextension.all.supjav

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class SupJav(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 2, 1.seconds)
        .build()

    /**
     * Tight-timeout client for third-party video host extractors & redirector
     * so dead or slow mirrors never stall playback startup.
     */
    private val fastExtractorClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        fastExtractorClient.newBuilder()
            .followRedirects(false)
            .build()
    }

    private val streamProxyClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(streamProxyClient) }

    /*
     * Keep NetworkHelper's Android UA and cookie jar so Cloudflare WebView clearance cookies match.
     */
    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()
    private val videoPageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = when {
            page <= 3 -> "$baseUrl$langPath/popular${if (page > 1) "/page/$page" else ""}"
            page <= 6 -> "$baseUrl$langPath/popular${if (page > 4) "/page/${page - 3}" else ""}?sort=week"
            else -> "$baseUrl$langPath/popular${if (page > 7) "/page/${page - 6}" else ""}?sort=month"
        }
        return GET(url, headers)
    }

    override fun popularAnimeSelector() = "div.posts > div.post > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else (element.selectFirst("a") ?: element)
        setUrlWithoutDomain(link.attr("href"))

        val img = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
        if (img != null) {
            title = img.attr("alt").ifBlank { img.attr("title") }
            val rawThumb = img.attr("data-original")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
                .ifBlank { img.absUrl("data-original") }
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.absUrl("src") }

            thumbnail_url = when {
                rawThumb.startsWith("//") -> "https:$rawThumb"
                rawThumb.startsWith("/") -> "$baseUrl$rawThumb"
                else -> rawThumb
            }
        }
        if (title.isBlank()) {
            title = link.attr("title").ifBlank {
                element.selectFirst("h3, h2")?.text()
                    ?: element.parent()?.selectFirst("h3, h2")?.text()
                    ?: ""
            }
        }
    }

    override fun popularAnimeNextPageSelector() = "a.next[href], a[rel=next][href], div.pagination a.next[href], div.pagination a[rel=next], " +
        "div.pagination li.active + li a[href], div.pagination li:has(.active) + li a[href], .page-numbers[href]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val document = response.useAsJsoup()
        document.throwIfCloudflareChallenge()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        if (animes.isEmpty()) return AnimesPage(emptyList(), false)
        val sortParam = response.request.url.queryParameter("sort")
        val localPage = pageNumber(response.request.url)
        val hasNext = hasNextPage(document, response.request.url) || (sortParam != "month" && localPage <= 3)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl$langPath/category/censored-jav${if (page > 1) "/page/$page" else ""}",
        headers,
    )

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListingResponse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = query.toHttpUrl()
            if (!url.host.equals(baseUrl.toHttpUrl().host, ignoreCase = true)) {
                throw Exception("Unsupported url")
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).removePrefix("/")
            return client.newCall(GET("$baseUrl/$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }

        val cleanQuery = query.replace(BRACKET_TAG_REGEX, "").trim()
        if (cleanQuery.isNotBlank()) {
            return super.getSearchAnime(page, cleanQuery, filters)
        }

        val requests = buildFilterRequests(page, filters)
        if (requests.size <= 1) {
            return super.getSearchAnime(page, "", filters)
        }

        return withContext(Dispatchers.IO) {
            val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
            try {
                val futures = requests.map { req ->
                    pool.submit<AnimesPage?> {
                        runCatching { client.newCall(req).execute().use(::searchAnimeParse) }.getOrNull()
                    }
                }
                val pages = futures.mapNotNull { runCatching { it.get(15, TimeUnit.SECONDS) }.getOrNull() }
                if (pages.isEmpty()) AnimesPage(emptyList(), false) else mergePages(pages)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListingResponse(response)

    private fun parseListingResponse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val document = response.useAsJsoup()
        document.throwIfCloudflareChallenge()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        return AnimesPage(animes, animes.isNotEmpty() && hasNextPage(document, response.request.url))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(BRACKET_TAG_REGEX, "").trim()
        if (cleanQuery.isNotBlank()) {
            val url = "$baseUrl$langPath${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
                .addQueryParameter("s", cleanQuery)
                .build()
                .toString()
            return GET(url, headers)
        }
        return buildFilterRequests(page, filters).first()
    }

    private fun buildFilterRequests(page: Int, filters: AnimeFilterList): List<Request> {
        val pagePath = if (page > 1) "/page/$page" else ""
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()

        val checkedCasts = filters.filterIsInstance<CastGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { it.slug }.orEmpty()
        val typedCasts = filters.filterIsInstance<CastFilter>().firstOrNull()
            ?.state?.split(',')?.map { it.toSlug() }?.filter(String::isNotBlank).orEmpty()
        val castUrls = (checkedCasts + typedCasts).distinct().map { "$baseUrl$langPath/cast/$it$pagePath" }

        val checkedMakers = filters.filterIsInstance<MakerGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { it.slug }.orEmpty()
        val typedMakers = filters.filterIsInstance<MakerTextFilter>().firstOrNull()
            ?.state?.split(',')?.map { it.toSlug() }?.filter(String::isNotBlank).orEmpty()
        val makerUrls = (checkedMakers + typedMakers).distinct().map { "$baseUrl$langPath/maker/$it$pagePath" }

        val checkedTags = filters.filterIsInstance<TagGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { it.slug }.orEmpty()
        val typedTags = filters.filterIsInstance<TagSlugFilter>().firstOrNull()
            ?.state?.split(',')?.map { it.toSlug() }?.filter(String::isNotBlank).orEmpty()
        val tagUrls = (checkedTags + typedTags).distinct().map { "$baseUrl$langPath/tag/$it$pagePath" }

        val categoryUrls = filters.filterIsInstance<CategoryGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { "$baseUrl$langPath/category/${it.slug}$pagePath" }.orEmpty()

        val combinedUrls = (castUrls + makerUrls + tagUrls + categoryUrls)
            .distinct()
            .take(MAX_PARALLEL_FILTERS)

        if (combinedUrls.isNotEmpty()) {
            return combinedUrls.map { GET(it, headers) }
        }

        val fallbackUrl = when (sort) {
            "popular" -> "$baseUrl$langPath/popular$pagePath"
            "week" -> "$baseUrl$langPath/popular$pagePath?sort=week"
            "month" -> "$baseUrl$langPath/popular$pagePath?sort=month"
            else -> "$baseUrl$langPath/category/censored-jav$pagePath"
        }
        return listOf(GET(fallbackUrl, headers))
    }

    /**
     * Merges results from multiple selected casts/makers/tags/categories:
     * videos matching MORE THAN ONE selected filter rank first, followed by
     * round-robin interleaved results across all selected filters.
     */
    private fun mergePages(pages: List<AnimesPage>): AnimesPage {
        val hitCounts = HashMap<String, Int>()
        for (p in pages) {
            for (anime in p.animes) {
                hitCounts[anime.url] = (hitCounts[anime.url] ?: 0) + 1
            }
        }
        val interleaved = mutableListOf<SAnime>()
        val seen = HashSet<String>()
        val maxLen = pages.maxOfOrNull { it.animes.size } ?: 0
        for (i in 0 until maxLen) {
            for (p in pages) {
                val item = p.animes.getOrNull(i) ?: continue
                if (seen.add(item.url)) interleaved.add(item)
            }
        }
        val ranked = interleaved.sortedByDescending { hitCounts[it.url] ?: 1 }
        return AnimesPage(ranked, pages.any { it.hasNextPage })
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    private fun hasNextPage(document: Document, requestUrl: HttpUrl): Boolean {
        val currentPage = pageNumber(requestUrl)
        val links = document.select(popularAnimeNextPageSelector())

        if (links.any { link ->
                val href = link.attr("href").trim()
                if (href.isBlank() || href == "#" || link.hasClass("disabled")) {
                    false
                } else {
                    val explicitNext = link.hasClass("next") ||
                        link.attr("rel").equals("next", ignoreCase = true)
                    if (!explicitNext) {
                        false
                    } else {
                        val target = requestUrl.resolve(href)
                        target != null && pageNumber(target) > currentPage
                    }
                }
            }
        ) {
            return true
        }

        return links.mapNotNull { link ->
            val href = link.attr("href").trim()
            if (href.isBlank() || href == "#") return@mapNotNull null
            val target = requestUrl.resolve(href) ?: return@mapNotNull null
            pageNumber(target)
        }.any { it > currentPage }
    }

    private fun pageNumber(url: HttpUrl): Int {
        val pathPage = PAGE_NUM_REGEX.find(url.encodedPath)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return pathPage
            ?: url.queryParameter("paged")?.toIntOrNull()
            ?: url.queryParameter("page")?.toIntOrNull()
            ?: 1
    }

    private fun String.toSlug(): String = trim()
        .lowercase(Locale.ROOT)
        .replace(NON_SLUG_REGEX, "-")
        .trim('-')

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Multi-select supported! Pick multiple Categories, Tags, Makers, or Actresses."),
        SortFilter(),
        CategoryGroup(CATEGORIES.map { CategoryCheckBox(it.first, it.second) }),
        TagGroup(TAGS.map { TagCheckBox(it.first, it.second) }),
        TagSlugFilter(),
        AnimeFilter.Separator(),
        MakerGroup(MAKERS.map { MakerCheckBox(it.first, it.second) }),
        MakerTextFilter(),
        CastGroup(CASTS.map { CastCheckBox(it.first, it.second) }),
        CastFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
    }

    private class SortFilter : UriPartFilter("Sort (when no other filter is checked)", SORTS)
    private class TagSlugFilter : AnimeFilter.Text("Custom Tags (comma-separated)")
    private class MakerTextFilter : AnimeFilter.Text("Custom Makers (comma-separated)")
    private class CastFilter : AnimeFilter.Text("Custom Actresses / Cast (comma-separated)")

    private class CategoryCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    private class CategoryGroup(items: List<CategoryCheckBox>) : AnimeFilter.Group<CategoryCheckBox>("Categories (multi-select)", items)

    private class TagCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    private class TagGroup(items: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>("Tags (multi-select)", items)

    private class MakerCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    private class MakerGroup(items: List<MakerCheckBox>) : AnimeFilter.Group<MakerCheckBox>("Makers / Studios (multi-select)", items)

    private class CastCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    private class CastGroup(items: List<CastCheckBox>) : AnimeFilter.Group<CastCheckBox>("Popular Actresses (multi-select)", items)

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val request = animeDetailsRequest(anime)
        val urlKey = request.url.toString()
        val html = fetchAndCacheVideoPage(request, urlKey)
        animeDetailsParse(Jsoup.parse(html, urlKey))
    }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.throwIfCloudflareChallenge()
        val content = document.selectFirst("div.content > div.post-meta")
            ?: document.selectFirst("div.post-meta")
        title = content?.selectFirst("h2")?.text() ?: ""
        val image = content?.selectFirst("img")
        if (image != null) {
            val rawThumb = image.attr("data-original")
                .ifBlank { image.attr("data-src") }
                .ifBlank { image.attr("src") }
                .ifBlank { image.absUrl("src") }
            thumbnail_url = when {
                rawThumb.startsWith("//") -> "https:$rawThumb"
                rawThumb.startsWith("/") -> "$baseUrl$rawThumb"
                else -> rawThumb
            }
        }

        content?.selectFirst("div.cats")?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = (content ?: document).select("div.tags > a").textsOrNull()
        status = SAnime.COMPLETED
    }

    private fun Elements.textsOrNull() = eachText().joinToString().takeUnless(String::isEmpty)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "JAV"
            episode_number = 1F
            url = anime.url
        },
    )

    override fun episodeListParse(response: Response): List<SEpisode> {
        response.throwIfCloudflareChallenge()
        return listOf(
            SEpisode.create().apply {
                name = "JAV"
                episode_number = 1F
                url = response.request.url.encodedPath
            },
        )
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val request = videoListRequest(episode)
        val urlKey = request.url.toString()
        val cachedEntry = videoPageCache[urlKey]?.takeIf {
            System.currentTimeMillis() - it.first < VIDEO_CACHE_TTL_MS
        }
        val html = cachedEntry?.second ?: fetchAndCacheVideoPage(request, urlKey)
        val videos = parseVideosFromHtml(html, urlKey)
        if (videos.isNotEmpty()) return@withContext videos.sort()
        if (cachedEntry != null) {
            videoPageCache.remove(urlKey)
            val freshHtml = fetchAndCacheVideoPage(request, urlKey)
            val refreshed = parseVideosFromHtml(freshHtml, urlKey)
            if (refreshed.isNotEmpty()) return@withContext refreshed.sort()
        }
        throw IOException("SupJav returned no playable streams. Try opening in WebView once or retry.")
    }

    override fun videoListParse(response: Response): List<Video> {
        response.throwIfCloudflareChallenge()
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        cacheVideoPage(pageUrl, html)
        return parseVideosFromHtml(html, pageUrl).sort()
    }

    private fun fetchAndCacheVideoPage(request: Request, urlKey: String): String {
        client.newCall(request).execute().use { response ->
            response.throwIfCloudflareChallenge()
            val html = response.body.string()
            if (html.isCloudflareChallengeHtml()) throw CloudflareBlockedException()
            cacheVideoPage(urlKey, html)
            return html
        }
    }

    private fun cacheVideoPage(urlKey: String, html: String) {
        if (html.length > 3000 && !html.isCloudflareChallengeHtml()) {
            if (videoPageCache.size > 30) videoPageCache.clear()
            videoPageCache[urlKey] = System.currentTimeMillis() to html
        }
    }

    private fun parseVideosFromHtml(html: String, pageUrl: String): List<Video> {
        val doc = Jsoup.parse(html, pageUrl)
        doc.throwIfCloudflareChallenge()

        val players = buildList {
            doc.select("div.btnst a[data-link], div.btnst a[href], div.btnst > a, div.btns a[data-link], a.btn-server[data-link], a[href*=supjav.php]").forEach { element ->
                val rawLink = element.attr("data-link").ifBlank { element.attr("href") }.trim()
                if (rawLink.isBlank() || rawLink == "#" || rawLink.startsWith("javascript:", ignoreCase = true) || rawLink.startsWith("#comment", ignoreCase = true)) {
                    return@forEach
                }
                val label = cleanServerLabel(element.text())
                if (label in SUPPORTED_PLAYERS || rawLink.contains("supjav.php", ignoreCase = true)) {
                    add(PlayerRequest(label, rawLink, pageUrl))
                }
            }
        }
            .distinctBy { "${it.label}|${it.id}" }
            .sortedBy { serverPriority(it.label) }

        if (players.isEmpty()) return emptyList()
        return extractPlayersInParallel(players).distinctBy { it.videoUrl }
    }

    /**
     * Runs host extractors in parallel with tight timeouts. As soon as the first playable stream
     * succeeds, waits at most [EXTRA_WAIT_AFTER_FIRST_MS] for other fast mirrors and cancels slow
     * or dead hosts instead of stalling playback startup.
     */
    private fun extractPlayersInParallel(players: List<PlayerRequest>): List<Video> {
        if (players.size == 1) {
            return runCatching { videosFromPlayerBlocking(players[0]) }.getOrDefault(emptyList())
        }

        val pool = Executors.newFixedThreadPool(players.size.coerceAtMost(5))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()

        try {
            for (player in players) {
                futures += completion.submit {
                    runCatching { videosFromPlayerBlocking(player) }.getOrDefault(emptyList())
                }
            }

            val overallDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_EXTRACTION_WAIT_MS)
            var softDeadline = overallDeadline
            var completed = 0

            while (completed < futures.size) {
                val remainingNs = (if (collected.isNotEmpty()) softDeadline else overallDeadline) - System.nanoTime()
                if (remainingNs <= 0L) break

                val done = completion.poll(remainingNs, TimeUnit.NANOSECONDS) ?: break
                completed++
                val batch = runCatching { done.get() }.getOrDefault(emptyList())
                if (batch.isNotEmpty()) {
                    val wasFirst = collected.isEmpty()
                    collected.addAll(batch)
                    if (wasFirst) {
                        val afterFirst = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXTRA_WAIT_AFTER_FIRST_MS)
                        softDeadline = minOf(overallDeadline, afterFirst)
                    }
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }

        return collected
    }

    private val streamtapeExtractor by lazy { StreamTapeExtractor(fastExtractorClient) }
    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val mixdropExtractor by lazy { MixDropExtractor(fastExtractorClient) }
    private val uqloadExtractor by lazy { UqloadExtractor(fastExtractorClient) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("Referer", "$PROTECTOR_URL/").build()
    }

    private fun videosFromPlayerBlocking(player: PlayerRequest): List<Video> {
        val url = resolveProtectorRedirectBlocking(player.id) ?: return emptyList()
        val hoster = when {
            url.contains("turbovid", true) -> "TV"

            url.contains("fc2stream", true) || url.contains("streamwish", true) ||
                url.contains("fastshow", true) || url.contains("wish", true) -> "FST"

            url.contains("streamtape", true) -> "ST"

            url.contains("voe", true) -> "VOE"

            url.contains("dood", true) || url.contains("ds2play", true) ||
                url.contains("myvidplay", true) || url.contains("playmogo", true) ||
                url.contains("d000d", true) || url.contains("doood", true) -> "DOOD"

            url.contains("mixdrop", true) -> "MIXDROP"

            url.contains("uqload", true) -> "UQLOAD"

            url.contains("vidhide", true) -> "VIDHIDE"

            else -> player.label
        }

        return runCatching {
            when (hoster) {
                "TV" -> extractTvVideosBlocking(url, player.pageReferer)

                "ST" -> streamtapeExtractor.videosFromUrl(url)

                "DOOD" -> doodExtractor.videosFromUrl(url)

                "MIXDROP" -> mixdropExtractor.videosFromUrl(url)

                "UQLOAD" -> runBlocking { uqloadExtractor.videosFromUrl(url) }

                "VOE" -> VoeExtractor(fastExtractorClient, embedHeaders(player.pageReferer)).videosFromUrl(url)

                "VAS" -> extractVasBlocking(url)

                "FST" -> {
                    extractPackedHlsBlocking(url, player.pageReferer, hoster).ifEmpty {
                        if (url.contains("fc2stream", ignoreCase = true)) {
                            emptyList()
                        } else {
                            runBlocking {
                                StreamWishExtractor(fastExtractorClient, embedHeaders(player.pageReferer)).videosFromUrl(url)
                            }
                        }
                    }
                }

                "VIDHIDE", "EVS", "LUC" -> {
                    extractPackedHlsBlocking(url, player.pageReferer, hoster).ifEmpty {
                        runBlocking {
                            VidHideExtractor(fastExtractorClient, embedHeaders(player.pageReferer)).videosFromUrl(url) { "$hoster - $it" }
                        }
                    }
                }

                else -> extractPackedHlsBlocking(url, player.pageReferer, hoster)
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveProtectorRedirectBlocking(id: String): String? {
        if (id.isBlank()) return null
        if (id.startsWith("//")) return "https:$id"
        if (id.startsWith("http://") || id.startsWith("https://")) {
            if (!id.contains("supjav", true) && !id.contains("supremejav", true)) return id
            return fetchRedirectBlocking(id)
        }
        return fetchRedirectBlocking(id.reversed()) ?: fetchRedirectBlocking(id)
    }

    private fun fetchRedirectBlocking(code: String): String? = runCatching {
        val targetUrl = if (
            code.startsWith("http://") ||
            code.startsWith("https://") ||
            code.contains("supjav.php")
        ) {
            code
        } else {
            "$PROTECTOR_URL/supjav.php?c=$code"
        }
        noRedirectClient.newCall(GET(targetUrl, protectorHeaders)).execute().use { response ->
            val loc = response.header("Location")?.trim()?.takeIf(String::isNotBlank)
                ?.let { response.request.url.resolve(it)?.toString() }
            response.throwIfCloudflareChallenge()
            loc
        }
    }.getOrNull()

    /**
     * turbovidhls sometimes returns HTTP 500 while still serving a valid
     * player body, so inspect the body regardless of HTTP status.
     */
    private fun extractTvVideosBlocking(url: String, pageReferer: String): List<Video> {
        val body = fastExtractorClient.newCall(GET(url, embedHeaders(pageReferer))).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }.replace("\\/", "/")
        val document = Jsoup.parse(body, url)
        val rawPlaylist = TV_URL_PLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: TV_DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
            ?: TV_FILE_URL_REGEX.find(body)?.groupValues?.get(1)
            ?: FST_M3U8_REGEX.find(body)?.value
            ?: return emptyList()
        val playlist = normalizePlayerUrl(rawPlaylist, url) ?: return emptyList()
        return extractHlsSingleFetch(playlist, url, "TV", checkBrowserWrapped = true)
    }

    private fun extractPackedHlsBlocking(
        url: String,
        pageReferer: String,
        label: String,
    ): List<Video> {
        val finalHttpUrl: HttpUrl
        val body: String
        fastExtractorClient.newCall(GET(url, embedHeaders(pageReferer))).execute().use {
            it.throwIfCloudflareChallenge()
            finalHttpUrl = it.request.url
            body = it.body.string()
        }
        val finalUrl = finalHttpUrl.toString()
        val finalOrigin = "${finalHttpUrl.scheme}://${finalHttpUrl.host}"
        val scripts = Jsoup.parse(body, finalUrl).select("script").joinToString("\n") { script ->
            val data = script.data()
            if ("eval(function(p,a,c" in data) {
                runCatching { JsUnpacker.unpackAndCombine(data) }.getOrNull() ?: data
            } else {
                data
            }
        }.replace("\\/", "/").replace("\\u0026", "&")

        // Prefer hls4 (/stream/.../master.m3u8) via local m3u8Server: StreamWish/VidHide
        // hls2 CDNs often time out while hls4 responds immediately with PNG-disguised TS chunks.
        val hls4Url = HLS4_LINK_REGEX.find(scripts)?.groupValues?.getOrNull(1)
            ?.let { normalizePlayerUrl(it, finalUrl) }
        if (hls4Url != null) {
            val proxied = extractProxiedHlsVariants(hls4Url, finalUrl, finalOrigin, label)
            if (proxied.isNotEmpty()) return proxied
        }

        val playlist = FST_M3U8_REGEX.find(scripts)?.value
            ?.let { normalizePlayerUrl(it, finalUrl) }
            ?: return emptyList()
        return extractHlsSingleFetch(playlist, finalUrl, label, checkBrowserWrapped = false)
    }

    private fun extractProxiedHlsVariants(
        playlistUrl: String,
        referer: String,
        origin: String,
        label: String,
    ): List<Video> {
        val playerHeaders = playbackHeaders(referer)
        val content = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, playerHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (!content.trimStart().startsWith("#EXTM3U")) return emptyList()

        if (!m3u8Server.isRunning()) m3u8Server.startServer()
        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val lines = content.lines().map(String::trim)
        val variants = mutableListOf<Video>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: normalizePlayerUrl(next, playlistUrl) ?: continue
            val localUrl = m3u8Server.processM3u8Url(
                m3u8Url = resolved,
                referer = referer,
                userAgent = headers["User-Agent"],
                origin = origin,
            ) ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qualityLabel = if (height != null) "$label - ${height}p" else "$label - HLS"
            variants.add(Video(localUrl, qualityLabel, localUrl))
        }
        if (variants.isNotEmpty()) return variants.distinctBy { it.videoUrl }

        val localMaster = m3u8Server.processM3u8Url(
            m3u8Url = playlistUrl,
            referer = referer,
            userAgent = headers["User-Agent"],
            origin = origin,
        ) ?: return emptyList()
        return listOf(Video(localMaster, "$label - HLS", localMaster))
    }

    /** VAS (vvaassjjav.xyz/e/{filecode}) serves its HLS master from a JSON API instead of the page. */
    private fun extractVasBlocking(url: String): List<Video> {
        val embedUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val filecode = embedUrl.pathSegments.lastOrNull(String::isNotBlank) ?: return emptyList()
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val apiHeaders = headers.newBuilder()
            .set("Referer", url.substringBefore("#"))
            .set("Origin", origin)
            .build()
        val body = """{"filecode":"$filecode"}""".toRequestBody("application/json".toMediaType())
        val response = fastExtractorClient.newCall(POST("$origin/api/stream", apiHeaders, body)).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
        val playlist = VAS_STREAM_REGEX.find(response)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return emptyList()
        return extractHlsSingleFetch(playlist, "$origin/", "VAS", checkBrowserWrapped = false)
    }

    /**
     * Fetches the HLS master playlist ONCE, validates `#EXTM3U` (and optionally checks for
     * TurboVid's browser-only image-wrapped chunks), and extracts variant streams in memory
     * without making 3 redundant playlist requests.
     */
    private fun extractHlsSingleFetch(
        playlistUrl: String,
        referer: String,
        label: String,
        checkBrowserWrapped: Boolean,
    ): List<Video> {
        val playerHeaders = playbackHeaders(referer)
        val content = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, playerHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (!content.trimStart().startsWith("#EXTM3U")) return emptyList()

        val lines = content.lines().map(String::trim)
        if (checkBrowserWrapped && lines.any { it.isBrowserWrappedMediaLine() }) {
            return emptyList()
        }

        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val variants = mutableListOf<Video>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: normalizePlayerUrl(next, playlistUrl) ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val qualityLabel = if (height != null) "$label - ${height}p" else "$label - HLS"
            variants.add(Video(resolved, qualityLabel, resolved, headers = playerHeaders))
        }

        if (variants.isNotEmpty()) {
            if (checkBrowserWrapped) {
                // Check only the first variant once to reject image-wrapped TurboVid playlists.
                val firstVariantUrl = variants.first().videoUrl ?: variants.first().url
                val firstVariantBody = runCatching {
                    fastExtractorClient.newCall(GET(firstVariantUrl, playerHeaders)).execute().use { r ->
                        if (r.isSuccessful) r.body.string() else ""
                    }
                }.getOrDefault("")
                if (firstVariantBody.lineSequence().any { it.trim().isBrowserWrappedMediaLine() }) {
                    return emptyList()
                }
            }
            return variants.distinctBy { it.videoUrl }
        }

        return listOf(Video(playlistUrl, "$label - HLS", playlistUrl, headers = playerHeaders))
    }

    private fun String.isBrowserWrappedMediaLine(): Boolean {
        if (isBlank() || startsWith("#")) return false
        val path = substringBefore('?')
        return contains("tiktokcdn.com", ignoreCase = true) || path.endsWith(".image", ignoreCase = true)
    }

    private fun cleanServerLabel(text: String): String {
        val clean = text.trim().uppercase(Locale.ROOT)
        return when {
            clean.contains("TV") -> "TV"
            clean.contains("VOE") -> "VOE"
            clean.contains("FST") || clean.contains("WISH") || clean.contains("STREAMWISH") || clean.contains("FASTSTREAM") || clean.contains("FASTSHOW") -> "FST"
            clean.contains("ST") || clean.contains("STREAMTAPE") -> "ST"
            clean.contains("DOOD") -> "DOOD"
            clean.contains("MIXDROP") -> "MIXDROP"
            clean.contains("UQLOAD") -> "UQLOAD"
            clean.contains("VIDHIDE") -> "VIDHIDE"
            else -> clean
        }
    }

    private fun serverPriority(label: String): Int = when (label.uppercase(Locale.ROOT)) {
        "FST" -> 1
        "VAS" -> 2
        "VIDHIDE", "EVS", "LUC" -> 3
        "VOE" -> 4
        "ST" -> 5
        "DOOD" -> 6
        "MIXDROP" -> 7
        "UQLOAD" -> 8
        "TV" -> 9
        else -> 10
    }

    private fun playbackHeaders(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .set("Accept-Encoding", "identity")
        .removeAll("Origin")
        .build()

    private fun embedHeaders(pageReferer: String): Headers = headers.newBuilder()
        .set("Referer", pageReferer)
        .removeAll("Origin")
        .build()

    private fun normalizePlayerUrl(rawUrl: String, pageUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> pageUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video>(
                { !it.quality.contains(quality, ignoreCase = true) },
                { it.quality.startsWith("TV", ignoreCase = true) },
                { -(QUALITY_NUM_REGEX.find(it.quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0) },
            ),
        )
    }

    private fun Response.throwIfCloudflareChallenge() {
        val preview = runCatching { peekBody(CHALLENGE_PEEK_BYTES).string() }.getOrDefault("")
        val cloudflareError = code in CLOUDFLARE_ERROR_CODES &&
            (
                header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    header("cf-ray") != null ||
                    header("cf-mitigated").equals("challenge", ignoreCase = true)
                )
        if (cloudflareError || preview.isCloudflareChallengeHtml()) {
            throw CloudflareBlockedException()
        }
    }

    private fun Document.throwIfCloudflareChallenge() {
        if (html().isCloudflareChallengeHtml()) throw CloudflareBlockedException()
    }

    private fun String.isCloudflareChallengeHtml(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return "_cf_chl_opt" in lower ||
            "id=\"challenge-form\"" in lower ||
            "id='challenge-form'" in lower ||
            TITLE_CHALLENGE_REGEX.containsMatchIn(this)
    }

    private class CloudflareBlockedException : IOException("Cloudflare blocked SupJav. Open the page in WebView once, complete the challenge, then retry.")

    private data class PlayerRequest(
        val label: String,
        val id: String,
        val pageReferer: String,
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L
        private const val VIDEO_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val MAX_EXTRACTION_WAIT_MS = 10_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_500L
        private const val MAX_PARALLEL_FILTERS = 4

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 429, 503)
        private val TITLE_CHALLENGE_REGEX =
            Regex("""<title>\s*(?:just a moment|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val TV_URL_PLAY_REGEX =
            Regex("""\b(?:(?:var|let|const)\s+)?urlPlay\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TV_DATA_HASH_REGEX =
            Regex("""\bdata-hash\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val TV_FILE_URL_REGEX =
            Regex("""\bfile\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val HLS4_LINK_REGEX = Regex(""""hls4"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val FST_M3U8_REGEX =
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val QUALITY_NUM_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        private val PAGE_NUM_REGEX = Regex("""/page/(\d+)(?:/|$)""", RegexOption.IGNORE_CASE)
        private val BRACKET_TAG_REGEX = Regex("""\[[^\]]+\]""")
        private val NON_SLUG_REGEX = Regex("""[^a-z0-9-]+""")

        private val SUPPORTED_PLAYERS = setOf("TV", "FST", "VOE", "ST", "DOOD", "MIXDROP", "UQLOAD", "VIDHIDE", "EVS", "VAS", "LUC")

        private val VAS_STREAM_REGEX = Regex(""""streaming_url"\s*:\s*"([^"]+)"""")

        private val CATEGORIES = arrayOf(
            "Censored JAV" to "censored-jav",
            "Uncensored JAV" to "uncensored-jav",
            "Amateur" to "amateur",
            "Reducing Mosaic" to "reducing-mosaic",
            "English Subtitles" to "english-subtitles",
            "Chinese Subtitles" to "chinese-subtitles",
            "FC2 PPV" to "fc2-ppv",
            "Uncensored Leaked" to "uncensored-leaked",
        )

        private val SORTS = arrayOf(
            "Latest" to "",
            "Popular (Today)" to "popular",
            "Popular (This Week)" to "week",
            "Popular (This Month)" to "month",
        )

        private val TAGS = arrayOf(
            "4K" to "4k",
            "Amateur" to "amateur",
            "Anal" to "anal",
            "Beautiful Girl" to "beautiful-girl",
            "Big Ass" to "big-ass",
            "Big Tits" to "big-tits",
            "Blowjob" to "blowjob",
            "Bondage" to "bondage",
            "Bukkake" to "bukkake",
            "Cheating Wife" to "cheating-wife",
            "Chinese Subtitle" to "chinese-subtitle",
            "Cosplay" to "cosplay",
            "Cowgirl" to "cowgirl",
            "Creampie" to "creampie",
            "Cuckold" to "cuckold",
            "Cumshot" to "cumshot",
            "Deep Throat" to "deep-throat",
            "Documentary" to "documentary",
            "Doggy Style" to "doggy-style",
            "Drama" to "drama",
            "English Subtitle" to "english-subtitle",
            "Facials" to "facials",
            "FC2PPV" to "fc2ppv",
            "Gangbang" to "gangbang",
            "Handjob" to "handjob",
            "Lesbian" to "lesbian",
            "Married Woman" to "married-woman",
            "Massage" to "massage",
            "Masturbation" to "masturbation",
            "Mature Woman" to "mature-woman",
            "Nurse" to "nurse",
            "Office Lady" to "office-lady",
            "Older Sister" to "older-sister",
            "Orgy" to "orgy",
            "Pantyhose" to "pantyhose",
            "POV" to "pov",
            "School Girls" to "school-girls",
            "Sex Toys" to "sex-toys",
            "Slender" to "slender",
            "Slut" to "slut",
            "Squirting" to "squirting",
            "Stepfamily" to "stepfamily",
            "Threesome" to "threesome",
            "Titty Fuck" to "titty-fuck",
            "Uncensored Leaked" to "uncensored-leaked",
            "Voyeur" to "voyeur",
            "VR" to "vr",
        )

        private val MAKERS = arrayOf(
            "S1 NO.1 STYLE" to "s1-no-1-style",
            "MOODYZ" to "moodyz",
            "MADONNA" to "madonna",
            "Idea Pocket" to "idea-pocket",
            "Prestige" to "prestige",
            "Attackers" to "attackers",
            "SOD Create" to "sod-create",
            "Faleno" to "faleno",
            "Fitch" to "fitch",
            "E-BODY" to "e-body",
            "Kawaii" to "kawaii",
            "Wanz Factory" to "wanz-factory",
            "OPPAI" to "oppai",
            "Premium" to "premium",
            "Venus" to "venus",
            "Honnaka" to "honnaka",
            "Glory Quest" to "glory-quest",
            "Das!!" to "das",
            "Hunter" to "hunter",
            "Tameike Goro" to "tameike-goro",
            "Center Village" to "center-village",
            "K M Produce" to "k-m-produce",
            "Nagae Style" to "nagae-style",
            "Alice Japan" to "alice-japan",
            "Maxing" to "maxing",
        )

        private val CASTS = arrayOf(
            "Yua Mikami" to "yua-mikami",
            "Yui Hatano" to "yui-hatano",
            "Ai Sayama" to "ai-sayama",
            "Julia" to "julia",
            "Eimi Fukada" to "eimi-fukada",
            "Saeko Matsushita" to "saeko-matsushita",
            "Yumi Kazama" to "yumi-kazama",
            "Mina Kitano" to "mina-kitano",
            "Nao Jinguji" to "nao-jinguji",
            "Tsubasa Amami" to "tsubasa-amami",
            "Ririko Kinoshita" to "ririko-kinoshita",
            "Kanako Ioka" to "kanako-ioka",
            "Miu Shiromine" to "miu-shiromine",
            "Meari Tachibana" to "meari-tachibana",
            "Yu Shinoda" to "yu-shinoda",
            "Hana Haruna" to "hana-haruna",
            "Tina Nanami" to "tina-nanami",
            "Ayaka Mutou" to "ayaka-mutou",
            "Remu Suzumori" to "remu-suzumori",
            "Kana Momonogi" to "kana-momonogi",
        )

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
