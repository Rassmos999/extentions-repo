package eu.kanade.tachiyomi.animeextension.all.javgg

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class Javgg :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "JavGG"

    override val baseUrl = "https://javgg.net"

    override val lang = "all"

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    private val preferences by getPreferencesLazy()

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
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

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidHide"

        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"

        private const val MAX_PARALLEL_FILTERS = 4
        private const val MAX_EXTRACTION_WAIT_MS = 14_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 2_000L
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val SERVER_LIST = arrayOf(
            "VidHide",
            "F4Stream",
            "Vidara",
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
            "TurboPlay",
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val F4_API_REGEX = Regex("""data-api=["']([^"']+)["']""")
        private val FILECODE_M3U8_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")

        private val SECTIONS = arrayOf(
            "Trending (All)" to "/trending/",
            "Popular Today" to "/trending/?sort=today",
            "Popular This Week" to "/trending/?sort=weekly",
            "Popular This Month" to "/trending/?sort=monthly",
            "Latest Uploads" to "/new-post/",
        )

        private val GENRES = listOf(
            "4K" to "4k",
            "Beautiful Girl" to "beautiful-girl",
            "Big Breasts" to "big-breasts",
            "Big Tits" to "big-tits",
            "Blowjob" to "blowjob",
            "Cowgirl" to "cowgirl",
            "Creampie" to "creampie",
            "Drama" to "drama",
            "Exclusive Distribution" to "exclusive-distribution",
            "Featured Actress" to "featured-actress",
            "Hi-Def" to "hi-def",
            "High Definition" to "high-definition",
            "Married Woman" to "married-woman",
            "Mature Woman" to "mature-woman",
            "Sample Video" to "sample-video",
            "Slender" to "slender",
            "Slut" to "slut",
            "Solowork" to "solowork",
            "Squirting" to "squirting",
            "Standalone Work" to "standalone-work",
        )

        private val TAGS = listOf(
            "English Subtitle" to "english-subtitle",
            "Chinese Porn" to "chinese-porn",
            "Chinese Subtitle" to "chinese-subtitle",
            "Uncensored Leak" to "uncensored-leak",
            "Reduce Mosaic" to "reduce-mosaic",
            "Censored" to "censored",
            "Decensored" to "decensored",
            "Uncensored" to "uncensored",
            "Amateur Censored" to "amateur-censored",
            "Amateur JAV" to "amateur-jav",
            "Chinese Uncensored" to "chinese-uncensored",
            "English Encoded" to "english-encoded",
            "English Subtitle Amateur" to "english-subtitle-amateur",
            "FC2 Reduce Mosaic" to "fc2-reduce-mosaic",
            "FC2 Uncensored" to "fc2-uncensored",
            "Fc2 Uncensored English Sub" to "fc2-uncensored-english-sub",
            "Fc2 Uncensored/Censored" to "fc2-uncensored-censored",
            "HD Censored" to "hd-censored",
            "HD Uncensored" to "hd-uncensored",
            "1080p" to "1080p",
            "FHD" to "fhd",
            "1PON" to "1pon",
            "CARIB" to "carib",
            "10MU" to "10mu",
            "HEYZO" to "heyzo",
            "PACO" to "paco",
            "KIN8" to "kin8",
            "1P" to "1p",
            "C0930" to "c0930",
            "H0930" to "h0930",
            "H4610" to "h4610",
            "Reduce Mosaic Amateur" to "reduce-mosaic-amateur",
            "Studio Watermark" to "studio-watermark",
            "November 2026" to "november-2026",
            "October 2026" to "october-2026",
            "September 2026" to "september-2026",
            "August 2026" to "august-2026",
            "July 2026" to "july-2026",
            "June 2026" to "june-2026",
            "May 2026" to "may-2026",
            "April 2026" to "april-2026",
            "March 2026" to "march-2026",
            "February 2026" to "february-2026",
            "January 2026" to "january-2026",
            "December 2025" to "december-2025",
        )
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        parseDetails(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseDetails(Jsoup.parse(html, url))
    }

    private fun parseDetails(document: Document): SAnime {
        val animeDetails = SAnime.create().apply {
            status = SAnime.COMPLETED
            description = document.selectFirst("#cover")?.text()
        }

        document.select(".data .boxye2").forEach { element ->
            val category = element.select("[id*=owye2]").text().trim()
            val tags = element.select(".sgeneros3 a").joinToString { it.text() }
            when {
                category.contains("Genres:") -> animeDetails.genre = tags
                category.contains("Cast:") -> animeDetails.artist = tags
                category.contains("Maker:") -> animeDetails.author = tags
            }
        }

        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parsePostGrid(response.asJsoup())

    private fun parsePostGrid(document: Document): AnimesPage {
        val elements = document.select("article[id*=post-]")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                val rawTitle = element.selectFirst(".data h3")!!.text()
                val duration = element.selectFirst("span.duration, span.time, div.duration, time, .time, .duration, span.runtime, .poster span")
                    ?.text()?.trim()
                    ?.takeIf { d -> d.contains(":") }
                title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
                thumbnail_url = element.selectFirst(".poster")?.getImageUrl()
            }
        }
        val nextPage = document.select("#nextpagination, .pagination a.next, a[rel=next], .pagination span.current + a, div.resppages a:contains(>)").any() && animeList.isNotEmpty()
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new-post/page/$page", headers)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) {
            val requests = buildFilterRequests(page, filters)
            if (requests.size > 1) {
                return fetchAndMergePages(requests)
            }
        }
        return client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess().use(::searchAnimeParse)
    }

    private fun buildFilterRequests(page: Int, filters: AnimeFilterList): List<Request> {
        val pageSuffix = if (page > 1) "page/$page/" else ""
        val selectedTags = filters.filterIsInstance<TagGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { "$baseUrl/tag/${it.slug}/$pageSuffix" }.orEmpty()
        val selectedGenres = filters.filterIsInstance<GenreGroup>().firstOrNull()
            ?.state?.filter { it.state }?.map { "$baseUrl/genre/${it.slug}/$pageSuffix" }.orEmpty()

        val combined = (selectedTags + selectedGenres).distinct().take(MAX_PARALLEL_FILTERS)
        if (combined.isNotEmpty()) {
            return combined.map { GET(it, headers) }
        }

        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.selected ?: "/trending/"
        val targetUrl = if (section.contains("?")) {
            val basePath = section.substringBefore("?").trimEnd('/')
            val queryPart = section.substringAfter("?")
            if (page > 1) "$baseUrl$basePath/page/$page/?$queryPart" else "$baseUrl$section"
        } else {
            "$baseUrl$section$pageSuffix"
        }
        return listOf(GET(targetUrl, headers))
    }

    private suspend fun fetchAndMergePages(requests: List<Request>): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            parsePostGrid(resp.asJsoup())
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addPathSegments("page/$page/")
                addQueryParameter("s", query.trim())
            }.build()
            return GET(url, headers)
        }
        return buildFilterRequests(page, filters).first()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val searchElements = document.select(".result-item article")
        if (searchElements.isEmpty()) {
            return parsePostGrid(document)
        }
        val animeList = searchElements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                val rawTitle = element.selectFirst(".details .title")!!.text()
                val duration = element.selectFirst("span.duration, span.time, div.duration, time, .time, .duration, span.runtime, .image span")
                    ?.text()?.trim()
                    ?.takeIf { d -> d.contains(":") }
                title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
                thumbnail_url = element.selectFirst(".image")?.getImageUrl()
            }
        }
        val nextPage = document.select("#nextpagination, .pagination a.next, a[rel=next], .pagination span.current + a, div.resppages a:contains(>)").any() && animeList.isNotEmpty()
        return AnimesPage(animeList, nextPage)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Multi-select supported! Pick multiple Tags and Genres."),
        SectionFilter(),
        TagGroup(TAGS.map { TagCheckBox(it.first, it.second) }),
        GenreGroup(GENRES.map { GenreCheckBox(it.first, it.second) }),
    )

    open class UriPartFilter(
        displayName: String,
        private val entries: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, entries.map { it.first }.toTypedArray()) {
        val selected get() = entries[state].second
    }

    class SectionFilter : UriPartFilter("Sort / Section", SECTIONS)
    private class TagCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    private class TagGroup(items: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>("Categories / Tags (multi-select)", items)
    private class GenreCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    private class GenreGroup(items: List<GenreCheckBox>) : AnimeFilter.Group<GenreCheckBox>("Genres (multi-select)", items)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        parseEpisodes(Jsoup.parse(html, fullUrl), fullUrl)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseEpisodes(Jsoup.parse(html, url), url)
    }

    private fun parseEpisodes(document: Document, pageUrl: String): List<SEpisode> = if (document.select(".dooplay_player_option, [id*=source-player] iframe").any()) {
        listOf(
            SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1F
                setUrlWithoutDomain(pageUrl)
            },
        )
    } else {
        emptyList()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val html = fetchPageCached(fullUrl)
        extractVideosFromDoc(Jsoup.parse(html, fullUrl)).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return extractVideosFromDoc(Jsoup.parse(html, url)).sort()
    }

    private fun extractVideosFromDoc(document: Document): List<Video> {
        val tasks = document.select("[id*=source-player] iframe").mapNotNull { iframe ->
            val embedSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (embedSrc.isBlank()) return@mapNotNull null
            val numOpt = iframe.closest(".source-box")?.attr("id")?.removePrefix("source-player-")
            val optionNum = if (numOpt == "trailer") "1" else numOpt
            val serverName = document.selectFirst("[data-nume=\"$optionNum\"] .server")
                ?.let { it.attr("data-text").ifBlank { it.text() } }
                .orEmpty()
            serverName to embedSrc
        }.distinctBy { it.second }

        if (tasks.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(4))
        val completion = ExecutorCompletionService<List<Video>>(pool)
        val futures = mutableListOf<Future<List<Video>>>()
        val collected = mutableListOf<Video>()
        try {
            tasks.forEach { (serverName, embedSrc) ->
                futures += completion.submit {
                    runCatching { runBlocking { serverVideoResolver(serverName, embedSrc) } }.getOrDefault(emptyList())
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

        val distinct = collected.distinctBy { it.videoUrl ?: it.url }
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

    private fun proxyTransform(m3u8Url: String, referer: String, origin: String): String = m3u8Server.processM3u8Url(
        m3u8Url = m3u8Url,
        referer = referer,
        userAgent = headers["User-Agent"],
        origin = origin,
    ) ?: m3u8Url

    private suspend fun serverVideoResolver(server: String, url: String): List<Video> {
        val target = "$server $url".lowercase()
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        return when {
            target.contains("f4scom") || target.contains("f4jav") || target.contains("f4scdn") || target.contains("f4stream") || target.contains("f4splay") -> {
                extractF4Videos(url)
            }

            target.contains("vidara") || target.contains("mxzhxco8dft") -> {
                extractVidaraVideos(url)
            }

            target.contains("ok.ru") || target.contains("okru") -> OkruExtractor(fastExtractorClient).videosFromUrl(url)

            target.contains("filelions") || target.contains("lion") -> {
                StreamWishExtractor(fastExtractorClient, embedHeaders)
                    .videosFromUrl(url, videoNameGen = { "FileLions:$it" }, m3u8UrlTransformer = ::proxyTransform)
                    .ifEmpty {
                        VidHideExtractor(fastExtractorClient, embedHeaders)
                            .videosFromUrl(url, videoNameGen = { "FileLions:$it" }, m3u8UrlTransformer = ::proxyTransform)
                    }
            }

            target.contains("earnvid") || target.contains("streamhgjav") || target.contains("vidhide") || target.contains("streamhide") || target.contains("guccihide") || target.contains("streamvid") -> {
                VidHideExtractor(fastExtractorClient, embedHeaders)
                    .videosFromUrl(url, videoNameGen = { "VidHide - $it" }, m3u8UrlTransformer = ::proxyTransform)
                    .ifEmpty {
                        StreamWishExtractor(fastExtractorClient, embedHeaders)
                            .videosFromUrl(url, videoNameGen = { "StreamWish:$it" }, m3u8UrlTransformer = ::proxyTransform)
                    }
            }

            target.contains("wishembed") || target.contains("streamwish") || target.contains("strwish") || target.contains("wish") -> {
                val host = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://streamwish.to"
                val docHeaders = headers.newBuilder()
                    .set("Origin", host)
                    .set("Referer", "$host/")
                    .build()
                StreamWishExtractor(fastExtractorClient, docHeaders)
                    .videosFromUrl(url, videoNameGen = { "StreamWish:$it" }, m3u8UrlTransformer = ::proxyTransform)
                    .ifEmpty {
                        VidHideExtractor(fastExtractorClient, embedHeaders)
                            .videosFromUrl(url, videoNameGen = { "StreamWish:$it" }, m3u8UrlTransformer = ::proxyTransform)
                    }
            }

            target.contains("voe") || target.contains("susanexpectcare") -> VoeExtractor(fastExtractorClient, embedHeaders).videosFromUrl(url)

            target.contains("yourupload") || target.contains("upload") -> YourUploadExtractor(fastExtractorClient).videoFromUrl(url, headers = embedHeaders)

            target.contains("turboplay") -> {
                val turboDocument = fastExtractorClient.newCall(GET(url)).execute().asJsoup()
                val masterUrl = turboDocument.select("#video_player").attr("data-hash")
                val hostOrigin = "https://${turboDocument.location().toHttpUrl().host}"
                val localUrl = proxyTransform(masterUrl, "$hostOrigin/", hostOrigin)
                listOf(Video(localUrl, "TurboPlay", localUrl))
            }

            else -> {
                VidHideExtractor(fastExtractorClient, embedHeaders)
                    .videosFromUrl(url, videoNameGen = { "VidHide - $it" }, m3u8UrlTransformer = ::proxyTransform)
                    .ifEmpty { extractDirectM3u8(url, embedHeaders) }
            }
        }
    }

    /**
     * F4Stream API returns both `src` (frequently timing-out direct CDN) and a
     * working proxy path `/v/...` in `v` / `file` / `proxy`. Prefer `/v/...` first.
     */
    private fun extractF4Videos(embedUrl: String): List<Video> {
        val parsedEmbed = embedUrl.toHttpUrlOrNull() ?: return emptyList()
        val embedOrigin = "${parsedEmbed.scheme}://${parsedEmbed.host}"
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val html = fastExtractorClient.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() }
        val apiPath = F4_API_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val apiUrl = parsedEmbed.resolve(apiPath)?.toString() ?: return emptyList()
        val playUrl = "$apiUrl?ab=0&ad=1&r=${URLEncoder.encode("$baseUrl/", "UTF-8")}"
        val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val streamUrl = fastExtractorClient.newCall(GET(playUrl, apiHeaders)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(resp.body.string())
            val candidates = listOf(
                json.optString("v"),
                json.optString("proxy"),
                json.optString("file"),
                json.optString("src"),
            ).map { it.trim().replace("\\u0026", "&").replace("\\/", "/") }.filter(String::isNotBlank)
            val preferred = candidates.firstOrNull { "/v/" in it } ?: candidates.firstOrNull()
            preferred?.let { raw ->
                if (raw.startsWith("http")) raw else parsedEmbed.resolve(raw)?.toString()
            }
        }
        if (streamUrl.isNullOrBlank()) return emptyList()
        val localUrl = proxyTransform(streamUrl, "$embedOrigin/", embedOrigin)
        return listOf(Video(localUrl, "F4Stream - HLS", localUrl))
    }

    private fun extractVidaraVideos(embedUrl: String): List<Video> {
        val filecode = embedUrl.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull { it.isNotBlank() }
            ?: return emptyList()
        val embedHost = embedUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        val candidates = listOfNotNull(embedHost, "https://vidara.to", "https://mxzhxco8dft.com").distinct()
        val payload = JSONObject().apply {
            put("filecode", filecode)
            put("device", "desktop")
            put("codecs", JSONArray(listOf("avc1", "hev1", "vp9")))
        }.toString()

        for (origin in candidates) {
            val streamUrl = runCatching {
                val apiHeaders = headers.newBuilder()
                    .set("Referer", "$origin/e/$filecode")
                    .set("Origin", origin)
                    .set("Content-Type", "application/json")
                    .build()
                fastExtractorClient.newCall(POST("$origin/api/stream", apiHeaders, payload.toRequestBody(JSON_MEDIA_TYPE)))
                    .execute()
                    .use { resp ->
                        if (!resp.isSuccessful) return@use null
                        JSONObject(resp.body.string()).optString("streaming_url")
                            .replace("\\u0026", "&")
                            .takeIf { it.startsWith("http") }
                    }
            }.getOrNull()
            if (!streamUrl.isNullOrBlank()) {
                val localUrl = proxyTransform(streamUrl, "$origin/", origin)
                return listOf(Video(localUrl, "Vidara - HLS", localUrl))
            }
        }
        return emptyList()
    }

    private fun extractDirectM3u8(url: String, reqHeaders: okhttp3.Headers): List<Video> = runCatching {
        val body = fastExtractorClient.newCall(GET(url, reqHeaders)).execute().use { it.body.string() }
            .replace("\\u0026", "&")
            .replace("\\/", "/")
        val m3u8 = FILECODE_M3U8_REGEX.find(body)?.value ?: return emptyList()
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl
        val localUrl = proxyTransform(m3u8, "$origin/", origin)
        listOf(Video(localUrl, "HLS", localUrl))
    }.getOrDefault(emptyList())

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

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        val img = if (tagName() == "img") this else selectFirst("img")
        val url = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("poster").ifEmpty {
                        it.attr("src")
                    }
                }
            }
        }?.takeIf { it.isNotBlank() } ?: run {
            val imageLinkRegex = """(https?:)?//\S+\.(jpg|jpeg|png|webp)""".toRegex()
            for (link in select("[href], [src]")) {
                val href = link.attr("href")
                val src = link.attr("src")
                if (imageLinkRegex.matches(href)) return@run href
                if (imageLinkRegex.matches(src)) return@run src
            }
            val textMatches = imageLinkRegex.find(text())
            val htmlMatches = imageLinkRegex.find(outerHtml())
            textMatches?.value ?: htmlMatches?.value
        }
        return url?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
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
}
