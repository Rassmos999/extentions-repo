package eu.kanade.tachiyomi.animeextension.all.smartforyou

import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import aniyomi.lib.playlistutils.PlaylistUtils
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
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Smart For You (+18) — Self-adapting multi-source personalized hub.
 *
 * Reads the shared `+18` SmartTasteEngine profile (`agy_global_taste_v1`) built across
 * all 27 installed adult extensions + Library Updates, automatically selects your
 * most-used sources (`Auto — Most Used`), queries them using your learned title topics,
 * stars, and tags, filters out already-watched videos, ranks everything globally by taste
 * score, and plays streams directly with local seek/skip caching.
 */
class SmartForYou :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Smart For You"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "all"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "smartforyou").also { it.syncSeedFromPreferences() }
    }

    private val pageCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val seenFeedKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    private val baseHttpClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val phCookieJar = PhAgeCookieJar()

    private val pornhubClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .cookieJar(phCookieJar)
            .build()
    }

    private val sextbClient: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.SECONDS)
            .addInterceptor(CfWebBridgeInterceptor("https://sextb.net"))
            .build()
    }

    private val sexAlArabClient: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.SECONDS)
            .addInterceptor(CfWebBridgeInterceptor("https://sexalarab.tv"))
            .build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(network.client) }
    private val playlistUtils by lazy { PlaylistUtils(baseHttpClient, headers) }

    override val client: OkHttpClient
        get() = baseHttpClient

    private fun isSeekCacheEnabled(): Boolean = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
    private fun preferredQuality(): String = preferences.getString(PREF_QUALITY_KEY, "720p") ?: "720p"
    private fun isAutoSourceMode(): Boolean = preferences.getBoolean(PREF_AUTO_SOURCES_KEY, true)

    private fun isSourceAllowed(id: String): Boolean = preferences.getBoolean("src_enabled_$id", true)

    /**
     * Automatically selects the user's top 3 most-used supported sources from the shared
     * `SmartTasteEngine` profile (updating itself continuously as usage shifts).
     */
    private fun resolveActiveSources(explicitOverride: List<String> = emptyList()): List<SourceDef> {
        if (explicitOverride.isNotEmpty()) {
            return explicitOverride.mapNotNull { id -> ALL_SOURCES.firstOrNull { it.id == id } }.take(4)
        }
        val allowed = ALL_SOURCES.filter { isSourceAllowed(it.id) }.ifEmpty { ALL_SOURCES }
        if (!isAutoSourceMode()) {
            return allowed.take(4)
        }
        val rankedIds = tasteEngine.snapshot().topSources
            .filter { id -> allowed.any { it.id == id } }
        val picked = mutableListOf<SourceDef>()
        for (id in rankedIds) {
            if (picked.size >= AUTO_SOURCE_COUNT) break
            allowed.firstOrNull { it.id == id }?.let(picked::add)
        }
        for (fallback in allowed) {
            if (picked.size >= AUTO_SOURCE_COUNT) break
            if (picked.none { it.id == fallback.id }) picked += fallback
        }
        return picked
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        val sources = resolveActiveSources()
        buildForYouFeed(page = page, sources = sources, mode = FeedMode.FOR_YOU)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/video?o=ht&page=$page", desktopHeaders(baseUrl))
    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        val sources = resolveActiveSources()
        buildForYouFeed(page = page, sources = sources, mode = FeedMode.LATEST)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/video?o=cm&page=$page", desktopHeaders(baseUrl))
    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val checkedSourceIds = filters.filterIsInstance<SourceGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.sourceId }
            .orEmpty()
        val modeValue = filters.filterIsInstance<FeedModeFilter>().firstOrNull()?.selected ?: "for_you"
        val sources = resolveActiveSources(checkedSourceIds)

        if (cleanQuery.isNotBlank()) {
            if (page == 1 && tasteEngine.isEnabled() && cleanQuery.length in 2..50) {
                tasteEngine.recordSearchOrFilter(query = cleanQuery)
            }
            return@withContext searchAcrossSources(page, cleanQuery, sources)
        }

        val mode = when (modeValue) {
            "trending" -> FeedMode.TRENDING
            "latest" -> FeedMode.LATEST
            else -> FeedMode.FOR_YOU
        }
        buildForYouFeed(page = page, sources = sources, mode = mode)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)
    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    private enum class FeedMode { FOR_YOU, TRENDING, LATEST }

    private fun buildForYouFeed(
        page: Int,
        sources: List<SourceDef>,
        mode: FeedMode,
    ): AnimesPage {
        val snap = tasteEngine.snapshot()
        val coreConcepts = snap.topConcepts
        val tasteQueries = buildList {
            addAll(coreConcepts.take(3))
            addAll(tasteEngine.buildTitleSearchQueries(maxQueries = 3))
            snap.topPerformers.firstOrNull()?.let(::add)
        }.distinct()

        Log.i(TAG, "ForYou mode=$mode sources=${sources.map { it.id }} core=$coreConcepts queries=$tasteQueries")

        val hasTaste = mode == FeedMode.FOR_YOU && tasteQueries.isNotEmpty()
        val batches = fetchInParallel(sources) { idx, src ->
            when {
                hasTaste -> {
                    // 1. Prefer native category/genre page when top core concept maps directly
                    //    (e.g. threesome/foursome -> SexTB /genre/threesome--foursome, PornHub ?c=65).
                    val conceptForSource = coreConcepts.getOrNull(idx % coreConcepts.size.coerceAtLeast(1))
                    val nativeCategoryPage = if (conceptForSource != null) {
                        runCatching { browseSourceByConcept(src.id, page, conceptForSource) }.getOrNull()
                    } else {
                        null
                    }
                    if (nativeCategoryPage != null && nativeCategoryPage.animes.isNotEmpty()) {
                        nativeCategoryPage
                    } else {
                        val q = tasteQueries[idx % tasteQueries.size]
                        val searchHits = runCatching { searchSource(src.id, page, q) }.getOrNull()
                        if (searchHits != null && searchHits.animes.isNotEmpty()) {
                            searchHits
                        } else {
                            browseSource(src.id, page, latest = false)
                        }
                    }
                }

                mode == FeedMode.LATEST -> browseSource(src.id, page, latest = true)

                else -> browseSource(src.id, page, latest = false)
            }
        }

        val merged = interleaveUnique(batches)
        if (page <= 1) seenFeedKeys.clear()
        val ranked = tasteEngine.rankCandidates(
            items = merged.animes,
            excludeWatched = true,
            blendWithOriginalRank = mode != FeedMode.FOR_YOU,
        ).filter { anime ->
            val keys = tasteEngine.extractKnownKeys(anime.url, anime.title)
            val alreadyShown = keys.any { it in seenFeedKeys }
            if (!alreadyShown) seenFeedKeys.addAll(keys)
            !alreadyShown
        }
        return AnimesPage(ranked, merged.hasNextPage)
    }

    private fun browseSourceByConcept(srcId: String, page: Int, concept: String): AnimesPage? = when (srcId) {
        "sextb" -> {
            val slug = SEXTB_CONCEPT_GENRES[concept] ?: return null
            val path = if (page > 1) "/genre/$slug/pg-$page" else "/genre/$slug"
            val html = fetchHtml(sextbClient, "https://sextb.net$path?sort=viewed", "https://sextb.net/")
            parseSexTbList(Jsoup.parse(html, "https://sextb.net"))
        }

        "pornhub" -> {
            val catId = PORNHUB_CONCEPT_CATEGORIES[concept] ?: return null
            val html = fetchHtml(pornhubClient, "https://www.pornhub.com/video?c=$catId&o=ht&page=$page", "https://www.pornhub.com/")
            parsePornHubList(Jsoup.parse(html, "https://www.pornhub.com"))
        }

        else -> null
    }

    private fun searchAcrossSources(
        page: Int,
        query: String,
        sources: List<SourceDef>,
    ): AnimesPage {
        val batches = fetchInParallel(sources) { _, src ->
            searchSource(src.id, page, query)
        }
        val merged = interleaveUnique(batches)
        if (page <= 1) seenFeedKeys.clear()
        val ranked = (
            if (tasteEngine.hasLearnedProfile()) {
                tasteEngine.rankCandidates(merged.animes, excludeWatched = false, blendWithOriginalRank = true)
            } else {
                merged.animes
            }
            ).filter { anime ->
            val keys = tasteEngine.extractKnownKeys(anime.url, anime.title)
            val alreadyShown = keys.any { it in seenFeedKeys }
            if (!alreadyShown) seenFeedKeys.addAll(keys)
            !alreadyShown
        }
        return AnimesPage(ranked, merged.hasNextPage)
    }

    private fun fetchInParallel(
        sources: List<SourceDef>,
        block: (Int, SourceDef) -> AnimesPage,
    ): List<AnimesPage> {
        if (sources.isEmpty()) return emptyList()
        if (sources.size == 1) {
            return listOfNotNull(runCatching { block(0, sources.first()) }.getOrNull())
        }
        val pool = Executors.newFixedThreadPool(sources.size.coerceAtMost(4))
        return try {
            val futures = sources.mapIndexed { idx, src ->
                pool.submit<AnimesPage?> {
                    runCatching { block(idx, src) }
                        .onFailure { Log.w(TAG, "Source ${src.id} failed: ${it.message}") }
                        .getOrNull()
                }
            }
            futures.mapNotNull { fut ->
                runCatching { fut.get(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun interleaveUnique(pages: List<AnimesPage>): AnimesPage {
        val out = mutableListOf<SAnime>()
        val seen = HashSet<String>()
        val maxLen = pages.maxOfOrNull { it.animes.size } ?: 0
        for (i in 0 until maxLen) {
            for (p in pages) {
                val item = p.animes.getOrNull(i) ?: continue
                if (seen.add(item.url.lowercase(Locale.ROOT))) out += item
            }
        }
        return AnimesPage(out, pages.any { it.hasNextPage })
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(anime.url)
        val srcId = detectSourceId(fullUrl)
        val details = parseDetailsForSource(srcId, fullUrl, anime)
        if (tasteEngine.isEnabled()) {
            tasteEngine.recordDetails(anime, details)
            if (srcId != null) tasteEngine.recordSourceUsage(srcId, 1.5)
        }
        details
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(toAbsoluteUrl(anime.url), desktopHeaders(toAbsoluteUrl(anime.url)))
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (tasteEngine.isEnabled() && anime.title.isNotBlank()) {
            tasteEngine.recordLibraryAnime(anime)
            detectSourceId(toAbsoluteUrl(anime.url))?.let { tasteEngine.recordSourceUsage(it, 1.2) }
        }
        return listOf(
            SEpisode.create().apply {
                name = "Play Video"
                url = toAbsoluteUrl(anime.url)
                episode_number = 1F
                date_upload = System.currentTimeMillis()
            },
        )
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val fullUrl = toAbsoluteUrl(episode.url)
        val srcId = detectSourceId(fullUrl)
            ?: throw IOException("Unsupported video source URL: $fullUrl")
        val rawVideos = extractVideosForSource(srcId, fullUrl)
        if (rawVideos.isEmpty()) {
            throw IOException("No playable stream found from $srcId. Tap Retry or open in WebView.")
        }
        if (tasteEngine.isEnabled()) {
            tasteEngine.recordPlayByUrl(fullUrl)
            tasteEngine.recordSourceUsage(srcId, 3.5)
        }
        val origin = originOf(fullUrl)
        val wrapped = if (isSeekCacheEnabled()) {
            m3u8Server.wrapVideosForCache(
                videos = rawVideos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = "$origin/",
                defaultOrigin = origin,
                defaultUserAgent = DESKTOP_UA,
            )
        } else {
            rawVideos
        }
        sortByPreferredQuality(wrapped)
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(toAbsoluteUrl(episode.url), desktopHeaders(toAbsoluteUrl(episode.url)))
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ======================== Source Connectors ===========================

    private fun detectSourceId(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "pornhub.com" in host -> "pornhub"
            "sextb.net" in host -> "sextb"
            "xvideos.com" in host -> "xvideos"
            "xnxx.com" in host -> "xnxx"
            "rule34video.com" in host -> "rule34video"
            "sexalarab." in host -> "sexalarab"
            else -> null
        }
    }

    private fun browseSource(srcId: String, page: Int, latest: Boolean): AnimesPage = when (srcId) {
        "pornhub" -> {
            val sort = if (latest) "cm" else "ht"
            val html = fetchHtml(pornhubClient, "https://www.pornhub.com/video?o=$sort&page=$page", "https://www.pornhub.com/")
            parsePornHubList(Jsoup.parse(html, "https://www.pornhub.com"))
        }

        "sextb" -> {
            val path = if (latest && page == 1) {
                "/new-releases"
            } else if (page > 1) {
                "/genre/ltx-encode/pg-$page"
            } else {
                "/genre/ltx-encode"
            }
            val url = "https://sextb.net$path" + if (!latest) "?sort=viewed" else ""
            val html = fetchHtml(sextbClient, url, "https://sextb.net/")
            parseSexTbList(Jsoup.parse(html, "https://sextb.net"))
        }

        "xvideos" -> {
            val url = if (latest) "https://www.xvideos.com/new/$page" else "https://www.xvideos.com/best/${if (page > 1) page - 1 else ""}"
            val html = fetchHtml(baseHttpClient, url, "https://www.xvideos.com/")
            parseWgczList(Jsoup.parse(html, "https://www.xvideos.com"), "https://www.xvideos.com", "XVideos")
        }

        "xnxx" -> {
            val url = if (latest) "https://www.xnxx.com/todays-selection/${if (page > 1) page - 1 else ""}" else "https://www.xnxx.com/best/${if (page > 1) page - 1 else ""}"
            val html = fetchHtml(baseHttpClient, url, "https://www.xnxx.com/")
            parseWgczList(Jsoup.parse(html, "https://www.xnxx.com"), "https://www.xnxx.com", "XNXX")
        }

        "rule34video" -> {
            val seg = if (latest) "latest-updates" else "most-popular"
            val url = "https://rule34video.com/$seg/${if (page > 1) "$page/" else ""}"
            val html = fetchHtml(baseHttpClient, url, "https://rule34video.com/")
            parseKvsList(Jsoup.parse(html, "https://rule34video.com"), "https://rule34video.com", "Rule34")
        }

        "sexalarab" -> {
            val seg = if (latest) "latest-updates" else "most-popular"
            val url = "https://sexalarab.tv/$seg/${if (page > 1) "$page/" else ""}"
            val html = fetchHtml(sexAlArabClient, url, "https://sexalarab.tv/")
            parseKvsList(Jsoup.parse(html, "https://sexalarab.tv"), "https://sexalarab.tv", "SexAlArab")
        }

        else -> AnimesPage(emptyList(), false)
    }

    private fun searchSource(srcId: String, page: Int, query: String): AnimesPage {
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        return when (srcId) {
            "pornhub" -> {
                val url = "https://www.pornhub.com/video/search?search=$enc" + if (page > 1) "&page=$page" else ""
                val html = fetchHtml(pornhubClient, url, "https://www.pornhub.com/")
                parsePornHubList(Jsoup.parse(html, "https://www.pornhub.com"))
            }

            "sextb" -> searchSexTbAjax(query, page)

            "xvideos" -> {
                val url = "https://www.xvideos.com/?k=$enc&p=${(page - 1).coerceAtLeast(0)}"
                val html = fetchHtml(baseHttpClient, url, "https://www.xvideos.com/")
                parseWgczList(Jsoup.parse(html, "https://www.xvideos.com"), "https://www.xvideos.com", "XVideos")
            }

            "xnxx" -> {
                val url = "https://www.xnxx.com/search/$enc/${(page - 1).coerceAtLeast(0)}"
                val html = fetchHtml(baseHttpClient, url, "https://www.xnxx.com/")
                parseWgczList(Jsoup.parse(html, "https://www.xnxx.com"), "https://www.xnxx.com", "XNXX")
            }

            "rule34video" -> {
                val slug = query.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), "-")
                val url = "https://rule34video.com/search/${URLEncoder.encode(slug, "UTF-8")}/${if (page > 1) "$page/" else ""}"
                val html = fetchHtml(baseHttpClient, url, "https://rule34video.com/")
                parseKvsList(Jsoup.parse(html, "https://rule34video.com"), "https://rule34video.com", "Rule34")
            }

            "sexalarab" -> {
                val url = "https://sexalarab.tv/search/?q=$enc&from_videos=$page"
                val html = fetchHtml(sexAlArabClient, url, "https://sexalarab.tv/")
                parseKvsList(Jsoup.parse(html, "https://sexalarab.tv"), "https://sexalarab.tv", "SexAlArab")
            }

            else -> AnimesPage(emptyList(), false)
        }
    }

    // ---- List Parsers ----

    private fun parsePornHubList(doc: Document): AnimesPage {
        val items = doc.select("li.pcVideoListItem:not(.mockNsfwThumb), ul#videoSearchResult li.pcVideoListItem")
            .asSequence()
            .filter { el ->
                el.parents().none { p ->
                    p.id() in setOf("hottestMenuSection", "recommMenuSection", "bottomVideos") ||
                        p.hasClass("premiumStyle")
                }
            }
            .mapNotNull { el ->
                val a = el.selectFirst("a[href*=viewkey=]") ?: return@mapNotNull null
                val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                val rawTitle = el.selectFirst(".vidTitleWrapper a, span.title a, a.thumbnailTitle")
                    ?.let { it.attr("title").ifBlank { it.text() } }
                    ?.trim()
                    ?.ifBlank { a.attr("title").trim() }
                    ?: return@mapNotNull null
                val dur = el.selectFirst("var.duration, span.duration")?.text()?.trim().orEmpty()
                val img = el.selectFirst("img")
                val thumb = listOf(
                    img?.attr("abs:data-image"),
                    img?.attr("abs:data-mediumthumb"),
                    img?.attr("abs:data-src"),
                    img?.attr("abs:src"),
                ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:") && "blank.gif" !in it }
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("PornHub", rawTitle, dur)
                    thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }
            .take(18)
            .toList()
        val hasNext = doc.selectFirst("li.page_next:not(.disabled) a[href], a[rel=next]") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseSexTbList(doc: Document): AnimesPage {
        val items = doc.select("section.tray")
            .filterNot { it.selectFirst("div.tray-title")?.text().orEmpty().contains("Recommended Videos", true) }
            .flatMap { it.select("div.tray-item:not(.ad-card):has(a[href]):not(:has(a[href*=\"/advertise\"]))") }
            .mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("abs:href").ifBlank { "https://sextb.net${a.attr("href")}" }
                val code = el.selectFirst("div.tray-item-code")?.text()?.trim().orEmpty()
                val rawTitle = el.selectFirst("div.tray-item-title")?.text()?.trim()
                    ?.ifBlank { a.attr("title").trim() }
                    ?: return@mapNotNull null
                val dur = el.selectFirst("div.tray-item-runtime")?.text()?.trim().orEmpty()
                val img = el.selectFirst("img")
                val thumb = listOf(img?.attr("data-src"), img?.attr("data-original"), img?.attr("src"))
                    .firstOrNull { !it.isNullOrBlank() && "default-cover" !in it }
                    ?.let {
                        if (it.startsWith("//")) {
                            "https:$it"
                        } else if (it.startsWith("/")) {
                            "https://sextb.net$it"
                        } else {
                            it
                        }
                    }
                val fullTitle = if (code.isNotBlank() && !rawTitle.contains(code, true)) "[$code] $rawTitle" else rawTitle
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("SexTB", fullTitle, dur)
                    thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("ul.pagination a[rel=next], ul.pagination li.active + li a[href]") != null
        return AnimesPage(items, hasNext)
    }

    private fun searchSexTbAjax(query: String, page: Int): AnimesPage {
        val form = FormBody.Builder()
            .add("q", query)
            .add("page", page.coerceAtLeast(1).toString())
            .add("type", "movie")
            .build()
        val reqHeaders = desktopHeaders("https://sextb.net/").newBuilder()
            .set("Origin", "https://sextb.net")
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        sextbClient.newCall(POST("https://sextb.net/ajax/search", reqHeaders, form)).execute().use { resp ->
            if (!resp.isSuccessful) return AnimesPage(emptyList(), false)
            val json = JSONObject(resp.body.string())
            val hits = json.optJSONArray("hits") ?: return AnimesPage(emptyList(), false)
            val items = buildList {
                for (i in 0 until hits.length()) {
                    val obj = hits.optJSONObject(i) ?: continue
                    val code = obj.optString("code").trim().lowercase(Locale.ROOT)
                    if (code.isBlank()) continue
                    val rawTitle = Jsoup.parse(obj.optString("name")).text().trim()
                    val runtime = obj.optString("runtimes").trim().let { if (it.isNotBlank() && it != "0") "$it min" else "" }
                    val poster = obj.optString("poster").trim()
                    val fullTitle = "[${code.uppercase(Locale.ROOT)}] $rawTitle"
                    add(
                        SAnime.create().apply {
                            url = "https://sextb.net/$code"
                            title = buildCardTitle("SexTB", fullTitle, runtime)
                            thumbnail_url = poster.takeIf { it.startsWith("http") }
                        },
                    )
                }
            }.distinctBy { it.url }
            val totalPages = json.optInt("totalPages", 1)
            return AnimesPage(items, items.isNotEmpty() && page < totalPages)
        }
    }

    private fun parseWgczList(doc: Document, origin: String, badge: String): AnimesPage {
        val items = doc.select("div.thumb-block:has(a[href*=/video])")
            .mapNotNull { el ->
                val a = el.selectFirst("p.title a[href], div.thumb a[href]") ?: return@mapNotNull null
                val rawHref = a.attr("href").trim()
                if (rawHref.isBlank() || rawHref.startsWith("javascript:")) return@mapNotNull null
                val href = if (rawHref.startsWith("http")) rawHref else "$origin/${rawHref.removePrefix("/")}"
                val rawTitle = el.selectFirst("p.title a")?.let { it.attr("title").ifBlank { it.text() } }
                    ?.trim()?.ifBlank { a.attr("title").trim() }
                    ?: return@mapNotNull null
                val dur = el.selectFirst("span.duration")?.text()?.trim().orEmpty()
                val img = el.selectFirst("img")
                val thumb = listOf(img?.attr("data-src"), img?.attr("src"))
                    .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:") && "lightbox-blank" !in it }
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle(badge, rawTitle, dur)
                    thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("div.pagination a.next-page, a.no-page.next-page") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseKvsList(doc: Document, origin: String, badge: String): AnimesPage {
        val items = doc.select("div.list-videos div.item > a, div.item > a[href]")
            .mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank {
                    val h = a.attr("href").trim()
                    if (h.startsWith("http")) h else "$origin/${h.removePrefix("/")}"
                }
                if (href.isBlank() || href == "$origin/") return@mapNotNull null
                val rawTitle = a.attr("title").trim().ifBlank {
                    a.selectFirst("strong.title, div.title")?.text()?.trim().orEmpty()
                }
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = a.selectFirst("div.duration, span.duration, div.time")?.text()?.trim().orEmpty()
                val img = a.selectFirst("img")
                val thumb = listOf(img?.attr("data-original"), img?.attr("data-webp"), img?.attr("data-src"), img?.attr("src"))
                    .firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:") }
                    ?.let {
                        if (it.startsWith("//")) {
                            "https:$it"
                        } else if (it.startsWith("/")) {
                            "$origin$it"
                        } else {
                            it
                        }
                    }
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle(badge, rawTitle, dur)
                    thumbnail_url = thumb
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("div.pagination li.next a[href], div.pagination a.next[href], a[rel=next]") != null
        return AnimesPage(items, hasNext)
    }

    private fun buildCardTitle(badge: String, rawTitle: String, duration: String): String = buildString {
        append("[").append(badge).append("] ")
        append(rawTitle.trim())
        if (duration.isNotBlank() && !rawTitle.contains(duration)) {
            append(" [").append(duration).append("]")
        }
    }

    // ---- Details Parser ----

    private fun parseDetailsForSource(srcId: String?, fullUrl: String, fallback: SAnime): SAnime {
        val httpClient = clientForSource(srcId)
        val html = runCatching { fetchHtml(httpClient, fullUrl, "${originOf(fullUrl)}/") }.getOrNull()
            ?: return SAnime.create().apply {
                url = fullUrl
                title = fallback.title
                thumbnail_url = fallback.thumbnail_url
                status = SAnime.COMPLETED
            }
        val doc = Jsoup.parse(html, fullUrl)
        val parsedTitle = doc.selectFirst("h1.title span.inlineFree, h1.film-info-title, h1.page-title, h1")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() }
            ?.ifBlank { fallback.title }
            ?: fallback.title
        val thumb = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }
            ?: fallback.thumbnail_url
        val tags = when (srcId) {
            "pornhub" -> doc.select("div.categoriesWrapper a, div.tagsWrapper a").eachText()
            "sextb" -> doc.select("div.description:contains(Genre) a").eachText()
            "xvideos", "xnxx" -> doc.select("div.video-metadata a.is-keyword, div.video-tags-list a").eachText()
            else -> doc.select("div.info a[href*=/tag], div.info a[href*=/categor], a[href*=/tags/]").eachText()
        }.map(String::trim).filter(String::isNotBlank).distinct().take(15)

        val stars = when (srcId) {
            "pornhub" -> doc.select("div.pornstarsWrapper a, div.userInfo a.bolded").eachText()
            "sextb" -> doc.select("div.description:contains(Cast) a").eachText()
            "xvideos", "xnxx" -> doc.select("div.video-metadata a.model, li.model a").eachText()
            else -> doc.select("div.info a[href*=/models/], div.info a[href*=/pornstar]").eachText()
        }.map(String::trim).filter(String::isNotBlank).distinct().take(8)

        val desc = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()
        return SAnime.create().apply {
            url = fullUrl
            title = parsedTitle
            thumbnail_url = thumb
            genre = tags.joinToString().ifBlank { null }
            artist = stars.joinToString().ifBlank { null }
            author = srcId?.uppercase(Locale.ROOT)
            description = buildList {
                if (srcId != null) add("Source: ${ALL_SOURCES.firstOrNull { it.id == srcId }?.label ?: srcId}")
                if (!desc.isNullOrBlank()) add(desc)
            }.joinToString("\n").ifBlank { null }
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ---- Video Extractors ----

    private fun extractVideosForSource(srcId: String, fullUrl: String): List<Video> = when (srcId) {
        "pornhub" -> extractPornHubVideos(fullUrl)
        "sextb" -> extractSexTbVideos(fullUrl)
        "xvideos" -> extractWgczVideos(fullUrl, "https://www.xvideos.com", "XVideos")
        "xnxx" -> extractWgczVideos(fullUrl, "https://www.xnxx.com", "XNXX")
        "rule34video" -> extractKvsVideos(baseHttpClient, fullUrl, "Rule34")
        "sexalarab" -> extractKvsVideos(sexAlArabClient, fullUrl, "SexAlArab")
        else -> emptyList()
    }

    private fun extractPornHubVideos(pageUrl: String): List<Video> {
        val req = GET(pageUrl, desktopHeaders("https://www.pornhub.com/"))
        val (html, setCookies) = pornhubClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("PornHub HTTP ${resp.code}")
            val cookies = LinkedHashMap<String, String>()
            for (raw in resp.headers("Set-Cookie")) {
                val pair = raw.substringBefore(';').trim()
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            resp.body.string() to cookies
        }

        val fvJson = extractBalancedJson(html, "var flashvars_") ?: return emptyList()
        val fv = runCatching { JSONObject(fvJson) }.getOrNull() ?: return emptyList()
        val defs = fv.optJSONArray("mediaDefinitions") ?: return emptyList()

        val playHeaders = desktopHeaders("https://www.pornhub.com/").newBuilder()
            .set("Origin", "https://www.pornhub.com")
            .set("Accept-Encoding", "identity")
            .build()

        val videos = mutableListOf<Video>()
        val getMediaUrls = mutableListOf<String>()
        val hlsUrls = mutableListOf<Pair<String, String>>()

        for (i in 0 until defs.length()) {
            val obj = defs.optJSONObject(i) ?: continue
            val fmt = obj.optString("format").lowercase(Locale.ROOT)
            val vUrl = obj.optString("videoUrl").replace("\\/", "/").trim()
            if (!vUrl.startsWith("http")) continue
            val q = obj.opt("quality")?.toString()?.replace(Regex("""[^\d]"""), "").orEmpty()
            when {
                "/get_media" in vUrl && (fmt == "mp4" || fmt.isBlank()) -> getMediaUrls += vUrl
                fmt == "mp4" -> videos += Video(vUrl, "PornHub - ${if (q.isNotBlank()) "${q}p" else "MP4"}", vUrl, headers = playHeaders)
                fmt == "hls" || ".m3u8" in vUrl -> hlsUrls += vUrl to (if (q.isNotBlank()) "${q}p" else "HLS")
            }
        }

        // Fetch progressive MP4s via /video/get_media using exact session cookies
        val cookieHeader = phCookieJar.buildHeader(setCookies)
        for (gmUrl in getMediaUrls.distinct()) {
            runCatching {
                val gmHeaders = playHeaders.newBuilder()
                    .set("Cookie", cookieHeader)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Accept", "application/json, */*")
                    .build()
                baseHttpClient.newCall(GET(gmUrl, gmHeaders)).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val arr = JSONArray(r.body.string())
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val mp4 = item.optString("videoUrl").replace("\\/", "/").trim()
                        val q = item.optString("quality").trim()
                        if (mp4.startsWith("http")) {
                            videos += Video(mp4, "PornHub - ${q}p (MP4)", mp4, headers = playHeaders)
                        }
                    }
                }
            }
        }

        if (videos.isEmpty()) {
            for ((hls, q) in hlsUrls.distinctBy { it.first }) {
                val variants = runCatching {
                    playlistUtils.extractFromHls(hls, referer = "https://www.pornhub.com/", masterHeaders = playHeaders, videoHeaders = playHeaders, videoNameGen = { "PornHub - $it" })
                }.getOrDefault(emptyList())
                if (variants.isNotEmpty()) videos += variants else videos += Video(hls, "PornHub - $q (HLS)", hls, headers = playHeaders)
            }
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractSexTbVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(sextbClient, pageUrl, "https://sextb.net/")
        val doc = Jsoup.parse(html, pageUrl)
        val buttons = doc.select("button.btn-player.episode[data-id], button.btn-player[data-id]:not(.vip)")
            .filterNot { it.hasClass("vip") || it.attr("data-id").trim().startsWith("-") }
            .distinctBy { it.attr("data-id").trim() }
            .sortedBy {
                val t = it.text().uppercase(Locale.ROOT)
                when {
                    "F4" in t -> 0
                    "SW" in t -> 1
                    "FL" in t -> 2
                    else -> 3
                }
            }
            .take(3)

        var token = Regex("""window\.__pt\s*=\s*["']([^"']+)""").find(html)?.groupValues?.get(1).orEmpty()
        var key = Regex("""window\.__pk\s*=\s*["']([^"']+)""").find(html)?.groupValues?.get(1).orEmpty()
        val fallbackFilmId = Regex("""(?:var|let|const)\s+filmId\s*=\s*(\d+)""").find(html)?.groupValues?.get(1).orEmpty()
        if (buttons.isEmpty() || token.isBlank() || key.isBlank()) return emptyList()

        val videos = mutableListOf<Video>()
        for (btn in buttons) {
            val epId = btn.attr("data-id").trim()
            val filmId = btn.attr("data-source").ifBlank { fallbackFilmId }.trim()
            if (epId.isBlank() || filmId.isBlank()) continue
            val form = FormBody.Builder()
                .add("episode", epId)
                .add("filmId", filmId)
                .add("pt", token)
                .add("ts", "")
                .build()
            val ajaxHeaders = desktopHeaders(pageUrl).newBuilder()
                .set("Origin", "https://sextb.net")
                .set("X-Requested-With", "XMLHttpRequest")
                .set("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()
            val payload = runCatching {
                sextbClient.newCall(POST("https://sextb.net/ajax/player", ajaxHeaders, form)).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body.string())
                }
            }.getOrNull() ?: continue
            val enc = payload.optString("player_enc")
            val dec = xorDecrypt(enc, key)
            token = payload.optString("next_pt").ifBlank { token }
            key = payload.optString("next_pk").ifBlank { key }
            if (dec.isBlank()) continue

            val iframeUrl = Jsoup.parse(dec.replace("\\/", "/")).selectFirst("iframe[src]")?.attr("src")
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?: Regex("""https?://[^\s"'<>]+""").find(dec.replace("\\/", "/"))?.value
                ?: continue

            val extracted = extractSexTbEmbed(iframeUrl, pageUrl)
            if (extracted.isNotEmpty()) {
                videos += extracted
                break
            }
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractSexTbEmbed(rawEmbedUrl: String, referer: String): List<Video> {
        val embedUrl = rawEmbedUrl.replaceFirst("hglink.to", "niramirus.com").replaceFirst("hgcloud.to", "niramirus.com")
        val lower = embedUrl.lowercase(Locale.ROOT)
        // F4 embed
        if ("f4s" in lower || "f4jav" in lower) {
            val resp = runCatching { baseHttpClient.newCall(GET(embedUrl, desktopHeaders(referer))).execute() }.getOrNull() ?: return emptyList()
            val (finalUrl, html) = resp.use { it.request.url to it.body.string() }
            val apiPath = Regex("""data-api=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return emptyList()
            val apiUrl = finalUrl.resolve(apiPath)?.toString() ?: return emptyList()
            val playUrl = "$apiUrl?ab=0&ad=1&r=${URLEncoder.encode("https://sextb.net/", "UTF-8")}"
            val srcUrl = runCatching {
                baseHttpClient.newCall(GET(playUrl, desktopHeaders(finalUrl.toString()))).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val j = JSONObject(r.body.string())
                    j.optString("src").trim().ifBlank { finalUrl.resolve(j.optString("url").trim())?.toString() }
                }
            }.getOrNull() ?: return emptyList()
            val embedOrigin = "${finalUrl.scheme}://${finalUrl.host}"
            if (!m3u8Server.isRunning()) m3u8Server.startServer()
            val localUrl = m3u8Server.processM3u8Url(srcUrl, referer = "$embedOrigin/", userAgent = DESKTOP_UA, origin = embedOrigin) ?: return emptyList()
            return listOf(Video(localUrl, "SexTB - F4 HLS", localUrl))
        }

        // StreamWish / FileLions / VidHide packed player
        val resp = runCatching { baseHttpClient.newCall(GET(embedUrl, desktopHeaders(referer))).execute() }.getOrNull() ?: return emptyList()
        val (finalUrl, body) = resp.use { it.request.url to it.body.string().replace("\\/", "/") }
        val doc = Jsoup.parse(body, finalUrl.toString())
        val scripts = doc.select("script").joinToString("\n") { s ->
            val d = s.data()
            if ("eval(function(p,a,c" in d) autoUnpacker(d) ?: d else d
        }
        val m3u8 = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(scripts)?.groupValues?.get(1)
            ?: Regex(""""hls3"\s*:\s*"([^"]+)"""").find(scripts)?.groupValues?.get(1)
            ?: Regex(""""hls2"\s*:\s*"([^"]+)"""").find(scripts)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(scripts)?.value
            ?: return emptyList()
        val resolved = finalUrl.resolve(m3u8)?.toString() ?: m3u8
        val embedOrigin = "${finalUrl.scheme}://${finalUrl.host}"
        if (!m3u8Server.isRunning()) m3u8Server.startServer()
        val localUrl = m3u8Server.processM3u8Url(resolved, referer = finalUrl.toString(), userAgent = DESKTOP_UA, origin = embedOrigin) ?: return emptyList()
        return listOf(Video(localUrl, "SexTB - HLS", localUrl))
    }

    private fun extractWgczVideos(pageUrl: String, origin: String, badge: String): List<Video> {
        val html = fetchHtml(baseHttpClient, pageUrl, "$origin/")
        val playHeaders = desktopHeaders("$origin/").newBuilder().set("Origin", origin).build()
        val videos = mutableListOf<Video>()

        val encId = Regex("""html5player\.setEncodedIdVideo\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)
            ?: Regex("""/video\.([a-z0-9]+)/""", RegexOption.IGNORE_CASE).find(pageUrl)?.groupValues?.get(1)
        val cdnId = Regex("""setCdnId\(['"]?(\d+)['"]?\)""").find(html)?.groupValues?.get(1) ?: "0"

        if (!encId.isNullOrBlank()) {
            runCatching {
                val apiHeaders = desktopHeaders(pageUrl).newBuilder()
                    .set("Origin", origin)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Accept", "application/json, */*")
                    .build()
                baseHttpClient.newCall(GET("$origin/html5player/getvideo/$encId/$cdnId", apiHeaders)).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val j = JSONObject(r.body.string())
                    j.optString("mp4_high").takeIf { it.startsWith("http") }?.let {
                        videos += Video(it, "$badge - High (MP4)", it, headers = playHeaders)
                    }
                    j.optString("mp4_low").takeIf { it.startsWith("http") }?.let {
                        videos += Video(it, "$badge - Low (MP4)", it, headers = playHeaders)
                    }
                    j.optString("hls").takeIf { it.startsWith("http") }?.let { hls ->
                        val variants = runCatching {
                            playlistUtils.extractFromHls(hls, referer = "$origin/", masterHeaders = playHeaders, videoHeaders = playHeaders, videoNameGen = { "$badge - $it" })
                        }.getOrDefault(emptyList())
                        if (variants.isNotEmpty()) videos += variants else videos += Video(hls, "$badge - HLS", hls, headers = playHeaders)
                    }
                }
            }
        }

        if (videos.isEmpty()) {
            Regex("""setVideoUrlHigh\(['"](https?://[^'"]+)['"]\)""").find(html)?.groupValues?.get(1)?.let {
                videos += Video(it, "$badge - High (MP4)", it, headers = playHeaders)
            }
            Regex("""setVideoHLS\(['"](https?://[^'"]+)['"]\)""").find(html)?.groupValues?.get(1)?.let {
                videos += Video(it, "$badge - HLS", it, headers = playHeaders)
            }
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractKvsVideos(httpClient: OkHttpClient, pageUrl: String, badge: String): List<Video> {
        val origin = originOf(pageUrl)
        val html = fetchHtml(httpClient, pageUrl, "$origin/")
        val license = Regex("""license_code\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1).orEmpty()
        val playHeaders = desktopHeaders("$origin/").newBuilder().set("Origin", origin).build()
        val keys = listOf(
            "video_alt_url3" to "1080p",
            "video_alt_url2" to "720p",
            "video_alt_url" to "480p",
            "video_url" to "360p",
        )
        val videos = mutableListOf<Video>()
        for ((field, label) in keys) {
            val raw = Regex("""$field\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.trim() ?: continue
            val decoded = decodeKvsUrl(raw, license)
            if (decoded.startsWith("http")) {
                val res = Regex("""(\d{3,4})p""").find(decoded)?.groupValues?.get(1)?.let { "${it}p" } ?: label
                videos += Video(decoded, "$badge - $res (MP4)", decoded, headers = playHeaders)
            }
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun decodeKvsUrl(raw: String, licenseCode: String): String {
        if (!raw.startsWith("function/0/")) return raw
        val cleaned = raw.removePrefix("function/0/")
        if (licenseCode.isBlank()) return cleaned
        val parts = cleaned.split("/")
        if (parts.size < 6) return cleaned
        val hash = parts[5]
        if (hash.length < 32) return cleaned
        val digits = licenseCode.filter(Char::isDigit).map { it.digitToInt() }.ifEmpty { return cleaned }
        val half = digits.size / 2
        val keyDigits = IntArray(half) { i -> (digits[i] + digits[i + half]) % 10 }
        val chars = hash.substring(0, 32).toCharArray()
        for (i in chars.indices.reversed()) {
            var swapIdx = i
            for (k in i until 32) swapIdx += keyDigits[k % keyDigits.size]
            swapIdx %= 32
            val tmp = chars[i]
            chars[i] = chars[swapIdx]
            chars[swapIdx] = tmp
        }
        val rebuilt = parts.toMutableList()
        rebuilt[5] = String(chars) + hash.substring(32)
        return rebuilt.joinToString("/")
    }

    // ============================== Helpers ===============================

    private fun clientForSource(srcId: String?): OkHttpClient = when (srcId) {
        "pornhub" -> pornhubClient
        "sextb" -> sextbClient
        "sexalarab" -> sexAlArabClient
        else -> baseHttpClient
    }

    private fun fetchHtml(httpClient: OkHttpClient, url: String, referer: String): String {
        val now = System.currentTimeMillis()
        pageCache[url]?.takeIf { now - it.first < CACHE_TTL_MS }?.let { return it.second }
        httpClient.newCall(GET(url, desktopHeaders(referer))).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body.string()
            if (body.length > 2000) {
                if (pageCache.size > 60) pageCache.clear()
                pageCache[url] = now to body
            }
            return body
        }
    }

    private fun desktopHeaders(referer: String): Headers = Headers.Builder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", referer)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun toAbsoluteUrl(raw: String): String = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "$baseUrl/${raw.removePrefix("/")}"

    private fun originOf(url: String): String = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: baseUrl

    private fun xorDecrypt(encoded: String, key: String): String {
        if (encoded.isBlank() || key.isBlank()) return ""
        return runCatching {
            val enc = Base64.decode(encoded, Base64.DEFAULT)
            val kb = key.toByteArray(Charsets.UTF_8)
            ByteArray(enc.size) { i -> (enc[i].toInt() xor kb[i % kb.size].toInt()).toByte() }.toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun extractBalancedJson(html: String, marker: String): String? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val start = html.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until html.length) {
            val c = html[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inStr) {
                escape = true
                continue
            }
            if (c == '"') inStr = !inStr
            if (!inStr) {
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun sortByPreferredQuality(videos: List<Video>): List<Video> {
        val pref = preferredQuality().removeSuffix("p").toIntOrNull() ?: 720
        return videos.sortedWith(
            compareBy<Video>(
                {
                    val h = Regex("""(\d{3,4})p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (h == pref) 0 else 1
                },
                { -(Regex("""(\d{3,4})p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0) },
            ),
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Auto-picks your most-used sources unless overridden below."),
        FeedModeFilter(),
        SourceGroup(ALL_SOURCES.map { SourceCheckBox(it.label, it.id) }),
    )

    private class FeedModeFilter :
        AnimeFilter.Select<String>(
            "Feed Mode",
            arrayOf("For You Mix (Smart Taste)", "Trending Mix", "Latest Mix"),
        ) {
        val selected: String get() = when (state) {
            1 -> "trending"
            2 -> "latest"
            else -> "for_you"
        }
    }

    private class SourceCheckBox(name: String, val sourceId: String) : AnimeFilter.CheckBox(name)
    private class SourceGroup(items: List<SourceCheckBox>) : AnimeFilter.Group<SourceCheckBox>("Sources (optional override)", items)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val activeSummary = resolveActiveSources().joinToString(", ") { it.label }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_SOURCES_KEY
            title = "Auto-select most-used sources"
            summary = "Automatically adapts to your top 3 most-used sources (Current: $activeSummary)."
            setDefaultValue(true)
        }.also(screen::addPreference)

        for (src in ALL_SOURCES) {
            SwitchPreferenceCompat(screen.context).apply {
                key = "src_enabled_${src.id}"
                title = "Allow source: ${src.label}"
                summary = "Include ${src.label} in Auto mix & manual searches."
                setDefaultValue(true)
            }.also(screen::addPreference)
        }

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("720p")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched HLS segments and MP4 ranges locally for faster seeking."
            setDefaultValue(true)
        }.also(screen::addPreference)

        tasteEngine.attachPreferences(screen)
    }

    // ========================== Helper Classes ============================

    private data class SourceDef(val id: String, val label: String)

    private class PhAgeCookieJar : CookieJar {
        private val session = ConcurrentHashMap<String, String>()

        fun buildHeader(extra: Map<String, String>): String {
            val merged = LinkedHashMap<String, String>()
            merged.putAll(session)
            merged.putAll(extra)
            REQUIRED.forEach { (k, v) -> merged[k] = v }
            return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!url.host.endsWith("pornhub.com")) return emptyList()
            val merged = LinkedHashMap<String, String>()
            merged.putAll(session)
            REQUIRED.forEach { (k, v) -> merged[k] = v }
            return merged.map { (k, v) ->
                Cookie.Builder().name(k).value(v).domain("pornhub.com").path("/").secure().build()
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (!url.host.endsWith("pornhub.com")) return
            for (c in cookies) {
                if (c.name.lowercase(Locale.ROOT) in BLOCKED || c.name in REQUIRED) continue
                if (c.value.isNotBlank() && !c.value.equals("deleted", true)) {
                    session[c.name] = c.value
                }
            }
        }

        companion object {
            private val BLOCKED = setOf("htjschalref", "rp", "key", "htjf-mobile", "overwriteccval")
            private val REQUIRED = linkedMapOf(
                "platform" to "pc",
                "age_verified" to "1",
                "accessAgeDisclaimerPH" to "1",
                "accessAgeDisclaimerUK" to "1",
                "accessPH" to "1",
                "hasVisited" to "1",
                "cookiesBannerSeen" to "1",
                "cookieConsent" to "3",
            )
        }
    }

    companion object {
        private const val TAG = "SmartForYou"
        private const val AUTO_SOURCE_COUNT = 3
        private const val SOURCE_TIMEOUT_MS = 12_000L
        private const val CACHE_TTL_MS = 3 * 60 * 1000L

        private const val PREF_AUTO_SOURCES_KEY = "pref_auto_sources"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val ALL_SOURCES = listOf(
            SourceDef("sextb", "SexTB"),
            SourceDef("pornhub", "PornHub"),
            SourceDef("xvideos", "XVideos"),
            SourceDef("xnxx", "XNXX"),
            SourceDef("rule34video", "Rule34Video"),
            SourceDef("sexalarab", "SexAlArab"),
        )

        private val SEXTB_CONCEPT_GENRES = mapOf(
            "threesome" to "threesome--foursome",
            "foursome" to "threesome--foursome",
            "orgy" to "orgy",
            "creampie" to "creampie",
            "milf" to "married-woman",
            "big tits" to "big-tits",
            "big ass" to "huge-butt",
            "anal" to "anal",
            "blowjob" to "blowjob",
            "squirt" to "squirting",
            "lesbian" to "lesbian",
            "cuckold" to "cuckold",
            "stepfamily" to "incest",
            "uncensored" to "ltx-encode",
            "amateur" to "amateur",
            "pov" to "pov",
            "massage" to "massage",
            "cosplay" to "cosplay",
            "bdsm" to "bdsm",
            "hardcore" to "hardcore",
            "interracial" to "black-man",
            "blonde" to "blonde",
            "bbw" to "chubby",
            "slender" to "slender",
            "small tits" to "small-tits",
            "shaved" to "shaved-pussy",
            "feet" to "foot-fetish",
            "handjob" to "handjob",
            "facial" to "facial",
            "cowgirl" to "cowgirl",
            "doggy style" to "doggy-style",
            "69" to "69",
            "masturbation" to "masturbation",
            "public" to "outdoor",
            "english subtitle" to "english-subtitle",
        )

        private val PORNHUB_CONCEPT_CATEGORIES = mapOf(
            "threesome" to "65",
            "foursome" to "65",
            "orgy" to "2",
            "creampie" to "15",
            "milf" to "29",
            "big tits" to "8",
            "big ass" to "4",
            "anal" to "35",
            "blowjob" to "13",
            "squirt" to "69",
            "lesbian" to "27",
            "cuckold" to "242",
            "stepfamily" to "444",
            "amateur" to "3",
            "pov" to "41",
            "massage" to "78",
            "cosplay" to "241",
            "bdsm" to "10",
            "hardcore" to "21",
            "interracial" to "25",
            "latina" to "26",
            "asian" to "1",
            "arab" to "98",
            "ebony" to "17",
            "blonde" to "9",
            "brunette" to "11",
            "redhead" to "42",
            "bbw" to "6",
            "small tits" to "59",
            "feet" to "93",
            "handjob" to "20",
            "facial" to "16",
            "masturbation" to "22",
            "public" to "24",
        )
    }
}
