package aniyomi.lib.m3u8server

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
 * across +18 extensions (`agy_global_taste_v1`).
 *
 * Key properties:
 * - Live cross-extension synchronization via monotonic version counter (never goes stale
 *   or overwrites signals written by another extension in the same process).
 * - Canonical concept extraction from titles, tags, genres, filters, and seed text
 *   (e.g. `3some`/`ffm`/`mmf`/`threesome--foursome` -> `threesome`, `4some`/`4p` -> `foursome`).
 * - Weighted multi-signal scoring where core concepts + performers dominate over generic
 *   narrative words, with multi-concept synergy boost and watched-URL suppression.
 */
class SmartTasteEngine(
    private val localPreferences: SharedPreferences,
    private val sourceKey: String = "default",
) {
    private val appContext: Application by injectLazy()

    private val preferences: SharedPreferences by lazy {
        runCatching {
            appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrDefault(localPreferences)
    }

    private val storageKey: String = DEFAULT_STORAGE_KEY
    private val localCategoryStorageKey: String = "smart_taste_cats_$sourceKey"

    data class ProfileSnapshot(
        val topSources: List<String>,
        val topConcepts: List<String>,
        val topPerformers: List<String>,
        val topTags: List<String>,
        val topKeywords: List<String>,
        val topBigrams: List<String>,
        val topStudios: List<String>,
        val topCategories: List<String>,
        val watchedCount: Int,
        val totalInteractions: Int,
    )

    private data class CachedVideoMeta(
        val title: String?,
        val tags: List<String>,
        val performers: List<String>,
        val studios: List<String>,
        val categories: List<String>,
    )

    private val lock = Any()

    private val sourceUsage = LinkedHashMap<String, Double>()
    private val concepts = LinkedHashMap<String, Double>()
    private val keywords = LinkedHashMap<String, Double>()
    private val bigrams = LinkedHashMap<String, Double>()
    private val tags = LinkedHashMap<String, Double>()
    private val performers = LinkedHashMap<String, Double>()
    private val studios = LinkedHashMap<String, Double>()
    private val categories = LinkedHashMap<String, Double>()
    private val watchedUrls = LinkedHashSet<String>()
    private val recentBrowseAtMs = HashMap<String, Long>()
    private val recentMetaByUrl = ConcurrentHashMap<String, CachedVideoMeta>()

    private var lastDecayDay: Long = currentEpochDay()
    private var totalInteractions: Int = 0
    private var lastAppliedSeed: String = ""
    private var loadedVersion: Long = -1L

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
        val tagsList = splitMultiEntities(parsedDetails.genre)
        val perfList = splitMultiEntities(parsedDetails.artist)
        val studioList = splitMultiEntities(parsedDetails.author)
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
        val tagsList = splitMultiEntities(anime.genre)
        val perfList = splitMultiEntities(anime.artist)
        val studioList = splitMultiEntities(anime.author)
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
            summary = "Learns core themes (e.g. threesome, foursome), stars & tags across all +18 extensions."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TASTE_SEED_KEY
            title = "Seed taste from favorite titles / categories"
            dialogTitle = "Paste favorite titles, categories, or stars"
            dialogMessage = "Separate by commas or new lines (e.g. threesome, foursome, 4some, creampie, or full video titles). Syncs instantly across all +18 extensions."
            summary = summaryText()
            setOnPreferenceChangeListener { pref, newValue ->
                val text = newValue as? String
                applySeedText(text, force = true)
                pref.summary = summaryText()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TASTE_RESET_KEY
            title = "Reset shared Smart Taste profile"
            summary = "Tap to clear all learned themes, titles, stars, and watched history."
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

    fun hasLearnedProfile(): Boolean = synchronized(lock) {
        ensureFreshLoadedLocked()
        concepts.isNotEmpty() || performers.isNotEmpty() || tags.isNotEmpty() || keywords.isNotEmpty() || categories.isNotEmpty()
    }

    fun snapshot(): ProfileSnapshot = synchronized(lock) {
        ensureFreshLoadedLocked()
        ProfileSnapshot(
            topSources = topKeys(sourceUsage, 10),
            topConcepts = topKeys(concepts, 12),
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

    fun recordSourceUsage(sourceId: String, weight: Double = WEIGHT_BROWSE) {
        val clean = sourceId.trim().lowercase(Locale.ROOT)
        if (clean.isBlank() || clean == "default" || clean == "smartforyou") return
        synchronized(lock) {
            ensureFreshLoadedLocked()
            applyDecayIfNeededLocked()
            bump(sourceUsage, clean, weight, MAX_SOURCES)
            persistLocked()
        }
    }

    fun summaryText(): String {
        val snap = snapshot()
        if (snap.totalInteractions == 0 && snap.topConcepts.isEmpty() && snap.topKeywords.isEmpty() && snap.topTags.isEmpty()) {
            return "No taste learned yet. Open/play videos or tap here to enter core themes/titles (e.g. threesome, foursome)."
        }
        val parts = buildList {
            if (snap.topConcepts.isNotEmpty()) add("Core: ${snap.topConcepts.take(4).joinToString(", ")}")
            if (snap.topSources.isNotEmpty()) add("Sources: ${snap.topSources.take(3).joinToString(", ")}")
            if (snap.topPerformers.isNotEmpty()) add("Stars: ${snap.topPerformers.take(3).joinToString(", ")}")
            if (snap.topConcepts.isEmpty() && snap.topTags.isNotEmpty()) add("Tags: ${snap.topTags.take(4).joinToString(", ")}")
            add("Watched: ${snap.watchedCount}")
        }
        return parts.joinToString(" | ")
    }

    fun isWatched(url: String?, title: String? = null): Boolean {
        val keys = extractKnownKeys(url, title)
        if (keys.isEmpty()) return false
        return synchronized(lock) {
            ensureFreshLoadedLocked()
            keys.any { it in watchedUrls }
        }
    }

    private fun markKnownLocked(url: String?, title: String?) {
        for (k in extractKnownKeys(url, title)) {
            watchedUrls.remove(k)
            watchedUrls.add(k)
            while (watchedUrls.size > MAX_WATCHED_URLS) {
                val first = watchedUrls.iterator().next()
                watchedUrls.remove(first)
            }
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
            ensureFreshLoadedLocked()
            // Always record opened / saved library items as known so For You only surfaces NEW videos.
            markKnownLocked(url, title)
            val now = System.currentTimeMillis()
            if (urlKey != null) {
                val prev = recentBrowseAtMs[urlKey]
                if (prev != null && now - prev < BROWSE_DEDUP_WINDOW_MS) {
                    persistLocked()
                    return
                }
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
            Log.i(TAG, "recordBrowse[$sourceKey] title='${title?.take(45)}' core=${topKeys(concepts, 4)}")
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
        synchronized(lock) {
            ensureFreshLoadedLocked()
            applyDecayIfNeededLocked()
            markKnownLocked(url, title)
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
            Log.i(TAG, "recordPlay[$sourceKey] title='${title?.take(45)}' core=${topKeys(concepts, 4)}")
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
            ensureFreshLoadedLocked()
            applyDecayIfNeededLocked()
            val cleanQ = query?.trim().orEmpty()
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
     * Parses user-provided favorite titles, core categories, or performer names (newline or comma separated)
     * from Extension Settings with strong weight so the engine immediately understands stored titles.
     */
    fun applySeedText(rawSeed: String?, force: Boolean = false) {
        val cleaned = rawSeed?.trim().orEmpty()
        synchronized(lock) {
            ensureFreshLoadedLocked()
            if (cleaned.isBlank() || (!force && cleaned == lastAppliedSeed)) return
            applyDecayIfNeededLocked()
            val lines = cleaned.split('\n', ';', ',')
                .map(String::trim)
                .filter { it.length >= 2 }
                .take(100)
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
            Log.i(TAG, "applySeedText[$sourceKey] lines=${lines.size} core=${topKeys(concepts, 5)}")
        }
    }

    fun reset() {
        synchronized(lock) {
            sourceUsage.clear()
            concepts.clear()
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
            val newVer = preferences.getLong(VERSION_KEY, 0L) + 1L
            loadedVersion = newVer
            preferences.edit()
                .remove(storageKey)
                .putLong(VERSION_KEY, newVer)
                .apply()
            localPreferences.edit().remove(localCategoryStorageKey).apply()
        }
    }

    /**
     * Scores a candidate listing card against the user's learned taste profile.
     * Core concepts (e.g. `threesome`, `foursome`, `creampie`) and performers carry
     * primary weight with a synergy bonus when multiple learned concepts match together.
     */
    fun scoreCandidate(
        title: String,
        url: String,
        extraTags: Collection<String> = emptyList(),
        extraPerformers: Collection<String> = emptyList(),
    ): Double = synchronized(lock) {
        ensureFreshLoadedLocked()
        if (extractKnownKeys(url, title).any { it in watchedUrls }) {
            return@synchronized -1_000.0
        }

        val lowerTitle = title.lowercase(Locale.ROOT)
        val tokens = tokenizeTitle(title)
        val titleBigrams = extractBigrams(tokens)
        val candidateConcepts = extractConcepts(title, extraTags)

        var score = 0.0

        // 1. Core Canonical Concept match (highest signal — catches 3some/threesome, 4some/foursome, etc.)
        var matchedConceptCount = 0
        for (concept in candidateConcepts) {
            val weight = concepts[concept] ?: continue
            score += logWeight(weight) * 4.6
            matchedConceptCount++
        }
        if (matchedConceptCount >= 2) {
            score += (matchedConceptCount - 1) * 3.2
        }

        // 2. Performer / Actress / Pornstar match
        for ((name, weight) in performers) {
            if (name.length >= 4 && lowerTitle.contains(name)) {
                score += logWeight(weight) * 3.6
            }
        }
        for (p in extraPerformers) {
            val norm = normalizeEntity(p) ?: continue
            performers[norm]?.let { score += logWeight(it) * 3.8 }
        }

        // 3. Explicit Tag / Genre match
        for ((tag, weight) in tags) {
            if (tag.length >= 3 && tag !in candidateConcepts && lowerTitle.contains(tag)) {
                score += logWeight(weight) * 2.0
            }
        }
        for (t in extraTags) {
            val norm = normalizeEntity(t) ?: continue
            if (norm !in candidateConcepts) {
                tags[norm]?.let { score += logWeight(it) * 2.2 }
            }
        }

        // 4. Studio / Channel match
        for ((st, weight) in studios) {
            if (st.length >= 3 && lowerTitle.contains(st)) {
                score += logWeight(weight) * 1.5
            }
        }

        // 5. Title bigrams & keywords (gentle secondary tie-breaker — never overrides core concepts)
        for (bg in titleBigrams) {
            bigrams[bg]?.let { score += logWeight(it) * 1.1 }
        }
        val matchedKeywords = HashSet<String>()
        for (tok in tokens) {
            if (matchedKeywords.add(tok) && tok !in candidateConcepts) {
                keywords[tok]?.let { score += logWeight(it) * 0.65 }
            }
        }

        score
    }

    /**
     * Re-ranks candidates by taste score while optionally excluding watched items
     * and keeping strictly taste-matching items ahead of generic filler.
     */
    fun rankCandidates(
        items: List<SAnime>,
        excludeWatched: Boolean = false,
        blendWithOriginalRank: Boolean = false,
    ): List<SAnime> {
        if (items.isEmpty()) return items

        val filtered = if (excludeWatched) {
            items.filterNot { isWatched(it.url, it.title) }
        } else {
            items
        }
        if (filtered.isEmpty() || !hasLearnedProfile()) return filtered

        val scored = filtered.mapIndexed { idx, anime ->
            val taste = scoreCandidate(anime.title, anime.url)
            val finalScore = if (blendWithOriginalRank && taste >= 0.0) {
                val positionBonus = (filtered.size - idx).toDouble() / filtered.size.coerceAtLeast(1) * 1.8
                taste + positionBonus
            } else {
                taste
            }
            Triple(anime, taste, finalScore)
        }

        return scored.sortedByDescending { it.third }.map { it.first }
    }

    /**
     * Builds targeted discovery queries prioritized by CORE CONCEPTS first (e.g. `threesome`,
     * `foursome`, `creampie`), then performers, then explicit tags — never random narrative filler!
     */
    fun buildTitleSearchQueries(maxQueries: Int = 3): List<String> = synchronized(lock) {
        ensureFreshLoadedLocked()
        val queries = mutableListOf<String>()

        // 1. Core canonical concepts first!
        val topCore = topKeys(concepts, 6)
        for (c in topCore) {
            if (queries.size >= maxQueries) break
            queries += c
        }

        // 2. Top performers if we still have room
        if (queries.size < maxQueries) {
            for (p in topKeys(performers, 4)) {
                if (queries.size >= maxQueries) break
                if (queries.none { it.equals(p, ignoreCase = true) }) queries += p
            }
        }

        // 3. Explicit tags if still needed
        if (queries.size < maxQueries) {
            for (t in topKeys(tags, 6)) {
                if (queries.size >= maxQueries) break
                if (queries.none { it.equals(t, ignoreCase = true) }) queries += t
            }
        }

        // 4. Clean keywords only as last resort when no concepts/performers/tags exist yet
        if (queries.isEmpty()) {
            val topKw = topKeys(keywords, 4)
            if (topKw.isNotEmpty()) queries += topKw.first()
        }

        queries.distinct().take(maxQueries)
    }

    /**
     * Extracts canonical adult concepts from title + tags/categories (e.g. `3some`/`ffm` -> `threesome`,
     * `4some`/`4p` -> `foursome`, `nakadashi` -> `creampie`).
     */
    fun extractConcepts(title: String?, tagsList: Collection<String> = emptyList()): Set<String> {
        val combined = buildString {
            if (!title.isNullOrBlank()) append(title).append(' ')
            for (t in tagsList) {
                if (t.isNotBlank()) append(t).append(' ')
            }
        }.lowercase(Locale.ROOT)
        if (combined.isBlank()) return emptySet()

        val found = LinkedHashSet<String>()
        for ((canonical, regex) in CONCEPT_PATTERNS) {
            if (regex.containsMatchIn(combined)) {
                found += canonical
            }
        }
        return found
    }

    private fun splitMultiEntities(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', '/', '|', '&')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun absorbSignalsLocked(
        title: String?,
        tagsList: Collection<String>,
        performersList: Collection<String>,
        studiosList: Collection<String>,
        categoriesList: Collection<String>,
        weight: Double,
    ) {
        val srcId = sourceKey.trim().lowercase(Locale.ROOT)
        if (srcId.isNotBlank() && srcId != "default" && srcId != "smartforyou") {
            bump(sourceUsage, srcId, weight, MAX_SOURCES)
        }

        // 1. Canonical core concepts (strongest weight — unites 3some/threesome, 4some/foursome, etc.)
        val expandedTags = tagsList.flatMap { splitMultiEntities(it) }
        val extractedConcepts = extractConcepts(title, expandedTags + categoriesList)
        for (concept in extractedConcepts) {
            bump(concepts, concept, weight * 1.45, MAX_CONCEPTS)
        }

        // 2. Title keywords & bigrams (lower secondary weight)
        if (!title.isNullOrBlank()) {
            val tokens = tokenizeTitle(title)
            val seenTok = HashSet<String>()
            for (tok in tokens) {
                if (seenTok.add(tok)) {
                    bump(keywords, tok, weight * 0.55, MAX_KEYWORDS)
                }
            }
            val seenBg = HashSet<String>()
            for (bg in extractBigrams(tokens)) {
                if (seenBg.add(bg)) {
                    bump(bigrams, bg, weight * 0.70, MAX_BIGRAMS)
                }
            }
        }

        // 3. Explicit tags (split composite tags like "Threesome / Foursome")
        for (rawTag in expandedTags) {
            val norm = normalizeEntity(rawTag) ?: continue
            if (norm in GENERIC_TAG_STOPWORDS) continue
            bump(tags, norm, weight, MAX_TAGS)
        }

        // 4. Performers / Stars
        for (rawPerf in performersList.flatMap { splitMultiEntities(it) }) {
            val norm = normalizeEntity(rawPerf) ?: continue
            if (norm.length < 3 || norm in GENERIC_TAG_STOPWORDS) continue
            bump(performers, norm, weight * 1.25, MAX_PERFORMERS)
        }

        // 5. Studios / Channels
        for (rawStudio in studiosList.flatMap { splitMultiEntities(it) }) {
            val norm = normalizeEntity(rawStudio) ?: continue
            if (norm.length < 2 || norm in GENERIC_TAG_STOPWORDS) continue
            bump(studios, norm, weight, MAX_STUDIOS)
        }

        // 6. Site-specific categories
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
        decayMap(sourceUsage, factor)
        decayMap(concepts, factor)
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

    /**
     * Always reloads from `agy_global_taste_v1` whenever ANY other extension wrote a newer
     * version, so cross-extension learning is 100% live without restarting the app.
     */
    private fun ensureFreshLoadedLocked() {
        val diskVersion = preferences.getLong(VERSION_KEY, 0L)
        if (loadedVersion == diskVersion && loadedVersion >= 0L) return

        sourceUsage.clear()
        concepts.clear()
        keywords.clear()
        bigrams.clear()
        tags.clear()
        performers.clear()
        studios.clear()
        categories.clear()
        watchedUrls.clear()

        runCatching {
            val raw = preferences.getString(storageKey, null)?.takeIf(String::isNotBlank) ?: return@runCatching
            val root = JSONObject(raw)
            lastDecayDay = root.optLong("day", currentEpochDay())
            totalInteractions = root.optInt("n", 0)
            lastAppliedSeed = root.optString("seed", "")
            readWeightMap(root.optJSONObject("src"), sourceUsage)
            readWeightMap(root.optJSONObject("cn"), concepts)
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
            // Upgrade legacy v1 profiles automatically: infer concepts from existing tags/keywords!
            if (concepts.isEmpty() && (tags.isNotEmpty() || keywords.isNotEmpty())) {
                for ((t, w) in tags) {
                    for (c in extractConcepts(t)) bump(concepts, c, w * 1.2, MAX_CONCEPTS)
                }
                for ((k, w) in keywords) {
                    for (c in extractConcepts(k)) bump(concepts, c, w * 0.9, MAX_CONCEPTS)
                }
            }
        }
        runCatching {
            val catRaw = localPreferences.getString(localCategoryStorageKey, null)?.takeIf(String::isNotBlank) ?: return@runCatching
            readWeightMap(JSONObject(catRaw), categories)
        }
        loadedVersion = diskVersion
    }

    private fun persistLocked() {
        runCatching {
            val newVersion = preferences.getLong(VERSION_KEY, 0L) + 1L
            val root = JSONObject().apply {
                put("day", lastDecayDay)
                put("n", totalInteractions)
                put("seed", lastAppliedSeed)
                put("src", writeWeightMap(sourceUsage, MAX_SOURCES))
                put("cn", writeWeightMap(concepts, MAX_CONCEPTS))
                put("kw", writeWeightMap(keywords, MAX_KEYWORDS))
                put("bg", writeWeightMap(bigrams, MAX_BIGRAMS))
                put("tg", writeWeightMap(tags, MAX_TAGS))
                put("pf", writeWeightMap(performers, MAX_PERFORMERS))
                put("st", writeWeightMap(studios, MAX_STUDIOS))
                put("w", JSONArray(watchedUrls.toList()))
            }
            preferences.edit()
                .putString(storageKey, root.toString())
                .putLong(VERSION_KEY, newVersion)
                .apply()
            loadedVersion = newVersion
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

    /**
     * Produces canonical cross-extension identifiers for a video so relative URLs from
     * individual extensions (`/sone-248-rm`, `/view_video.php?viewkey=abc`) match full
     * URLs in Smart For You (`https://sextb.net/sone-248-rm`) and identical JAV codes
     * across different sites (`SONE-248` / `SONE-248-RM`).
     */
    fun extractKnownKeys(url: String?, title: String? = null): List<String> {
        val out = LinkedHashSet<String>()
        normalizeUrlKey(url)?.let { norm ->
            out += norm
            VIEWKEY_REGEX.find(norm)?.groupValues?.getOrNull(1)?.let { out += "ph:$it" }
            WGCZ_ID_REGEX.find(norm)?.groupValues?.getOrNull(1)?.let { out += "wg:$it" }
            JAV_BASE_CODE_REGEX.find(norm)?.let { m ->
                out += "jav:${m.groupValues[1]}-${m.groupValues[2]}"
            }
        }
        if (!title.isNullOrBlank()) {
            JAV_BASE_CODE_REGEX.find(title.lowercase(Locale.ROOT))?.let { m ->
                out += "jav:${m.groupValues[1]}-${m.groupValues[2]}"
            }
        }
        return out.toList()
    }

    private fun normalizeUrlKey(url: String?): String? {
        val raw = url?.trim()?.lowercase(Locale.ROOT)?.substringBefore('#') ?: return null
        if (raw.isBlank()) return null
        // Strip scheme + host so relative & absolute URLs produce the exact same path+query key!
        val stripped = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val afterScheme = raw.substringAfter("://")
            val slashIdx = afterScheme.indexOf('/')
            if (slashIdx >= 0) afterScheme.substring(slashIdx) else "/"
        } else {
            raw
        }
        val pathPart = stripped.substringBefore('?').trimEnd('/').ifBlank { "/" }
        val queryPart = stripped.substringAfter('?', "")
        return if (queryPart.isNotBlank()) "$pathPart?$queryPart" else pathPart
    }

    private fun currentEpochDay(): Long = System.currentTimeMillis() / MILLIS_PER_DAY

    companion object {
        private const val TAG = "SmartTaste"
        private const val GLOBAL_PREFS_NAME = "agy_global_taste_v1"
        private const val DEFAULT_STORAGE_KEY = "smart_taste_profile_v1"
        private const val VERSION_KEY = "smart_taste_version"
        const val PREF_SMART_TASTE_KEY = "pref_smart_taste_enabled"
        const val PREF_TASTE_SEED_KEY = "pref_smart_taste_seed"
        const val PREF_TASTE_RESET_KEY = "pref_smart_taste_reset"

        private const val MILLIS_PER_DAY = 86_400_000L
        private const val BROWSE_DEDUP_WINDOW_MS = 12L * 60L * 60L * 1000L
        private const val DAILY_DECAY_FACTOR = 0.94
        private const val MIN_WEIGHT_FLOOR = 0.25
        private const val MAX_WEIGHT = 160.0

        private const val WEIGHT_BROWSE = 1.5
        private const val WEIGHT_FILTER = 2.5
        private const val WEIGHT_PLAY = 4.0
        private const val WEIGHT_SEED = 6.0

        private const val MAX_SOURCES = 25
        private const val MAX_CONCEPTS = 50
        private const val MAX_KEYWORDS = 80
        private const val MAX_BIGRAMS = 60
        private const val MAX_TAGS = 60
        private const val MAX_PERFORMERS = 50
        private const val MAX_STUDIOS = 40
        private const val MAX_CATEGORIES = 20
        private const val MAX_WATCHED_URLS = 600

        private val BRACKET_METADATA_REGEX = Regex("""\[[^\]]*\]|\([^)]*\)""")
        private val JAV_CODE_REGEX = Regex("""\b[a-z]{2,6}-\d{2,5}(?:-[a-z]{1,4})?\b""", RegexOption.IGNORE_CASE)
        private val JAV_BASE_CODE_REGEX = Regex("""\b([a-z]{2,6})-(\d{2,5})(?:-[a-z]{1,4})?\b""")
        private val VIEWKEY_REGEX = Regex("""viewkey=([a-z0-9]+)""")
        private val WGCZ_ID_REGEX = Regex("""/video\.([a-z0-9]+)/""")
        private val WORD_SPLIT_REGEX = Regex("""[^\p{L}\p{N}]+""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        // Canonical adult concept patterns — unites slang, abbreviations, hyphenated slugs, and synonyms.
        private val CONCEPT_PATTERNS: List<Pair<String, Regex>> = listOf(
            "threesome" to Regex("""\b(?:threesomes?|3some|3-some|3p|ffm|mmf|mff|fmf|double\s+(?:blowjob|fellatio|pussy)|two\s+(?:girls|lovers|women|sluts|sisters|wives)|sandwiched)\b"""),
            "foursome" to Regex("""\b(?:foursomes?|4some|4-some|4p|ffmm|mmff|fffm|mfff)\b"""),
            "orgy" to Regex("""\b(?:orgy|orgies|group\s*sex|gangbang|gang\s*bang|harem|swinger|5some|5p|multi\s*partner)\b"""),
            "creampie" to Regex("""\b(?:creampies?|cream\s*pie|nakadashi|cum\s*inside|cumming\s*inside|ejaculat\w*\s+into|internal\s+cumshot|unprotected\s+sex)\b"""),
            "milf" to Regex("""\b(?:milfs?|mature(?:\s+woman)?|married\s+woman|housewife|step\s*mom|stepmother|young\s+wife|cheating\s+wife)\b"""),
            "big tits" to Regex("""\b(?:big\s*tits?|huge\s*tits?|busty|big\s*boobs?|large\s*breasts?|titty\s*fuck|titfuck|paizuri|[g-k]-?cup|plump\s*breasts?)\b"""),
            "big ass" to Regex("""\b(?:big\s*ass(?:es)?|huge\s*butt|pawg|beautiful\s*butt|nice\s*ass|bubble\s*butt|phat\s*ass)\b"""),
            "anal" to Regex("""\b(?:anal|ass\s*fuck\w*|butt\s*fuck\w*|double\s*penetration|\bdp\b)\b"""),
            "blowjob" to Regex("""\b(?:blowjobs?|deep\s*throat\w*|fellatio|face\s*fuck\w*)\b"""),
            "squirt" to Regex("""\b(?:squirt(?:ing)?)\b"""),
            "lesbian" to Regex("""\b(?:lesbians?|girl\s+on\s+girl|scissoring|tribbing)\b"""),
            "cuckold" to Regex("""\b(?:cuckold\w*|ntr|netorare|cheating|affair|adultery)\b"""),
            "stepfamily" to Regex("""\b(?:incest|step\s*family|step\s*sister|step\s*brother|step\s*daughter|step\s*father|older\s+sister|younger\s+sister)\b"""),
            "uncensored" to Regex("""\b(?:uncensored|decensored|reducing\s*mosaic|ltx(?:\s+decensored)?|uncensored\s*leaked)\b"""),
            "amateur" to Regex("""\b(?:amateurs?|homemade|fc2ppv)\b"""),
            "pov" to Regex("""\b(?:pov|subjective|first\s*person)\b"""),
            "massage" to Regex("""\b(?:massage|nuru|oil\s*massage|oiled)\b"""),
            "cosplay" to Regex("""\b(?:cosplay|maid|nurse|school\s*girl|school\s*uniform|secretary|office\s*lady|teacher|stewardess|lingerie|pantyhose|stockings)\b"""),
            "bdsm" to Regex("""\b(?:bdsm|bondage|restraint|confinement|spanking|femdom|submissive|masochist\w*|tied\s+up)\b"""),
            "hardcore" to Regex("""\b(?:hardcore|rough\s*sex|nasty)\b"""),
            "interracial" to Regex("""\b(?:interracial|bbc|black\s+man)\b"""),
            "latina" to Regex("""\b(?:latinas?)\b"""),
            "asian" to Regex("""\b(?:asians?|japanese|korean|jav)\b"""),
            "arab" to Regex("""\b(?:arab|arabic|hijab|سكس\s*عربي|مصري)\b"""),
            "ebony" to Regex("""\b(?:ebony)\b"""),
            "blonde" to Regex("""\b(?:blondes?)\b"""),
            "brunette" to Regex("""\b(?:brunettes?)\b"""),
            "redhead" to Regex("""\b(?:red\s*heads?)\b"""),
            "teen" to Regex("""\b(?:teens?|college\s*girl|college\s*student)\b"""),
            "bbw" to Regex("""\b(?:bbw|chubby|curvy|thick)\b"""),
            "slender" to Regex("""\b(?:slender|petite|skinny|slim)\b"""),
            "small tits" to Regex("""\b(?:small\s*tits?|flat\s*chest)\b"""),
            "shaved" to Regex("""\b(?:shaved(?:\s+pussy)?)\b"""),
            "feet" to Regex("""\b(?:feet|foot\s*fetish|footjob)\b"""),
            "handjob" to Regex("""\b(?:handjobs?)\b"""),
            "facial" to Regex("""\b(?:facials?|bukkake|cumshot|swallow(?:ing)?)\b"""),
            "cowgirl" to Regex("""\b(?:cowgirl|reverse\s*cowgirl|riding)\b"""),
            "doggy style" to Regex("""\b(?:doggy\s*style|doggystyle)\b"""),
            "69" to Regex("""\b69\b"""),
            "masturbation" to Regex("""\b(?:masturbat\w*|solo\s*female|fingering|sex\s*toys?|vibrator|dildo)\b"""),
            "public" to Regex("""\b(?:public|outdoor|voyeur|flashing)\b"""),
            "hentai" to Regex("""\b(?:hentai|anime\s*porn|3d\s*hentai|rule34)\b"""),
            "english subtitle" to Regex("""\b(?:english\s*sub(?:title)?s?|eng\s*sub)\b"""),
        )

        private val GENERIC_TAG_STOPWORDS = setOf(
            "all", "any", "hd", "sd", "video", "videos", "porn", "jav", "verified",
            "exclusive", "hd porn", "60fps", "closed captions", "featured", "single work", "solo work",
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
            "girl", "girls", "guy", "guys", "whom", "whose", "which", "would", "could", "should",
            "because", "through", "during", "without", "again", "every", "never", "always",
            "having", "doing", "going", "come", "comes", "came", "let", "lets", "give", "gives",
            "gave", "keep", "keeps", "kept", "put", "puts", "see", "sees", "saw", "look", "looks",
            "feel", "feels", "felt", "good", "great", "super", "ultra", "mega", "hot", "sexy",
            "cute", "nice", "big", "little", "old", "young", "pornhub", "sextb", "com", "www",
            "http", "https", "mp4", "hls", "1080p", "720p", "480p", "4k", "60fps", "hd", "sd",
            "morning", "fight", "fighting", "secret", "room", "body", "really", "very", "turned",
            "ended", "couldn", "cannot", "never", "always", "finally", "completely", "continuously",
        )
    }
}
