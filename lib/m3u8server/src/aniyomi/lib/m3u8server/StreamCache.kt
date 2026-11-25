package aniyomi.lib.m3u8server

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/**
 * Thread-safe hybrid RAM + temp-disk LRU cache with TTL expiry and in-flight
 * download coalescing for HLS playlists, AES-128 keys, decoded TS segments,
 * and 512 KB MP4 byte-range blocks.
 *
 * Designed to stay well within Android's per-app memory budget while making
 * backward/forward seeking and scrubbing near-instant (< 2 ms on cache hit).
 */
internal class StreamCache(
    private val maxRamBytes: Long = DEFAULT_RAM_BYTES,
    private val maxDiskBytes: Long = DEFAULT_DISK_BYTES,
    private val segmentTtlMs: Long = DEFAULT_SEGMENT_TTL_MS,
    private val playlistTtlMs: Long = DEFAULT_PLAYLIST_TTL_MS,
    private val keyTtlMs: Long = DEFAULT_KEY_TTL_MS,
) {
    private val tag = "StreamCache"

    private data class RamEntry(
        val bytes: ByteArray,
        val expiresAt: Long,
    )

    private data class StringEntry(
        val value: String,
        val expiresAt: Long,
    )

    private data class KeyEntry(
        val bytes: ByteArray,
        val expiresAt: Long,
    )

    private val ramLock = Any()
    private val ramLru = LinkedHashMap<String, RamEntry>(32, 0.75f, true)
    private var currentRamBytes: Long = 0L

    private val playlistCache = ConcurrentHashMap<String, StringEntry>()
    private val aesKeyCache = ConcurrentHashMap<String, KeyEntry>()
    private val inFlight = ConcurrentHashMap<String, FutureTask<ByteArray>>()

    private val diskLock = Any()
    private val diskDir: File? by lazy {
        runCatching {
            val base = System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            File(base, CACHE_DIR_NAME).apply {
                if (!exists()) mkdirs()
            }.takeIf { it.isDirectory && it.canWrite() }
        }.getOrNull()
    }

    init {
        // Best-effort cleanup of stale files left over from prior sessions.
        runCatching { sweepDiskExpired() }
    }

    // ========================== Playlist & Key ============================

    fun getPlaylist(cacheKey: String): String? {
        val entry = playlistCache[cacheKey] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            playlistCache.remove(cacheKey, entry)
            return null
        }
        return entry.value
    }

    fun putPlaylist(cacheKey: String, content: String) {
        if (playlistCache.size > MAX_PLAYLIST_ENTRIES) {
            val now = System.currentTimeMillis()
            playlistCache.entries.removeIf { now > it.value.expiresAt }
            if (playlistCache.size > MAX_PLAYLIST_ENTRIES) {
                playlistCache.clear()
            }
        }
        playlistCache[cacheKey] = StringEntry(content, System.currentTimeMillis() + playlistTtlMs)
    }

    fun getAesKey(keyUrl: String): ByteArray? {
        val entry = aesKeyCache[keyUrl] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            aesKeyCache.remove(keyUrl, entry)
            return null
        }
        return entry.bytes
    }

    fun putAesKey(keyUrl: String, keyBytes: ByteArray) {
        if (aesKeyCache.size > MAX_KEY_ENTRIES) {
            val now = System.currentTimeMillis()
            aesKeyCache.entries.removeIf { now > it.value.expiresAt }
            if (aesKeyCache.size > MAX_KEY_ENTRIES) {
                aesKeyCache.clear()
            }
        }
        aesKeyCache[keyUrl] = KeyEntry(keyBytes, System.currentTimeMillis() + keyTtlMs)
    }

    // ====================== Segment / Block Bytes =========================

    fun hasCached(key: String): Boolean {
        if (getFromRam(key) != null) return true
        val file = diskFileFor(key) ?: return false
        if (!file.isFile) return false
        if (System.currentTimeMillis() - file.lastModified() > segmentTtlMs) {
            runCatching { file.delete() }
            return false
        }
        return true
    }

    fun isInFlight(key: String): Boolean = inFlight.containsKey(key)

    fun getBytes(key: String): ByteArray? {
        getFromRam(key)?.let { return it }
        val fromDisk = getFromDisk(key) ?: return null
        // Promote hot disk hit back into RAM tier.
        putInRam(key, fromDisk)
        return fromDisk
    }

    fun putBytes(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_SINGLE_ITEM_BYTES) return
        putInRam(key, bytes)
        putOnDisk(key, bytes)
    }

    /**
     * Returns cached bytes if present, otherwise coalesces concurrent callers
     * for the same [key] onto a single upstream [loader] execution.
     */
    fun getOrLoad(key: String, loader: () -> ByteArray): ByteArray {
        getBytes(key)?.let { return it }

        val newTask = FutureTask(Callable { loader() })
        val existing = inFlight.putIfAbsent(key, newTask)
        val task = existing ?: newTask

        if (existing == null) {
            try {
                task.run()
                val loaded = task.get()
                putBytes(key, loaded)
                return loaded
            } finally {
                inFlight.remove(key, task)
            }
        } else {
            return try {
                task.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    fun clearRam() {
        synchronized(ramLock) {
            ramLru.clear()
            currentRamBytes = 0L
        }
        playlistCache.clear()
        aesKeyCache.clear()
    }

    // ========================== Internal Tiers ============================

    private fun getFromRam(key: String): ByteArray? {
        val now = System.currentTimeMillis()
        synchronized(ramLock) {
            val entry = ramLru[key] ?: return null
            if (now > entry.expiresAt) {
                ramLru.remove(key)
                currentRamBytes = (currentRamBytes - entry.bytes.size).coerceAtLeast(0L)
                return null
            }
            return entry.bytes
        }
    }

    private fun putInRam(key: String, bytes: ByteArray) {
        if (bytes.size > maxRamBytes / 2) return
        val expiresAt = System.currentTimeMillis() + segmentTtlMs
        synchronized(ramLock) {
            ramLru.remove(key)?.let { old ->
                currentRamBytes = (currentRamBytes - old.bytes.size).coerceAtLeast(0L)
            }
            ramLru[key] = RamEntry(bytes, expiresAt)
            currentRamBytes += bytes.size

            val it = ramLru.entries.iterator()
            while (currentRamBytes > maxRamBytes && it.hasNext()) {
                val evicted = it.next()
                it.remove()
                currentRamBytes = (currentRamBytes - evicted.value.bytes.size).coerceAtLeast(0L)
            }
        }
    }

    private fun getFromDisk(key: String): ByteArray? {
        val file = diskFileFor(key) ?: return null
        return runCatching {
            if (!file.isFile) return null
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > segmentTtlMs) {
                file.delete()
                return null
            }
            val data = file.readBytes()
            // Touch mtime so LRU eviction keeps recently revisited segments.
            file.setLastModified(now)
            data
        }.getOrNull()
    }

    private fun putOnDisk(key: String, bytes: ByteArray) {
        val dir = diskDir ?: return
        val target = File(dir, hashKey(key) + CACHE_EXT)
        runCatching {
            synchronized(diskLock) {
                val tmp = File(dir, "${target.name}.${Thread.currentThread().id}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
                enforceDiskQuota(dir)
            }
        }.onFailure { e ->
            Log.w(tag, "Disk cache write skipped: ${e.message}")
        }
    }

    private fun sweepDiskExpired() {
        val dir = diskDir ?: return
        val now = System.currentTimeMillis()
        synchronized(diskLock) {
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (!file.isFile) continue
                if (file.name.endsWith(".tmp") || now - file.lastModified() > segmentTtlMs) {
                    file.delete()
                }
            }
            enforceDiskQuota(dir)
        }
    }

    private fun enforceDiskQuota(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(CACHE_EXT) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskBytes) return
        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        for (file in sortedOldestFirst) {
            if (total <= maxDiskBytes * 8 / 10) break
            val len = file.length()
            if (file.delete()) {
                total -= len
            }
        }
    }

    private fun diskFileFor(key: String): File? {
        val dir = diskDir ?: return null
        return File(dir, hashKey(key) + CACHE_EXT)
    }

    private fun hashKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        val digits = HEX_DIGITS
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    companion object {
        private const val CACHE_DIR_NAME = "aniyomi_stream_cache"
        private const val CACHE_EXT = ".bin"
        private const val MAX_PLAYLIST_ENTRIES = 64
        private const val MAX_KEY_ENTRIES = 64
        private const val MAX_SINGLE_ITEM_BYTES = 16 * 1024 * 1024 // 16 MB safety ceiling per segment

        const val DEFAULT_RAM_BYTES = 16L * 1024L * 1024L // 16 MB hot RAM LRU
        const val DEFAULT_DISK_BYTES = 96L * 1024L * 1024L // 96 MB warm disk LRU
        const val DEFAULT_SEGMENT_TTL_MS = 20L * 60L * 1000L // 20 minutes
        const val DEFAULT_PLAYLIST_TTL_MS = 5L * 60L * 1000L // 5 minutes
        const val DEFAULT_KEY_TTL_MS = 30L * 60L * 1000L // 30 minutes

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
