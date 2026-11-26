package eu.kanade.tachiyomi.animeextension.all.sextb

import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
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
import eu.kanade.tachiyomi.network.POST
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SexTB :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SexTB"

    override val baseUrl = "https://sextb.net"

    override val lang = "all"

    override val supportsLatest = true

    override val disableRelatedAnimes = true

    override val disableRelatedAnimesBySearch = true

    private val preferences by getPreferencesLazy()

    /*
     * Keep the app-provided Android User-Agent and cookie jar so Cloudflare
     * clearance cookies from WebView remain compatible with OkHttp requests.
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private val fastExtractorClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(11, TimeUnit.SECONDS)
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
    private val voeExtractor by lazy { VoeExtractor(fastExtractorClient, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(fastExtractorClient) }
    private val m3u8Server by lazy { M3u8ServerManager(streamProxyClient) }
    private val webBridge by lazy { SexTbWebBridge(baseUrl) }
    private val tasteEngine by lazy {
        SmartTasteEngine(preferences, sourceKey = "sextb").also { engine ->
            engine.applySeedText(preferences.getString(PREF_TASTE_SEED_KEY, null))
        }
    }

    // Switch to same-origin WebView fetch when OkHttp hits Cloudflare JA4 fingerprint mismatch.
    @Volatile
    private var webBridgeUntilMs: Long = 0L

    // Separate per-endpoint adaptive rate limiters so search traffic never blocks
    // listings, filters, video details, or /ajax/player playback requests.
    private enum class RateBucket(val normalGapMs: Long, val cooldownGapMs: Long) {
        LISTING(LISTING_REQUEST_GAP_MS, LISTING_COOLDOWN_GAP_MS),
        PLAYER(PLAYER_REQUEST_GAP_MS, PLAYER_COOLDOWN_GAP_MS),
        SEARCH(SEARCH_REQUEST_GAP_MS, SEARCH_COOLDOWN_GAP_MS),
    }

    private class BucketPacer(val bucket: RateBucket) {
        val lock = Any()
        val highPriorityWaiters = AtomicInteger(0)

        @Volatile
        var nextAllowedMs: Long = 0L

        @Volatile
        var cooldownUntilMs: Long = 0L
    }

    private val pacers = mapOf(
        RateBucket.LISTING to BucketPacer(RateBucket.LISTING),
        RateBucket.PLAYER to BucketPacer(RateBucket.PLAYER),
        RateBucket.SEARCH to BucketPacer(RateBucket.SEARCH),
    )

    private class InFlightGet {
        val latch = CountDownLatch(1)
        val result = AtomicReference<SiteResponse?>(null)
        val error = AtomicReference<Throwable?>(null)
    }

    private class InFlightSearch {
        val latch = CountDownLatch(1)
        val result = AtomicReference<AnimesPage?>(null)
        val error = AtomicReference<Throwable?>(null)
    }

    private val inFlightGets = ConcurrentHashMap<String, InFlightGet>()
    private val inFlightSearches = ConcurrentHashMap<String, InFlightSearch>()

    // Tracks URLs already returned per active search query across pages to prevent duplicate cards.
    private val seenSearchUrls = ConcurrentHashMap<String, MutableSet<String>>()

    // Persistent + in-memory caches so rotated SexTB hashes and actress paths survive app restarts.
    private val resolvedCatalogPaths: ConcurrentHashMap<String, String> by lazy {
        loadPersistedPathMap(PREF_CATALOG_PATH_CACHE_KEY)
    }
    private val resolvedActressPaths: ConcurrentHashMap<String, String> by lazy {
        loadPersistedPathMap(PREF_ACTRESS_PATH_CACHE_KEY)
    }
    private val pageCache = ConcurrentHashMap<String, Pair<Long, AnimesPage>>()
    private val videoPageCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val topChartPathCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun isSeekCacheEnabled(): Boolean = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
    private fun isSmartTasteEnabled(): Boolean = preferences.getBoolean(PREF_SMART_TASTE_KEY, true)
    private fun preferredServer(): String = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
    private fun preferredQuality(): String = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
    private fun defaultCategoryPath(): String = preferences.getString(PREF_DEFAULT_CATEGORY_KEY, DEFAULT_CATEGORY) ?: DEFAULT_CATEGORY

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val cat = defaultCategoryPath().let { if (it == FOR_YOU_SENTINEL) DEFAULT_CATEGORY else it }
        return catalogRequest(
            page = page,
            categoryPath = cat,
            sort = "viewed",
        )
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        if (isSmartTasteEnabled() && defaultCategoryPath() == FOR_YOU_SENTINEL && tasteEngine.hasLearnedProfile()) {
            return@withContext fetchForYouPage(page)
        }
        val request = popularAnimeRequest(page)
        applySmartRerank(executeCachedListing(request))
    }

    // Listing pages start with an ad-card tray-item linking to /advertise.
    override fun popularAnimeSelector(): String = "div.tray-item:not(.ad-card):has(a[href]):not(:has(a[href*=\"/advertise\"]))"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a[href]") ?: element
        setUrlWithoutDomain(link.attr("href"))

        val image: Element? = element.selectFirst("img")
        val rawTitle = element.selectFirst("div.tray-item-title")?.text()?.trim()
            ?.ifBlank { link.attr("title").trim() }
            ?.ifBlank { image?.attr("alt")?.trim() }
            ?: link.attr("title").trim().ifBlank { image?.attr("alt").orEmpty().trim() }

        if (image != null) {
            thumbnail_url = extractImageUrl(image)
        }

        val code = element.selectFirst("div.tray-item-code")?.text()?.trim().orEmpty()
        val duration = element.selectFirst("div.tray-item-runtime")?.text()?.trim().orEmpty()
        val ribbon = element.selectFirst("span.tray-item-ribbon")?.text()?.trim().orEmpty()

        val cleanedTitle = rawTitle.ifBlank { element.text().trim() }
        val cleanDur = duration.replace(Regex("""(?i)\s*mins?\b"""), "m").trim()
        title = buildString {
            when {
                code.isNotBlank() && cleanDur.isNotBlank() -> append("[").append(code).append(" • ").append(cleanDur).append("] ")
                cleanDur.isNotBlank() -> append("[").append(cleanDur).append("] ")
                code.isNotBlank() && !cleanedTitle.contains(code, ignoreCase = true) -> append("[").append(code).append("] ")
            }
            if (ribbon.isNotBlank() && !cleanedTitle.contains(ribbon, ignoreCase = true)) {
                append("[").append(ribbon).append("] ")
            }
            append(cleanedTitle)
        }.trim()
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:has(a.current) + li a[href], ul.pagination li.active + li a[href], ul.pagination a[rel=next], ul.pagination li.next a[href], a[rel=next]"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        response.rememberCatalogPath()
        val finalUrl = response.request.url.toString()
        val html = response.body.string()
        if (html.isCloudflareChallengeHtml()) throw CloudflareBlockedException()
        return parseListingDocument(Jsoup.parse(html, finalUrl))
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val cat = defaultCategoryPath().let { if (it == FOR_YOU_SENTINEL) DEFAULT_CATEGORY else it }
        return catalogRequest(
            page = page,
            categoryPath = cat,
            sort = "desc",
        )
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        val request = latestUpdatesRequest(page)
        applySmartRerank(executeCachedListing(request))
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val cleanQuery = query.replace(BRACKET_TAG_REGEX, "").trim()
        val searchMode = filters.filterIsInstance<SearchModeFilter>().firstOrNull()?.selected ?: "movie"

        if (cleanQuery.isNotBlank()) {
            // Skip synthetic metadata queries emitted by host recommendation fallbacks
            // (e.g. "Label: Moodyz Gati" scraped from our own description text).
            if (cleanQuery.startsWith("Label:", ignoreCase = true) ||
                cleanQuery.startsWith("Studio:", ignoreCase = true) ||
                cleanQuery.startsWith("Release Date:", ignoreCase = true)
            ) {
                return@withContext AnimesPage(emptyList(), false)
            }
            if (searchMode == "actor") {
                if (page == 1 && isSmartTasteEnabled()) {
                    tasteEngine.recordSearchOrFilter(performersList = cleanQuery.split(',').map(String::trim))
                }
                return@withContext searchByActressQuery(page, cleanQuery, filters)
            }
            if (page == 1 && isSmartTasteEnabled() && cleanQuery.length <= 50) {
                tasteEngine.recordSearchOrFilter(query = cleanQuery)
            }
            return@withContext searchByMovieQuery(page, cleanQuery)
        }

        if (page == 1 && isSmartTasteEnabled()) {
            val params = extractCommonFilterParams(filters)
            val checkedCats = extractCheckedCategories(filters).filterNot { it == FOR_YOU_SENTINEL || it == TOP_WEEK_SENTINEL || it == TOP_MONTH_SENTINEL }
            val checkedActressNames = filters.filterIsInstance<ActressGroup>().firstOrNull()
                ?.state?.filter { it.state }?.map { it.name }.orEmpty()
            val typedActressNames = filters.filterIsInstance<ActressTextFilter>().firstOrNull()
                ?.state?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
            tasteEngine.recordSearchOrFilter(
                tagsList = params.selectedGenres.map { it.replace('-', ' ') },
                performersList = checkedActressNames + typedActressNames,
                studiosList = params.selectedStudios.map { it.replace('-', ' ') },
                categoriesList = checkedCats,
            )
        }

        val plan = buildFilterPlan(page, filters)
        val result = executeFilterPlan(plan)
        if (plan.targets.any { it is FilterTarget.ForYou }) result else applySmartRerank(result)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(BRACKET_TAG_REGEX, "").trim()
        if (cleanQuery.isNotBlank()) {
            return buildMovieSearchRequest(page, cleanQuery)
        }
        val first = buildFilterPlan(page, filters).targets.firstOrNull()
        return (first as? FilterTarget.Catalog)?.request ?: catalogRequest(page, defaultCategoryPath())
    }

    private fun buildMovieSearchRequest(page: Int, query: String): Request {
        val slug = toSearchSlug(query)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment(slug)
            .apply {
                if (page > 1) addPathSegment("pg-${page.coerceAtLeast(1)}")
            }
            .build()
        return GET(url, headers)
    }

    private fun normalizeSearchCacheKey(query: String): String = query
        .lowercase(Locale.ROOT)
        .replace(SEARCH_KEY_NOISE_REGEX, " ")
        .trim()
        .ifBlank { query.trim().lowercase(Locale.ROOT) }

    private fun deduplicateSearchPage(normQuery: String, page: Int, rawPage: AnimesPage): AnimesPage {
        if (page <= 1) {
            if (seenSearchUrls.size > 40) seenSearchUrls.clear()
            val freshSet = java.util.Collections.synchronizedSet(HashSet<String>())
            val unique = rawPage.animes.filter { freshSet.add(it.url.lowercase(Locale.ROOT)) }
            seenSearchUrls[normQuery] = freshSet
            return AnimesPage(unique, rawPage.hasNextPage && unique.isNotEmpty())
        }
        val seen = seenSearchUrls.computeIfAbsent(normQuery) {
            java.util.Collections.synchronizedSet(HashSet())
        }
        val unique = rawPage.animes.filter { seen.add(it.url.lowercase(Locale.ROOT)) }
        return AnimesPage(unique, rawPage.hasNextPage && unique.isNotEmpty())
    }

    private fun searchByMovieQuery(page: Int, query: String): AnimesPage {
        val normQuery = normalizeSearchCacheKey(query)
        val cacheKey = "ajax-search:$normQuery:$page"
        val now = System.currentTimeMillis()
        pageCache[cacheKey]?.takeIf { (ts, cachedPage) ->
            val ttl = if (cachedPage.animes.isEmpty()) EMPTY_PAGE_CACHE_TTL_MS else PAGE_CACHE_TTL_MS
            now - ts < ttl
        }?.let { return deduplicateSearchPage(normQuery, page, it.second) }

        // Single-flight coalescing on normalized query+page so identical/near-identical
        // concurrent searches never fire duplicate network calls.
        val newHolder = InFlightSearch()
        val existing = inFlightSearches.putIfAbsent(cacheKey, newHolder)
        if (existing != null) {
            try {
                if (!existing.latch.await(SINGLE_FLIGHT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("SexTB timed out waiting for coalesced search '$query'")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("SexTB search interrupted for '$query'", e)
            }
            existing.result.get()?.let { return deduplicateSearchPage(normQuery, page, it) }
            val err = existing.error.get()
            if (err is IOException) throw err
            throw IOException(err?.message ?: "SexTB coalesced search failed for '$query'", err)
        }

        try {
            // Prefer SexTB's lightweight JSON /ajax/search endpoint (~30KB, much higher rate limit
            // than HTML /search/<slug> which is capped at ~4 req/min with a 60s window).
            val ajaxResult = try {
                searchViaAjaxMovies(query, page)
            } catch (e: CloudflareBlockedException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "/ajax/search failed for '$query': ${e.message}")
                null
            }

            val rawPage = if (ajaxResult != null) {
                ajaxResult
            } else if (query.length <= 70) {
                // Fallback to HTML /search/<slug> only for concise queries when /ajax/search failed.
                val primaryReq = buildMovieSearchRequest(page, query)
                try {
                    executeCachedListing(primaryReq, highPriority = false, bucket = RateBucket.SEARCH)
                } catch (e: HttpStatusException) {
                    if (e.code == 404) AnimesPage(emptyList(), false) else throw e
                }
            } else {
                AnimesPage(emptyList(), false)
            }

            if (pageCache.size > 100) pageCache.clear()
            pageCache[cacheKey] = System.currentTimeMillis() to rawPage
            newHolder.result.set(rawPage)
            return deduplicateSearchPage(normQuery, page, rawPage)
        } catch (t: Throwable) {
            newHolder.error.set(t)
            throw t
        } finally {
            newHolder.latch.countDown()
            inFlightSearches.remove(cacheKey, newHolder)
        }
    }

    private fun searchViaAjaxMovies(query: String, page: Int): AnimesPage? {
        val curPage = page.coerceAtLeast(1)
        val resp = executeSitePost(
            url = "$baseUrl/ajax/search",
            formParams = listOf(
                "q" to query,
                "page" to curPage.toString(),
                "type" to "movie",
            ),
            referer = "$baseUrl/",
            highPriority = false,
            bucket = RateBucket.SEARCH,
        )
        if (resp.status !in 200..299) return null
        val json = JSONObject(resp.body)
        val hits = json.optJSONArray("hits") ?: return AnimesPage(emptyList(), false)
        val animes = buildList {
            for (i in 0 until hits.length()) {
                val obj = hits.optJSONObject(i) ?: continue
                val code = obj.optString("code").trim().lowercase(Locale.ROOT)
                if (code.isBlank()) continue
                val rawName = Jsoup.parse(obj.optString("name")).text().trim()
                val runtime = obj.optString("runtimes").trim()
                val isLtx = obj.optBoolean("ltx", false)
                val version = obj.optString("version").trim()
                val poster = obj.optString("poster").trim()
                add(
                    SAnime.create().apply {
                        setUrlWithoutDomain("/$code")
                        title = buildString {
                            val upper = code.uppercase(Locale.ROOT)
                            val durBadge = if (runtime.isNotBlank() && runtime != "0") "${runtime}m" else ""
                            when {
                                durBadge.isNotBlank() -> append("[").append(upper).append(" • ").append(durBadge).append("] ")
                                !rawName.contains(upper, ignoreCase = true) -> append("[").append(upper).append("] ")
                            }
                            if (version.isNotBlank() && !rawName.contains(version, ignoreCase = true)) {
                                append("[").append(version).append("] ")
                            } else if (isLtx && !rawName.contains("LTX", ignoreCase = true)) {
                                append("[LTX] ")
                            }
                            append(rawName.ifBlank { upper })
                        }.trim()
                        thumbnail_url = normalizeUrl(poster)
                    },
                )
            }
        }.distinctBy { it.url.lowercase(Locale.ROOT) }
        val totalPages = json.optInt("totalPages", 1)
        val hasNext = animes.isNotEmpty() && curPage < totalPages
        Log.i(TAG, "AjaxSearch q='$query' p=$curPage/$totalPages count=${animes.size} viaWeb=${resp.viaWebView}")
        return AnimesPage(animes, hasNext)
    }

    private fun searchByActressQuery(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val names = query.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_MULTI_TARGETS)
        val actressPaths = names.mapNotNull { resolveActressPath(it) }.distinct()
        if (actressPaths.isEmpty()) {
            return searchByMovieQuery(page, query)
        }
        val params = extractCommonFilterParams(filters)
        val checkedCategories = extractCheckedCategories(filters)
        val targets = buildActressTargets(page, actressPaths, checkedCategories, params)
        return executeFilterPlan(FilterPlan(targets = targets, boostOverlap = actressPaths.size > 1))
    }

    private fun resolveActressPath(nameQuery: String): String? {
        val key = nameQuery.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        resolvedActressPaths[key]?.let { return it }

        // First query SexTB's live actor search endpoint to get exact (possibly hashed) actress URL.
        val ajaxPath = runCatching {
            val resp = executeSitePost(
                url = "$baseUrl/ajax/search",
                formParams = listOf(
                    "q" to nameQuery.trim(),
                    "page" to "1",
                    "type" to "actor",
                ),
                referer = "$baseUrl/",
                highPriority = true,
                bucket = RateBucket.SEARCH,
            )
            if (resp.status !in 200..299) return@runCatching null
            val json = JSONObject(resp.body)
            val hits = json.optJSONArray("hits") ?: return@runCatching null
            var firstUrl: String? = null
            for (i in 0 until hits.length()) {
                val item = hits.optJSONObject(i) ?: continue
                val u = item.optString("url").trim()
                if (!u.startsWith("/actress/")) continue
                if (firstUrl == null) firstUrl = u
                val hitName = item.optString("name").trim()
                if (hitName.equals(nameQuery.trim(), ignoreCase = true)) {
                    return@runCatching u
                }
            }
            firstUrl
        }.getOrNull()

        val resolved = ajaxPath ?: run {
            val slug = key.replace(NON_SLUG_REGEX, "-").trim('-')
            if (slug.isBlank()) null else "/actress/$slug"
        }
        if (resolved != null) {
            if (resolvedActressPaths.size >= MAX_PERSISTED_ACTRESS_PATHS) resolvedActressPaths.clear()
            val prev = resolvedActressPaths.put(key, resolved)
            if (prev != resolved) {
                persistPathMap(PREF_ACTRESS_PATH_CACHE_KEY, resolvedActressPaths)
            }
        }
        return resolved
    }

    private data class CommonFilterParams(
        val selectedGenres: List<String>,
        val selectedStudios: List<String>,
        val genreParam: String,
        val studioParam: String,
        val quality: String,
        val year: String,
        val sort: String,
    )

    private enum class CardMode {
        ALL,
        CENSORED_ONLY,
        UNCENSORED_ONLY,
    }

    private sealed class FilterTarget {
        data class Catalog(
            val request: Request,
            val cardMode: CardMode = CardMode.ALL,
        ) : FilterTarget()

        data class TopChart(val kind: String, val page: Int) : FilterTarget()
        data class ForYou(val page: Int) : FilterTarget()
    }

    private data class FilterPlan(
        val targets: List<FilterTarget>,
        val boostOverlap: Boolean = false,
    )

    private fun extractCheckedCategories(filters: AnimeFilterList): List<String> = filters.filterIsInstance<CategoryGroup>().firstOrNull()
        ?.state
        ?.filter { it.state }
        ?.map { it.path }
        ?.distinct()
        ?.take(MAX_MULTI_TARGETS)
        .orEmpty()

    private fun extractCommonFilterParams(filters: AnimeFilterList): CommonFilterParams {
        val quality = filters.filterIsInstance<QualityFilter>().firstOrNull()?.selected ?: "all"
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.selected ?: "all"
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: "desc"

        val selectedGenres = filters.filterIsInstance<GenreGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.slug }
            ?.distinct()
            ?.take(MAX_MULTI_SELECT)
            .orEmpty()
        val genreParam = if (selectedGenres.isEmpty()) "all" else selectedGenres.joinToString(",")

        val selectedStudios = filters.filterIsInstance<StudioGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.slug }
            ?.distinct()
            ?.take(MAX_MULTI_SELECT)
            .orEmpty()
        val studioParam = if (selectedStudios.isEmpty()) "all" else selectedStudios.joinToString(",")

        return CommonFilterParams(
            selectedGenres = selectedGenres,
            selectedStudios = selectedStudios,
            genreParam = genreParam,
            studioParam = studioParam,
            quality = quality,
            year = year,
            sort = sort,
        )
    }

    private fun buildActressTargets(
        page: Int,
        actressPaths: List<String>,
        checkedCategories: List<String>,
        params: CommonFilterParams,
    ): List<FilterTarget> {
        if (checkedCategories.isEmpty()) {
            return actressPaths.map { path ->
                FilterTarget.Catalog(
                    catalogRequest(
                        page = page,
                        categoryPath = path,
                        genre = params.genreParam,
                        studio = params.studioParam,
                        quality = params.quality,
                        year = params.year,
                        sort = params.sort,
                    ),
                )
            }
        }

        val wantsCensored = "/jav-censored" in checkedCategories
        val wantsUncensored = "/jav-uncensored" in checkedCategories
        val genreCategorySlugs = checkedCategories.mapNotNull { CATEGORY_TO_GENRE_SLUG[it] }.distinct()

        val targets = mutableListOf<FilterTarget>()
        for (actressPath in actressPaths) {
            if (wantsCensored || wantsUncensored) {
                val mode = when {
                    wantsCensored && wantsUncensored -> CardMode.ALL
                    wantsCensored -> CardMode.CENSORED_ONLY
                    else -> CardMode.UNCENSORED_ONLY
                }
                targets += FilterTarget.Catalog(
                    request = catalogRequest(
                        page = page,
                        categoryPath = actressPath,
                        genre = params.genreParam,
                        studio = params.studioParam,
                        quality = params.quality,
                        year = params.year,
                        sort = params.sort,
                    ),
                    cardMode = mode,
                )
            }
            for (catGenre in genreCategorySlugs) {
                val combinedGenres = (listOf(catGenre) + params.selectedGenres).distinct().take(MAX_MULTI_SELECT)
                targets += FilterTarget.Catalog(
                    request = catalogRequest(
                        page = page,
                        categoryPath = actressPath,
                        genre = combinedGenres.joinToString(","),
                        studio = params.studioParam,
                        quality = params.quality,
                        year = params.year,
                        sort = params.sort,
                    ),
                )
            }
            if (!wantsCensored && !wantsUncensored && genreCategorySlugs.isEmpty()) {
                targets += FilterTarget.Catalog(
                    catalogRequest(
                        page = page,
                        categoryPath = actressPath,
                        genre = params.genreParam,
                        studio = params.studioParam,
                        quality = params.quality,
                        year = params.year,
                        sort = params.sort,
                    ),
                )
            }
        }
        return targets.distinctBy { (it as? FilterTarget.Catalog)?.let { c -> c.request.url.toString() to c.cardMode } ?: it }
            .take(MAX_MULTI_TARGETS)
    }

    private fun buildFilterPlan(page: Int, filters: AnimeFilterList): FilterPlan {
        val params = extractCommonFilterParams(filters)

        val checkedActresses = filters.filterIsInstance<ActressGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.path }
            .orEmpty()
        val typedActresses = filters.filterIsInstance<ActressTextFilter>().firstOrNull()
            ?.state
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.mapNotNull { resolveActressPath(it) }
            .orEmpty()
        val actressPaths = (checkedActresses + typedActresses).distinct().take(MAX_MULTI_TARGETS)
        val checkedCategories = extractCheckedCategories(filters)

        // 1. Actress(es) selected — combine with any checked categories, genres, studios, quality, year, sort.
        if (actressPaths.isNotEmpty()) {
            return FilterPlan(
                targets = buildActressTargets(page, actressPaths, checkedCategories, params),
                boostOverlap = actressPaths.size > 1,
            )
        }

        // 2. One or more Categories / Charts selected — fetch ALL selected categories cleanly
        //    and round-robin interleave so every selected category appears evenly.
        if (checkedCategories.isNotEmpty()) {
            val targets = checkedCategories.map { path ->
                when (path) {
                    FOR_YOU_SENTINEL -> FilterTarget.ForYou(page)

                    TOP_WEEK_SENTINEL -> FilterTarget.TopChart("week", page)

                    TOP_MONTH_SENTINEL -> FilterTarget.TopChart("month", page)

                    else -> {
                        // Avoid redundant self-genre intersection (e.g. /genre/reducing-mosaic?genre=reducing-mosaic).
                        val ownSlug = CATEGORY_TO_GENRE_SLUG[path]
                        val filteredGenres = if (ownSlug != null) {
                            params.selectedGenres.filterNot { it == ownSlug }
                        } else {
                            params.selectedGenres
                        }
                        val effectiveGenre = if (filteredGenres.isEmpty()) "all" else filteredGenres.joinToString(",")
                        FilterTarget.Catalog(
                            catalogRequest(
                                page = page,
                                categoryPath = path,
                                genre = effectiveGenre,
                                studio = params.studioParam,
                                quality = params.quality,
                                year = params.year,
                                sort = params.sort,
                            ),
                        )
                    }
                }
            }
            return FilterPlan(targets = targets, boostOverlap = false)
        }

        // 3. No Category checked, 1+ Genres checked -> browse `/genre/<first>` directly
        //    with any extra genres as `?genre=<rest>` so results span ALL categories
        //    (Censored + Uncensored + LTX + Amateur + Subtitle) instead of defaulting to LTX-only!
        if (params.selectedGenres.isNotEmpty()) {
            val primaryGenre = params.selectedGenres.first()
            val extraGenres = params.selectedGenres.drop(1)
            return FilterPlan(
                targets = listOf(
                    FilterTarget.Catalog(
                        catalogRequest(
                            page = page,
                            categoryPath = "/genre/$primaryGenre",
                            genre = if (extraGenres.isEmpty()) "all" else extraGenres.joinToString(","),
                            studio = params.studioParam,
                            quality = params.quality,
                            year = params.year,
                            sort = params.sort,
                        ),
                    ),
                ),
            )
        }

        // 4. No Category & no Genre checked, 1+ Studios checked -> browse `/studio/<slug>`
        //    directly across ALL categories instead of restricting to defaultCategoryPath().
        if (params.selectedStudios.isNotEmpty()) {
            val targets = params.selectedStudios.take(MAX_MULTI_TARGETS).map { studioSlug ->
                FilterTarget.Catalog(
                    catalogRequest(
                        page = page,
                        categoryPath = "/studio/$studioSlug",
                        genre = "all",
                        studio = "all",
                        quality = params.quality,
                        year = params.year,
                        sort = params.sort,
                    ),
                )
            }
            return FilterPlan(targets = targets, boostOverlap = false)
        }

        // 5. Only Sort / Quality / Year changed with no category/genre/studio/actress -> default category.
        return FilterPlan(
            targets = listOf(
                FilterTarget.Catalog(
                    catalogRequest(
                        page = page,
                        categoryPath = defaultCategoryPath(),
                        genre = "all",
                        studio = "all",
                        quality = params.quality,
                        year = params.year,
                        sort = params.sort,
                    ),
                ),
            ),
        )
    }

    private fun executeFilterPlan(plan: FilterPlan): AnimesPage {
        if (plan.targets.isEmpty()) {
            return executeCachedListing(catalogRequest(1, defaultCategoryPath()))
        }
        if (plan.targets.size == 1) {
            return executeFilterTarget(plan.targets.first())
        }

        // Execute multi-target filters sequentially through the adaptive site pacer instead
        // of firing a parallel burst that triggers HTTP 429 and silently drops categories.
        val pages = mutableListOf<AnimesPage>()
        var firstError: Throwable? = null
        for (target in plan.targets) {
            try {
                pages += executeFilterTarget(target)
            } catch (e: CloudflareBlockedException) {
                throw e
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                Log.w(TAG, "Multi-filter target failed ($target): ${e.message}")
            }
        }
        if (pages.isEmpty()) {
            throw firstError ?: IOException("SexTB returned no results for the selected filters.")
        }
        return if (pages.size == 1) pages.first() else mergePages(pages, boostOverlap = plan.boostOverlap)
    }

    private fun executeFilterTarget(target: FilterTarget): AnimesPage = when (target) {
        is FilterTarget.Catalog -> executeCachedListing(target.request, target.cardMode)
        is FilterTarget.TopChart -> fetchTopChart(target.kind, target.page)
        is FilterTarget.ForYou -> fetchForYouPage(target.page)
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

    private fun findSexTbGenreSlug(tag: String): String? {
        val needle = tag.trim().lowercase(Locale.ROOT)
        if (needle.isBlank()) return null
        if (needle == "threesome" || needle == "foursome" || needle == "3some" || needle == "4some") {
            return "threesome--foursome"
        }
        return GENRES.firstOrNull { (name, slug) ->
            name.equals(needle, ignoreCase = true) ||
                slug.equals(needle.replace(' ', '-'), ignoreCase = true) ||
                name.lowercase(Locale.ROOT).contains(needle)
        }?.second
    }

    private fun fetchForYouPage(page: Int): AnimesPage {
        val snap = tasteEngine.snapshot()
        val combined = mutableListOf<SAnime>()
        val seen = HashSet<String>()
        var anyNext = false
        var sourcesFetched = 0

        fun collect(ap: AnimesPage?) {
            if (ap == null) return
            if (ap.hasNextPage) anyNext = true
            for (item in ap.animes) {
                if (seen.add(item.url.lowercase(Locale.ROOT))) combined += item
            }
            if (ap.animes.isNotEmpty()) sourcesFetched++
        }

        // 1. Top learned CORE concepts mapped directly to native SexTB genre pages (e.g. threesome--foursome)!
        val matchedGenres = (snap.topConcepts + snap.topTags)
            .mapNotNull(::findSexTbGenreSlug)
            .distinct()
            .take(2)
        if (matchedGenres.isNotEmpty()) {
            val primaryReq = catalogRequest(page = page, categoryPath = "/genre/${matchedGenres.first()}", sort = "viewed")
            collect(runCatching { executeCachedListing(primaryReq) }.getOrNull())
            if (matchedGenres.size > 1) {
                val combinedReq = catalogRequest(
                    page = page,
                    categoryPath = "/genre/${matchedGenres.first()}",
                    genre = matchedGenres[1],
                    sort = "viewed",
                )
                collect(runCatching { executeCachedListing(combinedReq) }.getOrNull())
            }
        }

        // 2. Top learned Actress (resolved via cached actress path or live actor lookup).
        val topActress = snap.topPerformers.firstOrNull()
        if (!topActress.isNullOrBlank() && sourcesFetched < 3) {
            val actressPath = runCatching { resolveActressPath(topActress) }.getOrNull()
            if (actressPath != null) {
                val req = catalogRequest(page = page, categoryPath = actressPath, sort = "desc")
                collect(runCatching { executeCachedListing(req) }.getOrNull())
            }
        }

        // 3. Top learned core concept via fast JSON /ajax/search!
        val titleQuery = tasteEngine.buildTitleSearchQueries(maxQueries = 1).firstOrNull()
        if (!titleQuery.isNullOrBlank() && sourcesFetched < 3) {
            collect(runCatching { searchViaAjaxMovies(titleQuery, page) }.getOrNull())
        }

        // 4. Fallback to preferred/learned SexTB category (e.g. LTX Decensored) if profile is still young.
        if (combined.size < 10) {
            val fallbackCat = snap.topCategories.firstOrNull()
                ?: defaultCategoryPath().takeUnless { it == FOR_YOU_SENTINEL }
                ?: DEFAULT_CATEGORY
            collect(runCatching { executeCachedListing(catalogRequest(page = page, categoryPath = fallbackCat, sort = "viewed")) }.getOrNull())
        }

        val ranked = tasteEngine.rankCandidates(combined, excludeWatched = true, blendWithOriginalRank = false)
        return AnimesPage(ranked, anyNext)
    }

    private fun fetchTopChart(kind: String, page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val cachedChartPath = topChartPathCache[kind]?.takeIf {
            System.currentTimeMillis() - it.first < TOP_CHART_CACHE_TTL_MS
        }?.second

        val chartPath = cachedChartPath ?: run {
            val siteResp = executeSiteGet(GET("$baseUrl/top", headers))
            val topDoc = Jsoup.parse(siteResp.body, siteResp.finalUrl)
            val prefix = if (kind == "month") "/top/month/" else "/top/week/"
            topDoc.selectFirst("a[href^=\"$prefix\"]")?.attr("href")?.trim()?.also { found ->
                topChartPathCache[kind] = System.currentTimeMillis() to found
            } ?: "/top"
        }

        return executeCachedListing(GET("$baseUrl$chartPath", headers))
    }

    private fun executeCachedListing(
        request: Request,
        cardMode: CardMode = CardMode.ALL,
        highPriority: Boolean = true,
        bucket: RateBucket = RateBucket.LISTING,
    ): AnimesPage {
        val key = if (cardMode == CardMode.ALL) request.url.toString() else "${request.url}#${cardMode.name}"
        val now = System.currentTimeMillis()
        val cached = pageCache[key]?.takeIf { (ts, page) ->
            val ttl = if (page.animes.isEmpty()) EMPTY_PAGE_CACHE_TTL_MS else PAGE_CACHE_TTL_MS
            now - ts < ttl
        }
        if (cached != null) return cached.second

        val siteResp = try {
            executeSiteGet(request, highPriority = highPriority, bucket = bucket)
        } catch (e: HttpStatusException) {
            // Self-heal if a persisted hashed catalog path rotated and returned HTTP 404.
            val healedReq = if (e.code == 404) evictStaleHashedPath(request) else null
            if (healedReq != null) {
                executeSiteGet(healedReq, highPriority = highPriority, bucket = bucket)
            } else {
                throw e
            }
        }
        rememberCatalogPathFromUrl(siteResp.finalUrl)
        val doc = Jsoup.parse(siteResp.body, siteResp.finalUrl)
        harvestHashedCatalogLinks(doc)
        val result = parseListingDocument(doc, cardMode)
        Log.i(TAG, "Listing parsed url=${siteResp.finalUrl} mode=$cardMode count=${result.animes.size} hasNext=${result.hasNextPage} viaWeb=${siteResp.viaWebView}")
        if (pageCache.size > 100) pageCache.clear()
        pageCache[key] = System.currentTimeMillis() to result
        return result
    }

    private fun isUncensoredCard(element: Element): Boolean {
        if (element.hasClass("tray-item-uncensored")) return true
        val ribbon = element.selectFirst("span.tray-item-ribbon")?.text()?.trim().orEmpty()
        if (ribbon.contains("Decensored", ignoreCase = true) ||
            ribbon.contains("Uncensored", ignoreCase = true) ||
            ribbon.contains("LTX", ignoreCase = true)
        ) {
            return true
        }
        val code = element.selectFirst("div.tray-item-code")?.text()?.trim().orEmpty()
        return code.endsWith("-RM", ignoreCase = true) || code.endsWith("-UNC", ignoreCase = true)
    }

    private fun parseListingDocument(document: Document, cardMode: CardMode = CardMode.ALL): AnimesPage {
        // Ignore secondary "Recommended Videos For You" tray shown when a search has 0 real hits.
        val traySections = document.select("section.tray")
            .filterNot { section ->
                val heading = section.selectFirst("div.tray-title")?.text().orEmpty()
                heading.contains("Recommended Videos", ignoreCase = true) ||
                    heading.contains("Related Actress", ignoreCase = true)
            }

        val rawTrayItems = if (traySections.isNotEmpty()) {
            traySections.flatMap { it.select(popularAnimeSelector()) }
        } else {
            document.select(popularAnimeSelector())
        }

        if (rawTrayItems.isNotEmpty()) {
            val filteredItems = when (cardMode) {
                CardMode.ALL -> rawTrayItems
                CardMode.CENSORED_ONLY -> rawTrayItems.filterNot(::isUncensoredCard)
                CardMode.UNCENSORED_ONLY -> rawTrayItems.filter(::isUncensoredCard)
            }
            val animes = filteredItems.map(::popularAnimeFromElement)
            val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
            return AnimesPage(animes, hasNext)
        }

        // TOP Charts pages use `<li class="top-row">` instead of `div.tray-item`.
        val topRows = document.select("li.top-row:has(a.top-code, a.top-thumb)")
        if (topRows.isNotEmpty()) {
            val animes = topRows.map { row ->
                val link = row.selectFirst("a.top-code, a.top-thumb")!!
                val rank = row.selectFirst("span.top-rank")?.text()?.trim().orEmpty()
                val code = row.selectFirst("a.top-code")?.text()?.trim().orEmpty()
                val name = row.selectFirst("span.top-name")?.text()?.trim().orEmpty()
                val img = row.selectFirst("a.top-thumb img")
                SAnime.create().apply {
                    setUrlWithoutDomain(link.attr("href"))
                    title = buildString {
                        if (rank.isNotBlank()) append("#").append(rank).append(" ")
                        if (code.isNotBlank()) append("[").append(code).append("] ")
                        append(name.ifBlank { code })
                    }.trim()
                    if (img != null) thumbnail_url = extractImageUrl(img)
                }
            }
            return AnimesPage(animes, false)
        }

        return AnimesPage(emptyList(), false)
    }

    /**
     * Merges results from multiple selected categories, studios, or actresses:
     * - For categories/studios (`boostOverlap = false`): strict round-robin interleaving
     *   so every selected category appears evenly right from the top.
     * - For actresses (`boostOverlap = true`): co-starring videos matching more than one
     *   selected actress rank first, followed by round-robin interleaved results.
     */
    private fun mergePages(pages: List<AnimesPage>, boostOverlap: Boolean = false): AnimesPage {
        val hitCounts = if (boostOverlap) {
            HashMap<String, Int>().also { map ->
                for (p in pages) {
                    for (anime in p.animes) {
                        map[anime.url] = (map[anime.url] ?: 0) + 1
                    }
                }
            }
        } else {
            null
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
        val finalList = if (hitCounts != null) {
            interleaved.sortedByDescending { hitCounts[it.url] ?: 1 }
        } else {
            interleaved
        }
        return AnimesPage(finalList, pages.any { it.hasNextPage })
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
        val normalizedPath = categoryPath.trimEnd('/')
        val resolvedPath = resolvedCatalogPaths[normalizedPath] ?: normalizedPath
        val pagedPath = if (page > 1 && resolvedPath != "/new-releases") {
            "$resolvedPath/pg-${page.coerceAtLeast(1)}"
        } else {
            resolvedPath
        }
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

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SearchModeFilter(),
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

    class SearchModeFilter : UriPartFilter("Search Mode (when typing query)", SEARCH_MODES)
    class QualityFilter : UriPartFilter("Quality", QUALITIES)
    class YearFilter : UriPartFilter("Year", YEARS)
    class SortFilter : UriPartFilter("Sort By", SORTS)
    class ActressTextFilter : AnimeFilter.Text("Actress Names (comma-separated)")

    class CategoryCheckBox(name: String, val path: String) : AnimeFilter.CheckBox(name)
    class CategoryGroup(categories: List<CategoryCheckBox>) : AnimeFilter.Group<CategoryCheckBox>("Categories & Charts (multi-select)", categories)

    class GenreCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    class GenreGroup(genres: List<GenreCheckBox>) : AnimeFilter.Group<GenreCheckBox>("Genres (up to 5)", genres)

    class StudioCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)
    class StudioGroup(studios: List<StudioCheckBox>) : AnimeFilter.Group<StudioCheckBox>("Studios (up to 5)", studios)

    class ActressCheckBox(name: String, val path: String) : AnimeFilter.CheckBox(name)
    class ActressGroup(actresses: List<ActressCheckBox>) : AnimeFilter.Group<ActressCheckBox>("Top Actresses (multi-select)", actresses)

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val request = animeDetailsRequest(anime)
        val urlKey = request.url.toString()
        val html = fetchAndCacheVideoPage(request, urlKey)
        val doc = Jsoup.parse(html, urlKey)
        val details = animeDetailsParse(doc)
        if (isSmartTasteEnabled()) {
            val studio = doc.metadataText("Studio:")
            tasteEngine.recordBrowse(
                url = anime.url,
                title = details.title.ifBlank { anime.title },
                tagsList = details.genre?.split(',')?.map(String::trim).orEmpty(),
                performersList = details.artist?.split(',')?.map(String::trim).orEmpty(),
                studiosList = listOfNotNull(studio),
            )
        }
        details
    }

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        document.throwIfCloudflareChallenge()

        val rawDetailTitle = document.selectFirst("h1.film-info-title, h1")
            ?.text()
            ?.replace(MULTI_SPACE_REGEX, " ")
            ?.trim()
            .orEmpty()
        val detailRuntime = document.metadataText("Runtimes:", "Runtime:")
            ?.replace(Regex("""(?i)\s*mins?\b"""), "m")
            ?.trim()
        title = if (!detailRuntime.isNullOrBlank() && rawDetailTitle.isNotBlank() && !rawDetailTitle.contains(detailRuntime, ignoreCase = true)) {
            "[$detailRuntime] $rawDetailTitle"
        } else {
            rawDetailTitle
        }

        val thumbnailElement: Element? = document.selectFirst(
            "div.covert img, #infomation img, div.col-3 img, #poster img, div.col-5 img, img[itemprop=image]",
        )
        if (thumbnailElement != null) {
            thumbnail_url = extractImageUrl(thumbnailElement)
        }

        author = document.metadataLinks("Director:")
        artist = document.metadataLinks("Cast(s):", "Cast:")
        genre = document.metadataLinks("Genre(s):", "Genre:")

        val synopsis = document.selectFirst(".full-text-desc")?.text()?.trim()
            ?.ifBlank { document.selectFirst(".text-desc-container")?.text()?.trim() }
            ?.ifBlank { document.selectFirst("meta[name=description]")?.attr("content")?.trim() }
            ?.takeIf(String::isNotBlank)

        description = buildList {
            synopsis?.let(::add)
            document.metadataText("Label:")?.let { add("Label: $it") }
            document.metadataText("Studio:")?.let { add("Studio: $it") }
            document.metadataText("Quality:")?.let { add("Quality: $it") }
            document.metadataText("Release Date:")?.let { add("Release Date: $it") }
            document.metadataText("Runtimes:", "Runtime:")?.let { add("Runtime: $it") }
            document.metadataText("Added:")?.let { add("Added: $it") }
            document.metadataText("Views:")?.let { add("Views: $it") }
        }.joinToString("\n").takeIf(String::isNotBlank)

        status = SAnime.COMPLETED
    }

    private fun Document.metadataLinks(vararg labels: String): String? = select("div.description")
        .firstOrNull { element -> labels.any { label -> element.text().contains(label, ignoreCase = true) } }
        ?.select("a")
        ?.map { it.text().trim() }
        ?.filter(String::isNotBlank)
        ?.distinct()
        ?.joinToString()
        ?.takeIf(String::isNotBlank)

    private fun Document.metadataText(vararg labels: String): String? = select("div.description")
        .firstOrNull { element -> labels.any { label -> element.text().trim().startsWith(label, ignoreCase = true) } }
        ?.text()
        ?.trim()
        ?.let { text ->
            labels.firstNotNullOfOrNull { label ->
                text.takeIf { it.startsWith(label, ignoreCase = true) }?.substring(label.length)?.trim()
            }
        }
        ?.takeIf(String::isNotBlank)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // Also learn from saved Library entries whenever Library Update runs.
        if (isSmartTasteEnabled() && anime.title.isNotBlank()) {
            tasteEngine.recordBrowse(
                url = anime.url,
                title = anime.title,
                tagsList = anime.genre?.split(',')?.map(String::trim).orEmpty(),
                performersList = anime.artist?.split(',')?.map(String::trim).orEmpty(),
            )
        }
        return listOf(
            SEpisode.create().apply {
                name = "Video"
                episode_number = 1F
                url = anime.url
            },
        )
    }

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
            if (cachedEntry != null && e !is CloudflareBlockedException && e !is HumanVerificationException) {
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
        val siteResp = executeSiteGet(request)
        cacheVideoPage(urlKey, siteResp.body)
        return siteResp.body
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

        // Sort player buttons by user-preferred server + reliability before calling /ajax/player.
        val playerButtons = document.select("button.btn-player.episode[data-id], button.btn-player[data-id]:not(.vip)")
            .filterNot { it.hasClass("vip") || it.attr("data-id").trim().startsWith("-") }
            .distinctBy { it.attr("data-id").trim() }
            .sortedBy { serverPriority(normalizeServerLabel(it.text())) }
            .take(MAX_SERVERS_TO_RESOLVE)

        if (playerButtons.isEmpty()) {
            throw IOException("SexTB returned no playable server buttons on this page.")
        }

        val initialToken = PT_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val initialKey = PK_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val fallbackFilmId = FILM_ID_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        if (initialToken.isBlank() || initialKey.isBlank()) {
            throw IOException("SexTB player tokens are missing. Open this video in WebView once, then retry.")
        }

        var token = initialToken
        var key = initialKey
        var sawHumanCheck = false

        fun resolveBatch(buttons: List<Element>): List<PlayerTarget> {
            val batch = mutableListOf<PlayerTarget>()
            for (button in buttons) {
                val episodeId = button.attr("data-id").trim()
                val filmId = button.attr("data-source").ifBlank { fallbackFilmId }.trim()
                if (episodeId.isBlank() || filmId.isBlank()) continue

                val payload = fetchPlayerPayload(episodeId, filmId, token, referer) ?: continue
                if (payload.humanCheckRequired) {
                    sawHumanCheck = true
                    token = payload.nextToken.ifBlank { token }
                    key = payload.nextKey.ifBlank { key }
                    break
                }

                val decrypted = xorDecrypt(payload.encryptedPlayer, key)
                token = payload.nextToken.ifBlank { token }
                key = payload.nextKey.ifBlank { key }
                if (decrypted.isBlank()) continue

                val label = normalizeServerLabel(button.text())
                extractPlayerUrls(decrypted, referer).forEach { url ->
                    batch += PlayerTarget(label, url.redirectHgLink(), referer)
                }
            }
            return batch.distinctBy { it.url }.sortedBy { serverPriority(it.label) }
        }

        // If the user pinned a specific preferred server and it's available first, resolve
        // ONLY that 1 server in wave 1 (1 /ajax/player POST instead of 2-4); fall back to
        // remaining servers automatically if it fails.
        val prefServer = preferredServer()
        val firstWaveCount = if (
            prefServer != PREF_SERVER_DEFAULT &&
            playerButtons.firstOrNull()?.let { normalizeServerLabel(it.text()).contains(prefServer, ignoreCase = true) } == true
        ) {
            1
        } else {
            INITIAL_SERVERS_TO_RESOLVE
        }
        val firstWaveButtons = playerButtons.take(firstWaveCount)
        val fallbackButtons = playerButtons.drop(firstWaveCount)

        var prioritizedPlayers = resolveBatch(firstWaveButtons)
        var rawVideos = if (prioritizedPlayers.isNotEmpty()) {
            extractPlayersInParallel(prioritizedPlayers).distinctBy { it.videoUrl ?: it.url }
        } else {
            emptyList()
        }

        if (rawVideos.isEmpty() && !sawHumanCheck && fallbackButtons.isNotEmpty()) {
            val fallbackPlayers = resolveBatch(fallbackButtons)
            if (fallbackPlayers.isNotEmpty()) {
                prioritizedPlayers = (prioritizedPlayers + fallbackPlayers).distinctBy { it.url }
                rawVideos = extractPlayersInParallel(fallbackPlayers).distinctBy { it.videoUrl ?: it.url }
            }
        }

        if (prioritizedPlayers.isEmpty()) {
            if (sawHumanCheck) throw HumanVerificationException()
            throw IOException("SexTB could not resolve any server. Open this video in WebView once, then retry.")
        }

        val finalVideos = if (isSeekCacheEnabled()) {
            m3u8Server.wrapVideosForCache(
                videos = rawVideos,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = referer,
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            rawVideos
        }

        val sortedVideos = sortVideos(finalVideos)
        Log.i(TAG, "VideoList RESOLVED targets=${prioritizedPlayers.size} raw=${rawVideos.size} final=${sortedVideos.size} qualities=${sortedVideos.map { it.quality }}")
        if (sortedVideos.isEmpty()) {
            throw IOException("SexTB servers were found, but none returned a playable stream. Retry or open the video in WebView.")
        }
        if (isSmartTasteEnabled()) {
            runCatching {
                val playTitle = document.selectFirst("h1.film-info-title, h1")?.text()?.trim()
                val cast = document.metadataLinks("Cast(s):", "Cast:")?.split(',')?.map(String::trim).orEmpty()
                val genres = document.metadataLinks("Genre(s):", "Genre:")?.split(',')?.map(String::trim).orEmpty()
                val studio = document.metadataText("Studio:")
                val relUrl = referer.toHttpUrlOrNull()?.encodedPath ?: referer
                tasteEngine.recordPlay(
                    url = relUrl,
                    title = playTitle,
                    tagsList = genres,
                    performersList = cast,
                    studiosList = listOfNotNull(studio),
                )
            }
        }
        return sortedVideos
    }

    private fun sortVideos(videos: List<Video>): List<Video> {
        val prefHeight = preferredQuality().removeSuffix("p").toIntOrNull() ?: 720
        return videos.sortedWith(
            compareBy<Video>(
                { serverPriority(it.quality) },
                {
                    val h = extractHeight(it.quality)
                    if (h == prefHeight) 0 else 1
                },
                { -extractHeight(it.quality) },
            ),
        )
    }

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

        when {
            label == "F4" || "f4scdn" in lowerUrl || "f4jav" in lowerUrl || "f4stream" in lowerUrl || "f4splay" in lowerUrl -> {
                return runCatching { extractF4VideosBlocking(target) }.getOrDefault(emptyList())
            }

            label == "UPN" || label == "US" -> {
                val upn = runCatching { extractUpnVideosBlocking(target) }.getOrDefault(emptyList())
                if (upn.isNotEmpty()) return upn
            }

            label == "DD" || "playmogo" in lowerUrl || "dood" in lowerUrl || "ds2play" in lowerUrl -> {
                return runCatching { doodExtractor.videosFromUrl(target.url, quality = "DD") }.getOrDefault(emptyList())
            }

            label == "ST" || "streamtape" in lowerUrl -> {
                return runCatching { streamTapeExtractor.videosFromUrl(target.url) }.getOrDefault(emptyList())
            }

            label == "MD" || "mixdrop" in lowerUrl -> {
                return runCatching { mixDropExtractor.videosFromUrl(target.url) }.getOrDefault(emptyList())
            }

            label == "VOE" || "voe." in lowerUrl -> {
                return runCatching { voeExtractor.videosFromUrl(target.url, prefix = "VOE - ") }.getOrDefault(emptyList())
            }

            label == "UQLOAD" || "uqload" in lowerUrl -> {
                return runCatching { runBlocking { uqloadExtractor.videosFromUrl(target.url, prefix = "Uqload - ") } }.getOrDefault(emptyList())
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

                    // Collect StreamWish / FileLions / VidHide HLS mirrors in priority order:
                    // hls4 (proxied PNG-TS) -> hls3 (master.txt) -> hls2 (master.m3u8) -> generic playlist URL.
                    val hlsCandidates = buildList {
                        HLS4_LINK_REGEX.find(scripts)?.groupValues?.getOrNull(1)
                            ?.let { normalizePlayerUrl(it, finalUrl) }
                            ?.let { add(HlsCandidate(it, mustProxy = true)) }
                        HLS3_LINK_REGEX.find(scripts)?.groupValues?.getOrNull(1)
                            ?.let { normalizePlayerUrl(it, finalUrl) }
                            ?.let { add(HlsCandidate(it, mustProxy = true)) }
                        HLS2_LINK_REGEX.find(scripts)?.groupValues?.getOrNull(1)
                            ?.let { normalizePlayerUrl(it, finalUrl) }
                            ?.let { add(HlsCandidate(it, mustProxy = false)) }
                        val fallback = M3U8_URL_REGEX.find(scripts)?.value
                            ?: document.selectFirst("[data-hash*=m3u8]")?.attr("data-hash")
                            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
                        fallback?.let { normalizePlayerUrl(it, finalUrl) }
                            ?.let { add(HlsCandidate(it, mustProxy = false)) }
                    }.distinctBy { it.url }

                    for (candidate in hlsCandidates) {
                        val useProxy = candidate.mustProxy || isSeekCacheEnabled()
                        val videos = if (useProxy) {
                            extractProxiedHlsVariants(
                                playlistUrl = candidate.url,
                                referer = finalUrl,
                                origin = finalOrigin,
                                label = label,
                            )
                        } else {
                            extractDirectHlsVariants(
                                playlistUrl = candidate.url,
                                referer = finalUrl,
                                origin = finalOrigin,
                                label = label,
                            )
                        }
                        if (videos.isNotEmpty()) return@use videos
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
        validateFirstVariant: Boolean = false,
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
        val rawVariants = mutableListOf<Pair<String, String>>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.takeIf { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val resolved = baseHttp?.resolve(next)?.toString() ?: normalizePlayerUrl(next, playlistUrl) ?: continue
            val height = RESOLUTION_HEIGHT_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val quality = if (height != null) "$label - ${height}p" else "$label - HLS"
            rawVariants += resolved to quality
        }

        if (rawVariants.isNotEmpty()) {
            if (validateFirstVariant) {
                val firstVariantOk = runCatching {
                    fastExtractorClient.newCall(GET(rawVariants.first().first, reqHeaders)).execute().use { r ->
                        r.isSuccessful && r.body.string().trimStart().startsWith("#EXTM3U")
                    }
                }.getOrDefault(false)
                if (!firstVariantOk) return emptyList()
            }

            val proxiedVariants = rawVariants.mapNotNull { (resolved, quality) ->
                val localUrl = m3u8Server.processM3u8Url(
                    m3u8Url = resolved,
                    referer = referer,
                    userAgent = headers["User-Agent"],
                    origin = origin,
                ) ?: return@mapNotNull null
                Video(localUrl, quality, localUrl)
            }
            if (proxiedVariants.isNotEmpty()) return proxiedVariants.distinctBy { it.videoUrl }
        }

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

    /**
     * F4 (`f4scdn.com` -> `f4splay.vip`) returns JSON with both:
     * - `src`: full CDN playlist URL (`.../playlist.txt`) where relative variant paths
     *   like `720p/index.txt` and `seg_00000.png` resolve cleanly (HTTP 200).
     * - `url`: token endpoint (`/v/<jwt>`) where resolving `720p/index.txt` relative to `/v/`
     *   produces a 404.
     * Prefer `src` first and always route through `m3u8Server` because F4 disguises MPEG-TS
     * segments as `.png` and playlists as `.txt`.
     */
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
            buildList {
                val rawSrc = jsonObj.optString("src").trim()
                if (rawSrc.isNotBlank()) add(rawSrc)
                val relUrl = jsonObj.optString("url").trim()
                if (relUrl.isNotBlank()) {
                    finalEmbedUrl.resolve(relUrl)?.toString()?.let(::add)
                }
            }.distinct()
        }

        for (candidate in candidates) {
            val proxied = extractProxiedHlsVariants(
                playlistUrl = candidate,
                referer = "$embedOrigin/",
                origin = embedOrigin,
                label = "F4",
                validateFirstVariant = true,
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
     * hglink.to / hgcloud.to serve an obfuscated JS loader that redirects to a
     * StreamWish mirror; niramirus.com / medixiru.com serve the packed player directly.
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
    ): PlayerPayload? = try {
        val resp = executeSitePost(
            url = "$baseUrl/ajax/player",
            formParams = listOf(
                "episode" to episodeId,
                "filmId" to filmId,
                "pt" to token,
                "ts" to "",
            ),
            referer = referer,
            highPriority = true,
            bucket = RateBucket.PLAYER,
        )
        if (resp.status !in 200..299) {
            null
        } else {
            val json = JSONObject(resp.body)
            val code = json.optString("code")
            if (code.equals("E_HUMAN", ignoreCase = true)) {
                PlayerPayload(
                    encryptedPlayer = "",
                    nextToken = json.optString("next_pt"),
                    nextKey = json.optString("next_pk"),
                    humanCheckRequired = true,
                )
            } else if (json.optBoolean("error", false)) {
                null
            } else {
                PlayerPayload(
                    encryptedPlayer = json.optString("player_enc"),
                    nextToken = json.optString("next_pt"),
                    nextKey = json.optString("next_pk"),
                ).takeIf { it.encryptedPlayer.isNotBlank() }
            }
        }
    } catch (e: CloudflareBlockedException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "fetchPlayerPayload failed for ep=$episodeId: ${e.message}")
        null
    }

    private fun shouldUseWebBridge(): Boolean = System.currentTimeMillis() < webBridgeUntilMs

    private fun isCloudflareThrowable(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            val name = cur.javaClass.name
            val msg = cur.message.orEmpty()
            if (cur is CloudflareBlockedException ||
                name.contains("Cloudflare", ignoreCase = true) ||
                msg.contains("Cloudflare", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun acquireSiteRequestSlot(
        bucket: RateBucket = RateBucket.LISTING,
        highPriority: Boolean = true,
    ) {
        val pacer = pacers.getValue(bucket)
        if (highPriority) pacer.highPriorityWaiters.incrementAndGet()
        try {
            while (true) {
                var sleepMs = 0L
                synchronized(pacer.lock) {
                    val now = System.currentTimeMillis()
                    if (!highPriority && pacer.highPriorityWaiters.get() > 0) {
                        sleepMs = 120L
                    } else if (now < pacer.nextAllowedMs) {
                        sleepMs = pacer.nextAllowedMs - now
                    } else {
                        val gap = if (now < pacer.cooldownUntilMs) bucket.cooldownGapMs else bucket.normalGapMs
                        pacer.nextAllowedMs = now + gap
                        return
                    }
                }
                if (sleepMs > 0L) {
                    try {
                        Thread.sleep(sleepMs.coerceAtMost(1_000L))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("SexTB request cancelled while pacing.", e)
                    }
                }
            }
        } finally {
            if (highPriority) pacer.highPriorityWaiters.decrementAndGet()
        }
    }

    private fun noteRateLimitHit(bucket: RateBucket, retryAfterSeconds: Long?, attempt: Int): Boolean {
        // If search endpoint returns a long 60s Retry-After window, fail fast instead of
        // stalling threads for a full minute (other buckets stay completely unaffected).
        if (bucket == RateBucket.SEARCH && retryAfterSeconds != null && retryAfterSeconds > 15L) {
            val pacer = pacers.getValue(bucket)
            synchronized(pacer.lock) {
                val now = System.currentTimeMillis()
                pacer.cooldownUntilMs = maxOf(pacer.cooldownUntilMs, now + RATE_LIMIT_COOLDOWN_MS)
                pacer.nextAllowedMs = maxOf(pacer.nextAllowedMs, now + 4_000L)
            }
            Log.w(TAG, "SexTB HTTP 429 on $bucket with Retry-After=${retryAfterSeconds}s; failing fast to avoid stall.")
            return false
        }

        val baseBackoff = when (attempt) {
            0 -> RATE_LIMIT_BACKOFF_1_MS
            1 -> RATE_LIMIT_BACKOFF_2_MS
            else -> RATE_LIMIT_BACKOFF_3_MS
        }
        val headerBackoff = retryAfterSeconds?.coerceIn(1L, 8L)?.times(1_000L) ?: 0L
        val delayMs = maxOf(baseBackoff, headerBackoff)
        val pacer = pacers.getValue(bucket)
        synchronized(pacer.lock) {
            val now = System.currentTimeMillis()
            pacer.cooldownUntilMs = maxOf(pacer.cooldownUntilMs, now + RATE_LIMIT_COOLDOWN_MS)
            pacer.nextAllowedMs = maxOf(pacer.nextAllowedMs, now + delayMs)
        }
        Log.w(TAG, "SexTB HTTP 429 on $bucket (attempt ${attempt + 1}/$MAX_429_RETRIES); backing off ${delayMs}ms.")
        return true
    }

    private fun executeSiteGet(
        request: Request,
        highPriority: Boolean = true,
        bucket: RateBucket = RateBucket.LISTING,
    ): SiteResponse {
        val url = request.url.toString()

        // Single-flight coalescing: if another coroutine is already fetching the exact same URL,
        // wait for its result instead of burning another rate-limit slot.
        val newHolder = InFlightGet()
        val existing = inFlightGets.putIfAbsent(url, newHolder)
        if (existing != null) {
            try {
                if (!existing.latch.await(SINGLE_FLIGHT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("SexTB timed out waiting for coalesced request $url")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("SexTB request interrupted for $url", e)
            }
            existing.result.get()?.let { return it }
            val err = existing.error.get()
            if (err is IOException) throw err
            throw IOException(err?.message ?: "SexTB coalesced request failed for $url", err)
        }

        try {
            val response = executeSiteGetInternal(request, url, highPriority, bucket)
            newHolder.result.set(response)
            return response
        } catch (t: Throwable) {
            newHolder.error.set(t)
            throw t
        } finally {
            newHolder.latch.countDown()
            inFlightGets.remove(url, newHolder)
        }
    }

    private fun executeSiteGetInternal(
        request: Request,
        url: String,
        highPriority: Boolean,
        bucket: RateBucket,
    ): SiteResponse {
        for (attempt in 0..MAX_429_RETRIES) {
            acquireSiteRequestSlot(bucket, highPriority)

            if (!shouldUseWebBridge()) {
                var switchedToBridge = false
                try {
                    client.newCall(request).execute().use { resp ->
                        resp.throwIfCloudflareChallenge()
                        if (resp.code == 429) {
                            if (attempt < MAX_429_RETRIES && noteRateLimitHit(bucket, resp.header("Retry-After")?.toLongOrNull(), attempt)) {
                                continue
                            }
                            throw IOException("SexTB rate limit (HTTP 429). Wait a moment and retry.")
                        }
                        val finalUrl = resp.request.url.toString()
                        val body = resp.body.string()
                        if (body.isCloudflareChallengeHtml()) throw CloudflareBlockedException()
                        if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
                        return SiteResponse(resp.code, finalUrl, body, viaWebView = false)
                    }
                } catch (t: Throwable) {
                    if (!isCloudflareThrowable(t)) throw t
                    if (!webBridge.hasClearanceCookie()) {
                        Log.w(TAG, "Cloudflare blocked $url and no cf_clearance cookie exists in WebView.")
                        throw CloudflareBlockedException()
                    }
                    Log.i(TAG, "OkHttp hit Cloudflare fingerprint mismatch on $url; switching to WebView session bridge.")
                    webBridgeUntilMs = System.currentTimeMillis() + WEB_BRIDGE_TTL_MS
                    switchedToBridge = true
                }
                if (switchedToBridge) {
                    acquireSiteRequestSlot(bucket, highPriority)
                }
            }

            val webResp = webBridge.fetch(
                url = url,
                method = "GET",
                extraHeaders = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
            )
            if (webResp.status == 429) {
                if (attempt < MAX_429_RETRIES && noteRateLimitHit(bucket, webResp.retryAfterSeconds, attempt)) {
                    continue
                }
                throw IOException("SexTB rate limit (HTTP 429). Wait a moment and retry.")
            }
            if (webResp.status in CLOUDFLARE_ERROR_CODES || webResp.body.isCloudflareChallengeHtml()) {
                webBridgeUntilMs = 0L
                Log.w(TAG, "WebView session bridge received Cloudflare challenge (${webResp.status}) for $url.")
                throw CloudflareBlockedException()
            }
            if (webResp.status !in 200..299) {
                throw HttpStatusException(webResp.status, url)
            }
            return SiteResponse(webResp.status, webResp.finalUrl, webResp.body, viaWebView = true)
        }
        throw IOException("SexTB rate limit (HTTP 429). Wait a moment and retry.")
    }

    private fun executeSitePost(
        url: String,
        formParams: List<Pair<String, String>>,
        referer: String,
        highPriority: Boolean = true,
        bucket: RateBucket = RateBucket.PLAYER,
    ): SiteResponse {
        val encodedForm = formParams.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        for (attempt in 0..MAX_429_RETRIES) {
            acquireSiteRequestSlot(bucket, highPriority)

            if (!shouldUseWebBridge()) {
                val form = FormBody.Builder().apply {
                    formParams.forEach { (k, v) -> add(k, v) }
                }.build()
                val ajaxHeaders = headers.newBuilder()
                    .set("Referer", referer)
                    .set("Origin", baseUrl)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Accept", "application/json, text/javascript, */*; q=0.01")
                    .build()
                var switchedToBridge = false
                try {
                    fastExtractorClient.newCall(POST(url, ajaxHeaders, form)).execute().use { resp ->
                        resp.throwIfCloudflareChallenge()
                        if (resp.code == 429) {
                            if (attempt < MAX_429_RETRIES && noteRateLimitHit(bucket, resp.header("Retry-After")?.toLongOrNull(), attempt)) {
                                continue
                            }
                            throw IOException("SexTB rate limit (HTTP 429). Wait a moment and retry.")
                        }
                        val finalUrl = resp.request.url.toString()
                        val body = resp.body.string()
                        if (body.isCloudflareChallengeHtml()) throw CloudflareBlockedException()
                        return SiteResponse(resp.code, finalUrl, body, viaWebView = false)
                    }
                } catch (t: Throwable) {
                    if (!isCloudflareThrowable(t)) throw t
                    if (!webBridge.hasClearanceCookie()) throw CloudflareBlockedException()
                    Log.i(TAG, "OkHttp POST hit Cloudflare mismatch on $url; switching to WebView session bridge.")
                    webBridgeUntilMs = System.currentTimeMillis() + WEB_BRIDGE_TTL_MS
                    switchedToBridge = true
                }
                if (switchedToBridge) {
                    acquireSiteRequestSlot(bucket, highPriority)
                }
            }

            val webResp = webBridge.fetch(
                url = url,
                method = "POST",
                formBody = encodedForm,
                extraHeaders = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
            )
            if (webResp.status == 429) {
                if (attempt < MAX_429_RETRIES && noteRateLimitHit(bucket, webResp.retryAfterSeconds, attempt)) {
                    continue
                }
                throw IOException("SexTB rate limit (HTTP 429). Wait a moment and retry.")
            }
            if (webResp.status in CLOUDFLARE_ERROR_CODES || webResp.body.isCloudflareChallengeHtml()) {
                webBridgeUntilMs = 0L
                throw CloudflareBlockedException()
            }
            return SiteResponse(webResp.status, webResp.finalUrl, webResp.body, viaWebView = true)
        }
        throw IOException("SexTB rate limit (HTTP 429). Wait a moment and retry.")
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
                "faststream" in url || "filelions" in url || "hglink" in url || "hgcloud" in url ||
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

    private fun extractImageUrl(image: Element): String? {
        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-original"),
            image.attr("src"),
            image.absUrl("data-src"),
            image.absUrl("src"),
        )
        val picked = candidates.firstOrNull { raw ->
            val trimmed = raw.trim()
            trimmed.isNotBlank() &&
                !trimmed.contains("default-cover", ignoreCase = true) &&
                !trimmed.contains("no-poster", ignoreCase = true)
        } ?: candidates.firstOrNull { it.isNotBlank() } ?: return null
        return normalizeUrl(picked)
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        val absolute = when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$baseUrl$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> "$baseUrl/${clean.removePrefix("/")}"
        }
        // Tadami's cover fetcher blacklists `cdna.sextb.net`; `cdn001.imggle.net` serves the identical path.
        return absolute.replace(SEXTBN_DEAD_CDN_REGEX, "https://cdn001.imggle.net/")
    }

    private fun toSearchSlug(query: String): String {
        val cleaned = query
            .replace('[', ' ')
            .replace(']', ' ')
            .replace('/', ' ')
            .trim()
            .replace(WHITESPACE_REGEX, "-")
            .replace(MULTI_DASH_REGEX, "-")
            .trim('-', ':', ',', '.')
            .ifBlank { query.trim() }
        return if (cleaned.length <= MAX_SEARCH_SLUG_LEN) {
            cleaned
        } else {
            cleaned.substring(0, MAX_SEARCH_SLUG_LEN).substringBeforeLast('-', cleaned.substring(0, MAX_SEARCH_SLUG_LEN)).trim('-')
        }
    }

    private fun Response.rememberCatalogPath() {
        rememberCatalogPathFromUrl(request.url.toString())
    }

    private fun loadPersistedPathMap(prefKey: String): ConcurrentHashMap<String, String> {
        val map = ConcurrentHashMap<String, String>()
        runCatching {
            val raw = preferences.getString(prefKey, null)?.takeIf(String::isNotBlank) ?: return@runCatching
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k).trim()
                if (k.startsWith("/") || prefKey == PREF_ACTRESS_PATH_CACHE_KEY) {
                    if (v.startsWith("/")) map[k] = v
                }
            }
        }
        return map
    }

    private fun persistPathMap(prefKey: String, map: ConcurrentHashMap<String, String>) {
        runCatching {
            val json = JSONObject()
            for ((k, v) in map) {
                json.put(k, v)
            }
            preferences.edit().putString(prefKey, json.toString()).apply()
        }
    }

    private fun evictStaleHashedPath(request: Request): Request? {
        val encodedPath = request.url.encodedPath
        val basePart = encodedPath.substringBefore("/pg-").trimEnd('/')
        val pageSuffix = if ("/pg-" in encodedPath) "/pg-${encodedPath.substringAfter("/pg-")}" else ""
        val matchingEntry = resolvedCatalogPaths.entries.firstOrNull { it.value == basePart } ?: return null
        resolvedCatalogPaths.remove(matchingEntry.key, matchingEntry.value)
        persistPathMap(PREF_CATALOG_PATH_CACHE_KEY, resolvedCatalogPaths)
        val rebuiltUrl = request.url.newBuilder()
            .encodedPath(matchingEntry.key + pageSuffix)
            .build()
        Log.i(TAG, "Evicted stale hashed path ${matchingEntry.value}; retrying with ${matchingEntry.key}")
        return request.newBuilder().url(rebuiltUrl).build()
    }

    private fun harvestHashedCatalogLinks(document: Document) {
        if (resolvedCatalogPaths.size >= MAX_PERSISTED_CATALOG_PATHS) return
        var dirty = false
        document.select("a[href^=\"/jav-\"], a[href^=\"/genre/\"], a[href^=\"/studio/\"]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.startsWith("/") && rememberCatalogPathFromUrl("$baseUrl$href", persist = false)) {
                dirty = true
            }
        }
        if (dirty) {
            persistPathMap(PREF_CATALOG_PATH_CACHE_KEY, resolvedCatalogPaths)
        }
    }

    private fun rememberCatalogPathFromUrl(url: String, persist: Boolean = true): Boolean {
        val resolvedPath = url.toHttpUrlOrNull()?.encodedPath?.substringBefore("/pg-")?.trimEnd('/')
            ?: return false
        if (resolvedPath.isBlank()) return false
        val stablePath = when {
            resolvedPath.startsWith("/jav-censored-") -> "/jav-censored"

            resolvedPath.startsWith("/jav-uncensored-") -> "/jav-uncensored"

            resolvedPath.startsWith("/jav-amateur-") -> "/jav-amateur"

            resolvedPath.startsWith("/jav-subtitle-") -> "/jav-subtitle"

            resolvedPath.startsWith("/genre/") -> {
                val tail = resolvedPath.removePrefix("/genre/")
                val baseSlug = tail.replace(TRAILING_HASH_REGEX, "")
                if (baseSlug != tail) "/genre/$baseSlug" else return false
            }

            resolvedPath.startsWith("/studio/") -> {
                val tail = resolvedPath.removePrefix("/studio/")
                val baseSlug = tail.replace(TRAILING_HASH_REGEX, "")
                if (baseSlug != tail) "/studio/$baseSlug" else return false
            }

            else -> return false
        }
        if (resolvedCatalogPaths.size >= MAX_PERSISTED_CATALOG_PATHS && !resolvedCatalogPaths.containsKey(stablePath)) {
            return false
        }
        val prev = resolvedCatalogPaths.put(stablePath, resolvedPath)
        val changed = prev != resolvedPath
        if (changed && persist) {
            persistPathMap(PREF_CATALOG_PATH_CACHE_KEY, resolvedCatalogPaths)
        }
        return changed
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

    private fun serverPriority(quality: String): Int {
        val pref = preferredServer()
        if (pref != PREF_SERVER_DEFAULT && quality.contains(pref, ignoreCase = true)) {
            return 0
        }
        return when {
            quality.contains("F4", true) -> 1
            quality.contains("SW", true) || quality.contains("StreamWish", true) -> 2
            quality.contains("DD", true) || quality.contains("Dood", true) -> 3
            quality.contains("FL", true) || quality.contains("FileLions", true) -> 4
            quality.contains("US", true) || quality.contains("UPN", true) -> 5
            quality.contains("PP", true) || quality.contains("VidHide", true) -> 6
            quality.contains("ST", true) || quality.contains("StreamTape", true) -> 7
            quality.contains("MD", true) || quality.contains("MixDrop", true) -> 8
            quality.contains("VOE", true) -> 9
            quality.contains("UQLOAD", true) -> 10
            quality.contains("TB", true) || quality.contains("SexTB", true) -> 11
            else -> 12
        }
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DEFAULT_CATEGORY_KEY
            title = "Default category (Popular / Latest)"
            entries = PREF_DEFAULT_CATEGORY_ENTRIES
            entryValues = PREF_DEFAULT_CATEGORY_VALUES
            setDefaultValue(DEFAULT_CATEGORY)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred video quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched HLS segments and MP4 ranges locally (RAM + temp storage) for faster seeking and rewinding."
            setDefaultValue(true)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SMART_TASTE_KEY
            title = "Smart Taste learning & re-rank (shared)"
            summary = "Learns titles, genres & actresses from Library updates, opens, and plays across extensions to power 'For You' and smart ranking."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TASTE_SEED_KEY
            title = "Seed taste from favorite titles / keywords"
            dialogTitle = "Paste favorite titles, actresses, or keywords"
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
            summary = "Tap to clear all learned titles, tags, actresses, and watched history."
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

    override fun videoListSelector(): String = "button.btn-player[data-id]"

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private data class SiteResponse(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val viaWebView: Boolean,
    )

    private data class HlsCandidate(
        val url: String,
        val mustProxy: Boolean,
    )

    private data class PlayerPayload(
        val encryptedPlayer: String,
        val nextToken: String,
        val nextKey: String,
        val humanCheckRequired: Boolean = false,
    )

    private data class PlayerTarget(
        val label: String,
        val url: String,
        val referer: String,
    )

    private class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

    private class CloudflareBlockedException : IOException("Cloudflare blocked SexTB. Open the page in WebView once, complete the challenge, then retry.")

    private class HumanVerificationException : IOException("SexTB requested human verification (Turnstile). Open this video in WebView once, confirm, then retry.")

    companion object {
        private const val TAG = "SexTB"
        private const val DEFAULT_CATEGORY = "/genre/ltx-encode"
        private const val FOR_YOU_SENTINEL = "__FOR_YOU__"
        private const val TOP_WEEK_SENTINEL = "__TOP_WEEK__"
        private const val TOP_MONTH_SENTINEL = "__TOP_MONTH__"

        private const val MAX_PLAYER_DEPTH = 1
        private const val INITIAL_SERVERS_TO_RESOLVE = 2
        private const val MAX_SERVERS_TO_RESOLVE = 4
        private const val MAX_MULTI_SELECT = 5
        private const val MAX_MULTI_TARGETS = 4
        private const val MAX_SEARCH_SLUG_LEN = 80
        private const val MAX_EXTRACTION_WAIT_MS = 10_000L
        private const val EXTRA_WAIT_AFTER_FIRST_MS = 1_800L
        private const val PAGE_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val EMPTY_PAGE_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val VIDEO_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val TOP_CHART_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val WEB_BRIDGE_TTL_MS = 15 * 60 * 1000L
        private const val MAX_PERSISTED_CATALOG_PATHS = 250
        private const val MAX_PERSISTED_ACTRESS_PATHS = 120
        private const val PREF_CATALOG_PATH_CACHE_KEY = "cache_catalog_paths_v1"
        private const val PREF_ACTRESS_PATH_CACHE_KEY = "cache_actress_paths_v1"
        private const val LISTING_REQUEST_GAP_MS = 220L
        private const val LISTING_COOLDOWN_GAP_MS = 1_200L
        private const val PLAYER_REQUEST_GAP_MS = 250L
        private const val PLAYER_COOLDOWN_GAP_MS = 1_200L
        private const val SEARCH_REQUEST_GAP_MS = 280L
        private const val SEARCH_COOLDOWN_GAP_MS = 1_500L
        private const val RATE_LIMIT_COOLDOWN_MS = 15_000L
        private const val RATE_LIMIT_BACKOFF_1_MS = 1_200L
        private const val RATE_LIMIT_BACKOFF_2_MS = 2_500L
        private const val RATE_LIMIT_BACKOFF_3_MS = 4_500L
        private const val MAX_429_RETRIES = 2
        private const val SINGLE_FLIGHT_WAIT_MS = 25_000L
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L
        private const val UPN_AES_KEY = "kiemtienmua911ca"
        private const val UPN_AES_IV = "1234567890oiuytr"

        private val CATEGORY_TO_GENRE_SLUG = mapOf(
            "/genre/ltx-encode" to "ltx-encode",
            "/genre/uncensored-leaked" to "uncensored-leaked",
            "/genre/reducing-mosaic" to "reducing-mosaic",
            "/genre/sextb-encode" to "sextb-encode",
            "/jav-amateur" to "amateur",
            "/jav-subtitle" to "english-subtitle",
        )

        private const val PREF_DEFAULT_CATEGORY_KEY = "pref_default_category"
        private val PREF_DEFAULT_CATEGORY_ENTRIES = arrayOf(
            "LTX Decensored",
            "For You (Smart Taste)",
            "Censored",
            "Uncensored",
            "Uncensored Leaked",
            "Reducing Mosaic",
            "Amateur",
            "English Subtitle",
            "SexTB Encode",
            "New Releases",
        )
        private val PREF_DEFAULT_CATEGORY_VALUES = arrayOf(
            "/genre/ltx-encode",
            FOR_YOU_SENTINEL,
            "/jav-censored",
            "/jav-uncensored",
            "/genre/uncensored-leaked",
            "/genre/reducing-mosaic",
            "/jav-amateur",
            "/jav-subtitle",
            "/genre/sextb-encode",
            "/new-releases",
        )

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "AUTO"
        private val PREF_SERVER_ENTRIES = arrayOf("Auto (Fastest)", "F4", "SW (StreamWish)", "DD (DoodStream)", "FL (FileLions)", "US / UPN", "ST (StreamTape)", "MD (MixDrop)")
        private val PREF_SERVER_VALUES = arrayOf("AUTO", "F4", "SW", "DD", "FL", "US", "ST", "MD")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"
        private const val PREF_SMART_TASTE_KEY = "pref_smart_taste_enabled"
        private const val PREF_TASTE_SEED_KEY = "pref_smart_taste_seed"
        private const val PREF_TASTE_RESET_KEY = "pref_smart_taste_reset"

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 503)
        private val titleChallengeRegex =
            Regex("""<title>\s*(?:just a moment|attention required|checking your browser)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val PT_REGEX = Regex("""window\.__pt\s*=\s*["']([^"']+)""")
        private val PK_REGEX = Regex("""window\.__pk\s*=\s*["']([^"']+)""")
        private val FILM_ID_REGEX = Regex("""(?:var|let|const)\s+filmId\s*=\s*(\d+)""")
        private val DATA_API_REGEX = Regex("""data-api=["']([^"']+)["']""")
        private val PLAYER_URL_REGEX = Regex("""(?:https?:)?//[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        private val HLS4_LINK_REGEX = Regex(""""hls4"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val HLS3_LINK_REGEX = Regex(""""hls3"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val HLS2_LINK_REGEX = Regex(""""hls2"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_HEIGHT_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
        private val QUALITY_HEIGHT_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        private val M3U8_URL_REGEX =
            Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|txt)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
        private val UPN_HEX_REGEX = Regex("""[0-9a-fA-F]+""")
        private val BRACKET_TAG_REGEX = Regex("""\[[^\]]+\]""")
        private val NON_SLUG_REGEX = Regex("""[^a-z0-9-]+""")
        private val SEARCH_KEY_NOISE_REGEX = Regex("""[\s\-_:/\[\]().,!?]+""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val SEXTBN_DEAD_CDN_REGEX = Regex("""https?://cdn[a-z0-9]*\.sextb\.net/""", RegexOption.IGNORE_CASE)
        private val MULTI_SPACE_REGEX = Regex("""\s+""")
        private val MULTI_DASH_REGEX = Regex("""-+""")
        private val TRAILING_HASH_REGEX = Regex("""-[a-z0-9]{8}$""")
        private val HG_LINK_HOSTS = listOf("hglink.to", "hgcloud.to")

        private val SEARCH_MODES = arrayOf(
            "Movies (Code / Title)" to "movie",
            "Actress" to "actor",
        )

        // All live SexTB categories and newly added sections/charts (canonical auto-resolving paths).
        private val CATEGORIES = arrayOf(
            "For You (Smart Taste)" to FOR_YOU_SENTINEL,
            "LTX Decensored" to "/genre/ltx-encode",
            "Censored" to "/jav-censored",
            "Uncensored" to "/jav-uncensored",
            "Uncensored Leaked" to "/genre/uncensored-leaked",
            "Reducing Mosaic" to "/genre/reducing-mosaic",
            "Amateur" to "/jav-amateur",
            "English Subtitle" to "/jav-subtitle",
            "SexTB Encode" to "/genre/sextb-encode",
            "New Releases" to "/new-releases",
            "TOP Chart (Weekly)" to TOP_WEEK_SENTINEL,
            "TOP Chart (Monthly)" to TOP_MONTH_SENTINEL,
        )

        // Expanded live genres from sextb.net's #myFilter and /genres catalog (exact verified slugs).
        private val GENRES = arrayOf(
            // Top popular genres (#myFilter order)
            "Creampie" to "creampie",
            "Masturbation" to "masturbation",
            "Big Tits" to "big-tits",
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
            "Sex Toys" to "sex-toys",
            "Nasty, Hardcore" to "nasty-hardcore",
            "Planning" to "planning",
            "Single Work" to "single-work",
            "Anal" to "anal",
            "Kiss Kiss" to "kiss-kiss",
            "Voyeur" to "voyeur",
            "Cheating Wife" to "cheating-wife",
            "Facial" to "facial",
            "Hardcore" to "hardcore",
            // Additional genres from /genres (categories like LTX / Uncensored Leaked live in Categories above)
            "Risky Mosaic" to "risky-mosaic",
            "Minimal Mosaic" to "minimal-mosaic",
            "30 Years Old" to "30-years-old",
            "40 Years Old" to "40-years-old",
            "69" to "69",
            "Adultery" to "adultery",
            "AV Idol" to "av-idol",
            "Bareback" to "bareback",
            "Bath" to "bath",
            "BDSM" to "bdsm",
            "Beautiful Face" to "beautiful-face",
            "Beautiful Pussy" to "beautiful-pussy",
            "Big Asses" to "big-asses",
            "Bitch" to "bitch",
            "Black Man" to "black-man",
            "Blonde" to "blonde",
            "Bondage" to "bondage",
            "Breast Milk" to "breast-milk",
            "Bukkake" to "bukkake",
            "Chubby" to "chubby",
            "Close Up" to "close-up",
            "Compilation" to "compilation",
            "Confinement" to "confinement",
            "Couple" to "couple",
            "Cumshot" to "cumshot",
            "Dirty Talk" to "dirty-talk",
            "Doggy Style" to "doggy-style",
            "Face Sitting" to "face-sitting",
            "Fantasy" to "fantasy",
            "Female Boss" to "female-boss",
            "Female Doctor" to "female-doctor",
            "Female Teacher" to "female-teacher",
            "Fingering" to "fingering",
            "Foot Fetish" to "foot-fetish",
            "Footjob" to "footjob",
            "Hard Sex" to "hard-sex",
            "Harem" to "harem",
            "Hot Spring" to "hot-spring",
            "Housewife" to "housewife",
            "Huge Dick - Large Dick" to "huge-dick-large-dick",
            "Huge Tits" to "huge-tits",
            "Hypnosis" to "hypnosis",
            "Leg Fetish" to "leg-fetish",
            "Lesbian" to "lesbian",
            "Lingerie" to "lingerie",
            "Lotion" to "lotion",
            "Maid" to "maid",
            "MILF" to "milf",
            "Mini Skirt" to "mini-skirt",
            "Nice Ass" to "nice-ass",
            "Nurse" to "nurse",
            "Office Lady" to "office-lady",
            "Orgasm" to "orgasm",
            "Orgy" to "orgy",
            "Outdoor" to "outdoor",
            "Panty Shot" to "panty-shot",
            "Pantyhose" to "pantyhose",
            "Pick Up" to "pick-up",
            "Private Tutor" to "private-tutor",
            "Prostitutes" to "prostitutes",
            "Quickie" to "quickie",
            "Restraint" to "restraint",
            "Sailor Suit" to "sailor-suit",
            "School Uniform" to "school-uniform",
            "Secretary" to "secretary",
            "Sexy Legs" to "sexy-legs",
            "Shaved Pussy" to "shaved-pussy",
            "Shemale" to "shemale",
            "Sister" to "sister",
            "Small Tits" to "small-tits",
            "Soapland Girl" to "soapland-girl",
            "Solo Work" to "solo-work",
            "Spanking" to "spanking",
            "Stepfamily" to "stepfamily",
            "Stepmom" to "stepmom",
            "Stepmother" to "stepmother",
            "Stewardess" to "stewardess",
            "Swallowing" to "swallowing",
            "Sweating" to "sweating",
            "Swimsuit" to "swimsuit",
            "Tall Girl" to "tall-girl",
            "Teen" to "teen",
            "Time Stop" to "time-stop",
            "Underwear" to "underwear",
            "Urination" to "urination",
            "Virgin" to "virgin",
            "Waitress" to "waitress",
            "White Actress" to "white-actress",
            "Widow" to "widow",
            "Young Wife" to "young-wife",
        )

        // All live studios from sextb.net's #myFilter picker + featured studios.
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
            "Natural High" to "natural-high",
            "Luxury TV" to "luxury-tv",
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
            "Giga" to "giga",
            "Heyzo" to "heyzo",
            "My Amateur Z" to "my-amateur-z",
            "Max A" to "max-a",
            "Crystal Eizou" to "crystal-eizou",
            "ROCKET" to "rocket",
            "IE NERGY" to "ie-nergy",
            "Bi" to "bi",
            "MAXING" to "maxing",
            "MERCURY (Mercury)" to "mercury-mercury",
            "10Musume" to "10musume",
            "1Pondo" to "1pondo",
            "BeFree" to "befree",
            "Dream Ticket" to "dream-ticket",
            "Muku" to "muku",
        )

        // Top actresses from live /list-actress & header menu.
        private val ACTRESSES = arrayOf(
            "Yu Shinoda" to "/actress/yu-shinoda",
            "Hana Haruna" to "/actress/if68a06ll",
            "Yui Hatano" to "/actress/6273dbs6j",
            "Ririko Kinoshita" to "/actress/1fjvbdte7",
            "Mina Kitano" to "/actress/mina-kitano",
            "Asahi Mizuno" to "/actress/asahi-mizuno",
            "Reiko Kobayakawa" to "/actress/reiko-kobayakawa",
            "Julia" to "/actress/0owbisfqi",
            "Ai Sayama" to "/actress/ai-sayama",
            "Mako Oda" to "/actress/mako-oda",
            "Meari Tachibana" to "/actress/kn257tc95",
            "Eimi Fukada" to "/actress/eimi-fukada",
            "Momoko Isshiki" to "/actress/momoko-isshiki",
            "Miu Shiromine" to "/actress/miu-shiromine",
            "Maki Hojo" to "/actress/maki-hojo",
            "Saeko Matsushita" to "/actress/saeko-matsushita",
            "Chitose Saegusa" to "/actress/chitose-saegusa",
            "Nao Jinguji" to "/actress/by7037fi",
            "Maki Tomoda" to "/actress/ubvtt1m8i",
            "Yua Mikami" to "/actress/bxai0wb1b",
            "Aka Asuka" to "/actress/qhcvgnp21",
            "Ayaka Mutou" to "/actress/ayaka-mutou",
            "Maria Nagai" to "/actress/maria-nagai",
            "Tsukasa Aoi" to "/actress/tsukasa-aoi",
            "Karen Yuzuriha" to "/actress/karen-yuzuriha",
            "Yumi Kazama" to "/actress/th1bdtq81",
            "Saika Kawakita" to "/actress/saika-kawakita",
            "Karen Kaede" to "/actress/3od4caha4",
            "Marina Shiraishi" to "/actress/9evi8vru",
            "Tsubasa Amami" to "/actress/tsubasa-amami",
            "Sumire Mizukawa" to "/actress/sumire-mizukawa",
            "Riho Fujimori" to "/actress/riho-fujimori",
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
