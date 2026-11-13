package eu.kanade.tachiyomi.animeextension.all.supjav

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.io.IOException

class SupJav(override val lang: String = "en") :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "SupJav"

    override val baseUrl = "https://supjav.com"

    override val supportsLatest = true

    /*
     * Keep NetworkHelper's Android UA and cookie jar so Cloudflare WebView clearance cookies match.
     */
    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private val langPath = when (lang) {
        "en" -> ""
        else -> "/$lang"
    }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl$langPath/popular${if (page > 1) "/page/$page" else ""}", headers)

    override fun popularAnimeSelector() = "div.posts > div.post > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else (element.selectFirst("a") ?: element)
        setUrlWithoutDomain(link.attr("href"))

        val img = element.selectFirst("img") ?: element.parent()?.selectFirst("img")
        if (img != null) {
            title = img.attr("alt").ifBlank { img.attr("title") }
            val rawThumb = img.attr("data-original")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
                .ifBlank { img.absUrl("data-original") }
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.absUrl("src") }

            thumbnail_url = when {
                rawThumb.startsWith("//") -> "https:$rawThumb"
                rawThumb.startsWith("/") -> "$baseUrl$rawThumb"
                else -> rawThumb
            }
        }
        if (title.isBlank()) {
            title = link.attr("title").ifBlank {
                element.selectFirst("h3, h2")?.text()
                    ?: element.parent()?.selectFirst("h3, h2")?.text()
                    ?: ""
            }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.pagination a.next[href], div.pagination a[rel=next], div.pagination li.active + li a[href], div.pagination li:has(.active) + li a[href], div.pagination li.active:not(:nth-last-child(2))"

    override fun popularAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        return super.popularAnimeParse(response)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl$langPath${if (page > 1) "/page/$page" else "/"}", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        return super.latestUpdatesParse(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val path = url.pathSegments.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$path", filters)
        }
        if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            return client.newCall(GET("$baseUrl/$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        }
        return super.getSearchAnime(page, query, filters)
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        val details = animeDetailsParse(response.useAsJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        response.throwIfCloudflareChallenge()
        return super.searchAnimeParse(response)
    }

    // The site paginates search as /page/N?s=, and categories/tags as /category/{slug}/page/N or /tag/{slug}/page/N.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.toUriPart().orEmpty()
        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.toUriPart().orEmpty()
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()

        val url = when {
            query.isNotBlank() -> "$baseUrl$langPath${if (page > 1) "/page/$page/" else "/"}".toHttpUrl().newBuilder()
                .addQueryParameter("s", query.trim())
                .build()
                .toString()

            category.isNotBlank() -> "$baseUrl$langPath/category/$category${if (sort == "popular") "/popular" else ""}${if (page > 1) "/page/$page" else ""}"

            tag.isNotBlank() -> "$baseUrl$langPath/tag/$tag${if (sort == "popular") "/popular" else ""}${if (page > 1) "/page/$page" else ""}"

            sort == "popular" -> "$baseUrl$langPath/popular${if (page > 1) "/page/$page" else ""}"

            else -> "$baseUrl$langPath${if (page > 1) "/page/$page" else "/"}"
        }
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Text search ignores category, tag, and sort filters"),
        CategoryFilter(),
        TagFilter(),
        SortFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
    }

    private class CategoryFilter : UriPartFilter("Category", CATEGORIES)
    private class TagFilter : UriPartFilter("Tag", TAGS)
    private class SortFilter : UriPartFilter("Sort", SORTS)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.throwIfCloudflareChallenge()
        val content = document.selectFirst("div.content > div.post-meta")
            ?: document.selectFirst("div.post-meta")
        title = content?.selectFirst("h2")?.text() ?: ""
        val image = content?.selectFirst("img")
        if (image != null) {
            val rawThumb = image.attr("data-original")
                .ifBlank { image.attr("data-src") }
                .ifBlank { image.attr("src") }
                .ifBlank { image.absUrl("src") }
            thumbnail_url = when {
                rawThumb.startsWith("//") -> "https:$rawThumb"
                rawThumb.startsWith("/") -> "$baseUrl$rawThumb"
                else -> rawThumb
            }
        }

        content?.selectFirst("div.cats")?.run {
            author = select("p:contains(Maker :) > a").textsOrNull()
            artist = select("p:contains(Cast :) > a").textsOrNull()
        }
        genre = (content ?: document).select("div.tags > a").textsOrNull()
        status = SAnime.COMPLETED
    }

    private fun Elements.textsOrNull() = eachText().joinToString().takeUnless(String::isEmpty)

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episode = SEpisode.create().apply {
            name = "JAV"
            episode_number = 1F
            url = anime.url
        }

        return listOf(episode)
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        response.throwIfCloudflareChallenge()
        val pageUrl = response.request.url.toString()
        val doc = response.useAsJsoup()
        doc.throwIfCloudflareChallenge()

        val players = buildList {
            doc.select("div.btnst a[data-link], div.btnst a[href], div.btnst > a, div.btns a[data-link], a.btn-server[data-link], a[href*=supjav.php]").forEach { element ->
                val rawLink = element.attr("data-link").ifBlank { element.attr("href") }.trim()
                if (rawLink.isBlank() || rawLink == "#" || rawLink.startsWith("javascript:", ignoreCase = true) || rawLink.startsWith("#comment", ignoreCase = true)) {
                    return@forEach
                }
                val label = cleanServerLabel(element.text())
                if (label in SUPPORTED_PLAYERS || rawLink.contains("supjav.php", ignoreCase = true)) {
                    add(PlayerRequest(label, rawLink, pageUrl))
                }
            }
        }.distinctBy { "${it.label}|${it.id}" }

        return players.parallelCatchingFlatMapBlocking(::videosFromPlayerBlocking)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }

    private val protectorHeaders by lazy {
        super.headersBuilder().set("Referer", "$PROTECTOR_URL/").build()
    }

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).build()
    }

    private fun videosFromPlayerBlocking(player: PlayerRequest): List<Video> {
        val url = resolveProtectorRedirectBlocking(player.id) ?: return emptyList()
        val hoster = when {
            url.contains("turbovid", true) -> "TV"

            url.contains("fc2stream", true) || url.contains("streamwish", true) ||
                url.contains("fastshow", true) || url.contains("wish", true) -> "FST"

            url.contains("streamtape", true) -> "ST"

            url.contains("voe", true) -> "VOE"

            url.contains("dood", true) || url.contains("ds2play", true) ||
                url.contains("myvidplay", true) || url.contains("playmogo", true) ||
                url.contains("d000d", true) || url.contains("doood", true) -> "DOOD"

            url.contains("mixdrop", true) -> "MIXDROP"

            url.contains("uqload", true) -> "UQLOAD"

            url.contains("vidhide", true) -> "VIDHIDE"

            else -> player.label
        }

        return runCatching {
            when (hoster) {
                "TV" -> extractTvVideosBlocking(url, player.pageReferer)

                "ST" -> streamtapeExtractor.videosFromUrl(url)

                "DOOD" -> doodExtractor.videosFromUrl(url)

                "MIXDROP" -> mixdropExtractor.videosFromUrl(url)

                "UQLOAD" -> runBlocking { uqloadExtractor.videosFromUrl(url) }

                "VOE" -> VoeExtractor(client, embedHeaders(player.pageReferer)).videosFromUrl(url)

                "VAS" -> extractVasBlocking(url)

                "FST" -> {
                    extractPackedHlsBlocking(url, player.pageReferer, hoster).ifEmpty {
                        if (url.contains("fc2stream", ignoreCase = true)) {
                            emptyList()
                        } else {
                            runBlocking {
                                StreamWishExtractor(client, embedHeaders(player.pageReferer)).videosFromUrl(url)
                            }
                        }
                    }
                }

                "VIDHIDE", "EVS", "LUC" -> {
                    extractPackedHlsBlocking(url, player.pageReferer, hoster).ifEmpty {
                        runBlocking {
                            VidHideExtractor(client, embedHeaders(player.pageReferer)).videosFromUrl(url) { "$hoster - $it" }
                        }
                    }
                }

                else -> extractPackedHlsBlocking(url, player.pageReferer, hoster)
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveProtectorRedirectBlocking(id: String): String? {
        if (id.isBlank()) return null
        if (id.startsWith("//")) return "https:$id"
        if (id.startsWith("http://") || id.startsWith("https://")) {
            if (!id.contains("supjav", true)) return id
            return fetchRedirectBlocking(id)
        }
        return fetchRedirectBlocking(id.reversed()) ?: fetchRedirectBlocking(id)
    }

    private fun fetchRedirectBlocking(code: String): String? = runCatching {
        val targetUrl = if (
            code.startsWith("http://") ||
            code.startsWith("https://") ||
            code.contains("supjav.php")
        ) {
            code
        } else {
            "$PROTECTOR_URL/supjav.php?c=$code"
        }
        noRedirectClient.newCall(GET(targetUrl, protectorHeaders)).execute().use { response ->
            response.throwIfCloudflareChallenge()
            response.header("Location")?.trim()?.takeIf(String::isNotBlank)
                ?.let { response.request.url.resolve(it)?.toString() }
        }
    }.getOrNull()

    /**
     * turbovidhls sometimes returns HTTP 500 while still serving a valid
     * player body, so inspect the body regardless of HTTP status.
     */
    private fun extractTvVideosBlocking(url: String, pageReferer: String): List<Video> {
        val body = client.newCall(GET(url, embedHeaders(pageReferer))).execute().use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }.replace("\\/", "/")
        val document = Jsoup.parse(body, url)
        val rawPlaylist = TV_URL_PLAY_REGEX.find(body)?.groupValues?.get(1)
            ?: TV_DATA_HASH_REGEX.find(body)?.groupValues?.get(1)
            ?: document.selectFirst("source[src*=m3u8], video[src*=m3u8]")?.attr("src")
            ?: TV_FILE_URL_REGEX.find(body)?.groupValues?.get(1)
            ?: FST_M3U8_REGEX.find(body)?.value
            ?: return emptyList()
        val playlist = normalizePlayerUrl(rawPlaylist, url) ?: return emptyList()
        if (!isReachableHlsPlaylist(playlist, url) || isBrowserWrappedTvPlaylist(playlist, url)) return emptyList()
        val playerHeaders = playbackHeaders(url)
        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = playlist,
                referer = url,
                masterHeaders = playerHeaders,
                videoHeaders = playerHeaders,
                videoNameGen = { if (it == "Video") "TV - HLS" else "TV - $it" },
            ).distinctBy { it.videoUrl }
        }.getOrDefault(emptyList()).ifEmpty {
            listOf(Video(playlist, "TV - HLS", playlist, headers = playerHeaders))
        }
    }

    private fun isBrowserWrappedTvPlaylist(
        playlist: String,
        referer: String,
        depth: Int = 0,
    ): Boolean = runCatching {
        client.newCall(GET(playlist, playbackHeaders(referer))).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val mediaUrls = response.body.string()
                .lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList()
            mediaUrls.any { mediaUrl ->
                mediaUrl.contains("tiktokcdn.com", ignoreCase = true) ||
                    mediaUrl.substringBefore('?').endsWith(".image", ignoreCase = true)
            } ||
                (
                    depth < 2 &&
                        mediaUrls
                            .filter { it.substringBefore('?').endsWith(".m3u8", ignoreCase = true) }
                            .any { child -> isBrowserWrappedTvPlaylist(child, playlist, depth + 1) }
                    )
        }
    }.getOrDefault(false)

    private fun extractPackedHlsBlocking(
        url: String,
        pageReferer: String,
        label: String,
    ): List<Video> {
        val response = client.newCall(GET(url, embedHeaders(pageReferer))).execute()
        val body = response.use {
            it.throwIfCloudflareChallenge()
            it.body.string()
        }
        val scripts = Jsoup.parse(body, url).select("script").joinToString("\n") { script ->
            val data = script.data()
            if ("eval(function(p,a,c" in data) {
                runCatching { JsUnpacker.unpackAndCombine(data) }.getOrNull() ?: data
            } else {
                data
            }
        }.replace("\\/", "/").replace("\\u0026", "&")
        val playlist = FST_M3U8_REGEX.find(scripts)?.value
            ?.let { normalizePlayerUrl(it, url) }
            ?: return emptyList()
        if (!isReachableHlsPlaylist(playlist, url)) return emptyList()
        val playerHeaders = playbackHeaders(url)
        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = playlist,
                referer = url,
                masterHeaders = playerHeaders,
                videoHeaders = playerHeaders,
                videoNameGen = { if (it == "Video") "$label - HLS" else "$label - $it" },
            ).distinctBy { it.videoUrl }
        }.getOrDefault(emptyList()).ifEmpty {
            listOf(Video(playlist, "$label - HLS", playlist, headers = playerHeaders))
        }
    }

    /** VAS (vvaassjjav.xyz/e/{filecode}) serves its HLS master from a JSON API instead of the page. */
    private fun extractVasBlocking(url: String): List<Video> {
        val embedUrl = url.toHttpUrlOrNull() ?: return emptyList()
        val filecode = embedUrl.pathSegments.lastOrNull(String::isNotBlank) ?: return emptyList()
        val origin = "${embedUrl.scheme}://${embedUrl.host}"
        val apiHeaders = headers.newBuilder()
            .set("Referer", url.substringBefore("#"))
            .set("Origin", origin)
            .build()
        val body = """{"filecode":"$filecode"}""".toRequestBody("application/json".toMediaType())
        val response = client.newCall(POST("$origin/api/stream", apiHeaders, body)).execute().use {
            if (!it.isSuccessful) return emptyList()
            it.body.string()
        }
        val playlist = VAS_STREAM_REGEX.find(response)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return emptyList()
        if (!isReachableHlsPlaylist(playlist, "$origin/")) return emptyList()
        val playerHeaders = playbackHeaders("$origin/")
        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = playlist,
                referer = "$origin/",
                masterHeaders = playerHeaders,
                videoHeaders = playerHeaders,
                videoNameGen = { if (it == "Video") "VAS - HLS" else "VAS - $it" },
            ).distinctBy { it.videoUrl }
        }.getOrDefault(emptyList()).ifEmpty {
            listOf(Video(playlist, "VAS - HLS", playlist, headers = playerHeaders))
        }
    }

    private fun isReachableHlsPlaylist(playlist: String, referer: String): Boolean = runCatching {
        client.newCall(GET(playlist, playbackHeaders(referer))).execute().use { response ->
            response.isSuccessful && response.body.string().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun cleanServerLabel(text: String): String {
        val clean = text.trim().uppercase()
        return when {
            clean.contains("TV") -> "TV"
            clean.contains("VOE") -> "VOE"
            clean.contains("FST") || clean.contains("WISH") || clean.contains("STREAMWISH") || clean.contains("FASTSTREAM") || clean.contains("FASTSHOW") -> "FST"
            clean.contains("ST") || clean.contains("STREAMTAPE") -> "ST"
            clean.contains("DOOD") -> "DOOD"
            clean.contains("MIXDROP") -> "MIXDROP"
            clean.contains("UQLOAD") -> "UQLOAD"
            clean.contains("VIDHIDE") -> "VIDHIDE"
            else -> clean
        }
    }

    private fun playbackHeaders(referer: String): okhttp3.Headers = headers.newBuilder()
        .set("Referer", referer)
        .removeAll("Origin")
        .build()

    private fun embedHeaders(pageReferer: String): okhttp3.Headers = headers.newBuilder()
        .set("Referer", pageReferer)
        .removeAll("Origin")
        .build()

    private fun normalizePlayerUrl(rawUrl: String, pageUrl: String): String? {
        val clean = rawUrl.trim().trim('"', '\'')
        if (clean.isBlank()) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            else -> pageUrl.toHttpUrlOrNull()?.resolve(clean)?.toString()
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
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
        val lower = lowercase()
        return "_cf_chl_opt" in lower ||
            "id=\"challenge-form\"" in lower ||
            "id='challenge-form'" in lower ||
            TITLE_CHALLENGE_REGEX.containsMatchIn(this)
    }

    private class CloudflareBlockedException : IOException("Cloudflare blocked SupJav. Open the page in WebView once, complete the challenge, then retry.")

    private data class PlayerRequest(
        val label: String,
        val id: String,
        val pageReferer: String,
    )

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PROTECTOR_URL = "https://lk1.supremejav.com"
        private const val CHALLENGE_PEEK_BYTES = 512L * 1024L

        private val CLOUDFLARE_ERROR_CODES = setOf(403, 429, 503)
        private val TITLE_CHALLENGE_REGEX =
            Regex("""<title>\s*(?:just a moment|attention required)[^<]*</title>""", RegexOption.IGNORE_CASE)
        private val TV_URL_PLAY_REGEX =
            Regex("""\b(?:(?:var|let|const)\s+)?urlPlay\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TV_DATA_HASH_REGEX =
            Regex("""\bdata-hash\s*=\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val TV_FILE_URL_REGEX =
            Regex("""\bfile\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        private val FST_M3U8_REGEX =
            Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)

        private val SUPPORTED_PLAYERS = setOf("TV", "FST", "VOE", "ST", "DOOD", "MIXDROP", "UQLOAD", "VIDHIDE", "EVS", "VAS", "LUC")

        private val VAS_STREAM_REGEX = Regex(""""streaming_url"\s*:\s*"([^"]+)"""")

        private val CATEGORIES = arrayOf(
            Pair("All", ""),
            Pair("Censored", "censored-jav"),
            Pair("Uncensored", "uncensored-jav"),
            Pair("Amateur", "amateur"),
            Pair("Reducing Mosaic", "reducing-mosaic"),
            Pair("English Subtitles", "english-subtitles"),
            Pair("Chinese Subtitles", "chinese-subtitles"),
        )

        private val TAGS = arrayOf(
            Pair("All", ""),
            Pair("4K", "4k"),
            Pair("Amateur", "amateur"),
            Pair("Anal", "anal"),
            Pair("Big Tits", "big-tits"),
            Pair("Blowjob", "blowjob"),
            Pair("Bondage", "bondage"),
            Pair("Bukkake", "bukkake"),
            Pair("Cosplay", "cosplay"),
            Pair("Creampie", "creampie"),
            Pair("Cumshot", "cumshot"),
            Pair("Documentary", "documentary"),
            Pair("Facials", "facials"),
            Pair("FC2PPV", "fc2ppv"),
            Pair("Handjob", "handjob"),
            Pair("Lesbian", "lesbian"),
            Pair("Married Woman", "married-woman"),
            Pair("Massage", "massage"),
            Pair("Masturbation", "masturbation"),
            Pair("Mature Woman", "mature-woman"),
            Pair("Nurse", "nurse"),
            Pair("Pantyhose", "pantyhose"),
            Pair("POV", "pov"),
            Pair("School Girls", "school-girls"),
            Pair("Slender", "slender"),
            Pair("Slut", "slut"),
        )

        private val SORTS = arrayOf(
            Pair("Latest", ""),
            Pair("Popular", "popular"),
        )

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
