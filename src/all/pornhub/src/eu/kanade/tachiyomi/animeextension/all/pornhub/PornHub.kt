package eu.kanade.tachiyomi.animeextension.all.pornhub

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class PornHub :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "PornHub"

    override val baseUrl = "https://www.pornhub.com"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Cookie", "platform=pc; age_verified=1; accessAgeDisclaimerPH=1; accessAgeDisclaimerUK=1; accessPH=1; hasVisited=1; cookiesBannerSeen=1; cookieConsent=3")
        .add("Referer", "$baseUrl/")

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(browseUrl(page, sort = "ht"), headers)

    override fun popularAnimeSelector(): String = "li.pcVideoListItem:not(.mockNsfwThumb), li.videoBox:not(.noVideo), ul#videoCategory li.videoblock, ul#videoSearchResult li.videoblock, li.videoWrapper:not(.noVideo), div.positionRelative.singleVideo, li[data-video-id]"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a.linkVideoThumb, div.phimage a, a.imageLink, a.js-videoPreview, a[href*=/view_video]")
        val href = link?.attr("abs:href")
            ?: element.selectFirst("a[href*=/view_video]")?.attr("abs:href")
            ?: ""
        setUrlWithoutDomain(href)
        title = link?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("span.title a, div.title a, a.thumbnailTitle")?.attr("title")?.ifBlank { null }
            ?: element.selectFirst("span.title a, div.title a, a.thumbnailTitle")?.text()?.ifBlank { null }
            ?: element.text().orEmpty()
        thumbnail_url = element.selectFirst("div.phimage img, img")?.let { img ->
            img.attr("data-image").ifBlank { null }
                ?: img.attr("data-highres").ifBlank { null }
                ?: img.attr("data-poster").ifBlank { null }
                ?: img.attr("poster").ifBlank { null }
                ?: img.attr("data-mediumthumb").ifBlank { null }
                ?: img.attr("data-src").ifBlank { null }
                ?: img.attr("data-thumb_url").ifBlank { null }
                ?: img.attr("data-smallthumb").ifBlank { null }
                ?: img.attr("src").ifBlank { null }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "li.page_next:not(.page_disabled):not(.disabled) a, a.page_next:not(.disabled), a[rel=next]:not(.disabled), link[rel=next], a:has(i.ph-icon-chevron-right):not(.disabled), div.pagination li.active + li a"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector())
            .map { popularAnimeFromElement(it) }
            .filter { it.url.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.url }
        val hasNext = document.selectFirst(popularAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNext)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl(page, sort = "cm"), headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "ht"
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.selected.orEmpty()
        val pornstar = filters.firstInstanceOrNull<PornstarFilter>()?.selected.orEmpty()
        val duration = filters.firstInstanceOrNull<DurationFilter>()?.selected.orEmpty()
        val hd = filters.firstInstanceOrNull<HDFilter>()?.selected.orEmpty()

        // Pornstar takes precedence (path-based browse).
        if (pornstar.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("pornstar/$pornstar/videos")
                .addQueryParameter("o", sort)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        // Keyword search.
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("video/search")
                .addQueryParameter("search", query.trim())
                .addQueryParameter("o", sort)
                .addQueryParameter("page", page.toString())
                .apply {
                    if (category.isNotBlank()) addQueryParameter("filter_category", category)
                    applyDuration(duration)
                    if (hd.isNotBlank()) addQueryParameter("hd", "1")
                }
                .build()
            return GET(url, headers)
        }

        // Category browse without query.
        if (category.isNotBlank()) {
            return GET(browseUrl(page, sort = sort, duration = duration, category = category, hd = hd), headers)
        }

        return GET(browseUrl(page, sort = sort, duration = duration, hd = hd), headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.title span.inlineFree, h1.title, span.inlineFree")
            ?.text()
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()

        author = document.selectFirst(
            "div.video-detailed-info div.userInfo a.bolded, " +
                "div.userInfo .usernameBadgesWrapper a, " +
                "div.usernameWrap a.bolded, " +
                "div.userInfo a[href*=/model/], " +
                "div.userInfo a[href*=/pornstar/], " +
                "div.userInfo a[href*=/channels/]",
        )?.text()

        description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        genre = document.select(
            "div.categoriesWrapper a, div.tagsWrapper a, " +
                "div.pornstarsWrapper a, a.js-categoryLink, a.js-tagLink",
        ).mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
            .ifBlank { null }

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

        status = SAnime.COMPLETED
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "Video"
            setUrlWithoutDomain(response.request.url.toString())
            date_upload = System.currentTimeMillis()
        },
    )

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val pageUrl = response.request.url.toString()
        val html = response.body.string()
        val videoList = mutableListOf<Video>()
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        // Extract flashvars JSON: search script tags for "mediaDefinitions"
        // or regex "var flashvars... = {".
        val flashvarsJson = extractFlashvars(html)
        val mediaDefinitions: JsonArray? = try {
            flashvarsJson?.let { json.parseToJsonElement(it).jsonObject["mediaDefinitions"]?.jsonArray }
        } catch (_: Exception) {
            null
        }

        fun resolveUrl(raw: String?): String? {
            val url = raw?.replace("\\/", "/")?.trim() ?: return null
            if (url.isBlank()) return null
            return when {
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$baseUrl$url"
                else -> null
            }
        }

        fun processDefinitions(defs: JsonArray, depth: Int = 0) {
            if (depth > 2) return
            for (item in defs) {
                val obj: JsonObject = try {
                    item.jsonObject
                } catch (_: Exception) {
                    continue
                }
                val format = obj["format"]?.jsonPrimitive?.contentOrNull
                val videoUrl = resolveUrl(obj["videoUrl"]?.jsonPrimitive?.contentOrNull)
                val qualityElement = obj["quality"]
                val qualityStr: String? = try {
                    qualityElement?.jsonPrimitive?.contentOrNull
                } catch (_: Exception) {
                    null
                }
                val height = try {
                    obj["height"]?.jsonPrimitive?.intOrNull
                } catch (_: Exception) {
                    null
                }

                if (format == "hls" && videoUrl != null) {
                    if ("/get_media" in videoUrl) {
                        // Fetch get_media JSON and recursively process definitions.
                        try {
                            client.newCall(GET(videoUrl, videoHeaders)).execute().use { resp ->
                                if (!resp.isSuccessful) return@use
                                val body = resp.body.string().trim()
                                if (!body.startsWith("[")) return@use
                                val arr = try {
                                    json.parseToJsonElement(body).jsonArray
                                } catch (_: Exception) {
                                    null
                                }
                                if (arr != null) processDefinitions(arr, depth + 1)
                            }
                        } catch (_: Exception) {}
                        continue
                    }
                    if (".m3u8" in videoUrl) {
                        // Master playlist: fetch and parse variants.
                        try {
                            client.newCall(GET(videoUrl, videoHeaders)).execute().use { resp ->
                                if (!resp.isSuccessful) throw Exception("master fetch failed")
                                val playlist = resp.body.string()
                                val lines = playlist.lines()
                                var found = false
                                val basePrefix = videoUrl.substringBeforeLast("/")
                                for (i in lines.indices) {
                                    val line = lines[i].trim()
                                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                                        val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                        val next = lines.getOrNull(i + 1)?.trim() ?: continue
                                        if (next.isBlank() || next.startsWith("#")) continue
                                        val variantUrl = when {
                                            next.startsWith("http") -> next
                                            next.startsWith("/") -> "$baseUrl$next"
                                            else -> "$basePrefix/$next"
                                        }
                                        val label = if (h != null) "${h}p (HLS)" else "HLS ${qualityStr ?: "720"}p"
                                        videoList.add(Video(variantUrl, label, variantUrl, videoHeaders))
                                        found = true
                                    }
                                }
                                if (!found) throw Exception("no variants")
                            }
                        } catch (_: Exception) {
                            videoList.add(Video(videoUrl, "HLS ${qualityStr ?: "720"}p", videoUrl, videoHeaders))
                        }
                        continue
                    }
                    // Non-m3u8 hls entry: add directly.
                    videoList.add(Video(videoUrl, "HLS ${qualityStr ?: "720"}p", videoUrl, videoHeaders))
                }

                if (format == "mp4" && videoUrl != null && "/get_media" !in videoUrl) {
                    val label = if (!qualityStr.isNullOrBlank()) {
                        "${qualityStr}p"
                    } else if (height != null) {
                        "${height}p"
                    } else {
                        "MP4"
                    }
                    videoList.add(Video(videoUrl, "MP4 $label", videoUrl, videoHeaders))
                }
            }
        }

        if (mediaDefinitions != null) {
            processDefinitions(mediaDefinitions)
        }

        // Fallback: search html for master.m3u8 URLs.
        if (videoList.isEmpty()) {
            Regex("""https?:\\/\\/[^"'\\s]+?\\.m3u8[^"'\\s]*""")
                .findAll(html)
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .forEach { url ->
                    val resolved = resolveUrl(url) ?: return@forEach
                    videoList.add(Video(resolved, "HLS 720p", resolved, videoHeaders))
                }
        }

        if (videoList.isEmpty()) {
            throw Exception("Host list empty: no playable streams found (page: $pageUrl)")
        }

        return videoList.sort().distinctBy { it.url to it.quality }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { !it.quality.contains(quality, ignoreCase = true) },
                { !it.quality.contains("HLS", ignoreCase = true) },
                { -(QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0) },
            ),
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply in SEARCH (empty query OK). Not on Popular tab."),
        SortFilter(),
        CategoryFilter(),
        PornstarFilter(),
        DurationFilter(),
        HDFilter(),
    )

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                Pair("Hot", "ht"),
                Pair("Most Relevant", "mr"),
                Pair("Most Viewed", "mv"),
                Pair("Top Rated", "tr"),
                Pair("Newest", "cm"),
                Pair("Longest", "lg"),
            ),
        )

    private class DurationFilter :
        UriPartFilter(
            "Duration",
            arrayOf(
                Pair("Any", ""),
                Pair("Under 10 min", "max:10"),
                Pair("10+ min", "min:10"),
                Pair("20+ min", "min:20"),
                Pair("30+ min", "min:30"),
            ),
        )

    private class HDFilter :
        UriPartFilter(
            "Quality",
            arrayOf(
                Pair("Any", ""),
                Pair("HD only", "hd"),
            ),
        )

    private class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Any", ""),
                Pair("Amateur", "3"),
                Pair("Anal", "35"),
                Pair("Arab", "127"),
                Pair("Asian", "1"),
                Pair("Babe", "5"),
                Pair("BBW", "6"),
                Pair("Big Ass", "4"),
                Pair("Big Dick", "7"),
                Pair("Big Tits", "8"),
                Pair("Bisexual Male", "76"),
                Pair("Blonde", "9"),
                Pair("Blowjob", "13"),
                Pair("Bondage", "10"),
                Pair("Brunette", "11"),
                Pair("Bukkake", "14"),
                Pair("Cartoon", "86"),
                Pair("Casting", "90"),
                Pair("Celebrity", "12"),
                Pair("College", "16"),
                Pair("Compilation", "57"),
                Pair("Cosplay", "241"),
                Pair("Creampie", "15"),
                Pair("Cuckold", "140"),
                Pair("Cumshot", "19"),
                Pair("Czech", "20"),
                Pair("Double Penetration", "72"),
                Pair("Ebony", "17"),
                Pair("Euro", "22"),
                Pair("Exclusive", "115"),
                Pair("Feet", "25"),
                Pair("Female Friendly", "95"),
                Pair("Fetish", "18"),
                Pair("Funny", "96"),
                Pair("Gangbang", "30"),
                Pair("German", "31"),
                Pair("Hardcore", "21"),
                Pair("HD Porn", "38"),
                Pair("Hentai", "36"),
                Pair("Interactive", "32"),
                Pair("Japanese", "111"),
                Pair("Latina", "26"),
                Pair("Lesbian", "27"),
                Pair("Massage", "78"),
                Pair("Mature", "28"),
                Pair("MILF", "29"),
                Pair("Music", "121"),
                Pair("Old/Young", "181"),
                Pair("Orgy", "33"),
                Pair("Parody", "34"),
                Pair("Party", "53"),
                Pair("Pornstar", "47"),
                Pair("POV", "41"),
                Pair("Public", "24"),
                Pair("Reality", "39"),
                Pair("Red Head", "42"),
                Pair("Role Play", "81"),
                Pair("Rough Sex", "67"),
                Pair("School", "88"),
                Pair("Small Tits", "59"),
                Pair("Solo Female", "492"),
                Pair("Solo Male", "493"),
                Pair("Squirt", "69"),
                Pair("Step Fantasy", "444"),
                Pair("Teen (18+)", "37"),
                Pair("Threesome", "65"),
                Pair("Toys", "23"),
                Pair("Transgender", "83"),
                Pair("Verified Amateurs", "138"),
                Pair("Verified Couples", "139"),
                Pair("Verified Models", "125"),
                Pair("Vintage", "40"),
                Pair("Webcam", "43"),
            ),
        )

    private class PornstarFilter :
        UriPartFilter(
            "Pornstar",
            arrayOf(
                Pair("Any", ""),
                Pair("Angela White", "angela-white"),
                Pair("Riley Reid", "riley-reid"),
                Pair("Mia Khalifa", "mia-khalifa"),
                Pair("Brandi Love", "brandi-love"),
                Pair("Abella Danger", "abella-danger"),
                Pair("Lana Rhoades", "lana-rhoades"),
                Pair("Adriana Chechik", "adriana-chechik"),
                Pair("Sasha Grey", "sasha-grey"),
                Pair("Lisa Ann", "lisa-ann"),
                Pair("Eva Elfie", "eva-elfie"),
                Pair("Nicole Aniston", "nicole-aniston"),
                Pair("Lena Paul", "lena-paul"),
                Pair("Violet Myers", "violet-myers"),
                Pair("Autumn Falls", "autumn-falls"),
                Pair("Alexis Texas", "alexis-texas"),
                Pair("Asa Akira", "asa-akira"),
                Pair("Madison Ivy", "madison-ivy"),
                Pair("Kendra Lust", "kendra-lust"),
                Pair("Ava Addams", "ava-addams"),
                Pair("Elsa Jean", "elsa-jean"),
                Pair("Dillion Harper", "dillion-harper"),
                Pair("Leah Gotti", "leah-gotti"),
                Pair("Mia Malkova", "mia-malkova"),
                Pair("Johnny Sins", "johnny-sins"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        val selected: String get() = vals[state].second
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // =============================== Helpers ==============================

    private fun browseUrl(
        page: Int,
        sort: String = "ht",
        duration: String = "",
        category: String = "",
        hd: String = "",
    ): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("video")
        .addQueryParameter("o", sort)
        .addQueryParameter("page", page.toString())
        .apply {
            if (category.isNotBlank()) addQueryParameter("c", category)
            applyDuration(duration)
            if (hd.isNotBlank()) addQueryParameter("hd", "1")
        }
        .build()
        .toString()

    private fun okhttp3.HttpUrl.Builder.applyDuration(duration: String) {
        when {
            duration.startsWith("min:") ->
                addQueryParameter("min_duration", duration.removePrefix("min:"))

            duration.startsWith("max:") ->
                addQueryParameter("max_duration", duration.removePrefix("max:"))
        }
    }

    private fun extractFlashvars(html: String): String? {
        val marker = Regex("""var\s+flashvars[_\w]*\s*=\s*\{""")
        val match = marker.find(html) ?: run {
            // Fallback: search script tags for mediaDefinitions.
            if (!html.contains("mediaDefinitions")) return null
            val idx = html.indexOf("\"mediaDefinitions\"")
            if (idx < 0) return null
            // Walk backwards to find enclosing object start is unreliable;
            // search for nearest "flashvars" assignment before it.
            val sub = html.substring(0, idx)
            val m2 = Regex("""var\s+flashvars[_\w]*\s*=\s*""").findAll(sub).lastOrNull() ?: return null
            val brace = html.indexOf('{', m2.range.last)
            if (brace < 0) return null
            return extractBalanced(html, brace, '{', '}')
        }
        val braceStart = html.indexOf('{', match.range.last - 1)
        if (braceStart < 0) return null
        return extractBalanced(html, braceStart, '{', '}')
    }

    private fun extractBalanced(source: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until source.length) {
            val c = source[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true

                open -> depth++

                close -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private inline fun <reified T> AnimeFilterList.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

    companion object {
        private val QUALITY_REGEX = Regex("""(\d{3,4})""")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "240p")
    }
}
