package eu.kanade.tachiyomi.animeextension.ar.cimalight

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.goodstramextractor.GoodStreamExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaLight :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "سيما لايت"
    override val baseUrl = "https://e.cimalight.co"
    override val lang = "ar"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/topvideos.php?page=$page", headers)
    override fun popularAnimeSelector(): String = "ul.pm-ul-browse-videos > li"
    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:last-child:not(.disabled) a[href*='page=']"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        // The first anchor in the thumb is the "Watch Later" login button, not the video link.
        val linkElement = element.selectFirst("div.caption h3 a[href*='watch.php'], div.pm-video-thumb a[href*='watch.php']")
        val imgElement = element.selectFirst("img")

        anime.title = linkElement?.attr("title") ?: element.selectFirst("div.caption h3 a")?.text()?.replace("مسلسل ", "")?.replace("فيلم ", "") ?: ""
        anime.thumbnail_url = imgElement?.attr("src").orEmpty()
        anime.setUrlWithoutDomain(linkElement?.attr("href").orEmpty())
        return anime
    }

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newvideos.php?page=$page", headers)
    override fun latestUpdatesSelector(): String = "ul.pm-ul-browse-videos > li"
    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li:last-child:not(.disabled) a[href*='page=']"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        // Get the current URL for vid parameter
        val currentUrl = document.location()
        if (currentUrl.contains("?vid=")) {
            val vid = currentUrl.substringAfter("?vid=").substringBefore("&")
            anime.setUrlWithoutDomain("/watch.php?vid=$vid")
        }

        // Title
        anime.title = document.selectFirst("h1[itemprop=name]")?.text().orEmpty()

        // Thumbnail
        val thumbMeta = document.selectFirst("meta[property=og:image]")
        if (thumbMeta != null) {
            anime.thumbnail_url = thumbMeta.attr("content")
        } else {
            val pmVideoData = document.selectFirst("script:containsData(pm_video_data)")
            if (pmVideoData != null) {
                val thumbMatch = """thumb_url\s*:\s*"([^"]+)""".toRegex().find(pmVideoData.data())
                thumbMatch?.let {
                    anime.thumbnail_url = it.groupValues[1]
                }
            }
        }

        // Description
        val descElement = document.selectFirst("div[itemprop=description]")
        anime.description = descElement?.text().orEmpty()

        // Genre from categories
        val genres = mutableListOf<String>()
        document.select("dl.dl-horizontal dt:contains(Aقسام)").select("next:Sibling(dd) a").forEach {
            genres.add(it.text())
        }
        if (genres.isNotEmpty()) {
            anime.genre = genres.joinToString(", ")
        }

        // Status - assume completed for movies, unknown for series
        if ("فيلم" in anime.title) {
            anime.status = SAnime.COMPLETED
        } else {
            anime.status = SAnime.UNKNOWN
        }

        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        // Check if this is a series page (has seasons)
        val seasonsContainer = document.selectFirst("div.SeasonsBox div.SeasonsBoxUL")
        if (seasonsContainer != null) {
            val seasonButtons = seasonsContainer.select("div.Tab button.tablinks")
            seasonButtons.forEach { btn ->
                val seasonTitle = btn.text()
                val seasonId = btn.attr("onclick")
                    .substringAfter("'")
                    .substringBefore("'")

                if (seasonId.isNotEmpty()) {
                    val tabContent = document.selectFirst("#$seasonId ul a")
                    tabContent?.parent()?.children()?.forEach { episodeLink ->
                        val episodeUrl = episodeLink.attr("abs:href")
                        if (episodeUrl.isNotEmpty()) {
                            val episodeName = episodeLink.text()
                            val episodeNum = parseEpisodeNumber(episodeName)

                            episodes.add(
                                SEpisode.create().apply {
                                    name = "$seasonTitle $episodeName"
                                    setUrlWithoutDomain(episodeUrl)
                                    episode_number = episodeNum
                                },
                            )
                        }
                    }
                }
            }
        } else {
            // Movie/single video page
            episodes.add(
                SEpisode.create().apply {
                    name = "مشاهدة"
                    setUrlWithoutDomain(response.request.url.toString())
                    episode_number = 1f
                },
            )
        }

        return episodes.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // The "xtgo" button leads to a disguised intermediate page whose server
        // buttons carry the real host embeds in data-embed attributes.
        val serverPageUrl = document.selectFirst("a.xtgo")?.attr("abs:href")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val serverPage = client.newCall(GET(serverPageUrl, headers)).execute().asJsoup()

        val embeds = (
            serverPage.select("[data-embed]").map { it.attr("abs:data-embed") } +
                serverPage.select("iframe#srcFrame").map { it.attr("abs:src") }
            ).filter { it.startsWith("http") }.distinct()

        return embeds.parallelCatchingFlatMapBlocking(::extractVideos).sortAndReturn()
    }

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun extractVideos(url: String): List<Video> {
        val videos = runCatching {
            when {
                "uqload" in url -> uqloadExtractor.videosFromUrl(url)
                "goodstream" in url -> goodStreamExtractor.videosFromUrl(url, "GoodStream")
                STREAMWISH_REGEX.containsMatchIn(url) -> streamWishExtractor.videosFromUrl(url)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        // Unknown or changed hosts: let a WebView sniff the media request.
        return videos.ifEmpty { universalExtractor.videosFromUrl(url, headers) }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    private fun parseEpisodeNumber(text: String): Float {
        // Try to extract number from Arabic text like "الحلقة 5" or "الحلقة 10"
        Regex("""الحلقة\s*(\d+)""").find(text)?.let { return it.groupValues[1].toFloat() }
        val numbers = Regex("""\d+""").findAll(text).map { it.value }.toList()
        return if (numbers.isNotEmpty()) {
            numbers.last().toFloat()
        } else {
            0f
        }
    }

    private fun List<Video>.sortAndReturn(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy<Video> { it.quality.contains(quality) }.thenBy {
                when {
                    it.quality.contains("1080") || it.quality == "High" -> 1000
                    it.quality.contains("720") -> 900
                    it.quality.contains("480") -> 800
                    it.quality.contains("360") -> 700
                    else -> 600
                }
            },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search.php?keywords=$query&page=$page", headers)

    override fun searchAnimeSelector(): String = "ul.pm-ul-browse-videos > li"
    override fun searchAnimeNextPageSelector(): String = "ul.pagination li:last-child:not(.disabled) a[href*='page=']"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================ Filters =============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("هذا الموقع لا يستخدم الفلاتر المتقدمة"),
    )

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
                true
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private val STREAMWISH_REGEX = Regex("(?:streamwish|vidtube|bigwarp|emturbovid|updown)\\.")
    }
}
