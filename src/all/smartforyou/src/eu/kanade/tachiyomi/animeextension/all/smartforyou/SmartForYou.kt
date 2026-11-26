package eu.kanade.tachiyomi.animeextension.all.smartforyou

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.m3u8server.CfWebBridgeInterceptor
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.m3u8server.SmartTasteEngine
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.voeextractor.VoeExtractor
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Smart For You (+18) — Self-adapting 15-source personalized hub.
 *
 * - Reads the shared `+18` SmartTasteEngine profile (`agy_global_taste_v1`) across all
 *   installed adult extensions + Library Updates.
 * - Supports 15 live sources (JAV, Western tubes, full scenes, Arabic/translated, 3D/Hentai)
 *   and automatically adapts to your most-used sources.
 * - Applies adaptive soft-mandatory theme constraints (`threesome`, `foursome`, `dp`,
 *   `gangbang`, `slut`) & non-JAV duration preferences (`25m+`) per source taxonomy,
 *   relaxing automatically whenever strict filters would shrink results.
 * - Routes per-host Referer & WebView clearance cookies for Coil thumbnails so SexTB and
 *   all 15 sources load cover artwork reliably.
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
    private val sourceNextAllowedMs = ConcurrentHashMap<String, Long>()
    private val sourceCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val recentDetailEntities = ConcurrentHashMap<String, Long>()
    private val sexTbHashedGenres = ConcurrentHashMap<String, String>()
    private val sourcePaceLock = Any()

    @Volatile
    private var sexTbGenresFetchedAtMs: Long = 0L

    @Volatile
    private var lastSearchRecordedMs: Long = 0L

    private val baseHttpClient: OkHttpClient by lazy {
        network.client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Client exposed to host Coil image loader: dynamically injects matching per-host Referer
    // and WebView Cloudflare cookies (fixes broken/stale SexTB & CDN covers!) and retries
    // without Referer if a CDN rejects hotlink referers.
    private val imageClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .addInterceptor(SmartImageRefererInterceptor())
            .build()
    }

    private val phCookieJar = PhAgeCookieJar()

    private val pornhubClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .cookieJar(phCookieJar)
            .build()
    }

    private val cfClients = ConcurrentHashMap<String, OkHttpClient>()

    private fun cfClientFor(origin: String): OkHttpClient = cfClients.computeIfAbsent(origin) {
        // Use network.client + CfWebBridgeInterceptor (NOT cloudflareClient) so worker threads
        // never trigger CloudflareInterceptor's Looper/Toast NPE crash.
        baseHttpClient.newBuilder()
            .addInterceptor(CfWebBridgeInterceptor(origin))
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        baseHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(network.client) }
    private val playlistUtils by lazy { PlaylistUtils(baseHttpClient, headers) }
    private val doodExtractor by lazy { DoodExtractor(baseHttpClient) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(baseHttpClient) }
    private val mixDropExtractor by lazy { MixDropExtractor(baseHttpClient) }
    private val voeExtractor by lazy { VoeExtractor(baseHttpClient, desktopHeaders("https://supjav.com/")) }

    override val client: OkHttpClient
        get() = imageClient

    private fun isSeekCacheEnabled(): Boolean = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
    private fun preferredQuality(): String = preferences.getString(PREF_QUALITY_KEY, "720p") ?: "720p"
    private fun isAutoSourceMode(): Boolean = preferences.getBoolean(PREF_AUTO_SOURCES_KEY, true)
    private fun autoSourceCount(): Int = (preferences.getString(PREF_AUTO_COUNT_KEY, "5")?.toIntOrNull() ?: 5).coerceIn(2, 6)

    private fun isSourceAllowed(id: String): Boolean = preferences.getBoolean("src_enabled_$id", true)

    private fun resolveActiveSources(explicitOverride: List<String> = emptyList()): List<SourceDef> {
        if (explicitOverride.isNotEmpty()) {
            return explicitOverride.mapNotNull { id -> ALL_SOURCES.firstOrNull { it.id == id } }.take(6)
        }
        val allowed = ALL_SOURCES.filter { isSourceAllowed(it.id) }.ifEmpty { ALL_SOURCES }
        val limit = autoSourceCount()
        if (!isAutoSourceMode()) {
            return allowed.take(limit)
        }
        val rankedIds = tasteEngine.snapshot().topSources
            .map { SOURCE_ALIASES[it] ?: it }
            .distinct()
            .filter { id -> allowed.any { it.id == id } }
        val picked = mutableListOf<SourceDef>()
        for (id in rankedIds) {
            if (picked.size >= limit) break
            allowed.firstOrNull { it.id == id }?.let(picked::add)
        }
        for (fallback in allowed) {
            if (picked.size >= limit) break
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
        if (cleanQuery.isNotBlank() && isSyntheticFallbackQuery(cleanQuery)) {
            return@withContext AnimesPage(emptyList(), false)
        }

        val checkedSourceIds = filters.filterIsInstance<SourceGroup>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.sourceId }
            .orEmpty()
        val modeValue = filters.filterIsInstance<FeedModeFilter>().firstOrNull()?.selected ?: "for_you"
        val sources = resolveActiveSources(checkedSourceIds)

        if (cleanQuery.isNotBlank()) {
            val now = System.currentTimeMillis()
            if (page == 1 && tasteEngine.isEnabled() && cleanQuery.length in 2..45 && now - lastSearchRecordedMs > 2_500L) {
                lastSearchRecordedMs = now
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
        val coreConcepts = (snap.topConcepts + snap.softAnchors).distinct()
        val tasteQueries = buildList {
            addAll(coreConcepts.take(5))
            addAll(tasteEngine.buildTitleSearchQueries(maxQueries = 5))
            snap.topPerformers.firstOrNull()?.let(::add)
        }.distinct()
        val nonJavMinMin = snap.preferredNonJavMinMinutes

        Log.i(
            TAG,
            "ForYou mode=$mode sources=${sources.map { it.id }} core=${coreConcepts.take(6)} anchors=${snap.softAnchors} nonJavMin=${nonJavMinMin}m",
        )

        val useTasteDiscovery = (mode == FeedMode.FOR_YOU || mode == FeedMode.LATEST) && tasteQueries.isNotEmpty()
        val preferNewest = mode == FeedMode.LATEST

        val batches = fetchInParallel(sources) { idx, src ->
            if (!useTasteDiscovery) {
                browseSource(src.id, page, latest = preferNewest)
            } else {
                fetchSourceAdaptive(
                    src = src,
                    idx = idx,
                    page = page,
                    coreConcepts = coreConcepts,
                    softAnchors = snap.softAnchors,
                    tasteQueries = tasteQueries,
                    nonJavMinMin = nonJavMinMin,
                    newest = preferNewest,
                )
            }
        }

        val merged = interleaveUnique(batches)
        // Final non-JAV duration guard: never let 4-6 min clips sneak onto For You / Latest
        // when the user's non-JAV target duration is >= 20m (unless total feed would starve).
        val cleanPool = if (nonJavMinMin >= 20 && mode != FeedMode.TRENDING) {
            val noShortNonJav = merged.animes.filter { anime ->
                val srcId = detectSourceId(anime.url)
                val isJavOrShort = srcId != null && (tasteEngine.isJavSource(srcId) || tasteEngine.isShortFormSource(srcId))
                if (isJavOrShort) {
                    true
                } else {
                    val d = tasteEngine.extractDurationMinutes(anime.title)
                    d == null || d >= 15
                }
            }
            if (noShortNonJav.size >= 6) noShortNonJav else merged.animes
        } else {
            merged.animes
        }
        if (page <= 1) seenFeedKeys.clear()
        val ranked = interleaveRankedBySource(
            items = cleanPool,
            blendWithOriginalRank = mode == FeedMode.TRENDING,
        ).filter { anime ->
            val keys = tasteEngine.extractKnownKeys(anime.url, anime.title)
            val alreadyShown = keys.any { it in seenFeedKeys }
            if (!alreadyShown) seenFeedKeys.addAll(keys)
            !alreadyShown
        }
        return AnimesPage(ranked, merged.hasNextPage)
    }

    /**
     * Source-diverse taste ranking:
     * 1. Scores every unseen video via SmartTasteEngine (MMF top priority, Group bonus,
     *    FFM -12 penalty, 25m+ non-JAV duration boost).
     * 2. Sorts each source's own bucket descending by score so every source puts its
     *    strongest MMF / Group / DP / 25m+ match first.
     * 3. Round-robin interleaves across all active sources (ordered by each bucket's best
     *    score) so every screen mixes multiple sources instead of stacking 10 from one site!
     */
    private fun interleaveRankedBySource(
        items: List<SAnime>,
        blendWithOriginalRank: Boolean,
    ): List<SAnime> {
        val unseen = items.filterNot { tasteEngine.isWatched(it.url, it.title) }
        if (unseen.isEmpty()) return emptyList()
        if (!tasteEngine.hasLearnedProfile()) return unseen

        val bySource = LinkedHashMap<String, MutableList<Pair<SAnime, Double>>>()
        val total = unseen.size.coerceAtLeast(1)
        unseen.forEachIndexed { idx, anime ->
            val rawScore = tasteEngine.scoreCandidate(anime.title, anime.url)
            val finalScore = if (blendWithOriginalRank && rawScore > -500.0) {
                rawScore + (total - idx).toDouble() / total * 1.8
            } else {
                rawScore
            }
            val srcId = detectSourceId(anime.url) ?: "other"
            bySource.getOrPut(srcId) { mutableListOf() }.add(anime to finalScore)
        }

        // Sort each source's bucket descending by score, then order sources by their top pick
        val queues = bySource.values
            .map { bucket -> bucket.sortedByDescending { it.second }.toMutableList() }
            .sortedByDescending { it.firstOrNull()?.second ?: Double.NEGATIVE_INFINITY }

        val out = ArrayList<SAnime>(unseen.size)
        var progressed = true
        while (progressed) {
            progressed = false
            for (q in queues) {
                if (q.isNotEmpty()) {
                    out += q.removeAt(0).first
                    progressed = true
                }
            }
        }
        return out
    }

    /**
     * Adaptive soft-mandatory fetcher per source:
     * 1. Picks themes actually supported by [src] (mapping concepts to native category slugs
     *    or Arabic/site-native queries).
     * 2. On non-JAV tube sources supporting URL duration filters (PornHub/XVideos/XNXX),
     *    starts with `20+ min` / `25m+` duration filter AND combo/anchor theme.
     * 3. Checks unseen yield — if too narrow (< [MIN_HEALTHY_YIELD]), smoothly relaxes:
     *    combo -> single concept -> drop strict URL duration filter -> popular/latest browse,
     *    while keeping all results scored by SmartTasteEngine (anchors + duration).
     */
    private fun fetchSourceAdaptive(
        src: SourceDef,
        idx: Int,
        page: Int,
        coreConcepts: List<String>,
        softAnchors: List<String>,
        tasteQueries: List<String>,
        nonJavMinMin: Int,
        newest: Boolean,
    ): AnimesPage {
        val applyNonJavDurGuard = !src.isJav && !src.isShortForm && nonJavMinMin >= 20
        val hardMinDur = if (applyNonJavDurGuard) (nonJavMinMin - 5).coerceAtLeast(18) else 0
        val wantDurFilter = applyNonJavDurGuard && (src.supportsDurationQuery || src.isArabic)
        val collected = mutableListOf<SAnime>()
        val softMediumPool = mutableListOf<SAnime>()
        val seen = HashSet<String>()
        var anyNext = false

        fun absorb(p: AnimesPage?): Int {
            if (p == null) return 0
            if (p.hasNextPage) anyNext = true
            var added = 0
            for (item in p.animes) {
                val urlKey = item.url.lowercase(Locale.ROOT)
                if (tasteEngine.isWatched(item.url, item.title) || !seen.add(urlKey)) continue
                if (hardMinDur > 0) {
                    val dur = tasteEngine.extractDurationMinutes(item.title)
                    if (dur != null && dur < hardMinDur) {
                        // Never accept 1–14 min clips (e.g. 4m / 6m) when non-JAV target is 25m+!
                        if (dur >= 15) softMediumPool += item
                        continue
                    }
                }
                collected += item
                added++
            }
            return added
        }

        // Choose rotated primary & secondary concepts for this source
        val primaryConcept = coreConcepts.getOrNull(idx % coreConcepts.size.coerceAtLeast(1))
        val anchorPartner = softAnchors.firstOrNull { it != primaryConcept && it in COMBO_FRIENDLY_CONCEPTS }

        // Pass 1: Native category/genre browse for supported concept (fast, high precision!)
        if (primaryConcept != null) {
            val nativePage = runCatching {
                browseSourceByConcept(src.id, page, primaryConcept, newest = newest, minDurationMin = if (wantDurFilter) nonJavMinMin else 0)
            }.getOrNull()
            absorb(nativePage)
        }

        // Pass 2: On search-friendly non-JAV tubes, try an anchor combo (e.g. "threesome dp" / "gangbang slut")
        // with duration filter if Pass 1 didn't run or yielded too few items.
        if (collected.size < MIN_HEALTHY_YIELD && primaryConcept != null && anchorPartner != null &&
            src.id in setOf("pornhub", "xvideos", "xnxx", "xmoviesforyou", "rule34video")
        ) {
            val comboQuery = "${conceptQueryForSource(src, primaryConcept)} ${conceptQueryForSource(src, anchorPartner)}".trim()
            val comboPage = runCatching {
                searchSource(src.id, page, comboQuery, minDurationMin = if (wantDurFilter) nonJavMinMin else 0, newest = newest)
            }.getOrNull()
            absorb(comboPage)
        }

        // Pass 3: Single concept search (with duration filter on non-JAV tubes & longest sort on Arabic KVS)
        if (collected.size < MIN_HEALTHY_YIELD) {
            val qRaw = primaryConcept ?: tasteQueries[idx % tasteQueries.size]
            val q = conceptQueryForSource(src, qRaw)
            val singlePage = runCatching {
                searchSource(src.id, page, q, minDurationMin = if (wantDurFilter) nonJavMinMin else 0, newest = newest)
            }.getOrNull()
            absorb(singlePage)
        }

        // Pass 4 (Adaptive relaxation!): If URL duration filter was too strict, relax URL param
        // while STILL keeping our client-side >=20m / >=15m duration filter (so 4-6m clips never sneak in!).
        if (collected.size < MIN_HEALTHY_YIELD && wantDurFilter) {
            val qRaw = primaryConcept ?: tasteQueries[idx % tasteQueries.size]
            val q = conceptQueryForSource(src, qRaw)
            val relaxedPage = runCatching {
                searchSource(src.id, page, q, minDurationMin = 0, newest = newest)
            }.getOrNull()
            absorb(relaxedPage)
        }

        // Pass 5: Final fallback to source browse (still filtered by hardMinDur!).
        if (collected.size < 4) {
            val fallbackPage = runCatching { browseSource(src.id, page, latest = newest) }.getOrNull()
            absorb(fallbackPage)
        }

        // Only if < 4 long videos exist for this source, backfill from 15–19m medium pool (never 4-6m!).
        if (collected.size < 4 && softMediumPool.isNotEmpty()) {
            collected += softMediumPool.take(6 - collected.size)
        }

        return AnimesPage(collected, anyNext)
    }

    private fun conceptQueryForSource(src: SourceDef, concept: String): String {
        val lower = concept.lowercase(Locale.ROOT)
        if (src.isArabic) {
            return ARABIC_CONCEPT_QUERIES[lower] ?: concept
        }
        return when (lower) {
            "mmf" -> if (src.isJav) "3p" else "mmf threesome"
            "threesome" -> if (src.isJav) "threesome" else "mmf threesome"
            "dp" -> if (src.isJav) "anal" else "double penetration"
            else -> concept
        }
    }

    private fun browseSourceByConcept(
        srcId: String,
        page: Int,
        concept: String,
        newest: Boolean = false,
        minDurationMin: Int = 0,
    ): AnimesPage? = when (srcId) {
        "sextb" -> {
            val slug = SEXTB_CONCEPT_GENRES[concept] ?: return null
            val genrePath = resolveSexTbGenrePath(slug)
            val path = if (page > 1) "$genrePath/pg-$page" else genrePath
            val sort = if (newest) "desc" else "viewed"
            val html = fetchHtml(cfClientFor("https://sextb.net"), "https://sextb.net$path?sort=$sort", "https://sextb.net/")
            parseSexTbList(Jsoup.parse(html, "https://sextb.net"))
        }

        "pornhub" -> {
            // For MMF / threesome, prefer targeted "mmf threesome" search over generic c=65
            // (which is heavily FFM 1-guy-2-girls) so 2-guys-1-girl results dominate!
            if (concept == "mmf" || concept == "threesome") {
                return searchSource("pornhub", page, "mmf threesome", minDurationMin = minDurationMin, newest = newest)
            }
            val catId = PORNHUB_CONCEPT_CATEGORIES[concept] ?: return null
            val sort = if (newest) "cm" else "ht"
            val durParam = if (minDurationMin >= 30) {
                "&min_duration=30"
            } else if (minDurationMin >= 20) {
                "&min_duration=20"
            } else {
                ""
            }
            val html = fetchHtml(pornhubClient, "https://www.pornhub.com/video?c=$catId&o=$sort$durParam&page=$page", "https://www.pornhub.com/")
            parsePornHubList(Jsoup.parse(html, "https://www.pornhub.com"))
        }

        "supjav" -> {
            val slug = SUPJAV_CONCEPT_TAGS[concept] ?: return null
            val pg = if (page > 1) "/page/$page" else ""
            val html = fetchHtml(cfClientFor("https://supjav.com"), "https://supjav.com/tag/$slug$pg", "https://supjav.com/")
            parseSupJavList(Jsoup.parse(html, "https://supjav.com"))
        }

        "jable" -> {
            val slug = JABLE_CONCEPT_TAGS[concept] ?: return null
            val sortBy = if (newest) "post_date" else "video_viewed"
            val url = "https://jable.tv/tags/$slug/?lang=en&sort_by=$sortBy&from=${"%02d".format(page)}"
            val html = fetchHtml(cfClientFor("https://jable.tv"), url, "https://jable.tv/")
            parseJableList(Jsoup.parse(html, "https://jable.tv"))
        }

        "missav" -> {
            val slug = MISSAV_CONCEPT_GENRES[concept] ?: return null
            val sort = if (newest) "released_at" else "monthly_views"
            val html = fetchHtml(cfClientFor("https://missav.ws"), "https://missav.ws/en/genres/$slug?sort=$sort&page=$page", "https://missav.ws/")
            parseMissAvList(Jsoup.parse(html, "https://missav.ws"))
        }

        "javgg" -> {
            val slug = JAVGG_CONCEPT_GENRES[concept] ?: return null
            val pg = if (page > 1) "page/$page/" else ""
            val html = fetchHtml(cfClientFor("https://javgg.net"), "https://javgg.net/genre/$slug/$pg", "https://javgg.net/")
            parseJavGgList(Jsoup.parse(html, "https://javgg.net"))
        }

        "javguru" -> {
            val slug = JAVGURU_CONCEPT_TAGS[concept] ?: return null
            val pg = if (page > 1) "page/$page/" else ""
            val html = fetchHtml(cfClientFor("https://jav.guru"), "https://jav.guru/tag/$slug/$pg", "https://jav.guru/")
            parseJavGuruList(Jsoup.parse(html, "https://jav.guru"))
        }

        "xmoviesforyou" -> {
            val slug = XMOVIES_CONCEPT_CATS[concept] ?: return null
            val pg = if (page > 1) "?page=$page" else ""
            val html = fetchHtml(cfClientFor("https://xmoviesforyou.com"), "https://xmoviesforyou.com/category/$slug$pg", "https://xmoviesforyou.com/")
            parseXMoviesList(Jsoup.parse(html, "https://xmoviesforyou.com"), page)
        }

        else -> null
    }

    private fun isSyntheticFallbackQuery(q: String): Boolean {
        if (q.length > 55) return true
        val lower = q.lowercase(Locale.ROOT)
        if (lower.startsWith("source:") ||
            lower.startsWith("label:") ||
            lower.startsWith("studio:") ||
            lower.startsWith("uploader:") ||
            lower.startsWith("duration:") ||
            lower.startsWith("[sextb") ||
            lower.startsWith("[pornhub") ||
            lower.startsWith("[xvideos") ||
            lower.startsWith("[xnxx") ||
            lower.startsWith("[rule34") ||
            lower.startsWith("[sexalarab") ||
            lower.startsWith("[supjav") ||
            lower.startsWith("[jable") ||
            lower.startsWith("[missav") ||
            lower.startsWith("[javgg") ||
            lower.startsWith("[javguru") ||
            lower.startsWith("[xmovies") ||
            lower.startsWith("[aflamk1") ||
            lower.startsWith("[arabx") ||
            lower == "featured actress" ||
            lower == "single work" ||
            lower == "solo work"
        ) {
            return true
        }
        // Drop Tadami AnimeSearchFallbackEngine burst queries matching entities we JUST returned
        // from getAnimeDetails within the last 14 seconds (prevents xvideos HTTP 429 storms!).
        val detailTs = recentDetailEntities[lower]
        if (detailTs != null && System.currentTimeMillis() - detailTs < 14_000L) {
            return true
        }
        return q.any { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' } && q.length > 24
    }

    private fun searchAcrossSources(
        page: Int,
        query: String,
        sources: List<SourceDef>,
    ): AnimesPage {
        val batches = fetchInParallel(sources) { _, src ->
            searchSource(src.id, page, query, minDurationMin = 0, newest = false)
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
        val pool = Executors.newFixedThreadPool(sources.size.coerceAtMost(6))
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
        rememberDetailEntitiesForFallbackGuard(details)
        if (tasteEngine.isEnabled()) {
            tasteEngine.recordDetails(anime, details)
            if (srcId != null) tasteEngine.recordSourceUsage(srcId, 1.5)
        }
        details
    }

    private fun rememberDetailEntitiesForFallbackGuard(details: SAnime) {
        val now = System.currentTimeMillis()
        if (recentDetailEntities.size > 150) {
            recentDetailEntities.entries.removeIf { now - it.value > 20_000L }
        }
        details.genre?.split(',')?.forEach { g ->
            val k = g.trim().lowercase(Locale.ROOT)
            if (k.length >= 2) recentDetailEntities[k] = now
        }
        details.artist?.split(',')?.forEach { a ->
            val k = a.trim().lowercase(Locale.ROOT)
            if (k.length >= 2) recentDetailEntities[k] = now
        }
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

    // ======================== 15 Source Connectors ========================

    private fun detectSourceId(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
        return when {
            "pornhub.com" in host -> "pornhub"
            "sextb.net" in host -> "sextb"
            "xvideos.com" in host -> "xvideos"
            "xnxx.com" in host -> "xnxx"
            "xmoviesforyou.com" in host -> "xmoviesforyou"
            "supjav.com" in host -> "supjav"
            "jable.tv" in host -> "jable"
            "missav." in host -> "missav"
            "javgg.net" in host -> "javgg"
            "jav.guru" in host -> "javguru"
            "aflamk1." in host -> "aflamk1"
            "sexalarab." in host -> "sexalarab"
            "arabx." in host -> "arabx"
            "arabxn." in host -> "arabxn"
            "rule34video.com" in host -> "rule34video"
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
            val baseGenre = resolveSexTbGenrePath("ltx-encode")
            val path = if (latest && page == 1) {
                "/new-releases"
            } else if (page > 1) {
                "$baseGenre/pg-$page"
            } else {
                baseGenre
            }
            val url = "https://sextb.net$path" + if (!latest) "?sort=viewed" else ""
            val html = fetchHtml(cfClientFor("https://sextb.net"), url, "https://sextb.net/")
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

        "xmoviesforyou" -> {
            val url = if (latest) {
                if (page <= 1) "https://xmoviesforyou.com/" else "https://xmoviesforyou.com/page/$page"
            } else {
                "https://xmoviesforyou.com/most-viewed"
            }
            val html = fetchHtml(cfClientFor("https://xmoviesforyou.com"), url, "https://xmoviesforyou.com/")
            parseXMoviesList(Jsoup.parse(html, "https://xmoviesforyou.com"), page)
        }

        "supjav" -> {
            val url = if (latest) {
                "https://supjav.com/${if (page > 1) "page/$page" else ""}"
            } else {
                "https://supjav.com/popular${if (page > 1) "/page/$page" else ""}"
            }
            val html = fetchHtml(cfClientFor("https://supjav.com"), url, "https://supjav.com/")
            parseSupJavList(Jsoup.parse(html, "https://supjav.com"))
        }

        "jable" -> {
            val seg = if (latest) "latest-updates" else "hot"
            val sortBy = if (latest) "post_date" else "video_viewed_week"
            val url = "https://jable.tv/$seg/?lang=en&sort_by=$sortBy&from=${"%02d".format(page)}"
            val html = fetchHtml(cfClientFor("https://jable.tv"), url, "https://jable.tv/")
            parseJableList(Jsoup.parse(html, "https://jable.tv"))
        }

        "missav" -> {
            val seg = if (latest) "en/new" else "en/today-hot"
            val html = fetchHtml(cfClientFor("https://missav.ws"), "https://missav.ws/$seg?page=$page", "https://missav.ws/")
            parseMissAvList(Jsoup.parse(html, "https://missav.ws"))
        }

        "javgg" -> {
            val url = if (latest) "https://javgg.net/new-post/page/$page/" else "https://javgg.net/trending/page/$page/"
            val html = fetchHtml(cfClientFor("https://javgg.net"), url, "https://javgg.net/")
            parseJavGgList(Jsoup.parse(html, "https://javgg.net"))
        }

        "javguru" -> {
            val url = if (latest) {
                if (page <= 1) "https://jav.guru/" else "https://jav.guru/page/$page/"
            } else {
                "https://jav.guru/most-watched-rank/"
            }
            val html = fetchHtml(cfClientFor("https://jav.guru"), url, "https://jav.guru/")
            parseJavGuruList(Jsoup.parse(html, "https://jav.guru"))
        }

        "aflamk1" -> {
            val seg = if (latest) "latest-updates" else "most-popular"
            val url = "https://www.aflamk1.net/$seg/${if (page > 1) "$page/" else ""}"
            val html = fetchHtml(cfClientFor("https://www.aflamk1.net"), url, "https://www.aflamk1.net/")
            parseKvsList(Jsoup.parse(html, "https://www.aflamk1.net"), "https://www.aflamk1.net", "Aflamk1")
        }

        "sexalarab" -> {
            val seg = if (latest) "latest-updates" else "most-popular"
            val url = "https://sexalarab.tv/$seg/${if (page > 1) "$page/" else ""}"
            val html = fetchHtml(cfClientFor("https://sexalarab.tv"), url, "https://sexalarab.tv/")
            parseKvsList(Jsoup.parse(html, "https://sexalarab.tv"), "https://sexalarab.tv", "SexAlArab")
        }

        "arabx" -> {
            val seg = if (latest) "latest-updates" else "most-popular"
            val url = "https://www.arabx.cam/$seg/${if (page > 1) "$page/" else ""}"
            val html = fetchHtml(cfClientFor("https://www.arabx.cam"), url, "https://www.arabx.cam/")
            parseKvsList(Jsoup.parse(html, "https://www.arabx.cam"), "https://www.arabx.cam", "ArabX")
        }

        "arabxn" -> {
            val url = if (latest) {
                if (page <= 1) "https://www.arabxn.com/" else "https://www.arabxn.com/page/$page"
            } else {
                if (page <= 1) "https://www.arabxn.com/video/" else "https://www.arabxn.com/video/page/$page"
            }
            val html = fetchHtml(cfClientFor("https://www.arabxn.com"), url, "https://www.arabxn.com/")
            parseArabXnList(Jsoup.parse(html, "https://www.arabxn.com"))
        }

        "rule34video" -> {
            val seg = if (latest) "latest-updates" else "most-popular"
            val url = "https://rule34video.com/$seg/${if (page > 1) "$page/" else ""}"
            val html = fetchHtml(baseHttpClient, url, "https://rule34video.com/")
            parseKvsList(Jsoup.parse(html, "https://rule34video.com"), "https://rule34video.com", "Rule34")
        }

        else -> AnimesPage(emptyList(), false)
    }

    private fun searchSource(
        srcId: String,
        page: Int,
        query: String,
        minDurationMin: Int = 0,
        newest: Boolean = false,
    ): AnimesPage {
        val clean = query.trim()
        val enc = URLEncoder.encode(clean, "UTF-8")
        return when (srcId) {
            "pornhub" -> {
                val durParam = if (minDurationMin >= 30) {
                    "&min_duration=30"
                } else if (minDurationMin >= 20) {
                    "&min_duration=20"
                } else {
                    ""
                }
                val sortParam = if (newest) "&o=mr" else ""
                val pageParam = if (page > 1) "&page=$page" else ""
                val url = "https://www.pornhub.com/video/search?search=$enc$durParam$sortParam$pageParam"
                val html = fetchHtml(pornhubClient, url, "https://www.pornhub.com/")
                parsePornHubList(Jsoup.parse(html, "https://www.pornhub.com"))
            }

            "sextb" -> searchSexTbAjax(clean, page)

            "xvideos" -> {
                val durParam = if (minDurationMin >= 20) "&durf=20min_more" else ""
                val sortParam = if (newest) "&sort=uploaddate" else ""
                val url = "https://www.xvideos.com/?k=$enc&p=${(page - 1).coerceAtLeast(0)}$durParam$sortParam"
                val html = fetchHtml(baseHttpClient, url, "https://www.xvideos.com/")
                parseWgczList(Jsoup.parse(html, "https://www.xvideos.com"), "https://www.xvideos.com", "XVideos")
            }

            "xnxx" -> {
                val durSeg = if (minDurationMin >= 20) "20min+/" else ""
                val url = "https://www.xnxx.com/search/$durSeg$enc/${(page - 1).coerceAtLeast(0)}"
                val html = fetchHtml(baseHttpClient, url, "https://www.xnxx.com/")
                parseWgczList(Jsoup.parse(html, "https://www.xnxx.com"), "https://www.xnxx.com", "XNXX")
            }

            "xmoviesforyou" -> {
                val sort = if (newest) "latest" else "relevance"
                val pg = if (page > 1) "&page=$page" else ""
                val url = "https://xmoviesforyou.com/new-search?q=$enc&sort=$sort$pg"
                val html = fetchHtml(cfClientFor("https://xmoviesforyou.com"), url, "https://xmoviesforyou.com/")
                parseXMoviesList(Jsoup.parse(html, "https://xmoviesforyou.com"), page)
            }

            "supjav" -> {
                val pg = if (page > 1) "page/$page/" else ""
                val url = "https://supjav.com/$pg?s=$enc"
                val html = fetchHtml(cfClientFor("https://supjav.com"), url, "https://supjav.com/")
                parseSupJavList(Jsoup.parse(html, "https://supjav.com"))
            }

            "jable" -> {
                val url = "https://jable.tv/search/$enc/?lang=en&q=$enc&from=${"%02d".format(page)}"
                val html = fetchHtml(cfClientFor("https://jable.tv"), url, "https://jable.tv/")
                parseJableList(Jsoup.parse(html, "https://jable.tv"))
            }

            "missav" -> {
                val url = "https://missav.ws/en/search/$enc?page=$page"
                val html = fetchHtml(cfClientFor("https://missav.ws"), url, "https://missav.ws/")
                parseMissAvList(Jsoup.parse(html, "https://missav.ws"))
            }

            "javgg" -> {
                val pg = if (page > 1) "page/$page/" else ""
                val url = "https://javgg.net/$pg?s=$enc"
                val html = fetchHtml(cfClientFor("https://javgg.net"), url, "https://javgg.net/")
                parseJavGgList(Jsoup.parse(html, "https://javgg.net"))
            }

            "javguru" -> {
                val pg = if (page > 1) "page/$page/" else ""
                val url = "https://jav.guru/$pg?s=$enc"
                val html = fetchHtml(cfClientFor("https://jav.guru"), url, "https://jav.guru/")
                parseJavGuruList(Jsoup.parse(html, "https://jav.guru"))
            }

            "aflamk1" -> {
                val sort = if (minDurationMin >= 20) "&sort_by=duration" else ""
                val url = "https://www.aflamk1.net/search/?q=$enc$sort" + if (page > 1) "&from_videos=$page" else ""
                val html = fetchHtml(cfClientFor("https://www.aflamk1.net"), url, "https://www.aflamk1.net/")
                parseKvsList(Jsoup.parse(html, "https://www.aflamk1.net"), "https://www.aflamk1.net", "Aflamk1")
            }

            "sexalarab" -> {
                val sort = if (minDurationMin >= 20) "&sort_by=duration" else ""
                val url = "https://sexalarab.tv/search/?q=$enc$sort&from_videos=$page"
                val html = fetchHtml(cfClientFor("https://sexalarab.tv"), url, "https://sexalarab.tv/")
                parseKvsList(Jsoup.parse(html, "https://sexalarab.tv"), "https://sexalarab.tv", "SexAlArab")
            }

            "arabx" -> {
                val slug = URLEncoder.encode(clean.replace(Regex("""\s+"""), "-"), "UTF-8")
                val params = buildList {
                    if (minDurationMin >= 20) add("sort_by=duration")
                    if (page > 1) add("from_videos=$page")
                }.joinToString("&")
                val url = "https://www.arabx.cam/search/$slug/" + if (params.isNotBlank()) "?$params" else ""
                val html = fetchHtml(cfClientFor("https://www.arabx.cam"), url, "https://www.arabx.cam/")
                parseKvsList(Jsoup.parse(html, "https://www.arabx.cam"), "https://www.arabx.cam", "ArabX")
            }

            "arabxn" -> {
                val pg = if (page > 1) "/page/$page" else ""
                val url = "https://www.arabxn.com/search/$enc$pg"
                val html = fetchHtml(cfClientFor("https://www.arabxn.com"), url, "https://www.arabxn.com/")
                parseArabXnList(Jsoup.parse(html, "https://www.arabxn.com"))
            }

            "rule34video" -> {
                val slug = clean.lowercase(Locale.ROOT).replace(Regex("""\s+"""), "-")
                val url = "https://rule34video.com/search/${URLEncoder.encode(slug, "UTF-8")}/${if (page > 1) "$page/" else ""}"
                val html = fetchHtml(baseHttpClient, url, "https://rule34video.com/")
                parseKvsList(Jsoup.parse(html, "https://rule34video.com"), "https://rule34video.com", "Rule34")
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
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://sextb.net") ?: return@mapNotNull null
                val code = el.selectFirst("div.tray-item-code")?.text()?.trim().orEmpty()
                val rawTitle = el.selectFirst("div.tray-item-title")?.text()?.trim()
                    ?.ifBlank { a.attr("title").trim() }
                    ?: return@mapNotNull null
                val dur = el.selectFirst("div.tray-item-runtime")?.text()?.trim().orEmpty()
                val img = el.selectFirst("img")
                val thumb = extractBestThumb(img, "https://sextb.net")
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
        acquireSourceSlot("sextb.net")
        cfClientFor("https://sextb.net").newCall(POST("https://sextb.net/ajax/search", reqHeaders, form)).execute().use { resp ->
            if (!resp.isSuccessful) return AnimesPage(emptyList(), false)
            val json = JSONObject(resp.body.string())
            val hits = json.optJSONArray("hits") ?: return AnimesPage(emptyList(), false)
            val items = buildList {
                for (i in 0 until hits.length()) {
                    val obj = hits.optJSONObject(i) ?: continue
                    val code = obj.optString("code").trim().lowercase(Locale.ROOT)
                    if (code.isBlank()) continue
                    val rawTitle = Jsoup.parse(obj.optString("name")).text().trim()
                    val runtime = obj.optString("runtimes").trim().let { if (it.isNotBlank() && it != "0") "${it}m" else "" }
                    val poster = normalizeOriginUrl(obj.optString("poster"), "https://sextb.net")
                    val fullTitle = "[${code.uppercase(Locale.ROOT)}] $rawTitle"
                    add(
                        SAnime.create().apply {
                            url = "https://sextb.net/$code"
                            title = buildCardTitle("SexTB", fullTitle, runtime)
                            thumbnail_url = poster
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
                val dur = el.selectFirst("span.duration, span.metadata, p.metadata")?.text()?.let { text ->
                    Regex("""\b(\d+:\d+|\d+\s*min)\b""", RegexOption.IGNORE_CASE).find(text)?.value
                }?.trim().orEmpty()
                val thumb = extractBestThumb(el.selectFirst("img"), origin)
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
        val items = doc.select("div.list-videos div.item, div.item:has(a[href])")
            .mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, origin) ?: return@mapNotNull null
                if (href == "$origin/" || href == origin) return@mapNotNull null
                val rawTitle = a.attr("title").trim().ifBlank {
                    el.selectFirst("strong.title, div.title, a.th div.thumb_title")?.text()?.trim().orEmpty()
                }
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = el.selectFirst("div.duration, span.duration, div.time, span.time, div.time_length, span.label")?.text()?.trim().orEmpty()
                val thumb = extractBestThumb(el.selectFirst("img"), origin)
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

    private fun parseSupJavList(doc: Document): AnimesPage {
        val items = doc.select("div.posts > div.post")
            .mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://supjav.com") ?: return@mapNotNull null
                val img = el.selectFirst("img")
                val rawTitle = img?.attr("alt")?.trim()?.ifBlank { null }
                    ?: a.attr("title").trim().ifBlank { null }
                    ?: el.selectFirst("h3, h2")?.text()?.trim()
                    ?: return@mapNotNull null
                val dur = el.selectFirst("span.duration, span.time, div.duration, time")?.text()?.trim().orEmpty()
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("SupJav", rawTitle, dur)
                    thumbnail_url = extractBestThumb(img, "https://supjav.com")
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("a.next[href], a[rel=next][href], div.pagination li.active + li a[href]") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseJableList(doc: Document): AnimesPage {
        val items = doc.select(".container .video-img-box")
            .mapNotNull { el ->
                val a = el.selectFirst(".img-box a[href]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://jable.tv") ?: return@mapNotNull null
                val rawTitle = el.selectFirst(".detail .title")?.text()?.trim().orEmpty()
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = el.selectFirst("span.label, span.duration, span.time, .img-box span")?.text()?.trim()
                    ?.takeIf { ":" in it }.orEmpty()
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("Jable", rawTitle, dur)
                    thumbnail_url = extractBestThumb(el.selectFirst(".img-box img"), "https://jable.tv")
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst(".pagination li.active + li a, .pagination a.next, a[rel=next]") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseMissAvList(doc: Document): AnimesPage {
        val items = doc.select("div[class*=thumbnail]")
            .mapNotNull { el ->
                val a = el.selectFirst("a[class*=text-secondary][href], a[href][alt]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://missav.ws") ?: return@mapNotNull null
                if (href.endsWith("#") || "javascript:" in href) return@mapNotNull null
                val rawTitle = a.text().ifBlank { a.attr("alt") }.trim()
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = el.selectFirst("span[class*=duration], span[class*=time], span.font-mono, time")?.text()?.trim()
                    ?.takeIf { ":" in it }.orEmpty()
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("MissAV", rawTitle, dur)
                    thumbnail_url = extractBestThumb(el.selectFirst("img"), "https://missav.ws")
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("a[rel=next], nav a:contains(›)") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseJavGgList(doc: Document): AnimesPage {
        val items = doc.select("article.item, div.items article")
            .mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://javgg.net") ?: return@mapNotNull null
                val rawTitle = el.selectFirst("h3 a, h3, .data h3")?.text()?.trim()?.ifBlank { a.attr("title").trim() }.orEmpty()
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = el.selectFirst("span.duration, span.time, div.duration, span.quality")?.text()?.trim()
                    ?.takeIf { ":" in it || "min" in it.lowercase(Locale.ROOT) }.orEmpty()
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("JavGG", rawTitle, dur)
                    thumbnail_url = extractBestThumb(el.selectFirst("img"), "https://javgg.net")
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("a.arrow_pag[href], a[rel=next], div.pagination a.next") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseJavGuruList(doc: Document): AnimesPage {
        val items = doc.select("div.inside-article, div.row > div.imgg")
            .mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://jav.guru") ?: return@mapNotNull null
                val rawTitle = el.selectFirst("h2 a, h2, .grid1 a")?.text()?.trim()?.ifBlank { a.attr("title").trim() }.orEmpty()
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = el.selectFirst("span.duration, span.time, div.duration, time")?.text()?.trim()
                    ?.takeIf { ":" in it || "min" in it.lowercase(Locale.ROOT) }.orEmpty()
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("JavGuru", rawTitle, dur)
                    thumbnail_url = extractBestThumb(el.selectFirst("img"), "https://jav.guru")
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("a.next.page-numbers, a[rel=next]") != null
        return AnimesPage(items, hasNext)
    }

    private fun parseXMoviesList(doc: Document, page: Int): AnimesPage {
        val elements = doc.select("a.card").ifEmpty {
            doc.select("section a.group.flex.flex-col").ifEmpty {
                doc.select("main a.group.flex.flex-col, a[data-video-card]")
            }
        }
        val items = elements.mapNotNull { el ->
            val href = normalizeOriginUrl(el.absUrl("href").ifBlank { el.attr("href") }, "https://xmoviesforyou.com") ?: return@mapNotNull null
            if ("/category/" in href || "/studio/" in href || "/pornstar/" in href || "/page/" in href) return@mapNotNull null
            val img = el.selectFirst("img")
            val rawTitle = el.selectFirst("h3.title, h3, h2")?.text()?.trim()?.ifBlank { null }
                ?: img?.attr("alt")?.trim()?.ifBlank { null }
                ?: el.attr("title").trim()
            if (rawTitle.isBlank()) return@mapNotNull null
            val dur = el.selectFirst("span.duration, div.duration, span.time, time, span[class*=bottom-]")?.text()?.trim()
                ?.takeIf { ":" in it || "min" in it.lowercase(Locale.ROOT) }.orEmpty()
            SAnime.create().apply {
                url = href
                title = buildCardTitle("XMovies", rawTitle, dur)
                thumbnail_url = extractBestThumb(img, "https://xmoviesforyou.com")
            }
        }.distinctBy { it.url }.take(18)
        val hasNext = items.isNotEmpty() && doc.select("a[href]").any {
            it.text().trim().equals("Next", true) || "page=${page + 1}" in it.attr("href") || it.attr("href").endsWith("/page/${page + 1}")
        }
        return AnimesPage(items, hasNext)
    }

    private fun parseArabXnList(doc: Document): AnimesPage {
        val items = doc.select("article.video-card, article.rowvideo, article")
            .mapNotNull { el ->
                val a = el.selectFirst("a[href*=/watch-], a[href]") ?: return@mapNotNull null
                val href = normalizeOriginUrl(a.attr("abs:href").ifBlank { a.attr("href") }, "https://www.arabxn.com") ?: return@mapNotNull null
                if ("/watch-" !in href) return@mapNotNull null
                val rawTitle = a.attr("title").trim().ifBlank {
                    el.selectFirst("h2, h3, strong.title, .title")?.text()?.trim().orEmpty()
                }
                if (rawTitle.isBlank()) return@mapNotNull null
                val dur = el.selectFirst("div.timevideo1, div.timevideo, div.duration, span.duration, span.time, time")?.text()?.trim().orEmpty()
                SAnime.create().apply {
                    url = href
                    title = buildCardTitle("ArabXN", rawTitle, dur)
                    thumbnail_url = extractBestThumb(el.selectFirst("img"), "https://www.arabxn.com")
                }
            }
            .distinctBy { it.url }
            .take(18)
        val hasNext = doc.selectFirst("a[rel=next], nav.catalog-pagination span.is-current ~ a[href]") != null
        return AnimesPage(items, hasNext)
    }

    /**
     * Places BOTH the source badge AND video duration at the START of the card title
     * (e.g. `[PornHub • 32:15] Title...` or `[SexTB • 120m] [SONE-248] ...`) so duration is
     * ALWAYS visible on 2-line grid cards and never truncated at the end!
     */
    private fun buildCardTitle(badge: String, rawTitle: String, duration: String): String {
        val cleanDur = duration.trim()
            .replace(Regex("""(?i)\s*mins?\b"""), "m")
            .replace(Regex("""^[^\d]+"""), "")
            .trim()
        val prefix = if (cleanDur.isNotBlank()) "[$badge • $cleanDur]" else "[$badge]"
        return "$prefix ${rawTitle.trim()}"
    }

    private fun extractBestThumb(img: Element?, origin: String): String? {
        if (img == null) return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-original"),
            img.attr("data-webp"),
            img.attr("data-lazy-src"),
            img.attr("data-image"),
            img.attr("poster"),
            img.attr("src"),
        )
        val picked = candidates.firstOrNull { raw ->
            val t = raw.trim()
            t.isNotBlank() &&
                !t.startsWith("data:", ignoreCase = true) &&
                "blank.gif" !in t &&
                "lightbox-blank" !in t &&
                "default-cover" !in t &&
                "no-poster" !in t
        } ?: return null
        return normalizeOriginUrl(picked, origin)
    }

    private fun resolveSexTbGenrePath(slug: String): String {
        val now = System.currentTimeMillis()
        if (sexTbHashedGenres.isEmpty() || now - sexTbGenresFetchedAtMs > 30 * 60 * 1000L) {
            runCatching {
                val html = fetchHtml(cfClientFor("https://sextb.net"), "https://sextb.net/genres", "https://sextb.net/")
                val doc = Jsoup.parse(html, "https://sextb.net")
                for (a in doc.select("a[href*=/genre/]")) {
                    val path = a.attr("href").substringBefore('?').trimEnd('/')
                    if (!path.startsWith("/genre/")) continue
                    val fullSlug = path.removePrefix("/genre/")
                    val baseSlug = fullSlug.replace(Regex("""-[a-z0-9]{6,10}$"""), "")
                    sexTbHashedGenres[baseSlug] = path
                    sexTbHashedGenres[fullSlug] = path
                }
                sexTbGenresFetchedAtMs = now
            }
        }
        return sexTbHashedGenres[slug] ?: "/genre/$slug"
    }

    private fun normalizeOriginUrl(raw: String?, origin: String): String? {
        val clean = raw?.trim()?.trim('"', '\'') ?: return null
        if (clean.isBlank()) return null
        val absolute = when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "${origin.trimEnd('/')}$clean"
            else -> "${origin.trimEnd('/')}/${clean.removePrefix("/")}"
        }
        // Tadami Aurora's Coil cover fetcher blacklists `cdna.sextb.net`; `cdn001.imggle.net`
        // serves the exact same image path without being blocked!
        return absolute.replace(SEXTB_DEAD_CDN_REGEX, "https://cdn001.imggle.net/")
    }

    // ---- Details Parser ----

    private fun parseDetailsForSource(srcId: String?, fullUrl: String, fallback: SAnime): SAnime {
        val httpClient = clientForSource(srcId)
        val origin = originOf(fullUrl)
        val html = runCatching { fetchHtml(httpClient, fullUrl, "$origin/") }.getOrNull()
            ?: return SAnime.create().apply {
                url = fullUrl
                title = fallback.title
                thumbnail_url = fallback.thumbnail_url
                status = SAnime.COMPLETED
            }
        val doc = Jsoup.parse(html, fullUrl)
        val parsedTitle = doc.selectFirst("h1.title span.inlineFree, h1.film-info-title, h1.page-title, div.post-meta h2, .header-left h4, main h1, h1")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() }
            ?.ifBlank { fallback.title }
            ?: fallback.title

        val thumb = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.trim()
            ?.let { normalizeOriginUrl(it, origin) }
            ?: extractBestThumb(doc.selectFirst("div.film-poster img, img.poster, div.post-meta img, #player img"), origin)
            ?: fallback.thumbnail_url

        val tags = when (srcId) {
            "pornhub" -> doc.select("div.categoriesWrapper a, div.tagsWrapper a").eachText()
            "sextb" -> doc.select("div.description:contains(Genre) a").eachText()
            "supjav" -> doc.select("div.tags > a").eachText()
            "jable" -> doc.select(".tags a").eachText()
            "missav" -> doc.select("a[href*=/genres/], a[href*=/tags/]").eachText()
            "javgg", "javguru" -> doc.select("a[href*=/genre/], a[href*=/tag/]").eachText()
            "xmoviesforyou" -> doc.select("a[href^='/category/'], a[href^='/tag/']").eachText()
            "xvideos", "xnxx" -> doc.select("div.video-metadata a.is-keyword, div.video-tags-list a").eachText()
            else -> doc.select("div.info a[href*=/tag], div.info a[href*=/categor], a[href*=/tags/], a.taga[href*=/tag/]").eachText()
        }.map(String::trim).filter(String::isNotBlank).distinct().take(15)

        val stars = when (srcId) {
            "pornhub" -> doc.select("div.pornstarsWrapper a, div.userInfo a.bolded").eachText()
            "sextb" -> doc.select("div.description:contains(Cast) a").eachText()
            "supjav" -> doc.select("p:contains(Cast :) > a").eachText()
            "jable" -> doc.select(".header-left .model span[title]").eachAttr("title")
            "xmoviesforyou" -> doc.select("a[href^='/pornstar/']").eachText()
            "xvideos", "xnxx" -> doc.select("div.video-metadata a.model, li.model a").eachText()
            else -> doc.select("div.info a[href*=/models/], div.info a[href*=/pornstar], a.taga[href*=/pornstar/]").eachText()
        }.map(String::trim).filter(String::isNotBlank).distinct().take(8)

        val detailDur = tasteEngine.extractDurationMinutes(
            fallback.title,
            doc.selectFirst("meta[property=video:duration], meta[itemprop=duration]")?.attr("content"),
            doc.selectFirst("div.description:contains(Runtime), span.duration, div.duration, .header-right")?.text(),
        )

        val desc = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()
        return SAnime.create().apply {
            url = fullUrl
            title = if (detailDur != null && detailDur > 0 && !parsedTitle.contains("${detailDur}m") && !parsedTitle.contains(":")) {
                "[${detailDur}m] $parsedTitle"
            } else {
                parsedTitle
            }
            thumbnail_url = thumb
            genre = tags.joinToString().ifBlank { null }
            artist = stars.joinToString().ifBlank { null }
            author = srcId?.uppercase(Locale.ROOT)
            description = buildList {
                if (srcId != null) add("Source: ${ALL_SOURCES.firstOrNull { it.id == srcId }?.label ?: srcId}")
                if (detailDur != null && detailDur > 0) add("Duration: $detailDur min")
                if (!desc.isNullOrBlank()) add(desc)
            }.joinToString("\n").ifBlank { null }
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ---- Video Extractors (All 15 Sources) ----

    private fun extractVideosForSource(srcId: String, fullUrl: String): List<Video> = when (srcId) {
        "pornhub" -> extractPornHubVideos(fullUrl)
        "sextb" -> extractSexTbVideos(fullUrl)
        "xvideos" -> extractWgczVideos(fullUrl, "https://www.xvideos.com", "XVideos")
        "xnxx" -> extractWgczVideos(fullUrl, "https://www.xnxx.com", "XNXX")
        "xmoviesforyou" -> extractXMoviesVideos(fullUrl)
        "supjav" -> extractSupJavVideos(fullUrl)
        "jable" -> extractJableVideos(fullUrl)
        "missav" -> extractMissAvVideos(fullUrl)
        "javgg" -> extractJavGgVideos(fullUrl)
        "javguru" -> extractJavGuruVideos(fullUrl)
        "aflamk1" -> extractKvsVideos(cfClientFor("https://www.aflamk1.net"), fullUrl, "Aflamk1")
        "sexalarab" -> extractKvsVideos(cfClientFor("https://sexalarab.tv"), fullUrl, "SexAlArab")
        "arabx" -> extractArabXVideos(fullUrl)
        "arabxn" -> extractArabXnVideos(fullUrl)
        "rule34video" -> extractKvsVideos(baseHttpClient, fullUrl, "Rule34")
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
        val sextbClient = cfClientFor("https://sextb.net")
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

            val extracted = extractGenericEmbed(iframeUrl, pageUrl, "SexTB")
            if (extracted.isNotEmpty()) {
                videos += extracted
                break
            }
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractJableVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(cfClientFor("https://jable.tv"), pageUrl, "https://jable.tv/")
        val m3u8 = Regex("""\bhlsUrl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val playHeaders = desktopHeaders("https://jable.tv/").newBuilder().set("Origin", "https://jable.tv").build()
        return runCatching {
            playlistUtils.extractFromHls(m3u8, referer = "https://jable.tv/", masterHeaders = playHeaders, videoHeaders = playHeaders, videoNameGen = { "Jable - $it" })
        }.getOrDefault(emptyList()).ifEmpty {
            listOf(Video(m3u8, "Jable - HLS", m3u8, headers = playHeaders))
        }
    }

    private fun extractMissAvVideos(pageUrl: String): List<Video> {
        val origin = originOf(pageUrl)
        val html = fetchHtml(cfClientFor(origin), pageUrl, "$origin/")
        val doc = Jsoup.parse(html, pageUrl)
        val scripts = doc.select("script").joinToString("\n") { s ->
            val d = s.data()
            if ("function(p,a,c,k,e,d)" in d) autoUnpacker(d) ?: d else d
        }
        val m3u8 = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(scripts)?.value ?: return emptyList()
        val refOrigin = if ("missav.fans" in origin) "https://missav.ws" else origin
        val playHeaders = desktopHeaders("$refOrigin/").newBuilder().set("Origin", refOrigin).build()
        return runCatching {
            playlistUtils.extractFromHls(m3u8, referer = "$refOrigin/", masterHeaders = playHeaders, videoHeaders = playHeaders, videoNameGen = { "MissAV - $it" })
        }.getOrDefault(emptyList()).ifEmpty {
            listOf(Video(m3u8, "MissAV - HLS", m3u8, headers = playHeaders))
        }
    }

    private fun extractXMoviesVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(cfClientFor("https://xmoviesforyou.com"), pageUrl, "https://xmoviesforyou.com/")
        val videos = mutableListOf<Video>()
        val videoId = Regex("""["']videoId["']\s*:\s*["']?([a-zA-Z0-9_-]+)["']?""").find(html)?.groupValues?.get(1)
            ?: Regex("""/api/stream/([a-zA-Z0-9_-]+)""").find(html)?.groupValues?.get(1)
        val target = Regex("""["']target["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1).orEmpty()
        if (!videoId.isNullOrBlank()) {
            runCatching {
                val apiUrl = "https://xmoviesforyou.com/api/stream/$videoId" + if (target.isNotBlank()) "?target=${URLEncoder.encode(target, "UTF-8")}" else ""
                val apiHeaders = desktopHeaders(pageUrl).newBuilder().set("Accept", "application/json, */*").build()
                cfClientFor("https://xmoviesforyou.com").newCall(GET(apiUrl, apiHeaders)).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val hls = JSONObject(r.body.string()).optString("url").replace("\\/", "/").trim()
                    if (hls.startsWith("http")) {
                        if (!m3u8Server.isRunning()) m3u8Server.startServer()
                        val local = m3u8Server.processM3u8Url(hls, referer = "https://xmoviesforyou.com/", userAgent = DESKTOP_UA, origin = "https://xmoviesforyou.com")
                        if (local != null) videos += Video(local, "XMovies - HLS", local)
                    }
                }
            }
        }
        if (videos.isEmpty()) {
            val doc = Jsoup.parse(html, pageUrl)
            val mirrors = doc.select("a[href]")
                .map { it.attr("href").trim() }
                .filter { it.startsWith("http") && listOf("dood", "streamtape", "mixdrop", "ds2play", "myvidplay").any { h -> h in it.lowercase(Locale.ROOT) } }
                .distinct().take(3)
            for (m in mirrors) {
                videos += extractGenericEmbed(m, pageUrl, "XMovies")
                if (videos.isNotEmpty()) break
            }
        }
        return videos.distinctBy { it.videoUrl ?: it.url }
    }

    private fun extractSupJavVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(cfClientFor("https://supjav.com"), pageUrl, "https://supjav.com/")
        val doc = Jsoup.parse(html, pageUrl)
        val links = doc.select("div.btnst a[data-link], div.btnst a[href], div.btns a[data-link], a[href*=supjav.php]")
            .mapNotNull { it.attr("data-link").ifBlank { it.attr("href") }.trim().takeIf { s -> s.isNotBlank() && s != "#" } }
            .distinct()
            .take(4)
        for (rawId in links) {
            val resolved = resolveSupJavRedirect(rawId) ?: continue
            val hits = extractGenericEmbed(resolved, pageUrl, "SupJav")
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun resolveSupJavRedirect(id: String): String? {
        if (id.startsWith("//")) return "https:$id"
        if (id.startsWith("http") && "supjav" !in id.lowercase(Locale.ROOT)) return id
        val candidates = if (id.startsWith("http")) {
            listOf(id)
        } else {
            listOf("https://lkc21.com/supjav.php?c=${id.reversed()}", "https://lkc21.com/supjav.php?c=$id")
        }
        for (u in candidates) {
            val loc = runCatching {
                noRedirectClient.newCall(GET(u, desktopHeaders("https://lkc21.com/"))).execute().use { r ->
                    r.header("Location")?.trim()?.takeIf(String::isNotBlank)
                        ?.let { r.request.url.resolve(it)?.toString() }
                }
            }.getOrNull()
            if (!loc.isNullOrBlank()) return loc
        }
        return null
    }

    private fun extractJavGgVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(cfClientFor("https://javgg.net"), pageUrl, "https://javgg.net/")
        val doc = Jsoup.parse(html, pageUrl)
        val iframes = doc.select("[id*=source-player] iframe, iframe[src]")
            .mapNotNull { it.attr("src").ifBlank { it.attr("data-src") }.trim().takeIf { s -> s.startsWith("http") } }
            .distinct().take(4)
        for (iframe in iframes) {
            val hits = extractGenericEmbed(iframe, pageUrl, "JavGG")
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun extractJavGuruVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(cfClientFor("https://jav.guru"), pageUrl, "https://jav.guru/")
        val b64Matches = Regex(""""iframe_url"\s*:\s*"([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        for (b64 in b64Matches.take(4)) {
            val iframeUrl = runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull() ?: continue
            val token = iframeUrl.toHttpUrlOrNull()?.queryParameter("xd")
            val targetUrl = if (token != null) "${iframeUrl.substringBefore("?")}?xr=${token.reversed()}" else iframeUrl
            val finalEmbed = runCatching {
                baseHttpClient.newCall(GET(targetUrl, desktopHeaders(iframeUrl))).execute().use { r ->
                    val u = r.request.url.toString()
                    val body = r.body.string()
                    Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1) ?: u
                }
            }.getOrNull() ?: targetUrl
            val hits = extractGenericEmbed(finalEmbed, pageUrl, "JavGuru")
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun extractArabXVideos(pageUrl: String): List<Video> {
        val client = cfClientFor("https://www.arabx.cam")
        val kvs = runCatching { extractKvsVideos(client, pageUrl, "ArabX") }.getOrDefault(emptyList())
        if (kvs.isNotEmpty()) return kvs
        val html = fetchHtml(client, pageUrl, "https://www.arabx.cam/")
        val doc = Jsoup.parse(html, pageUrl)
        val iframes = doc.select("div.player iframe[src], iframe[src]")
            .map { it.attr("abs:src").ifBlank { it.attr("src") } }
            .filter { it.startsWith("http") && "google" !in it && "exoclick" !in it }
            .distinct().take(3)
        for (iframe in iframes) {
            val hits = extractGenericEmbed(iframe, pageUrl, "ArabX")
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun extractArabXnVideos(pageUrl: String): List<Video> {
        val html = fetchHtml(cfClientFor("https://www.arabxn.com"), pageUrl, "https://www.arabxn.com/")
        val doc = Jsoup.parse(html, pageUrl)
        val safeRef = pageUrl.toHttpUrlOrNull()?.toString() ?: "https://www.arabxn.com/"
        val playHeaders = desktopHeaders(safeRef)
        val fromSources = doc.select("video#my-video source[src], video source[src]")
            .mapNotNull { s ->
                val u = s.attr("abs:src").ifBlank { s.attr("src") }
                if (u.startsWith("http") && ".mp4" in u.lowercase(Locale.ROOT)) {
                    val label = s.attr("title").ifBlank { "MP4" }
                    Video(u, "ArabXN - $label", u, headers = playHeaders)
                } else {
                    null
                }
            }
        if (fromSources.isNotEmpty()) return fromSources.distinctBy { it.url }
        return Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .distinct()
            .map { Video(it, "ArabXN - MP4", it, headers = playHeaders) }
            .toList()
    }

    private fun extractGenericEmbed(rawEmbedUrl: String, referer: String, badge: String): List<Video> {
        val embedUrl = rawEmbedUrl.replaceFirst("hglink.to", "niramirus.com").replaceFirst("hgcloud.to", "niramirus.com")
        val lower = embedUrl.lowercase(Locale.ROOT)
        // Dedicated host extractors
        runCatching {
            when {
                "streamtape" in lower -> return streamTapeExtractor.videosFromUrl(embedUrl)
                "dood" in lower || "ds2play" in lower || "myvidplay" in lower || "d000d" in lower -> return doodExtractor.videosFromUrl(embedUrl)
                "mixdrop" in lower -> return mixDropExtractor.videosFromUrl(embedUrl)
                "voe." in lower -> return voeExtractor.videosFromUrl(embedUrl)
            }
        }
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
            return listOf(Video(localUrl, "$badge - F4 HLS", localUrl))
        }

        // Packed HLS / TurboVid / StreamWish / FileLions / VidHide / Direct MP4
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
            ?: Regex("""urlPlay\s*=\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(scripts)?.value
            ?: Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").find(body)?.value
        if (!m3u8.isNullOrBlank()) {
            val resolved = finalUrl.resolve(m3u8)?.toString() ?: m3u8
            val embedOrigin = "${finalUrl.scheme}://${finalUrl.host}"
            if (!m3u8Server.isRunning()) m3u8Server.startServer()
            val localUrl = m3u8Server.processM3u8Url(resolved, referer = finalUrl.toString(), userAgent = DESKTOP_UA, origin = embedOrigin) ?: return emptyList()
            return listOf(Video(localUrl, "$badge - HLS", localUrl))
        }
        val mp4 = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(scripts)?.value
            ?: Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").find(body)?.value
            ?: return emptyList()
        return listOf(Video(mp4, "$badge - MP4", mp4, headers = desktopHeaders(finalUrl.toString())))
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
        "sextb" -> cfClientFor("https://sextb.net")
        "xmoviesforyou" -> cfClientFor("https://xmoviesforyou.com")
        "supjav" -> cfClientFor("https://supjav.com")
        "jable" -> cfClientFor("https://jable.tv")
        "missav" -> cfClientFor("https://missav.ws")
        "javgg" -> cfClientFor("https://javgg.net")
        "javguru" -> cfClientFor("https://jav.guru")
        "aflamk1" -> cfClientFor("https://www.aflamk1.net")
        "sexalarab" -> cfClientFor("https://sexalarab.tv")
        "arabx" -> cfClientFor("https://www.arabx.cam")
        "arabxn" -> cfClientFor("https://www.arabxn.com")
        else -> baseHttpClient
    }

    private fun acquireSourceSlot(hostKey: String) {
        val waitMs: Long
        synchronized(sourcePaceLock) {
            val now = System.currentTimeMillis()
            val cooldownUntil = sourceCooldownUntilMs[hostKey] ?: 0L
            val gap = if (now < cooldownUntil) 1_800L else 420L
            val scheduled = maxOf(now, sourceNextAllowedMs[hostKey] ?: 0L)
            sourceNextAllowedMs[hostKey] = scheduled + gap
            waitMs = scheduled - now
        }
        if (waitMs > 0L) {
            runCatching { Thread.sleep(waitMs.coerceAtMost(2_500L)) }
        }
    }

    private fun fetchHtml(httpClient: OkHttpClient, url: String, referer: String): String {
        val now = System.currentTimeMillis()
        pageCache[url]?.takeIf { now - it.first < CACHE_TTL_MS }?.let { return it.second }
        val hostKey = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: "default"
        acquireSourceSlot(hostKey)
        httpClient.newCall(GET(url, desktopHeaders(referer))).execute().use { resp ->
            if (resp.code == 429 || resp.code == 403) {
                synchronized(sourcePaceLock) {
                    val t = System.currentTimeMillis()
                    sourceCooldownUntilMs[hostKey] = maxOf(sourceCooldownUntilMs[hostKey] ?: 0L, t + 25_000L)
                    sourceNextAllowedMs[hostKey] = maxOf(sourceNextAllowedMs[hostKey] ?: 0L, t + 2_500L)
                }
                throw IOException("HTTP ${resp.code} for $url")
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body.string()
            if (body.length > 2000) {
                if (pageCache.size > 100) pageCache.clear()
                pageCache[url] = System.currentTimeMillis() to body
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
        AnimeFilter.Header("Auto-picks your most-used sources from 15 supported sites unless overridden below."),
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
    private class SourceGroup(items: List<SourceCheckBox>) : AnimeFilter.Group<SourceCheckBox>("Sources (15 available — optional override)", items)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val activeSummary = resolveActiveSources().joinToString(", ") { it.label }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_AUTO_SOURCES_KEY
            title = "Auto-select most-used sources"
            summary = "Adapts automatically across 15 sources (Active now: $activeSummary)."
            setDefaultValue(true)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUTO_COUNT_KEY
            title = "Active sources per feed page"
            entries = arrayOf("2 sources (fastest)", "3 sources", "4 sources", "5 sources (recommended)", "6 sources (broadest)")
            entryValues = arrayOf("2", "3", "4", "5", "6")
            setDefaultValue("5")
            summary = "%s"
        }.also(screen::addPreference)

        for (src in ALL_SOURCES) {
            SwitchPreferenceCompat(screen.context).apply {
                key = "src_enabled_${src.id}"
                title = "Allow source: ${src.label}"
                summary = "Include ${src.label} in Auto mix & searches."
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

    private data class SourceDef(
        val id: String,
        val label: String,
        val isJav: Boolean = false,
        val isShortForm: Boolean = false,
        val supportsDurationQuery: Boolean = false,
        val isArabic: Boolean = false,
    )

    /**
     * Fixes broken cover thumbnails (especially SexTB & JAV/Arabic CDNs) inside Coil:
     * rewrites the default `Referer: https://www.pornhub.com/` header to the exact host
     * origin for each source, attaches WebView clearance cookies when available, and retries
     * once without Referer if an image CDN rejects hotlink referers (HTTP 403).
     */
    private class SmartImageRefererInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val orig = chain.request()
            val urlStr = orig.url.toString()
            val host = orig.url.host.lowercase(Locale.ROOT)
            val referer = resolveImageReferer(host)

            val builder = orig.newBuilder()
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            if (referer != null) {
                builder.header("Referer", referer)
            } else {
                builder.removeHeader("Referer")
            }
            if ("sextb" in host || "supjav" in host || "jable" in host || "missav" in host) {
                val webCookie = runCatching {
                    CookieManager.getInstance()?.getCookie(referer ?: urlStr)
                }.getOrNull()
                if (!webCookie.isNullOrBlank() && orig.header("Cookie").isNullOrBlank()) {
                    builder.header("Cookie", webCookie)
                }
            }
            val resp = chain.proceed(builder.build())
            if (resp.code == 403 || resp.code == 401) {
                resp.close()
                val retryReq = orig.newBuilder()
                    .removeHeader("Referer")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .build()
                return chain.proceed(retryReq)
            }
            return resp
        }

        private fun resolveImageReferer(host: String): String? = when {
            "sextb" in host || "dmm.co.jp" in host || "sextbcdn" in host -> "https://sextb.net/"
            "phncdn" in host || "pornhub" in host -> "https://www.pornhub.com/"
            "xvideos" in host || "xhcdn" in host || "xnxx" in host -> "https://www.xvideos.com/"
            "xmoviesforyou" in host -> "https://xmoviesforyou.com/"
            "supjav" in host || "imgjav" in host -> "https://supjav.com/"
            "jable" in host -> "https://jable.tv/"
            "missav" in host || "fourhoi" in host -> "https://missav.ws/"
            "javgg" in host -> "https://javgg.net/"
            "jav.guru" in host || "javguru" in host -> "https://jav.guru/"
            "aflamk1" in host -> "https://www.aflamk1.net/"
            "sexalarab" in host -> "https://sexalarab.tv/"
            "arabx." in host -> "https://www.arabx.cam/"
            "arabxn" in host -> "https://www.arabxn.com/"
            "rule34video" in host -> "https://rule34video.com/"
            else -> null
        }
    }

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
        private const val MIN_HEALTHY_YIELD = 6
        private const val SOURCE_TIMEOUT_MS = 13_000L
        private const val CACHE_TTL_MS = 3 * 60 * 1000L

        private const val PREF_AUTO_SOURCES_KEY = "pref_auto_sources"
        private const val PREF_AUTO_COUNT_KEY = "pref_auto_source_count"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val SEXTB_DEAD_CDN_REGEX = Regex("""https?://cdn[a-z0-9]*\.sextb\.net/""", RegexOption.IGNORE_CASE)

        private val ALL_SOURCES = listOf(
            SourceDef("sextb", "SexTB", isJav = true),
            SourceDef("pornhub", "PornHub", supportsDurationQuery = true),
            SourceDef("xvideos", "XVideos", supportsDurationQuery = true),
            SourceDef("xnxx", "XNXX", supportsDurationQuery = true),
            SourceDef("xmoviesforyou", "XMoviesForYou"),
            SourceDef("supjav", "SupJav", isJav = true),
            SourceDef("jable", "Jable", isJav = true),
            SourceDef("missav", "MissAV", isJav = true),
            SourceDef("javgg", "JavGG", isJav = true),
            SourceDef("javguru", "JavGuru", isJav = true),
            SourceDef("aflamk1", "Aflamk1", isArabic = true),
            SourceDef("sexalarab", "SexAlArab", isArabic = true),
            SourceDef("arabx", "ArabX", isArabic = true),
            SourceDef("arabxn", "ArabXN", isArabic = true),
            SourceDef("rule34video", "Rule34Video", isShortForm = true),
        )

        private val SOURCE_ALIASES = mapOf(
            "supjav2" to "supjav",
            "sexmahali" to "sexalarab",
        )

        private val COMBO_FRIENDLY_CONCEPTS = setOf(
            "mmf", "threesome", "foursome", "dp", "gangbang", "slut", "orgy", "anal", "creampie", "milf", "interracial",
        )

        private val ARABIC_CONCEPT_QUERIES = mapOf(
            "mmf" to "جماعي",
            "threesome" to "ثلاثي",
            "foursome" to "جماعي",
            "gangbang" to "جماعي",
            "orgy" to "جماعي",
            "dp" to "خلفي",
            "slut" to "شرموطة",
            "anal" to "خلفي",
            "milf" to "امهات",
            "cuckold" to "ديوث",
            "stepfamily" to "محارم",
            "lesbian" to "سحاق",
            "massage" to "مساج",
            "creampie" to "قذف",
        )

        private val SEXTB_CONCEPT_GENRES = mapOf(
            "mmf" to "threesome--foursome",
            "threesome" to "threesome--foursome",
            "foursome" to "threesome--foursome",
            "gangbang" to "orgy",
            "orgy" to "orgy",
            "slut" to "slut",
            "dp" to "anal",
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
        )

        private val PORNHUB_CONCEPT_CATEGORIES = mapOf(
            "threesome" to "65",
            "foursome" to "65",
            "dp" to "72",
            "gangbang" to "80",
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

        private val SUPJAV_CONCEPT_TAGS = mapOf(
            "mmf" to "3p-4p",
            "threesome" to "3p-4p",
            "foursome" to "3p-4p",
            "gangbang" to "gangbang",
            "orgy" to "orgy",
            "slut" to "slut",
            "dp" to "anal",
            "anal" to "anal",
            "creampie" to "creampie",
            "cuckold" to "cuckold",
            "milf" to "mature-woman",
            "interracial" to "black-actor",
            "lesbian" to "lesbian",
            "bdsm" to "bdsm",
            "massage" to "massage",
            "squirt" to "squirting",
        )

        private val JABLE_CONCEPT_TAGS = mapOf(
            "mmf" to "3p",
            "threesome" to "3p",
            "foursome" to "groupsex",
            "gangbang" to "groupsex",
            "orgy" to "groupsex",
            "dp" to "anal-sex",
            "anal" to "anal-sex",
            "creampie" to "creampie",
            "cuckold" to "ntr",
            "milf" to "mature-woman",
            "massage" to "massage",
            "squirt" to "squirting",
            "bdsm" to "bondage",
        )

        private val MISSAV_CONCEPT_GENRES = mapOf(
            "mmf" to "3p-4p",
            "threesome" to "3p-4p",
            "foursome" to "3p-4p",
            "gangbang" to "gangbang",
            "orgy" to "orgy",
            "slut" to "slut",
            "anal" to "anal",
            "creampie" to "creampie",
            "cuckold" to "ntr",
            "milf" to "mature-woman",
            "massage" to "massage",
            "squirt" to "squirting",
        )

        private val JAVGG_CONCEPT_GENRES = mapOf(
            "mmf" to "3p-4p",
            "threesome" to "3p-4p",
            "foursome" to "3p-4p",
            "gangbang" to "gangbang",
            "orgy" to "orgy",
            "slut" to "slut",
            "anal" to "anal",
            "creampie" to "creampie",
            "cuckold" to "cuckold",
            "milf" to "mature-woman",
        )

        private val JAVGURU_CONCEPT_TAGS = mapOf(
            "mmf" to "3p-4p",
            "threesome" to "3p-4p",
            "foursome" to "3p-4p",
            "gangbang" to "orgy",
            "orgy" to "orgy",
            "slut" to "slut",
            "anal" to "anal",
            "creampie" to "creampie",
            "cuckold" to "cuckold",
        )

        private val XMOVIES_CONCEPT_CATS = mapOf(
            "mmf" to "threesome",
            "threesome" to "threesome",
            "foursome" to "threesome",
            "gangbang" to "gangbang",
            "dp" to "dp",
            "orgy" to "orgy",
            "anal" to "anal",
            "creampie" to "creampie",
            "milf" to "milf",
            "interracial" to "interracial",
            "lesbian" to "lesbian",
            "bdsm" to "bdsm",
        )
    }
}
