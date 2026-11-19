package eu.kanade.tachiyomi.animeextension.all.pornhub

import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Clean networking layer for PornHub.
 *
 * Key architectural guarantees:
 * 1. Never reads or re-wraps HTML response bodies inside OkHttp interceptors, so the host app's
 *    BrotliInterceptor / Gzip decompression always completes cleanly before HTML is touched.
 * 2. Isolates PornHub requests from poisoned Android WebView cookies (`htjf-mobile`, `htjschalref`,
 *    `KEY`, `rp`, `overwriteCCVal`, etc.) while keeping a clean persistent desktop session and
 *    still reading Cloudflare clearance cookies (`cf_clearance`, `__cf_bm`) if needed.
 * 3. Caches decompressed HTML + `Set-Cookie` maps post-decompression, so opening Details and then
 *    Play reuses the exact page & session cookies without redundant requests.
 * 4. Transparently refreshes expired `phncdn.com` thumbnail URLs from the current session map.
 */
internal class PornHubClient(
    private val hostClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
) {
    data class PageResult(
        val finalUrl: String,
        val html: String,
        val responseCookies: Map<String, String>,
        val statusCode: Int,
    )

    private val cookieJar = PornHubCookieJar(hostClient.cookieJar)
    private val pageCache = ConcurrentHashMap<String, Pair<Long, PageResult>>()
    private val freshThumbnails = ConcurrentHashMap<String, String>()
    private val fallbackThumbnails = ConcurrentHashMap<String, String>()

    val client: OkHttpClient = hostClient.newBuilder()
        .cookieJar(cookieJar)
        .rateLimitHost(baseUrlProvider().toHttpUrl(), 1, 1.seconds)
        .addInterceptor(::phncdnCoverInterceptor)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Shares connection pool, dispatcher, Brotli decompression, and rate-limiter with [client],
     * but lets callers pass an explicit `Cookie` header (needed for `/video/get_media?s=...`
     * which validates against the exact session cookies emitted by its `view_video.php` response).
     */
    val rawCookieClient: OkHttpClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    fun rememberThumbnail(url: String?, fallbackUrl: String? = null) {
        if (url.isNullOrBlank()) return
        extractThumbnailKey(url)?.let { key -> freshThumbnails[key] = url }
        if (!fallbackUrl.isNullOrBlank() && fallbackUrl != url) {
            if (fallbackThumbnails.size > 400) fallbackThumbnails.clear()
            fallbackThumbnails[url] = fallbackUrl
        }
    }

    fun buildCookieHeader(overrideCookies: Map<String, String> = emptyMap()): String {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(cookieJar.snapshotSessionCookies())
        overrideCookies.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower !in BLOCKED_COOKIES && !v.equals("deleted", ignoreCase = true) && v.isNotBlank()) {
                merged[k] = v
            }
        }
        REQUIRED_COOKIES.forEach { (k, v) -> merged[k] = v }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getCachedPage(url: String, ttlMs: Long = VIDEO_CACHE_TTL_MS): PageResult? {
        val (ts, cached) = pageCache[url] ?: return null
        return if (System.currentTimeMillis() - ts < ttlMs) cached else null
    }

    fun fetchPage(
        request: Request,
        cacheTtlMs: Long = BROWSE_CACHE_TTL_MS,
        bypassCache: Boolean = false,
        allow404Empty: Boolean = false,
    ): PageResult {
        val urlKey = request.url.toString()
        if (!bypassCache && cacheTtlMs > 0L && request.method == "GET") {
            getCachedPage(urlKey, cacheTtlMs)?.let { return it }
            pageCache.remove(urlKey)
        }

        var lastCode = 0
        var lastChallenged = false

        for (attempt in 0..MAX_RETRIES) {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val finalUrl = response.request.url.toString()
                val responseCookies = parseSetCookies(response.headers("Set-Cookie"))
                val html = response.body.string()

                if (code == 404 && allow404Empty) {
                    return PageResult(finalUrl, "", responseCookies, 404)
                }

                val challenged = isChallengeHtml(html)
                if (code in REFUSED_CODES || challenged) {
                    lastCode = code
                    lastChallenged = challenged
                    if (attempt < MAX_RETRIES) {
                        cookieJar.resetSession()
                        client.connectionPool.evictAll()
                        Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1))
                        return@use
                    }
                    if (challenged) {
                        throw IOException(
                            "PornHub temporarily rate-limited requests. Wait a few seconds and tap Retry.",
                        )
                    }
                    throw IOException(
                        "PornHub blocked this connection (HTTP $code). " +
                            "If a VPN or WARP is active, turn it off or switch to DNS-only mode and retry.",
                    )
                }

                if (!response.isSuccessful) {
                    throw IOException("HTTP error $code while loading PornHub.")
                }

                val result = PageResult(finalUrl, html, responseCookies, code)
                if (cacheTtlMs > 0L && request.method == "GET" && html.length > MIN_CACHEABLE_HTML_LEN) {
                    if (pageCache.size > MAX_CACHE_ENTRIES) pageCache.clear()
                    pageCache[urlKey] = System.currentTimeMillis() to result
                    if (finalUrl != urlKey) {
                        pageCache[finalUrl] = System.currentTimeMillis() to result
                    }
                }
                return result
            }
        }

        if (lastChallenged) {
            throw IOException("PornHub temporarily rate-limited requests. Wait a few seconds and tap Retry.")
        }
        throw IOException("PornHub connection failed (HTTP $lastCode).")
    }

    /**
     * Only touches `phncdn.com` CDN requests (never reads or wraps `pornhub.com` HTML bodies).
     */
    private fun phncdnCoverInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!host.endsWith("phncdn.com")) return chain.proceed(request)

        val referer = "${baseUrlProvider().trimEnd('/')}/"
        val reqUrl = request.url.toString()
        val isThumbHost = host.contains("pix-") || host.startsWith("ei.") || host.startsWith("di.")
        val freshUrl = if (isThumbHost) extractThumbnailKey(reqUrl)?.let { freshThumbnails[it] } else null

        val targetUrl = if (freshUrl != null && freshUrl != reqUrl && isThumbnailExpired(reqUrl)) {
            freshUrl
        } else {
            reqUrl
        }

        val initialReq = request.newBuilder()
            .url(targetUrl)
            .apply {
                if (request.header("Referer") == null) header("Referer", referer)
            }
            .build()

        val resp = chain.proceed(initialReq)
        if (resp.code in EXPIRED_COVER_CODES) {
            val retryUrl = fallbackThumbnails[targetUrl]?.takeIf { it != targetUrl }
                ?: freshUrl?.takeIf { it != targetUrl }
            if (retryUrl != null) {
                resp.close()
                return chain.proceed(
                    request.newBuilder()
                        .url(retryUrl)
                        .header("Referer", referer)
                        .build(),
                )
            }
        }
        return resp
    }

    private fun isChallengeHtml(html: String): Boolean {
        if (html.length > 8000) return false
        return html.contains("leastFactor") ||
            html.contains("onload=\"go()\"") ||
            html.contains("htjschalref")
    }

    private fun parseSetCookies(headers: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in headers) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.lowercase() in BLOCKED_COOKIES) continue
            if (value.isBlank() || value.equals("deleted", ignoreCase = true)) continue
            map[name] = value
        }
        return map
    }

    private fun extractThumbnailKey(url: String): String? = THUMB_KEY_REGEX.find(url)?.value

    private fun isThumbnailExpired(url: String): Boolean {
        val exp = THUMB_EXP_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return false
        return exp <= (System.currentTimeMillis() / 1000L) + 60L
    }

    private class PornHubCookieJar(
        private val delegate: CookieJar,
    ) : CookieJar {
        private val session = ConcurrentHashMap<String, String>()

        fun resetSession() {
            session.clear()
        }

        fun snapshotSessionCookies(): Map<String, String> = LinkedHashMap(session)

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            if (host.endsWith("phncdn.com")) return emptyList()
            if (!host.endsWith("pornhub.com")) return delegate.loadForRequest(url)

            val merged = LinkedHashMap<String, String>()
            // Pull only Cloudflare clearance cookies from host WebView jar, never PornHub's mobile/challenge cookies.
            runCatching { delegate.loadForRequest(url) }.getOrDefault(emptyList()).forEach { c ->
                if (c.name in CLOUDFLARE_COOKIES && c.value.isNotBlank()) {
                    merged[c.name] = c.value
                }
            }
            merged.putAll(session)
            REQUIRED_COOKIES.forEach { (k, v) -> merged[k] = v }

            return merged.map { (name, value) ->
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain("pornhub.com")
                    .path("/")
                    .secure()
                    .build()
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            if (host.endsWith("phncdn.com")) return
            if (!host.endsWith("pornhub.com")) {
                delegate.saveFromResponse(url, cookies)
                return
            }

            val cfCookies = mutableListOf<Cookie>()
            for (c in cookies) {
                val lower = c.name.lowercase()
                if (c.name in CLOUDFLARE_COOKIES) {
                    cfCookies.add(c)
                    continue
                }
                if (lower in BLOCKED_COOKIES || c.name in REQUIRED_COOKIES) continue
                if (c.value.isBlank() || c.value.equals("deleted", ignoreCase = true)) {
                    session.remove(c.name)
                } else {
                    session[c.name] = c.value
                }
            }
            if (cfCookies.isNotEmpty()) {
                runCatching { delegate.saveFromResponse(url, cfCookies) }
            }
        }
    }

    companion object {
        const val BROWSE_CACHE_TTL_MS = 90 * 1000L
        const val VIDEO_CACHE_TTL_MS = 5 * 60 * 1000L

        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 2500L
        private const val MIN_CACHEABLE_HTML_LEN = 5000
        private const val MAX_CACHE_ENTRIES = 60

        private val REFUSED_CODES = setOf(403, 429)
        private val EXPIRED_COVER_CODES = setOf(403, 404, 410, 470, 472)

        private val THUMB_KEY_REGEX = Regex("""/videos/\d+/\d+/\d+/""")
        private val THUMB_EXP_REGEX = Regex("""(?:validto|exp)=(\d+)""")

        private val CLOUDFLARE_COOKIES = setOf("cf_clearance", "__cf_bm")

        private val BLOCKED_COOKIES = setOf(
            "htjschalref",
            "rp",
            "key",
            "htjf-mobile",
            "accessagedisclaimerph",
            "overwriteccval",
        )

        val REQUIRED_COOKIES = linkedMapOf(
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
