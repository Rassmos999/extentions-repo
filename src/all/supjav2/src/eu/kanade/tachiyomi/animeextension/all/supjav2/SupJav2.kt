package eu.kanade.tachiyomi.animeextension.all.supjav2

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

class SupJav2(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "supjav2").also { it.syncSeedFromPreferences() }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = tasteEngine.rerankPage(super.getPopularAnime(page))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = tasteEngine.rerankPage(super.getLatestUpdates(page))

    override val name = "SupJav 2"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(CfWebBridgeInterceptor { baseUrl })
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(13, TimeUnit.SECONDS)
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
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/category/censored-jav${if (page > 1) "/page/$page" else ""}", headers)

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

        val duration = (
            element.selectFirst("span.duration, span.time, div.duration, time")
                ?: element.parent()?.selectFirst("span.duration, span.time, div.duration, time")
            )?.text()?.trim()

        if (!duration.isNullOrBlank()) {
            title = "$title [$duration]"
        }
    }

    override fun popularAnimeNextPageSelector() = "a.next[href], a[rel=next][href], .pagination a.next[href], .pagination a[rel=next][href], .pagination li.active + li a[href], .page-numbers[href]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val document = response.asJsoup()
        document.throwIfCloudflareChallenge()
        val animes = document.select(popularAnimeSelector())
            .map(::popularAnimeFromElement)
            .distinctBy { it.url }
        return AnimesPage(animes, animes.isNotEmpty() && hasNextPage(document, response.request.url))
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl$langPath${if (page > 1) "/page/$page" else ""}", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = tasteEngine.onSearchResult(page, query, getSearchAnimeBase(page, query, filters))

    private suspend fun getSearchAnimeBase(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = runCatching { query.toHttpUrl() }.getOrNull()
                ?: return AnimesPage(emptyList(), false)
            if (!url.host.equals(baseUrl.toHttpUrl().host, ignoreCase = true)) {
                throw Exception("Unsupported url host")
            }
            val path = url.encodedPath.removePrefix("/")
            if (path.isBlank()) return AnimesPage(emptyList(), false)
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH).removePrefix("/").trim()
            if (id.isBlank()) return AnimesPage(emptyList(), false)
            val url = when {
                id.startsWith("http://") || id.startsWith("https://") -> id
                id.startsWith("/") -> "$baseUrl$id"
                else -> "$baseUrl/$id"
            }
            return runCatching {
                client.newCall(GET(url, headers))
                    .awaitSuccess()
                    .use(::searchAnimeByIdParse)
            }.getOrElse { AnimesPage(emptyList(), false) }
        }

        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            val requests = buildFilterRequests(page, filters)
            if (requests.size > 1) {
                return fetchAndMergePages(requests)
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
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

    private suspend fun fetchAndMergePages(requests: List<Request>): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            popularAnimeParse(resp)
                        }
                    }.getOrNull()
                }
            }
            val pages = futures.mapNotNull { runCatching { it.get(15, TimeUnit.SECONDS) }.getOrNull() }
            mergePages(pages)
        } finally {
            pool.shutdownNow()
        }
    }

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

    private fun String.toSlug(): String = trim().lowercase(Locale.ROOT).replace(NON_SLUG_REGEX, "-").trim('-')

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val pagePath = if (page > 1) "/page/$page" else ""
        return when {
            query.startsWith(PREFIX_SEARCH) -> {
                val id = query.removePrefix(PREFIX_SEARCH).removePrefix("/").trim()
                GET("$baseUrl/$id", headers)
            }

            query.isNotBlank() -> {
                val url = "$baseUrl$langPath$pagePath/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query.trim())
                    .build()
                GET(url, headers)
            }

            else -> buildFilterRequests(page, filters).first()
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
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

    private class SortFilter : UriPartFilter("Sort (when no filter checked)", SORTS)
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
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = getAnimeDetailsBase(anime).also { tasteEngine.recordDetails(anime, it) }

    private suspend fun getAnimeDetailsBase(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.throwIfCloudflareChallenge()
        val content = document.selectFirst("div.content > div.post-meta")
            ?: document.selectFirst("div.post-meta")
        if (content != null) {
            title = content.selectFirst("h2")?.text() ?: ""
            val image: Element? = content.selectFirst("img")
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

            content.selectFirst("div.cats")?.run {
                author = select("p:contains(Maker :) > a").textsOrNull()
                artist = select("p:contains(Cast :) > a").textsOrNull()
            }
            genre = content.select("div.tags > a").textsOrNull()
        }
        status = SAnime.COMPLETED
    }

    private fun Elements.textsOrNull() = eachText().joinToString().takeUnless(String::isEmpty)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        tasteEngine.recordLibraryAnime(anime)
        return getEpisodeListBase(anime)
    }

    private suspend fun getEpisodeListBase(anime: SAnime): List<SEpisode> = listOf(
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

    override fun episodeListSelector(): String = "div.content"
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = getVideoListBase(episode).also { if (it.isNotEmpty()) tasteEngine.recordPlayByUrl(episode.url) }

    private suspend fun getVideoListBase(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        parseVideosFromHtml(html, fullUrl).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        response.throwIfCloudflareChallenge()
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        cachePage(pageUrl, html)
        return parseVideosFromHtml(html, pageUrl).sort()
    }

    private fun parseVideosFromHtml(html: String, pageReferer: String): List<Video> {
        if (html.isCloudflareChallengeHtml()) throw CloudflareBlockedException()
        val doc = Jsoup.parse(html, pageReferer)

        val players = doc.select("div.btnst a[data-link], div.btns a[data-link], a.btn-server[data-link], a[href*='supjav.php']")
            .mapNotNull { element ->
                val label = cleanServerLabel(element.text())
                val link = element.attr("data-link")
                    .ifBlank { element.attr("data-url") }
                    .ifBlank { element.attr("href") }
                    .trim()
                if (link.isBlank() || link.startsWith("javascript:", ignoreCase = true)) {
                    return@mapNotNull null
                }
                PlayerRequest(label, link, pageReferer)
            }
            .distinctBy { it.id }

        if (players.isEmpty()) {
            throw IOException("SupJav 2 returned no server buttons. Refresh the video page and retry.")
        }

        val pool = Executors.newFixedThreadPool(players.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            players.forEach { player ->
                futures += completion.submit {
                    runCatching { videosFromPlayerBlocking(player) }.getOrDefault(emptyList())
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_EXTRACTION_WAIT_MS)
            var softDeadline = deadline
            var done = 0
            while (done < futures.size) {
                val rem = (if (collected.isNotEmpty()) softDeadline else deadline) - System.nanoTime()
                if (rem <= 0L) break
                val fut = completion.poll(rem, TimeUnit.NANOSECONDS) ?: break
                done++
                val batch = runCatching { fut.get() }.getOrDefault(emptyList())
                if (batch.isNotEmpty()) {
                    val wasFirst = collected.isEmpty()
                    collected += batch
                    if (wasFirst) {
                        softDeadline = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXTRA_WAIT_AFTER_FIRST_MS))
                    }
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }

        val videos = collected
            .distinctBy { it.videoUrl ?: it.url }
            .sortedBy { video -> if (video.quality.startsWith("TV", ignoreCase = true)) 1 else 0 }

        if (videos.isEmpty()) {
            throw IOException("SupJav 2 servers were found, but none returned a playable stream. Retry or open the video in WebView once.")
        }

        val useSeekCache = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
        return if (useSeekCache) {
            m3u8Server.wrapVideosForCache(
                videos = videos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = "$baseUrl/",
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            videos
        }
    }

    private fun proxyTransform(m3u8Url: String, referer: String, origin: String): String = m3u8Server.processM3u8Url(
        m3u8Url = m3u8Url,
        referer = referer,
        userAgent = headers["User-Agent"],
        origin = origin,
    ) ?: m3u8Url

    private fun videosFromPlayerBlocking(player: PlayerRequest): List<Video> {
        val url = resolveProtectorRedirectBlocking(player.id) ?: return emptyList()
        val hoster = when {
            url.contains("turbovid", true) -> "TV"

            url.contains("fc2stream", true) || url.contains("streamwish", true) ||
                url.contains("fastshow", true) || url.contains("wish", true) ||
                url.contains("niramirus", true) || url.contains("callistanise", true) -> "FST"

            url.contains("streamtape", true) -> "ST"

            url.contains("voe", true) -> "VOE"

            url.contains("dood", true) || url.contains("ds2play", true) ||
                url.contains("myvidplay", true) || url.contains("playmogo", true) ||
                url.contains("d000d", true) || url.contains("doood", true) -> "DOOD"

            url.contains("mixdrop", true) -> "MIXDROP"

            url.contains("uqload", true) -> "UQLOAD"

            url.contains("vidhide", true) || url.contains("earnvids", true) || url.contains("morencius", true) -> "VIDHIDE"

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
                                StreamWishExtractor(fastExtractorClient, embedHeaders(player.pageReferer))
                                    .videosFromUrl(url, { "$hoster - $it" }, ::proxyTransform)
                            }
                        }
                    }
                }

                "VIDHIDE", "EVS", "LUC" -> {
                    extractPackedHlsBlocking(url, player.pageReferer, hoster).ifEmpty {
                        runBlocking {
                            VidHideExtractor(fastExtractorClient, embedHeaders(player.pageReferer))
                                .videosFromUrl(url, { "$hoster - $it" }, ::proxyTransform)
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
            if (!id.contains("supjav", true)) return id
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
            response.throwIfCloudflareChallenge()
            response.header("Location")?.trim()?.takeIf(String::isNotBlank)
                ?.let { response.request.url.resolve(it)?.toString() }
        }
    }.getOrNull()

    private fun extractTvVideosBlocking(url: String, pageReferer: String): List<Video> {
        val body = fastExtractorClient.newCall(GET(url, embedHeaders(pageReferer))).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }.replace("\\/", "/").replace("\\u0026", "&")
        val document = Jsoup.parse(body, url)
        val rawPlaylist = TV_URL_PLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: TV_DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
            ?: TV_FILE_URL_REGEX.find(body)?.groupValues?.get(1)
            ?: FST_M3U8_REGEX.find(body)?.value
            ?: return emptyList()
        val playlist = normalizePlayerUrl(rawPlaylist, url) ?: return emptyList()
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl
        return extractProxiedHlsVariants(playlist, "$origin/", origin, "TV")
    }

    /**
     * Prefers `hls4` (`/stream/.../master.m3u8`) routed via `m3u8Server` (strips PNG-prefixed
     * TS segments and avoids dead/timing-out `hls2` CDNs).
     */
    private fun extractPackedHlsBlocking(
        url: String,
        pageReferer: String,
        label: String,
    ): List<Video> {
        val body = fastExtractorClient.newCall(GET(url, embedHeaders(pageReferer))).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }
        val scripts = Jsoup.parse(body, url).select("script").joinToString("\n") { script ->
            val data = script.data()
            if ("eval(function(p,a,c" in data) {
                runCatching { JsUnpacker.unpackAndCombine(data) }.getOrNull() ?: data
            } else {
                data
            }
        }.replace("\\/", "/").replace("\\u0026", "&")

        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl
        val referer = "$origin/"

        val candidates = listOfNotNull(
            HLS4_REGEX.find(scripts)?.groupValues?.getOrNull(1)?.let { normalizePlayerUrl(it, url) },
            HLS2_REGEX.find(scripts)?.groupValues?.getOrNull(1)?.let { normalizePlayerUrl(it, url) },
            HLS3_REGEX.find(scripts)?.groupValues?.getOrNull(1)?.let { normalizePlayerUrl(it, url) },
            FST_M3U8_REGEX.find(scripts)?.value?.let { normalizePlayerUrl(it, url) },
        ).distinct()

        for (playlist in candidates) {
            val extracted = extractProxiedHlsVariants(playlist, referer, origin, label)
            if (extracted.isNotEmpty()) return extracted
        }
        return emptyList()
    }

    private fun extractProxiedHlsVariants(
        masterUrl: String,
        referer: String,
        origin: String,
        label: String,
    ): List<Video> {
        val reqHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Referer", referer)
            .set("Origin", origin)
            .set("Accept-Encoding", "identity")
            .build()

        val body = runCatching {
            fastExtractorClient.newCall(GET(masterUrl, reqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string().removePrefix("\uFEFF")
            }
        }.getOrNull() ?: return emptyList()

        if (!body.trimStart().startsWith("#EXTM3U")) return emptyList()

        val baseHttp = masterUrl.toHttpUrlOrNull()
        val lines = body.lines().map(String::trim)
        val variants = mutableListOf<Pair<Int, Video>>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val qLabel = if (height > 0) "$label - ${height}p" else "$label - HD"
            val localUrl = proxyTransform(resolved, referer, origin)
            variants += height to Video(localUrl, qLabel, localUrl)
        }
        if (variants.isNotEmpty()) {
            return variants.sortedByDescending { it.first }.map { it.second }.distinctBy { it.videoUrl }
        }
        val localUrl = proxyTransform(masterUrl, referer, origin)
        return listOf(Video(localUrl, "$label - HLS", localUrl))
    }

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
            ?.replace("\\u0026", "&")
            ?.replace("\\/", "/")
            ?: return emptyList()
        return extractProxiedHlsVariants(playlist, "$origin/", origin, "VAS")
    }

    private fun cleanServerLabel(text: String): String {
        val clean = text.trim().uppercase()
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

    private val playlistUtils by lazy { PlaylistUtils(fastExtractorClient, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(fastExtractorClient) }
    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val mixdropExtractor by lazy { MixDropExtractor(fastExtractorClient) }
    private val uqloadExtractor by lazy { UqloadExtractor(fastExtractorClient) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("Referer", "$PROTECTOR_URL/").build()
    }

    private val noRedirectClient by lazy {
        fastExtractorClient.newBuilder().followRedirects(false).build()
    }

    private fun embedHeaders(pageReferer: String): okhttp3.Headers = headers.newBuilder()
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
            resp.throwIfCloudflareChallenge()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body.string()
        }
        if (!html.isCloudflareChallengeHtml()) cachePage(url, html)
        return html
    }

    private fun cachePage(url: String, html: String) {
        if (pageCache.size > 30) pageCache.clear()
        pageCache[url] = System.currentTimeMillis() to html
    }

    override fun videoListSelector(): String = "div.content"
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        tasteEngine.attachPreferences(screen)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched segments locally (20 min TTL) and prefetches ahead for faster skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenBy { if (it.quality.startsWith("TV", ignoreCase = true)) 1 else 0 },
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
        val lower = lowercase()
        return "_cf_chl_opt" in lower ||
            "id=\"challenge-form\"" in lower ||
            "id='challenge-form'" in lower ||
            TITLE_CHALLENGE_REGEX.containsMatchIn(this)
    }

    private class CloudflareBlockedException : IOException("Cloudflare blocked SupJav 2. Open the page in WebView once, complete the challenge, then retry.")

    private data class PlayerRequest(
        val label: String,
        val id: String,
        val pageReferer: String,
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L
        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 12_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_800L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 429, 503)
        private val PAGE_NUM_REGEX = Regex("/page/(\\d+)(?:/|$)", RegexOption.IGNORE_CASE)
        private val NON_SLUG_REGEX = Regex("""[^a-z0-9-]+""")
        private val TITLE_CHALLENGE_REGEX =
            Regex("""<title>\s*(?:just a moment|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val TV_URL_PLAY_REGEX =
            Regex("""\b(?:(?:var|let|const)\s+)?urlPlay\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TV_DATA_HASH_REGEX =
            Regex("""\bdata-hash\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val TV_FILE_URL_REGEX =
            Regex("""\bfile\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val HLS4_REGEX = Regex("""["']hls4["']\s*:\s*["']([^"']+)["']""")
        private val HLS2_REGEX = Regex("""["']hls2["']\s*:\s*["']([^"']+)["']""")
        private val HLS3_REGEX = Regex("""["']hls3["']\s*:\s*["']([^"']+)["']""")
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val FST_M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
        private val VAS_STREAM_REGEX = Regex(""""streaming_url"\s*:\s*"([^"]+)"""")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"

        private val CATEGORIES = listOf(
            "Censored JAV" to "censored-jav",
            "Uncensored JAV" to "uncensored-jav",
            "Amateur" to "amateur",
            "Reducing Mosaic" to "reducing-mosaic",
            "English Subtitles" to "english-subtitles",
            "Chinese Subtitles" to "chinese-subtitles",
            "FC2 PPV" to "fc2-ppv",
            "Western" to "western",
        )

        private val TAGS = listOf(
            "4K" to "4k", "Amateur" to "amateur", "Anal" to "anal", "Big Tits" to "big-tits",
            "Blowjob" to "blowjob", "Bondage" to "bondage", "Bukkake" to "bukkake",
            "Cosplay" to "cosplay", "Creampie" to "creampie", "Cumshot" to "cumshot",
            "Documentary" to "documentary", "Facials" to "facials", "FC2PPV" to "fc2ppv",
            "Handjob" to "handjob", "Lesbian" to "lesbian", "Married Woman" to "married-woman",
            "Massage" to "massage", "Masturbation" to "masturbation", "Mature Woman" to "mature-woman",
            "Nurse" to "nurse", "Pantyhose" to "pantyhose", "POV" to "pov",
            "School Girls" to "school-girls", "Slender" to "slender", "Slut" to "slut",
            "Solowork" to "solowork", "Squirting" to "squirting", "Threesome" to "threesome",
            "Titty Fuck" to "titty-fuck", "Uncensored" to "uncensored",
        )

        private val MAKERS = listOf(
            "S1 NO.1 STYLE" to "s1-no-1-style", "Moodyz" to "moodyz", "IdeaPocket" to "ideapocket",
            "Prestige" to "prestige", "Madonna" to "madonna", "FALENO" to "faleno",
            "SOD Create" to "sod-create", "E-BODY" to "e-body", "Attackers" to "attackers",
            "Kawaii" to "kawaii", "Wanz Factory" to "wanz-factory", "PREMIUM" to "premium",
            "Fitch" to "fitch", "ROOKIE" to "rookie", "OPPAI" to "oppai",
        )

        private val CASTS = listOf(
            "Yua Mikami" to "yua-mikami", "Saika Kawakita" to "saika-kawakita",
            "Eimi Fukada" to "eimi-fukada", "Kana Momonogi" to "kana-momonogi",
            "Remu Suzumori" to "remu-suzumori", "Mirei Shinonome" to "mirei-shinonome",
            "JULIA" to "julia", "Hitomi" to "hitomi", "Aika" to "aika",
            "Tsukasa Aoi" to "tsukasa-aoi", "Momo Sakura" to "momo-sakura",
            "Ichika Matsumoto" to "ichika-matsumoto", "Yumi Kazama" to "yumi-kazama",
            "Mao Hamasaki" to "mao-hamasaki", "Karen Kaede" to "karen-kaede",
        )

        private val SORTS = arrayOf(
            Pair("Censored (Default)", ""),
            Pair("Popular", "popular"),
            Pair("Popular This Week", "week"),
            Pair("Popular This Month", "month"),
        )
    }
}
