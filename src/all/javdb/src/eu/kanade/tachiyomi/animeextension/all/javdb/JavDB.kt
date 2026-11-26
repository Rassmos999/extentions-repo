package eu.kanade.tachiyomi.animeextension.all.javdb

import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class JavDB :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "javdb").also { it.syncSeedFromPreferences() }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = tasteEngine.rerankPage(super.getPopularAnime(page))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = tasteEngine.rerankPage(super.getLatestUpdates(page))

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = super.getAnimeDetails(anime).also { tasteEngine.recordDetails(anime, it) }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        tasteEngine.recordLibraryAnime(anime)
        return super.getEpisodeList(anime)
    }

    override val name = "JavDB"

    override val baseUrl = "https://javdb.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // javdb.com bans IPs that crawl too fast; covers live on jdbstatic.com and are not limited.
    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(::loginGate)
        .addNetworkInterceptor(::siteCookies)
        .rateLimitHost(baseUrl.toHttpUrl(), 2, 1.seconds)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    /**
     * The interface language and the age gate are cookies. They are appended after the
     * cookie jar ran so a WebView login session (`_jdb_session`) is kept alongside them.
     */
    private fun siteCookies(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != SITE_HOST) return chain.proceed(request)

        val kept = request.header("Cookie").orEmpty()
            .split(';')
            .map(String::trim)
            .filter { it.isNotBlank() && it.substringBefore('=') !in OWN_COOKIES }
        val locale = preferences.getString(PREF_LOCALE_KEY, PREF_LOCALE_DEFAULT)!!
        val cookie = (kept + listOf("locale=$locale", "over18=1")).joinToString("; ")
        return chain.proceed(request.newBuilder().header("Cookie", cookie).build())
    }

    /**
     * Uncensored, FC2, anime, tag browsing and some rankings are members-only: the site
     * answers with a redirect to /login, a tiny `window.location.href='/login'` page, or 403.
     */
    private fun loginGate(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.url.host != SITE_HOST) return response

        val redirectedToLogin = response.request.url.encodedPath == "/login" && request.url.encodedPath != "/login"
        val scriptRedirect = response.isSuccessful && response.peekBody(LOGIN_PEEK_BYTES).string().contains(LOGIN_SCRIPT)
        val forbidden = response.code == 403 && request.url.encodedPath.startsWith("/rankings")
        if (redirectedToLogin || scriptRedirect || forbidden) {
            response.close()
            throw IOException(LOGIN_REQUIRED)
        }
        return response
    }

    private val missAvUrl get() = preferences.getString(PREF_MISSAV_KEY, PREF_MISSAV_DEFAULT)!!

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val m3u8Server by lazy { M3u8ServerManager(client) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val period = preferences.getString(PREF_POPULAR_KEY, PREF_POPULAR_DEFAULT)!!
        return GET("$baseUrl/rankings/movies?p=$period&t=censored", headers)
    }

    override fun popularAnimeParse(response: Response) = movieListParse(response)

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response) = movieListParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = tasteEngine.onSearchResult(page, query, getSearchAnimeBase(page, query, filters))

    private suspend fun getSearchAnimeBase(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val text = query.trim()
        val siteUrl = text.toHttpUrlOrNull()?.takeIf { it.host == SITE_HOST }

        return when {
            text.startsWith(PREFIX_ID) -> movieById("/v/${text.removePrefix(PREFIX_ID).trim()}")

            siteUrl != null && siteUrl.encodedPath.startsWith("/v/") -> movieById(siteUrl.encodedPath)

            // Any other javdb.com list (an actor, maker, series, code, tag page...) is browsed as-is.
            siteUrl != null -> client.newCall(GET(siteUrl.withPage(page), headers)).awaitSuccess()
                .let(::movieListParse)

            text.isNotEmpty() && filters.searchType() in ENTITY_SEARCH -> entitySearch(page, text, filters)

            // The site's own `f=code` search returns nothing; its code pages list every release.
            text.isNotEmpty() && filters.searchType() == "code" -> {
                val prefix = text.substringBefore('-').trim().uppercase(Locale.ROOT)
                client.newCall(GET("$baseUrl/video_codes/$prefix".toHttpUrl().withPage(page), headers)).awaitSuccess()
                    .let(::movieListParse)
            }

            else -> super.getSearchAnime(page, query, filters)
        }
    }

    private suspend fun movieById(path: String): AnimesPage {
        val document = client.newCall(GET("$baseUrl$path", headers)).awaitSuccess().useAsJsoup()
        val anime = animeDetailsParse(document).apply {
            setUrlWithoutDomain(path)
            initialized = true
        }
        return AnimesPage(listOf(anime), false)
    }

    /** Actor/maker/series/director search: open the best match and list its movies. */
    private suspend fun entitySearch(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val type = filters.searchType()
        val path = entityPaths.getOrPut("$type:$query") {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("f", type)
                .build()
            val document = client.newCall(GET(url, headers)).awaitSuccess().useAsJsoup()
            // Results live in section.section; the navbar above links /makers/N73g and similar.
            document.select("section.section a[href^=/${type}s/], section.section a[href^=/$type/]")
                .map { it.attr("href").substringBefore('?') }
                .firstOrNull { it.count { c -> c == '/' } == 2 && it.substringAfterLast('/') !in SECTION_SLUGS }
                ?: return AnimesPage(emptyList(), false)
        }
        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            filters.firstInstanceOrNull<EntitySortFilter>()?.toUriPart()
                ?.takeIf(String::isNotEmpty)
                ?.let { addQueryParameter("sort_type", it) }
            addQueryParameter("page", page.toString())
        }.build()
        return client.newCall(GET(url, headers)).awaitSuccess().let(::movieListParse)
    }

    private val entityPaths = mutableMapOf<String, String>()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val text = query.trim()
        val url = when {
            text.isNotEmpty() -> "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", text)
                addQueryParameter("f", filters.searchType())
                filters.firstInstanceOrNull<SearchSortFilter>()?.toUriPart()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { addQueryParameter("sb", it) }
                addQueryParameter("page", page.toString())
            }.build()

            filters.ranking().isNotEmpty() -> "$baseUrl${filters.ranking()}".toHttpUrl().withPage(page)

            filters.selectedTags().isNotEmpty() -> {
                val section = filters.firstInstanceOrNull<SectionFilter>()?.tagPath().orEmpty()
                "$baseUrl/tags$section".toHttpUrl().newBuilder().apply {
                    // Several tags of one group are comma-joined, as the site's own tag picker does.
                    filters.selectedTags().forEach { (group, ids) -> addQueryParameter(group, ids.joinToString(",")) }
                    addQueryParameter("page", page.toString())
                }.build()
            }

            else -> {
                val section = filters.firstInstanceOrNull<SectionFilter>()?.toUriPart().orEmpty()
                "$baseUrl$section/".toHttpUrl().newBuilder().apply {
                    filters.firstInstanceOrNull<ShowFilter>()?.toUriPart()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { addQueryParameter("vft", it) }
                    filters.firstInstanceOrNull<SortFilter>()?.toUriPart()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { addQueryParameter("vst", it) }
                    addQueryParameter("page", page.toString())
                }.build()
            }
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = movieListParse(response)

    private fun HttpUrl.withPage(page: Int) = newBuilder().setQueryParameter("page", page.toString()).build()

    private fun movieListParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val movies = document.select(MOVIE_SELECTOR).map(::movieFromElement)
        val hasNextPage = document.selectFirst("nav.pagination a.pagination-next[href]") != null
        return AnimesPage(movies, hasNextPage)
    }

    private fun movieFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val code = element.selectFirst("div.video-title strong")?.text().orEmpty()
        val name = element.attr("title").ifBlank { element.selectFirst("div.video-title")?.ownText().orEmpty() }
        val duration = element.selectFirst("span.duration, span.time, div.duration, time")?.text()?.trim()
        val baseTitle = listOf(code, name).filter(String::isNotBlank).joinToString(" ")
        title = if (!duration.isNullOrBlank() && !baseTitle.contains(duration)) "[$duration] $baseTitle" else baseTitle
        thumbnail_url = element.selectFirst("div.cover img")?.absUrl("src")
    }

    // ============================== Filters ===============================
    override fun getFilterList() = JavDBFilters.filterList()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = animeDetailsParse(response.asJsoup())

    private fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val info = document.infoBlocks()
        val code = info["ID"]?.text()?.trim().orEmpty()
        val name = document.selectFirst("h2.title strong.current-title")?.text().orEmpty()
        title = listOf(code, name).filter(String::isNotBlank).joinToString(" ")
            .ifBlank { document.selectFirst("h2.title")?.text().orEmpty() }
        thumbnail_url = document.selectFirst("img.video-cover")?.absUrl("src")

        val actors = info["Actor(s)"]?.select("a")
        artist = actors?.joinToString { it.text() }?.takeIf(String::isNotBlank)
        author = listOfNotNull(info["Maker"], info["Publisher"])
            .flatMap { it.select("a").eachText() }
            .distinct()
            .joinToString()
            .takeIf(String::isNotBlank)
        genre = info["Tags"]?.select("a")?.eachText()?.joinToString()?.takeIf(String::isNotBlank)
        status = SAnime.COMPLETED

        description = buildString {
            for (label in DESCRIPTION_LABELS) {
                val value = info[label]?.text()?.trim()?.takeIf(String::isNotBlank) ?: continue
                append(label.removeSuffix("(s)")).append(": ").append(value).append('\n')
            }
            val magnets = document.select(MAGNET_SELECTOR).size
            if (magnets > 0) append("Magnets: ").append(magnets).append('\n')
        }.trim()
    }

    /** `nav.movie-panel-info` rows keyed by their label, e.g. "Released Date" → value element. */
    private fun Document.infoBlocks(): Map<String, Element> = select("nav.movie-panel-info div.panel-block")
        .mapNotNull { block ->
            val label = block.selectFirst("strong")?.text()?.trim()?.removeSuffix(":") ?: return@mapNotNull null
            val value = block.selectFirst("span.value") ?: return@mapNotNull null
            label to value
        }
        .toMap()

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val released = document.infoBlocks()["Released Date"]?.text()?.trim()
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Movie"
                episode_number = 1F
                date_upload = released?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
            },
        )
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = getVideoListBase(episode).also { if (it.isNotEmpty()) tasteEngine.recordPlayByUrl(episode.url) }

    private suspend fun getVideoListBase(episode: SEpisode): List<Video> {
        val document = client.newCall(GET(baseUrl + episode.url, headers)).awaitSuccess().useAsJsoup()
        val code = document.infoBlocks()["ID"]?.text()?.trim()
            ?: document.selectFirst("h2.title strong")?.text()?.trim()
            ?: ""

        // Western IDs (e.g. "DigitalPlayground.26.10.29") have no MissAV page.
        val streams = if (preferences.getBoolean(PREF_STREAMS_KEY, PREF_STREAMS_DEFAULT) && JAV_CODE_REGEX.matches(code)) {
            missAvSlugs(code).parallelCatchingFlatMap { (slug, label) -> missAvVideos(slug, label, code) }
                .distinctBy { it.videoUrl }
        } else {
            emptyList()
        }
        val magnets = if (preferences.getBoolean(PREF_MAGNETS_KEY, PREF_MAGNETS_DEFAULT)) {
            document.select(MAGNET_SELECTOR).mapNotNull(::magnetVideo)
        } else {
            emptyList()
        }

        if (streams.isEmpty() && magnets.isEmpty()) {
            throw Exception("No stream found for $code. Enable magnet links in the extension settings to use torrents.")
        }
        val cachedStreams = if (preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)) {
            m3u8Server.wrapVideosForCache(
                videos = streams.sort(),
                cacheHls = true,
                cacheMp4 = false,
                defaultReferer = "$STREAM_MIRROR_DEFAULT/",
                defaultOrigin = STREAM_MIRROR_DEFAULT,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            streams.sort()
        }
        return cachedStreams + magnets
    }

    /** MissAV page slugs for a JavDB code: the plain release plus its leak / Chinese-subtitle uploads. */
    private fun missAvSlugs(code: String): List<Pair<String, String>> {
        val base = FC2_REGEX.matchEntire(code)?.let { "fc2-ppv-${it.groupValues[1]}" } ?: code.lowercase(Locale.ROOT)
        val slugs = mutableListOf(base to "MissAV")
        if (preferences.getBoolean(PREF_VARIANTS_KEY, PREF_VARIANTS_DEFAULT)) {
            slugs += "$base-uncensored-leak" to "MissAV Leak"
            slugs += "$base-chinese-subtitle" to "MissAV CN Sub"
        }
        return slugs
    }

    private suspend fun missAvVideos(slug: String, label: String, code: String): List<Video> {
        val hosts = listOf(missAvUrl.removeSuffix("/"), STREAM_MIRROR_DEFAULT, STREAM_MIRROR_ALT).distinct()
        var document: Document? = null
        var activeOrigin = missAvUrl.removeSuffix("/")
        for (host in hosts) {
            val reqHeaders = headersBuilder()
                .set("Referer", "$host/")
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val doc = runCatching {
                client.newCall(GET("$host/en/$slug", reqHeaders)).await().use {
                    if (it.isSuccessful) it.asJsoup() else null
                }
            }.getOrNull()
            if (doc != null) {
                document = doc
                activeOrigin = host
                break
            }
        }
        if (document == null) return emptyList()

        // MissAV falls back to a search/recommendation page for unknown slugs.
        val pageTitle = document.selectFirst("meta[property=og:title]")?.attr("content") ?: document.title()
        if (!pageTitle.contains(code, ignoreCase = true) && !pageTitle.contains(slug, ignoreCase = true)) return emptyList()

        val script = document.select("script").map { it.data() }
            .firstOrNull { it.contains("function(p,a,c,k,e,d)") }
            ?: return emptyList()
        val unpacked = Unpacker.unpack(script).ifEmpty { script }.replace("\\/", "/")
        val master = M3U8_SOURCE_REGEX.find(unpacked)?.groupValues?.get(1)
            ?: M3U8_URL_REGEX.findAll(unpacked).map { it.value }.let { all -> all.firstOrNull { "playlist.m3u8" in it } ?: all.firstOrNull() }
            ?: return emptyList()

        // surrit.com hotlink-checks Referer/Origin against official MissAV site origins (rejects CDN host and missav.fans).
        val streamOrigin = if (activeOrigin.contains("missav.fans", ignoreCase = true)) STREAM_MIRROR_DEFAULT else activeOrigin
        val refererCandidates = listOf(streamOrigin, STREAM_MIRROR_DEFAULT).distinct()
        for (origin in refererCandidates) {
            val referer = "$origin/"
            val hlsHeaders = headersBuilder()
                .set("Accept", "*/*")
                .set("Origin", origin)
                .set("Referer", referer)
                .build()
            val videos = runCatching {
                playlistUtils.extractFromHls(
                    master,
                    referer = referer,
                    masterHeaders = hlsHeaders,
                    videoHeaders = hlsHeaders,
                    videoNameGen = { "$label - $it" },
                )
            }.getOrDefault(emptyList())
            if (videos.isNotEmpty()) return videos
        }
        return emptyList()
    }

    private fun magnetVideo(item: Element): Video? {
        val link = item.selectFirst("a[href^=magnet:]") ?: return null
        val magnet = link.attr("href")
        val name = link.selectFirst("span.name")?.text().orEmpty()
        val size = link.selectFirst("span.meta")?.text()?.trim().orEmpty()
        val tags = link.select("div.tags span.tag").eachText().joinToString(", ")
        val date = item.selectFirst("div.date span.time")?.text().orEmpty()
        val label = listOf(name, size, tags, date).filter(String::isNotBlank).joinToString(" · ")
        return Video(magnet, "Magnet: $label", magnet)
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        tasteEngine.attachPreferences(screen)

        screen.addListPreference(
            key = PREF_LOCALE_KEY,
            default = PREF_LOCALE_DEFAULT,
            title = "Site language",
            summary = "%s",
            entries = listOf("English", "繁體中文", "简体中文"),
            entryValues = listOf("en", "zh", "zh-CN"),
        )
        screen.addListPreference(
            key = PREF_POPULAR_KEY,
            default = PREF_POPULAR_DEFAULT,
            title = "Popular tab ranking",
            summary = "%s",
            entries = listOf("Daily", "Weekly", "Monthly"),
            entryValues = listOf("daily", "weekly", "monthly"),
        )
        screen.addSwitchPreference(
            key = PREF_STREAMS_KEY,
            default = PREF_STREAMS_DEFAULT,
            title = "Stream from MissAV",
            summary = "JavDB itself has no free player; look the movie ID up on MissAV",
        )
        screen.addSwitchPreference(
            key = PREF_VARIANTS_KEY,
            default = PREF_VARIANTS_DEFAULT,
            title = "Also try leak / Chinese-subtitle uploads",
            summary = "Two extra MissAV lookups per movie",
        )
        screen.addListPreference(
            key = PREF_MISSAV_KEY,
            default = PREF_MISSAV_DEFAULT,
            title = "MissAV domain",
            summary = "%s",
            entries = MISSAV_DOMAINS,
            entryValues = MISSAV_DOMAINS,
        )
        screen.addSwitchPreference(
            key = PREF_MAGNETS_KEY,
            default = PREF_MAGNETS_DEFAULT,
            title = "Show magnet links",
            summary = "Needs an app build with torrent support",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSwitchPreference(
            key = PREF_SEEK_CACHE_KEY,
            default = true,
            title = "Enable local seek/skip cache",
            summary = "Caches watched HLS segments locally (20 min TTL) and prefetches ahead for faster skip/rewind.",
        )
    }

    companion object {
        const val PREFIX_ID = "id:"

        private const val SITE_HOST = "javdb.com"
        private val OWN_COOKIES = setOf("locale", "over18")

        private const val LOGIN_SCRIPT = "window.location.href='/login'"
        private const val LOGIN_PEEK_BYTES = 256L
        private const val LOGIN_REQUIRED =
            "This JavDB section is for members only. Open WebView, log in to javdb.com, then retry."

        private const val MOVIE_SELECTOR = "div.movie-list div.item > a.box"
        private const val MAGNET_SELECTOR = "#magnets-content div.item"

        private val ENTITY_SEARCH = setOf("actor", "maker", "series", "director")
        private val SECTION_SLUGS = setOf("censored", "uncensored", "western", "fc2", "anime")
        private val DESCRIPTION_LABELS = listOf(
            "ID", "Released Date", "Duration", "Director", "Maker", "Publisher", "Series", "Rating", "Actor(s)",
        )

        private val JAV_CODE_REGEX = Regex("""[A-Za-z0-9]+(?:-[A-Za-z]+)?-\d+[A-Za-z]?""")
        private val FC2_REGEX = Regex("""FC2-(?:PPV-)?(\d+)""", RegexOption.IGNORE_CASE)
        private val QUALITY_REGEX = Regex("""(\d{3,4})p""")
        private val M3U8_SOURCE_REGEX =
            Regex("""(?:source|surrogate|m3u8|hls)\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
        private val M3U8_URL_REGEX = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

        private const val PREF_LOCALE_KEY = "pref_locale"
        private const val PREF_LOCALE_DEFAULT = "en"
        private const val PREF_POPULAR_KEY = "pref_popular_period"
        private const val PREF_POPULAR_DEFAULT = "weekly"
        private const val PREF_STREAMS_KEY = "pref_missav_streams"
        private const val PREF_STREAMS_DEFAULT = true
        private const val PREF_VARIANTS_KEY = "pref_missav_variants"
        private const val PREF_VARIANTS_DEFAULT = true
        private const val PREF_MISSAV_KEY = "pref_missav_domain"
        private val MISSAV_DOMAINS = listOf("https://missav.live", "https://missav123.com", "https://missav.ai", "https://missav.ws", "https://missav.fans")
        private val PREF_MISSAV_DEFAULT = MISSAV_DOMAINS.first()
        private const val STREAM_MIRROR_DEFAULT = "https://missav.live"
        private const val STREAM_MIRROR_ALT = "https://missav123.com"
        private const val PREF_MAGNETS_KEY = "pref_magnets"
        private const val PREF_MAGNETS_DEFAULT = true
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"
    }
}

internal inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T
