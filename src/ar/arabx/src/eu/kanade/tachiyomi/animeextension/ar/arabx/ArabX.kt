package eu.kanade.tachiyomi.animeextension.ar.arabx

import androidx.preference.PreferenceScreen
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
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class ArabX :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "ArabX"

    override val baseUrl = "https://www.arabx.cam"

    override val lang = "ar"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(paginatedPath("most-popular", page), headers)

    override fun popularAnimeSelector(): String = "div.list-videos div.item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")
        setUrlWithoutDomain(link?.attr("abs:href").orEmpty())
        val rawTitle = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("strong.title")?.text()?.trim().orEmpty()
        val duration = element.selectFirst(
            "div.duration, span.duration, div.time, span.time, span.clock, time, div.timevideo1, span.label",
        )?.text()?.trim()?.ifBlank { null }
        title = if (!duration.isNullOrEmpty() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("img.thumb, img")?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination li.next a, a[rel=next], div.pagination a.next"

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup(), response.request.url.queryParameter("mode") == "async")

    private fun parseListing(document: Document, isAsync: Boolean): AnimesPage {
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = when {
            document.selectFirst(popularAnimeNextPageSelector()) != null -> true
            isAsync -> animes.isNotEmpty()
            else -> false
        } && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(paginatedPath("latest-updates", page), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val rawTerm = query.trim()
        if (rawTerm.isEmpty()) {
            val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
            val paths = collectFilterPaths(filters, page)
            if (paths.size > 1) {
                val requests = paths.take(MAX_PARALLEL_FILTERS).map { path ->
                    val url = if (sort.isNotEmpty()) {
                        path.toHttpUrl().newBuilder().addQueryParameter("sort_by", sort).build().toString()
                    } else {
                        path
                    }
                    GET(url, headers)
                }
                return fetchAndMergePages(requests)
            }
        }
        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    private fun collectFilterPaths(filters: AnimeFilterList, page: Int): List<String> {
        val tags = splitMulti(filters.firstInstanceOrNull<TagFilter>()?.state)
            .map { paginatedPath("tags/${encodePathSegment(it)}", page) }
        val models = splitMulti(filters.firstInstanceOrNull<ModelFilter>()?.state)
            .map { paginatedPath("models/${encodePathSegment(it)}", page) }
        val cats = filters.firstInstanceOrNull<CategoryGroup>()
            ?.state?.filter { it.state }
            ?.map { paginatedPath("categories/${it.value}", page) }.orEmpty()
        return (tags + models + cats).distinct()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val section = filters.firstInstanceOrNull<SectionFilter>()?.selected.orEmpty()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val rawTerm = query.trim()

        if (rawTerm.isNotEmpty()) {
            val slug = encodePathSegment(rawTerm)
            if (page <= 1) {
                val url = "$baseUrl/search/$slug/".toHttpUrl().newBuilder().apply {
                    if (sort.isNotEmpty()) addQueryParameter("sort_by", sort)
                }.build()
                return GET(url, headers)
            }
            val url = "$baseUrl/search/$slug/".toHttpUrl().newBuilder()
                .addQueryParameter("mode", "async")
                .addQueryParameter("function", "get_block")
                .addQueryParameter("block_id", "list_videos_videos_list_search_result")
                .addQueryParameter("q", rawTerm)
                .addQueryParameter("from_videos", page.toString())
                .addQueryParameter("from_albums", page.toString())
                .apply {
                    if (sort.isNotEmpty()) addQueryParameter("sort_by", sort)
                }
                .build()
            return GET(url, headers)
        }

        val firstPath = collectFilterPaths(filters, page).firstOrNull()
        if (firstPath != null) {
            val url = if (sort.isNotEmpty()) {
                firstPath.toHttpUrl().newBuilder().addQueryParameter("sort_by", sort).build().toString()
            } else {
                firstPath
            }
            return GET(url, headers)
        }

        val path = when (section) {
            "top-rated" -> "top-rated"
            "latest-updates" -> "latest-updates"
            else -> "most-popular"
        }
        val base = paginatedPath(path, page)
        if (sort.isNotEmpty()) {
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("sort_by", sort)
                .build()
            return GET(url, headers)
        }
        return GET(base, headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup(), response.request.url.queryParameter("mode") == "async")

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = cleanUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            }
        description = document.selectFirst("div.block-details div.info div.item em, div.info em")
            ?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        genre = document.select(
            "div.block-details a[href*=/tags/], div.block-details a[href*=/categories/], " +
                "div.info a[href*=/tags/], a.tag, div.tags a",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        author = document.select(
            "div.block-details a[href*=/models/], a[href*=/models/]",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        thumbnail_url = fixUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))
        status = SAnime.COMPLETED
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(cleanUrl(anime.url), headers)

    override fun episodeListRequest(anime: SAnime): Request = GET(cleanUrl(anime.url), headers)

    private fun cleanUrl(url: String): String {
        val full = if (url.startsWith("http")) url else "$baseUrl$url"
        return runCatching { full.toHttpUrl().toString() }.getOrDefault(full)
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
        parseVideosFromHtml(html, fullUrl).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        cachePage(pageUrl, html)
        return parseVideosFromHtml(html, pageUrl)
    }

    private fun parseVideosFromHtml(pageHtml: String, pageUrl: String): List<Video> {
        val document = Jsoup.parse(pageHtml, pageUrl)
        val iframeSrcs = document.select("div.player iframe[src], div.embed-wrap iframe[src], iframe[src]")
            .map { it.attr("abs:src").ifBlank { it.attr("src") } }
            .filter { it.startsWith("http") && !it.contains("google") && !it.contains("exoclick") }
            .distinct()

        val videos = mutableListOf<Video>()
        if (iframeSrcs.isNotEmpty()) {
            videos += extractHostersInParallel(iframeSrcs, pageUrl)
        }
        if (videos.isEmpty()) {
            videos += extractDirectStreams(pageHtml, pageUrl, labelPrefix = "Direct")
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractHostersInParallel(iframes: List<String>, pageUrl: String): List<Video> {
        if (iframes.size == 1) return runCatching { videosFromHoster(iframes[0], pageUrl) }.getOrDefault(emptyList())
        val pool = Executors.newFixedThreadPool(iframes.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            iframes.forEach { iframe ->
                futures += completion.submit { runCatching { videosFromHoster(iframe, pageUrl) }.getOrDefault(emptyList()) }
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
        return collected
    }

    private fun videosFromHoster(embedUrl: String, pageUrl: String): List<Video> {
        val embedHeaders = headersBuilder().set("Referer", pageUrl).build()
        val (finalUrl, html) = runCatching {
            fastExtractorClient.newCall(GET(embedUrl, embedHeaders)).execute().use { resp ->
                resp.request.url.toString() to resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (html.isBlank()) return emptyList()

        val unpacked = JsUnpacker.unpackAndCombine(html).orEmpty().ifBlank {
            documentScripts(html)
                .mapNotNull { JsUnpacker.unpackAndCombine(it) }
                .firstOrNull()
                .orEmpty()
        }

        val combined = "$html\n$unpacked".replace("\\/", "/").replace("\\u0026", "&")
        val streams = extractDirectStreams(combined, finalUrl, labelPrefix = "Playeriz")
        if (streams.isNotEmpty()) return streams

        val nested = IFRAME_SRC_REGEX.findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { it.startsWith("http") && it != embedUrl }
            ?: return emptyList()
        return runCatching { videosFromHosterWithoutNesting(nested, finalUrl) }.getOrDefault(emptyList())
    }

    private fun videosFromHosterWithoutNesting(embedUrl: String, pageUrl: String): List<Video> {
        val embedHeaders = headersBuilder().set("Referer", pageUrl).build()
        val html = runCatching {
            fastExtractorClient.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() }
        }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()
        val unpacked = JsUnpacker.unpackAndCombine(html).orEmpty()
        return extractDirectStreams("$html\n$unpacked", embedUrl, labelPrefix = "Playeriz")
    }

    private fun extractDirectStreams(html: String, referer: String, labelPrefix: String): List<Video> {
        val results = mutableListOf<Video>()
        val origin = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        val streamHeaders = headersBuilder()
            .set("Referer", referer)
            .apply { if (origin != null) set("Origin", origin) }
            .set("Accept", "*/*")
            .build()

        val urls = linkedSetOf<String>()
        FILE_URL_REGEX.findAll(html).forEach { urls += it.groupValues[1] }
        STREAM_URL_REGEX.findAll(html).forEach { urls += it.groupValues[1] }

        for (url in urls) {
            when {
                url.contains(".m3u8", ignoreCase = true) -> {
                    results += extractHlsSingleFetch(url, streamHeaders, labelPrefix)
                }

                url.contains(".mp4", ignoreCase = true) -> {
                    results += Video(url, "$labelPrefix - MP4", url, streamHeaders)
                }
            }
        }
        return results
    }

    private fun extractHlsSingleFetch(playlistUrl: String, streamHeaders: Headers, labelPrefix: String): List<Video> {
        val raw = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, streamHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string().removePrefix("\uFEFF")
            }
        }.getOrNull() ?: return emptyList()

        if (!raw.trimStart().startsWith("#EXTM3U")) return emptyList()

        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val lines = raw.lines().map { it.removePrefix("\uFEFF").trim() }
        val variants = mutableListOf<Video>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val q = if (height != null) "$labelPrefix - ${height}p" else "$labelPrefix - HLS"
            variants += Video(resolved, q, resolved, streamHeaders)
        }
        return variants.distinctBy { it.videoUrl }.ifEmpty {
            listOf(Video(playlistUrl, "$labelPrefix - HLS", playlistUrl, streamHeaders))
        }
    }

    private fun documentScripts(html: String): List<String> = SCRIPT_REGEX.findAll(html).map { it.groupValues[1] }.toList()

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
        SectionFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        CategoryGroup(),
        TagFilter(),
        ModelFilter(),
    )

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs[state].second
    }

    private class SectionFilter :
        UriSelectFilter(
            "Section",
            arrayOf(
                "Most popular" to "most-popular",
                "Latest updates" to "latest-updates",
                "Top rated" to "top-rated",
            ),
        )

    private class SortFilter :
        UriSelectFilter(
            "Sort by",
            arrayOf(
                "Default (section order)" to "",
                "Most viewed (all time)" to "video_viewed",
                "Most viewed (month)" to "video_viewed_month",
                "Most viewed (week)" to "video_viewed_week",
                "Most viewed (today)" to "video_viewed_today",
                "Top rated" to "rating",
                "Longest" to "duration",
                "Most commented" to "most_commented",
                "Most favourited" to "most_favourited",
                "Newest" to "post_date",
            ),
        )

    private class CategoryCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup :
        AnimeFilter.Group<CategoryCheckBox>(
            "التصنيفات / Categories (اختيار متعدد)",
            CATEGORIES.map { (label, slug) -> CategoryCheckBox(label, slug) },
        )

    private class TagFilter : AnimeFilter.Text("Tag (افصل بفاصلة ,)")

    private class ModelFilter : AnimeFilter.Text("Model (slug, e.g. kira-queen, angela-white)")

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
                            parseListing(resp.asJsoup(), false)
                        }
                    }.getOrNull()
                }
            }
            val pages = futures.mapNotNull { runCatching { it.get(14, TimeUnit.SECONDS) }.getOrNull() }
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

    private fun paginatedPath(path: String, page: Int): String {
        val clean = path.trim('/')
        return if (page <= 1) "$baseUrl/$clean/" else "$baseUrl/$clean/$page/"
    }

    private fun encodePathSegment(raw: String): String {
        val cleaned = raw.trim().replace(Regex("""\s+"""), "-").trim('-')
        return URLEncoder.encode(cleaned, Charsets.UTF_8.name()).replace("+", "%20")
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
        private const val MAX_EXTRACTION_WAIT_MS = 12_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_500L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val FILE_URL_REGEX = Regex(
            """file\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val STREAM_URL_REGEX = Regex(
            """(https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?)""",
            RegexOption.IGNORE_CASE,
        )
        private val IFRAME_SRC_REGEX = Regex(
            """<iframe[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val SCRIPT_REGEX = Regex(
            """<script[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080", "720", "480", "360", "240", "HLS", "MP4")
        private const val PREF_QUALITY_DEFAULT = "720"

        private val CATEGORIES = listOf(
            "سكس مترجم" to "سكس-مترجم",
            "افلام سكس مترجمه" to "افلام-سكس-مترجمه",
            "سكس بزاز كبيرة" to "سكس-بزاز-كبيرة",
            "سكس محارم" to "سكس-محارم",
            "سكس امهات مترجم" to "سكس-امهات-مترجم",
            "سكس عائلي" to "سكس-عائلي",
            "سكس مراهقات" to "سكس-مراهقات",
            "سكس اخوات" to "سكس-اخوات",
            "سكس نيك الطيز" to "سكس-نيك-الطيز",
            "سكس" to "سكس",
            "سكس عربي" to "سكس-عربي",
            "سكس سحاق" to "سكس-سحاق",
            "سكس نيك سمراوات" to "سكس-نيك-سمراوات",
        )
    }
}
