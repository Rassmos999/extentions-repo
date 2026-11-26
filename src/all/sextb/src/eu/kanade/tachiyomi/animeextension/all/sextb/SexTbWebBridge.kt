package eu.kanade.tachiyomi.animeextension.all.sextb

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * Lightweight same-origin WebView fetch bridge for sites where Cloudflare binds
 * `cf_clearance` to Chromium's TLS/HTTP2 fingerprint (JA4), causing OkHttp to fail
 * with `CloudflareFingerprintMismatchException` even after the user has already
 * completed Cloudflare verification in WebView.
 *
 * This bridge NEVER solves, clicks, or bypasses any CAPTCHA/Turnstile challenge:
 * it only executes same-origin `fetch()` using the user's already-valid CookieManager
 * session, and immediately returns any 403/challenge response so the caller prompts
 * the user to verify in WebView.
 */
internal class SexTbWebBridge(private val baseUrl: String) {

    data class FetchResult(
        val status: Int,
        val finalUrl: String,
        val body: String,
        val retryAfterSeconds: Long? = null,
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
    private val consecutiveTimeouts = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, PendingCall>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var ready = false

    fun hasClearanceCookie(): Boolean = runCatching {
        val cookies = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
        cookies.contains("cf_clearance=", ignoreCase = true)
    }.getOrDefault(false)

    fun fetch(
        url: String,
        method: String = "GET",
        formBody: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L,
    ): FetchResult {
        ensureReady()
        val wv = webView ?: throw IOException("SexTB WebView bridge is unavailable.")
        val reqId = "r${requestCounter.incrementAndGet()}"
        val call = PendingCall()
        pending[reqId] = call

        val headersJson = JSONObject().apply {
            extraHeaders.forEach { (k, v) -> put(k, v) }
        }.toString()

        val bodyJs = if (formBody != null) JSONObject.quote(formBody) else "undefined"
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
                            var ra = (resp.headers && resp.headers.get('retry-after')) || '';
                            return resp.text().then(function(txt) {
                                clearTimeout(timer);
                                window.$BRIDGE_NAME.onResult(
                                    ${JSONObject.quote(reqId)},
                                    resp.status || 0,
                                    resp.url || ${JSONObject.quote(url)},
                                    ra,
                                    txt || ''
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
                // Only recycle the shared WebView after repeated consecutive timeouts
                // so one slow request never aborts unrelated in-flight requests.
                if (consecutiveTimeouts.incrementAndGet() >= RESET_AFTER_TIMEOUTS) {
                    consecutiveTimeouts.set(0)
                    reset()
                }
                throw IOException("SexTB WebView request timed out for $url")
            }
            consecutiveTimeouts.set(0)
            when (val outcome = call.outcome.get()) {
                is CallOutcome.Ok -> outcome.result

                is CallOutcome.Err -> {
                    val err = outcome.error
                    if (err is IOException) throw err
                    throw IOException(err.message ?: "SexTB WebView fetch failed", err)
                }

                null -> throw IOException("SexTB WebView request returned no result for $url")
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
            pending.values.forEach { it.complete(CallOutcome.Err(IOException("SexTB WebView bridge reset"))) }
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
            val bootstrapUrl = "${baseUrl.trimEnd('/')}/robots.txt"
            val allowedHost = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()

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
                throw IOException("Failed to initialize SexTB WebView bridge: ${err.message}", err)
            }
            if (!ok || !ready || webView == null) {
                reset()
                throw IOException("Timed out initializing SexTB WebView bridge.")
            }
            Log.i(TAG, "SexTB WebView bridge initialized on $bootstrapUrl")
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onResult(reqId: String, status: Int, finalUrl: String, retryAfter: String, body: String) {
            val retryAfterSec = retryAfter.trim().toLongOrNull()
            pending.remove(reqId)?.complete(
                CallOutcome.Ok(FetchResult(status, finalUrl, body, retryAfterSec)),
            )
        }

        @JavascriptInterface
        fun onError(reqId: String, error: String) {
            pending.remove(reqId)?.complete(CallOutcome.Err(IOException("WebView fetch error: $error")))
        }
    }

    companion object {
        private const val TAG = "SexTB"
        private const val BRIDGE_NAME = "SexTbBridge"
        private const val INIT_TIMEOUT_MS = 8_000L
        private const val RESET_AFTER_TIMEOUTS = 3
    }
}
