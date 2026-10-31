package aniyomi.lib.m3u8server

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Some CDNs (e.g. rou.video) ship both the playlist and every segment as a
 * valid PNG image whose real payload lives in a private ancillary chunk
 * (`roUd`). The chunk body starts with a one-byte flag: `0` = raw bytes,
 * `1` = zlib-deflated bytes.
 *
 * Unlike the fake-header cases handled by [AutoDetector], the payload sits
 * *inside* the PNG chunk stream (with a CRC and `IEND` after it), so it has
 * to be cut out chunk-wise rather than by skipping a junk prefix.
 */
internal object PngContainer {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    private val STANDARD_CHUNKS = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB",
        "cICP", "mDCV", "cLLI", "tEXt", "zTXt", "iTXt", "bKGD", "hIST", "pHYs", "sPLT",
        "eXIf", "tIME", "acTL", "fcTL", "fdAT",
    )

    private const val FLAG_RAW = 0
    private const val FLAG_DEFLATE = 1

    /**
     * Returns the media payload hidden in a private chunk of [data], or null
     * when [data] is not such a container (callers then use [data] as-is).
     */
    fun extractPayload(data: ByteArray): ByteArray? {
        if (data.size < SIGNATURE.size || SIGNATURE.indices.any { data[it] != SIGNATURE[it] }) return null

        var offset = SIGNATURE.size
        while (offset + 12 <= data.size) {
            val length = readInt(data, offset)
            val bodyStart = offset + 8
            val bodyEnd = bodyStart + length
            if (length < 0 || bodyEnd + 4 > data.size) return null

            val type = String(data, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") return null

            if (type !in STANDARD_CHUNKS && length > 1) {
                val body = data.copyOfRange(bodyStart + 1, bodyEnd)
                val payload = when (data[bodyStart].toInt()) {
                    FLAG_RAW -> body
                    FLAG_DEFLATE -> inflate(body)
                    else -> null
                }
                if (payload != null && looksLikeMedia(payload)) return payload
            }
            offset = bodyEnd + 4 // skip CRC
        }
        return null
    }

    /** MPEG-TS sync byte, an HLS playlist, or an fMP4 box header. */
    private fun looksLikeMedia(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        if (payload[0] == 0x47.toByte()) return true
        if (payload.size >= 7 && String(payload, 0, 7, Charsets.US_ASCII) == "#EXTM3U") return true
        return payload.size >= 8 && String(payload, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof")
    }

    private fun inflate(body: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(body)
            val out = ByteArrayOutputStream(body.size * 4)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xFF shl 24) or
        (data[offset + 1].toInt() and 0xFF shl 16) or
        (data[offset + 2].toInt() and 0xFF shl 8) or
        (data[offset + 3].toInt() and 0xFF)
}
