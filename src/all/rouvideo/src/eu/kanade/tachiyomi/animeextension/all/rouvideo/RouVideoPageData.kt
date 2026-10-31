package eu.kanade.tachiyomi.animeextension.all.rouvideo

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/**
 * rou.video moved from Next.js (`script#__NEXT_DATA__`) to TanStack Start, which
 * streams the route loader data as a seroval-serialized JS expression:
 * `$_TSR.router=($R=>$R[0]={...,matches:$R[n]=[...,{...,l:<loaderData>}]})($R["tsr"])`.
 *
 * The loader data has the same shape as the old `props.pageProps`, so [pageData]
 * re-wraps it as `{"props":{"pageProps":...}}` and the existing DTOs keep working.
 */
internal object RouVideoPageData {

    private val json = Json { ignoreUnknownKeys = true }

    fun Document.pageData(): String? {
        selectFirst("script#__NEXT_DATA__")?.data()?.let { return it }
        return loaderData()?.let { """{"props":{"pageProps":$it}}""" }
    }

    /** Leaf route loader data of the TanStack router state, as JSON. */
    fun Document.loaderData(): JsonObject? {
        val script = select("script").map { it.data() }
            .firstOrNull { it.contains("\$_TSR.router") }
            ?: return null
        val router = serovalToJson(script) ?: return null
        return runCatching {
            json.parseToJsonElement(router).jsonObject["matches"]!!.jsonArray
                .last().jsonObject["l"]!!.jsonObject
        }.getOrNull()
    }

    /**
     * The video page hides the stream location in `ev: {d, k}`:
     * base64 → shift every char code down by `k` → JSON `{videoUrl, thumbVTTUrl}`.
     */
    fun JsonObject.decodeVideoUrl(): String? {
        val ev = this["ev"] as? JsonObject ?: return null
        val data = ev["d"]?.jsonPrimitive?.contentOrNull ?: return null
        val shift = ev["k"]?.jsonPrimitive?.intOrNull ?: return null
        val decoded = String(Base64.decode(data, Base64.DEFAULT), Charsets.ISO_8859_1)
            .map { (it.code - shift).toChar() }
            .joinToString("")
        val obj: JsonElement = runCatching { json.parseToJsonElement(decoded) }.getOrNull() ?: return null
        return obj.jsonObject["videoUrl"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Converts the body of a seroval `($R=>...)($R[...])` expression to JSON:
     * drops `$R[n]=` assignments, turns back-references into `null`, quotes
     * bare keys, and maps `!0`/`!1`/`void 0` and JS-only string escapes.
     */
    fun serovalToJson(script: String): String? {
        val start = script.indexOf("(\$R=>").takeIf { it >= 0 }?.plus(5) ?: return null
        val end = script.lastIndexOf(")(\$R").takeIf { it > start } ?: return null
        val src = script.substring(start, end)
        val out = StringBuilder(src.length + 1024)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> i = copyString(src, i, out)

                src.startsWith("\$R[", i) -> {
                    var j = i + 3
                    while (j < src.length && src[j].isDigit()) j++
                    if (j < src.length && src[j] == ']') {
                        if (j + 1 < src.length && src[j + 1] == '=') {
                            i = j + 2
                        } else {
                            out.append("null")
                            i = j + 1
                        }
                    } else {
                        out.append(c)
                        i++
                    }
                }

                src.startsWith("!0", i) -> {
                    out.append("true")
                    i += 2
                }

                src.startsWith("!1", i) -> {
                    out.append("false")
                    i += 2
                }

                src.startsWith("void 0", i) -> {
                    out.append("null")
                    i += 6
                }

                (c.isLetter() || c == '_' || c == '$') && out.lastOrNull().let { it == '{' || it == ',' } -> {
                    var j = i + 1
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '$')) j++
                    val word = src.substring(i, j)
                    if (j < src.length && src[j] == ':') {
                        out.append('"').append(word).append('"')
                    } else {
                        out.append(word)
                    }
                    i = j
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Copies the JS string literal starting at [start] as a JSON string; returns the index after it. */
    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        out.append('"')
        var i = start + 1
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> {
                    out.append('"')
                    return i + 1
                }

                c == '\\' && i + 1 < src.length -> {
                    val e = src[i + 1]
                    when (e) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u' -> out.append('\\').append(e)
                        'x' -> out.append("\\u00").append(src, i + 2, minOf(i + 4, src.length)).also { i += 2 }
                        'v' -> out.append("\\u000b")
                        '0' -> out.append("\\u0000")
                        '\n' -> Unit
                        else -> out.append(e)
                    }
                    i += 2
                }

                c < ' ' -> {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        out.append('"')
        return i
    }
}
