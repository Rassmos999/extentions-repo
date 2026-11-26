package aniyomi.lib.m3u8server

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Transparent OkHttp [Interceptor] that resolves Cloudflare JA4 TLS-fingerprint
 * mismatches (`cf_clearance` bound to Chromium WebView's TLS fingerprint, causing
 * OkHttp to receive 403/503 challenges even after the user already verified in WebView).
 *
 * Security & policy guarantees:
 * - NEVER solves, clicks, waits out, or bypasses any CAPTCHA/Turnstile challenge.
 * - Only activates for the configured extension origin(s) when `cf_clearance` is
 *   ALREADY present in Android's [CookieManager].
 * - When `cf_clearance` is missing or expired (WebView fetch also gets 403/503),
 *   passes the challenge response directly back to OkHttp / `CloudflareInterceptor`
 *   so the host app prompts the user to verify in WebView normally.
 * - Zero overhead on normal OkHttp 200 responses and zero interference with
 *   third-party video/CDN hosts.
 */
class CfWebBridgeInterceptor(
    private val baseUrlProvider: () -> String,
) : Interceptor {

    constructor(baseUrl: String) : this({ baseUrl })

    private val bridges = ConcurrentHashMap<String, SameOriginWebBridge>()
    private val stickyUntilMs = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val reqUrl = request.url
        val reqHost = reqUrl.host.lowercase()

        val allowedRootHost = baseUrlProvider().toHttpUrlOrNull()?.host
            ?.lowercase()
            ?.removePrefix("www.")
            ?: return chain.proceed(request)

        val normalizedReqHost = reqHost.removePrefix("www.")
        if (normalizedReqHost != allowedRootHost && !normalizedReqHost.endsWith(".$allowedRootHost")) {
            return chain.proceed(request)
        }

        // Never bridge large streaming media files through WebView memory.
        val pathLower = reqUrl.encodedPath.lowercase()
        if (SKIP_EXTENSIONS.any { pathLower.endsWith(it) }) {
            return chain.proceed(request)
        }

        val origin = "${reqUrl.scheme}://${reqUrl.host}"
        if (!hasClearanceCookie(origin, reqUrl.toString())) {
            stickyUntilMs.remove(origin)
            return chain.proceed(request)
        }

        val now = System.currentTimeMillis()
        if (now < (stickyUntilMs[origin] ?: 0L)) {
            return executeViaBridge(request, origin)
        }

        val response = chain.proceed(request)
        if (!isCloudflareChallenge(response)) {
            return response
        }

        // Re-check clearance cookie (CloudflareInterceptor or another tab may have just set it).
        if (!hasClearanceCookie(origin, reqUrl.toString())) {
            return response
        }

        Log.i(TAG, "Cloudflare JA4 mismatch on $origin (${response.code}); switching to same-origin WebView bridge")
        response.close()
        stickyUntilMs[origin] = System.currentTimeMillis() + STICKY_TTL_MS
        return executeViaBridge(request, origin)
    }

    private fun executeViaBridge(request: Request, origin: String): Response {
        val bridge = bridges.computeIfAbsent(origin) { SameOriginWebBridge(origin) }
        var result = runBridgeRequest(bridge, request)

        // Single gentle backoff retry on HTTP 429 rate-limit without clearing sticky mode.
        if (result.status == 429) {
            runCatching { Thread.sleep(RATE_LIMIT_RETRY_MS) }
            result = runBridgeRequest(bridge, request)
        }

        val isChallenge = result.status in CF_STATUS_CODES &&
            !result.isBase64 &&
            isChallengeBody(result.body)

        if (isChallenge) {
            Log.w(TAG, "WebView bridge also hit Cloudflare challenge (${result.status}) on $origin; resetting sticky state")
            stickyUntilMs.remove(origin)
            bridge.reset()
        }

        val rawBytes = if (result.isBase64) {
            runCatching { Base64.decode(result.body, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        } else {
            result.body.toByteArray(Charsets.UTF_8)
        }

        val contentType = result.contentType.ifBlank {
            if (result.isBase64) "application/octet-stream" else "text/html; charset=utf-8"
        }

        val finalHttpUrl = result.finalUrl.toHttpUrlOrNull() ?: request.url
        val finalRequest = if (finalHttpUrl != request.url) {
            request.newBuilder().url(finalHttpUrl).build()
        } else {
            request
        }

        val status = if (result.status in 100..599) result.status else 502
        val builder = Response.Builder()
            .request(finalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message(statusMessage(status))
            .header("Content-Type", contentType)
            .body(rawBytes.toResponseBody(contentType.toMediaTypeOrNull()))

        if (isChallenge) {
            builder.header("Server", "cloudflare")
            builder.header("Cf-Mitigated", "challenge")
        }

        return builder.build()
    }

    private fun runBridgeRequest(bridge: SameOriginWebBridge, request: Request): SameOriginWebBridge.FetchResult {
        val method = request.method.uppercase()
        val bodyStr = if (method != "GET" && method != "HEAD" && request.body != null) {
            val reqBody = request.body!!
            val contentLen = runCatching { reqBody.contentLength() }.getOrDefault(-1L)
            if (contentLen > MAX_POST_BODY_BYTES) {
                throw IOException("Request body too large for WebView bridge ($contentLen bytes)")
            }
            val buffer = Buffer()
            reqBody.writeTo(buffer)
            buffer.readUtf8()
        } else {
            null
        }

        val extraHeaders = LinkedHashMap<String, String>()
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            val lower = name.lowercase()
            if (lower !in FORBIDDEN_FETCH_HEADERS) {
                extraHeaders[name] = request.headers.value(i)
            }
        }
        request.body?.contentType()?.toString()?.let { ct ->
            if (extraHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                extraHeaders["Content-Type"] = ct
            }
        }

        return bridge.fetch(
            url = request.url.toString(),
            method = method,
            body = bodyStr,
            extraHeaders = extraHeaders,
            timeoutMs = FETCH_TIMEOUT_MS,
        )
    }

    private fun hasClearanceCookie(origin: String, fullUrl: String): Boolean = runCatching {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie(origin).orEmpty()
        if (c1.contains("cf_clearance=", ignoreCase = true)) return true
        val c2 = cm.getCookie(fullUrl).orEmpty()
        c2.contains("cf_clearance=", ignoreCase = true)
    }.getOrDefault(false)

    private fun isCloudflareChallenge(response: Response): Boolean {
        if (response.code !in CF_STATUS_CODES) return false
        if (response.header("cf-mitigated")?.contains("challenge", ignoreCase = true) == true) {
            return true
        }
        val server = response.header("server").orEmpty()
        val hasCfRay = response.header("cf-ray") != null
        if (!hasCfRay && !server.contains("cloudflare", ignoreCase = true)) {
            return false
        }
        val snippet = runCatching { response.peekBody(16_384L).string() }.getOrDefault("")
        return isChallengeBody(snippet)
    }

    private fun isChallengeBody(body: String): Boolean {
        if (body.isBlank()) return false
        val head = if (body.length > 16_384) body.substring(0, 16_384) else body
        val lower = head.lowercase()
        return CHALLENGE_MARKERS.any { lower.contains(it) }
    }

    private fun statusMessage(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "HTTP $code"
    }

    companion object {
        private const val TAG = "CfWebBridge"
        private const val STICKY_TTL_MS = 15L * 60L * 1000L
        private const val FETCH_TIMEOUT_MS = 15_000L
        private const val RATE_LIMIT_RETRY_MS = 900L
        private const val MAX_POST_BODY_BYTES = 1024L * 1024L
        private val CF_STATUS_CODES = setOf(403, 503)
        private val SKIP_EXTENSIONS = listOf(".m3u8", ".ts", ".mp4", ".mpd", ".webm", ".mkv", ".m4s")
        private val FORBIDDEN_FETCH_HEADERS = setOf(
            "host",
            "user-agent",
            "referer",
            "origin",
            "cookie",
            "content-length",
            "accept-encoding",
            "connection",
            "upgrade-insecure-requests",
            "sec-fetch-dest",
            "sec-fetch-mode",
            "sec-fetch-site",
            "sec-fetch-user",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
        )
        private val CHALLENGE_MARKERS = listOf(
            "just a moment",
            "checking your browser",
            "_cf_chl_opt",
            "challenges.cloudflare.com",
            "cf-browser-verification",
            "cf-turnstile",
            "attention required! | cloudflare",
            "enable javascript and cookies to continue",
        )
    }
}

internal class SameOriginWebBridge(private val origin: String) {

    data class FetchResult(
        val status: Int,
        val finalUrl: String,
        val contentType: String,
        val isBase64: Boolean,
        val body: String,
    )

    private sealed class CallOutcome {
        data class Ok(val result: FetchResult) : CallOutcome()
        data class Err(val error: Throwable) : CallOutcome()
    }

    private class PendingCall {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<CallOutcome?>(null)

        fun complete(value: CallOutcome) {
            if (outcome.compareAndSet(null, value)) {
                latch.countDown()
            }
        }
    }

    private val context: Application by injectLazy()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val initLock = Any()
    private val requestCounter = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, PendingCall>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var ready = false

    fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L,
    ): FetchResult {
        ensureReady()
        val wv = webView ?: throw IOException("WebView bridge unavailable for $origin")
        val reqId = "r${requestCounter.incrementAndGet()}"
        val call = PendingCall()
        pending[reqId] = call

        val headersJson = JSONObject().apply {
            extraHeaders.forEach { (k, v) -> put(k, v) }
        }.toString()

        val bodyJs = if (body != null) JSONObject.quote(body) else "undefined"
        val js = """
            (function() {
                try {
                    if (!window.$BRIDGE_NAME) {
                        return;
                    }
                    var ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
                    var timer = setTimeout(function() {
                        try { if (ctrl) ctrl.abort(); } catch (_) {}
                    }, ${timeoutMs.coerceAtMost(18_000L)});
                    var opts = {
                        method: ${JSONObject.quote(method)},
                        credentials: 'include',
                        redirect: 'follow',
                        headers: $headersJson
                    };
                    if (ctrl) opts.signal = ctrl.signal;
                    if ($bodyJs !== undefined) opts.body = $bodyJs;
                    fetch(${JSONObject.quote(url)}, opts)
                        .then(function(resp) {
                            var ct = (resp.headers && resp.headers.get('content-type')) || '';
                            var ctLower = ct.toLowerCase();
                            var isText = !ctLower || ctLower.indexOf('text/') === 0 ||
                                ctLower.indexOf('json') !== -1 ||
                                ctLower.indexOf('xml') !== -1 ||
                                ctLower.indexOf('html') !== -1 ||
                                ctLower.indexOf('javascript') !== -1 ||
                                ctLower.indexOf('x-www-form-urlencoded') !== -1;
                            if (isText) {
                                return resp.text().then(function(txt) {
                                    clearTimeout(timer);
                                    window.$BRIDGE_NAME.onResult(
                                        ${JSONObject.quote(reqId)},
                                        resp.status || 0,
                                        resp.url || ${JSONObject.quote(url)},
                                        ct,
                                        false,
                                        txt || ''
                                    );
                                });
                            }
                            return resp.arrayBuffer().then(function(buf) {
                                clearTimeout(timer);
                                var bytes = new Uint8Array(buf);
                                var chunk = 8192;
                                var bin = '';
                                for (var i = 0; i < bytes.length; i += chunk) {
                                    bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                                }
                                window.$BRIDGE_NAME.onResult(
                                    ${JSONObject.quote(reqId)},
                                    resp.status || 0,
                                    resp.url || ${JSONObject.quote(url)},
                                    ct,
                                    true,
                                    btoa(bin)
                                );
                            });
                        })
                        .catch(function(err) {
                            clearTimeout(timer);
                            window.$BRIDGE_NAME.onError(
                                ${JSONObject.quote(reqId)},
                                String(err || 'fetch failed')
                            );
                        });
                } catch (e) {
                    try {
                        window.$BRIDGE_NAME.onError(${JSONObject.quote(reqId)}, String(e));
                    } catch (_) {}
                }
            })();
        """.trimIndent()

        mainHandler.post {
            try {
                wv.evaluateJavascript(js, null)
            } catch (e: Exception) {
                call.complete(CallOutcome.Err(IOException("WebView evaluateJavascript failed: ${e.message}", e)))
            }
        }

        return try {
            val finished = call.latch.await(timeoutMs + 1_500L, TimeUnit.MILLISECONDS)
            if (!finished) {
                reset()
                throw IOException("WebView bridge request timed out for $url")
            }
            when (val out = call.outcome.get()) {
                is CallOutcome.Ok -> out.result

                is CallOutcome.Err -> {
                    val err = out.error
                    if (err is IOException) throw err
                    throw IOException(err.message ?: "WebView bridge fetch failed", err)
                }

                null -> throw IOException("WebView bridge returned no result for $url")
            }
        } finally {
            pending.remove(reqId)
        }
    }

    fun reset() {
        synchronized(initLock) {
            ready = false
            val old = webView
            webView = null
            if (old != null) {
                mainHandler.post {
                    runCatching {
                        old.stopLoading()
                        old.removeJavascriptInterface(BRIDGE_NAME)
                        old.destroy()
                    }
                }
            }
            pending.values.forEach { it.complete(CallOutcome.Err(IOException("WebView bridge reset for $origin"))) }
            pending.clear()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureReady() {
        if (ready && webView != null) return
        synchronized(initLock) {
            if (ready && webView != null) return

            val initLatch = CountDownLatch(1)
            val initError = AtomicReference<Throwable?>(null)
            val bootstrapUrl = "${origin.trimEnd('/')}/robots.txt"
            val allowedHost = origin.removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()

            mainHandler.post {
                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)

                    val wv = WebView(context)
                    cookieManager.setAcceptThirdPartyCookies(wv, false)
                    with(wv.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    wv.addJavascriptInterface(JsBridge(), BRIDGE_NAME)
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val host = request?.url?.host?.lowercase().orEmpty()
                            if (host == allowedHost || host.endsWith(".$allowedHost")) {
                                return null
                            }
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null && url.startsWith("http", ignoreCase = true)) {
                                ready = true
                                initLatch.countDown()
                            }
                        }
                    }

                    webView = wv
                    wv.loadUrl(bootstrapUrl)
                } catch (t: Throwable) {
                    initError.set(t)
                    initLatch.countDown()
                }
            }

            val ok = initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val err = initError.get()
            if (err != null) {
                reset()
                throw IOException("Failed to initialize WebView bridge for $origin: ${err.message}", err)
            }
            if (!ok || !ready || webView == null) {
                reset()
                throw IOException("Timed out initializing WebView bridge for $origin")
            }
            Log.i(TAG, "WebView bridge initialized on $bootstrapUrl")
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onResult(reqId: String, status: Int, finalUrl: String, contentType: String, isBase64: Boolean, body: String) {
            pending.remove(reqId)?.complete(
                CallOutcome.Ok(FetchResult(status, finalUrl, contentType, isBase64, body)),
            )
        }

        @JavascriptInterface
        fun onError(reqId: String, error: String) {
            pending.remove(reqId)?.complete(CallOutcome.Err(IOException("WebView fetch error: $error")))
        }
    }

    companion object {
        private const val TAG = "CfWebBridge"
        private const val BRIDGE_NAME = "AgyCfBridge"
        private const val INIT_TIMEOUT_MS = 8_000L
    }
}
