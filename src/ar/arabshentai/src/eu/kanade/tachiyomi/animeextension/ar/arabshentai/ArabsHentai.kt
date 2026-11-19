package eu.kanade.tachiyomi.animeextension.ar.arabshentai

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * ArabsHentai — https://arabshentai.net/
 *
 * Keeps DooPlay base class to preserve the existing sourceId for user libraries,
 * while implementing the live WP-Script / RetroTube theme:
 * - Series catalog: `/anime/` (`/anime/page/N/`) → `/category/{slug}/`
 * - Latest episodes: `/` (`/page/N/`) with `?filter=latest|popular|most-viewed|longest`
 * - Tags & Years: `/tag/{slug}/` (`/tag/{slug}/page/N/`)
 * - Video streams: Dropbox direct MP4 (`raw=1`) + non-dead direct/embed fallbacks
 *   (skipping dead `x-tez.com` URLs).
 */
class ArabsHentai :
    DooPlay(
        "ar",
        "ArabsHentai",
        "https://arabshentai.net",
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

    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val mixDropExtractor by lazy { MixDropExtractor(fastExtractorClient) }
    private val streamWishExtractor by lazy { StreamWishExtractor(fastExtractorClient, headers) }
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

    override fun popularAnimeSelector() = "article.thumb-block, article.post, div.thumb-block"

    override fun popularAnimeNextPageSelector() = "div.pagination a.next, a.next.page-numbers, a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]") ?: element
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        setUrlWithoutDomain(href)
        val img = element.selectFirst("img")
        title = link.attr("title").ifBlank {
            element.selectFirst(".cat-title, .entry-header span, .entry-title, h2, h3")?.text().orEmpty()
        }.ifBlank {
            img?.attr("alt").orEmpty()
        }.trim()
        thumbnail_url = img?.extractThumbUrl()
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Latest ===============================
    override val latestUpdatesPath = ""

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Search ===============================
    override val fetchGenres = false

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.trim()
        val selectedTags = filters.filterIsInstance<TagGroup>()
            .firstOrNull()?.state?.filter { it.state }?.map { it.value }.orEmpty()
        val customTags = filters.filterIsInstance<TagTextFilter>()
            .firstOrNull()?.state.orEmpty()
            .split(',', '،')
            .map { it.trim().replace(Regex("""\s+"""), "-").trim('-') }
            .filter { it.isNotEmpty() }
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart().orEmpty()
        val allTags = buildList {
            addAll(selectedTags)
            addAll(customTags)
            if (year.isNotBlank()) add(year)
        }.distinct()

        if (cleanQuery.isBlank() && allTags.size > 1) {
            val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
            val requests = allTags.take(MAX_PARALLEL_FILTERS).map { slug ->
                GET(tagUrl(slug, page, sort), headers)
            }
            return fetchAndMergePages(requests)
        }

        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val browse = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.toUriPart().orEmpty()
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.toUriPart().orEmpty()
        val firstTag = filters.filterIsInstance<TagGroup>()
            .firstOrNull()?.state?.firstOrNull { it.state }?.value
            ?: filters.filterIsInstance<TagTextFilter>()
                .firstOrNull()?.state.orEmpty()
                .split(',', '،')
                .map { it.trim().replace(Regex("""\s+"""), "-").trim('-') }
                .firstOrNull { it.isNotEmpty() }
            ?: year

        if (cleanQuery.isNotBlank()) {
            val builder = (if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/").toHttpUrl().newBuilder()
                .addQueryParameter("s", cleanQuery)
            if (sort.isNotBlank()) builder.addQueryParameter("filter", sort)
            return GET(builder.build(), headers)
        }

        if (firstTag.isNotBlank()) {
            return GET(tagUrl(firstTag, page, sort), headers)
        }

        if (browse == "latest" || sort.isNotBlank()) {
            val builder = (if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/").toHttpUrl().newBuilder()
            if (sort.isNotBlank()) builder.addQueryParameter("filter", sort)
            return GET(builder.build(), headers)
        }

        return popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun parseListing(document: Document): AnimesPage {
        val animes = document.select(popularAnimeSelector())
            .mapNotNull { el ->
                val anime = popularAnimeFromElement(el)
                val lowerUrl = anime.url.lowercase(Locale.ROOT)
                if (anime.url.isBlank() || anime.title.isBlank()) return@mapNotNull null
                if (NON_CONTENT_PATHS.any { it in lowerUrl }) return@mapNotNull null
                anime
            }
            .distinctBy { it.url }

        val current = document.selectFirst("div.pagination a.current, div.pagination span.current")
            ?.text()?.trim()?.toIntOrNull() ?: 1
        val hasNextLink = document.selectFirst(popularAnimeNextPageSelector()) != null
        val hasHigherPage = document.select("div.pagination a").any { a ->
            (a.text().trim().toIntOrNull() ?: -1) > current
        }
        return AnimesPage(animes, (hasNextLink || hasHigherPage) && animes.isNotEmpty())
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
        val doc = Jsoup.parse(html, fullUrl)
        animeDetailsParse(doc)
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1.widget-title, h1.entry-title, h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: document.selectFirst("article.thumb-block img, .video-player img, img")?.extractThumbUrl()

        genre = document.select(".tags-list a, a[rel=tag], a[href*='/tag/']")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        description = document.selectFirst(".archive-description, .video-description, .entry-content p, meta[property=og:description]")
            ?.let { el -> el.attr("content").ifBlank { el.text() }.trim() }
            ?.ifBlank { null }

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        val doc = Jsoup.parse(html, fullUrl)
        parseEpisodesFromDoc(doc, fullUrl)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseEpisodesFromDoc(Jsoup.parse(html, url), url)
    }

    private fun parseEpisodesFromDoc(doc: Document, pageUrl: String): List<SEpisode> {
        // Category/Series page lists episodes as article.thumb-block cards.
        if ("/category/" in pageUrl) {
            val eps = doc.select("article.thumb-block, article.post, div.thumb-block").mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                if (href.isBlank() || "/category/" in href || href == pageUrl) return@mapNotNull null
                val name = a.attr("title").ifBlank {
                    el.selectFirst(".cat-title, .entry-header span, .entry-title, h2, h3")?.text().orEmpty()
                }.trim()
                val epNum = EP_NUM.findAll(name).lastOrNull()?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
                SEpisode.create().apply {
                    setUrlWithoutDomain(href)
                    this.name = name.ifBlank { "الحلقة ${epNum.toInt()}" }
                    episode_number = epNum
                }
            }.distinctBy { it.url }

            if (eps.isNotEmpty()) {
                return eps.sortedByDescending { it.episode_number }
            }
        }

        // Direct episode page (opened from Latest / Search / Tag).
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(pageUrl)
                episode_number = 1F
                name = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()?.ifBlank { null } ?: episodeMovieText
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
            throw IOException("Cloudflare blocked ArabsHentai. Open this episode in WebView once, then retry.")
        }

        val document = Jsoup.parse(html, pageUrl)
        val videos = mutableListOf<Video>()

        // 1) Working Dropbox direct MP4 links (raw=1 streams video/mp4 with 206 range support).
        val dropboxLinks = linkedSetOf<String>()
        document.select("a[href*=dropbox.com]").forEach { a ->
            a.absUrl("href").ifBlank { a.attr("href") }
                .takeIf { it.startsWith("http") }
                ?.let(dropboxLinks::add)
        }
        DROPBOX_MP4_REGEX.findAll(html).forEach { dropboxLinks += it.value.replace("&amp;", "&") }
        dropboxLinks.forEach { rawDb ->
            val directDb = toDropboxRawUrl(rawDb)
            val quality = QUALITY_IN_URL.find(directDb)?.value?.let { "Dropbox - $it" } ?: "Dropbox - MP4"
            val dbHeaders = headers.newBuilder()
                .removeAll("Referer")
                .set("Accept", "*/*")
                .build()
            videos += Video(directDb, quality, directDb, headers = dbHeaders)
        }

        // 2) Live direct HTML5 / script MP4 & M3U8 sources (skip dead x-tez.com hosts).
        val directCandidates = linkedSetOf<String>()
        document.select("video source[src], video[src], source[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }.trim().replace("\\/", "/")
            if (src.startsWith("http") && isPlayableMediaUrl(src)) directCandidates += src
        }
        MP4_OR_M3U8.findAll(html)
            .map { it.value.replace("\\/", "/").replace("&amp;", "&") }
            .filter { it.startsWith("http") && isPlayableMediaUrl(it) }
            .forEach(directCandidates::add)

        directCandidates.forEach { src ->
            if ("dropbox.com" in src.lowercase(Locale.ROOT)) return@forEach
            val q = if (".m3u8" in src.lowercase(Locale.ROOT)) "Direct - HLS" else (QUALITY_IN_URL.find(src)?.value?.let { "Direct - $it" } ?: "Direct - MP4")
            videos += streamFromUrl(src, q, pageUrl)
        }

        // 3) Third-party iframes (if any), extracted in parallel with tight timeouts.
        val iframes = document.select("iframe[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") }.trim().replace("&amp;", "&").takeIf(::isUsefulEmbed) }
            .distinct()
        if (iframes.isNotEmpty()) {
            videos += extractEmbedsInParallel(iframes)
        }

        val distinct = videos.distinctBy { it.videoUrl ?: it.url }
        if (distinct.isEmpty()) {
            throw IOException("لم يتم العثور على رابط تشغيل صالح لهذه الحلقة على ArabsHentai.")
        }
        return distinct
    }

    private fun extractEmbedsInParallel(embeds: List<String>): List<Video> {
        val pool = Executors.newFixedThreadPool(embeds.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            embeds.forEach { embed ->
                futures += completion.submit {
                    runCatching { runBlocking { extractFromEmbed(embed, "Embed") } }.getOrDefault(emptyList())
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

    private suspend fun extractFromEmbed(url: String, label: String): List<Video> {
        var clean = url.trim().replace("&amp;", "&").replace("\\/", "/")
        if (clean.startsWith("//")) clean = "https:$clean"
        if (!clean.startsWith("http")) return emptyList()
        val lower = clean.lowercase(Locale.ROOT)
        return when {
            "dood" in lower || "d0o0d" in lower || "ds2play" in lower || "playmogo" in lower ->
                doodExtractor.videoFromUrl(clean, prefix = "$label - ")?.let(::listOf).orEmpty()

            "mixdrop" in lower || "mxdrop" in lower ->
                mixDropExtractor.videoFromUrl(clean, lang = label)

            STREAMWISH.any { it in lower } ->
                streamWishExtractor.videosFromUrl(clean, label)

            "streamtape" in lower || "streamta.pe" in lower ->
                streamTapeExtractor.videoFromUrl(clean, quality = label)?.let(::listOf).orEmpty()

            "voe" in lower ->
                voeExtractor.videosFromUrl(clean)

            "uqload" in lower ->
                uqloadExtractor.videosFromUrl(clean, "$label -")

            ".m3u8" in lower || ".mp4" in lower ->
                streamFromUrl(clean, label, "$baseUrl/")

            else ->
                runCatching { universalExtractor.videosFromUrl(clean, headers, prefix = "$label - ") }
                    .getOrDefault(emptyList())
        }
    }

    private fun streamFromUrl(url: String, quality: String, referer: String): List<Video> {
        val clean = url.trim().replace("\\/", "/").replace("&amp;", "&")
        val vHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("Accept", "*/*")
            .build()
        return listOf(Video(clean, quality, clean, headers = vHeaders))
    }

    override fun List<Video>.sort(): List<Video> {
        val pref = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy<Video> { if (it.quality.contains("Dropbox", true)) 0 else 1 }
                .thenBy { !it.quality.contains(pref, ignoreCase = true) }
                .thenByDescending { QUALITY_IN_URL.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يمكنك اختيار أكثر من تصنيف في نفس الوقت"),
        BrowseFilter(),
        SortFilter(),
        YearFilter(),
        TagGroup(),
        TagTextFilter(),
    )

    private class BrowseFilter :
        UriPartFilter(
            "القسم / Section",
            arrayOf(
                Pair("قائمة الأنمي /anime/", "anime"),
                Pair("أحدث الحلقات /", "latest"),
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "الترتيب / Sort",
            arrayOf(
                Pair("الافتراضي", ""),
                Pair("الأحدث", "latest"),
                Pair("الأكثر شعبية", "popular"),
                Pair("الأكثر مشاهدة", "most-viewed"),
                Pair("الأطول", "longest"),
            ),
        )

    private class YearFilter :
        UriPartFilter(
            "السنة / Year",
            arrayOf(Pair("الكل", "")) +
                (2026 downTo 2000).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
        )

    private class TagCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class TagGroup :
        AnimeFilter.Group<TagCheckBox>(
            "التصنيفات / Tags (اختيار متعدد)",
            LIVE_TAGS.map { (label, slug) -> TagCheckBox(label, slug) },
        )

    private class TagTextFilter : AnimeFilter.Text("وسم إضافي (افصل بفاصلة ,)")

    // ============================== Helpers ===============================
    private fun tagUrl(slug: String, page: Int, sort: String): String {
        val encoded = URLEncoder.encode(slug, Charsets.UTF_8.name()).replace("+", "%20")
        val path = if (page <= 1) "$baseUrl/tag/$encoded/" else "$baseUrl/tag/$encoded/page/$page/"
        return if (sort.isNotBlank()) {
            path.toHttpUrl().newBuilder().addQueryParameter("filter", sort).build().toString()
        } else {
            path
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

    private fun toDropboxRawUrl(url: String): String {
        val http = url.toHttpUrlOrNull() ?: return url.replace("dl=1", "raw=1").replace("dl=0", "raw=1")
        return http.newBuilder()
            .removeAllQueryParameters("dl")
            .setQueryParameter("raw", "1")
            .build()
            .toString()
    }

    private fun isPlayableMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (DEAD_HOSTS.any { it in lower }) return false
        if ("wp-content/themes" in lower || "px.gif" in lower) return false
        return ".mp4" in lower || ".m3u8" in lower
    }

    private fun isUsefulEmbed(src: String): Boolean {
        val http = src.toHttpUrlOrNull() ?: return false
        val host = http.host.lowercase(Locale.ROOT)
        if ('.' !in host) return false
        return "google" !in host && "exoclick" !in host && "doubleclick" !in host &&
            "twinrdengine" !in host && "arabshentai.net" !in host
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
        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 10_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_500L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val DEAD_HOSTS = listOf("x-tez.com", "sacdnssedge")
        private val NON_CONTENT_PATHS = listOf("/sample-page", "/privacy-policy", "/dmca", "/contact")

        private val EP_NUM = Regex("""\b(\d{1,4})\b""")
        private val DROPBOX_MP4_REGEX = Regex(
            """https?://(?:www\.)?dropbox\.com/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE,
        )
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

        // Verified live tags from https://arabshentai.net/tag/
        private val LIVE_TAGS = listOf(
            "بدون حجب (Uncensored)" to "بدون-حجب",
            "Uncensored" to "uncensored",
            "سكس محرم" to "سكس-محرم",
            "حريم" to "حريم",
            "من طيز (Anal)" to "anal",
            "جنس شرجي" to "جنس-شرجي",
            "جماعي" to "جماعي",
            "اغتصاب" to "اغتصاب",
            "أم" to "أم",
            "امي" to "امي",
            "ام و ابنتها" to "ام-و-ابنتها",
            "ام زوجتي" to "ام-زوجتي",
            "اخت" to "اخت",
            "اختي" to "اختي",
            "اخوات" to "اخوات",
            "أبنة" to "أبنة",
            "بنت الاخ" to "بنت-الاخ",
            "خيانة" to "خيانة",
            "دياثة" to "دياثة",
            "تحكم بلعقل" to "تحكم-بلعقل",
            "تنويم مغناطيسي" to "تنويم-مغناطيسي",
            "سيطرة" to "سيطرة",
            "تعذيب" to "تعذيب",
            "تعنيف" to "تعنيف",
            "خادمة" to "خادمة",
            "استاذ" to "استاذ",
            "سينسي" to "سينسي",
            "رومانسي" to "رومانسي",
            "حب" to "حب",
            "اول مرة" to "اول-مرة",
            "أتشي" to "أتشي",
            "تسوندري" to "تسوندري",
            "سحاقيات" to "سحاقيات",
            "يوري (Yuri)" to "yuri",
            "ياوي (Yaoi)" to "yaoi",
            "سوكوبوس" to "سوكوبوس",
            "ساحرات" to "ساحرات",
            "سحر" to "سحر",
            "سكس شياطين" to "سكس-شياطين",
            "اشباح" to "اشباح",
            "انتقام" to "انتقام",
            "سادي" to "سادي",
        )
    }
}
