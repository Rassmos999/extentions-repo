package eu.kanade.tachiyomi.animeextension.all.javgg

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8ServerManager
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLEncoder

class Javgg :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "JavGG"

    override val baseUrl = "https://javgg.net"

    override val lang = "all"

    override val supportsLatest = true

    override val supportsRelatedAnimes = false

    private val preferences by getPreferencesLazy()

    private val m3u8Server by lazy { M3u8ServerManager(client) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidHide"

        private val SERVER_LIST = arrayOf(
            "VidHide",
            "F4Stream",
            "Vidara",
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
            "TurboPlay",
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val F4_API_REGEX = Regex("""data-api=["']([^"']+)["']""")
        private val FILECODE_M3U8_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")

        private val SECTIONS = arrayOf(
            "Trending (All)" to "/trending/",
            "Popular Today" to "/trending/?sort=today",
            "Popular This Week" to "/trending/?sort=weekly",
            "Popular This Month" to "/trending/?sort=monthly",
            "Latest Uploads" to "/new-post/",
        )

        private val GENRES = arrayOf(
            "All" to "",
            "4K" to "4k",
            "Beautiful Girl" to "beautiful-girl",
            "Big Breasts" to "big-breasts",
            "Big Tits" to "big-tits",
            "Blowjob" to "blowjob",
            "Cowgirl" to "cowgirl",
            "Creampie" to "creampie",
            "Drama" to "drama",
            "Exclusive Distribution" to "exclusive-distribution",
            "Featured Actress" to "featured-actress",
            "Hi-Def" to "hi-def",
            "High Definition" to "high-definition",
            "Married Woman" to "married-woman",
            "Mature Woman" to "mature-woman",
            "Sample Video" to "sample-video",
            "Slender" to "slender",
            "Slut" to "slut",
            "Solowork" to "solowork",
            "Squirting" to "squirting",
            "Standalone Work" to "standalone-work",
        )

        private val TAGS = arrayOf(
            "All" to "",
            "English Subtitle" to "english-subtitle",
            "Chinese Porn" to "chinese-porn",
            "Chinese Subtitle" to "chinese-subtitle",
            "Uncensored Leak" to "uncensored-leak",
            "Reduce Mosaic" to "reduce-mosaic",
            "Censored" to "censored",
            "Decensored" to "decensored",
            "Uncensored" to "uncensored",
            "Amateur Censored" to "amateur-censored",
            "Amateur JAV" to "amateur-jav",
            "Chinese Uncensored" to "chinese-uncensored",
            "English Encoded" to "english-encoded",
            "English Subtitle Amateur" to "english-subtitle-amateur",
            "FC2 Reduce Mosaic" to "fc2-reduce-mosaic",
            "FC2 Uncensored" to "fc2-uncensored",
            "Fc2 Uncensored English Sub" to "fc2-uncensored-english-sub",
            "Fc2 Uncensored/Censored" to "fc2-uncensored-censored",
            "HD Censored" to "hd-censored",
            "HD Uncensored" to "hd-uncensored",
            "1080p" to "1080p",
            "FHD" to "fhd",
            "1PON" to "1pon",
            "CARIB" to "carib",
            "10MU" to "10mu",
            "HEYZO" to "heyzo",
            "PACO" to "paco",
            "KIN8" to "kin8",
            "1P" to "1p",
            "C0930" to "c0930",
            "H0930" to "h0930",
            "H4610" to "h4610",
            "Reduce Mosaic Amateur" to "reduce-mosaic-amateur",
            "Studio Watermark" to "studio-watermark",
            "subbed by ilovejav" to "subbed-by-ilovejav",
            "subbed by yutatarokun" to "subbed-by-yutatarokun",
            "To Be Release" to "to-be-release",
            "November 2026" to "november-2026",
            "October 2026" to "october-2026",
            "September 2026" to "september-2026",
            "August 2026" to "august-2026",
            "July 2026" to "july-2026",
            "June 2026" to "june-2026",
            "May 2026" to "may-2026",
            "April 2026" to "april-2026",
            "March 2026" to "march-2026",
            "February 2026" to "february-2026",
            "January 2026" to "january-2026",
            "December 2025" to "december-2025",
            "November 2025" to "november-2025",
            "October 2025" to "october-2025",
            "September 2025" to "september-2025",
            "August 2025" to "august-2025",
            "July 2025" to "july-2025",
            "June 2025" to "june-2025",
            "May 2025" to "may-2025",
            "April 2025" to "april-2025",
            "March 2025" to "march-2025",
            "February 2025" to "february-2025",
            "January 2025" to "january-2025",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            status = SAnime.COMPLETED
            description = document.selectFirst("#cover")?.text()
        }

        document.select(".data .boxye2").forEach { element ->
            val category = element.select("[id*=owye2]").text().trim()
            val tags = element.select(".sgeneros3 a").joinToString { it.text() }
            when {
                category.contains("Genres:") -> animeDetails.genre = tags
                category.contains("Cast:") -> animeDetails.artist = tags
                category.contains("Maker:") -> animeDetails.author = tags
            }
        }

        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parsePostGrid(response.asJsoup())

    private fun parsePostGrid(document: Document): AnimesPage {
        val elements = document.select("article[id*=post-]")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                val rawTitle = element.selectFirst(".data h3")!!.text()
                val duration = element.selectFirst("span.duration, span.time, div.duration, time, .time, .duration, span.runtime, .poster span")
                    ?.text()?.trim()
                    ?.takeIf { d -> d.contains(":") }
                title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
                thumbnail_url = element.selectFirst(".poster")?.getImageUrl()
            }
        }
        val nextPage = document.select("#nextpagination, .pagination a.next, a[rel=next], .pagination span.current + a, div.resppages a:contains(>)").any() && animeList.isNotEmpty()
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/new-post/page/$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addPathSegments("page/$page/")
                addQueryParameter("s", query.trim())
            }.build()
            return GET(url, headers)
        }

        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selected.orEmpty()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected.orEmpty()
        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.selected ?: "/trending/"

        val targetUrl = when {
            tag.isNotBlank() -> "$baseUrl/tag/$tag/" + if (page > 1) "page/$page/" else ""

            genre.isNotBlank() -> "$baseUrl/genre/$genre/" + if (page > 1) "page/$page/" else ""

            section.contains("?") -> {
                val basePath = section.substringBefore("?").trimEnd('/')
                val queryPart = section.substringAfter("?")
                if (page > 1) "$baseUrl$basePath/page/$page/?$queryPart" else "$baseUrl$section"
            }

            else -> "$baseUrl$section" + if (page > 1) "page/$page/" else ""
        }
        return GET(targetUrl, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val searchElements = document.select(".result-item article")
        if (searchElements.isEmpty()) {
            return parsePostGrid(document)
        }
        val animeList = searchElements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
                val rawTitle = element.selectFirst(".details .title")!!.text()
                val duration = element.selectFirst("span.duration, span.time, div.duration, time, .time, .duration, span.runtime, .image span")
                    ?.text()?.trim()
                    ?.takeIf { d -> d.contains(":") }
                title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
                thumbnail_url = element.selectFirst(".image")?.getImageUrl()
            }
        }
        val nextPage = document.select("#nextpagination, .pagination a.next, a[rel=next], .pagination span.current + a, div.resppages a:contains(>)").any() && animeList.isNotEmpty()
        return AnimesPage(animeList, nextPage)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SectionFilter(),
        TagFilter(),
        GenreFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val entries: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, entries.map { it.first }.toTypedArray()) {
        val selected get() = entries[state].second
    }

    class SectionFilter : UriPartFilter("Sort / Section", SECTIONS)
    class TagFilter : UriPartFilter("Category / Tag", TAGS)
    class GenreFilter : UriPartFilter("Genre", GENRES)

    override fun String.stripKeywordForRelatedAnimes(): List<String> {
        val regexWhitespace = Regex("\\s+")
        val regexSpecialCharacters =
            Regex("([-!~#$%^&*+_|/\\\\,?:;'“”‘’\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)")
        val regexNumberOnly = Regex("^\\d+$")

        return replace(regexSpecialCharacters, " ")
            .split(regexWhitespace)
            .map {
                // remove number only
                it.replace(regexNumberOnly, "")
                    .lowercase()
            }
            // exclude single character
            .filter { it.length > 1 }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.select(".dooplay_player_option, [id*=source-player] iframe").any()) {
            listOf(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1F
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            emptyList()
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("[id*=source-player] iframe").parallelCatchingFlatMapBlocking { iframe ->
            val embedSrc = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            if (embedSrc.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()
            val numOpt = iframe.closest(".source-box")?.attr("id")?.removePrefix("source-player-")
            val optionNum = if (numOpt == "trailer") "1" else numOpt
            val serverName = document.selectFirst("[data-nume=\"$optionNum\"] .server")
                ?.let { it.attr("data-text").ifBlank { it.text() } }
                .orEmpty()
            serverVideoResolver(serverName, embedSrc)
        }
    }

    private suspend fun serverVideoResolver(server: String, url: String): List<Video> {
        val target = "$server $url".lowercase()
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        return when {
            target.contains("f4scom") || target.contains("f4jav") || target.contains("f4scdn") || target.contains("f4stream") -> {
                extractF4Videos(url)
            }

            target.contains("vidara") || target.contains("mxzhxco8dft") -> {
                extractVidaraVideos(url)
            }

            target.contains("ok.ru") || target.contains("okru") -> OkruExtractor(client).videosFromUrl(url)

            target.contains("filelions") || target.contains("lion") -> {
                StreamWishExtractor(client, embedHeaders).videosFromUrl(url, videoNameGen = { "FileLions:$it" })
                    .ifEmpty { VidHideExtractor(client, embedHeaders).videosFromUrl(url, videoNameGen = { "FileLions:$it" }) }
            }

            target.contains("earnvid") || target.contains("streamhgjav") || target.contains("vidhide") || target.contains("streamhide") || target.contains("guccihide") || target.contains("streamvid") -> {
                VidHideExtractor(client, embedHeaders).videosFromUrl(url)
            }

            target.contains("wishembed") || target.contains("streamwish") || target.contains("strwish") || target.contains("wish") -> {
                val host = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "https://streamwish.to"
                val docHeaders = headers.newBuilder()
                    .set("Origin", host)
                    .set("Referer", "$host/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
                    .ifEmpty { VidHideExtractor(client, embedHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" }) }
            }

            target.contains("voe") || target.contains("susanexpectcare") -> VoeExtractor(client, embedHeaders).videosFromUrl(url)

            target.contains("yourupload") || target.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = embedHeaders)

            target.contains("turboplay") -> {
                val turboDocument = client.newCall(GET(url)).execute().asJsoup()
                val masterUrl = turboDocument.select("#video_player").attr("data-hash")
                val customHeaders = headers.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Origin", "https://${turboDocument.location().toHttpUrl().host}")
                    add("Referer", "https://${turboDocument.location().toHttpUrl().host}/")
                }.build()

                listOf(Video(masterUrl, "TurboPlay", masterUrl, customHeaders))
            }

            else -> {
                VidHideExtractor(client, embedHeaders).videosFromUrl(url)
                    .ifEmpty { extractDirectM3u8(url, embedHeaders) }
            }
        }
    }

    private fun extractF4Videos(embedUrl: String): List<Video> {
        val parsedEmbed = embedUrl.toHttpUrlOrNull() ?: return emptyList()
        val embedOrigin = "${parsedEmbed.scheme}://${parsedEmbed.host}"
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val html = client.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() }
        val apiPath = F4_API_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()
        val apiUrl = parsedEmbed.resolve(apiPath)?.toString() ?: return emptyList()
        val playUrl = "$apiUrl?ab=0&ad=1&r=${URLEncoder.encode("$baseUrl/", "UTF-8")}"
        val apiHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val src = client.newCall(GET(playUrl, apiHeaders)).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            JSONObject(resp.body.string()).optString("src").trim()
        }
        if (src.isBlank()) return emptyList()
        if (!m3u8Server.isRunning()) m3u8Server.startServer()
        val localUrl = m3u8Server.processM3u8Url(
            m3u8Url = src,
            referer = "$embedOrigin/",
            userAgent = headers["User-Agent"],
            origin = embedOrigin,
        ) ?: return emptyList()
        return listOf(Video(localUrl, "F4Stream - HLS", localUrl))
    }

    private fun extractVidaraVideos(embedUrl: String): List<Video> {
        val filecode = embedUrl.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull { it.isNotBlank() }
            ?: return emptyList()
        val embedHost = embedUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        val candidates = listOfNotNull(embedHost, "https://vidara.to", "https://mxzhxco8dft.com").distinct()
        val payload = JSONObject().apply {
            put("filecode", filecode)
            put("device", "desktop")
            put("codecs", JSONArray(listOf("avc1", "hev1", "vp9")))
        }.toString()

        for (origin in candidates) {
            val streamUrl = runCatching {
                val apiHeaders = headers.newBuilder()
                    .set("Referer", "$origin/e/$filecode")
                    .set("Origin", origin)
                    .set("Content-Type", "application/json")
                    .build()
                client.newCall(POST("$origin/api/stream", apiHeaders, payload.toRequestBody(JSON_MEDIA_TYPE)))
                    .execute()
                    .use { resp ->
                        if (!resp.isSuccessful) return@use null
                        JSONObject(resp.body.string()).optString("streaming_url").takeIf { it.startsWith("http") }
                    }
            }.getOrNull()
            if (!streamUrl.isNullOrBlank()) {
                return listOf(Video(streamUrl, "Vidara - HLS", streamUrl, headers))
            }
        }
        return emptyList()
    }

    private fun extractDirectM3u8(url: String, reqHeaders: okhttp3.Headers): List<Video> = runCatching {
        val body = client.newCall(GET(url, reqHeaders)).execute().use { it.body.string() }
            .replace("\\/", "/")
        val m3u8 = FILECODE_M3U8_REGEX.find(body)?.value ?: return emptyList()
        val origin = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        val videoHeaders = headers.newBuilder().apply {
            if (origin != null) {
                set("Referer", "$origin/")
                set("Origin", origin)
            }
        }.build()
        listOf(Video(m3u8, "HLS", m3u8, videoHeaders))
    }.getOrDefault(emptyList())

    private fun org.jsoup.nodes.Element.getImageUrl(): String? {
        val img = if (tagName() == "img") this else selectFirst("img")
        val url = img?.let {
            it.attr("data-src").ifEmpty {
                it.attr("data-original").ifEmpty {
                    it.attr("poster").ifEmpty {
                        it.attr("src")
                    }
                }
            }
        }?.takeIf { it.isNotBlank() } ?: run {
            val imageLinkRegex = """(https?:)?//\S+\.(jpg|jpeg|png|webp)""".toRegex()
            for (link in select("[href], [src]")) {
                val href = link.attr("href")
                val src = link.attr("src")
                if (imageLinkRegex.matches(href)) return@run href
                if (imageLinkRegex.matches(src)) return@run src
            }
            val textMatches = imageLinkRegex.find(text())
            val htmlMatches = imageLinkRegex.find(outerHtml())
            textMatches?.value ?: htmlMatches?.value
        }
        return url?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
