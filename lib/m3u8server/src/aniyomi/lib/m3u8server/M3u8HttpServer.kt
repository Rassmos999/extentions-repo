package aniyomi.lib.m3u8server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Local HTTP proxy server for M3U8 and direct MP4 streaming using NanoHTTPD.
 *
 * Features:
 * - Hybrid RAM (16 MB) + temp-disk (96 MB) LRU cache with 20-min TTL so
 *   seeking/skipping backward or revisiting timestamps replays in < 2 ms.
 * - Playlist (5 min) and AES-128 key (30 min) caching (avoids re-fetching keys
 *   on every TS segment).
 * - In-flight download coalescing so concurrent player + prefetch fetches never
 *   duplicate upstream requests.
 * - Adaptive 2-segment lookahead prefetching for HLS playlists (cancels stale
 *   prefetches automatically when the user seeks to a new position).
 * - `/media` 512 KB aligned byte-range block cache with `206 Partial Content`
 *   support and 1-block read-ahead for direct MP4 streams.
 */
class M3u8HttpServer(
    private val client: OkHttpClient,
    port: Int = 0, // 0 means random port
    private val fallbackClient: OkHttpClient? = null,
) : NanoHTTPD(port) {

    val port: Int
        get() = super.getListeningPort()

    private val tag by lazy { javaClass.simpleName }

    @Volatile
    private var isRunning = false

    private val cache = StreamCache()

    private val playlistSeqCounter = AtomicInteger(0)
    private val playlistSegmentsLock = Any()
    private val playlistSegments = object : LinkedHashMap<String, List<SegmentTask>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SegmentTask>>?): Boolean = size > MAX_TRACKED_PLAYLISTS
    }
    private val lastRequestedIndex = ConcurrentHashMap<String, Int>()
    private val activePrefetches = ConcurrentHashMap<String, MutableList<Future<*>>>()

    private val mediaMetaCache = ConcurrentHashMap<String, MediaMeta>()

    @Volatile
    private var prefetchExecutor: ThreadPoolExecutor? = null

    private data class SegmentTask(
        val url: String,
        val keyUrl: String?,
        val iv: String?,
        val headers: Map<String, String>,
    )

    private data class MediaMeta(
        val contentLength: Long,
        val contentType: String,
        val acceptsRanges: Boolean,
        val expiresAt: Long,
    )

    private fun getPrefetchPool(): ThreadPoolExecutor {
        prefetchExecutor?.takeIf { !it.isShutdown }?.let { return it }
        synchronized(this) {
            prefetchExecutor?.takeIf { !it.isShutdown }?.let { return it }
            return ThreadPoolExecutor(
                2,
                2,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue(MAX_PREFETCH_QUEUE),
                { r ->
                    Thread(r, "m3u8-prefetch").apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY - 1
                    }
                },
                ThreadPoolExecutor.DiscardOldestPolicy(),
            ).also {
                it.allowCoreThreadTimeOut(true)
                prefetchExecutor = it
            }
        }
    }

    override fun start() {
        try {
            super.start()
            isRunning = true
            Log.d(tag, "M3U8 HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            throw e
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        runCatching { prefetchExecutor?.shutdownNow() }
        prefetchExecutor = null
        activePrefetches.clear()
        lastRequestedIndex.clear()
        synchronized(playlistSegmentsLock) { playlistSegments.clear() }
        mediaMetaCache.clear()
        cache.clearRam()
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(tag, "Received request: $method $uri from ${session.remoteIpAddress}")

        val response = when {
            uri.startsWith("/m3u8") -> handleM3u8Request(session)

            uri.startsWith("/segment") -> handleSegmentRequest(session)

            uri.startsWith("/media") -> handleMediaRequest(session)

            uri.startsWith("/health") -> handleHealthRequest()

            else -> {
                Log.w(tag, "Unknown endpoint: $uri")
                newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        Log.d(tag, "Response status: ${response.status}")
        return response
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackOrigin = session.parameters["origin"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val fallbackCookie = session.parameters["cookie"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent, fallbackOrigin, fallbackCookie)

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in M3U8 request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            val processedContent = runBlocking { processM3u8Content(url, headers) }
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", processedContent)
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream HTTP ${e.code} for $url: ${e.message}")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val keyUrl = session.parameters["key"]?.first()
        val iv = session.parameters["iv"]?.first()
        val pid = session.parameters["pid"]?.first()
        val idx = session.parameters["idx"]?.first()?.toIntOrNull()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackOrigin = session.parameters["origin"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val fallbackCookie = session.parameters["cookie"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent, fallbackOrigin, fallbackCookie)

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in segment request")
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        // Trigger adaptive lookahead prefetch for the next 2 segments in this playlist.
        if (!pid.isNullOrBlank() && idx != null) {
            scheduleSegmentPrefetch(pid, idx)
        }

        return try {
            val segmentData = runBlocking {
                processSegmentUrl(url, headers, keyUrl, iv)
            }
            serveBytesWithRange(session, segmentData, "video/mp2t")
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream segment HTTP ${e.code} for $url: ${e.message}")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /**
     * Serves a direct MP4 / progressive media URL using aligned 512 KB cached
     * byte-range blocks so seeking back to any visited point replays from RAM/disk
     * in milliseconds without buffering the entire file in memory.
     */
    private fun handleMediaRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()
        val fallbackReferer = session.parameters["referer"]?.first()
        val fallbackOrigin = session.parameters["origin"]?.first()
        val fallbackUserAgent = session.parameters["useragent"]?.first()
        val fallbackCookie = session.parameters["cookie"]?.first()
        val headers = extractHeadersFromSession(session, fallbackReferer, fallbackUserAgent, fallbackOrigin, fallbackCookie)

        if (url.isNullOrBlank()) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            val meta = resolveMediaMeta(url, headers)
            if (meta == null || !meta.acceptsRanges || meta.contentLength <= 0L) {
                // Graceful pass-through if upstream doesn't support byte ranges.
                return streamUpstreamPassthrough(url, headers, session.headers["range"])
            }

            if (session.method == Method.HEAD) {
                return newFixedLengthResponse(Status.OK, meta.contentType, ByteArrayInputStream(ByteArray(0)), 0L).apply {
                    addHeader("Accept-Ranges", "bytes")
                    addHeader("Content-Length", meta.contentLength.toString())
                }
            }

            val total = meta.contentLength
            val rangeHeader = session.headers["range"]
            val parsedRange = parseByteRange(rangeHeader, total)
            if (rangeHeader != null && parsedRange == null) {
                return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid Range").apply {
                    addHeader("Content-Range", "bytes */$total")
                }
            }

            val start = parsedRange?.first ?: 0L
            val end = parsedRange?.second ?: (total - 1L)
            val length = (end - start + 1L).coerceAtLeast(0L)

            val blockStream = BlockCachingInputStream(
                mediaUrl = url,
                headers = headers,
                totalSize = total,
                startOffset = start,
                endOffset = end,
            )

            val status = if (parsedRange != null) Status.PARTIAL_CONTENT else Status.OK
            newFixedLengthResponse(status, meta.contentType, blockStream, length).apply {
                addHeader("Accept-Ranges", "bytes")
                if (parsedRange != null) {
                    addHeader("Content-Range", "bytes $start-$end/$total")
                }
            }
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream media HTTP ${e.code} for $url: ${e.message}")
            passThroughStatus(e)
        } catch (e: Exception) {
            Log.e(tag, "Error serving media: ${e.message}", e)
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun resolveMediaMeta(url: String, headers: Map<String, String>): MediaMeta? {
        val now = System.currentTimeMillis()
        mediaMetaCache[url]?.let { cached ->
            if (now < cached.expiresAt) return cached
            mediaMetaCache.remove(url, cached)
        }

        // Single-roundtrip probe: fetch block 0 (0..524287) with Range header.
        val probeEnd = MEDIA_BLOCK_SIZE - 1L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-$probeEnd")
        headers.forEach { (k, v) ->
            if (!k.equals("range", ignoreCase = true) && !k.equals("accept-encoding", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        reqBuilder.header("Accept-Encoding", "identity")

        return executeWithFallback(reqBuilder.build(), url) { response ->
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith("text/html", ignoreCase = true) }
                ?: "video/mp4"
            val contentRange = response.header("Content-Range")
            if (response.code == 206 && contentRange != null) {
                val total = parseContentRangeTotal(contentRange)
                val bytes = response.body.bytes()
                if (total != null && total > 0L && bytes.isNotEmpty()) {
                    cache.putBytes(mediaBlockKey(url, 0L), bytes)
                    val meta = MediaMeta(
                        contentLength = total,
                        contentType = contentType,
                        acceptsRanges = true,
                        expiresAt = System.currentTimeMillis() + MEDIA_META_TTL_MS,
                    )
                    if (mediaMetaCache.size > MAX_MEDIA_META) mediaMetaCache.clear()
                    mediaMetaCache[url] = meta
                    return@executeWithFallback meta
                }
            }
            // Upstream returned 200 without Range support — cache meta as non-rangeable.
            val fullLen = response.header("Content-Length")?.toLongOrNull() ?: -1L
            MediaMeta(
                contentLength = fullLen,
                contentType = contentType,
                acceptsRanges = false,
                expiresAt = System.currentTimeMillis() + MEDIA_META_TTL_MS,
            )
        }
    }

    private fun fetchMediaBlock(url: String, headers: Map<String, String>, totalSize: Long, blockIndex: Long): ByteArray {
        val blockStart = blockIndex * MEDIA_BLOCK_SIZE
        val blockEnd = minOf(totalSize - 1L, blockStart + MEDIA_BLOCK_SIZE - 1L)
        val cacheKey = mediaBlockKey(url, blockIndex)
        return cache.getOrLoad(cacheKey) {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=$blockStart-$blockEnd")
            headers.forEach { (k, v) ->
                if (!k.equals("range", ignoreCase = true) && !k.equals("accept-encoding", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            reqBuilder.header("Accept-Encoding", "identity")
            executeWithFallback(reqBuilder.build(), url) { response ->
                response.body.bytes()
            }
        }
    }

    private fun scheduleMediaBlockPrefetch(url: String, headers: Map<String, String>, totalSize: Long, nextBlockIndex: Long) {
        if (!isRunning) return
        val blockStart = nextBlockIndex * MEDIA_BLOCK_SIZE
        if (blockStart >= totalSize) return
        val key = mediaBlockKey(url, nextBlockIndex)
        if (cache.hasCached(key) || cache.isInFlight(key)) return
        runCatching {
            getPrefetchPool().submit {
                runCatching { fetchMediaBlock(url, headers, totalSize, nextBlockIndex) }
            }
        }
    }

    private inner class BlockCachingInputStream(
        private val mediaUrl: String,
        private val headers: Map<String, String>,
        private val totalSize: Long,
        startOffset: Long,
        private val endOffset: Long,
    ) : InputStream() {
        private var position: Long = startOffset
        private var currentBlockIdx: Long = -1L
        private var currentBlock: ByteArray? = null
        private var closed = false

        private fun ensureBlock(): ByteArray? {
            if (closed || position > endOffset) return null
            val neededBlock = position / MEDIA_BLOCK_SIZE
            if (neededBlock != currentBlockIdx || currentBlock == null) {
                val data = fetchMediaBlock(mediaUrl, headers, totalSize, neededBlock)
                currentBlock = data
                currentBlockIdx = neededBlock
                // Lookahead prefetch next 1 block asynchronously.
                scheduleMediaBlockPrefetch(mediaUrl, headers, totalSize, neededBlock + 1L)
            }
            return currentBlock
        }

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n <= 0) -1 else buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed || position > endOffset) return -1
            if (len == 0) return 0
            val block = ensureBlock() ?: return -1
            val offsetInBlock = (position % MEDIA_BLOCK_SIZE).toInt()
            if (offsetInBlock >= block.size) return -1
            val remainingInRequest = (endOffset - position + 1L).coerceAtMost(len.toLong()).toInt()
            val availableInBlock = block.size - offsetInBlock
            val toCopy = minOf(remainingInRequest, availableInBlock)
            if (toCopy <= 0) return -1
            System.arraycopy(block, offsetInBlock, b, off, toCopy)
            position += toCopy
            return toCopy
        }

        override fun close() {
            closed = true
            currentBlock = null
        }
    }

    private fun streamUpstreamPassthrough(url: String, headers: Map<String, String>, rangeHeader: String?): Response {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (!k.equals("accept-encoding", ignoreCase = true)) reqBuilder.header(k, v)
        }
        reqBuilder.header("Accept-Encoding", "identity")
        if (!rangeHeader.isNullOrBlank()) reqBuilder.header("Range", rangeHeader)
        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful && response.code != 206) {
            val code = response.code
            response.close()
            throw UpstreamStatusException(code, url, "Upstream media passthrough failed")
        }
        val contentType = response.header("Content-Type")?.substringBefore(';')?.trim() ?: "video/mp4"
        val contentLength = response.header("Content-Length")?.toLongOrNull()
        val status = if (response.code == 206) Status.PARTIAL_CONTENT else Status.OK
        val bodyStream = response.body.byteStream()
        val closingStream = object : InputStream() {
            override fun read(): Int = bodyStream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = bodyStream.read(b, off, len)
            override fun close() {
                runCatching { bodyStream.close() }
                runCatching { response.close() }
            }
        }
        val nanoResp = if (contentLength != null && contentLength >= 0L) {
            newFixedLengthResponse(status, contentType, closingStream, contentLength)
        } else {
            newChunkedResponse(status, contentType, closingStream)
        }
        response.header("Accept-Ranges")?.let { nanoResp.addHeader("Accept-Ranges", it) }
        response.header("Content-Range")?.let { nanoResp.addHeader("Content-Range", it) }
        return nanoResp
    }

    private fun serveBytesWithRange(session: IHTTPSession, data: ByteArray, mimeType: String): Response {
        val total = data.size.toLong()
        val rangeHeader = session.headers["range"]
        if (!rangeHeader.isNullOrBlank()) {
            val range = parseByteRange(rangeHeader, total)
                ?: return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid Range").apply {
                    addHeader("Content-Range", "bytes */$total")
                }
            val start = range.first.toInt()
            val len = (range.second - range.first + 1L).toInt()
            return newFixedLengthResponse(
                Status.PARTIAL_CONTENT,
                mimeType,
                ByteArrayInputStream(data, start, len),
                len.toLong(),
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes ${range.first}-${range.second}/$total")
            }
        }
        return newFixedLengthResponse(
            Status.OK,
            mimeType,
            ByteArrayInputStream(data),
            total,
        ).apply {
            addHeader("Accept-Ranges", "bytes")
        }
    }

    private fun scheduleSegmentPrefetch(pid: String, currentIdx: Int) {
        if (!isRunning) return
        val segments = synchronized(playlistSegmentsLock) { playlistSegments[pid] } ?: return
        val prevIdx = lastRequestedIndex.put(pid, currentIdx)
        // If the user jumped/seeked away, cancel stale prefetches for this playlist.
        if (prevIdx != null && abs(currentIdx - prevIdx) > PREFETCH_LOOKAHEAD + 1) {
            activePrefetches.remove(pid)?.forEach { runCatching { it.cancel(true) } }
        }
        val pool = getPrefetchPool()
        val futures = activePrefetches.computeIfAbsent(pid) { java.util.Collections.synchronizedList(mutableListOf()) }
        // Prune finished futures to keep the list tiny.
        synchronized(futures) { futures.removeAll { it.isDone } }

        for (offset in 1..PREFETCH_LOOKAHEAD) {
            val nextIdx = currentIdx + offset
            if (nextIdx >= segments.size) break
            val task = segments[nextIdx]
            val segKey = segmentCacheKey(task.url, task.keyUrl, task.iv)
            if (cache.hasCached(segKey) || cache.isInFlight(segKey)) continue
            runCatching {
                val f = pool.submit {
                    runCatching {
                        runBlocking { processSegmentUrl(task.url, task.headers, task.keyUrl, task.iv) }
                    }
                }
                futures.add(f)
            }
        }
    }

    private fun passThroughStatus(e: UpstreamStatusException): Response {
        val nanoStatus = when (e.code) {
            401 -> Status.UNAUTHORIZED
            403 -> Status.FORBIDDEN
            404 -> Status.NOT_FOUND
            429 -> Status.TOO_MANY_REQUESTS
            in 400..499 -> Status.BAD_REQUEST
            500 -> Status.INTERNAL_ERROR
            503 -> Status.SERVICE_UNAVAILABLE
            else -> Status.INTERNAL_ERROR
        }
        val body = "Upstream ${e.code} for ${e.url}\n${e.message}"
        return newFixedLengthResponse(nanoStatus, MIME_PLAINTEXT, body)
    }

    private fun handleHealthRequest(): Response {
        val status = getHealthStatus()
        return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, status)
    }

    private fun extractHeadersFromSession(
        session: IHTTPSession,
        fallbackReferer: String? = null,
        fallbackUserAgent: String? = null,
        fallbackOrigin: String? = null,
        fallbackCookie: String? = null,
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()

        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "connection", "cache-control", "pragma", "cookie",
                -> {
                    headers[key.lowercase()] = value
                }
            }
        }

        if (!fallbackUserAgent.isNullOrBlank()) {
            headers["user-agent"] = fallbackUserAgent
        }
        if (!fallbackReferer.isNullOrBlank()) {
            headers["referer"] = fallbackReferer
        }
        if (!fallbackOrigin.isNullOrBlank()) {
            headers["origin"] = fallbackOrigin
        }
        if (!fallbackCookie.isNullOrBlank()) {
            headers["cookie"] = fallbackCookie
        }

        return headers
    }

    private class UpstreamStatusException(
        val code: Int,
        val url: String,
        message: String,
        cause: Throwable? = null,
    ) : IOException("$code for $url: $message", cause)

    private suspend fun processM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        try {
            val playlistKey = "$url|${headers["referer"].orEmpty()}|${headers["origin"].orEmpty()}"
            val m3u8Content = cache.getPlaylist(playlistKey) ?: fetchM3u8Content(url, headers).also {
                cache.putPlaylist(playlistKey, it)
            }

            val referer = headers["referer"]
            val origin = headers["origin"]
            val userAgent = headers["user-agent"]
            val cookie = headers["cookie"]
            modifyM3u8Content(m3u8Content, url, port, referer, userAgent, origin, cookie)
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Upstream ${e.code} propagating from processM3u8Content for $url")
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8 URL: ${e.message}", e)
            throw UpstreamStatusException(503, url, "Error processing m3u8: ${e.message}", e)
        }
    }

    /**
     * Process segment with hybrid cache, in-flight deduplication, AES-128 key
     * caching, PNG container unwrapping, and junk-byte stripping.
     */
    suspend fun processSegmentUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        keyUrl: String? = null,
        iv: String? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val segKey = segmentCacheKey(url, keyUrl, iv)
        try {
            cache.getOrLoad(segKey) {
                val rawSegment = fetchSegmentBytesBlocking(url, headers)
                val plaintext = if (keyUrl != null && iv != null) {
                    val keyBytes = cache.getAesKey(keyUrl) ?: fetchSegmentBytesBlocking(keyUrl, headers).also {
                        cache.putAesKey(keyUrl, it)
                    }
                    decryptAes128Cbc(rawSegment, keyBytes, iv)
                } else {
                    rawSegment
                }
                val unwrapped = PngContainer.extractPayload(plaintext) ?: plaintext
                stripInterleavedJunk(unwrapped)
            }
        } catch (e: UpstreamStatusException) {
            Log.w(tag, "Segment fetch upstream ${e.code} for $url")
            throw e
        } catch (e: Exception) {
            val cause = e.cause
            if (cause is UpstreamStatusException) throw cause
            Log.e(tag, "Error processing segment URL: ${e.message}", e)
            throw UpstreamStatusException(503, url, "Error processing segment: ${e.message}", e)
        }
    }

    private fun fetchSegmentBytesBlocking(url: String, headers: Map<String, String>): ByteArray {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            if (!key.equals("range", ignoreCase = true)) {
                requestBuilder.addHeader(key, value)
            }
        }
        return executeWithFallback(requestBuilder.build(), url) { response ->
            response.body.bytes()
        }
    }

    private fun <T> executeWithFallback(request: Request, url: String, onSuccess: (okhttp3.Response) -> T): T = try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw UpstreamStatusException(response.code, url, "Upstream HTTP ${response.code}")
            }
            onSuccess(response)
        }
    } catch (primary: UpstreamStatusException) {
        throw primary
    } catch (primary: Exception) {
        val fb = fallbackClient ?: throw UpstreamStatusException(503, url, "Primary client failed: ${primary.message}", primary)
        try {
            fb.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw UpstreamStatusException(response.code, url, "Fallback HTTP ${response.code}: ${primary.message}", primary)
                }
                onSuccess(response)
            }
        } catch (e: UpstreamStatusException) {
            throw e
        } catch (e: Exception) {
            throw UpstreamStatusException(503, url, "Primary and fallback failed: ${e.message}", primary)
        }
    }

    private fun stripInterleavedJunk(fullData: ByteArray): ByteArray {
        val skipRanges = AutoDetector.detectInterleavedSkips(fullData)
        if (skipRanges.isEmpty()) return fullData
        val strippedBytes = skipRanges.sumOf { it.last - it.first + 1 }
        val finalSize = fullData.size - strippedBytes
        val stripped = ByteArrayOutputStream(finalSize.coerceAtLeast(0))
        var cursor = 0
        for (range in skipRanges) {
            if (range.first > cursor) {
                stripped.write(copyRegion(fullData, cursor, range.first - cursor))
            }
            cursor = range.last + 1
        }
        if (cursor < fullData.size) {
            stripped.write(copyRegion(fullData, cursor, fullData.size - cursor))
        }
        return stripped.toByteArray()
    }

    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) {
            throw IOException("Invalid AES-128 key length: ${key.size}")
        }
        val normalizedIv = iv.normalizeHlsIv()
        if (normalizedIv.length != 32) {
            throw IOException("Invalid AES-128 IV length: ${normalizedIv.length}")
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(normalizedIv.hexToByteArray()),
            )
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to decrypt AES-128 segment", e)
        } catch (e: NumberFormatException) {
            throw IOException("Invalid AES-128 IV", e)
        }
    }

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x").removePrefix("0X").padStart(32, '0')

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    fun getHealthStatus(): String = if (isRunning) {
        "M3U8 HTTP Server is running on port $port"
    } else {
        "M3U8 HTTP Server is not running"
    }

    private suspend fun fetchM3u8Content(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }
        executeWithFallback(requestBuilder.build(), url) { response ->
            val content = decodePlaylistBody(response.body.bytes())
            if (content.isBlank()) {
                throw UpstreamStatusException(502, url, "Empty response body")
            }
            content
        }
    }

    private fun decodePlaylistBody(bytes: ByteArray): String = (PngContainer.extractPayload(bytes) ?: bytes)
        .toString(Charsets.UTF_8)
        .removePrefix("\uFEFF")

    private fun copyRegion(src: ByteArray, off: Int, len: Int): ByteArray {
        if (off == 0 && len == src.size) return src
        if (len == 0) return ByteArray(0)
        val out = ByteArray(len)
        System.arraycopy(src, off, out, 0, len)
        return out
    }

    fun createLocalUrl(
        m3u8Url: String,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        cookie: String? = null,
    ): String {
        val sb = StringBuilder("http://localhost:$port/m3u8?url=")
            .append(URLEncoder.encode(m3u8Url, Charsets.UTF_8.name()))
        appendHeaderParams(sb, referer, origin, userAgent, cookie)
        return sb.toString()
    }

    fun createMediaUrl(
        mediaUrl: String,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        cookie: String? = null,
    ): String {
        val sb = StringBuilder("http://localhost:$port/media?url=")
            .append(URLEncoder.encode(mediaUrl, Charsets.UTF_8.name()))
        appendHeaderParams(sb, referer, origin, userAgent, cookie)
        return sb.toString()
    }

    private fun appendHeaderParams(
        sb: StringBuilder,
        referer: String?,
        origin: String?,
        userAgent: String?,
        cookie: String?,
    ) {
        if (!referer.isNullOrBlank()) {
            sb.append("&referer=").append(URLEncoder.encode(referer, Charsets.UTF_8.name()))
        }
        if (!origin.isNullOrBlank()) {
            sb.append("&origin=").append(URLEncoder.encode(origin, Charsets.UTF_8.name()))
        }
        if (!userAgent.isNullOrBlank()) {
            sb.append("&useragent=").append(URLEncoder.encode(userAgent, Charsets.UTF_8.name()))
        }
        if (!cookie.isNullOrBlank()) {
            sb.append("&cookie=").append(URLEncoder.encode(cookie, Charsets.UTF_8.name()))
        }
    }

    private fun modifyM3u8Content(
        content: String,
        originalUrl: String,
        serverPort: Int,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        cookie: String? = null,
    ): String {
        val lines = content.lines()
        val modifiedLines = mutableListOf<String>()
        val segmentTasks = mutableListOf<SegmentTask>()
        val playlistId = "p${playlistSeqCounter.incrementAndGet()}"
        var mediaSequence = 0L
        var segmentSequence = mediaSequence
        var currentKey: HlsKey? = null
        var expectVariantPlaylist = false

        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val taskHeaders = buildMap<String, String> {
            if (!userAgent.isNullOrBlank()) put("user-agent", userAgent)
            if (!referer.isNullOrBlank()) put("referer", referer)
            if (!origin.isNullOrBlank()) put("origin", origin)
            if (!cookie.isNullOrBlank()) put("cookie", cookie)
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
                    segmentSequence = mediaSequence
                    modifiedLines.add(line)
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseHlsAttributes(line)
                    when (attributes["METHOD"]?.uppercase()) {
                        "AES-128" -> {
                            val keyUri = attributes["URI"]
                            if (keyUri.isNullOrBlank()) {
                                currentKey = null
                                modifiedLines.add(line)
                            } else {
                                val resolvedKeyUrl = resolveHlsUrl(baseHttpUrl, keyUri)
                                val iv = attributes["IV"]
                                currentKey = HlsKey(
                                    url = resolvedKeyUrl,
                                    iv = iv?.let { it.normalizeHlsIv() } ?: segmentSequence.toHlsIv(),
                                )
                            }
                        }

                        "NONE" -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }

                        else -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                    }
                }

                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    expectVariantPlaylist = true
                    modifiedLines.add(line)
                }

                line.startsWith("#") || line.isBlank() -> {
                    modifiedLines.add(line)
                }

                else -> {
                    val resolvedUrl = resolveHlsUrl(baseHttpUrl, line.trim())
                    val isPlaylist = expectVariantPlaylist ||
                        resolvedUrl.contains(".m3u8", ignoreCase = true) ||
                        resolvedUrl.substringBefore('?').substringBefore('#').endsWith(".txt", ignoreCase = true)
                    expectVariantPlaylist = false
                    if (isPlaylist) {
                        modifiedLines.add(buildChildM3u8Url(serverPort, resolvedUrl, referer, userAgent, origin, cookie))
                    } else {
                        val segIdx = segmentTasks.size
                        segmentTasks.add(
                            SegmentTask(
                                url = resolvedUrl,
                                keyUrl = currentKey?.url,
                                iv = currentKey?.iv,
                                headers = taskHeaders,
                            ),
                        )
                        modifiedLines.add(
                            createLocalSegmentUrl(
                                serverPort = serverPort,
                                segmentUrl = resolvedUrl,
                                key = currentKey,
                                referer = referer,
                                userAgent = userAgent,
                                origin = origin,
                                cookie = cookie,
                                playlistId = playlistId,
                                segmentIndex = segIdx,
                            ),
                        )
                    }
                    segmentSequence++
                }
            }
        }

        if (segmentTasks.isNotEmpty()) {
            synchronized(playlistSegmentsLock) {
                playlistSegments[playlistId] = segmentTasks
            }
            // Warm up segment 0 immediately on playlist load for faster startup!
            scheduleInitialWarmup(segmentTasks.first())
        }

        return modifiedLines.joinToString("\n")
    }

    private fun scheduleInitialWarmup(firstSegment: SegmentTask) {
        if (!isRunning) return
        val segKey = segmentCacheKey(firstSegment.url, firstSegment.keyUrl, firstSegment.iv)
        if (cache.hasCached(segKey) || cache.isInFlight(segKey)) return
        runCatching {
            getPrefetchPool().submit {
                runCatching {
                    runBlocking {
                        processSegmentUrl(firstSegment.url, firstSegment.headers, firstSegment.keyUrl, firstSegment.iv)
                    }
                }
            }
        }
    }

    private fun buildChildM3u8Url(
        serverPort: Int,
        m3u8Url: String,
        referer: String?,
        userAgent: String?,
        origin: String? = null,
        cookie: String? = null,
    ): String {
        val sb = StringBuilder("http://localhost:$serverPort/m3u8?url=")
            .append(URLEncoder.encode(m3u8Url, Charsets.UTF_8.name()))
        appendHeaderParams(sb, referer, origin, userAgent, cookie)
        return sb.toString()
    }

    private data class HlsKey(val url: String, val iv: String)

    private val hlsAttributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun parseHlsAttributes(line: String): Map<String, String> = hlsAttributeRegex.findAll(line.substringAfter(":")).associate {
        it.groupValues[1] to it.groupValues[2].trim('"')
    }

    private fun resolveHlsUrl(baseHttpUrl: HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri

    private fun createLocalSegmentUrl(
        serverPort: Int,
        segmentUrl: String,
        key: HlsKey?,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        cookie: String? = null,
        playlistId: String? = null,
        segmentIndex: Int? = null,
    ): String {
        val encodedUrl = URLEncoder.encode(segmentUrl, Charsets.UTF_8.name())
        return buildString {
            append("http://localhost:$serverPort/segment?url=$encodedUrl")
            if (key != null) {
                append("&key=")
                append(URLEncoder.encode(key.url, Charsets.UTF_8.name()))
                append("&iv=")
                append(URLEncoder.encode(key.iv, Charsets.UTF_8.name()))
            }
            if (!playlistId.isNullOrBlank() && segmentIndex != null) {
                append("&pid=").append(playlistId)
                append("&idx=").append(segmentIndex)
            }
            appendHeaderParams(this, referer, origin, userAgent, cookie)
        }
    }

    private fun segmentCacheKey(url: String, keyUrl: String?, iv: String?): String = if (keyUrl != null && iv != null) "seg|$url|$keyUrl|$iv" else "seg|$url"

    private fun mediaBlockKey(url: String, blockIndex: Long): String = "mp4|$url|$blockIndex"

    private fun parseByteRange(rangeHeader: String?, totalSize: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank() || totalSize <= 0L) return null
        val trimmed = rangeHeader.trim()
        if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
        val spec = trimmed.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()
        return if (startPart.isEmpty()) {
            val suffix = endPart.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val start = (totalSize - suffix).coerceAtLeast(0L)
            start to (totalSize - 1L)
        } else {
            val start = startPart.toLongOrNull()?.takeIf { it >= 0L && it < totalSize } ?: return null
            val end = if (endPart.isEmpty()) {
                totalSize - 1L
            } else {
                endPart.toLongOrNull()?.coerceAtMost(totalSize - 1L) ?: return null
            }
            if (end < start) return null
            start to end
        }
    }

    private fun parseContentRangeTotal(header: String): Long? = header.substringAfterLast('/', "").trim().toLongOrNull()

    companion object {
        private const val PREFETCH_LOOKAHEAD = 2
        private const val MAX_PREFETCH_QUEUE = 12
        private const val MAX_TRACKED_PLAYLISTS = 16
        private const val MAX_MEDIA_META = 64
        private const val MEDIA_BLOCK_SIZE = 512L * 1024L // 512 KB aligned chunks
        private const val MEDIA_META_TTL_MS = 20L * 60L * 1000L
    }
}
