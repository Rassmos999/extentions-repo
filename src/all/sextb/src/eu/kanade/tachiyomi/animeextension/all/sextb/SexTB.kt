package eu.kanade.tachiyomi.animeextension.all.sextb

import android.util.Base64
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
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
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SexTB : ParsedAnimeHttpSource() {

    override val name = "SexTB"

    override val baseUrl = "https://sextb.net"

    override val lang = "all"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    /*
     * Keep the app-provided Android User-Agent and cookie jar. Forcing a
     * desktop UA here makes Cloudflare clearance cookies incompatible with
     * the WebView session used by Aniyomi's NetworkHelper.
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    /**
     * Tight-timeout client for third-party video host extractors so a slow or dead
     * mirror never blocks playback startup for 30 seconds.
     */
    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
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

    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val mixDropExtractor by lazy { MixDropExtractor(fastExtractorClient) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(fastExtractorClient) }
    private val m3u8Server by lazy { M3u8ServerManager(streamProxyClient) }
    private val resolvedCatalogPaths = ConcurrentHashMap(CURRENT_CATALOG_PATHS)
    private val videoPageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = catalogRequest(
        page = page,
        categoryPath = DEFAULT_CATEGORY,
        sort = "viewed",
    )

    // Every listing page opens with an "ad-card" tray-item linking to /advertise.
    override fun popularAnimeSelector(): String = "div.tray-item:not(.ad-card):has(a[href]):not(:has(a[href*=\"/advertise\"]))"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]") ?: element
        setUrlWithoutDomain(link.attr("href"))

        val image: Element? = element.selectFirst("img")
        title = element.selectFirst("div.tray-item-title")?.text()
            ?.ifBlank { image?.attr("alt") }
            ?: image?.attr("alt").orEmpty()

        if (image != null) {
            thumbnail_url = normalizeUrl(
                image.attr("data-src")
                    .ifBlank { image.attr("src") }
                    .ifBlank { image.absUrl("data-src") }
                    .ifBlank { image.absUrl("src") },
            )
        }

        if (title.isBlank()) {
            title = link.attr("title").ifBlank { element.text() }
        }

        val code = element.selectFirst("div.tray-item-code")?.text()?.trim()
        val duration = element.selectFirst("div.tray-item-runtime")?.text()?.trim()
        if (!code.isNullOrBlank() && !title.contains(code, ignoreCase = true)) {
            title = "[$code] $title"
        }
        if (!duration.isNullOrBlank()) {
            title = "$title [$duration]"
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:has(a.current) + li a, ul.pagination li.active + li a, ul.pagination a[rel=next], ul.pagination li.next a, a[rel=next]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        return super.popularAnimeParse(response)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = catalogRequest(
        page = page,
        categoryPath = DEFAULT_CATEGORY,
        sort = "desc",
    )

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        return super.latestUpdatesParse(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val cleanQuery = query.replace(BRACKET_TAG_REGEX, "").trim()
        if (cleanQuery.isNotBlank()) {
            val req = searchAnimeRequest(page, cleanQuery, filters)
            return@withContext client.newCall(req).execute().use(::searchAnimeParse)
        }

        val requests = buildFilterRequests(page, filters)
        if (requests.size <= 1) {
            val req = requests.firstOrNull() ?: searchAnimeRequest(page, "", filters)
            return@withContext client.newCall(req).execute().use(::searchAnimeParse)
        }

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(BRACKET_TAG_REGEX, "").trim()
        if (cleanQuery.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("search")
                .addPathSegment(cleanQuery)
                .apply {
                    if (page > 1) addPathSegment("pg-${page.coerceAtLeast(1)}")
                }
                .build()
            return GET(url, headers)
        }
        return buildFilterRequests(page, filters).first()
    }

    private fun buildFilterRequests(page: Int, filters: AnimeFilterList): List<Request> {
        val quality = filters.filterIsInstance<QualityFilter>().firstOrNull()?.selected ?: "all"
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.selected ?: "all"
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: "desc"

        val selectedGenres = filters.filterIsInstance<GenreGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.slug }
            ?.take(MAX_MULTI_SELECT)
            .orEmpty()
        val genreParam = if (selectedGenres.isEmpty()) "all" else selectedGenres.joinToString(",")

        val selectedStudios = filters.filterIsInstance<StudioGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.slug }
            ?.take(MAX_MULTI_SELECT)
            .orEmpty()
        val studioParam = if (selectedStudios.isEmpty()) "all" else selectedStudios.joinToString(",")

        val checkedActresses = filters.filterIsInstance<ActressGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.path }
            .orEmpty()
        val typedActresses = filters.filterIsInstance<ActressTextFilter>().firstOrNull()
            ?.state
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT).replace(NON_SLUG_REGEX, "-").trim('-') }
            ?.filter(String::isNotBlank)
            ?.map { "/actress/$it" }
            .orEmpty()
        val actressPaths = (checkedActresses + typedActresses).distinct().take(MAX_PARALLEL_FILTERS)

        val checkedCategories = filters.filterIsInstance<CategoryGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.path }
            ?.distinct()
            ?.take(MAX_PARALLEL_FILTERS)
            .orEmpty()

        val targetPaths = when {
            actressPaths.isNotEmpty() -> actressPaths
            checkedCategories.isNotEmpty() -> checkedCategories
            else -> listOf(DEFAULT_CATEGORY)
        }

        return targetPaths.map { path ->
            catalogRequest(
                page = page,
                categoryPath = path,
                genre = genreParam,
                studio = studioParam,
                quality = quality,
                year = year,
                sort = sort,
            )
        }
    }

    /**
     * Merges results from multiple selected actresses or categories:
     * videos matching MORE THAN ONE selected filter (e.g., co-starring videos) rank first,
     * followed by round-robin interleaved results from every selected filter.
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

    private fun catalogRequest(
        page: Int,
        categoryPath: String,
        genre: String = "all",
        studio: String = "all",
        quality: String = "all",
        year: String = "all",
        sort: String = "desc",
    ): Request {
        val resolvedPath = resolvedCatalogPaths[categoryPath] ?: categoryPath
        val pagedPath = if (page > 1) "$resolvedPath/pg-${page.coerceAtLeast(1)}" else resolvedPath
        val hasExtraFilter = genre != "all" || studio != "all" || quality != "all" || year != "all"
        val url = "$baseUrl$pagedPath".toHttpUrl().newBuilder()
            .apply {
                if (hasExtraFilter) {
                    addQueryParameter("genre", genre)
                    addQueryParameter("studio", studio)
                    addQueryParameter("quality", quality)
                    addQueryParameter("year", year)
                    addQueryParameter("sort", sort)
                } else if (sort != "desc") {
                    addQueryParameter("sort", sort)
                }
            }
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        return super.searchAnimeParse(response)
    }

    // =============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        QualityFilter(),
        YearFilter(),
        CategoryGroup(CATEGORIES.map { CategoryCheckBox(it.first, it.second) }),
        GenreGroup(GENRES.map { GenreCheckBox(it.first, it.second) }),
        StudioGroup(STUDIOS.map { StudioCheckBox(it.first, it.second) }),
        AnimeFilter.Separator(),
        ActressGroup(ACTRESSES.map { ActressCheckBox(it.first, it.second) }),
        ActressTextFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val entries: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, entries.map { it.first }.toTypedArray()) {
        val selected get() = entries[state].second
    }

    class QualityFilter : UriPartFilter("Quality", QUALITIES)
    class YearFilter : UriPartFilter("Year", YEARS)
    class SortFilter : UriPartFilter("Sort By", SORTS)
    class ActressTextFilter : AnimeFilter.Text("Actress Names (comma-separated)")

    class CategoryCheckBox(name: String, val path: String) : AnimeFilter.CheckBox(name)
    class CategoryGroup(categories: List<CategoryCheckBox>) : AnimeFilter.Group<CategoryCheckBox>("Categories (multi-select)", categories)

    class GenreCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    class GenreGroup(genres: List<GenreCheckBox>) : AnimeFilter.Group<GenreCheckBox>("Genres (up to 5)", genres)

    class StudioCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    class StudioGroup(studios: List<StudioCheckBox>) : AnimeFilter.Group<StudioCheckBox>("Studios (up to 5)", studios)

    class ActressCheckBox(name: String, val path: String) : AnimeFilter.CheckBox(name)
    class ActressGroup(actresses: List<ActressCheckBox>) : AnimeFilter.Group<ActressCheckBox>("Actresses (multi-select)", actresses)

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val request = animeDetailsRequest(anime)
        val urlKey = request.url.toString()
        val html = fetchAndCacheVideoPage(request, urlKey)
        animeDetailsParse(Jsoup.parse(html, urlKey))
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        document.throwIfCloudflareChallenge()

        title = document.selectFirst("h1.film-info-title, h1")?.text().orEmpty()
        val thumbnailElement: Element? = document.selectFirst("#poster img, div.col-5 img, img[itemprop=image]")
        if (thumbnailElement != null) {
            thumbnail_url = normalizeUrl(
                thumbnailElement.attr("data-src").ifBlank { thumbnailElement.attr("src") },
            )
        }
        author = document.metadataLinks("Director:")
        artist = document.metadataLinks("Cast(s):", "Cast:")
        genre = document.metadataLinks("Genre(s):", "Genre:")
        val descriptionElement: Element? =
            document.selectFirst(".text-desc-container, .full-text-desc, meta[name=description]")
        val synopsis = if (descriptionElement?.tagName() == "meta") {
            descriptionElement.attr("content")
        } else {
            descriptionElement?.text()
        }?.takeIf(String::isNotBlank)
        description = buildList {
            synopsis?.let(::add)
            document.metadataText("Label:")?.let { add("Label: $it") }
            document.metadataText("Studio:")?.let { add("Studio: $it") }
            document.metadataText("Quality:")?.let { add("Quality: $it") }
            document.metadataText("Release Date:")?.let { add("Release Date: $it") }
            document.metadataText("Runtimes:", "Runtime:")?.let { add("Runtime: $it") }
        }.joinToString("\n").takeIf(String::isNotBlank)
        status = SAnime.COMPLETED
    }

    private fun Document.metadataLinks(vararg labels: String): String? = select("div.description")
        .firstOrNull { element -> labels.any { label -> element.text().contains(label, ignoreCase = true) } }
        ?.select("a")
        ?.joinToString { it.text() }
        ?.takeIf(String::isNotBlank)

    private fun Document.metadataText(vararg labels: String): String? = select("div.description")
        .firstOrNull { element -> labels.any { label -> element.text().startsWith(label, ignoreCase = true) } }
        ?.text()
        ?.let { text ->
            labels.firstNotNullOfOrNull { label ->
                text.takeIf { it.startsWith(label, ignoreCase = true) }?.substring(label.length)?.trim()
            }
        }
        ?.takeIf(String::isNotBlank)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            episode_number = 1F
            url = anime.url
        },
    )

    override fun episodeListSelector(): String = "div.film-info"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        response.throwIfCloudflareChallenge()
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = response.request.url.encodedPath
            },
        )
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val request = videoListRequest(episode)
        val urlKey = request.url.toString()
        val cachedEntry = videoPageCache[urlKey]?.takeIf {
            System.currentTimeMillis() - it.first < VIDEO_CACHE_TTL_MS
        }
        val html = cachedEntry?.second ?: fetchAndCacheVideoPage(request, urlKey)
        try {
            parseVideosFromHtml(html, urlKey)
        } catch (e: IOException) {
            if (cachedEntry != null && e !is CloudflareBlockedException) {
                // Tokens in cached page may have expired; fetch fresh page once.
                videoPageCache.remove(urlKey)
                val freshHtml = fetchAndCacheVideoPage(request, urlKey)
                parseVideosFromHtml(freshHtml, urlKey)
            } else {
                throw e
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        response.throwIfCloudflareChallenge()
        val referer = response.request.url.toString()
        val html = response.body.string()
        cacheVideoPage(referer, html)
        return parseVideosFromHtml(html, referer)
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
        if (html.length > 5000 && !html.isCloudflareChallengeHtml()) {
            if (videoPageCache.size > 30) videoPageCache.clear()
            videoPageCache[urlKey] = System.currentTimeMillis() to html
        }
    }

    private fun parseVideosFromHtml(pageHtml: String, referer: String): List<Video> {
        val document = Jsoup.parse(pageHtml, referer)
        document.throwIfCloudflareChallenge()

        // Pre-sort buttons by fastest/most reliable host BEFORE sequential /ajax/player calls.
        val playerButtons = document.select("button.btn-player.episode[data-id], button.btn-player[data-id]:not(.vip)")
            .distinctBy { it.attr("data-id") }
            .sortedBy { serverPriority(normalizeServerLabel(it.text())) }
            .take(MAX_SERVERS_TO_RESOLVE)
        if (playerButtons.isEmpty()) {
            throw IOException("SexTB returned no server buttons. Refresh the video page and retry.")
        }

        val initialToken = PT_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val initialKey = PK_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val fallbackFilmId = FILM_ID_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        if (initialToken.isBlank() || initialKey.isBlank()) {
            throw IOException("SexTB player tokens are missing. Open this video in WebView once, then retry.")
        }

        var token = initialToken
        var key = initialKey
        val resolvedPlayers = mutableListOf<PlayerTarget>()

        for (button in playerButtons) {
            val episodeId = button.attr("data-id").trim()
            val filmId = button.attr("data-source").ifBlank { fallbackFilmId }.trim()
            if (episodeId.isBlank() || filmId.isBlank()) continue

            val payload = fetchPlayerPayload(episodeId, filmId, token, referer) ?: continue
            val decrypted = xorDecrypt(payload.encryptedPlayer, key)
            if (decrypted.isBlank()) continue

            token = payload.nextToken.ifBlank { token }
            key = payload.nextKey.ifBlank { key }

            val label = normalizeServerLabel(button.text())
            extractPlayerUrls(decrypted, referer).forEach { url ->
                resolvedPlayers += PlayerTarget(label, url.redirectHgLink(), referer)
            }
        }

        if (resolvedPlayers.isEmpty()) {
            throw IOException("SexTB could not resolve any server. Open the video in WebView once, then retry.")
        }

        val prioritizedPlayers = resolvedPlayers
            .distinctBy { it.url }
            .sortedBy { serverPriority(it.label) }

        val videos = extractPlayersInParallel(prioritizedPlayers)
            .distinctBy { it.videoUrl }
            .sortedWith(compareBy<Video>({ serverPriority(it.quality) }, { -extractHeight(it.quality) }))

        if (videos.isEmpty()) {
            throw IOException("SexTB servers were found, but none returned a playable stream. Retry or open the video in WebView once.")
        }
        return videos
    }

    /**
     * Runs host extractors in parallel with tight timeouts. As soon as the first playable stream
     * arrives, waits at most [EXTRA_WAIT_AFTER_FIRST_MS] for other fast mirrors and cancels slow
     * or dead hosts instead of stalling playback startup.
     */
    private fun extractPlayersInParallel(targets: List<PlayerTarget>): List<Video> {
        if (targets.size == 1) {
            return runCatching { extractVideosBlocking(targets[0], mutableSetOf(), 0) }.getOrDefault(emptyList())
        }

        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()

        try {
            for (target in targets) {
                futures += completion.submit {
                    runCatching { extractVideosBlocking(target, mutableSetOf(), 0) }.getOrDefault(emptyList())
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

    private fun extractVideosBlocking(
        target: PlayerTarget,
        visited: MutableSet<String>,
        depth: Int,
    ): List<Video> {
        if (depth > MAX_PLAYER_DEPTH || !visited.add(target.url)) return emptyList()
        val lowerUrl = target.url.lowercase(Locale.ROOT)
        val label = normalizeServerLabel(target.label, lowerUrl)
        val videoHeaders = headersFor(target.referer)

        if (".m3u8" in lowerUrl) {
            return listOf(Video(target.url, "$label - HLS", target.url, headers = videoHeaders))
        }
        if (lowerUrl.substringBefore('?').endsWith(".mp4")) {
            return listOf(Video(target.url, "$label - MP4", target.url, headers = videoHeaders))
        }

        // Dedicated extractors first.
        when {
            label == "F4" || "f4scdn" in lowerUrl || "f4jav" in lowerUrl || "f4stream" in lowerUrl || "f4splay" in lowerUrl -> {
                return runCatching { extractF4VideosBlocking(target) }.getOrDefault(emptyList())
            }

            label == "UPN" || label == "US" -> {
                val upn = runCatching { extractUpnVideosBlocking(target) }.getOrDefault(emptyList())
                if (upn.isNotEmpty()) return upn
            }

            label == "DD" -> {
                return runCatching { doodExtractor.videosFromUrl(target.url, quality = "DD") }.getOrDefault(emptyList())
            }

            label == "ST" -> {
                return runCatching { streamTapeExtractor.videosFromUrl(target.url) }.getOrDefault(emptyList())
            }

            label == "MD" -> {
                return runCatching { mixDropExtractor.videosFromUrl(target.url) }.getOrDefault(emptyList())
            }
        }

        val urlsToTry = if ("niramirus.com" in lowerUrl) {
            listOf(target.url, target.url.replaceFirst("niramirus.com", "medixiru.com"))
        } else {
            listOf(target.url)
        }

        for (candidateUrl in urlsToTry) {
            val extracted = runCatching {
                fastExtractorClient.newCall(GET(candidateUrl, videoHeaders)).execute().use { response ->
                    response.throwIfCloudflareChallenge()
                    val finalHttpUrl = response.request.url
                    val finalUrl = finalHttpUrl.toString()
                    val finalOrigin = "${finalHttpUrl.scheme}://${finalHttpUrl.host}"
                    val body = response.body.string().replace("\\/", "/").replace("\\u0026", "&")
                    val document = Jsoup.parse(body, finalUrl)
                    val scripts = document.select("script").joinToString("\n") { script ->
                        val data = script.data()
                        if ("eval(function(p,a,c" in data) autoUnpacker(data) ?: data else data
                    }

                    // Prefer hls4 (/stream/.../master.m3u8) on StreamWish/FileLions mirrors:
                    // hls2 CDNs (cdn-centaurus / dramiyos-cdn) frequently time out, while hls4
                    // responds immediately with PNG-disguised TS segments handled by m3u8Server.
                    val hls4Raw = HLS4_LINK_REGEX.find(scripts)?.groupValues?.getOrNull(1)
                    val hls4Url = hls4Raw?.let { normalizePlayerUrl(it, finalUrl) }
                    if (hls4Url != null) {
                        val proxied = extractProxiedHlsVariants(
                            playlistUrl = hls4Url,
                            referer = finalUrl,
                            origin = finalOrigin,
                            label = label,
                        )
                        if (proxied.isNotEmpty()) return@use proxied
                    }

                    val rawPlaylist = M3U8_URL_REGEX.find(scripts)?.value
                        ?: document.selectFirst("[data-hash*=m3u8]")?.attr("data-hash")
                        ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
                    val playlist = rawPlaylist?.let { normalizePlayerUrl(it, finalUrl) }
                    if (playlist != null) {
                        val direct = extractDirectHlsVariants(
                            playlistUrl = playlist,
                            referer = finalUrl,
                            origin = finalOrigin,
                            label = label,
                        )
                        if (direct.isNotEmpty()) return@use direct
                    }

                    val nested = extractPlayerUrls(body, finalUrl)
                        .firstOrNull { it != candidateUrl && it.startsWith("http") }
                        ?: return@use emptyList()
                    extractVideosBlocking(
                        PlayerTarget(normalizeServerLabel(label, nested.lowercase(Locale.ROOT)), nested, finalUrl),
                        visited,
                        depth + 1,
                    )
                }
            }.getOrDefault(emptyList())
            if (extracted.isNotEmpty()) return extracted
        }
        return emptyList()
    }

    private fun extractProxiedHlsVariants(
        playlistUrl: String,
        referer: String,
        origin: String,
        label: String,
    ): List<Video> {
        val reqHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", origin)
            .set("Accept-Encoding", "identity")
            .build()
        val body = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, reqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (!body.trimStart().startsWith("#EXTM3U")) return emptyList()

        if (!m3u8Server.isRunning()) m3u8Server.startServer()
        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val lines = body.lines().map(String::trim)
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
            val quality = if (height != null) "$label - ${height}p" else "$label - HLS"
            variants += Video(localUrl, quality, localUrl)
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

    private fun extractDirectHlsVariants(
        playlistUrl: String,
        referer: String,
        origin: String,
        label: String,
    ): List<Video> {
        val reqHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", origin)
            .set("Accept-Encoding", "identity")
            .build()
        val body = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, reqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (!body.trimStart().startsWith("#EXTM3U")) return emptyList()

        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val lines = body.lines().map(String::trim)
        val variants = mutableListOf<Video>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: normalizePlayerUrl(next, playlistUrl) ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val quality = if (height != null) "$label - ${height}p" else "$label - HLS"
            variants += Video(resolved, quality, resolved, headers = reqHeaders)
        }
        return variants.distinctBy { it.videoUrl }.ifEmpty {
            listOf(Video(playlistUrl, "$label - HLS", playlistUrl, headers = reqHeaders))
        }
    }

    private fun extractF4VideosBlocking(target: PlayerTarget): List<Video> {
        val embedResponse = fastExtractorClient.newCall(GET(target.url, headersFor(target.referer))).execute()
        val finalEmbedUrl: okhttp3.HttpUrl
        val html: String
        embedResponse.use { resp ->
            resp.throwIfCloudflareChallenge()
            finalEmbedUrl = resp.request.url
            html = resp.body.string()
        }
        val embedOrigin = "${finalEmbedUrl.scheme}://${finalEmbedUrl.host}"
        val apiPath = DATA_API_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val apiUrl = finalEmbedUrl.resolve(apiPath)?.toString() ?: return emptyList()
        val playUrl = "$apiUrl?ab=0&ad=1&r=${URLEncoder.encode("$baseUrl/", "UTF-8")}"
        val candidates = fastExtractorClient.newCall(GET(playUrl, headersFor(finalEmbedUrl.toString()))).execute().use { resp ->
            resp.throwIfCloudflareChallenge()
            if (!resp.isSuccessful) return emptyList()
            val jsonObj = JSONObject(resp.body.string())
            // Prefer the embed host's proxy /v/... URL over external `src` CDN which frequently times out.
            buildList {
                val relUrl = jsonObj.optString("url").trim()
                if (relUrl.isNotBlank()) {
                    finalEmbedUrl.resolve(relUrl)?.toString()?.let(::add)
                }
                val rawSrc = jsonObj.optString("src").trim()
                if (rawSrc.isNotBlank()) add(rawSrc)
            }
        }
        for (candidate in candidates) {
            val proxied = extractProxiedHlsVariants(
                playlistUrl = candidate,
                referer = "$embedOrigin/",
                origin = embedOrigin,
                label = "F4",
            )
            if (proxied.isNotEmpty()) return proxied
        }
        return emptyList()
    }

    private fun extractUpnVideosBlocking(target: PlayerTarget): List<Video> {
        val playerUrl = target.url.toHttpUrlOrNull() ?: return emptyList()
        val videoId = playerUrl.fragment?.substringBefore('&')?.trim().orEmpty()
        if (videoId.isBlank()) return emptyList()
        val playerPageUrl = playerUrl.newBuilder().fragment(null).query(null).build().toString()
        val playerHeaders = headersFor(playerPageUrl)
        val referrerHost = target.referer.toHttpUrlOrNull()?.host.orEmpty()
        val apiUrl = playerUrl.newBuilder()
            .encodedPath("/api/v1/video")
            .query(null)
            .fragment(null)
            .addQueryParameter("id", videoId)
            .addQueryParameter("w", "1920")
            .addQueryParameter("h", "1080")
            .apply { if (referrerHost.isNotBlank()) addQueryParameter("r", referrerHost) }
            .build()
        val encrypted = fastExtractorClient.newCall(GET(apiUrl, playerHeaders)).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string().trim()
        }
        val payload = decryptUpnPayload(encrypted) ?: return emptyList()
        val data = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        val playlist = listOf("cfNative", "source", "cf")
            .map { data.optString(it).trim() }
            .firstOrNull { it.startsWith("http", true) && ".m3u8" in it.lowercase(Locale.ROOT) }
            ?: return emptyList()
        return listOf(Video(playlist, "US - HLS", playlist, headers = playerHeaders))
    }

    private fun decryptUpnPayload(encryptedHex: String): String? = runCatching {
        if (encryptedHex.length % 2 != 0 || !UPN_HEX_REGEX.matches(encryptedHex)) return null
        val encrypted = ByteArray(encryptedHex.length / 2) { index ->
            encryptedHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(UPN_AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(UPN_AES_IV.toByteArray(Charsets.UTF_8)),
        )
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    /**
     * hglink.to / hgcloud.to only serve an obfuscated JS loader that bounces to a
     * StreamWish mirror; the mirrors serve the packed player for the same /e/ id.
     */
    private fun String.redirectHgLink(): String {
        val host = toHttpUrlOrNull()?.host ?: return this
        if (HG_LINK_HOSTS.none { host.endsWith(it) }) return this
        return replaceFirst(host, "niramirus.com")
    }

    private fun normalizePlayerUrl(rawUrl: String, pageUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> pageUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()
        }
    }

    private fun fetchPlayerPayload(
        episodeId: String,
        filmId: String,
        token: String,
        referer: String,
    ): PlayerPayload? {
        val form = FormBody.Builder()
            .add("episode", episodeId)
            .add("filmId", filmId)
            .add("pt", token)
            .build()
        val ajaxHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", baseUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        return try {
            fastExtractorClient.newCall(POST("$baseUrl/ajax/player", ajaxHeaders, form)).execute().use { ajaxResponse ->
                ajaxResponse.throwIfCloudflareChallenge()
                val json = JSONObject(ajaxResponse.body.string())
                if (json.optBoolean("error", false)) return null
                PlayerPayload(
                    encryptedPlayer = json.optString("player_enc"),
                    nextToken = json.optString("next_pt"),
                    nextKey = json.optString("next_pk"),
                ).takeIf { it.encryptedPlayer.isNotBlank() }
            }
        } catch (e: CloudflareBlockedException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun xorDecrypt(encoded: String, key: String): String {
        if (encoded.isBlank() || key.isBlank()) return ""
        return runCatching {
            val encrypted = Base64.decode(encoded, Base64.DEFAULT)
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val decrypted = ByteArray(encrypted.size) { index ->
                (encrypted[index].toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
            }
            decrypted.toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun extractPlayerUrls(html: String, referer: String): List<String> {
        val normalizedHtml = html.replace("\\/", "/").replace("\\u0026", "&")
        val document = Jsoup.parse(normalizedHtml, referer)
        val elementUrls = document.select("iframe[src], source[src], video[src], a[href]")
            .mapNotNull { element ->
                val attribute = if (element.hasAttr("src")) "src" else "href"
                normalizeUrl(element.absUrl(attribute).ifBlank { element.attr(attribute) })
                    ?.takeUnless { it.startsWith("javascript:", ignoreCase = true) || it == "#" }
            }
        val scriptUrls = PLAYER_URL_REGEX.findAll(normalizedHtml)
            .mapNotNull { normalizeUrl(it.value) }
            .toList()

        return (elementUrls + scriptUrls).distinct()
    }

    private fun normalizeServerLabel(label: String, url: String = ""): String {
        val clean = label.trim().uppercase(Locale.ROOT)
        return when {
            "f4scdn" in url || "f4splay" in url || "f4jav" in url || "f4stream" in url || clean == "F4" -> "F4"

            "ryderjet" in url || "callistanise" in url || "vidhide" in url ||
                "streamhide" in url || "streamp2p" in url || "strp2p" in url -> clean.ifBlank { "FL" }

            "streamwish" in url || "strwish" in url || "embedwish" in url ||
                "faststream" in url || "filelions" in url || "hglink" in url ||
                "niramirus" in url || "medixiru" in url || clean in setOf("SW", "FL") -> clean.ifBlank { "SW" }

            "dood" in url || "ds2play" in url || "playmogo" in url || clean in setOf("DD", "DOOD") -> "DD"

            "streamtape" in url || clean in setOf("ST", "STREAMTAPE") -> "ST"

            "player.upn." in url || "upn.one" in url -> "UPN"

            "uqload" in url || clean == "UQLOAD" -> "UQLOAD"

            clean == "US" -> "US"

            "mixdrop" in url || clean in setOf("MD", "MIXDROP") -> "MD"

            clean in setOf("PP", "VIDHIDE") -> "PP"

            "voe." in url || clean == "VOE" -> "VOE"

            "sextb" in url || clean == "TB" -> "TB"

            else -> clean.ifBlank { "TB" }
        }
    }

    private fun headersFor(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .set("Accept-Encoding", "identity")
        .removeAll("Origin")
        .build()

    private fun normalizeUrl(rawUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$baseUrl$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> "$baseUrl/${clean.removePrefix("/")}"
        }
    }

    private fun Response.rememberCatalogPath() {
        val resolvedPath = request.url.encodedPath.substringBefore("/pg-").trimEnd('/')
        val stablePath = when {
            resolvedPath.startsWith("/jav-censored-") -> "/jav-censored"
            resolvedPath.startsWith("/jav-uncensored-") -> "/jav-uncensored"
            resolvedPath.startsWith("/jav-amateur-") -> "/jav-amateur"
            resolvedPath.startsWith("/jav-subtitle-") -> "/jav-subtitle"
            resolvedPath.startsWith("/genre/uncensored-leaked-") -> "/genre/uncensored-leaked"
            resolvedPath.startsWith("/genre/ltx-encode-") -> "/genre/ltx-encode"
            resolvedPath.startsWith("/genre/reducing-mosaic-") -> "/genre/reducing-mosaic"
            else -> return
        }
        resolvedCatalogPaths[stablePath] = resolvedPath
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
            titleChallengeRegex.containsMatchIn(this)
    }

    private fun extractHeight(quality: String): Int = QUALITY_HEIGHT_REGEX.find(quality)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun serverPriority(quality: String): Int = when {
        quality.contains("SW", true) || quality.contains("StreamWish", true) -> 1
        quality.contains("FL", true) || quality.contains("FileLions", true) -> 2
        quality.contains("F4", true) -> 3
        quality.contains("US", true) || quality.contains("UPN", true) -> 4
        quality.contains("PP", true) || quality.contains("VidHide", true) -> 5
        quality.contains("DD", true) || quality.contains("Dood", true) -> 6
        quality.contains("ST", true) || quality.contains("StreamTape", true) -> 7
        quality.contains("MD", true) || quality.contains("MixDrop", true) -> 8
        quality.contains("VOE", true) -> 9
        quality.contains("TB", true) || quality.contains("SexTB", true) -> 10
        else -> 11
    }

    override fun videoListSelector(): String = "button.btn-player[data-id]"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private data class PlayerPayload(
        val encryptedPlayer: String,
        val nextToken: String,
        val nextKey: String,
    )

    private data class PlayerTarget(
        val label: String,
        val url: String,
        val referer: String,
    )

    private class CloudflareBlockedException : IOException("Cloudflare blocked SexTB. Open the page in WebView once, complete the challenge, then retry.")

    companion object {
        private const val DEFAULT_CATEGORY = "/jav-censored"
        private const val MAX_PLAYER_DEPTH = 1
        private const val MAX_SERVERS_TO_RESOLVE = 5
        private const val MAX_MULTI_SELECT = 5
        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 10_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_500L
        private const val VIDEO_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L
        private const val UPN_AES_KEY = "kiemtienmua911ca"
        private const val UPN_AES_IV = "1234567890oiuytr"

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 429, 503)
        private val titleChallengeRegex =
            Regex("""<title>\s*(?:just a moment|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val PT_REGEX = Regex("""window\.__pt\s*=\s*["']([^"']+)""")
        private val PK_REGEX = Regex("""window\.__pk\s*=\s*["']([^"']+)""")
        private val FILM_ID_REGEX = Regex("""(?:var|let|const)\s+filmId\s*=\s*(\d+)""")
        private val DATA_API_REGEX = Regex("""data-api=["']([^"']+)["']""")
        private val PLAYER_URL_REGEX = Regex("""(?:https?:)?//[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        private val HLS4_LINK_REGEX = Regex(""""hls4"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val QUALITY_HEIGHT_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        private val M3U8_URL_REGEX =
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
        private val UPN_HEX_REGEX = Regex("""[0-9a-fA-F]+""")
        private val BRACKET_TAG_REGEX = Regex("""\[[^\]]+\]""")
        private val NON_SLUG_REGEX = Regex("""[^a-z0-9-]+""")
        private val HG_LINK_HOSTS = listOf("hglink.to", "hgcloud.to")

        private val CATEGORIES = arrayOf(
            "Censored" to "/jav-censored",
            "Uncensored" to "/jav-uncensored",
            "Amateur" to "/jav-amateur",
            "English Subtitle" to "/jav-subtitle",
            "Uncensored Leaked" to "/genre/uncensored-leaked",
            "LTX Decensored" to "/genre/ltx-encode",
            "Reducing Mosaic" to "/genre/reducing-mosaic",
            "New Releases" to "/new-releases",
        )

        private val CURRENT_CATALOG_PATHS = mapOf(
            "/jav-censored" to "/jav-censored-snc8057e",
            "/jav-uncensored" to "/jav-uncensored-3u2i466o",
            "/jav-amateur" to "/jav-amateur-7wcuqoax",
            "/jav-subtitle" to "/jav-subtitle-m4l5p9nr",
            "/genre/uncensored-leaked" to "/genre/uncensored-leaked-8iodxgmu",
            "/genre/ltx-encode" to "/genre/ltx-encode-s69gd8nr",
            "/genre/reducing-mosaic" to "/genre/reducing-mosaic-ihaj0oav",
        )

        // All 50 live genres from sextb.net's #myFilter picker (exact slugs).
        private val GENRES = arrayOf(
            "Creampie" to "creampie",
            "Big Tits" to "big-tits",
            "Masturbation" to "masturbation",
            "Reducing Mosaic" to "reducing-mosaic",
            "Amateur" to "amateur",
            "Married Woman" to "married-woman",
            "Mature Woman" to "mature-woman",
            "Beautiful Girl" to "beautiful-girl",
            "Blowjob" to "blowjob",
            "Slender" to "slender",
            "Featured Actress" to "featured-actress",
            "Slut" to "slut",
            "Digital Mosaic" to "digital-mosaic",
            "Threesome / Foursome" to "threesome--foursome",
            "Squirting" to "squirting",
            "Cuckold" to "cuckold",
            "Uncensored" to "jav-uncensored",
            "Drama" to "drama",
            "Nice Tits" to "nice-tits",
            "Gonzo" to "gonzo",
            "Titty Fuck" to "titty-fuck",
            "English Subtitle" to "english-subtitle",
            "Cowgirl" to "cowgirl",
            "School Girls" to "school-girls",
            "Over 4 Hours" to "over-4-hours",
            "Female College Student" to "female-college-student",
            "Handjob" to "handjob",
            "Older Sister" to "older-sister",
            "POV" to "pov",
            "Other Fetishes" to "other-fetishes",
            "Cosplay" to "cosplay",
            "Documentary" to "documentary",
            "Beautiful Butt" to "beautiful-butt",
            "Huge Butt" to "huge-butt",
            "Deep Throat" to "deep-throat",
            "Incest" to "incest",
            "Abuse" to "abuse",
            "Massage" to "massage",
            "Affair" to "affair",
            "Facials" to "facials",
            "Nasty, Hardcore" to "nasty-hardcore",
            "Sex Toys" to "sex-toys",
            "Planning" to "planning",
            "Single Work" to "single-work",
            "Anal" to "anal",
            "Kiss Kiss" to "kiss-kiss",
            "Voyeur" to "voyeur",
            "Cheating Wife" to "cheating-wife",
            "Facial" to "facial",
            "Hardcore" to "hardcore",
        )

        // All 50 live studios from sextb.net's #myFilter picker (exact slugs).
        private val STUDIOS = arrayOf(
            "FC2PPV" to "fc2ppv",
            "MADONNA" to "madonna",
            "MOODYZ" to "moodyz",
            "S1 NO.1 STYLE" to "s1-no-1-style",
            "SOD Create" to "sod-create",
            "Attackers" to "attackers",
            "Prestige" to "prestige",
            "Idea Pocket" to "idea-pocket",
            "K M Produce" to "k-m-produce",
            "Venus" to "venus",
            "Fitch" to "fitch",
            "Glory Quest" to "glory-quest",
            "Das!!" to "das-",
            "Hunter" to "hunter",
            "Tameike Goro" to "tameike-goro",
            "Center Village" to "center-village",
            "Honnaka" to "honnaka",
            "PREMIUM" to "premium",
            "Wanz Factory" to "wanz-factory",
            "DOC" to "doc",
            "Planet Plus" to "planet-plus",
            "Faleno" to "faleno",
            "OPPAI" to "oppai",
            "E-BODY" to "e-body",
            "Kawaii" to "kawaii",
            "Luxury TV" to "luxury-tv",
            "Natural High" to "natural-high",
            "Takara Eizou" to "takara-eizou",
            "Amateur TV" to "amateur-tv",
            "Deep's" to "deeps",
            "First Star" to "first-star",
            "Serebu No Tomo" to "serebu-no-tomo",
            "PRESTIGE PREMIUM" to "prestige-premium",
            "Ruby" to "ruby",
            "Nagae Style" to "nagae-style",
            "Alice JAPAN" to "alice-japan",
            "King Summit Enterprises" to "king-summit-enterprises",
            "Nampa TV" to "nampa-tv",
            "Caribbeancom" to "caribbeancom",
            "My Amateur Z" to "my-amateur-z",
            "Heyzo" to "heyzo",
            "Giga" to "giga",
            "Max A" to "max-a",
            "Crystal Eizou" to "crystal-eizou",
            "ROCKET" to "rocket",
            "IE NERGY" to "ie-nergy",
            "Bi" to "bi",
            "MAXING" to "maxing",
            "MERCURY (Mercury)" to "mercury-mercury",
            "Muku" to "muku",
        )

        private val ACTRESSES = arrayOf(
            "Ai Sayama" to "/actress/ai-sayama",
            "Yua Mikami" to "/actress/bxai0wb1b",
            "Saeko Matsushita" to "/actress/saeko-matsushita",
            "Yui Hatano" to "/actress/6273dbs6j",
            "Yumi Kazama" to "/actress/th1bdtq81",
            "Mina Kitano" to "/actress/mina-kitano",
            "Nao Jinguji" to "/actress/by7037fi",
            "Tsubasa Amami" to "/actress/tsubasa-amami",
            "Ririko Kinoshita" to "/actress/1fjvbdte7",
            "Kanako Ioka" to "/actress/kanako-ioka",
            "Aka Asuka" to "/actress/qhcvgnp21",
            "Miu Shiromine" to "/actress/miu-shiromine",
            "Julia" to "/actress/0owbisfqi",
            "Meari Tachibana" to "/actress/kn257tc95",
            "Yu Shinoda" to "/actress/yu-shinoda",
            "Hana Haruna" to "/actress/if68a06ll",
            "Tina Nanami" to "/actress/tina-nanami",
            "Ayaka Mutou" to "/actress/ayaka-mutou",
            "Yuuna Shiina" to "/actress/yuuna-shiina",
        )

        private val QUALITIES = arrayOf(
            "All" to "all",
            "HD" to "hd",
            "SD" to "sd",
        )

        private val YEARS: Array<Pair<String, String>> = buildList {
            add("All" to "all")
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            for (year in currentYear downTo 2016) add(year.toString() to year.toString())
        }.toTypedArray()

        private val SORTS = arrayOf(
            "Last Updated" to "desc",
            "New Release" to "release",
            "Most Liked" to "liked",
            "Most Favorite" to "favorite",
            "Most Viewed" to "viewed",
            "Most Viewed Day" to "viewed-day",
            "Most Viewed Week" to "viewed-week",
            "Most Viewed Month" to "viewed-month",
        )
    }
}
