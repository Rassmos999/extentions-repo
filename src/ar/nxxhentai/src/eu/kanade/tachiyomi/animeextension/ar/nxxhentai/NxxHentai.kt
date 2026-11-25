package eu.kanade.tachiyomi.animeextension.ar.nxxhentai

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * NxxHentai — https://nxxhentai.net/
 *
 * DooPlay / Vortex theme: series under `/anime/`, episodes under `/episodes/`,
 * multi-server via `doo_player_ajax` / `wp-json/dooplayer/v2/` extracted in parallel.
 * Prioritizes VIPServer (`player.nxxhentai.net` -> BunnyCDN multi-quality MP4) and
 * routes `playmogo` mirrors to DoodExtractor.
 */
class NxxHentai :
    DooPlay(
        "ar",
        "NxxHentai",
        "https://nxxhentai.net",
    ) {

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .set("Referer", "$baseUrl/")

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
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

    private val playlistUtils by lazy { PlaylistUtils(fastExtractorClient, headers) }
    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val mixDropExtractor by lazy { MixDropExtractor(fastExtractorClient) }
    private val m3u8Server by lazy { M3u8ServerManager(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(fastExtractorClient, headers) }

    private fun proxyTransform(m3u8Url: String, referer: String, origin: String): String = m3u8Server.processM3u8Url(
        m3u8Url = m3u8Url,
        referer = referer,
        userAgent = headers["User-Agent"],
        origin = origin,
    ) ?: m3u8Url

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_seek_cache"
            title = "تفعيل كاش التقديم والترجيع (Local Seek Cache)"
            summary = "تخزين مقاطع الفيديو مؤقتاً لمدة 20 دقيقة لتسريع التقديم والرجوع لنفس النقطة."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(fastExtractorClient) }
    private val voeExtractor by lazy { VoeExtractor(fastExtractorClient, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(fastExtractorClient) }
    private val universalExtractor by lazy { UniversalExtractor(fastExtractorClient) }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(url, headers)
    }

    private fun catalogSelector() = "article.item.tvshows div.poster, article.item.movies div.poster, " +
        "article.item div.poster, div.content article div.poster, div.items article div.poster"

    override fun popularAnimeSelector() = catalogSelector()

    override fun popularAnimeNextPageSelector() = "div.pagination span.current + a, div.pagination a.arrow_pag, " +
        "div.resppages a > i#nextpagination, div.resppages a > span.fa-chevron-left, " +
        "div.resppages a > i.fa-chevron-left, div.resppages a > span.fa-chevron-right, " +
        "div.resppages a > i.fa-caret-left, a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
            ?: element.parent()?.selectFirst("a[href]")
            ?: element
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        setUrlWithoutDomain(href)
        val img = element.selectFirst("img")
        title = img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.parent()?.selectFirst(".data h3 a, .data h3, h3")?.text().orEmpty()
        thumbnail_url = img?.getImageUrl()
    }

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodes"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = catalogSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override val fetchGenres = false

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val selectedGenres = filters.filterIsInstance<GenreGroup>()
            .firstOrNull()?.state?.filter { it.state }?.map { it.value }.orEmpty()
        if (query.isBlank() && selectedGenres.size > 1) {
            val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
            val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart().orEmpty()
            val requests = selectedGenres.take(MAX_PARALLEL_FILTERS).map { genre ->
                buildCatalogRequest(page, sort, year, genre)
            }
            return fetchAndMergePages(requests)
        }
        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encoded = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            val url = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
            return GET(url, headers)
        }

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart().orEmpty()
        val genre = filters.filterIsInstance<GenreGroup>()
            .firstOrNull()?.state?.firstOrNull { it.state }?.value.orEmpty()
        val browse = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.toUriPart().orEmpty()

        when (browse) {
            "movies" -> {
                val path = if (page <= 1) "$baseUrl/movies/" else "$baseUrl/movies/page/$page/"
                return GET(path, headers)
            }

            "episodes" -> {
                val path = if (page <= 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
                return GET(path, headers)
            }
        }

        return buildCatalogRequest(page, sort, year, genre)
    }

    private fun buildCatalogRequest(page: Int, sort: String, year: String, genre: String): Request {
        if (genre.isNotBlank() && sort.isBlank() && year.isBlank()) {
            val path = if (page <= 1) "$baseUrl/genre/$genre/" else "$baseUrl/genre/$genre/page/$page/"
            return GET(path, headers)
        }
        if (year.isNotBlank() && genre.isBlank() && sort.isBlank()) {
            val path = if (page <= 1) "$baseUrl/release/$year/" else "$baseUrl/release/$year/page/$page/"
            return GET(path, headers)
        }
        val path = if (page <= 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        val builder = path.toHttpUrl().newBuilder()
        if (sort.isNotBlank()) builder.addQueryParameter("sort", sort)
        if (year.isNotBlank()) builder.addQueryParameter("release", year)
        if (genre.isNotBlank()) builder.addQueryParameter("genre", genre)
        return GET(builder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchDoc(response.asJsoup())

    private fun parseSearchDoc(document: Document): AnimesPage {
        val elements = document.select(catalogSelector()).ifEmpty {
            document.select("div.result-item div.image a, .search-page .result-item article a")
        }
        val animes = elements.map { el ->
            if (el.tagName() == "a") searchResultFromLink(el) else popularAnimeFromElement(el)
        }.filter { it.url.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.url }

        val current = document.selectFirst("div.pagination span.current, div.pagination a.current")
            ?.text()?.toIntOrNull() ?: 1
        val hasHigher = document.select("div.pagination a").any { (it.text().toIntOrNull() ?: -1) > current }
        val hasNext = (document.selectFirst(popularAnimeNextPageSelector()) != null || hasHigher) && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    override fun searchAnimeSelector() = catalogSelector() + ", div.result-item div.image a"

    override fun searchAnimeFromElement(element: Element): SAnime = if (element.tagName() == "a") searchResultFromLink(element) else popularAnimeFromElement(element)

    private fun searchResultFromLink(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        val img = element.selectFirst("img")
        title = element.parents().select(".title a, .title, .details .title a").firstOrNull()?.text()
            ?: img?.attr("alt")
            ?: element.attr("title").ifBlank { element.text() }
        thumbnail_url = img?.getImageUrl()
    }

    private suspend fun fetchAndMergePages(requests: List<Request>): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            parseSearchDoc(resp.asJsoup())
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
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            val sheader = doc.selectFirst("div.sheader")
            val poster = sheader?.selectFirst("div.poster img")
                ?: doc.selectFirst("div.poster img")
            thumbnail_url = poster?.getImageUrl()
            title = doc.selectFirst("div.wp-content > h1, div.data h1, h1")?.text()
                ?.ifBlank { null }
                ?: poster?.attr("alt").orEmpty()

            genre = doc.select(
                "div.sgeneros a, a[href*='/genre/'], a[href*='?genre='], " +
                    "div.wp-content a[href*='/genre/']",
            ).eachText().map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString()

            description = buildString {
                val paras = doc.select("div.wp-content p, #info p")
                    .map { it.text().trim() }
                    .filter { it.length > 20 && !it.contains("Patreon", ignoreCase = true) }
                if (paras.isNotEmpty()) append(paras.first())
            }.ifBlank { null }

            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        val doc = getRealAnimeDoc(Jsoup.parse(html, url))
        val byUrl = LinkedHashMap<String, SEpisode>()

        fun addEpisode(a: Element, fallbackIndex: Int) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank() || href == doc.location()) return
            val okPath = href.contains("/episodes/") || href.contains("/movies/") ||
                a.selectFirst(".episodiotitle") != null
            if (!okPath) return
            val name = a.selectFirst(".episodiotitle")?.text()?.trim()
                ?.ifBlank { null }
                ?: a.attr("title").ifBlank { a.ownText() }.ifBlank {
                    a.parent()?.selectFirst(".episodiotitle")?.text()
                }.orEmpty().ifBlank { "الحلقة $fallbackIndex" }
            val ep = SEpisode.create().apply {
                setUrlWithoutDomain(href)
                this.name = name
                episode_number = EP_NUM.findAll(name).mapNotNull { it.groupValues[1].toFloatOrNull() }
                    .lastOrNull() ?: fallbackIndex.toFloat()
                date_upload = a.selectFirst(".episodate, .date")?.text()?.toDate()
                    ?: a.parent()?.selectFirst(".episodate, .date")?.text()?.toDate()
                    ?: 0L
            }
            byUrl.putIfAbsent(ep.url, ep)
        }

        var i = 1
        doc.select("ul.episodios > li a[href], ul.episodios li a[href]").forEach { addEpisode(it, i++) }
        i = 1
        doc.select("#episodes a[href*='/episodes/'], .se-a a[href*='/episodes/'], a[href*='/episodes/']")
            .forEach { addEpisode(it, i++) }

        if (byUrl.isNotEmpty()) {
            return byUrl.values.sortedByDescending { it.episode_number }
        }

        val seasonList = doc.select(seasonListSelector)
        if (seasonList.isNotEmpty()) {
            return seasonList.flatMap(::getSeasonEpisodes).reversed()
        }

        if (doc.selectFirst("ul#playeroptionsul, li.dooplay_player_option, video source, iframe.metaframe") != null) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = doc.selectFirst("h1")?.text() ?: episodeMovieText
                },
            )
        }

        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
            },
        )
    }

    override val episodeMovieText = "فيلم / Video"
    override val episodeSeasonPrefix = "موسم"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
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
        if (html.contains("Just a moment", ignoreCase = true) ||
            html.contains("Verifying you are human", ignoreCase = true) ||
            html.contains("cf-browser-verification", ignoreCase = true)
        ) {
            throw IOException(
                "Cloudflare blocked the episode page. Open WebView on this episode once, then retry play.",
            )
        }

        val document = Jsoup.parse(html, pageUrl)
        val videos = mutableListOf<Video>()

        // 1) Direct HTML5 sources already on page
        document.select(
            "video source[src], video#wpst-video source, #player source, " +
                ".responsive-player source, #dooplay_player_response source, source[src]",
        ).forEach { source ->
            val src = source.absUrl("src").ifBlank { source.attr("src") }.trim().replace("\\/", "/")
            if (src.startsWith("http") && isMediaUrl(src)) {
                val label = source.attr("label").ifBlank { QUALITY_IN_URL.find(src)?.value ?: "Player" }
                val tag = if (isBadProgressiveCdn(src)) "CDN fallback" else "Direct - $label"
                videos += streamFromUrl(src, tag)
            }
        }

        MP4_OR_M3U8.findAll(html).map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .distinct()
            .filter { u ->
                u.startsWith("http") &&
                    !u.contains("wp-content/themes", ignoreCase = true) &&
                    !u.contains("px.gif")
            }
            .forEach { u ->
                if (isBadProgressiveCdn(u)) {
                    videos += streamFromUrl(u, "CDN fallback")
                } else {
                    val q = if (u.contains(".m3u8", true)) "HLS" else (QUALITY_IN_URL.find(u)?.value ?: "MP4")
                    videos += streamFromUrl(u, "Page - $q")
                }
            }

        // 2) Parallel extraction for preloaded iframes + DooPlay player tabs (VIPServer first).
        val tasks = mutableListOf<Pair<Int, () -> List<Video>>>()

        document.select(
            "iframe.metaframe, iframe.rptss, .playex iframe, " +
                "#dooplay_player_response iframe, #pframe iframe, iframe[src]",
        ).mapNotNull { iframe ->
            iframe.absUrl("src").ifBlank { iframe.attr("src") }.trim().replace("&amp;", "&")
                .takeIf { isUsefulEmbed(it) }
        }.distinct().forEach { embed ->
            tasks += 1 to { runCatching { runBlocking { extractFromEmbed(embed, "Embed") } }.getOrDefault(emptyList()) }
        }

        val players = document.select(
            "ul#playeroptionsul li.dooplay_player_option, " +
                "ul#playeroptionsul li[data-post], li.dooplay_player_option",
        )
        players.forEach { player ->
            val name = player.selectFirst("span.title")?.text()?.trim()
                ?: player.ownText().trim().ifBlank { "Server ${player.attr("data-nume")}" }
            val prio = when {
                name.contains("vip", true) || player.hasClass("nxx-vip-server") -> 0
                name.contains("dood", true) || name.contains("playmogo", true) -> 2
                else -> 3
            }
            tasks += prio to {
                val embed = getPlayerUrl(player, pageUrl)
                if (embed != null) {
                    runCatching { runBlocking { extractFromEmbed(embed, name) } }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        }

        if (tasks.isNotEmpty()) {
            videos += runTasksInParallel(tasks.sortedBy { it.first }.map { it.second })
        }

        // 3) Only probe /links/ download table if primary servers returned nothing (saves 5-10s!).
        val primaryValid = videos.filterNot { isBadProgressiveCdn(it.url) }
        if (primaryValid.isEmpty()) {
            val downloads = document.select(
                "#download a[href*='/links/'], .links_table a[href*='/links/'], " +
                    "table a[href*='/links/'], a.nxx-download-ad-trigger[href], " +
                    ".box_links a[href*='/links/']",
            ).mapNotNull { a ->
                val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = a.parent()?.selectFirst(".link-server-name, .server-name, .title")?.text()
                    ?: a.selectFirst(".link-server-name")?.text()
                    ?: a.attr("title").ifBlank { "Download" }
                href to label.trim()
            }.distinctBy { it.first }.take(4)

            if (downloads.isNotEmpty()) {
                videos += runTasksInParallel(
                    downloads.map { (href, label) ->
                        {
                            runCatching {
                                runBlocking {
                                    val finalUrl = resolveRedirect(href)
                                    if (finalUrl.isBlank() || (finalUrl == href && "/links/" in href)) {
                                        extractFromLinkPage(href, label)
                                    } else {
                                        extractFromEmbed(finalUrl, label)
                                    }
                                }
                            }.getOrDefault(emptyList())
                        }
                    },
                )
            }
        }

        var distinct = videos.distinctBy { it.videoUrl ?: it.url }
        val hosters = distinct.filterNot { isBadProgressiveCdn(it.url) }
        if (hosters.isNotEmpty()) distinct = hosters

        if (distinct.isEmpty()) {
            throw IOException(
                "No playable streams (empty player or Cloudflare). " +
                    "Open WebView on this episode URL, wait for the page, then retry Start.",
            )
        }
        return if (preferences.getBoolean("pref_seek_cache", true)) {
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

    private fun runTasksInParallel(jobs: List<() -> List<Video>>): List<Video> {
        if (jobs.size == 1) return runCatching { jobs[0]() }.getOrDefault(emptyList())
        val pool = Executors.newFixedThreadPool(jobs.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            jobs.forEach { job -> futures += completion.submit { runCatching(job).getOrDefault(emptyList()) } }
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
        return collected
    }

    private fun isBadProgressiveCdn(url: String): Boolean = "sacdnssedge" in url.lowercase(Locale.ROOT)

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy<Video> { hosterScore(it) }
                .thenBy { !it.quality.contains(pref, ignoreCase = true) }
                .thenByDescending { qualityRank(it.quality) },
        )
    }

    /** Lower score = preferred (played first). VIPServer / BunnyCDN MP4 is fastest & cleanest. */
    private fun hosterScore(video: Video): Int {
        val q = (video.quality + " " + video.url).lowercase(Locale.ROOT)
        return when {
            "b-cdn.net" in q || "vipserver" in q || "player.nxxhentai" in q -> 0
            "dood" in q || "playmogo" in q || "streamhg" in q || "hgcloud" in q || "streamwish" in q -> 1
            "mixdrop" in q || "mxdrop" in q || "m3u8" in q || "hls" in q -> 2
            "streamtape" in q || "voe" in q || "uqload" in q -> 3
            "upns" in q || "rubyvidhub" in q -> 4
            "direct" in q || "page" in q -> 7
            isBadProgressiveCdn(video.url) || "cdn fallback" in q -> 9
            else -> 5
        }
    }

    private fun qualityRank(quality: String): Int = QUALITY_IN_URL.find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun isMediaUrl(url: String): Boolean = url.contains(".mp4", ignoreCase = true) ||
        url.contains(".m3u8", ignoreCase = true) ||
        url.contains("sacdnssedge", ignoreCase = true) ||
        (url.contains("/video/", ignoreCase = true) && url.contains("http"))

    private fun isUsefulEmbed(src: String): Boolean {
        if (!src.startsWith("http") && !src.startsWith("//")) return false
        val s = src.lowercase(Locale.ROOT)
        return "google" !in s && "exoclick" !in s && "animeapp.org" !in s &&
            "doubleclick" !in s && "about:blank" !in s && s.length > 12
    }

    private fun getPlayerUrl(player: Element, pageUrl: String): String? {
        val post = player.attr("data-post").ifBlank { return null }
        val nume = player.attr("data-nume").ifBlank { return null }
        val type = player.attr("data-type").ifBlank { "tv" }
        val safeRef = safeReferer(pageUrl)

        // Prefer lightweight REST v2 GET first, fall back to admin-ajax POST.
        val restRaw = runCatching {
            fastExtractorClient.newCall(
                GET(
                    "$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume",
                    headers.newBuilder().set("Referer", safeRef).set("Accept", "application/json,*/*").build(),
                ),
            ).execute().use { if (it.isSuccessful) it.body.string() else "" }
        }.getOrDefault("")
        parseEmbedUrlFromResponse(restRaw)?.let { return it }

        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()

        val postHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .set("Origin", baseUrl)
            .set("Referer", safeRef)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val raw = runCatching {
            fastExtractorClient.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", postHeaders, body))
                .execute().use { it.body.string() }
        }.getOrNull().orEmpty()
        return parseEmbedUrlFromResponse(raw)
    }

    private fun parseEmbedUrlFromResponse(raw: String): String? {
        if (raw.isBlank() || raw.contains("Just a moment", ignoreCase = true)) return null
        val fromJson = EMBED_URL_JSON.find(raw)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("\\\"", "\"")
            ?.trim()
            .orEmpty()
        if (fromJson.startsWith("//")) return "https:$fromJson"
        if (fromJson.startsWith("http")) return fromJson

        val iframe = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.replace("&amp;", "&")
        return when {
            iframe == null -> null
            iframe.startsWith("//") -> "https:$iframe"
            iframe.startsWith("http") -> iframe
            else -> null
        }
    }

    private fun resolveRedirect(url: String, customReferer: String? = null): String {
        val referer = customReferer ?: if (url.contains("player.nxxhentai.net")) {
            url.substringBeforeLast("/")
        } else {
            "$baseUrl/"
        }
        val req = GET(url, headers.newBuilder().set("Referer", referer).build())
        return runCatching {
            noRedirectClient.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                if (!loc.isNullOrBlank()) return@use loc
                val final = resp.request.url.toString()
                if (final != url && final.startsWith("http") && "/links/" !in final) return@use final
                val body = resp.body.string()
                val doc = Jsoup.parse(body, url)
                doc.selectFirst("a[href^=http], a.button, .download-link a")?.absUrl("href")
                    ?.takeIf { it.startsWith("http") && "/links/" !in it }
                    ?: Regex("""(?:location|window\.location|href)\s*=\s*['"](https?://[^'"]+)['"]""")
                        .find(body)?.groupValues?.get(1)
                    ?: MP4_OR_M3U8.find(body)?.value
                    ?: final
            }
        }.getOrDefault(url)
    }

    private suspend fun extractFromLinkPage(url: String, label: String): List<Video> {
        val resolved = resolveRedirect(url)
        if (resolved != url && isUsefulEmbed(resolved)) return extractFromEmbed(resolved, label)
        val doc = runCatching {
            fastExtractorClient.newCall(GET(url, headers.newBuilder().set("Referer", "$baseUrl/").build()))
                .execute().asJsoup()
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Video>()
        doc.select("iframe[src], a[href*='http']").forEach { el ->
            val u = if (el.hasAttr("src")) el.absUrl("src") else el.absUrl("href")
            if (isUsefulEmbed(u) && "/links/" !in u) out += extractFromEmbed(u, label)
        }
        MP4_OR_M3U8.findAll(doc.html()).map { it.value }.distinct().forEach { out += streamFromUrl(it, label) }
        return out
    }

    private suspend fun extractFromEmbed(url: String, label: String): List<Video> {
        var clean = url.trim().replace("&amp;", "&").replace("\\/", "/")
        if (clean.startsWith("//")) clean = "https:$clean"
        if (!clean.startsWith("http")) return emptyList()
        val lower = clean.lowercase(Locale.ROOT)

        return when {
            lower.contains("dood") || lower.contains("d0o0d") || lower.contains("ds2play") ||
                lower.contains("ds2video") || lower.contains("doodstream") || lower.contains("playmogo") ->
                doodExtractor.videoFromUrl(clean, prefix = "$label - ")?.let(::listOf).orEmpty()

            lower.contains("mixdrop") || lower.contains("mxdrop") ->
                mixDropExtractor.videoFromUrl(clean, lang = label)

            STREAMWISH.any { it in lower } ->
                streamWishExtractor.videosFromUrl(clean, { q -> "$label - $q" }, ::proxyTransform)

            lower.contains("streamtape") || lower.contains("streamta.pe") ->
                streamTapeExtractor.videoFromUrl(clean, quality = label)?.let(::listOf).orEmpty()

            lower.contains("voe") || lower.contains("voe-unblock") ->
                voeExtractor.videosFromUrl(clean)

            lower.contains("uqload") ->
                uqloadExtractor.videosFromUrl(clean, "$label -")

            lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains("sacdnssedge") ->
                streamFromUrl(clean, label)

            lower.contains("upns") ->
                extractUpns(clean, label)

            lower.contains("player.nxxhentai.net/player/") ->
                extractVipNxxPlayer(clean, label)

            lower.contains("rubyvidhub") || lower.contains("vidhub") ->
                extractFromPlayerPage(clean, label)

            else -> {
                val uni = runCatching {
                    universalExtractor.videosFromUrl(clean, headers, prefix = "$label - ")
                }.getOrDefault(emptyList())
                if (uni.isNotEmpty()) return uni
                extractFromPlayerPage(clean, label)
            }
        }
    }

    /**
     * Resolves `player.nxxhentai.net/download/{key}` -> `/file` 302 redirects to signed BunnyCDN MP4s in parallel.
     */
    private suspend fun extractVipNxxPlayer(playerUrl: String, label: String): List<Video> {
        val dlUrl = playerUrl.replace("/player/", "/download/")
        val doc = runCatching {
            fastExtractorClient.newCall(
                GET(dlUrl, headers.newBuilder().set("Referer", playerUrl).build()),
            ).execute().asJsoup()
        }.getOrNull() ?: return extractFromPlayerPage(playerUrl, label)

        val fileLinks = doc.select("a[href*='/file']").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith("http") || "/file" !in href) return@mapNotNull null
            val q = QUALITY_IN_URL.find(href)?.value
                ?: a.text().trim().ifBlank { "MP4" }
            href to q
        }.distinctBy { it.first }

        if (fileLinks.isEmpty()) return extractFromPlayerPage(playerUrl, label)

        val pool = Executors.newFixedThreadPool(fileLinks.size.coerceAtMost(4))
        return try {
            val futures = fileLinks.map { (href, q) ->
                pool.submit<List<Video>> {
                    val resolved = resolveRedirect(href, customReferer = dlUrl)
                    if (resolved.startsWith("http") && isMediaUrl(resolved)) {
                        streamFromUrl(resolved, "$label - $q")
                    } else {
                        emptyList()
                    }
                }
            }
            futures.flatMap { runCatching { it.get(7, TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
                .distinctBy { it.videoUrl ?: it.url }
        } finally {
            pool.shutdownNow()
        }
    }

    private suspend fun extractUpns(url: String, label: String): List<Video> {
        val id = url.substringAfterLast('#').substringBefore('?').trim()
        if (id.isBlank() || id.startsWith("http")) return extractFromPlayerPage(url, label)
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return extractFromPlayerPage(url, label)
        val scheme = runCatching { url.toHttpUrl().scheme }.getOrDefault("https")
        val api = "$scheme://$host/api/v1/video?id=$id"
        val body = runCatching {
            fastExtractorClient.newCall(
                GET(
                    api,
                    headers.newBuilder()
                        .set("Referer", "$scheme://$host/")
                        .set("Accept", "application/json,*/*")
                        .set("Origin", "$scheme://$host")
                        .build(),
                ),
            ).execute().use { it.body.string() }
        }.getOrNull().orEmpty()

        val found = mutableListOf<Video>()
        if (body.contains("http") && ("m3u8" in body || "mp4" in body)) {
            MP4_OR_M3U8.findAll(body).map { it.value.replace("\\/", "/") }.distinct().forEach {
                found += streamFromUrl(it, "$label Upns")
            }
        }
        return found.distinctBy { it.url }.ifEmpty { extractFromPlayerPage(url, label) }
    }

    private suspend fun extractFromPlayerPage(url: String, label: String): List<Video> {
        val pageReferer = if (url.contains("player.nxxhentai.net")) url else "$baseUrl/"
        val doc = runCatching {
            fastExtractorClient.newCall(
                GET(url, headers.newBuilder().set("Referer", pageReferer).build()),
            ).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val nested = mutableListOf<Video>()
        val body = doc.html()

        doc.select("source[src], video[src], video source").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (src.startsWith("http") && isMediaUrl(src)) nested += streamFromUrl(src, label)
        }
        MP4_OR_M3U8.findAll(body).map { it.value.replace("\\/", "/") }.distinct().forEach {
            nested += streamFromUrl(it, "$label CDN")
        }
        Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""")
            .findAll(body).map { it.groupValues[1] }.distinct().forEach {
                nested += streamFromUrl(it, label)
            }
        doc.select("iframe[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (isUsefulEmbed(src) && src != url && "upns" !in src.lowercase(Locale.ROOT)) {
                nested += extractFromEmbed(src, label)
            }
        }
        return nested.distinctBy { it.url }
    }

    private fun streamFromUrl(url: String, quality: String): List<Video> {
        val clean = url.replace("\\/", "/").replace("&amp;", "&").trim()
        if (!clean.startsWith("http")) return emptyList()
        val vHeaders = videoHeaders(clean)
        return when {
            clean.contains(".m3u8", ignoreCase = true) -> {
                runCatching {
                    playlistUtils.extractFromHls(
                        clean,
                        referer = if (clean.contains("b-cdn.net") || clean.contains("player.nxxhentai.net")) {
                            "https://player.nxxhentai.net/"
                        } else {
                            "$baseUrl/"
                        },
                        videoNameGen = { q -> "$quality - $q" },
                    )
                }.getOrElse { listOf(Video(clean, quality, clean, headers = vHeaders)) }
            }

            else -> listOf(Video(clean, quality, clean, headers = vHeaders))
        }
    }

    private fun videoHeaders(url: String = ""): okhttp3.Headers {
        val referer = when {
            url.contains("b-cdn.net") || url.contains("player.nxxhentai.net") -> "https://player.nxxhentai.net/"
            else -> "$baseUrl/"
        }
        val builder = headers.newBuilder()
            .set(
                "User-Agent",
                headers["User-Agent"]
                    ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
            .set("Referer", referer)
            .set("Accept", "*/*")
        if (!url.contains("b-cdn.net")) {
            builder.set("Origin", baseUrl)
        }
        return builder.build()
    }

    private fun safeReferer(url: String): String = url.toHttpUrlOrNull()?.toString()
        ?.takeIf { it.all { ch -> ch.code in 0x20..0x7E } }
        ?: "$baseUrl/"

    private fun toAbsoluteUrl(url: String): String {
        val raw = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl/${url.trimStart('/')}"
        }
        val normalized = if ('?' !in raw && !raw.endsWith("/")) "$raw/" else raw
        return normalized.toHttpUrlOrNull()?.toString() ?: normalized
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
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يمكنك اختيار أكثر من تصنيف في نفس الوقت"),
        BrowseFilter(),
        SortFilter(),
        YearFilter(),
        GenreGroup(),
    )

    private class BrowseFilter :
        UriPartFilter(
            "تصفح",
            arrayOf(
                Pair("قائمة الهنتاي /anime/", ""),
                Pair("أحدث الحلقات /episodes/", "episodes"),
                Pair("أفلام /movies/", "movies"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "ترتيب (على /anime/)",
            arrayOf(
                Pair("آخر ما تم نشره", ""),
                Pair("أحدث الإصدارات", "release"),
                Pair("A → Z", "az"),
            ),
        )

    private class YearFilter :
        UriPartFilter(
            "سنة الإصدار",
            arrayOf(Pair("الكل", "")) +
                (2026 downTo 2000).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
        )

    private class GenreCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class GenreGroup :
        AnimeFilter.Group<GenreCheckBox>(
            "التصنيفات (اختيار متعدد)",
            GENRES.map { (label, slug) -> GenreCheckBox(label, slug) },
        )

    companion object {
        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 10_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_800L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val EP_NUM = Regex("""(\d{1,4})""")
        private val EMBED_URL_JSON = Regex(""""embed_url"\s*:\s*"([^"]*)"""")
        private val MP4_OR_M3U8 = Regex(
            """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE,
        )
        private val QUALITY_IN_URL = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")

        private val STREAMWISH = listOf(
            "streamwish", "ahvsh", "wishfast", "sfastwish", "flaswish", "awish",
            "dwish", "playerwish", "streamhg", "hgcloud", "hglink", "vidhide",
            "streamhide", "filelions", "vidmoly", "smoothpre",
        )

        private val GENRES = listOf(
            "بدون حجب" to "بدون-حجب",
            "محارم" to "محارم",
            "حريم" to "حريم",
            "ميلف" to "ميلف",
            "معلمة" to "معلمة",
            "معلم" to "معلم",
            "طالبة مدرسة" to "طالبة-مدرسة",
            "اثداء كبيرة" to "اثداء-كبيرة",
            "اغتصاب" to "اغتصاب",
            "اغتصاب جماعي" to "اغتصاب-جماعي",
            "تحكم بالعقل" to "تحكم-بالعقل",
            "تحطم العقل" to "تحطم-العقل",
            "تنويم المغناطيسي" to "تنويم-المغناطيسي",
            "تصوير" to "تصوير",
            "تعابير منحرفة" to "تعابير-منحرفة",
            "جنس فموي" to "جنس-فموي",
            "جنس ثلاثي" to "جنس-ثلاثي",
            "جنس جماعي" to "جنس-جماعي",
            "جنس بالثدي" to "جنس-بالثدي",
            "جنس بالعام" to "جنس-بالعام",
            "خيانة" to "خيانة",
            "دياثة" to "دياثة",
            "يوري" to "يوري",
            "ياوي" to "ياوي",
            "فوتا" to "فوتا",
            "لولي" to "لولي",
            "شوتا" to "شوتا",
            "حمل" to "حمل",
            "عبودية" to "عبودية",
            "كوميدي" to "كوميدي",
            "رومانسي" to "رومانسي",
            "دراما" to "دراما",
            "فانتازيا" to "فانتازيا",
            "فانيلا" to "فانيلا",
            "هنتاي" to "هنتاي",
            "وحوش" to "وحوش",
            "مجسات" to "مجسات",
            "خادمات" to "خادمات",
            "ممرضة" to "ممرضة",
            "كوسبلاي" to "كوسبلاي",
            "فتاة قطة" to "فتاة-قطة",
            "إبتزاز" to "إبتزاز",
            "إذلال" to "إذلال",
            "إلف" to "إلف",
            "إيسيكاي" to "إيسيكاي-isekai",
            "اخ و أخت" to "اخ-و-أخت",
            "استمناء" to "استمناء",
            "التحكم بالأشخاص" to "التحكم-بالأشخاص",
            "العاب جنسية" to "العاب-جنسية",
            "الفتاة السحرية" to "الفتاة-السحرية",
            "بشرة سوداء" to "بشرة-سوداء",
            "تبادل الزوجات" to "تبادل-الزوجات",
            "تحرش في المواصلات" to "تحرش-في-المواصلات",
            "تسوندري" to "تسوندري",
            "خيال" to "خيال",
            "ربات البيوت" to "ربات-البيوت",
            "رعب" to "رعب",
            "رياضة" to "رياضة",
            "سيصدر قريباً" to "سيصدر-قريباً",
            "شريحة من الحياة" to "شريحة-من-الحياة",
            "شياطين" to "شياطين",
            "عاهرات" to "عاهرات",
            "عذرية" to "عذرية",
            "قوة خارقة" to "قوة-خارقة",
            "ملابس داخلية" to "ملابس-داخلية",
            "ملابس سباحة" to "ملابس-سباحة",
            "موظفات المكاتب" to "موظفات-المكاتب",
            "وغد قبيح" to "وغد-قبيح",
            "عنف و رطب حبال" to "عنف-و-رطب-حبال",
        )
    }
}
