package aniyomi.lib.m3u8server

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.pow

/**
 * Privacy-first, 100% on-device behavioral taste & recommendation engine shared
 * across extensions.
 *
 * Uses a shared process-wide preference store (`agy_global_taste_v1`) so titles,
 * tags, topics, and performers learned in one extension (e.g. PornHub or SexTB)
 * automatically enrich recommendations across all extensions using this engine,
 * while keeping site-specific category IDs/paths cleanly namespaced per extension.
 *
 * Learns automatically from standard extension callbacks (no host-app modifications):
 * - Saved library refreshes / detail views (`recordBrowse`)
 * - Video playback (`recordPlay` — strongest signal + watched-URL deduplication)
 * - Manual filter & search choices (`recordSearchOrFilter`)
 * - Optional user-supplied seed titles/keywords in Extension Settings (`applySeedText`)
 */
class SmartTasteEngine(
    private val localPreferences: SharedPreferences,
    private val sourceKey: String = "default",
) {
    private val appContext: Application by injectLazy()

    // Shared global store across all extensions in the host app process (falls back to localPreferences).
    private val preferences: SharedPreferences by lazy {
        runCatching {
            appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrDefault(localPreferences)
    }

    private val storageKey: String = DEFAULT_STORAGE_KEY
    private val localCategoryStorageKey: String = "smart_taste_cats_$sourceKey"

    data class ProfileSnapshot(
        val topPerformers: List<String>,
        val topTags: List<String>,
        val topKeywords: List<String>,
        val topBigrams: List<String>,
        val topStudios: List<String>,
        val topCategories: List<String>,
        val watchedCount: Int,
        val totalInteractions: Int,
    )

    private val lock = Any()

    private val keywords = LinkedHashMap<String, Double>()
    private val bigrams = LinkedHashMap<String, Double>()
    private val tags = LinkedHashMap<String, Double>()
    private val performers = LinkedHashMap<String, Double>()
    private val studios = LinkedHashMap<String, Double>()
    private val categories = LinkedHashMap<String, Double>()
    private val watchedUrls = LinkedHashSet<String>()
    private val recentBrowseAtMs = HashMap<String, Long>()
    private val recentMetaByUrl = ConcurrentHashMap<String, CachedVideoMeta>()

    private data class CachedVideoMeta(
        val title: String?,
        val tags: List<String>,
        val performers: List<String>,
        val studios: List<String>,
        val categories: List<String>,
    )

    fun isEnabled(): Boolean = localPreferences.getBoolean(PREF_SMART_TASTE_KEY, true)

    fun syncSeedFromPreferences() {
        applySeedText(localPreferences.getString(PREF_TASTE_SEED_KEY, null))
    }

    fun rerankPage(page: AnimesPage): AnimesPage {
        if (!isEnabled() || page.animes.size < 3 || !hasLearnedProfile()) return page
        return AnimesPage(
            rankCandidates(page.animes, excludeWatched = false, blendWithOriginalRank = true),
            page.hasNextPage,
        )
    }

    fun onSearchResult(page: Int, query: String, result: AnimesPage): AnimesPage {
        if (!isEnabled()) return result
        val clean = query.trim()
        if (clean.isBlank()) return rerankPage(result)
        if (page == 1 && clean.length in 2..50 &&
            !clean.startsWith("Label:", true) &&
            !clean.startsWith("Studio:", true) &&
            !clean.startsWith("Uploader:", true) &&
            !clean.startsWith("Duration:", true) &&
            !clean.startsWith("Views:", true) &&
            !clean.startsWith("Rating:", true)
        ) {
            recordSearchOrFilter(query = clean)
        }
        return result
    }

    fun recordDetails(sourceAnime: SAnime, parsedDetails: SAnime) {
        if (!isEnabled()) return
        val title = parsedDetails.title.ifBlank { sourceAnime.title }.takeIf(String::isNotBlank)
        val tagsList = parsedDetails.genre?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        val perfList = parsedDetails.artist?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        val studioList = parsedDetails.author?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        val url = sourceAnime.url.ifBlank { parsedDetails.url }
        rememberMeta(url, title, tagsList, perfList, studioList, emptyList())
        recordBrowse(
            url = url,
            title = title,
            tagsList = tagsList,
            performersList = perfList,
            studiosList = studioList,
        )
    }

    fun recordLibraryAnime(anime: SAnime) {
        if (!isEnabled() || anime.title.isBlank()) return
        val tagsList = anime.genre?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        val perfList = anime.artist?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        val studioList = anime.author?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        rememberMeta(anime.url, anime.title, tagsList, perfList, studioList, emptyList())
        recordBrowse(
            url = anime.url,
            title = anime.title,
            tagsList = tagsList,
            performersList = perfList,
            studiosList = studioList,
        )
    }

    fun recordPlayByUrl(url: String?, fallbackTitle: String? = null) {
        if (!isEnabled()) return
        val key = normalizeUrlKey(url)
        val meta = key?.let { recentMetaByUrl[it] }
        recordPlay(
            url = url,
            title = meta?.title ?: fallbackTitle,
            tagsList = meta?.tags.orEmpty(),
            performersList = meta?.performers.orEmpty(),
            studiosList = meta?.studios.orEmpty(),
            categoriesList = meta?.categories.orEmpty(),
        )
    }

    fun attachPreferences(screen: PreferenceScreen) {
        syncSeedFromPreferences()
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SMART_TASTE_KEY
            title = "Smart Taste learning & re-rank (shared +18)"
            summary = "Shares one unified +18 taste profile across extensions from Library updates, opens, plays, and searches."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TASTE_SEED_KEY
            title = "Seed taste from favorite titles / keywords"
            dialogTitle = "Paste favorite titles, stars, or keywords"
            dialogMessage = "Separate entries by commas or new lines. Shared automatically across all +18 Smart Taste extensions."
            summary = summaryText()
            setOnPreferenceChangeListener { pref, newValue ->
                val text = newValue as? String
                applySeedText(text)
                pref.summary = summaryText()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TASTE_RESET_KEY
            title = "Reset shared Smart Taste profile"
            summary = "Tap to clear all learned titles, tags, stars, and watched history."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) {
                    reset()
                    localPreferences.edit().remove(PREF_TASTE_SEED_KEY).apply()
                }
                false
            }
        }.also(screen::addPreference)
    }

    private fun rememberMeta(
        url: String?,
        title: String?,
        tagsList: List<String>,
        performersList: List<String>,
        studiosList: List<String>,
        categoriesList: List<String>,
    ) {
        val key = normalizeUrlKey(url) ?: return
        if (recentMetaByUrl.size > 200) recentMetaByUrl.clear()
        recentMetaByUrl[key] = CachedVideoMeta(title, tagsList, performersList, studiosList, categoriesList)
    }

    private var lastDecayDay: Long = currentEpochDay()
    private var totalInteractions: Int = 0
    private var lastAppliedSeed: String = ""
    private var loaded: Boolean = false

    fun hasLearnedProfile(): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        performers.isNotEmpty() || tags.isNotEmpty() || keywords.isNotEmpty() || categories.isNotEmpty()
    }

    fun snapshot(): ProfileSnapshot = synchronized(lock) {
        ensureLoadedLocked()
        ProfileSnapshot(
            topPerformers = topKeys(performers, 8),
            topTags = topKeys(tags, 10),
            topKeywords = topKeys(keywords, 12),
            topBigrams = topKeys(bigrams, 8),
            topStudios = topKeys(studios, 6),
            topCategories = topKeys(categories, 6),
            watchedCount = watchedUrls.size,
            totalInteractions = totalInteractions,
        )
    }

    fun summaryText(): String {
        val snap = snapshot()
        if (snap.totalInteractions == 0 && snap.topKeywords.isEmpty() && snap.topTags.isEmpty()) {
            return "No taste learned yet. Open/play videos, run Library Update, or paste favorite titles/keywords below."
        }
        val parts = buildList {
            if (snap.topPerformers.isNotEmpty()) add("Top stars: ${snap.topPerformers.take(3).joinToString(", ")}")
            if (snap.topTags.isNotEmpty()) add("Tags: ${snap.topTags.take(4).joinToString(", ")}")
            if (snap.topBigrams.isNotEmpty()) {
                add("Topics: ${snap.topBigrams.take(3).joinToString(", ")}")
            } else if (snap.topKeywords.isNotEmpty()) {
                add("Keywords: ${snap.topKeywords.take(4).joinToString(", ")}")
            }
            add("Watched: ${snap.watchedCount}")
        }
        return parts.joinToString(" | ")
    }

    fun isWatched(url: String?): Boolean {
        val key = normalizeUrlKey(url) ?: return false
        return synchronized(lock) {
            ensureLoadedLocked()
            key in watchedUrls
        }
    }

    fun recordBrowse(
        url: String?,
        title: String?,
        tagsList: Collection<String> = emptyList(),
        performersList: Collection<String> = emptyList(),
        studiosList: Collection<String> = emptyList(),
        categoriesList: Collection<String> = emptyList(),
    ) {
        val urlKey = normalizeUrlKey(url)
        synchronized(lock) {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            if (urlKey != null) {
                val prev = recentBrowseAtMs[urlKey]
                if (prev != null && now - prev < BROWSE_DEDUP_WINDOW_MS) return
                if (recentBrowseAtMs.size > 300) recentBrowseAtMs.clear()
                recentBrowseAtMs[urlKey] = now
            }
            applyDecayIfNeededLocked()
            absorbSignalsLocked(
                title = title,
                tagsList = tagsList,
                performersList = performersList,
                studiosList = studiosList,
                categoriesList = categoriesList,
                weight = WEIGHT_BROWSE,
            )
            totalInteractions++
            persistLocked()
        }
    }

    fun recordPlay(
        url: String?,
        title: String?,
        tagsList: Collection<String> = emptyList(),
        performersList: Collection<String> = emptyList(),
        studiosList: Collection<String> = emptyList(),
        categoriesList: Collection<String> = emptyList(),
    ) {
        val urlKey = normalizeUrlKey(url)
        synchronized(lock) {
            ensureLoadedLocked()
            applyDecayIfNeededLocked()
            if (urlKey != null) {
                watchedUrls.remove(urlKey)
                watchedUrls.add(urlKey)
                while (watchedUrls.size > MAX_WATCHED_URLS) {
                    val first = watchedUrls.iterator().next()
                    watchedUrls.remove(first)
                }
            }
            absorbSignalsLocked(
                title = title,
                tagsList = tagsList,
                performersList = performersList,
                studiosList = studiosList,
                categoriesList = categoriesList,
                weight = WEIGHT_PLAY,
            )
            totalInteractions++
            persistLocked()
        }
    }

    fun recordSearchOrFilter(
        query: String? = null,
        tagsList: Collection<String> = emptyList(),
        performersList: Collection<String> = emptyList(),
        studiosList: Collection<String> = emptyList(),
        categoriesList: Collection<String> = emptyList(),
    ) {
        synchronized(lock) {
            ensureLoadedLocked()
            applyDecayIfNeededLocked()
            val cleanQ = query?.trim().orEmpty()
            // Ignore giant auto-generated recommendation queries from host fallback engines.
            val titleSignal = if (cleanQ.length in 2..65 && !cleanQ.startsWith("Label:", true)) cleanQ else null
            if (titleSignal == null && tagsList.isEmpty() && performersList.isEmpty() && studiosList.isEmpty() && categoriesList.isEmpty()) {
                return
            }
            absorbSignalsLocked(
                title = titleSignal,
                tagsList = tagsList,
                performersList = performersList,
                studiosList = studiosList,
                categoriesList = categoriesList,
                weight = WEIGHT_FILTER,
            )
            totalInteractions++
            persistLocked()
        }
    }

    /**
     * Parses user-provided favorite titles, keywords, or performer names (newline or comma separated)
     * from Extension Settings so the engine immediately understands their stored titles.
     */
    fun applySeedText(rawSeed: String?) {
        val cleaned = rawSeed?.trim().orEmpty()
        synchronized(lock) {
            ensureLoadedLocked()
            if (cleaned.isBlank() || cleaned == lastAppliedSeed) return
            applyDecayIfNeededLocked()
            val lines = cleaned.split('\n', ';', ',')
                .map(String::trim)
                .filter { it.length >= 2 }
                .take(80)
            for (line in lines) {
                absorbSignalsLocked(
                    title = line,
                    tagsList = if (line.split(' ').size <= 3) listOf(line) else emptyList(),
                    performersList = emptyList(),
                    studiosList = emptyList(),
                    categoriesList = emptyList(),
                    weight = WEIGHT_SEED,
                )
            }
            lastAppliedSeed = cleaned
            totalInteractions += lines.size
            persistLocked()
        }
    }

    fun reset() {
        synchronized(lock) {
            keywords.clear()
            bigrams.clear()
            tags.clear()
            performers.clear()
            studios.clear()
            categories.clear()
            watchedUrls.clear()
            recentBrowseAtMs.clear()
            totalInteractions = 0
            lastAppliedSeed = ""
            lastDecayDay = currentEpochDay()
            loaded = true
            preferences.edit().remove(storageKey).apply()
            localPreferences.edit().remove(localCategoryStorageKey).apply()
        }
    }

    /**
     * Scores a candidate listing card against the user's learned taste profile.
     * Higher score = stronger match. Already-watched items receive a heavy penalty.
     */
    fun scoreCandidate(
        title: String,
        url: String,
        extraTags: Collection<String> = emptyList(),
        extraPerformers: Collection<String> = emptyList(),
    ): Double = synchronized(lock) {
        ensureLoadedLocked()
        val urlKey = normalizeUrlKey(url)
        if (urlKey != null && urlKey in watchedUrls) {
            return@synchronized -1_000.0
        }

        val lowerTitle = title.lowercase(Locale.ROOT)
        val tokens = tokenizeTitle(title)
        val titleBigrams = extractBigrams(tokens)

        var score = 0.0

        // 1. Performer / Actress / Pornstar match (strongest signal)
        for ((name, weight) in performers) {
            if (name.length >= 4 && lowerTitle.contains(name)) {
                score += logWeight(weight) * 3.4
            }
        }
        for (p in extraPerformers) {
            val norm = normalizeEntity(p) ?: continue
            performers[norm]?.let { score += logWeight(it) * 3.6 }
        }

        // 2. Tag / Genre match
        for ((tag, weight) in tags) {
            if (tag.length >= 3 && lowerTitle.contains(tag)) {
                score += logWeight(weight) * 2.1
            }
        }
        for (t in extraTags) {
            val norm = normalizeEntity(t) ?: continue
            tags[norm]?.let { score += logWeight(it) * 2.4 }
        }

        // 3. Title bigram match (captures exact phrases like "big tits", "married woman", "step mom")
        for (bg in titleBigrams) {
            bigrams[bg]?.let { score += logWeight(it) * 2.3 }
        }

        // 4. Title keyword match
        val matchedKeywords = HashSet<String>()
        for (tok in tokens) {
            if (matchedKeywords.add(tok)) {
                keywords[tok]?.let { score += logWeight(it) * 1.35 }
            }
        }

        // 5. Studio / Channel match
        for ((st, weight) in studios) {
            if (st.length >= 3 && lowerTitle.contains(st)) {
                score += logWeight(weight) * 1.6
            }
        }

        score
    }

    /**
     * Re-ranks a list of candidates by taste score while preserving natural order among ties
     * and optionally filtering out already-watched URLs (for `For You` feeds).
     */
    fun rankCandidates(
        items: List<SAnime>,
        excludeWatched: Boolean = false,
        blendWithOriginalRank: Boolean = false,
    ): List<SAnime> {
        if (items.isEmpty() || !hasLearnedProfile()) return items

        val filtered = if (excludeWatched) {
            items.filterNot { isWatched(it.url) }.ifEmpty { items }
        } else {
            items
        }

        return filtered.mapIndexed { idx, anime ->
            val taste = scoreCandidate(anime.title, anime.url)
            val finalScore = if (blendWithOriginalRank && taste >= 0.0) {
                // Gentle boost on normal Popular/Latest so fresh top items stay competitive
                // while strong taste matches rise noticeably to the top.
                val positionBonus = (filtered.size - idx).toDouble() / filtered.size.coerceAtLeast(1) * 2.5
                taste + positionBonus
            } else {
                taste
            }
            anime to finalScore
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Builds concise search queries from learned title bigrams/keywords for `For You` discovery.
     */
    fun buildTitleSearchQueries(maxQueries: Int = 2): List<String> = synchronized(lock) {
        ensureLoadedLocked()
        val queries = mutableListOf<String>()
        val topBg = topKeys(bigrams, 4)
        for (bg in topBg) {
            if (queries.size >= maxQueries) break
            queries += bg
        }
        if (queries.size < maxQueries) {
            val topKw = topKeys(keywords, 6).filterNot { kw -> queries.any { kw in it } }
            if (topKw.size >= 2) {
                queries += "${topKw[0]} ${topKw[1]}"
            } else if (topKw.size == 1) {
                queries += topKw[0]
            }
        }
        queries.distinct().take(maxQueries)
    }

    private fun absorbSignalsLocked(
        title: String?,
        tagsList: Collection<String>,
        performersList: Collection<String>,
        studiosList: Collection<String>,
        categoriesList: Collection<String>,
        weight: Double,
    ) {
        if (!title.isNullOrBlank()) {
            val tokens = tokenizeTitle(title)
            val seenTok = HashSet<String>()
            for (tok in tokens) {
                if (seenTok.add(tok)) {
                    bump(keywords, tok, weight * 0.85, MAX_KEYWORDS)
                }
            }
            val seenBg = HashSet<String>()
            for (bg in extractBigrams(tokens)) {
                if (seenBg.add(bg)) {
                    bump(bigrams, bg, weight * 1.15, MAX_BIGRAMS)
                }
            }
        }

        for (rawTag in tagsList) {
            val norm = normalizeEntity(rawTag) ?: continue
            if (norm in GENERIC_TAG_STOPWORDS) continue
            bump(tags, norm, weight, MAX_TAGS)
        }
        for (rawPerf in performersList) {
            val norm = normalizeEntity(rawPerf) ?: continue
            if (norm.length < 3 || norm in GENERIC_TAG_STOPWORDS) continue
            bump(performers, norm, weight * 1.25, MAX_PERFORMERS)
        }
        for (rawStudio in studiosList) {
            val norm = normalizeEntity(rawStudio) ?: continue
            if (norm.length < 2 || norm in GENERIC_TAG_STOPWORDS) continue
            bump(studios, norm, weight, MAX_STUDIOS)
        }
        for (rawCat in categoriesList) {
            val clean = rawCat.trim()
            if (clean.isBlank()) continue
            bump(categories, clean, weight, MAX_CATEGORIES)
        }
    }

    private fun bump(map: LinkedHashMap<String, Double>, key: String, delta: Double, maxSize: Int) {
        val updated = ((map[key] ?: 0.0) + delta).coerceAtMost(MAX_WEIGHT)
        map[key] = updated
        if (map.size > maxSize + 15) {
            val keep = map.entries.sortedByDescending { it.value }.take(maxSize)
            map.clear()
            for ((k, v) in keep) map[k] = v
        }
    }

    private fun applyDecayIfNeededLocked() {
        val today = currentEpochDay()
        val elapsed = (today - lastDecayDay).coerceAtLeast(0L)
        if (elapsed <= 0L) return
        val factor = DAILY_DECAY_FACTOR.pow(elapsed.coerceAtMost(60L).toDouble())
        decayMap(keywords, factor)
        decayMap(bigrams, factor)
        decayMap(tags, factor)
        decayMap(performers, factor)
        decayMap(studios, factor)
        decayMap(categories, factor)
        lastDecayDay = today
    }

    private fun decayMap(map: LinkedHashMap<String, Double>, factor: Double) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val decayed = entry.value * factor
            if (decayed < MIN_WEIGHT_FLOOR) {
                it.remove()
            } else {
                entry.setValue(decayed)
            }
        }
    }

    private fun topKeys(map: Map<String, Double>, limit: Int): List<String> = map.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }

    private fun logWeight(w: Double): Double = ln(1.0 + w.coerceAtLeast(0.0))

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = preferences.getString(storageKey, null)?.takeIf(String::isNotBlank) ?: return@runCatching
            val root = JSONObject(raw)
            lastDecayDay = root.optLong("day", currentEpochDay())
            totalInteractions = root.optInt("n", 0)
            lastAppliedSeed = root.optString("seed", "")
            readWeightMap(root.optJSONObject("kw"), keywords)
            readWeightMap(root.optJSONObject("bg"), bigrams)
            readWeightMap(root.optJSONObject("tg"), tags)
            readWeightMap(root.optJSONObject("pf"), performers)
            readWeightMap(root.optJSONObject("st"), studios)
            val wArr = root.optJSONArray("w")
            if (wArr != null) {
                for (i in 0 until wArr.length()) {
                    val u = wArr.optString(i).trim()
                    if (u.isNotBlank()) watchedUrls.add(u)
                }
            }
        }
        runCatching {
            val catRaw = localPreferences.getString(localCategoryStorageKey, null)?.takeIf(String::isNotBlank) ?: return@runCatching
            readWeightMap(JSONObject(catRaw), categories)
        }
    }

    private fun persistLocked() {
        runCatching {
            val root = JSONObject().apply {
                put("day", lastDecayDay)
                put("n", totalInteractions)
                put("seed", lastAppliedSeed)
                put("kw", writeWeightMap(keywords, MAX_KEYWORDS))
                put("bg", writeWeightMap(bigrams, MAX_BIGRAMS))
                put("tg", writeWeightMap(tags, MAX_TAGS))
                put("pf", writeWeightMap(performers, MAX_PERFORMERS))
                put("st", writeWeightMap(studios, MAX_STUDIOS))
                put("w", JSONArray(watchedUrls.toList()))
            }
            preferences.edit().putString(storageKey, root.toString()).apply()
        }
        runCatching {
            localPreferences.edit()
                .putString(localCategoryStorageKey, writeWeightMap(categories, MAX_CATEGORIES).toString())
                .apply()
        }
    }

    private fun readWeightMap(obj: JSONObject?, target: LinkedHashMap<String, Double>) {
        if (obj == null) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optDouble(k, 0.0)
            if (v >= MIN_WEIGHT_FLOOR) target[k] = v
        }
    }

    private fun writeWeightMap(source: Map<String, Double>, limit: Int): JSONObject {
        val out = JSONObject()
        source.entries
            .sortedByDescending { it.value }
            .take(limit)
            .forEach { (k, v) -> out.put(k, Math.round(v * 100.0) / 100.0) }
        return out
    }

    private fun tokenizeTitle(rawTitle: String): List<String> {
        val cleaned = rawTitle
            .replace(BRACKET_METADATA_REGEX, " ")
            .replace(JAV_CODE_REGEX, " ")
            .lowercase(Locale.ROOT)
        return WORD_SPLIT_REGEX.split(cleaned)
            .asSequence()
            .map(String::trim)
            .filter { tok ->
                tok.length in 3..26 &&
                    !tok.all(Char::isDigit) &&
                    tok !in TITLE_STOPWORDS
            }
            .take(24)
            .toList()
    }

    private fun extractBigrams(tokens: List<String>): List<String> {
        if (tokens.size < 2) return emptyList()
        val result = ArrayList<String>(tokens.size - 1)
        for (i in 0 until tokens.size - 1) {
            val a = tokens[i]
            val b = tokens[i + 1]
            if (a != b) result += "$a $b"
        }
        return result
    }

    private fun normalizeEntity(raw: String): String? {
        val clean = raw.trim()
            .replace(MULTI_SPACE_REGEX, " ")
            .lowercase(Locale.ROOT)
        return clean.takeIf { it.length in 2..48 }
    }

    private fun normalizeUrlKey(url: String?): String? {
        val clean = url?.trim()?.lowercase(Locale.ROOT)?.substringBefore('#') ?: return null
        return clean.takeIf(String::isNotBlank)
    }

    private fun currentEpochDay(): Long = System.currentTimeMillis() / MILLIS_PER_DAY

    companion object {
        private const val GLOBAL_PREFS_NAME = "agy_global_taste_v1"
        private const val DEFAULT_STORAGE_KEY = "smart_taste_profile_v1"
        const val PREF_SMART_TASTE_KEY = "pref_smart_taste_enabled"
        const val PREF_TASTE_SEED_KEY = "pref_smart_taste_seed"
        const val PREF_TASTE_RESET_KEY = "pref_smart_taste_reset"
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val BROWSE_DEDUP_WINDOW_MS = 12L * 60L * 60L * 1000L
        private const val DAILY_DECAY_FACTOR = 0.94
        private const val MIN_WEIGHT_FLOOR = 0.25
        private const val MAX_WEIGHT = 120.0

        private const val WEIGHT_BROWSE = 1.5
        private const val WEIGHT_FILTER = 2.2
        private const val WEIGHT_PLAY = 3.5
        private const val WEIGHT_SEED = 5.0

        private const val MAX_KEYWORDS = 80
        private const val MAX_BIGRAMS = 60
        private const val MAX_TAGS = 60
        private const val MAX_PERFORMERS = 50
        private const val MAX_STUDIOS = 40
        private const val MAX_CATEGORIES = 20
        private const val MAX_WATCHED_URLS = 300

        private val BRACKET_METADATA_REGEX = Regex("""\[[^\]]*\]|\([^)]*\)""")
        private val JAV_CODE_REGEX = Regex("""\b[a-z]{2,6}-\d{2,5}(?:-[a-z]{1,4})?\b""", RegexOption.IGNORE_CASE)
        private val WORD_SPLIT_REGEX = Regex("""[^\p{L}\p{N}]+""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        private val GENERIC_TAG_STOPWORDS = setOf(
            "all", "any", "hd", "sd", "video", "videos", "porn", "jav", "verified",
            "exclusive", "hd porn", "60fps", "closed captions", "featured",
        )

        private val TITLE_STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "your", "you", "her", "his", "she",
            "him", "are", "was", "were", "have", "has", "had", "not", "but", "what", "all", "can",
            "out", "who", "get", "gets", "got", "when", "will", "just", "into", "over", "after",
            "before", "while", "until", "about", "there", "their", "they", "them", "our", "its",
            "one", "two", "three", "first", "time", "part", "full", "video", "movie", "watch",
            "free", "online", "new", "best", "top", "very", "more", "most", "much", "make", "makes",
            "made", "take", "takes", "want", "wants", "like", "love", "day", "night", "min", "mins",
            "hours", "hour", "vol", "edition", "special", "version", "uncut", "official", "real",
            "being", "been", "does", "did", "how", "why", "where", "than", "then", "too", "only",
            "even", "still", "back", "down", "here", "such", "way", "own", "man", "men", "woman",
            "girl", "girls", "guy", "guys", "who", "whom", "whose", "which", "would", "could",
            "should", "because", "through", "during", "without", "again", "every", "never",
            "always", "while", "being", "having", "doing", "going", "come", "comes", "came",
            "let", "lets", "give", "gives", "gave", "keep", "keeps", "kept", "put", "puts",
            "see", "sees", "saw", "look", "looks", "feel", "feels", "felt", "good", "great",
            "super", "ultra", "mega", "hot", "sexy", "cute", "nice", "big", "little", "old",
            "young", "pornhub", "sextb", "com", "www", "http", "https", "mp4", "hls", "1080p",
            "720p", "480p", "4k", "60fps", "hd", "sd",
        )
    }
}
