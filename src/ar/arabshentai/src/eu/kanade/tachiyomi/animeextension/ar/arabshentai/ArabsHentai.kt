package eu.kanade.tachiyomi.animeextension.ar.arabshentai

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
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
import org.json.JSONObject
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
 * ArabsHentai — https://arabshentai.com/ (Anime & Movies only; excludes Manga & Games)
 *
 * DooPlay WordPress theme with Cloudflare managed protection (handled automatically
 * by the app's NetworkHelper WebView solver — keep default app User-Agent):
 * - Anime catalog: `/anime/` (`/anime/page/N/`)
 * - Latest episodes: `/episodes/` (`/episodes/page/N/`)
 * - Anime movies: `/movies/` (`/movies/page/N/`)
 * - Anime genres: `/genre/{slug}/` (`/genre/{slug}/page/N/`)
 * - Search: `/?s={query}` filtered strictly to anime/movies/episodes (manga excluded)
 * - Video players: `/wp-json/dooplayer/v2/{post}/{type}/{nume}` (fallback `admin-ajax.php`)
 *   routing EarnVids (`morencius.com` / VidHide), StreamHG (`hgcloud.to` / StreamWish),
 *   and DoodStream (`playmogo.com`) in parallel.
 */
class ArabsHentai :
    DooPlay(
        "ar",
        "ArabsHentai",
        "https://arabshentai.com",
    ) {

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(CfWebBridgeInterceptor { baseUrl })
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .set("Referer", "$baseUrl/")

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
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
    private val playlistUtils by lazy { PlaylistUtils(fastExtractorClient, headers) }
    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val vidHideExtractor by lazy { VidHideExtractor(fastExtractorClient, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(fastExtractorClient, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(fastExtractorClient) }
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

    override fun popularAnimeSelector() = "article.item.tvshows, article.item.movies, article.item.se.episodes, article.item"

    override fun popularAnimeNextPageSelector() = "div.pagination a.arrow_pag, div.pagination span.current + a, a.next.page-numbers, a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("div.poster a[href], div.data h3 a[href], a[href]") ?: element
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        setUrlWithoutDomain(href)
        val img = element.selectFirst("div.poster img, img")
        val rawTitle = element.selectFirst("div.data h3 a, div.data h3, h3.title, .title a, .title")?.text()?.trim().orEmpty()
        val imgAlt = img?.attr("alt")?.trim().orEmpty()
        title = when {
            "/episodes/" in href.lowercase(Locale.ROOT) && imgAlt.isNotBlank() -> imgAlt
            rawTitle.isNotBlank() -> rawTitle
            imgAlt.isNotBlank() -> imgAlt
            else -> link.attr("title").ifBlank { link.text() }.trim()
        }
        thumbnail_url = img?.extractThumbUrl()
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodes"

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/episodes/" else "$baseUrl/episodes/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Search ===============================
    override val fetchGenres = false

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.trim()
        val selectedGenres = filters.filterIsInstance<GenreGroup>()
            .firstOrNull()?.state?.filter { it.state }?.map { it.slug }.orEmpty()

        if (cleanQuery.isBlank() && selectedGenres.size > 1) {
            val requests = selectedGenres.take(MAX_PARALLEL_FILTERS).map { slug ->
                GET(genreUrl(slug, page), headers)
            }
            return fetchAndMergePages(requests)
        }

        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        val browse = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.toUriPart().orEmpty()
        val firstGenre = filters.filterIsInstance<GenreGroup>()
            .firstOrNull()?.state?.firstOrNull { it.state }?.slug.orEmpty()

        if (cleanQuery.isNotBlank()) {
            val builder = (if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/").toHttpUrl().newBuilder()
                .addQueryParameter("s", cleanQuery)
            return GET(builder.build(), headers)
        }

        if (firstGenre.isNotBlank()) {
            return GET(genreUrl(firstGenre, page), headers)
        }

        return when (browse) {
            "episodes" -> latestUpdatesRequest(page)
            "movies" -> GET(if (page <= 1) "$baseUrl/movies/" else "$baseUrl/movies/page/$page/", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val resultItems = doc.select("div.result-item")
        if (resultItems.isNotEmpty()) {
            val animes = resultItems.mapNotNull { item ->
                // Strictly exclude Manga and Games from search results
                if (item.selectFirst("span.wp-manga") != null) return@mapNotNull null
                val link = item.selectFirst(".thumbnail a[href], .title a[href], a[href]") ?: return@mapNotNull null
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (!isAnimeContentUrl(href)) return@mapNotNull null
                val img = item.selectFirst("img")
                val title = item.selectFirst(".details .title a, .title a, .title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: img?.attr("alt")?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = img?.extractThumbUrl()
                }
            }.distinctBy { it.url }

            return AnimesPage(animes, hasNextPage(doc))
        }
        return parseListing(doc)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun parseListing(document: Document): AnimesPage {
        val animes = document.select(popularAnimeSelector())
            .mapNotNull { el ->
                if (el.selectFirst("span.wp-manga") != null) return@mapNotNull null
                val itemType = el.selectFirst("span.item_type")?.text()?.trim().orEmpty()
                if ("المانجا" in itemType || "مانجا" in itemType) return@mapNotNull null
                val anime = popularAnimeFromElement(el)
                if (anime.url.isBlank() || anime.title.isBlank()) return@mapNotNull null
                if (!isAnimeContentUrl(anime.url)) return@mapNotNull null
                anime
            }
            .distinctBy { it.url }

        return AnimesPage(animes, hasNextPage(document) && animes.isNotEmpty())
    }

    private fun hasNextPage(document: Document): Boolean {
        val current = document.selectFirst("div.pagination span.current, div.pagination a.current")
            ?.text()?.trim()?.toIntOrNull() ?: 1
        val hasNextLink = document.selectFirst(popularAnimeNextPageSelector()) != null
        val hasHigherPage = document.select("div.pagination a").any { a ->
            (a.text().trim().toIntOrNull() ?: -1) > current
        }
        return hasNextLink || hasHigherPage
    }

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
            val pages = futures.mapNotNull { runCatching { it.get(15, TimeUnit.SECONDS) }.getOrNull() }
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
        val initialDoc = Jsoup.parse(html, fullUrl)
        val resolvedDoc = resolveSeriesDoc(initialDoc, fullUrl)
        animeDetailsParse(resolvedDoc)
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val sheader = document.selectFirst("div.sheader")
        val poster = sheader?.selectFirst("div.poster img") ?: document.selectFirst("div.poster img")

        title = document.selectFirst("div.sheader h1, div.data h1, div.wp-content > h1, h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: poster?.attr("alt")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()

        thumbnail_url = poster?.extractThumbUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.startsWith("http") }

        genre = document.select("div.sgeneros a, a[href*='/genre/']")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        description = buildString {
            val paras = document.select("div.wp-content p, #info p")
                .map { it.text().trim() }
                .filter { it.length > 10 }
            if (paras.isNotEmpty()) {
                append(paras.joinToString("\n\n"))
            } else {
                document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::append)
            }
            val year = sheader?.selectFirst("span.date, .extra span.date")?.text()?.trim()
            if (!year.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("التاريخ: ").append(year)
            }
        }.ifBlank { null }

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        val initialDoc = Jsoup.parse(html, fullUrl)
        val resolvedDoc = resolveSeriesDoc(initialDoc, fullUrl)
        parseEpisodesFromDoc(resolvedDoc, resolvedDoc.location().ifBlank { fullUrl })
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        val doc = resolveSeriesDoc(Jsoup.parse(html, url), url)
        return parseEpisodesFromDoc(doc, doc.location().ifBlank { url })
    }

    private fun resolveSeriesDoc(doc: Document, pageUrl: String): Document {
        if ("/episodes/" !in pageUrl.lowercase(Locale.ROOT)) return doc
        val parentHref = doc.selectFirst(".pag_episodes a[href*='/anime/'], .breadcrumb a[href*='/anime/'], #single a[href*='/anime/']")
            ?.absUrl("href")
            ?.takeIf { it.startsWith("http") && "/anime/" in it.lowercase(Locale.ROOT) && !it.trimEnd('/').endsWith("/anime") }
            ?: return doc
        return runCatching {
            val parentHtml = fetchPageCached(parentHref)
            Jsoup.parse(parentHtml, parentHref)
        }.getOrDefault(doc)
    }

    private fun parseEpisodesFromDoc(doc: Document, pageUrl: String): List<SEpisode> {
        val byUrl = LinkedHashMap<String, SEpisode>()
        var idx = 1

        fun addEpisodeLink(a: Element) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }.trim()
            if (href.isBlank() || "/episodes/" !in href.lowercase(Locale.ROOT)) return
            val titleEl = a.selectFirst(".episodiotitle")
            val epLabel = titleEl?.ownText()?.trim()?.ifBlank { null }
                ?: a.attr("title").trim().ifBlank { null }
                ?: a.ownText().trim().ifBlank { null }
                ?: "الحلقة $idx"
            val dateText = a.selectFirst(".episodiotitle span.date, span.date, .episodate")?.text()?.trim()
            val epNum = EP_NUM.findAll(epLabel).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull()
                ?: SLUG_EP_NUM.find(href)?.groupValues?.get(1)?.toFloatOrNull()
                ?: idx.toFloat()
            val ep = SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = epLabel
                episode_number = epNum
                date_upload = dateText?.let(::parseDate).orZero()
            }
            if (byUrl.putIfAbsent(ep.url, ep) == null) {
                idx++
            }
        }

        doc.select("ul.episodios a[href*='/episodes/'], #episodes a[href*='/episodes/'], #serie_contenido a[href*='/episodes/']")
            .forEach(::addEpisodeLink)

        if (byUrl.isNotEmpty()) {
            return byUrl.values.sortedByDescending { it.episode_number }
        }

        // Single movie or standalone episode page
        val singleTitle = doc.selectFirst("div.sheader h1, h1.entry-title, h1")?.text()?.trim()?.ifBlank { null }
            ?: episodeMovieText
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(pageUrl)
                episode_number = 1F
                name = singleTitle
            },
        )
    }

    override val episodeMovieText = "فيلم / Movie"
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
        return parseVideosFromHtml(html, url).sort()
    }

    private fun parseVideosFromHtml(html: String, pageUrl: String): List<Video> {
        if (isCloudflareChallenge(html)) {
            throw IOException("Cloudflare blocked ArabsHentai. Open this page in WebView once, then retry.")
        }

        val document = Jsoup.parse(html, pageUrl)
        val directEmbedTasks = linkedMapOf<String, String>() // url -> serverLabel

        // 1) Collect DooPlay player options to resolve + extract IN PARALLEL
        val playerOptions = document.select("#playeroptionsul li.dooplay_player_option, li.dooplay_player_option")
            .mapNotNull { opt ->
                val post = opt.attr("data-post").trim()
                val nume = opt.attr("data-nume").trim()
                if (post.isBlank() || nume.isBlank() || nume.equals("trailer", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val type = opt.attr("data-type").trim().ifBlank {
                    if ("/movies/" in pageUrl.lowercase(Locale.ROOT)) "movie" else "tv"
                }
                val label = opt.selectFirst(".title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: opt.text().trim().ifBlank { "Server $nume" }
                Triple(post, type, nume) to label
            }

        // 2) Direct iframes already present on the page (if any)
        document.select("iframe.metaframe[src], #dooplay_player_response iframe[src], .play-box-iframe iframe[src], iframe[src]")
            .forEach { iframe ->
                val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }.trim().replace("&amp;", "&")
                if (src.startsWith("http") && isUsefulEmbed(src)) {
                    directEmbedTasks.putIfAbsent(src, "Embed")
                }
            }

        val videos = mutableListOf<Video>()

        // 3) Direct HTML5 <video>/<source> links (if any)
        document.select("video source[src], video[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }.trim().replace("\\/", "/")
            if (src.startsWith("http") && (".mp4" in src.lowercase(Locale.ROOT) || ".m3u8" in src.lowercase(Locale.ROOT))) {
                videos += streamFromUrl(src, "Direct", pageUrl)
            }
        }

        // 4) Resolve DooPlay options AND extract all embeds in parallel
        val jobs = mutableListOf<() -> List<Video>>()
        playerOptions.forEach { (key, label) ->
            val (post, type, nume) = key
            jobs += {
                val embedUrl = resolveDooPlayerOption(post, type, nume, pageUrl)
                if (!embedUrl.isNullOrBlank() && isUsefulEmbed(embedUrl)) {
                    runBlocking { extractFromEmbed(embedUrl, label) }
                } else {
                    emptyList()
                }
            }
        }
        directEmbedTasks.forEach { (embedUrl, label) ->
            jobs += {
                runBlocking { extractFromEmbed(embedUrl, label) }
            }
        }
        if (jobs.isNotEmpty()) {
            videos += runExtractionJobsInParallel(jobs)
        }

        val distinct = videos.distinctBy { it.videoUrl ?: it.url }
        if (distinct.isEmpty()) {
            throw IOException("لم يتم العثور على رابط تشغيل صالح لهذه الحلقة على ArabsHentai.")
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

    private fun resolveDooPlayerOption(post: String, type: String, nume: String, referer: String): String? {
        val ajaxHeaders = headers.newBuilder()
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", safeAsciiReferer(referer))
            .build()

        // Primary: wp-json/dooplayer/v2/{post}/{type}/{nume}
        val wpJsonUrl = "$baseUrl/wp-json/dooplayer/v2/$post/$type/$nume"
        val wpJsonEmbed = runCatching {
            client.newCall(GET(wpJsonUrl, ajaxHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                extractEmbedUrlFromAjaxBody(resp.body.string())
            }
        }.getOrNull()
        if (!wpJsonEmbed.isNullOrBlank()) return wpJsonEmbed

        // Fallback: wp-admin/admin-ajax.php
        val formBody = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type)
            .build()
        return runCatching {
            client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, formBody)).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                extractEmbedUrlFromAjaxBody(resp.body.string())
            }
        }.getOrNull()
    }

    private fun extractEmbedUrlFromAjaxBody(body: String): String? {
        if (body.isBlank()) return null
        val jsonEmbed = runCatching { JSONObject(body).optString("embed_url") }.getOrNull()
            ?.takeIf(String::isNotBlank)
        val raw = (
            jsonEmbed
                ?: EMBED_URL_JSON_REGEX.find(body)?.groupValues?.getOrNull(1)
                ?: body
            )
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .trim()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        // Sometimes embed_url is an HTML <iframe src="..."> snippet
        val iframeSrc = Jsoup.parseBodyFragment(raw).selectFirst("iframe[src]")?.attr("src")?.trim()
        if (!iframeSrc.isNullOrBlank()) {
            return if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
        }
        return URL_IN_TEXT_REGEX.find(raw)?.value
    }

    private fun runExtractionJobsInParallel(jobs: List<() -> List<Video>>): List<Video> {
        val pool = Executors.newFixedThreadPool(jobs.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            jobs.forEach { job ->
                futures += completion.submit {
                    runCatching { job() }.getOrDefault(emptyList())
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_EXTRACTION_WAIT_MS)
            var softDeadline = deadline
            var doneCount = 0
            while (doneCount < futures.size) {
                val rem = (if (collected.isNotEmpty()) softDeadline else deadline) - System.nanoTime()
                if (rem <= 0L) break
                val fut = completion.poll(rem, TimeUnit.NANOSECONDS) ?: break
                doneCount++
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

    private fun proxyTransform(m3u8Url: String, referer: String, origin: String): String = m3u8Server.processM3u8Url(
        m3u8Url = m3u8Url,
        referer = referer,
        userAgent = headers["User-Agent"],
        origin = origin,
    ) ?: m3u8Url

    private suspend fun extractFromEmbed(url: String, label: String): List<Video> {
        var clean = url.trim().replace("&amp;", "&").replace("\\/", "/")
        if (clean.startsWith("//")) clean = "https:$clean"
        if (!clean.startsWith("http")) return emptyList()
        val lower = clean.lowercase(Locale.ROOT)
        val lowerLabel = label.lowercase(Locale.ROOT)

        return when {
            DOOD_HOSTS.any { it in lower } || "dood" in lowerLabel ->
                doodExtractor.videoFromUrl(clean, prefix = "$label - ")?.let(::listOf).orEmpty()

            VIDHIDE_HOSTS.any { it in lower } || "earnvids" in lowerLabel || "vidhide" in lowerLabel ->
                runCatching { vidHideExtractor.videosFromUrl(clean, { q -> "$label - $q" }, ::proxyTransform) }
                    .getOrDefault(emptyList())
                    .ifEmpty {
                        runCatching { streamWishExtractor.videosFromUrl(clean, { q -> "$label - $q" }, ::proxyTransform) }.getOrDefault(emptyList())
                    }

            STREAMWISH_HOSTS.any { it in lower } || "streamhg" in lowerLabel || "streamwish" in lowerLabel ->
                runCatching { streamWishExtractor.videosFromUrl(clean, { q -> "$label - $q" }, ::proxyTransform) }
                    .getOrDefault(emptyList())
                    .ifEmpty {
                        runCatching { vidHideExtractor.videosFromUrl(clean, { q -> "$label - $q" }, ::proxyTransform) }.getOrDefault(emptyList())
                    }

            "mixdrop" in lower || "mxdrop" in lower ->
                mixDropExtractor.videoFromUrl(clean, lang = label)

            "streamtape" in lower || "streamta.pe" in lower ->
                streamTapeExtractor.videoFromUrl(clean, quality = label)?.let(::listOf).orEmpty()

            "voe" in lower ->
                voeExtractor.videosFromUrl(clean)

            "uqload" in lower ->
                uqloadExtractor.videosFromUrl(clean, "$label -")

            ".m3u8" in lower ->
                runCatching {
                    playlistUtils.extractFromHls(clean, referer = "$baseUrl/", videoNameGen = { q -> "$label - $q" })
                }.getOrDefault(emptyList())

            ".mp4" in lower ->
                streamFromUrl(clean, label, "$baseUrl/")

            else ->
                runCatching { vidHideExtractor.videosFromUrl(clean, { q -> "$label - $q" }, ::proxyTransform) }
                    .getOrDefault(emptyList())
                    .ifEmpty {
                        runCatching { universalExtractor.videosFromUrl(clean, headers, prefix = "$label - ") }
                            .getOrDefault(emptyList())
                    }
        }
    }

    private fun streamFromUrl(url: String, quality: String, referer: String): List<Video> {
        val clean = url.trim().replace("\\/", "/").replace("&amp;", "&")
        val vHeaders = headers.newBuilder()
            .set("Referer", safeAsciiReferer(referer))
            .set("Accept", "*/*")
            .build()
        return listOf(Video(clean, quality, clean, headers = vHeaders))
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(videoSortPrefKey, videoSortPrefDefault) ?: "1080"
        return sortedWith(
            compareBy<Video> { !it.quality.contains(pref, ignoreCase = true) }
                .thenByDescending { QUALITY_IN_URL.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
                .thenBy { if (it.quality.contains("earnvids", true) || it.quality.contains("streamhg", true)) 0 else 1 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "تفعيل كاش التقديم والترجيع (Local Seek Cache)"
            summary = "تخزين مقاطع الفيديو مؤقتاً لمدة 20 دقيقة لتسريع التقديم والرجوع لنفس النقطة."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("القسم للأنمي والأفلام فقط (بدون مانجا) — يمكنك اختيار أكثر من تصنيف"),
        BrowseFilter(),
        GenreGroup(),
    )

    private class BrowseFilter :
        UriPartFilter(
            "القسم / Section",
            arrayOf(
                Pair("قائمة الأنمي /anime/", "anime"),
                Pair("أحدث الحلقات /episodes/", "episodes"),
                Pair("أفلام الأنمي /movies/", "movies"),
            ),
        )

    private class GenreCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class GenreGroup :
        AnimeFilter.Group<GenreCheckBox>(
            "تصنيفات الأنمي / Genres (اختيار متعدد)",
            LIVE_GENRES.map { (label, slug) -> GenreCheckBox(label, slug) },
        )

    // ============================== Helpers ===============================
    private fun genreUrl(slug: String, page: Int): String = "$baseUrl/genre".toHttpUrl().newBuilder()
        .addPathSegment(slug)
        .apply {
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }
        .addPathSegment("")
        .build()
        .toString()

    private fun isAnimeContentUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (NON_ANIME_PATHS.any { it in lower }) return false
        return "/anime/" in lower || "/episodes/" in lower || "/movies/" in lower
    }

    private fun isCloudflareChallenge(html: String): Boolean = html.contains("Just a moment", ignoreCase = true) ||
        html.contains("Verifying you are human", ignoreCase = true) ||
        html.contains("cf-browser-verification", ignoreCase = true) ||
        html.contains("_cf_chl_opt", ignoreCase = true)

    private fun toAbsoluteUrl(url: String): String {
        val raw = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl/${url.trimStart('/')}"
        }
        val normalized = if ('?' !in raw && !raw.endsWith("/")) "$raw/" else raw
        return normalized.toHttpUrlOrNull()?.toString() ?: normalized
    }

    private fun safeAsciiReferer(referer: String): String = referer.toHttpUrlOrNull()?.toString()
        ?.takeIf { it.all { ch -> ch.code in 0x20..0x7E } }
        ?: "$baseUrl/"

    private fun fetchPageCached(url: String): String {
        val now = System.currentTimeMillis()
        pageCache[url]?.let { (ts, body) ->
            if (now - ts < PAGE_CACHE_TTL_MS) return body
        }
        val html = client.newCall(GET(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            resp.body.string()
        }
        if (!isCloudflareChallenge(html)) {
            cachePage(url, html)
        }
        return html
    }

    private fun cachePage(url: String, html: String) {
        if (pageCache.size > 30) pageCache.clear()
        pageCache[url] = System.currentTimeMillis() to html
    }

    private fun parseDate(text: String): Long = runCatching {
        dateFormatter.parse(text)?.time ?: 0L
    }.getOrDefault(0L)

    private fun Long?.orZero(): Long = this ?: 0L

    private fun isUsefulEmbed(src: String): Boolean {
        val http = src.toHttpUrlOrNull() ?: return false
        val host = http.host.lowercase(Locale.ROOT)
        if ('.' !in host) return false
        return "google" !in host && "exoclick" !in host && "doubleclick" !in host &&
            "twinrdengine" !in host && "arabshentai.com" !in host && "cloudflare" !in host
    }

    private fun Element.extractThumbUrl(): String? {
        val src = when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("data-original") -> attr("data-original")
            else -> attr("src")
        }.ifBlank { attr("src") }.trim()
        if (src.isBlank() || src.startsWith("data:")) return null
        return when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("http://") || src.startsWith("https://") -> src
            else -> absUrl("data-src").ifBlank { absUrl("src") }
        }.takeIf { it.startsWith("http") }
    }

    companion object {
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"
        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 14_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 2_000L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val NON_ANIME_PATHS = listOf(
            "/manga/",
            "/%d8%a7%d9%84%d8%b9%d8%a7%d8%a8",
            "/العاب",
            "/account",
            "/dmca",
            "/premium-membership",
            "/user-settings",
            "/wp-login",
        )

        private val EP_NUM = Regex("""\b(\d{1,4})\b""")
        private val SLUG_EP_NUM = Regex("""(?:s\d+e|ep?[-_]?|-)(\d{1,4})/?$""", RegexOption.IGNORE_CASE)
        private val QUALITY_IN_URL = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")
        private val EMBED_URL_JSON_REGEX = Regex(""""embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s"'<>\\]+""")

        private val DOOD_HOSTS = listOf(
            "dood",
            "d0o0d",
            "ds2play",
            "playmogo",
            "d-s.io",
            "do0od",
            "myvidplay",
            "doply",
        )

        private val VIDHIDE_HOSTS = listOf(
            "morencius", "earnvids", "vidhide", "dingtezuni", "movearnpre",
            "mivalyo", "ryderjet", "dhtpre", "peytonepre", "smoothpre", "niikaplayerr",
        )

        private val STREAMWISH_HOSTS = listOf(
            "streamwish", "hgcloud", "streamhg", "hglink", "niramirus", "medixiru",
            "ahvsh", "wishfast", "sfastwish", "flaswish", "awish", "dwish",
            "playerwish", "streamhide", "filelions", "vidmoly",
        )

        // Verified live anime genres from https://arabshentai.com/تصنيفات-الأنمي/
        private val LIVE_GENRES = listOf(
            "بدون حجب (Uncensored)" to "بدون-حجب",
            "حجب (Censored)" to "حجب",
            "مترجم" to "مترجم",
            "هنتاي" to "هنتاي",
            "2D" to "2d",
            "3D" to "3d",
            "BDSM" to "bdsm",
            "NTR" to "ntr",
            "X-ray (أشعة سينية)" to "الأشعة-السينية",
            "اثداء كبيرة" to "اثداء-كبيرة",
            "اثداء صغيرة" to "اثداء-صغيرة",
            "مؤخرة كبيرة" to "مؤخرة-كبيرة",
            "قذف بالداخل" to "قذف-بالداخل",
            "جنس فموي" to "جنس-فموي",
            "جنس شرجي" to "جنس-شرجي",
            "جنس بالثدي" to "جنس-بالثدي",
            "جنس يدوي" to "جنس-يدوي",
            "جنس بالقدم" to "جنس-بالقدم",
            "جنس بالابط" to "جنس-بالابط",
            "جنس بالحلمة" to "جنس-بالحلمة",
            "جنس بالعام" to "جنس-بالعام",
            "جنس ثلاثي" to "جنس-ثلاثي",
            "جنس جماعي" to "جنس-جماعي",
            "حريم" to "حريم",
            "محارم" to "محارم",
            "أم و أبن" to "أم-و-أبن",
            "أخ وأخته" to "أخ-وأخته",
            "اوياكودون" to "اوياكودون",
            "خيانة" to "خيانة",
            "دياثة" to "دياثة",
            "تبادل زوجات" to "تبادل-زوجات",
            "ميلف (MILF)" to "ميلف",
            "ربة منزل" to "ربة-منزل",
            "طالبة مدرسة" to "طالبة-مدرسة",
            "مدرسي" to "مدرسي",
            "معلم" to "معلم",
            "معلمة" to "معلمة",
            "خادمة" to "خادمة",
            "ممرضة" to "ممرضة",
            "سيدة مكتب" to "سيدة-مكتب",
            "راهبة" to "راهبة",
            "عذرية" to "عذرية",
            "استمناء" to "استمناء",
            "إبتزاز" to "إبتزاز",
            "إذلال" to "إذلال",
            "اغتصاب" to "اغتصاب",
            "اغتصاب جماعي" to "اغتصاب-جماعي",
            "اغتصاب عكسي" to "اغتصاب-عكسي",
            "التحكم بالعقل" to "التحكم-بالعقل",
            "تحطم العقل" to "تحطم-العقل",
            "فيمدوم" to "فيمدوم",
            "رومانسي" to "رومانسي",
            "فانيلا" to "فانيلا",
            "ايتشي" to "ايتشي",
            "كوميدي" to "كوميدي",
            "دراما" to "دراما",
            "أكشن" to "أكشن",
            "مغامرات" to "مغامرات",
            "فانتازي" to "فانتازي",
            "سحر" to "سحر",
            "رعب" to "رعب",
            "تاريخي" to "تاريخي",
            "رياضي" to "رياضي",
            "ايلف" to "ايلف",
            "سوكوبوس" to "سوكوبوس",
            "مونستر" to "مونستر",
            "مجسات" to "مجسات",
            "فتاة قطة" to "فتاة-قطة",
            "فتاة ثعلب" to "فتاة-ثعلب",
            "فتاة بقرة" to "فتاة-بقرة",
            "كوسبلاي" to "كوسبلاي",
            "ملابس سباحة" to "ملابس-سباحة",
            "ملابس مغايرة" to "ملابس-مغايرة",
            "نظارات" to "نظارات",
            "تسوندري" to "تسوندري",
            "غيارو" to "غيارو",
            "تراب" to "تراب",
            "فوتا" to "فوتا",
            "يوري (Yuri)" to "يوري",
            "ياوي (Yaoi)" to "ياوي",
            "بشرة سوداء" to "بشرة-سوداء",
            "بين الأعراق" to "بين-الأعراق",
            "قضيب ضخم" to "قضيب-ضخم",
            "قضيب صغير" to "قضيب-صغير",
            "وغد قبيح" to "وغد-قبيح",
            "حمل" to "حمل",
            "تصوير" to "تصوير",
            "تعابير منحرفة" to "تعابير-منحرفة",
            "العاب جنسية" to "العاب-جنسية",
            "دعارة" to "دعارة",
            "عربدة" to "عربدة",
            "سكس" to "سكس",
            "كارتون" to "كارتون",
            "سينين" to "سينين",
            "جوسي" to "جوسي",
            "فروي" to "فروي",
            "حيوانات" to "حيوانات",
            "حصان" to "حصان",
        )
    }
}
