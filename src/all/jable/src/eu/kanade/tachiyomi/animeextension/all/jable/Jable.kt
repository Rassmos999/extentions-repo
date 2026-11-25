package eu.kanade.tachiyomi.animeextension.all.jable

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException

class Jable(override val lang: String) :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val baseUrl: String
        get() = "https://jable.tv"
    override val name: String
        get() = "Jable"
    override val supportsLatest: Boolean
        get() = true

    private val preferences by getPreferencesLazy()
    private val json by injectLazy<Json>()
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val m3u8Server by lazy { M3u8ServerManager(client) }
    private var tagsUpdated = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_seek_cache"
            title = "Enable local seek/skip cache"
            summary = "Caches watched HLS segments locally (20 min TTL) and prefetches ahead for faster skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}?lang=${lang.toRequestLang()}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val info = doc.select(".info-header")
            title = info.select(".header-left h4").text()
            author = info.select(".header-left .model")
                .joinToString { it.select("span[title]").attr("title") }
            genre = doc.select(".tags a").joinToString { it.text() }
            update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
            status = SAnime.COMPLETED
            description = info.select(".header-right").text()
        }
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Episode"
            url = anime.url
        },
    )

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val videoUrl = doc.select("script:containsData(hlsUrl)")
            .asSequence()
            .mapNotNull { HLS_URL_REGEX.find(it.data())?.groupValues?.getOrNull(1) }
            .firstOrNull()
            ?: throw IOException("Video URL not found (if Cloudflare blocked Jable, open in WebView once and retry).")
        val videoHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        val rawVideos = runCatching {
            playlistUtils.extractFromHls(
                videoUrl,
                referer = "$baseUrl/",
                videoNameGen = { quality -> if (quality == "Video") "Default" else quality },
            )
        }.getOrDefault(emptyList()).ifEmpty {
            listOf(Video(videoUrl, "Default", videoUrl, headers = videoHeaders))
        }
        return if (preferences.getBoolean("pref_seek_cache", true)) {
            m3u8Server.wrapVideosForCache(
                videos = rawVideos,
                cacheHls = true,
                cacheMp4 = false,
                defaultReferer = "$baseUrl/",
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            rawVideos
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        if (!tagsUpdated) {
            tagsUpdated = preferences.saveTags(
                doc.select("a.tag").associate {
                    it.ownText() to it.attr("href").substringAfter(baseUrl).removePrefix("/")
                        .removeSuffix("/")
                },
            )
        }
        val items = doc.select(".container .video-img-box").map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.select(".img-box a").attr("href"))
                thumbnail_url = it.select(".img-box img").let { img ->
                    img.attr("data-src").ifEmpty {
                        img.attr("data-original").ifEmpty {
                            img.attr("poster").ifEmpty {
                                img.attr("src")
                            }
                        }
                    }
                }.let { url -> if (url.startsWith("//")) "https:$url" else url }
                val rawTitle = it.select(".detail .title").text()
                val duration = it.selectFirst("span.duration, span.label, span.time, div.duration, time, span.badge, .img-box span")
                    ?.text()?.trim()
                    ?.takeIf { d -> d.contains(":") }
                title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
            }
        }
        val hasNextPage = items.isNotEmpty() && doc.select(".pagination li:has(.active) + li a, .pagination li.active + li:not(.disabled), .pagination a.next, a[rel=next], .pagination li a[aria-label*=Next], .pagination a:contains(›)").isNotEmpty()
        return AnimesPage(items, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = searchRequest("latest-updates", page, latestFilter)

    override fun popularAnimeParse(response: Response): AnimesPage = latestUpdatesParse(response)

    override fun popularAnimeRequest(page: Int) = searchRequest("hot", page, popularFilter)

    override fun searchAnimeParse(response: Response) = latestUpdatesParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotEmpty()) {
        searchRequest(
            "search/$query",
            page,
            AnimeFilterList(filters.list + defaultSearchFunctionFilter),
            query = query,
        )
    } else {
        val categoryPath = filters.list.filterIsInstance<CategoryFilter>()
            .firstOrNull()?.selected?.second.orEmpty()
        val tagPath = filters.list.filterIsInstance<TagFilter>()
            .firstOrNull()?.selected?.second.orEmpty()
        val path = categoryPath.ifEmpty { tagPath }.ifEmpty { "hot" }
        searchRequest(path, page, AnimeFilterList(filters.list + commonVideoListFuncFilter))
    }

    private fun searchRequest(
        path: String,
        page: Int,
        filters: AnimeFilterList = AnimeFilterList(),
        query: String = "",
    ): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("$path/")
            .addQueryParameter("lang", lang.toRequestLang())
        if (tagsUpdated) {
            // load whole page for update filter tags info
            urlBuilder.addQueryParameter("mode", "async")
        }
        var sortBy: String? = null
        filters.list.forEach {
            when (it) {
                is BlockFunctionFilter -> {
                    urlBuilder.addQueryParameter("function", it.selected.functionName)
                        .addQueryParameter("block_id", it.selected.blockId)
                }

                is SortFilter -> {
                    if (it.selected.second.isNotEmpty()) {
                        sortBy = it.selected.second
                    }
                }

                is TagFilter -> {
                    if (query.isNotEmpty() && it.selected.second.isNotEmpty()) {
                        urlBuilder.addQueryParameter("tag", it.selected.second.substringAfterLast('/'))
                    }
                }

                else -> {}
            }
        }
        sortBy?.let { urlBuilder.addQueryParameter("sort_by", it) }
        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter("q", query)
        }
        urlBuilder.addQueryParameter("from", "%02d".format(page))
            .addQueryParameter("_", System.currentTimeMillis().toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun getFilterList(): AnimeFilterList {
        val tagEntries = buildList {
            add("" to "")
            val cachedTags = preferences.getTags()
            if (!cachedTags.isNullOrEmpty()) {
                cachedTags.forEach { add(it.key to it.value) }
            } else {
                DEFAULT_TAGS.forEach { add(it) }
            }
        }.toTypedArray()

        return AnimeFilterList(
            CategoryFilter(
                intl.filterCategoryTitle,
                arrayOf(
                    "" to "",
                    intl.catBdsm to "categories/bdsm",
                    intl.catSexOnly to "categories/sex-only",
                    intl.catChineseSubtitle to "categories/chinese-subtitle",
                    intl.catInsult to "categories/insult",
                    intl.catUniform to "categories/uniform",
                    intl.catRoleplay to "categories/roleplay",
                    intl.catPrivateCam to "categories/private-cam",
                    intl.catUncensored to "categories/uncensored",
                    intl.catPov to "categories/pov",
                    intl.catGroupsex to "categories/groupsex",
                    intl.catPantyhose to "categories/pantyhose",
                    intl.catLesbian to "categories/lesbian",
                ),
            ),
            SortFilter(
                intl.filterPopularSortTitle,
                arrayOf(
                    "" to "",
                    intl.hotMonth to "video_viewed_month",
                    intl.hotWeek to "video_viewed_week",
                    intl.hotDay to "video_viewed_today",
                    intl.hotAll to "video_viewed",
                ),
            ),
            TagFilter(
                intl.filterTagTitle,
                tagEntries,
            ),
            SortFilter(
                intl.filterTagsSortTitle,
                arrayOf(
                    "" to "",
                    intl.sortLatestUpdate to "post_date",
                    intl.sortMostView to "video_viewed",
                    intl.sortMostFavorite to "most_favourited",
                    intl.sortRecentBest to "post_date_and_popularity",
                ),
            ),
        )
    }

    private fun SharedPreferences.getTags(): Map<String, String>? {
        val savedStr = getString("${lang}_$PREF_KEY_TAGS", null)
        if (savedStr.isNullOrEmpty()) {
            return null
        }
        return json.decodeFromString<Map<String, String>>(savedStr)
    }

    private fun SharedPreferences.saveTags(tags: Map<String, String>): Boolean {
        if (tags.isNotEmpty()) {
            edit().putString("${lang}_$PREF_KEY_TAGS", json.encodeToString(tags)).apply()
            return true
        }
        return false
    }

    private fun String.toRequestLang(): String {
        if (this == "ja") return "jp"
        return this
    }

    private val intl by lazy {
        JableIntl(lang)
    }

    private val commonVideoListFuncFilter by lazy {
        BlockFunctionFilter(
            intl.popular,
            arrayOf(BlockFunction(intl.popular, "list_videos_common_videos_list")),
        )
    }

    private val defaultSearchFunctionFilter by lazy {
        BlockFunctionFilter("", arrayOf(BlockFunction("", "list_videos_videos_list_search_result")))
    }

    private val popularFilter by lazy {
        AnimeFilterList(
            commonVideoListFuncFilter,
            SortFilter(
                intl.hotWeek,
                arrayOf(intl.hotWeek to "video_viewed_week"),
            ),
        )
    }

    private val latestFilter by lazy {
        AnimeFilterList(
            BlockFunctionFilter(
                intl.latestUpdate,
                arrayOf(BlockFunction(intl.latestUpdate, "list_videos_latest_videos_list")),
            ),
            SortFilter(
                intl.sortLatestUpdate,
                arrayOf(intl.sortLatestUpdate to "post_date"),
            ),
        )
    }

    companion object {
        const val PREF_KEY_TAGS = "pref_key_tags"

        private val HLS_URL_REGEX = Regex("""\bhlsUrl\s*=\s*["']([^"']+)["']""")

        private val DEFAULT_TAGS = listOf(
            "Big tits" to "tags/big-tits",
            "Creampie" to "tags/creampie",
            "Blowjob" to "tags/blowjob",
            "Anal sex" to "tags/anal-sex",
            "School uniform" to "tags/school-uniform",
            "Swimsuit" to "tags/swimsuit",
            "Maid" to "tags/maid",
            "Nurse" to "tags/nurse",
            "Teacher" to "tags/teacher",
            "Housewife" to "tags/housewife",
            "Mature woman" to "tags/mature-woman",
            "Wife" to "tags/wife",
            "Widow" to "tags/widow",
            "Affair" to "tags/affair",
            "NTR" to "tags/ntr",
            "Bondage" to "tags/bondage",
            "Gangbang" to "tags/groupsex",
            "3P" to "tags/3p",
            "Chikan" to "tags/chikan",
            "Massage" to "tags/massage",
            "Squirting" to "tags/squirting",
            "Deep throat" to "tags/deep-throat",
            "Facial" to "tags/facial",
            "Tit Wank" to "tags/tit-wank",
            "Stockings" to "tags/stockings",
            "Fishnets" to "tags/fishnets",
            "Black pantyhose" to "tags/black-pantyhose",
            "OL" to "tags/ol",
            "Anime character" to "tags/Cosplay",
            "Bunny girl" to "tags/bunny-girl",
            "Kimono" to "tags/kimono",
            "Cheongsam" to "tags/cheongsam",
            "Glasses" to "tags/glasses",
            "Hot spring" to "tags/hot-spring",
            "Time stop" to "tags/time-stop",
            "Hypnosis" to "tags/hypnosis",
        )
    }
}
