package eu.kanade.tachiyomi.animeextension.ar.sexmahali

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
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
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class SexMahali :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "sexmahali").also { it.syncSeedFromPreferences() }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = tasteEngine.rerankPage(super.getPopularAnime(page))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = tasteEngine.rerankPage(super.getLatestUpdates(page))

    override val name = "SexMahali"

    override val baseUrl = "https://sexmahali.com"

    override val lang = "ar"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(CfWebBridgeInterceptor { baseUrl })
        .build()

    private val preferences by getPreferencesLazy()

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

    private val voeExtractor by lazy { VoeExtractor(fastExtractorClient, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(fastExtractorClient) }
    private val doodExtractor by lazy { DoodExtractor(fastExtractorClient) }
    private val m3u8Server by lazy { M3u8ServerManager(streamProxyClient) }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page, SORT_POPULAR, categoryPath = ""), headers)

    override fun popularAnimeSelector(): String = "article.loop-video.thumb-block, article.thumb-block"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        val rawTitle = link.attr("title").ifBlank {
            element.selectFirst("header.entry-header span, header.entry-header")?.text().orEmpty()
        }.ifBlank { link.text() }.trim()
        val duration = element.extractDuration()
        title = if (!duration.isNullOrBlank() && !rawTitle.contains(duration)) {
            "[$duration] $rawTitle"
        } else {
            rawTitle
        }
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    private fun Element.extractDuration(): String? {
        val target = selectFirst("span.duration, span.time, div.duration, span.clock, time, .duration, .time, .video-duration")
            ?: parent()?.selectFirst("span.duration, span.time, div.duration, span.clock, time, .duration, .time, .video-duration")
        val text = target?.text()?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.replace(Regex("""^[^\d]+"""), "").trim().ifBlank { text }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page, SORT_LATEST, categoryPath = ""), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = tasteEngine.onSearchResult(page, query, getSearchAnimeBase(page, query, filters))

    private suspend fun getSearchAnimeBase(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val selectedCats = filters.firstInstanceOrNull<CategoryGroup>()
            ?.state?.filter { it.state }?.map { it.value }.orEmpty()
        val tags = filters.firstInstanceOrNull<TagFilter>()?.state.orEmpty()
            .split(',', '،')
            .map { it.trim().replace(Regex("""\s+"""), "-").trim('-') }
            .filter { it.isNotEmpty() }
            .map { "tag/$it" }
        val actors = filters.firstInstanceOrNull<ActorFilter>()?.state.orEmpty()
            .split(',', '،')
            .map { it.trim().replace(Regex("""\s+"""), "-").trim('-') }
            .filter { it.isNotEmpty() }
            .map { "actor/$it" }

        val paths = (selectedCats + tags + actors).distinct()
        if (query.isBlank() && paths.size > 1) {
            val effectiveSort = sort.ifBlank { SORT_LATEST }
            val requests = paths.take(MAX_PARALLEL_FILTERS).map { path ->
                GET(browseUrl(page, effectiveSort, path), headers)
            }
            return fetchAndMergePages(requests)
        }

        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected.orEmpty()
        val firstCat = filters.firstInstanceOrNull<CategoryGroup>()
            ?.state?.firstOrNull { it.state }?.value.orEmpty()
        val firstTag = filters.firstInstanceOrNull<TagFilter>()?.state.orEmpty()
            .split(',', '،').map { it.trim().replace(Regex("""\s+"""), "-").trim('-') }
            .firstOrNull { it.isNotEmpty() }?.let { "tag/$it" }.orEmpty()
        val firstActor = filters.firstInstanceOrNull<ActorFilter>()?.state.orEmpty()
            .split(',', '،').map { it.trim().replace(Regex("""\s+"""), "-").trim('-') }
            .firstOrNull { it.isNotEmpty() }?.let { "actor/$it" }.orEmpty()
        val category = firstCat.ifBlank { firstTag.ifBlank { firstActor } }

        if (query.isNotBlank()) {
            val builder = baseUrl.toHttpUrl().newBuilder()
            if (category.isNotBlank()) {
                category.trim('/').split('/').filter { it.isNotEmpty() }.forEach {
                    builder.addPathSegment(it)
                }
            }
            if (page > 1) {
                builder.addPathSegment("page")
                builder.addPathSegment(page.toString())
            }
            builder.addQueryParameter("s", query.trim())
            if (sort.isNotBlank()) {
                builder.addQueryParameter("filter", sort)
            }
            return GET(builder.build(), headers)
        }

        if (category.isNotBlank() || sort.isNotBlank()) {
            return GET(browseUrl(page, sort.ifBlank { SORT_LATEST }, category), headers)
        }

        return popularAnimeRequest(page)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response.asJsoup())

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("يمكنك اختيار أكثر من تصنيف أو ممثل في نفس الوقت"),
        SortFilter(),
        CategoryGroup(),
        TagFilter(),
        ActorFilter(),
    )

    private class SortFilter :
        UriSelectFilter(
            "الترتيب / Sort",
            arrayOf(
                "الأكثر شعبية (Popular)" to SORT_POPULAR,
                "الأحدث (Latest)" to SORT_LATEST,
                "الأكثر مشاهدة (Most viewed)" to SORT_MOST_VIEWED,
                "الأطول (Longest)" to SORT_LONGEST,
                "عشوائي (Random)" to SORT_RANDOM,
            ),
        )

    private class CategoryCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup :
        AnimeFilter.Group<CategoryCheckBox>(
            "التصنيفات / Categories (اختيار متعدد)",
            CATEGORIES.map { (label, path) -> CategoryCheckBox(label, path) },
        )

    private class TagFilter : AnimeFilter.Text("تاج / Tag (افصل بفاصلة ,)")

    private class ActorFilter : AnimeFilter.Text("ممثل / Actor (افصل بفاصلة ,)")

    private open class UriSelectFilter(
        name: String,
        private val pairs: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, pairs.map { it.first }.toTypedArray()) {
        val selected: String get() = pairs.getOrNull(state)?.second.orEmpty()
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = getAnimeDetailsBase(anime).also { tasteEngine.recordDetails(anime, it) }

    private suspend fun getAnimeDetailsBase(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.ifBlank { null }
            ?: document.selectFirst("h1, .entry-title, .video-player meta[itemprop=name]")?.attr("content")
            ?: document.selectFirst("h1, .entry-title")?.text().orEmpty()

        thumbnail_url = fixUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content"),
        )

        description = buildString {
            document.selectFirst(".video-description, #video-about .desc, .entry-content .desc")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { append(it) }

            document.selectFirst("#video-date")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n\n")
                append(it)
            }

            document.selectFirst("#video-views")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (isNotEmpty()) append("\n")
                append("المشاهدات: $it")
            }
        }.ifBlank {
            document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        }

        genre = document.select(".tags-list a.label, .tags a.label, a[rel=category tag]")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .distinct()
            .joinToString()
            .ifBlank { null }

        artist = document.select("#video-actors a")
            .mapNotNull { it.text().trim().ifBlank { null } }
            .joinToString()
            .ifBlank { null }

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        tasteEngine.recordLibraryAnime(anime)
        return getEpisodeListBase(anime)
    }

    private suspend fun getEpisodeListBase(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        val doc = Jsoup.parse(html, fullUrl)
        val date = parseArabicDate(doc.selectFirst("#video-date")?.text().orEmpty())
        listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(fullUrl)
                name = "Video"
                episode_number = 1f
                date_upload = date
            },
        )
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        val document = Jsoup.parse(html, url)
        val date = parseArabicDate(document.selectFirst("#video-date")?.text().orEmpty())
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "Video"
                episode_number = 1f
                date_upload = date
            },
        )
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = getVideoListBase(episode).also { if (it.isNotEmpty()) tasteEngine.recordPlayByUrl(episode.url) }

    private suspend fun getVideoListBase(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
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

    private fun parseVideosFromHtml(pageHtml: String, pageUrl: String): List<Video> {
        val document = Jsoup.parse(pageHtml, pageUrl)
        val embeds = extractEmbedUrls(document, pageHtml)
        if (embeds.isEmpty()) return emptyList()

        val prioritized = embeds.sortedBy { embedPriority(it) }
        val pool = Executors.newFixedThreadPool(prioritized.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()

        try {
            for (embedUrl in prioritized) {
                futures += completion.submit {
                    runCatching { videosFromEmbed(embedUrl) }.getOrDefault(emptyList())
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

        return collected.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractEmbedUrls(document: Document, pageHtml: String): List<String> {
        val urls = linkedSetOf<String>()

        fun addIfValid(raw: String) {
            val clean = raw.trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")
            val http = clean.toHttpUrlOrNull() ?: return
            val host = http.host.lowercase(Locale.ROOT)
            if ('.' !in host) return
            if ("google" in host || "doubleclick" in host || "exoclick" in host || "sexmahali.com" in host) return
            urls.add(clean)
        }

        document.select("iframe[src]").forEach { iframe ->
            addIfValid(iframe.attr("abs:src").ifBlank { iframe.attr("src") })
        }

        document.select("meta[itemprop=embedURL]").forEach { meta ->
            addIfValid(meta.attr("content"))
        }

        // WP-Script multi-server: embeds=["<iframe src=\"https:\/\/host\/...\">", ...]
        IFRAME_SRC_REGEX.findAll(pageHtml).forEach { match ->
            addIfValid(match.groupValues[1])
        }

        return urls.toList()
    }

    private fun embedPriority(embedUrl: String): Int {
        val h = embedUrl.lowercase(Locale.ROOT)
        return when {
            "hentaized" in h -> 0
            "hgcloud" in h || "hglink" in h || "streamwish" in h -> 1
            "voe" in h -> 2
            "playmogo" in h || "dood" in h -> 3
            "streamtape" in h || "stape" in h -> 4
            else -> 5
        }
    }

    private fun videosFromEmbed(embedUrl: String): List<Video> {
        val normalizedUrl = redirectHgLink(embedUrl)
        val host = runCatching { normalizedUrl.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrDefault("")
        val label = hostLabel(host)

        return when {
            host.contains("voe") ->
                voeExtractor.videosFromUrl(normalizedUrl, prefix = "$label ")

            host.contains("streamtape") || host.contains("stape") || host.contains("strtape") ->
                streamTapeExtractor.videosFromUrl(normalizedUrl, quality = label)

            host.contains("dood") || host.contains("playmogo") || host.contains("ds2play") ->
                doodExtractor.videosFromUrl(normalizedUrl, quality = label)

            isTurboVidHost(host) ->
                videosFromTurboVid(normalizedUrl, label)

            else ->
                videosFromGenericEmbed(normalizedUrl, label)
        }
    }

    private fun videosFromTurboVid(embedUrl: String, label: String): List<Video> {
        val finalUrl: String
        val body: String
        fastExtractorClient.newCall(GET(embedUrl, headers)).execute().use { resp ->
            finalUrl = resp.request.url.toString()
            body = resp.bodyString()
        }
        val playlistUrl = DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: URLPLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(body)?.value
            ?: return emptyList()
        if (playlistUrl.toHttpUrlOrNull() == null) return emptyList()
        return extractHlsSingleFetch(playlistUrl, finalUrl, label)
    }

    private fun videosFromGenericEmbed(embedUrl: String, label: String): List<Video> {
        val (finalHttpUrl, body) = runCatching {
            fastExtractorClient.newCall(GET(embedUrl, headers)).execute().use { resp ->
                resp.request.url to resp.bodyString()
            }
        }.getOrNull() ?: return emptyList()

        if ("Just a moment" in body || "cf-browser-verification" in body) return emptyList()
        val finalUrl = finalHttpUrl.toString()
        val finalOrigin = "${finalHttpUrl.scheme}://${finalHttpUrl.host}"

        val decoded = ATOB_REGEX.findAll(body).mapNotNull { match ->
            runCatching {
                String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        }.joinToString("\n")

        val unpacked = JsUnpacker.unpackAndCombine(body).orEmpty()
        val combined = "$body\n$unpacked\n$decoded".replace("\\/", "/").replace("\\u0026", "&")

        // Prefer hls4 via local m3u8Server on StreamWish/HGCloud mirrors (strips PNG disguise).
        val hls4Raw = HLS4_LINK_REGEX.find(combined)?.groupValues?.getOrNull(1)
        if (!hls4Raw.isNullOrBlank()) {
            val hls4Url = finalHttpUrl.resolve(hls4Raw)?.toString() ?: hls4Raw
            val proxied = extractProxiedHlsVariants(hls4Url, finalUrl, finalOrigin, label)
            if (proxied.isNotEmpty()) return proxied
        }

        val activeToken = ACTIVE_TOKEN_REGEX.find(decoded)?.groupValues?.getOrNull(1)
        val playlistUrl = MASTER_M3U8_REGEX.find(decoded)?.value
            ?: M3U8_REGEX.find(decoded)?.value
            ?: DATA_HASH_REGEX.find(combined)?.groupValues?.get(1)
            ?: URLPLAY_REGEX.find(combined)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(combined)?.value

        if (playlistUrl != null && playlistUrl.toHttpUrlOrNull() != null) {
            val streamReferer = if ("hentaized" in finalUrl) "https://hentaized.com/" else finalUrl
            val streamOrigin = if ("hentaized" in finalUrl) "https://hentaized.com" else finalOrigin
            val hls = extractHlsSingleFetch(playlistUrl, streamReferer, label, streamOrigin, activeToken)
            if (hls.isNotEmpty()) return hls
        }

        val mp4 = MP4_REGEX.find(combined)?.value ?: return emptyList()
        val videoHeaders = headers.newBuilder()
            .set("Referer", safeReferer(finalUrl))
            .build()
        return listOf(Video(mp4, "$label - MP4", mp4, headers = videoHeaders))
    }

    /**
     * Fetches HLS master playlist once, strips UTF-8 BOM (`\uFEFF` present on cdn.sexmahali.online),
     * resolves variant URLs cleanly, and attaches `verify` token when required.
     */
    private fun extractHlsSingleFetch(
        playlistUrl: String,
        referer: String,
        label: String,
        origin: String? = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" },
        verifyToken: String? = null,
    ): List<Video> {
        val streamHeaders = headers.newBuilder()
            .set("Referer", safeReferer(referer))
            .apply { if (!origin.isNullOrBlank()) set("Origin", origin) }
            .set("Accept", "*/*")
            .build()

        val rawContent = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, streamHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        val content = rawContent.removePrefix("\uFEFF")
        if (!content.trimStart().startsWith("#EXTM3U")) return emptyList()

        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val lines = content.lines().map { it.removePrefix("\uFEFF").trim() }
        val variants = mutableListOf<Video>()

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            var resolved = baseHttp?.resolve(next)?.toString() ?: continue
            if (!verifyToken.isNullOrBlank() && "verify=" !in resolved) {
                resolved = resolved.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("verify", verifyToken)
                    ?.build()?.toString() ?: resolved
            }
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: NAME_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val quality = if (height != null) "$label - ${height}p" else "$label - HLS"
            variants += Video(resolved, quality, resolved, headers = streamHeaders)
        }

        return variants.distinctBy { it.videoUrl }.ifEmpty {
            listOf(Video(playlistUrl, "$label - HLS", playlistUrl, headers = streamHeaders))
        }
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
            .build()
        val body = runCatching {
            fastExtractorClient.newCall(GET(playlistUrl, reqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string().removePrefix("\uFEFF")
            }
        }.getOrNull() ?: return emptyList()

        if (!body.trimStart().startsWith("#EXTM3U")) return emptyList()
        if (!m3u8Server.isRunning()) m3u8Server.startServer()
        val baseHttp = playlistUrl.toHttpUrlOrNull()
        val lines = body.lines().map { it.removePrefix("\uFEFF").trim() }
        val variants = mutableListOf<Video>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: continue
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

    private fun redirectHgLink(url: String): String {
        val host = url.toHttpUrlOrNull()?.host ?: return url
        return if (host.endsWith("hglink.to") || host.endsWith("hgcloud.to")) {
            url.replaceFirst(host, "niramirus.com")
        } else {
            url
        }
    }

    private fun isTurboVidHost(host: String): Boolean = host.contains("turbovid") ||
        host.contains("turboviplay") ||
        host.contains("emturbovid") ||
        host.contains("sptvp") ||
        host.contains("turbosplayer")

    private fun hostLabel(host: String): String = when {
        host.contains("voe") -> "Voe"

        host.contains("streamtape") || host.contains("stape") -> "StreamTape"

        host.contains("turbovid") || host.contains("turboviplay") -> "TurboVid"

        host.contains("hgcloud") || host.contains("niramirus") -> "HGCloud"

        host.contains("hentaized") -> "Hentaized"

        host.contains("abyss") -> "Abyss"

        host.contains("seekplays") -> "SeekPlays"

        host.contains("playmogo") || host.contains("dood") -> "Dood"

        host.isBlank() -> "Server"

        else -> host.substringBefore('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy<Video> { !it.quality.contains(quality, ignoreCase = true) }
                .thenByDescending {
                    QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                },
        )
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        tasteEngine.attachPreferences(screen)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    // =============================== Helpers ==============================

    private fun parseListing(document: Document): AnimesPage {
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }

        val current = document.selectFirst("div.pagination .current, div.pagination span.current, div.pagination a.current")
            ?.text()?.toIntOrNull() ?: 1
        val hasNextLink = document.selectFirst("div.pagination a.next, div.pagination .current + a, div.pagination span.current + a, div.pagination a.next.page-numbers") != null
        val hasHigherPage = document.select("div.pagination a, div.pagination span").any {
            (it.text().toIntOrNull() ?: -1) > current
        }
        val hasNext = (hasNextLink || hasHigherPage) && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
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

    private fun browseUrl(page: Int, sort: String, categoryPath: String): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        if (categoryPath.isNotBlank()) {
            categoryPath.trim('/').split('/').filter { it.isNotEmpty() }.forEach {
                builder.addPathSegment(it)
            }
        }
        if (page > 1) {
            builder.addPathSegment("page")
            builder.addPathSegment(page.toString())
        }
        if (sort.isNotBlank()) {
            builder.addQueryParameter("filter", sort)
        }
        return builder.build().toString()
    }

    private fun parseArabicDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val normalized = normalizeArabicDigits(raw)
        val day = Regex("""\b(\d{1,2})\b""").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
        val year = Regex("""\b(20\d{2})\b""").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
        val monthIndex = ARABIC_MONTHS.indexOfFirst { raw.contains(it) }
        if (monthIndex < 0) return 0L
        return Calendar.getInstance().apply {
            clear()
            set(year, monthIndex, day)
        }.timeInMillis
    }

    private fun normalizeArabicDigits(input: String): String = input.map { ch ->
        when (ch) {
            in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
            in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
            else -> ch
        }
    }.joinToString("")

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    private fun Element.getImageUrl(): String? {
        val src = when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("data-original") -> attr("data-original")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ").substringBefore(",")
            hasAttr("poster") -> attr("poster")
            else -> attr("src")
        }.ifBlank { attr("src") }.trim()

        if (src.isBlank() || src.startsWith("data:")) return null
        val resolved = when {
            src.startsWith("//") -> "https:$src"
            src.startsWith("http://") || src.startsWith("https://") -> src
            else -> absUrl("data-src").ifBlank { absUrl("data-lazy-src").ifBlank { absUrl("src") } }
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
        private const val SORT_POPULAR = "popular"
        private const val SORT_LATEST = "latest"
        private const val SORT_MOST_VIEWED = "most-viewed"
        private const val SORT_LONGEST = "longest"
        private const val SORT_RANDOM = "random"

        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 10_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_500L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080", "720", "480", "360", "240")

        private val QUALITY_REGEX = Regex("""(\d{3,4})""")
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val NAME_HEIGHT_REGEX = Regex("""NAME="(\d{3,4})p"""", RegexOption.IGNORE_CASE)
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src=\\?["']([^"'\\]+)\\?["']""", RegexOption.IGNORE_CASE)
        private val ATOB_REGEX = Regex("""atob\(\s*["']([^"']+)["']\s*\)""")
        private val ACTIVE_TOKEN_REGEX = Regex("""activeToken\s*=\s*["']([^"']+)["']""")
        private val HLS4_LINK_REGEX = Regex(""""hls4"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val DATA_HASH_REGEX = Regex("""data-hash\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val URLPLAY_REGEX = Regex("""urlPlay\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val MASTER_M3U8_REGEX = Regex("""https?://[^"'\\\s<>]+master\.m3u8[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        private val M3U8_REGEX = Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        private val MP4_REGEX = Regex("""https?://[^"'\\\s<>]+\.mp4[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)

        private val CATEGORIES = listOf(
            "سكس مترجم" to "category/سكس-مترجم",
            "سكس امهات مترجم" to "category/سكس-امهات-مترجم",
            "سكس اخوات مترجم" to "category/سكس-اخوات-مترجم",
            "سكس خيانة مترجم" to "category/سكس-خيانة-مترجم",
            "سكس ميلف مترجم" to "category/سكس-ميلف-مترجم",
            "سكس حجاب مترجم" to "category/سكس-حجاب-مترجم",
            "سكس محارم مترجم" to "category/سكس-محارم-مترجم",
            "سكس مساج مترجم" to "category/سكس-مساج-مترجم",
            "سكس ثلاثي مترجم" to "category/سكس-ثلاثي-مترجم",
            "سكس مراهقات مترجم" to "category/سكس-مراهقات-مترجم",
        )

        private val ARABIC_MONTHS = listOf(
            "يناير",
            "فبراير",
            "مارس",
            "أبريل",
            "مايو",
            "يونيو",
            "يوليو",
            "أغسطس",
            "سبتمبر",
            "أكتوبر",
            "نوفمبر",
            "ديسمبر",
        )
    }
}
