package eu.kanade.tachiyomi.animeextension.all.pornhub

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Clean networking layer for PornHub with split per-endpoint rate buckets,
 * single-flight request coalescing, and adaptive 429/challenge backoff.
 *
 * Key architectural guarantees:
 * 1. Never reads or re-wraps HTML response bodies inside OkHttp interceptors, so the host app's
 *    BrotliInterceptor / Gzip decompression always completes cleanly before HTML is touched.
 * 2. Isolates PornHub requests from poisoned Android WebView cookies (`htjf-mobile`, `htjschalref`,
 *    `KEY`, `rp`, `overwriteCCVal`, etc.) while keeping a clean persistent desktop session and
 *    still reading Cloudflare clearance cookies (`cf_clearance`, `__cf_bm`) if needed.
 * 3. Caches decompressed HTML + `Set-Cookie` maps post-decompression (including 404/empty pages),
 *    coalesces duplicate concurrent GETs, and isolates Search pacing from Listing/Details/Play.
 * 4. Transparently refreshes expired `phncdn.com` thumbnail URLs from the current session map.
 */
internal class PornHubClient(
    private val hostClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
) {
    enum class RateBucket(val normalGapMs: Long, val cooldownGapMs: Long) {
        LISTING(LISTING_GAP_MS, LISTING_COOLDOWN_GAP_MS),
        VIDEO(VIDEO_GAP_MS, VIDEO_COOLDOWN_GAP_MS),
        SEARCH(SEARCH_GAP_MS, SEARCH_COOLDOWN_GAP_MS),
    }

    data class PageResult(
        val finalUrl: String,
        val html: String,
        val responseCookies: Map<String, String>,
        val statusCode: Int,
    )

    private class BucketPacer(val bucket: RateBucket) {
        val lock = Any()
        val highPriorityWaiters = AtomicInteger(0)

        @Volatile
        var nextAllowedMs: Long = 0L

        @Volatile
        var cooldownUntilMs: Long = 0L
    }

    private class InFlightPage {
        val latch = CountDownLatch(1)
        val result = AtomicReference<PageResult?>(null)
        val error = AtomicReference<Throwable?>(null)
    }

    private val cookieJar = PornHubCookieJar(hostClient.cookieJar)
    private val pageCache = ConcurrentHashMap<String, Pair<Long, PageResult>>()
    private val inFlightPages = ConcurrentHashMap<String, InFlightPage>()
    private val freshThumbnails = ConcurrentHashMap<String, String>()
    private val fallbackThumbnails = ConcurrentHashMap<String, String>()

    private val pacers = mapOf(
        RateBucket.LISTING to BucketPacer(RateBucket.LISTING),
        RateBucket.VIDEO to BucketPacer(RateBucket.VIDEO),
        RateBucket.SEARCH to BucketPacer(RateBucket.SEARCH),
    )

    val client: OkHttpClient = hostClient.newBuilder()
        .cookieJar(cookieJar)
        .addInterceptor(::phncdnCoverInterceptor)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Shares connection pool, dispatcher, and Brotli decompression with [client],
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
        val effectiveTtl = if (cached.statusCode == 404 || cached.html.isBlank()) EMPTY_CACHE_TTL_MS else ttlMs
        return if (System.currentTimeMillis() - ts < effectiveTtl) cached else null
    }

    private fun acquireSlot(bucket: RateBucket, highPriority: Boolean) {
        val pacer = pacers.getValue(bucket)
        if (highPriority) pacer.highPriorityWaiters.incrementAndGet()
        try {
            while (true) {
                var sleepMs = 0L
                synchronized(pacer.lock) {
                    val now = System.currentTimeMillis()
                    if (!highPriority && pacer.highPriorityWaiters.get() > 0) {
                        sleepMs = 120L
                    } else if (now < pacer.nextAllowedMs) {
                        sleepMs = pacer.nextAllowedMs - now
                    } else {
                        val gap = if (now < pacer.cooldownUntilMs) bucket.cooldownGapMs else bucket.normalGapMs
                        pacer.nextAllowedMs = now + gap
                        return
                    }
                }
                if (sleepMs > 0L) {
                    try {
                        Thread.sleep(sleepMs.coerceAtMost(1_000L))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("PornHub request cancelled while pacing.", e)
                    }
                }
            }
        } finally {
            if (highPriority) pacer.highPriorityWaiters.decrementAndGet()
        }
    }

    private fun noteThrottle(bucket: RateBucket, retryAfterSeconds: Long?, attempt: Int) {
        val baseBackoff = RETRY_BACKOFF_MS * (attempt + 1)
        val headerBackoff = retryAfterSeconds?.coerceIn(1L, 10L)?.times(1_000L) ?: 0L
        val delayMs = maxOf(baseBackoff, headerBackoff)
        val pacer = pacers.getValue(bucket)
        synchronized(pacer.lock) {
            val now = System.currentTimeMillis()
            pacer.cooldownUntilMs = maxOf(pacer.cooldownUntilMs, now + COOLDOWN_WINDOW_MS)
            pacer.nextAllowedMs = maxOf(pacer.nextAllowedMs, now + delayMs)
        }
    }

    fun fetchPage(
        request: Request,
        cacheTtlMs: Long = BROWSE_CACHE_TTL_MS,
        bypassCache: Boolean = false,
        allow404Empty: Boolean = false,
        bucket: RateBucket = RateBucket.LISTING,
        highPriority: Boolean = true,
    ): PageResult {
        val urlKey = request.url.toString()
        if (!bypassCache && cacheTtlMs > 0L && request.method == "GET") {
            getCachedPage(urlKey, cacheTtlMs)?.let { return it }
            pageCache.remove(urlKey)
        }

        // Single-flight coalescing so identical concurrent GETs never burn extra rate-limit slots.
        if (!bypassCache && request.method == "GET") {
            val newHolder = InFlightPage()
            val existing = inFlightPages.putIfAbsent(urlKey, newHolder)
            if (existing != null) {
                try {
                    if (!existing.latch.await(SINGLE_FLIGHT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        throw IOException("PornHub timed out waiting for coalesced request.")
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("PornHub request interrupted.", e)
                }
                existing.result.get()?.let { return it }
                val err = existing.error.get()
                if (err is IOException) throw err
                throw IOException(err?.message ?: "PornHub coalesced request failed.", err)
            }

            try {
                val res = executeFetchWithRetries(request, urlKey, cacheTtlMs, allow404Empty, bucket, highPriority)
                newHolder.result.set(res)
                return res
            } catch (t: Throwable) {
                newHolder.error.set(t)
                throw t
            } finally {
                newHolder.latch.countDown()
                inFlightPages.remove(urlKey, newHolder)
            }
        }

        return executeFetchWithRetries(request, urlKey, cacheTtlMs, allow404Empty, bucket, highPriority)
    }

    private fun executeFetchWithRetries(
        request: Request,
        urlKey: String,
        cacheTtlMs: Long,
        allow404Empty: Boolean,
        bucket: RateBucket,
        highPriority: Boolean,
    ): PageResult {
        var lastCode = 0
        var lastChallenged = false

        for (attempt in 0..MAX_RETRIES) {
            acquireSlot(bucket, highPriority)

            client.newCall(request).execute().use { response ->
                val code = response.code
                val finalUrl = response.request.url.toString()
                val responseCookies = parseSetCookies(response.headers("Set-Cookie"))
                val html = response.body.string()

                if (code == 404 && allow404Empty) {
                    val emptyRes = PageResult(finalUrl, "", responseCookies, 404)
                    if (cacheTtlMs > 0L && request.method == "GET") {
                        if (pageCache.size > MAX_CACHE_ENTRIES) pageCache.clear()
                        pageCache[urlKey] = System.currentTimeMillis() to emptyRes
                    }
                    return emptyRes
                }

                val challenged = isChallengeHtml(html)
                if (code in REFUSED_CODES || challenged) {
                    lastCode = code
                    lastChallenged = challenged
                    if (attempt < MAX_RETRIES) {
                        if (challenged || code == 403) {
                            cookieJar.resetSession()
                            client.connectionPool.evictAll()
                        }
                        noteThrottle(bucket, response.header("Retry-After")?.toLongOrNull(), attempt)
                        return@use
                    }
                    if (challenged || code == 429) {
                        throw IOException(
                            "PornHub temporarily rate-limited requests (HTTP $code). Wait a few seconds and tap Retry.",
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

        if (lastChallenged || lastCode == 429) {
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
        const val BROWSE_CACHE_TTL_MS = 5 * 60 * 1000L
        const val VIDEO_CACHE_TTL_MS = 5 * 60 * 1000L
        const val EMPTY_CACHE_TTL_MS = 3 * 60 * 1000L

        private const val LISTING_GAP_MS = 280L
        private const val LISTING_COOLDOWN_GAP_MS = 1_400L
        private const val VIDEO_GAP_MS = 250L
        private const val VIDEO_COOLDOWN_GAP_MS = 1_200L
        private const val SEARCH_GAP_MS = 380L
        private const val SEARCH_COOLDOWN_GAP_MS = 1_800L
        private const val COOLDOWN_WINDOW_MS = 18_000L
        private const val SINGLE_FLIGHT_WAIT_MS = 25_000L

        private const val MAX_RETRIES = 2
        private const val RETRY_BACKOFF_MS = 1_800L
        private const val MIN_CACHEABLE_HTML_LEN = 5000
        private const val MAX_CACHE_ENTRIES = 100

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
