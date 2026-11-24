package eu.kanade.tachiyomi.animeextension.ar.hentaiaraby

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HentaiAraby — https://hentaiaraby.com/
 *
 * Uses the site's public REST API (`https://apiv2.hentaiaraby.com/api/v1/content`)
 * and CDN (`https://cdn.hentaiaraby.com`) for:
 * - Trending / Latest / Sorted catalog browsing
 * - Full-text search (`/search/videos`) and 69 Arabic/EN categories (`/categories/{slug}`)
 * - Series episode grouping (`series.videos`)
 * - Multi-quality HLS playlists (`master.m3u8`), direct MP4 fallback, and Arabic/English WebVTT subtitles.
 */
class HentaiAraby :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiAraby"

    override val baseUrl = "https://hentaiaraby.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val detailCache = ConcurrentHashMap<String, Pair<Long, VideoDetailDto>>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$API_BASE/videos".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "trending")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val parsed = json.decodeFromString<VideosListResponse>(body)
        val items = parsed.data?.items.orEmpty()
        val animes = items.mapNotNull(::toSAnime).distinctBy { it.url }
        return AnimesPage(animes, items.size >= PAGE_SIZE)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$API_BASE/videos".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "latest")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val cleanQuery = query.trim()
        val selectedCategories = filters.filterIsInstance<CategoryGroup>()
            .firstOrNull()?.state?.filter { it.state }?.map { it.slug }.orEmpty()

        if (cleanQuery.isBlank() && selectedCategories.size > 1) {
            val requests = selectedCategories.take(MAX_PARALLEL_FILTERS).map { slug ->
                categoryRequest(slug, page)
            }
            return fetchAndMergeCategoryPages(requests)
        }

        val response = client.newCall(searchAnimeRequest(page, query, filters)).awaitSuccess()
        return searchAnimeParse(response)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val selectedCategory = filters.filterIsInstance<CategoryGroup>()
            .firstOrNull()?.state?.firstOrNull { it.state }?.slug.orEmpty()

        if (cleanQuery.isNotBlank()) {
            val url = "$API_BASE/search/videos".toHttpUrl().newBuilder()
                .addQueryParameter("q", cleanQuery)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("sortBy", sort.ifBlank { "relevance" })
                .addQueryParameter("order", "desc")
                .build()
            return GET(url, headers)
        }

        if (selectedCategory.isNotBlank()) {
            return categoryRequest(selectedCategory, page)
        }

        val effectiveSort = sort.ifBlank { "trending" }
        val url = "$API_BASE/videos".toHttpUrl().newBuilder()
            .addQueryParameter("sort", effectiveSort)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath
        val body = response.body.string()
        return when {
            path.contains("/search/videos") -> {
                val parsed = json.decodeFromString<SearchVideosResponse>(body)
                val data = parsed.data
                val videos = data?.videos.orEmpty()
                val animes = videos.mapNotNull(::toSAnime).distinctBy { it.url }
                val pagination = data?.pagination
                val hasNext = if (pagination != null) {
                    pagination.page < pagination.totalPages
                } else {
                    videos.size >= PAGE_SIZE
                }
                AnimesPage(animes, hasNext && animes.isNotEmpty())
            }

            path.contains("/categories/") -> parseCategoryBody(body)

            else -> {
                val parsed = json.decodeFromString<VideosListResponse>(body)
                val items = parsed.data?.items.orEmpty()
                val animes = items.mapNotNull(::toSAnime).distinctBy { it.url }
                AnimesPage(animes, items.size >= PAGE_SIZE)
            }
        }
    }

    private fun categoryRequest(slug: String, page: Int): Request {
        val url = "$API_BASE/categories".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
        return GET(url, headers)
    }

    private fun parseCategoryBody(body: String): AnimesPage {
        val parsed = json.decodeFromString<CategoryVideosResponse>(body)
        val data = parsed.data ?: return AnimesPage(emptyList(), false)
        val animes = data.videos.mapNotNull(::toSAnime).distinctBy { it.url }
        val hasNext = data.currentPage < data.totalPages && animes.isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    private suspend fun fetchAndMergeCategoryPages(requests: List<Request>): AnimesPage = withContext(Dispatchers.IO) {
        val pool = Executors.newFixedThreadPool(requests.size.coerceAtMost(MAX_PARALLEL_FILTERS))
        try {
            val futures = requests.map { req ->
                pool.submit<AnimesPage?> {
                    runCatching {
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            parseCategoryBody(resp.body.string())
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
    override fun animeDetailsRequest(anime: SAnime): Request = GET(getAnimeUrl(anime), headers)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = withContext(Dispatchers.IO) {
        val slug = extractSlug(anime.url)
        val detail = fetchVideoDetail(slug)
        toDetailedSAnime(detail, anime.url)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val slug = extractSlug(response.request.url.encodedPath)
        val detail = fetchVideoDetail(slug)
        return toDetailedSAnime(detail, "/video/$slug")
    }

    private fun toDetailedSAnime(detail: VideoDetailDto, fallbackUrl: String): SAnime = SAnime.create().apply {
        url = if (detail.slug.isNotBlank()) "/video/${detail.slug}" else fallbackUrl
        title = detail.title.trim()
        thumbnail_url = detail.thumbnail?.let(::toCdnUrl)
        author = detail.series?.name?.trim()?.takeIf(String::isNotBlank)
            ?: detail.user?.username?.trim()?.takeIf(String::isNotBlank)
        genre = detail.categories
            .mapNotNull { it.name.trim().takeIf(String::isNotBlank) }
            .distinct()
            .joinToString()
            .ifBlank { null }
        description = buildString {
            val mainDesc = detail.description?.trim().orEmpty()
                .ifBlank { detail.metaDescription?.trim().orEmpty() }
            if (mainDesc.isNotBlank()) append(mainDesc)
            val metaParts = buildList {
                detail.series?.name?.trim()?.takeIf(String::isNotBlank)?.let { add("السلسلة: $it") }
                detail.quality?.trim()?.takeIf(String::isNotBlank)?.let { add("الجودة: $it") }
                detail.duration?.trim()?.takeIf(String::isNotBlank)?.let { add("المدة: $it") }
                if (detail.views > 0) add("المشاهدات: ${detail.views}")
            }
            if (metaParts.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(metaParts.joinToString(" | "))
            }
        }.ifBlank { null }
        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val slug = extractSlug(anime.url)
        val detail = fetchVideoDetail(slug)
        buildEpisodeList(detail)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = extractSlug(response.request.url.encodedPath)
        val detail = fetchVideoDetail(slug)
        return buildEpisodeList(detail)
    }

    private fun buildEpisodeList(detail: VideoDetailDto): List<SEpisode> {
        val seriesVideos = detail.series?.videos.orEmpty()
        if (seriesVideos.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    url = "/video/${detail.slug}"
                    episode_number = (detail.episode ?: 1).toFloat()
                    name = detail.title.trim().ifBlank { "الحلقة ${detail.episode ?: 1}" }
                    date_upload = parseIsoDate(detail.createdAt)
                    scanlator = buildScanlator(detail.quality, detail.duration)
                },
            )
        }

        val combined = LinkedHashMap<String, SeriesVideoDto>()
        if (detail.slug.isNotBlank()) {
            combined[detail.slug] = SeriesVideoDto(
                id = detail.id,
                slug = detail.slug,
                title = detail.title,
                thumbnail = detail.thumbnail,
                duration = detail.duration,
                quality = detail.quality,
                episode = detail.episode,
                createdAt = detail.createdAt,
            )
        }
        seriesVideos.forEach { v ->
            if (v.slug.isNotBlank()) {
                combined.putIfAbsent(v.slug, v)
            }
        }

        val sorted = combined.values.sortedWith(
            compareByDescending<SeriesVideoDto> { it.episode ?: Int.MIN_VALUE }
                .thenByDescending { parseIsoDate(it.createdAt) },
        )

        val total = sorted.size
        return sorted.mapIndexed { idx, v ->
            val epNum = v.episode ?: (total - idx)
            SEpisode.create().apply {
                url = "/video/${v.slug}"
                episode_number = epNum.toFloat()
                name = buildString {
                    append("الحلقة ")
                    append(epNum)
                    val cleanTitle = v.title.trim()
                    if (cleanTitle.isNotBlank()) {
                        append(" - ")
                        append(cleanTitle)
                    }
                }
                date_upload = parseIsoDate(v.createdAt)
                scanlator = buildScanlator(v.quality, v.duration)
            }
        }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val slug = extractSlug(episode.url)
        val detail = fetchVideoDetail(slug)
        extractVideos(detail).sort()
    }

    override fun videoListParse(response: Response): List<Video> {
        val slug = extractSlug(response.request.url.encodedPath)
        val detail = fetchVideoDetail(slug)
        return extractVideos(detail).sort()
    }

    private fun extractVideos(detail: VideoDetailDto): List<Video> {
        val subtitleTracks = detail.subtitles
            .filter { it.filePath.isNotBlank() }
            .sortedBy { subSortWeight(it.language) }
            .map { sub ->
                Track(
                    url = toCdnUrl(sub.filePath),
                    lang = formatSubtitleLang(sub.language),
                )
            }

        val mediaHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()

        val videos = mutableListOf<Video>()

        // 1) Primary multi-quality HLS playlist (`https://cdn.hentaiaraby.com/hls/{id}/master.m3u8`)
        val isMapped = when {
            !detail.videoType.isNullOrBlank() -> detail.videoType.equals("mapped", ignoreCase = true)
            detail.videoPath.isNullOrBlank() -> true
            else -> RENDITIONS_SUFFIX.containsMatchIn(detail.videoPath)
        }
        if (isMapped && detail.id.isNotBlank()) {
            val masterUrl = "$CDN_URL/hls/${detail.id}/master.m3u8"
            val hlsVideos = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = masterUrl,
                    referer = "$baseUrl/",
                    masterHeaders = mediaHeaders,
                    videoHeaders = mediaHeaders,
                    videoNameGen = { q -> if (q == "Video") "HLS - Auto" else "HLS - $q" },
                    subtitleList = subtitleTracks,
                )
            }.getOrDefault(emptyList())
            videos += hlsVideos
        }

        // 2) Direct MP4 fallback / download stream
        val mp4Url = resolveMp4Url(detail)
        if (!mp4Url.isNullOrBlank()) {
            val mp4Res = QUALITY_REGEX.find(mp4Url)?.value
                ?: detail.quality?.takeIf(String::isNotBlank)
                ?: "MP4"
            videos += Video(
                url = mp4Url,
                quality = "Direct MP4 - $mp4Res",
                videoUrl = mp4Url,
                headers = mediaHeaders,
                subtitleTracks = subtitleTracks,
            )
        }

        val distinct = videos.distinctBy { it.videoUrl ?: it.url }
        if (distinct.isEmpty()) {
            throw IOException("لم يتم العثور على رابط تشغيل صالح لهذا الفيديو على HentaiAraby.")
        }
        return distinct
    }

    private fun resolveMp4Url(detail: VideoDetailDto): String? {
        val mp4 = detail.mp4Path?.trim()
        if (!mp4.isNullOrBlank()) return toCdnUrl(mp4)
        val vp = detail.videoPath?.trim()
        if (vp.isNullOrBlank()) return null
        val full = toCdnUrl(vp)
        return if (RENDITIONS_SUFFIX.containsMatchIn(full)) {
            "${full.trimEnd('/')}/1080p.mp4"
        } else {
            full
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        return sortedWith(
            compareBy<Video> { !it.quality.contains(prefType, ignoreCase = true) }
                .thenBy { !it.quality.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { QUALITY_NUM_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
        )
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يمكنك اختيار أكثر من تصنيف في نفس الوقت"),
        SortFilter(),
        CategoryGroup(),
    )

    private class SortFilter :
        UriPartFilter(
            "الترتيب / Sort",
            arrayOf(
                Pair("الأكثر رواجاً (Trending)", "trending"),
                Pair("الأحدث (Latest)", "latest"),
                Pair("الأكثر مشاهدة (Views)", "views"),
                Pair("الأكثر إعجاباً (Likes)", "likes"),
                Pair("الأقدم (Oldest)", "oldest"),
                Pair("المدة (Duration)", "duration"),
            ),
        )

    private class CategoryCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup :
        AnimeFilter.Group<CategoryCheckBox>(
            "التصنيفات / Categories (اختيار متعدد)",
            LIVE_CATEGORIES.map { (label, slug) -> CategoryCheckBox(label, slug) },
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "الجودة المفضلة / Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = "نوع البث المفضل / Preferred Stream"
            entries = arrayOf("HLS (متعدد الجودات)", "Direct MP4 (مباشر)")
            entryValues = arrayOf("HLS", "Direct MP4")
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Helpers ===============================
    private fun toSAnime(item: VideoItemDto): SAnime? {
        if (item.slug.isBlank() || item.title.isBlank()) return null
        return SAnime.create().apply {
            url = "/video/${item.slug}"
            title = item.title.trim()
            thumbnail_url = item.thumbnail?.let(::toCdnUrl)
        }
    }

    private fun fetchVideoDetail(slug: String): VideoDetailDto {
        val now = System.currentTimeMillis()
        detailCache[slug]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }
        val apiUrl = "$API_BASE/videos".toHttpUrl().newBuilder()
            .addPathSegment(slug)
            .build()
        val body = client.newCall(GET(apiUrl, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} while loading video $slug")
            resp.body.string()
        }
        val parsed = json.decodeFromString<VideoDetailResponse>(body)
        val detail = parsed.data ?: throw IOException("Empty video response for $slug")
        if (detailCache.size > 40) detailCache.clear()
        detailCache[slug] = now to detail
        return detail
    }

    private fun extractSlug(urlOrPath: String): String {
        val clean = urlOrPath.substringBefore('?').substringBefore('#').trimEnd('/')
        val raw = clean.substringAfterLast('/')
        if ('%' !in raw) return raw
        return runCatching {
            URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(raw)
    }

    private fun toCdnUrl(path: String): String {
        val trimmed = path.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "$CDN_URL/${trimmed.trimStart('/')}"
        }
    }

    private fun buildScanlator(quality: String?, duration: String?): String? = listOfNotNull(
        quality?.trim()?.takeIf(String::isNotBlank),
        duration?.trim()?.takeIf(String::isNotBlank),
    ).joinToString(" · ").ifBlank { null }

    private fun parseIsoDate(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return runCatching {
            val cleaned = iso.substringBefore('.').removeSuffix("Z")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(cleaned)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun subSortWeight(langCode: String): Int = when (langCode.lowercase(Locale.ROOT)) {
        "ar", "ara", "arabic" -> 0
        "en", "eng", "english" -> 1
        else -> 2
    }

    private fun formatSubtitleLang(langCode: String): String = when (langCode.lowercase(Locale.ROOT)) {
        "ar", "ara" -> "العربية"
        "en", "eng" -> "English"
        "es", "spa" -> "Español"
        "fr", "fra" -> "Français"
        "de", "deu" -> "Deutsch"
        else -> langCode.uppercase(Locale.ROOT)
    }

    companion object {
        private const val API_BASE = "https://apiv2.hentaiaraby.com/api/v1/content"
        private const val CDN_URL = "https://cdn.hentaiaraby.com"
        private const val PAGE_SIZE = 25
        private const val MAX_PARALLEL_FILTERS = 4
        private const val CACHE_TTL_MS = 3 * 60 * 1000L

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_TYPE_KEY = "preferred_stream_type"
        private const val PREF_TYPE_DEFAULT = "HLS"

        private val RENDITIONS_SUFFIX = Regex("""/renditions/?$""", RegexOption.IGNORE_CASE)
        private val QUALITY_REGEX = Regex("""(?i)(2160|1440|1080|720|480|360|240)p""")
        private val QUALITY_NUM_REGEX = Regex("""(?i)(2160|1440|1080|720|480|360|240)p?""")

        // Verified live categories from https://apiv2.hentaiaraby.com/api/v1/content/categories
        private val LIVE_CATEGORIES = listOf(
            "أثداء كبيرة" to "هنتاي-أثداء-كبيرة",
            "خاضع للرقابة" to "هنتاي-خاضع-للرقابة",
            "غير خاضع للرقابة (Uncensored)" to "هنتاي-غير-خاضع-للرقابة",
            "الجنس الفموي" to "هنتاي-الجنس-الفموي",
            "كريم باي (Creampie)" to "هنتاي-كريم-باي",
            "حبكة (Plot)" to "هنتاي-حبكة",
            "فتاة المدرسة" to "هنتاي-فتاة-المدرسة",
            "استمناء الثدي" to "هنتاي-استمناء-الثدي",
            "الوجه" to "هنتاي-الوجه",
            "عذراء" to "هنتاي-عذراء",
            "شرجي (Anal)" to "هنتاي-شرجي",
            "حريم (Harem)" to "هنتاي-حريم",
            "تقييد (Bondage)" to "هنتاي-تقييد",
            "استمناء يدوي" to "هنتاي-استمناء-يدوي",
            "ألعاب" to "هنتاي-ألعاب",
            "جنس علني" to "هنتاي-جنس-علني",
            "خيالي (Fantasy)" to "هنتاي-خيالي",
            "جانج بانج (Gangbang)" to "هنتاي-جانج-بانج",
            "زنا المحارم (Incest)" to "هنتاي-زنا-المحارم",
            "أشعة سينية (X-Ray)" to "هنتاي-أشعة-سينية",
            "معلم" to "هنتاي-معلم",
            "نظارات" to "هنتاي-نظارات",
            "فانيلا (Vanilla)" to "هنتاي-فانيلا",
            "يوري (Yuri)" to "هنتاي-يوري",
            "انتزاع الشريك (NTR)" to "هنتاي-انتزاع-الشريك",
            "Ahegao" to "Ahegao",
            "الوحش" to "هنتاي-الوحش",
            "ميلف (MILF)" to "هنتاي-ميلف",
            "كوسبلاي (Cosplay)" to "هنتاي-كوسبلاي",
            "الاستمناء" to "هنتاي-الاستمناء",
            "بدلة السباحة" to "هنتاي-بدلة-السباحة",
            "تم تصويره" to "هنتاي-تم-تصويره",
            "المجسات (Tentacles)" to "هنتاي-المجسات",
            "جنس جماعي" to "هنتاي-جنس-جماعي",
            "رياضات المياه" to "هنتاي-رياضات-المياه",
            "الكوميديا" to "هنتاي-الكوميديا",
            "خادمة (Maid)" to "هنتاي-خادمة",
            "التحكم بالعقل" to "هنتاي-التحكم-بالعقل",
            "جنس ثلاثي" to "هنتاي-جنس-ثلاتي",
            "وجهة نظر (POV)" to "هنتاي-وجهة-نظر",
            "حلب" to "هنتاي-حلب",
            "ابتزاز جنسي" to "هنتاي-ابتزاز-جنسي",
            "كسر العقل" to "هنتاي-كسر-العقل",
            "البشرة الداكنة" to "هنتاي-البشرة-الداكنة",
            "الوغد القبيح" to "هنتاي-الوغد-القبيح",
            "فوتاناري (Futanari)" to "هنتاي-فوتاناري",
            "رعب" to "هنتاي-رعب",
            "BDSM" to "bdsm",
            "ممرضة" to "هنتاي-ممرضة",
            "حامل" to "هنتاي-حامل",
            "Foot Job" to "foot-job",
            "ريم جوب" to "هنتاي-ريم-جوب",
            "التضخم" to "هنتاي-التضخم",
            "تسوندير (Tsundere)" to "هنتاي-تسوندير",
            "اغتصاب" to "اغتصاب",
            "الخضوع" to "الخضوع",
            "نيكوميمي" to "هنتاي-نيكوميمي",
            "ناعم" to "هنتاي-ناعم",
            "الفخ (Trap)" to "هنتاي-الفخ",
            "ياوي (Yaoi)" to "هنتاي-ياوي",
            "دوجي ستايل" to "هنتاي-دوجي-ستايل",
            "الملابس الداخلية" to "هنتاي-الملابس-الداخلية",
            "اختراق مزدوج" to "هنتاي-اختراق-مزدوج",
            "بوكاكي (Bukkake)" to "بوكاكي",
            "رومانسية" to "هنتاي-رومانسية",
            "ثلاثي الأبعاد (3D)" to "ثلاثي-الابعاد",
        )
    }
}
