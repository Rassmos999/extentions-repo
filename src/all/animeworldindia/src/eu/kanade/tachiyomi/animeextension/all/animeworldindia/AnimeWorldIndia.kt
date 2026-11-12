package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeWorldIndia(
    override val lang: String,
    private val language: String,
) : ParsedAnimeHttpSource() {

    override val name = "AnimeWorld India"

    override val baseUrl = "https://watchanimeworld.one"

    override val supportsLatest = true

    private val zephyrixExtractor by lazy { ZephyrixExtractor(client, headers, baseUrl) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val path = if (language.isNotBlank()) "/category/language/$language" else "/series"
        return GET("$baseUrl$path/page/$page/", headers)
    }

    override fun popularAnimeSelector() = "article.post"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val link = element.selectFirst("a.lnk-blk") ?: element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        title = element.selectFirst("header h2.entry-title, h2.entry-title, .title")?.text().orEmpty()
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
        }
    }

    override fun popularAnimeNextPageSelector() = "div.nav-links a.next, div.nav-links a:contains(NEXT)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val path = if (language.isNotBlank()) "/category/language/$language" else "/movies"
        return GET("$baseUrl$path/page/$page/", headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query.trim())
                .build()
            return GET(url, headers)
        }
        val path = AnimeWorldIndiaFilters.resolveFilterPath(filters, language)
        return GET("$baseUrl$path/page/$page/", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeWorldIndiaFilters.filters

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("article.post.single h1.entry-title, h1.entry-title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("article.post.single img[loading], article.post.single > div img, .post-thumbnail img")
            ?.attr("abs:src")
        description = document.select("article.post.single .description p")
            .joinToString("\n\n") { it.text() }
            .ifBlank { document.selectFirst("article.post.single .description")?.text() }
        genre = document.select("article.post.single p.genres a, ul.cast-lst a[href*='/genre/']")
            .joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val pageUrl = response.request.url.toString()

        if (pageUrl.contains("/movies/") || document.selectFirst("#options-0 iframe") != null) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(pageUrl)
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }

        val seasonAnchors = document.select("ul.aa-cnt li a[data-post][data-season]")
        if (seasonAnchors.size <= 1) {
            return document.select(episodeListSelector())
                .map(::episodeFromElement)
                .reversed()
        }

        val allEpisodes = mutableListOf<SEpisode>()
        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", pageUrl)
            .build()

        seasonAnchors.forEach { anchor ->
            val postId = anchor.attr("data-post")
            val seasonNum = anchor.attr("data-season")
            val formBody = FormBody.Builder()
                .add("action", "action_select_season")
                .add("season", seasonNum)
                .add("post", postId)
                .build()
            runCatching {
                val seasonDoc = client.newCall(
                    POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, formBody),
                ).execute().useAsJsoup()
                seasonDoc.select("article.post").forEach { el ->
                    allEpisodes.add(episodeFromElement(el))
                }
            }
        }

        if (allEpisodes.isEmpty()) {
            allEpisodes.addAll(document.select(episodeListSelector()).map(::episodeFromElement))
        }

        return allEpisodes.distinctBy { it.url }.reversed()
    }

    override fun episodeListSelector() = "ul#episode_by_temp article.post, article.episodes"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val link = element.selectFirst("a.lnk-blk") ?: element.selectFirst("a[href]")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        val code = element.selectFirst("span.num-epi")?.text()?.trim().orEmpty()
        val epTitle = element.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
        name = when {
            code.isNotEmpty() && epTitle.isNotEmpty() -> "$code - $epTitle"
            epTitle.isNotEmpty() -> epTitle
            code.isNotEmpty() -> "Episode $code"
            else -> "Episode"
        }
        episode_number = parseEpisodeNumber(code, link.attr("href"))
    }

    private fun parseEpisodeNumber(code: String, href: String): Float {
        if (code.contains("x", ignoreCase = true)) {
            val season = code.substringBefore("x", "").toIntOrNull() ?: 0
            val ep = code.substringAfter("x", "").toFloatOrNull() ?: 0f
            return if (season > 1) season * 1000f + ep else ep
        }
        return EP_NUM_REGEX.find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val iframes = document.select("div[id^=options-] iframe, #options-0 iframe")
        val videos = mutableListOf<Video>()

        iframes.forEachIndexed { idx, iframe ->
            val src = iframe.attr("abs:src").ifBlank { iframe.attr("abs:data-src") }
            if (src.contains("zephyrix", ignoreCase = true)) {
                videos.addAll(zephyrixExtractor.videosFromUrl(src, "Server ${idx + 1}"))
            }
        }

        return videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    companion object {
        private val EP_NUM_REGEX = Regex("""(\d+(?:\.\d+)?)/?$""")
    }
}
