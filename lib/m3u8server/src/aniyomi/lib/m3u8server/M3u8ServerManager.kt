package aniyomi.lib.m3u8server

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

/**
 * M3U8 & Media Caching Server manager to facilitate usage across extensions.
 */
class M3u8ServerManager(
    private val client: OkHttpClient,
    private val fallbackClient: OkHttpClient? = null,
) {
    private val tag by lazy { javaClass.simpleName }
    private var server: M3u8HttpServer? = null

    /**
     * Starts the local HTTP proxy server on the specified port (0 = random port).
     */
    @Synchronized
    fun startServer(port: Int = 0) {
        if (server != null) {
            Log.d(tag, "Server is already running")
            return
        }

        try {
            server = M3u8HttpServer(client, port, fallbackClient)
            server?.start()
            Log.d(tag, "Server started on port: ${server?.port}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            server = null
            throw e
        }
    }

    /**
     * Stops the local HTTP server and releases its prefetch threads and RAM cache.
     */
    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = server?.isRunning() ?: false

    fun getServerUrl(): String? = server?.let { "http://localhost:${it.port}" }

    /**
     * Processes an M3U8 playlist URL through the caching/prefetching local server.
     */
    @JvmOverloads
    fun processM3u8Url(
        m3u8Url: String,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        cookie: String? = null,
    ): String? {
        if (!isRunning()) startServer()
        return server?.createLocalUrl(m3u8Url, referer, userAgent, origin, cookie)
    }

    /**
     * Wraps a direct progressive media URL (e.g. MP4) through the local `/media`
     * 512 KB aligned byte-range block cache so seeking/rewinding replays locally.
     */
    @JvmOverloads
    fun processMediaUrl(
        mediaUrl: String,
        referer: String? = null,
        userAgent: String? = null,
        origin: String? = null,
        cookie: String? = null,
    ): String? {
        if (!isRunning()) startServer()
        return server?.createMediaUrl(mediaUrl, referer, userAgent, origin, cookie)
    }

    suspend fun processSegmentUrl(segmentUrl: String, headers: Map<String, String> = emptyMap()): ByteArray? = server?.processSegmentUrl(segmentUrl, headers)

    /**
     * Routes eligible HLS (`.m3u8`) and/or progressive MP4/WebM streams in [videos]
     * through the local caching/prefetching proxy while leaving localhost URLs,
     * DASH `.mpd`, and magnet links untouched.
     */
    @JvmOverloads
    fun wrapVideosForCache(
        videos: List<Video>,
        cacheHls: Boolean = true,
        cacheMp4: Boolean = true,
        defaultReferer: String? = null,
        defaultOrigin: String? = null,
        defaultUserAgent: String? = null,
    ): List<Video> {
        if ((!cacheHls && !cacheMp4) || videos.isEmpty()) return videos
        return videos.map { video ->
            val rawUrl = (video.videoUrl ?: video.url).trim()
            if (rawUrl.isBlank() ||
                rawUrl.startsWith("http://localhost:", ignoreCase = true) ||
                rawUrl.startsWith("http://127.0.0.1:", ignoreCase = true) ||
                rawUrl.startsWith("magnet:", ignoreCase = true) ||
                rawUrl.contains(".mpd", ignoreCase = true)
            ) {
                return@map video
            }

            val lower = rawUrl.lowercase()
            val isHls = lower.contains(".m3u8") || lower.contains("application/vnd.apple.mpegurl")
            val isProgressive = !isHls && (lower.startsWith("http://") || lower.startsWith("https://"))

            val referer = video.headers?.get("Referer") ?: video.headers?.get("referer") ?: defaultReferer
            val origin = video.headers?.get("Origin") ?: video.headers?.get("origin") ?: defaultOrigin
            val userAgent = video.headers?.get("User-Agent") ?: video.headers?.get("user-agent") ?: defaultUserAgent
            val cookie = video.headers?.get("Cookie") ?: video.headers?.get("cookie")

            val proxied = when {
                isHls && cacheHls -> processM3u8Url(rawUrl, referer, userAgent, origin, cookie)
                isProgressive && cacheMp4 -> processMediaUrl(rawUrl, referer, userAgent, origin, cookie)
                else -> null
            } ?: return@map video

            Video(
                url = video.url,
                quality = video.quality,
                videoUrl = proxied,
                headers = video.headers,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }
    }

    fun getServerInfo(): String = if (isRunning()) {
        val serverUrl = getServerUrl() ?: "Unknown"
        """
            M3U8 HTTP Server is running
            Base URL: $serverUrl
            Status: ${server?.getHealthStatus()}
        """.trimIndent()
    } else {
        "M3U8 HTTP Server is not running"
    }
}
