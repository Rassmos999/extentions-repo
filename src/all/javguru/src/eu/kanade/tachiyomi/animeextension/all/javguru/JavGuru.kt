package eu.kanade.tachiyomi.animeextension.all.javguru

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.javcoverfetcher.JavCoverFetcher
import aniyomi.lib.javcoverfetcher.JavCoverFetcher.fetchHDCovers
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.all.javguru.extractors.EmTurboExtractor
import eu.kanade.tachiyomi.animeextension.all.javguru.extractors.MaxStreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.min

class JavGuru :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Jav Guru"

    override val baseUrl = "https://jav.guru"

    override val lang = "all"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    private val preferences by getPreferencesLazy()

    @Volatile
    private lateinit var popularElements: Elements

    // ========================= Popular =========================

    override suspend fun getPopularAnime(page: Int): AnimesPage = if (page == 1) {
        client.newCall(popularAnimeRequest(page))
            .awaitSuccess()
            .use(::popularAnimeParse)
    } else {
        cachedPopularAnimeParse(page)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/most-watched-rank/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        popularElements = response.useAsJsoup().select(".rank-item")

        return cachedPopularAnimeParse(1)
    }

    private fun cachedPopularAnimeParse(page: Int): AnimesPage {
        val end = min(page * 20, popularElements.size)
        val entries = popularElements.subList((page - 1) * 20, end).map { element ->
            SAnime.create().apply {
                element.select(".rank-title a").let { a ->
                    getIDFromUrl(a)?.let { url = it }
                        ?: setUrlWithoutDomain(a.attr("href"))

                    val rawTitle = element.selectFirst("a.rank-thumb-link")?.attr("title")
                        ?.takeIf { it.isNotBlank() }
                        ?: a.text()
                    val duration = element.selectFirst("span.duration, span.time, div.duration, time, .time, .duration, span.runtime")
                        ?.text()?.trim()
                        ?.takeIf { d -> d.contains(":") }
                    title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
                }
                thumbnail_url = element.getImageUrl()
            }
        }
        return AnimesPage(entries, end < popularElements.size)
    }

    // ========================= Latest =========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl + if (page > 1) "/page/$page/" else ""

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()

        val entries = document.select("div.site-content div.inside-article:not(:contains(nothing))").map { element ->
            SAnime.create().apply {
                element.select("a").let { a ->
                    getIDFromUrl(a)?.let { url = it }
                        ?: setUrlWithoutDomain(a.attr("href"))
                }
                thumbnail_url = element.getImageUrl()
                val rawTitle = element.select("h2 > a").text()
                val duration = element.selectFirst("span.duration, span.time, div.duration, time, .time, .duration, span.runtime, .inside-article span")
                    ?.text()?.trim()
                    ?.takeIf { d -> d.contains(":") }
                title = if (!duration.isNullOrBlank()) "[$duration] $rawTitle" else rawTitle
            }
        }

        val page = document.location()
            .pageNumberFromUrlOrNull() ?: 1

        val lastPage = document.select("div.wp-pagenavi a")
            .last()
            ?.attr("href")
            .pageNumberFromUrlOrNull() ?: 1

        val hasNextPage = entries.isNotEmpty() && (
            page < lastPage || document.select("div.wp-pagenavi a.next, div.wp-pagenavi a.nextpostslink, a[rel=next], .pagination a.next").isNotEmpty()
            )

        return AnimesPage(entries, hasNextPage)
    }

    // ========================= Search =========================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val queryUrl = query.toHttpUrlOrNull()
        if (queryUrl?.host == baseUrl.toHttpUrl().host) {
            val cleanSegments = queryUrl.pathSegments.filter { it.isNotEmpty() }
            if (cleanSegments.size == 1) {
                val idOrSlug = cleanSegments[0]
                val url = "/$idOrSlug/"
                val tempAnime = SAnime.create().apply { this.url = url }
                return getAnimeDetails(tempAnime).let {
                    val anime = it.apply { this.url = url }
                    AnimesPage(listOf(anime), false)
                }
            }
        }

        if (query.startsWith(PREFIX_ID)) {
            val id = query.substringAfter(PREFIX_ID)
            if (id.toIntOrNull() == null) {
                return AnimesPage(emptyList(), false)
            }
            val url = "/$id/"
            val tempAnime = SAnime.create().apply { this.url = url }
            return getAnimeDetails(tempAnime).let {
                val anime = it.apply { this.url = url }
                AnimesPage(listOf(anime), false)
            }
        }

        if (query.isNotEmpty()) {
            return client.newCall(searchAnimeRequest(page, query, filters))
                .awaitSuccess()
                .use(::searchAnimeParse)
        } else {
            val selectedTags = filters.filterIsInstance<TagGroup>().firstOrNull()?.state?.filter { it.state } ?: emptyList()
            if (selectedTags.isNotEmpty()) {
                val combinedSlug = selectedTags.joinToString("+") { it.urlPart.trim('/').substringAfterLast('/') }
                val url = "$baseUrl/tag/$combinedSlug/" + if (page > 1) "page/$page/" else ""
                val request = GET(url, headers)
                return client.newCall(request)
                    .awaitSuccess()
                    .use(::searchAnimeParse)
            }

            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> {
                        if (filter.state != 0) {
                            val url = "$baseUrl${filter.toUrlPart()}" + if (page > 1) "page/$page/" else ""
                            val request = GET(url, headers)
                            return client.newCall(request)
                                .awaitSuccess()
                                .use(::searchAnimeParse)
                        }
                    }

                    is ActressFilter,
                    is ActorFilter,
                    is StudioFilter,
                    is MakerFilter,
                    -> {
                        if (filter.state.isNotEmpty()) {
                            val url = "$baseUrl${filter.toUrlPart()}" + if (page > 1) "page/$page/" else ""
                            val request = GET(url, headers)
                            return client.newCall(request)
                                .awaitIgnoreCode(404)
                                .use(::searchAnimeParse)
                        }
                    }

                    else -> { }
                }
            }
        }

        throw Exception("Select at least one Filter")
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            if (query.isNotEmpty()) {
                addQueryParameter("s", query.trim())
            }
            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> {
                        if (filter.state != 0) {
                            val catSlug = filter.toUrlPart().trim('/').substringAfterLast('/')
                            if (catSlug.isNotEmpty()) addQueryParameter("category_name", catSlug)
                        }
                    }

                    else -> {}
                }
            }
        }.build().toString()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response) = latestUpdatesParse(response)

    // ========================= Details =========================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.useAsJsoup()

        val javId = document.selectFirst(".infoleft li:contains(code)")?.ownText()
        val siteCover = document.select(".large-screenshot img").attr("abs:src")

        return SAnime.create().apply {
            title = document.select(".titl").text()
            genre = document.select(".infoleft a[rel*=tag]").joinToString { it.text() }
            author = document.selectFirst(".infoleft li:contains(studio) a")?.text()
            artist = document.selectFirst(".infoleft li:contains(label) a")?.text()
            status = SAnime.COMPLETED
            description = buildString {
                document.selectFirst(".infoleft li:contains(code)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(director)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(studio)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(label)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(actor)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(actress)")?.text()?.let { append("$it\n") }
                document.selectFirst(".infoleft li:contains(release date)")?.text()?.let { append("$it\n") }
            }
            thumbnail_url = if (preferences.fetchHDCovers) {
                javId?.let { JavCoverFetcher.getCoverById(it) } ?: siteCover
            } else {
                siteCover
            }
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.useAsJsoup()
        return document.select("div.woo-sc-related-posts li").map { element ->
            SAnime.create().apply {
                element.select("a.thumbnail").let { a ->
                    getIDFromUrl(a)?.let { url = it }
                        ?: setUrlWithoutDomain(a.attr("href"))
                }
                title = element.select("img").attr("alt").ifBlank {
                    element.select("a.related-title").attr("title")
                }
                thumbnail_url = element.getImageUrl()
            }
        }
    }

    private fun Element.getImageUrl(): String? {
        val img = if (tagName() == "img") this else selectFirst("img")
        return img?.let {
            it.attr("abs:src").ifEmpty {
                it.attr("data-src").ifEmpty {
                    it.attr("data-original").ifEmpty {
                        it.attr("poster").ifEmpty {
                            it.attr("src")
                        }
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

    // ========================= Episodes =========================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val dateText = document.selectFirst("span.thedate")?.text()?.substringAfter("Posted:")?.trim()
        val dateUpload = DATE_FORMATTER.tryParse(dateText)

        return listOf(
            SEpisode.create().apply {
                url = response.request.url.encodedPath
                name = "Episode"
                date_upload = dateUpload
            },
        )
    }

    // ========================= Videos =========================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()

        val iframeData = document.selectFirst("script:containsData(iframe_url)")?.html()
            ?: return emptyList()

        val iframeUrls = IFRAME_B64_REGEX.findAll(iframeData)
            .map { it.groupValues[1] }
            .map { String(Base64.decode(it, Base64.DEFAULT)) }
            .toList()

        return iframeUrls
            .parallelMapNotNullBlocking(::resolveHosterUrl)
            .parallelCatchingFlatMapBlocking(::getVideos)
    }

    private suspend fun resolveHosterUrl(iframeUrl: String): String? = runCatching {
        val token = iframeUrl.toHttpUrlOrNull()?.queryParameter("xd")
        val finalUrl = if (token != null) {
            val base = iframeUrl.substringBefore("?")
            "$base?xr=${token.reversed()}"
        } else {
            val iframeDocument = client.newCall(GET(iframeUrl, headers))
                .awaitSuccess().useAsJsoup()
            val script = iframeDocument.selectFirst("script:containsData(cfg)")?.html() ?: return null
            val cid = CID_REGEX.find(script)?.groupValues?.get(1) ?: return null
            val rawBase = BASE_REGEX.find(script)?.groupValues?.get(1) ?: return null
            val base = iframeUrl.toHttpUrlOrNull()?.resolve(rawBase)?.toString() ?: rawBase
            val rtype = RTYPE_REGEX.find(script)?.groupValues?.get(1) ?: "x"
            val keys = KEYS_REGEX.find(script)?.groupValues?.get(1)
                ?.split(",")
                ?.map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
                ?: return null

            val element = iframeDocument.getElementById(cid) ?: return null
            val tokenBuilder = StringBuilder()
            for (key in keys) {
                tokenBuilder.append(element.attr(key))
            }
            val fullToken = tokenBuilder.toString()
            if (fullToken.isBlank()) return null
            "$base?$rtype" + "r=${fullToken.reversed()}"
        }

        val newHeaders = headersBuilder()
            .set("Referer", iframeUrl)
            .build()

        val redirectUrl = noRedirectClient.newCall(GET(finalUrl, newHeaders))
            .await().use { it.header("location") }
            ?: return null

        if (redirectUrl.toHttpUrlOrNull() == null) {
            return null
        }

        return redirectUrl
    }.getOrNull()

    private val streamWishExtractor by lazy {
        val swHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        StreamWishExtractor(client, swHeaders)
    }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val maxStreamExtractor by lazy { MaxStreamExtractor(client, headers) }
    private val emTurboExtractor by lazy { EmTurboExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private suspend fun getVideos(hosterUrl: String): List<Video> = when {
        listOf("javplaya", "javclan").any { it in hosterUrl } -> {
            val direct = extractPackedM3u8(hosterUrl, prefix = "StreamWish")
            if (direct.isNotEmpty()) {
                direct
            } else {
                streamWishExtractor.videosFromUrl(hosterUrl).map { video ->
                    val newHeaders = (video.headers ?: headers).newBuilder()
                        .set("Referer", "$baseUrl/")
                        .set("Origin", baseUrl)
                        .build()
                    Video(
                        url = video.url,
                        quality = video.quality,
                        videoUrl = video.videoUrl,
                        headers = newHeaders,
                        subtitleTracks = video.subtitleTracks,
                        audioTracks = video.audioTracks,
                    )
                }
            }
        }

        hosterUrl.contains("streamtape") -> {
            streamTapeExtractor.videoFromUrl(hosterUrl).let(::listOfNotNull)
        }

        // vide0.net / playmogo are DoodStream mirrors
        listOf("dood", "ds2play", "vide0", "playmogo").any { it in hosterUrl } -> {
            doodExtractor.videosFromUrl(hosterUrl)
        }

        listOf("mixdrop", "mixdroop").any { it in hosterUrl } -> {
            mixDropExtractor.videoFromUrl(hosterUrl)
        }

        hosterUrl.contains("maxstream") -> {
            maxStreamExtractor.videoFromUrl(hosterUrl)
        }

        hosterUrl.contains("emturbovid") -> {
            emTurboExtractor.getVideos(hosterUrl)
        }

        listOf("vidara", "mxzhxco8dft").any { it in hosterUrl } -> {
            extractVidaraVideos(hosterUrl)
        }

        // Rotating VOE mirror domains (e.g. susanexpectcare.com, javlesbians.com)
        listOf("voe", "javlesbians", "susanexpectcare").any { it in hosterUrl } -> {
            voeExtractor.videosFromUrl(hosterUrl)
        }

        // LuluStream mirror (packed JWPlayer, same layout as StreamWish)
        listOf("streamhihi", "lulu").any { it in hosterUrl } -> {
            extractPackedM3u8(hosterUrl, prefix = "LuluStream")
                .ifEmpty { streamWishExtractor.videosFromUrl(hosterUrl, videoNameGen = { "LuluStream - $it" }) }
        }

        else -> {
            extractUnknownMirror(hosterUrl)
        }
    }

    private suspend fun extractUnknownMirror(hosterUrl: String): List<Video> {
        val body = runCatching {
            client.newCall(GET(hosterUrl, headersBuilder().set("Referer", "$baseUrl/").build()))
                .awaitSuccess()
                .use { it.body.string() }
        }.getOrNull() ?: return emptyList()

        return when {
            body.contains("MKGMa") || body.contains("""type="application/json"""") || body.contains("""type='application/json'""") ->
                voeExtractor.videosFromUrl(hosterUrl)

            body.contains("'/pass_md5/'") ->
                doodExtractor.videosFromUrl(hosterUrl)

            body.contains("/api/stream") || body.contains("mxzhxco8dft") ->
                extractVidaraVideos(hosterUrl)

            else -> extractPackedM3u8FromBody(hosterUrl, body)
        }
    }

    private fun extractVidaraVideos(embedUrl: String): List<Video> {
        val parsed = embedUrl.toHttpUrlOrNull() ?: return emptyList()
        val filecode = parsed.pathSegments.lastOrNull { it.isNotBlank() } ?: return emptyList()
        val embedOrigin = "${parsed.scheme}://${parsed.host}"
        val payload = JSONObject().apply {
            put("filecode", filecode)
            put("device", "desktop")
            put("codecs", JSONArray(listOf("avc1", "hev1", "vp9")))
        }.toString()

        for (origin in listOf(embedOrigin, "https://vidara.to", "https://mxzhxco8dft.com").distinct()) {
            val streamUrl = runCatching {
                val apiHeaders = headersBuilder()
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

    private suspend fun extractPackedM3u8(embedUrl: String, prefix: String = "HLS"): List<Video> {
        val body = runCatching {
            client.newCall(GET(embedUrl, headersBuilder().set("Referer", "$baseUrl/").build()))
                .awaitSuccess()
                .use { it.body.string() }
        }.getOrNull() ?: return emptyList()
        return extractPackedM3u8FromBody(embedUrl, body, prefix)
    }

    private fun extractPackedM3u8FromBody(embedUrl: String, rawBody: String, prefix: String = "HLS"): List<Video> {
        val unpacked = unpackPackedJs(rawBody).ifBlank { rawBody }.replace("\\/", "/")
        val m3u8Url = M3U8_URL_REGEX.find(unpacked)?.value ?: return emptyList()
        val origin = embedUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        val videoHeaders = headersBuilder().apply {
            if (origin != null) {
                set("Referer", "$origin/")
                set("Origin", origin)
            }
        }.build()
        return listOf(Video(m3u8Url, prefix, m3u8Url, videoHeaders))
    }

    private fun unpackPackedJs(html: String): String {
        val out = StringBuilder()
        for (match in PACKED_JS_REGEX.findAll(html)) {
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: continue
            val dict = match.groupValues[4].split("|")
            val decoded = WORD_REGEX.replace(payload) { m ->
                val idx = parseBaseN(m.value, radix)
                if (idx != null && idx in dict.indices && dict[idx].isNotEmpty()) dict[idx] else m.value
            }
            out.append(decoded).append('\n')
        }
        return out.toString()
    }

    private fun parseBaseN(token: String, radix: Int): Int? {
        if (radix !in 2..62) return null
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var acc = 0
        for (ch in token) {
            val v = alphabet.indexOf(ch)
            if (v < 0 || v >= radix) return null
            acc = acc * radix + v
        }
        return acc
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy<Video> {
                val isJavClan = listOf("javplaya", "javclan", "streamwish", "wishembed").any { host ->
                    it.videoUrl?.contains(host) == true || it.url.contains(host)
                }
                if (isJavClan) 1 else 0
            }.thenByDescending {
                it.quality.contains(quality)
            },
        )
    }

    // ========================= Utilities =========================

    private fun getIDFromUrl(element: Elements): String? = element.attr("abs:href")
        .toHttpUrlOrNull()
        ?.pathSegments
        ?.firstOrNull()
        ?.toIntOrNull()
        ?.toString()
        ?.let { "/$it/" }

    private fun String?.pageNumberFromUrlOrNull() = this
        ?.let { PAGINATION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private suspend fun Call.awaitIgnoreCode(code: Int): Response = await().also { response ->
        if (!response.isSuccessful && response.code != code) {
            response.close()
            throw Exception("HTTP error ${response.code}")
        }
    }

    override fun getFilterList() = getFilters()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        JavCoverFetcher.addPreferenceToScreen(screen)
    }

    companion object {
        const val PREFIX_ID = "id:"

        private val IFRAME_B64_REGEX = Regex(""""iframe_url":"([^"]+)"""")
        private val CID_REGEX = Regex("""cid:\s*['"]([^'"]+)['"]""")
        private val BASE_REGEX = Regex("""base:\s*['"]([^'"]+)['"]""")
        private val RTYPE_REGEX = Regex("""rtype:\s*['"]([^'"]+)['"]""")
        private val KEYS_REGEX = Regex("""keys:\s*\[([^]]+)]""")
        private val PAGINATION_REGEX = Regex("""/page/(\d+)""")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val M3U8_URL_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
        private val PACKED_JS_REGEX = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
        private val WORD_REGEX = Regex("""\b\w+\b""")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale.US)
        }

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_VALUES.first()
    }
}
