package eu.kanade.tachiyomi.animeextension.en.hentaimama

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class HentaiMama :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)

    // Popular Anime

    // The site moved to "series-card" cards; /tvshows/ still uses the old Dooplay "article.tvshows".
    override fun popularAnimeSelector(): String = "article.series-card, article.tvshows"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/advance-search/page/$page/?submit=Submit&filter=weekly")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val link = element.selectFirst("h3.sc-title a, div.data h3 a, a[href*=/tvshows/]")!!
        anime.setUrlWithoutDomain(link.attr("href"))
        val baseTitle = link.text().ifEmpty { element.selectFirst("img")?.attr("alt").orEmpty() }
        val duration = element.selectFirst("span.duration, span.time, div.duration, time, span.badge, div.badge, span.feist, span.time-length")?.text()?.trim()
        anime.title = if (!duration.isNullOrEmpty()) "[$duration] $baseTitle" else baseTitle
        anime.thumbnail_url = element.getImageUrl()
        return anime
    }

    private fun Element.getImageUrl(): String? {
        val img = if (tagName() == "img") this else selectFirst("img")
        return img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("poster").ifEmpty {
                        it.attr("src")
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

    override fun popularAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> = super.episodeListParse(response).sortedByDescending { it.episode_number }

    override fun episodeListSelector() = "a.dt-se-item"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val title = element.selectFirst("b.dt-se-title")?.text().orEmpty()
        val number = EPISODE_NUMBER_REGEX.find(element.selectFirst("b.dt-se-num")?.text() ?: title)

        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = title.ifEmpty { "Episode ${number?.value ?: 1}" }
        episode.date_upload = element.selectFirst("span.dt-se-date")?.text()?.let {
            runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull()
        } ?: 0L
        episode.episode_number = number?.value?.toFloatOrNull() ?: 1F

        return episode
    }

    // Video Extractor

    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val m3u8Server by lazy { M3u8ServerManager(client) }
    private val playlistUtils by lazy { PlaylistUtils(fastClient, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val postId = POST_ID_REGEX.find(html)?.groupValues?.get(1)
            ?: Jsoup.parse(html).selectFirst("[data-id]")?.attr("data-id")
            ?: return emptyList()

        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add("a", postId)
            .build()

        val newHeaders = Headers.headersOf("referer", "$baseUrl/")

        // Returns a JSON array of <iframe> HTML strings, one per mirror.
        val players = fastClient.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", newHeaders, body))
            .execute().use { it.body.string() }
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
        val urls = IFRAME_SRC_REGEX.findAll(players)
            .map { Parser.unescapeEntities(it.groupValues[1], true) }
            .distinct()
            .toList()

        val extracted = urls.parallelCatchingFlatMapBlocking { url ->
            val mirror = when {
                url.contains("newjav.php") -> "Mirror 1"
                url.contains("dt_embed=hls-mp4") -> "Mirror 2"
                url.contains("dt_embed=r2-mp4") -> "Mirror 3"
                url.contains("dt_embed=hls") -> "HLS"
                url.contains("dt_embed=rtmp") -> "Beta"
                else -> "Default"
            }
            val page = fastClient.newCall(GET(url, newHeaders)).execute().use { it.body.string() }
                .replace("\\u0026", "&")
                .replace("\\/", "/")

            STREAM_URL_REGEX.findAll(page).map { it.value }.distinct().flatMap { videoUrl ->
                if (videoUrl.contains(".m3u8")) {
                    playlistUtils.extractFromHls(videoUrl, referer = "$baseUrl/", videoNameGen = { "$mirror - $it" })
                } else {
                    val quality = QUALITY_REGEX.find(videoUrl)?.value
                    listOf(Video(videoUrl, if (quality != null) "$mirror - $quality" else mirror, videoUrl, headers))
                }
            }.toList()
        }.distinctBy { it.videoUrl ?: it.url }

        val useSeekCache = preferences.getBoolean(PREF_SEEK_CACHE_KEY, true)
        return if (useSeekCache) {
            m3u8Server.wrapVideosForCache(
                videos = extracted,
                cacheHls = true,
                cacheMp4 = true,
                defaultReferer = "$baseUrl/",
                defaultOrigin = baseUrl,
                defaultUserAgent = headers["User-Agent"],
            )
        } else {
            extracted
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = NEXT_PAGE_SELECTOR

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.trim()
        // advance-search ignores ?s= now (404); text search lives on the WordPress /?s= endpoint.
        return if (cleanQuery.isNotEmpty()) {
            val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
            GET("$baseUrl/page/$page/?s=$encodedQuery")
        } else {
            GET("$baseUrl/advance-search/page/$page/?${getSearchParameters(filters)}")
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.dsc-poster img, div.sheader div.poster img")?.getImageUrl()
        anime.title = document.selectFirst("h1.dsc-title, div.sheader h1")?.text().orEmpty()
        anime.genre = document.select("a[href*=/genre/]")
            .map { it.text() }.distinct()
            .joinToString(", ")
        anime.description = buildString {
            append(document.selectFirst("div.dsc-desc, #info1 div.wp-content")?.text().orEmpty())
            document.selectFirst(".dsc-alt")?.text()?.takeIf(String::isNotBlank)?.let {
                append("\n\nAlternative title: $it")
            }
        }
        anime.author = document.select("a[href*=/studio/]")
            .map { it.text() }.distinct()
            .joinToString(", ")
        anime.status = if (document.selectFirst(".dsc-chip.is-airing") != null) SAnime.ONGOING else SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = NEXT_PAGE_SELECTOR

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("Mirror 2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEEK_CACHE_KEY
            title = "Enable local seek/skip cache"
            summary = "Caches watched HLS segments and MP4 blocks locally (20 min TTL) for instant skip/rewind."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    // Filters

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)
    private fun getGenres() = listOf(
        Genre("3D"),
        Genre("Action"),
        Genre("Adventure"),
        Genre("Ahegao"),
        Genre("Anal"),
        Genre("Animal Ears"),
        Genre("Beastiality"),
        Genre("Blackmail"),
        Genre("Blowjob"),
        Genre("Bondage"),
        Genre("Brainwashed"),
        Genre("Bukakke"),
        Genre("Cat Girl"),
        Genre("Comedy"),
        Genre("Cosplay"),
        Genre("Creampie"),
        Genre("Cross-dressing"),
        Genre("Dark Skin"),
        Genre("DeepThroat"),
        Genre("Demons"),
        Genre("Doctor"),
        Genre("Double Penatration"),
        Genre("Drama"),
        Genre("Dubbed"),
        Genre("Ecchi"),
        Genre("Elf"),
        Genre("Eroge"),
        Genre("Facesitting"),
        Genre("Facial"),
        Genre("Fantasy"),
        Genre("Female Doctor"),
        Genre("Female Teacher"),
        Genre("Femdom"),
        Genre("Footjob"),
        Genre("Futanari"),
        Genre("Gangbang"),
        Genre("Gore"),
        Genre("Gyaru"),
        Genre("Harem"),
        Genre("Historical"),
        Genre("Horny Slut"),
        Genre("Housewife"),
        Genre("Humiliation"),
        Genre("Incest"),
        Genre("Inflation"),
        Genre("Internal Cumshot"),
        Genre("Lactation"),
        Genre("Large Breasts"),
        Genre("Lolicon"),
        Genre("Magical Girls"),
        Genre("Maid"),
        Genre("Martial Arts"),
        Genre("Megane"),
        Genre("MILF"),
        Genre("Mind Break"),
        Genre("Molestation"),
        Genre("Non-Japanese"),
        Genre("NTR"),
        Genre("Nuns"),
        Genre("Nurses"),
        Genre("Office Ladies"),
        Genre("Police"),
        Genre("POV"),
        Genre("Pregnant"),
        Genre("Princess"),
        Genre("Public Sex"),
        Genre("Rape"),
        Genre("Rim job"),
        Genre("Romance"),
        Genre("Scat"),
        Genre("School Girls"),
        Genre("Sci-Fi"),
        Genre("Shimapan"),
        Genre("Short"),
        Genre("Shoutacon"),
        Genre("Slaves"),
        Genre("Sports"),
        Genre("Squirting"),
        Genre("Stocking"),
        Genre("Strap-on"),
        Genre("Strapped On"),
        Genre("Succubus"),
        Genre("Super Power"),
        Genre("Supernatural"),
        Genre("Swimsuit"),
        Genre("Tentacles"),
        Genre("Three some"),
        Genre("Tits Fuck"),
        Genre("Torture"),
        Genre("Toys"),
        Genre("Train Molestation"),
        Genre("Tsundere"),
        Genre("Uncensored"),
        Genre("Urination"),
        Genre("Vampire"),
        Genre("Vanilla"),
        Genre("Virgins"),
        Genre("Widow"),
        Genre("X-Ray"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Year", years)
    private fun getYears() = listOf(
        Year("2026"),
        Year("2025"),
        Year("2024"),
        Year("2023"),
        Year("2022"),
        Year("2021"),
        Year("2020"),
        Year("2019"),
        Year("2018"),
        Year("2017"),
        Year("2016"),
        Year("2015"),
        Year("2014"),
        Year("2013"),
        Year("2012"),
        Year("2011"),
        Year("2010"),
        Year("2009"),
        Year("2008"),
        Year("2007"),
        Year("2006"),
        Year("2005"),
        Year("2004"),
        Year("2003"),
        Year("2002"),
        Year("2001"),
        Year("2000"),
        Year("1999"),
        Year("1998"),
        Year("1997"),
        Year("1996"),
        Year("1995"),
        Year("1994"),
        Year("1993"),
        Year("1992"),
        Year("1991"),
        Year("1987"),
    )

    internal class Producer(val id: String) : AnimeFilter.CheckBox(id)
    private class ProducerList(producers: List<Producer>) : AnimeFilter.Group<Producer>("Producer", producers)
    private fun getProducer() = listOf(
        Producer("8bit"),
        Producer("Actas"),
        Producer("Active"),
        Producer("AIC"),
        Producer("AIC A.S.T.A."),
        Producer("Alice Soft"),
        Producer("An DerCen"),
        Producer("Angelfish"),
        Producer("Animac"),
        Producer("AniMan"),
        Producer("Animax"),
        Producer("Antechinus"),
        Producer("APPP"),
        Producer("Armor"),
        Producer("Arms"),
        Producer("Asahi Production"),
        Producer("AT-2"),
        Producer("Blue Eyes"),
        Producer("BOMB! CUTE! BOMB!"),
        Producer("BOOTLEG"),
        Producer("Bunnywalker"),
        Producer("Central Park Media"),
        Producer("CherryLips"),
        Producer("ChiChinoya"),
        Producer("Chippai"),
        Producer("ChuChu"),
        Producer("Circle Tribute"),
        Producer("CLOCKUP"),
        Producer("Collaboration Works"),
        Producer("Comic Media"),
        Producer("Cosmic Ray"),
        Producer("Cosmo"),
        Producer("Cotton Doll"),
        Producer("Cranberry"),
        Producer("D3"),
        Producer("Daiei"),
        Producer("Digital Works"),
        Producer("Discovery"),
        Producer("Dream Force"),
        Producer("Dubbed"),
        Producer("Easy Film"),
        Producer("Echo"),
        Producer("EDGE"),
        Producer("Filmlink International"),
        Producer("Five Ways"),
        Producer("Front Line"),
        Producer("Frontier Works"),
        Producer("Godoy"),
        Producer("Gold Bear"),
        Producer("Green Bunny"),
        Producer("Himajin Planning"),
        Producer("Hokiboshi"),
        Producer("Hoods Entertainment"),
        Producer("Horipro"),
        Producer("Hot Bear"),
        Producer("HydraFXX"),
        Producer("Innocent Grey"),
        Producer("Jam"),
        Producer("JapanAnime"),
        Producer("King Bee"),
        Producer("Kitty Films"),
        Producer("Kitty Media"),
        Producer("Knack Productions"),
        Producer("KSS"),
        Producer("Lemon Heart"),
        Producer("Lune Pictures"),
        Producer("Majin"),
        Producer("Marvelous Entertainment"),
        Producer("Mary Jane"),
        Producer("Media"),
        Producer("Media Blasters"),
        Producer("Milkshake"),
        Producer("Mitsu"),
        Producer("Moonstone Cherry"),
        Producer("Mousou Senka"),
        Producer("MS Pictures"),
        Producer("Nihikime no Dozeu"),
        Producer("Nur"),
        Producer("NuTech Digital"),
        Producer("Obtain Future"),
        Producer("Office Take Off"),
        Producer("OLE-M"),
        Producer("Oriental Light and Magic"),
        Producer("Oz"),
        Producer("Pashmina"),
        Producer("Pink Pineapple"),
        Producer("Pixy"),
        Producer("PoRO"),
        Producer("Production I.G"),
        Producer("Queen Bee"),
        Producer("Sakura Purin Animation"),
        Producer("Schoolzone"),
        Producer("Selfish"),
        Producer("Seven"),
        Producer("Shelf"),
        Producer("Shinkuukan"),
        Producer("Shinyusha"),
        Producer("Shouten"),
        Producer("Silky’s"),
        Producer("Soft Garage"),
        Producer("SoftCel Pictures"),
        Producer("SPEED"),
        Producer("Studio 9 Maiami"),
        Producer("Studio Eromatick"),
        Producer("Studio Fantasia"),
        Producer("Studio Jack"),
        Producer("Studio Kyuuma"),
        Producer("Studio Matrix"),
        Producer("Studio Sign"),
        Producer("Studio Tulip"),
        Producer("Studio Unicorn"),
        Producer("Suzuki Mirano"),
        Producer("T-Rex"),
        Producer("The Right Stuf International"),
        Producer("Toho Company"),
        Producer("Top-Marschal"),
        Producer("Toranoana"),
        Producer("Toshiba Entertainment"),
        Producer("Triangle Bitter"),
        Producer("Triple X"),
        Producer("Union Cho"),
        Producer("Valkyria"),
        Producer("White Bear"),
        Producer("Y.O.U.C"),
        Producer("ZIZ Entertainment"),
        Producer("Zyc"),

    )

    private data class Order(val name: String, val id: String)
    private class OrderList(Orders: Array<String>) : AnimeFilter.Select<String>("Order", Orders)
    private val orderName = getOrder().map {
        it.name
    }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Weekly Views", "weekly"),
        Order("Monthly Views", "monthly"),
        Order("Alltime Views", "alltime"),
        Order("A-Z", "alphabet"),
        Order("Rating", "rating"),

    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var sortBy = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring =
                                totalstring + "&genres_filter%5B" + "%5D=" + Genre.id
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring =
                                totalstring + "&years_filter%5B" + "%5D=" + Year.id
                        }
                    }
                }

                is ProducerList -> { // ---Producer
                    filter.state.forEach { Producer ->
                        if (Producer.state) {
                            totalstring =
                                totalstring + "&studios_filter%5B" + "%5D=" + Producer.id
                        }
                    }
                }

                is OrderList -> { // ---Order
                    sortBy = getOrder()[filter.state].id
                }

                else -> {}
            }
        }

        return "$totalstring&submit=Submit&filter=$sortBy"
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderList(orderName),
        GenreList(getGenres()),
        YearList(getYears()),
        ProducerList(getProducer()),
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_SEEK_CACHE_KEY = "pref_seek_cache"
        private val PREF_QUALITY_ENTRIES = arrayOf("Mirror 1", "Mirror 2", "Mirror 3", "HLS", "Beta")

        private const val NEXT_PAGE_SELECTOR = "a.dt-pg-next, div.pagination-wraper div.resppages a, link[rel=next]"
        private val EPISODE_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val DATE_FORMAT by lazy { SimpleDateFormat("MMM dd, yyyy", Locale.US) }
        private val POST_ID_REGEX = Regex("""\ba:\s*'(\d+)'""")
        private val IFRAME_SRC_REGEX = Regex("""src="([^"]+)"""")
        private val STREAM_URL_REGEX = Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""")
        private val QUALITY_REGEX = Regex("""\d{3,4}p""")
    }
}
