package eu.kanade.tachiyomi.animeextension.en.hahomoe

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.util.concurrent.TimeUnit

class HahoMoe :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {
    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "hahomoe").also { it.syncSeedFromPreferences() }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = tasteEngine.rerankPage(super.getPopularAnime(page))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = tasteEngine.rerankPage(super.getLatestUpdates(page))

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = tasteEngine.onSearchResult(page, query, super.getSearchAnime(page, query, filters))

    override suspend fun getVideoList(episode: SEpisode): List<Video> = super.getVideoList(episode).also { if (it.isNotEmpty()) tasteEngine.recordPlayByUrl(episode.url) }

    override val name = "haho.moe"

    override val baseUrl = "https://haho.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // haho.moe detail pages can take 10-15s under load; give read timeout breathing room.
    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(client) }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()

    init {
        val httpUrl = baseUrl.toHttpUrl()
        val cookie = Cookie.parse(httpUrl, "loop-view=thumb")!!
        client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?s=vdy-d&page=$page", headers)

    override fun popularAnimeSelector() = "ul.anime-loop.loop > li > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href") + "?s=srt-d")
        val baseTitle = element.selectFirst("div.label > span, div span.thumb-title")!!.text()
        val duration = element.selectFirst("span.duration, span.time, div.duration, time, span.badge, div.badge, div.time")?.text()?.trim()
        title = if (!duration.isNullOrEmpty()) "[$duration] $baseTitle" else baseTitle
        thumbnail_url = element.getImageUrl()
    }

    private fun Element.getImageUrl(): String? {
        val img = if (tagName() == "img") this else selectFirst("img")
        return img?.let {
            it.absUrl("data-src").ifEmpty {
                it.absUrl("data-original").ifEmpty {
                    it.absUrl("poster").ifEmpty {
                        it.absUrl("src")
                    }
                }
            }
        }?.takeIf { it.isNotBlank() }?.let { url ->
            when {
                url.startsWith("//") -> "https:$url"
                else -> url
            }
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[rel=next], ul.pagination a[rel=next], a.page-link[rel=next]"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime?s=rel-d&page=$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun getFilterList() = HahoMoeFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val (includedTags, excludedTags, orderBy, ordering) = HahoMoeFilters.getSearchParameters(filters)

        val httpQuery = buildString {
            if (query.isNotBlank()) append(query.trim())
            if (includedTags.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(includedTags.joinToString(" genre:", prefix = "genre:"))
            }
            if (excludedTags.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(excludedTags.joinToString(" -genre:", prefix = "-genre:"))
            }
        }.let { URLEncoder.encode(it, "UTF-8") }

        return GET("$baseUrl/anime?page=$page&s=$orderBy$ordering&q=$httpQuery", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = getAnimeDetailsBase(anime).also { tasteEngine.recordDetails(anime, it) }

    private suspend fun getAnimeDetailsBase(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        animeDetailsParse(Jsoup.parse(html, fullUrl))
    }

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("img.cover-image.img-thumbnail")?.getImageUrl()
        title = document.selectFirst("li.breadcrumb-item.active, h1")?.text().orEmpty()
        genre = document.select("li.genre span.value, div.genre-tree ul > li > a").joinToString { it.text() }
        description = document.selectFirst("div.card-body")?.text()
        author = document.select("li.production span.value").joinToString { it.text() }
        artist = document.selectFirst("li.group span.value")?.text()
        status = parseStatus(document.selectFirst("li.status span.value")?.text())
    }

    private fun parseStatus(statusString: String?): Int = when (statusString) {
        "Ongoing" -> SAnime.ONGOING
        "Completed" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episode-loop > li > a"

    private fun episodeNextPageSelector() = popularAnimeNextPageSelector()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        tasteEngine.recordLibraryAnime(anime)
        return getEpisodeListBase(anime)
    }

    private suspend fun getEpisodeListBase(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val html = fetchPageCached(fullUrl)
        parseEpisodesFromDoc(Jsoup.parse(html, fullUrl))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val html = response.body.string()
        cachePage(url, html)
        return parseEpisodesFromDoc(Jsoup.parse(html, url))
    }

    private fun parseEpisodesFromDoc(initialDoc: Document): List<SEpisode> {
        var doc = initialDoc
        return buildList {
            do {
                if (isNotEmpty()) {
                    val url = doc.selectFirst(episodeNextPageSelector())!!.absUrl("href")
                    doc = client.newCall(GET(url, headers)).execute().use { it.asJsoup() }
                }

                doc.select(episodeListSelector())
                    .map(::episodeFromElement)
                    .also(::addAll)
            } while (doc.selectFirst(episodeNextPageSelector()) != null)

            sortByDescending { it.episode_number }
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))

        val episodeNumberString = element.selectFirst("div.episode-number, div.episode-slug")?.text() ?: "Episode"
        episode_number = episodeNumberString.removePrefix("Episode ").toFloatOrNull() ?: 1F
        val title = element.selectFirst("div.episode-label, div.episode-title")?.text()
            ?.takeUnless { it.equals("No Title", true) }
            ?.let { ": $it" }
            .orEmpty()
        name = episodeNumberString + title
        date_upload = element.selectFirst("span.episode-date, div.date")?.text().orEmpty().toDate()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe[src]")?.absUrl("src")
            ?.takeIf(String::isNotBlank)
            ?: throw IOException("No player iframe found on haho.moe.")
        val iframeHeaders = headersBuilder().set("Referer", document.location()).build()
        val iframeResponse = client.newCall(GET(iframe, iframeHeaders)).execute().use { it.asJsoup() }

        val videoHeaders = headersBuilder()
            .set("Referer", iframe)
            .set("Origin", baseUrl)
            .build()

        val rawVideos = iframeResponse.select(videoListSelector()).mapNotNull { el ->
            val src = el.attr("src").trim()
            if (src.isBlank()) return@mapNotNull null
            val quality = el.attr("title").ifBlank { el.attr("label").ifBlank { "Default" } }
            Video(src, quality, src, headers = videoHeaders)
        }

        val useSeekCache = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
        return if (useSeekCache) {
            m3u8Server.wrapVideosForCache(
                videos = rawVideos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = iframe,
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            rawVideos
        }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video = Video(element.attr("src"), element.attr("title"), element.attr("src"))

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        tasteEngine.attachPreferences(screen)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched MP4 blocks locally (20 min TTL) for instant skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
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

    private fun String.toDate(): Long {
        val fixedDate = trim().replace(DATE_REGEX, "").replace("'", "")
        return runCatching { DATE_FORMATTER.parse(fixedDate)?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val PAGE_CACHE_TTL_MS = 3 * 60 * 1000L

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd 'of' MMM, yyyy", Locale.ENGLISH)
        }

        private val DATE_REGEX by lazy { Regex("(?<=\\d)(st|nd|rd|th)") }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"
    }
}
