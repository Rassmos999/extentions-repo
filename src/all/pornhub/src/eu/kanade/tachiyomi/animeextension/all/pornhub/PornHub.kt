package eu.kanade.tachiyomi.animeextension.all.pornhub

import androidx.preference.EditTextPreference
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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class PornHub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "all"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "pornhub").also { engine ->
            engine.applySeedText(preferences.getString(PREF_TASTE_SEED_KEY, null))
        }
    }

    private val seenSearchUrls = ConcurrentHashMap<String, MutableSet<String>>()

    private fun isSmartTasteEnabled(): Boolean = preferences.getBoolean(PREF_SMART_TASTE_KEY, true)
    private fun isForYouDefault(): Boolean = preferences.getString(PREF_DEFAULT_FEED_KEY, "ht") == PornHubFilters.FOR_YOU_SORT

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    private val phClient by lazy {
        PornHubClient(
            hostClient = network.client,
            baseUrlProvider = { baseUrl },
        )
    }

    private val extractor by lazy {
        PornHubExtractor(
            phClient = phClient,
            baseUrlProvider = { baseUrl },
            userAgent = DESKTOP_USER_AGENT,
            json = json,
        )
    }

    override val client: OkHttpClient
        get() = phClient.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", DESKTOP_USER_AGENT)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        if (isSmartTasteEnabled() && isForYouDefault() && tasteEngine.hasLearnedProfile()) {
            return@withContext fetchForYouPage(page)
        }
        val pageResult = phClient.fetchPage(
            request = popularAnimeRequest(page),
            cacheTtlMs = PornHubClient.BROWSE_CACHE_TTL_MS,
            bucket = PornHubClient.RateBucket.LISTING,
            highPriority = true,
        )
        val parsed = parseListingDocument(Jsoup.parse(pageResult.html, pageResult.finalUrl))
        applySmartRerank(parsed)
    }

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page = page, sort = "ht"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListingDocument(response.asJsoup())

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        val pageResult = phClient.fetchPage(
            request = latestUpdatesRequest(page),
            cacheTtlMs = PornHubClient.BROWSE_CACHE_TTL_MS,
            bucket = PornHubClient.RateBucket.LISTING,
            highPriority = true,
        )
        val parsed = parseListingDocument(Jsoup.parse(pageResult.html, pageResult.finalUrl))
        applySmartRerank(parsed)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page = page, sort = "cm"), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListingDocument(response.asJsoup())

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val cleanQuery = query.replace(DURATION_PREFIX_REGEX, "").trim()
        if (cleanQuery.equals("view video.php", ignoreCase = true) ||
            cleanQuery.equals("view_video.php", ignoreCase = true) ||
            cleanQuery.startsWith("uploader:", ignoreCase = true) ||
            cleanQuery.startsWith("duration:", ignoreCase = true) ||
            cleanQuery.startsWith("views:", ignoreCase = true) ||
            cleanQuery.startsWith("rating:", ignoreCase = true)
        ) {
            return@withContext AnimesPage(emptyList(), false)
        }

        val f = PornHubFilters.extract(filters)
        if (cleanQuery.isBlank() && f.sort == PornHubFilters.FOR_YOU_SORT) {
            return@withContext fetchForYouPage(page)
        }

        if (page == 1 && isSmartTasteEnabled()) {
            val perfSignals = buildList {
                if (f.pornstar.isNotBlank()) add(f.pornstar.replace('-', ' '))
                if (f.targetName.isNotBlank()) add(f.targetName)
            }
            tasteEngine.recordSearchOrFilter(
                query = cleanQuery.takeIf { it.isNotBlank() },
                performersList = perfSignals,
                categoriesList = f.categories,
            )
        }

        // Multi-category selection (2–4 categories): fetch each category paced cleanly
        // through RateBucket.LISTING and round-robin interleave so every selected category shows evenly.
        if (cleanQuery.isBlank() && f.categories.size > 1 && resolveTargetRoute("", f) == null) {
            return@withContext fetchMultiCategoryPage(page, f)
        }

        val isKeywordSearch = cleanQuery.isNotBlank()
        val pageResult = phClient.fetchPage(
            request = searchAnimeRequest(page, cleanQuery, filters),
            cacheTtlMs = PornHubClient.BROWSE_CACHE_TTL_MS,
            allow404Empty = true,
            bucket = if (isKeywordSearch) PornHubClient.RateBucket.SEARCH else PornHubClient.RateBucket.LISTING,
            highPriority = !isKeywordSearch,
        )
        if (pageResult.statusCode == 404 || pageResult.html.isBlank()) {
            return@withContext AnimesPage(emptyList(), false)
        }
        val parsed = parseListingDocument(Jsoup.parse(pageResult.html, pageResult.finalUrl), isSearch = isKeywordSearch)
        if (isKeywordSearch) {
            deduplicateSearchPage(cleanQuery.lowercase(Locale.ROOT), page, parsed)
        } else {
            applySmartRerank(parsed)
        }
    }

    private fun deduplicateSearchPage(normQuery: String, page: Int, rawPage: AnimesPage): AnimesPage {
        if (page <= 1) {
            if (seenSearchUrls.size > 40) seenSearchUrls.clear()
            val fresh = Collections.synchronizedSet(HashSet<String>())
            val unique = rawPage.animes.filter { fresh.add(it.url.lowercase(Locale.ROOT)) }
            seenSearchUrls[normQuery] = fresh
            return AnimesPage(unique, rawPage.hasNextPage && unique.isNotEmpty())
        }
        val seen = seenSearchUrls.computeIfAbsent(normQuery) { Collections.synchronizedSet(HashSet()) }
        val unique = rawPage.animes.filter { seen.add(it.url.lowercase(Locale.ROOT)) }
        return AnimesPage(unique, rawPage.hasNextPage && unique.isNotEmpty())
    }

    private fun fetchMultiCategoryPage(page: Int, f: PornHubFilters.FilterData): AnimesPage {
        val pages = mutableListOf<AnimesPage>()
        var firstError: Throwable? = null
        for (cat in f.categories) {
            val req = GET(
                browseUrl(
                    page = page,
                    sort = f.sort,
                    category = cat,
                    orientation = f.orientation,
                    production = f.production,
                    duration = f.duration,
                    hd = f.quality,
                ),
                headers,
            )
            try {
                val res = phClient.fetchPage(
                    request = req,
                    cacheTtlMs = PornHubClient.BROWSE_CACHE_TTL_MS,
                    allow404Empty = true,
                    bucket = PornHubClient.RateBucket.LISTING,
                    highPriority = true,
                )
                if (res.statusCode != 404 && res.html.isNotBlank()) {
                    pages += parseListingDocument(Jsoup.parse(res.html, res.finalUrl))
                }
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
            }
        }
        if (pages.isEmpty()) {
            if (firstError != null) throw firstError
            return AnimesPage(emptyList(), false)
        }
        val interleaved = mutableListOf<SAnime>()
        val seen = HashSet<String>()
        val maxLen = pages.maxOfOrNull { it.animes.size } ?: 0
        for (i in 0 until maxLen) {
            for (p in pages) {
                val item = p.animes.getOrNull(i) ?: continue
                if (seen.add(item.url.lowercase(Locale.ROOT))) interleaved += item
            }
        }
        return applySmartRerank(AnimesPage(interleaved, pages.any { it.hasNextPage }))
    }

    private fun applySmartRerank(page: AnimesPage): AnimesPage {
        if (!isSmartTasteEnabled() || page.animes.size < 3 || !tasteEngine.hasLearnedProfile()) {
            return page
        }
        return AnimesPage(
            tasteEngine.rankCandidates(page.animes, excludeWatched = false, blendWithOriginalRank = true),
            page.hasNextPage,
        )
    }

    private fun fetchForYouPage(page: Int): AnimesPage {
        val snap = tasteEngine.snapshot()
        val requests = mutableListOf<Pair<Request, PornHubClient.RateBucket>>()

        // 1. Top learned core concept or category (e.g. threesome -> c=65, creampie -> c=15).
        val matchedCat = snap.topConcepts.firstNotNullOfOrNull { PornHubFilters.findCategoryValueByName(it) }
            ?: snap.topCategories.firstOrNull()
            ?: snap.topTags.firstNotNullOfOrNull { PornHubFilters.findCategoryValueByName(it) }
        if (!matchedCat.isNullOrBlank()) {
            requests += GET(browseUrl(page = page, sort = "ht", category = matchedCat), headers) to PornHubClient.RateBucket.LISTING
        }

        // 2. Top learned core concept / discovery queries (e.g. foursome, threesome, creampie).
        val titleQueries = tasteEngine.buildTitleSearchQueries(maxQueries = 3)
        for (q in titleQueries) {
            if (requests.size >= 3) break
            requests += buildKeywordSearchRequest(page, q) to PornHubClient.RateBucket.SEARCH
        }

        // 3. Top learned performer/star if room remains.
        val topStar = snap.topPerformers.firstOrNull()
        if (!topStar.isNullOrBlank() && requests.size < 3) {
            requests += buildKeywordSearchRequest(page, topStar) to PornHubClient.RateBucket.SEARCH
        }

        if (requests.isEmpty()) {
            val fallbackRes = phClient.fetchPage(
                request = popularAnimeRequest(page),
                cacheTtlMs = PornHubClient.BROWSE_CACHE_TTL_MS,
                bucket = PornHubClient.RateBucket.LISTING,
                highPriority = true,
            )
            return parseListingDocument(Jsoup.parse(fallbackRes.html, fallbackRes.finalUrl))
        }

        val combined = mutableListOf<SAnime>()
        val seen = HashSet<String>()
        var anyNext = false
        for ((req, bucket) in requests.take(3)) {
            val res = runCatching {
                phClient.fetchPage(
                    request = req,
                    cacheTtlMs = PornHubClient.BROWSE_CACHE_TTL_MS,
                    allow404Empty = true,
                    bucket = bucket,
                    highPriority = true,
                )
            }.getOrNull() ?: continue
            if (res.statusCode == 404 || res.html.isBlank()) continue
            val parsed = parseListingDocument(Jsoup.parse(res.html, res.finalUrl), isSearch = bucket == PornHubClient.RateBucket.SEARCH)
            if (parsed.hasNextPage) anyNext = true
            for (item in parsed.animes) {
                if (seen.add(item.url.lowercase(Locale.ROOT))) {
                    combined += item
                }
            }
        }

        val ranked = tasteEngine.rankCandidates(combined, excludeWatched = true, blendWithOriginalRank = false)
        return AnimesPage(ranked, anyNext)
    }

    private fun buildKeywordSearchRequest(page: Int, query: String): Request {
        val clean = query.replace(SEARCH_SANITIZE_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim().lowercase(Locale.ROOT)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("video/search")
            .addEncodedQueryParameter("search", URLEncoder.encode(clean, "UTF-8"))
            .apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val f = PornHubFilters.extract(filters)
        val rawQuery = query.replace(DURATION_PREFIX_REGEX, "").trim()

        // 1. Creator/target browse via prefix (`pornstar:`, `model:`, `channel:`, `user:`) or filter fields.
        val targetRoute = resolveTargetRoute(rawQuery, f)
        if (targetRoute != null) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(targetRoute)
                .apply {
                    if (f.sort in PROFILE_SORTS) addQueryParameter("o", f.sort)
                    addQueryParameter("page", page.toString())
                }
                .build()
            return GET(url, headers)
        }

        // 2. Keyword search (`/video/search` uses `+` spaces, omission = Relevance, `mr` = Most Recent).
        val cleanQuery = rawQuery
            .replace(SEARCH_SANITIZE_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .lowercase(Locale.ROOT)
        if (cleanQuery.isNotBlank()) {
            val searchSort = when (f.sort) {
                "ht" -> ""
                "cm" -> "mr"
                else -> f.sort
            }
            val filterCategory = when (f.category) {
                "path:teen" -> "37"
                "path:babe" -> "5"
                "path:hentai" -> "36"
                "path:college" -> "79"
                else -> f.category.takeIf { !it.startsWith("path:") }
            }
            val searchPath = if (f.orientation == "gay") "gay/video/search" else "video/search"
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(searchPath)
                .addEncodedQueryParameter("search", URLEncoder.encode(cleanQuery, "UTF-8"))
                .apply {
                    if (searchSort.isNotBlank()) addQueryParameter("o", searchSort)
                    if (page > 1) addQueryParameter("page", page.toString())
                    if (!filterCategory.isNullOrBlank()) addQueryParameter("filter_category", filterCategory)
                    if (f.production.isNotBlank()) addQueryParameter("p", f.production)
                    applyDuration(f.duration)
                    if (f.quality.isNotBlank()) addQueryParameter("hd", "1")
                }
                .build()
            return GET(url, headers)
        }

        // 3. Filtered browse without search query.
        return GET(
            browseUrl(
                page = page,
                sort = f.sort,
                category = f.category,
                orientation = f.orientation,
                production = f.production,
                duration = f.duration,
                hd = f.quality,
            ),
            headers,
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListingDocument(response.asJsoup(), isSearch = true)

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val pageResult = phClient.fetchPage(
            request = animeDetailsRequest(anime),
            cacheTtlMs = PornHubClient.VIDEO_CACHE_TTL_MS,
            bucket = PornHubClient.RateBucket.VIDEO,
            highPriority = true,
        )
        val details = parseDetailsDocument(Jsoup.parse(pageResult.html, pageResult.finalUrl), pageResult.html)
        if (isSmartTasteEnabled()) {
            tasteEngine.recordBrowse(
                url = anime.url,
                title = details.title.ifBlank { anime.title },
                tagsList = details.genre?.split(',')?.map(String::trim).orEmpty(),
                performersList = listOfNotNull(details.artist),
                studiosList = listOfNotNull(details.author),
            )
        }
        details
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http", ignoreCase = true)) {
            anime.url
        } else {
            "$baseUrl${anime.url}"
        }
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.body.string()
        val document = Jsoup.parse(html, response.request.url.toString())
        return parseDetailsDocument(document, html)
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // Also learn from saved Library entries whenever Library Update runs.
        if (isSmartTasteEnabled() && anime.title.isNotBlank()) {
            tasteEngine.recordBrowse(
                url = anime.url,
                title = anime.title,
                tagsList = anime.genre?.split(',')?.map(String::trim).orEmpty(),
                performersList = listOfNotNull(anime.artist),
                studiosList = listOfNotNull(anime.author),
            )
        }
        val fullUrl = if (anime.url.startsWith("http", ignoreCase = true)) anime.url else "$baseUrl${anime.url}"
        val cachedHtml = phClient.getCachedPage(fullUrl, PornHubClient.VIDEO_CACHE_TTL_MS)?.html
        val publishedDate = cachedHtml?.let { extractPublishedDate(it) } ?: System.currentTimeMillis()
        return listOf(
            SEpisode.create().apply {
                name = "Play Video"
                url = anime.url
                episode_number = 1F
                date_upload = publishedDate
            },
        )
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.body.string()
        return listOf(
            SEpisode.create().apply {
                name = "Play Video"
                setUrlWithoutDomain(response.request.url.toString())
                episode_number = 1F
                date_upload = extractPublishedDate(html) ?: System.currentTimeMillis()
            },
        )
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val preferredFormat = preferences.getString(PREF_FORMAT_KEY, PREF_FORMAT_DEFAULT) ?: PREF_FORMAT_DEFAULT
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val request = videoListRequest(episode)
        val urlKey = request.url.toString()
        val wasCached = phClient.getCachedPage(urlKey, PornHubClient.VIDEO_CACHE_TTL_MS) != null

        var pageResult = phClient.fetchPage(
            request = request,
            cacheTtlMs = PornHubClient.VIDEO_CACHE_TTL_MS,
            bucket = PornHubClient.RateBucket.VIDEO,
            highPriority = true,
        )
        var videos = extractor.extractVideos(pageResult, preferredFormat, preferredQuality)

        // If cached page yielded no MP4 (e.g., stale get_media token) or no videos at all, refresh once.
        val needsFreshMp4 = preferredFormat == PornHubExtractor.FORMAT_MP4_FIRST &&
            videos.none { it.quality.contains("MP4", ignoreCase = true) }
        if (wasCached && (videos.isEmpty() || needsFreshMp4)) {
            pageResult = phClient.fetchPage(
                request = request,
                cacheTtlMs = PornHubClient.VIDEO_CACHE_TTL_MS,
                bypassCache = true,
                bucket = PornHubClient.RateBucket.VIDEO,
                highPriority = true,
            )
            val refreshed = extractor.extractVideos(pageResult, preferredFormat, preferredQuality)
            if (refreshed.isNotEmpty()) {
                videos = refreshed
            }
        }

        if (videos.isEmpty()) {
            throw Exception("No playable video streams found for this video.")
        }
        if (isSmartTasteEnabled() && pageResult.html.isNotBlank()) {
            runCatching {
                val doc = Jsoup.parse(pageResult.html, pageResult.finalUrl)
                val details = parseDetailsDocument(doc, pageResult.html)
                val stars = doc.select("div.pornstarsWrapper a").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
                tasteEngine.recordPlay(
                    url = episode.url,
                    title = details.title,
                    tagsList = details.genre?.split(',')?.map(String::trim).orEmpty(),
                    performersList = (stars + listOfNotNull(details.artist)).distinct(),
                    studiosList = listOfNotNull(details.author),
                )
            }
        }
        wrapWithSeekCache(videos)
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = if (episode.url.startsWith("http", ignoreCase = true)) {
            episode.url
        } else {
            "$baseUrl${episode.url}"
        }
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val responseCookies = LinkedHashMap<String, String>()
        for (raw in response.headers("Set-Cookie")) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (value.isNotBlank() && !value.equals("deleted", ignoreCase = true)) {
                    responseCookies[name] = value
                }
            }
        }
        val pageResult = PornHubClient.PageResult(
            finalUrl = response.request.url.toString(),
            html = html,
            responseCookies = responseCookies,
            statusCode = response.code,
        )
        val preferredFormat = preferences.getString(PREF_FORMAT_KEY, PREF_FORMAT_DEFAULT) ?: PREF_FORMAT_DEFAULT
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val videos = extractor.extractVideos(pageResult, preferredFormat, preferredQuality)
        if (videos.isEmpty()) {
            throw Exception("No playable video streams found for this video.")
        }
        return wrapWithSeekCache(videos)
    }

    private val m3u8Server by lazy { M3u8ServerManager(client) }

    private fun wrapWithSeekCache(videos: List<Video>): List<Video> = if (preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)) {
        m3u8Server.wrapVideosForCache(
            videos = videos,
            cacheHls = true,
            cacheMp4 = true,
            defaultReferer = "$baseUrl/",
            defaultOrigin = baseUrl,
            defaultUserAgent = headers["User-Agent"],
        )
    } else {
        videos
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = PornHubFilters.createFilterList()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DEFAULT_FEED_KEY,
            title = "Default Popular feed",
            entries = PREF_DEFAULT_FEED_ENTRIES,
            entryValues = PREF_DEFAULT_FEED_VALUES,
            default = "ht",
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_FORMAT_KEY,
            title = PREF_FORMAT_TITLE,
            entries = PREF_FORMAT_ENTRIES,
            entryValues = PREF_FORMAT_VALUES,
            default = PREF_FORMAT_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched HLS segments and MP4 blocks locally (20 min TTL) for instant skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SMART_TASTE_KEY
            title = "Smart Taste learning & re-rank (shared)"
            summary = "Learns titles, tags & performers from Library updates, opens, and plays across extensions to power 'For You' and smart ranking."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TASTE_SEED_KEY
            title = "Seed taste from favorite titles / keywords"
            dialogTitle = "Paste favorite titles, stars, or keywords"
            dialogMessage = "Separate entries by commas or new lines. Learned profile is shared across Smart Taste extensions."
            summary = tasteEngine.summaryText()
            setOnPreferenceChangeListener { pref, newValue ->
                val text = newValue as? String
                tasteEngine.applySeedText(text)
                pref.summary = tasteEngine.summaryText()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TASTE_RESET_KEY
            title = "Reset learned Smart Taste profile"
            summary = "Tap to clear all learned titles, tags, stars, and watched history."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) {
                    tasteEngine.reset()
                    preferences.edit().remove(PREF_TASTE_SEED_KEY).apply()
                }
                false
            }
        }.also(screen::addPreference)
    }

    // ============================== Parsers ===============================

    private fun parseListingDocument(document: Document, isSearch: Boolean = false): AnimesPage {
        if (isSearch) {
            val docTitle = document.title()
            if (docTitle.contains("No results found", ignoreCase = true) ||
                docTitle.contains("Page Not Found", ignoreCase = true) ||
                document.selectFirst("div.noResults") != null
            ) {
                return AnimesPage(emptyList(), false)
            }
        }

        val candidates = if (isSearch) {
            document.select(SEARCH_CARD_SELECTOR).ifEmpty { document.select(LISTING_CARD_SELECTOR) }
        } else {
            document.select(LISTING_CARD_SELECTOR)
        }

        val animes = candidates
            .asSequence()
            .filter { el ->
                el.parents().none { parent ->
                    parent.id() in EXCLUDED_LIST_IDS ||
                        parent.hasClass("dropdownHottestVideos") ||
                        parent.hasClass("dropdownReccomendedVideos") ||
                        parent.hasClass("dropdownPlaylistVideos") ||
                        parent.hasClass("premiumStyle")
                }
            }
            .mapNotNull(::parseVideoCard)
            .distinctBy { it.url }
            .toList()

        val hasNext = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNext)
    }

    private fun parseVideoCard(element: Element): SAnime? {
        val link = element.selectFirst(
            "a.linkVideoThumb[href*=/view_video], div.phimage a[href*=/view_video], " +
                "a.js-videoPreview[href*=/view_video], a[href*=/view_video.php], a[href*=viewkey=]",
        ) ?: return null

        val href = link.attr("abs:href").ifBlank { link.attr("href") }
        if (href.isBlank() || "viewkey=" !in href) return null

        val titleLink = element.selectFirst(
            ".vidTitleWrapper a, span.title a, div.title a, a.thumbnailTitle",
        )
        val img = element.selectFirst("div.phimage img, img")

        val rawTitle = titleLink?.attr("title")?.trim()?.ifBlank { null }
            ?: titleLink?.text()?.trim()?.ifBlank { null }
            ?: link.attr("title").trim().ifBlank { null }
            ?: img?.attr("alt")?.trim()?.ifBlank { null }
            ?: img?.attr("title")?.trim()?.ifBlank { null }
            ?: return null

        val duration = element.selectFirst("var.duration, span.duration, .duration, span.video-duration")
            ?.text()
            ?.trim()
            .orEmpty()

        val (thumb, fallbackThumb) = img?.let(::extractListingThumbnails) ?: (null to null)
        phClient.rememberThumbnail(thumb, fallbackThumb)

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = if (duration.isNotBlank() && !rawTitle.contains(duration)) {
                "[$duration] $rawTitle"
            } else {
                rawTitle
            }
            thumbnail_url = thumb
        }
    }

    private fun parseDetailsDocument(document: Document, html: String): SAnime {
        val flashvars = extractor.extractFlashvarsObject(html)

        val videoTitle = document.selectFirst("h1.title span.inlineFree, h1.title, span.inlineFree")
            ?.text()?.trim()?.ifBlank { null }
            ?: flashvars?.get("video_title")?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "PornHub Video"

        val uploader = extractUploader(document)

        val views = document.selectFirst("div.ratingInfo div.views span.count, span.views .count")
            ?.text()?.trim().orEmpty()
        val rating = document.selectFirst("span.percent")?.text()?.trim().orEmpty()
        val durationSec = flashvars?.get("video_duration")?.jsonPrimitive?.intOrNull
        val metaDesc = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val descParts = mutableListOf<String>()
        if (!uploader.isNullOrBlank()) descParts.add("Uploader: $uploader")
        if (durationSec != null && durationSec > 0) descParts.add("Duration: ${formatDuration(durationSec)}")
        if (views.isNotBlank()) descParts.add("Views: $views")
        if (rating.isNotBlank()) descParts.add("Rating: $rating")
        if (!metaDesc.isNullOrBlank()) descParts.add(metaDesc)

        val genres = document.select(
            "div.categoriesWrapper a, div.tagsWrapper a, div.pornstarsWrapper a, a.js-categoryLink, a.js-tagLink",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .ifEmpty {
                CATEGORIES_SCRIPT_REGEX.find(html)?.groupValues?.getOrNull(1)
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.distinct()
                    .orEmpty()
            }
            .joinToString()
            .ifBlank { null }

        val cover = document.selectFirst("meta[name=twitter:image], meta[property=twitter:image]")
            ?.attr("content")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
            ?: flashvars?.get("image_url")?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
        phClient.rememberThumbnail(cover)

        return SAnime.create().apply {
            title = videoTitle
            author = uploader
            artist = uploader
            description = descParts.joinToString("\n").ifBlank { null }
            genre = genres
            thumbnail_url = cover
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    private fun extractUploader(document: Document): String? {
        document.selectFirst("script[type=application/ld+json]")?.data()?.let { ld ->
            JSON_LD_AUTHOR_REGEX.find(ld)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        return document.selectFirst(
            "div.video-detailed-info div.userInfo a.bolded, " +
                "div.userInfo .usernameBadgesWrapper a, " +
                "div.usernameWrap a.bolded, " +
                "div.userInfo a[href*=/model/], " +
                "div.userInfo a[href*=/pornstar/], " +
                "div.userInfo a[href*=/channels/], " +
                "a.bolded[href*=/users/]",
        )?.text()?.trim()?.ifBlank { null }
    }

    private fun extractListingThumbnails(img: Element): Pair<String?, String?> {
        val candidates = listOf(
            img.attr("abs:data-image"),
            img.attr("abs:data-mediumthumb"),
            img.attr("abs:data-thumb_url"),
            img.attr("abs:data-highres"),
            img.attr("abs:data-poster"),
            img.attr("abs:data-src"),
            img.attr("abs:src"),
        )
        val directUrl = candidates.firstOrNull { url ->
            url.isNotBlank() &&
                !url.startsWith("data:", ignoreCase = true) &&
                !url.contains("blank.gif", ignoreCase = true)
        }
        // If directUrl is already on the permanent `ei.phncdn.com` host, keep its exact valid frame index.
        if (directUrl != null && directUrl.toHttpUrlOrNull()?.host.equals(STABLE_IMAGE_HOST, ignoreCase = true)) {
            return directUrl to null
        }
        val stableUrl = stableThumbnailUrl(img.attr("data-path"))
        return if (stableUrl != null) {
            stableUrl to directUrl
        } else {
            directUrl to null
        }
    }

    private fun stableThumbnailUrl(dataPath: String?): String? {
        if (dataPath.isNullOrBlank()) return null
        val normalized = dataPath.replace("{index}", "0").let { raw ->
            when {
                raw.startsWith("https://", ignoreCase = true) -> raw
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> "https://$STABLE_IMAGE_HOST$raw"
                else -> raw
            }
        }
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        if (!parsed.host.equals(STABLE_IMAGE_HOST, ignoreCase = true)) return null
        val cleanPath = parsed.encodedPath.substringBefore("/plain/")
        if (!STABLE_IMAGE_PATH_REGEX.matches(cleanPath)) return null
        return parsed.newBuilder()
            .encodedPath(cleanPath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun extractPublishedDate(html: String): Long? {
        val raw = PUBLISHED_DATE_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            fmt.parse(raw)?.time
        }.getOrNull()
    }

    private fun formatDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ============================ URL Builders ============================

    private fun resolveTargetRoute(rawQuery: String, f: PornHubFilters.FilterData): String? {
        val lower = rawQuery.lowercase(Locale.ROOT)
        val prefixType = when {
            lower.startsWith("pornstar:") -> "pornstar"
            lower.startsWith("model:") -> "model"
            lower.startsWith("channel:") -> "channel"
            lower.startsWith("user:") -> "user"
            else -> null
        }

        val targetType: String
        val rawName: String
        when {
            prefixType != null -> {
                targetType = prefixType
                rawName = rawQuery.substringAfter(':').trim()
            }

            f.targetName.isNotBlank() -> {
                targetType = if (f.targetType == "video") "pornstar" else f.targetType
                rawName = f.targetName
            }

            f.targetType != "video" && rawQuery.isNotBlank() -> {
                targetType = f.targetType
                rawName = rawQuery
            }

            f.pornstar.isNotBlank() -> {
                return "pornstar/${f.pornstar}/videos"
            }

            else -> return null
        }

        val slug = rawName.lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, "-")
            .trim('-')
        if (slug.isBlank()) return null

        return when (targetType) {
            "pornstar" -> "pornstar/$slug/videos"
            "model" -> "model/$slug/videos"
            "channel" -> "channels/$slug/videos"
            else -> "users/$slug/videos/public"
        }
    }

    private fun browseUrl(
        page: Int,
        sort: String = "ht",
        category: String = "",
        orientation: String = "",
        production: String = "",
        duration: String = "",
        hd: String = "",
    ): String {
        val isPathCategory = category.startsWith("path:") && orientation.isBlank()
        val effectiveSort = when {
            category.isNotBlank() && (sort == "ht" || sort == "mr") -> ""
            category.isBlank() && sort == "mr" -> "ht"
            else -> sort
        }
        return baseUrl.toHttpUrl().newBuilder().apply {
            when {
                isPathCategory -> addPathSegments("categories/${category.removePrefix("path:")}")

                orientation == "gay" -> {
                    addPathSegments("gay/video")
                    if (category.isNotBlank() && !category.startsWith("path:")) addQueryParameter("c", category)
                }

                orientation == "transgender" -> {
                    addPathSegment("transgender")
                    if (category.isNotBlank() && !category.startsWith("path:")) addQueryParameter("c", category)
                }

                else -> {
                    addPathSegment("video")
                    if (category.isNotBlank()) addQueryParameter("c", category)
                }
            }
            if (effectiveSort.isNotBlank()) addQueryParameter("o", effectiveSort)
            if (production.isNotBlank()) addQueryParameter("p", production)
            if (hd.isNotBlank() && !isPathCategory) addQueryParameter("hd", "1")
            addQueryParameter("page", page.toString())
            applyDuration(duration)
        }.build().toString()
    }

    private fun HttpUrl.Builder.applyDuration(duration: String) {
        when {
            duration.startsWith("min:") -> addQueryParameter("min_duration", duration.removePrefix("min:"))
            duration.startsWith("max:") -> addQueryParameter("max_duration", duration.removePrefix("max:"))
        }
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val STABLE_IMAGE_HOST = "ei.phncdn.com"

        private const val LISTING_CARD_SELECTOR =
            "li.pcVideoListItem:not(.mockNsfwThumb), li.videoBox:not(.noVideo), " +
                "ul#videoCategory li.videoblock, ul#videoSearchResult li.videoblock, " +
                "li.videoWrapper:not(.noVideo), div.positionRelative.singleVideo, li[data-video-id]"
        private const val SEARCH_CARD_SELECTOR =
            "ul#videoSearchResult li.pcVideoListItem:not(.mockNsfwThumb), " +
                "ul#videoSearchResult li[data-video-id], ul#videoSearchResult li.videoBox, " +
                "div.searchPageVideos li.pcVideoListItem"
        private const val NEXT_PAGE_SELECTOR =
            "li.page_next:not(.page_disabled):not(.disabled) a[href], " +
                "div.page_next a[href], a.page_next:not(.disabled)[href], " +
                "a[rel=next]:not(.disabled), link[rel=next], " +
                "div.pagination li.active + li a[href]"

        private val EXCLUDED_LIST_IDS = setOf(
            "hottestMenuSection",
            "recommMenuSection",
            "bottomVideos",
            "uploadedVideosSection",
        )
        private val PROFILE_SORTS = setOf("mr", "mv", "tr", "lg")

        private val DURATION_PREFIX_REGEX = Regex("""^\[[^\]]+\]\s*""")
        private val SEARCH_SANITIZE_REGEX = Regex("""[/?#]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val STABLE_IMAGE_PATH_REGEX = Regex(""".*\.(?:jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
        private val JSON_LD_AUTHOR_REGEX = Regex(""""author"\s*:\s*"([^"]+)"""")
        private val CATEGORIES_SCRIPT_REGEX = Regex("""['"]categories_in_video['"]\s*:\s*['"]([^'"]+)['"]""")
        private val PUBLISHED_DATE_REGEX = Regex("""['"]video_date_published['"]\s*:\s*['"](\d{8})['"]""")

        private const val PREF_FORMAT_KEY = "preferred_stream_format"
        private const val PREF_FORMAT_TITLE = "Preferred stream format"
        private const val PREF_FORMAT_DEFAULT = PornHubExtractor.FORMAT_MP4_FIRST
        private val PREF_FORMAT_ENTRIES = listOf(
            "MP4 First (Recommended - Best Seeking)",
            "All Streams (MP4 + HLS)",
            "HLS Only",
        )
        private val PREF_FORMAT_VALUES = listOf(
            PornHubExtractor.FORMAT_MP4_FIRST,
            PornHubExtractor.FORMAT_ALL,
            PornHubExtractor.FORMAT_HLS_ONLY,
        )

        private const val PREF_DEFAULT_FEED_KEY = "pref_default_feed"
        private val PREF_DEFAULT_FEED_ENTRIES = listOf("Hot / Trending", "For You (Smart Taste)")
        private val PREF_DEFAULT_FEED_VALUES = listOf("ht", PornHubFilters.FOR_YOU_SORT)
        private const val PREF_SMART_TASTE_KEY = "pref_smart_taste_enabled"
        private const val PREF_TASTE_SEED_KEY = "pref_smart_taste_seed"
        private const val PREF_TASTE_RESET_KEY = "pref_smart_taste_reset"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "240p")
    }
}
