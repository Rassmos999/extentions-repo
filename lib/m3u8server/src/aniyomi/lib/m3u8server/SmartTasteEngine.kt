package aniyomi.lib.m3u8server

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import kotlin.math.roundToInt

/**
 * Privacy-first, 100% on-device behavioral taste, duration & recommendation engine shared
 * across all +18 extensions (`agy_global_taste_v1`).
 *
 * Key properties:
 * - Live cross-extension synchronization via monotonic version counter (never goes stale
 *   or overwrites signals written by another extension in the same process).
 * - Rich canonical concept extraction from English & Arabic titles, tags, genres, filters,
 *   and seed text (threesome, foursome, dp, gangbang, slut, orgy, cuckold, creampie, etc.).
 * - Adaptive soft-mandatory anchors (`threesome`, `foursome`, `dp`, `gangbang`, `slut`)
 *   that strongly prioritize matching/combo videos without starving sources with smaller catalogs.
 * - Per-source & JAV vs Non-JAV video duration learning (`25+ min` non-JAV preference aware),
 *   automatically parsing duration badges & descriptions across extensions.
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
        val softAnchors: List<String> = DEFAULT_SOFT_ANCHORS,
        val preferredNonJavMinMinutes: Int = DEFAULT_NON_JAV_MIN_MINUTES,
        val nonJavAvgMinutes: Int = DEFAULT_NON_JAV_AVG_MINUTES,
        val javAvgMinutes: Int = DEFAULT_JAV_AVG_MINUTES,
    )

    private data class CachedVideoMeta(
        val title: String?,
        val description: String?,
        val tags: List<String>,
        val performers: List<String>,
        val studios: List<String>,
        val categories: List<String>,
        val durationMinutes: Int?,
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
    private val sourceDurAvg = LinkedHashMap<String, Double>()
    private val recentBrowseAtMs = HashMap<String, Long>()
    private val recentMetaByUrl = ConcurrentHashMap<String, CachedVideoMeta>()

    private var lastDecayDay: Long = currentEpochDay()
    private var totalInteractions: Int = 0
    private var lastAppliedSeed: String = ""
    private var loadedVersion: Long = -1L
    private var bootVersion: Int = 0
    private var nonJavDurSum: Double = DEFAULT_NON_JAV_AVG_MINUTES * 3.0
    private var nonJavDurCount: Int = 3
    private var javDurSum: Double = DEFAULT_JAV_AVG_MINUTES * 2.0
    private var javDurCount: Int = 2

    fun isEnabled(): Boolean = localPreferences.getBoolean(PREF_SMART_TASTE_KEY, true)

    fun syncSeedFromPreferences() {
        applySeedText(localPreferences.getString(PREF_TASTE_SEED_KEY, null))
        synchronized(lock) {
            ensureFreshLoadedLocked()
            if (ensureBaselineBootstrapLocked()) {
                persistLocked()
            }
        }
    }

    fun getSoftAnchors(): List<String> {
        val raw = preferences.getString(PREF_SOFT_ANCHORS_KEY, null)
            ?: localPreferences.getString(PREF_SOFT_ANCHORS_KEY, null)
            ?: DEFAULT_SOFT_ANCHORS_TEXT
        if (raw.equals("off", ignoreCase = true) || raw.equals("none", ignoreCase = true)) {
            return emptyList()
        }
        val parsed = raw.split(',', ';', '\n')
            .mapNotNull { token ->
                val clean = token.trim().lowercase(Locale.ROOT)
                if (clean.isBlank()) return@mapNotNull null
                extractConcepts(clean).firstOrNull { it !in FORMAT_ONLY_CONCEPTS } ?: clean
            }
            .distinct()
            .take(8)
        return parsed.ifEmpty { DEFAULT_SOFT_ANCHORS }
    }

    fun setSoftAnchors(rawText: String?) {
        val clean = rawText?.trim().orEmpty()
        preferences.edit().putString(PREF_SOFT_ANCHORS_KEY, clean.ifBlank { DEFAULT_SOFT_ANCHORS_TEXT }).apply()
        localPreferences.edit().putString(PREF_SOFT_ANCHORS_KEY, clean.ifBlank { DEFAULT_SOFT_ANCHORS_TEXT }).apply()
        synchronized(lock) {
            ensureFreshLoadedLocked()
            for (anchor in getSoftAnchors()) {
                if ((concepts[anchor] ?: 0.0) < 6.0) {
                    concepts[anchor] = 6.5
                }
            }
            persistLocked()
        }
    }

    fun preferredNonJavMinMinutes(): Int {
        val pref = preferences.getString(PREF_MIN_NON_JAV_DUR_KEY, null)
            ?: localPreferences.getString(PREF_MIN_NON_JAV_DUR_KEY, null)
            ?: "auto"
        if (pref != "auto") {
            return pref.toIntOrNull()?.coerceIn(0, 120) ?: DEFAULT_NON_JAV_MIN_MINUTES
        }
        return synchronized(lock) {
            ensureFreshLoadedLocked()
            computeAutoNonJavMinMinutesLocked()
        }
    }

    fun setPreferredNonJavMinMinutesPref(value: String) {
        preferences.edit().putString(PREF_MIN_NON_JAV_DUR_KEY, value).apply()
        localPreferences.edit().putString(PREF_MIN_NON_JAV_DUR_KEY, value).apply()
    }

    private fun computeAutoNonJavMinMinutesLocked(): Int {
        if (nonJavDurCount <= 0) return DEFAULT_NON_JAV_MIN_MINUTES
        val avg = nonJavDurSum / nonJavDurCount.coerceAtLeast(1)
        return when {
            avg >= 26.0 -> 25
            avg >= 20.0 -> 20
            avg >= 14.0 -> 15
            else -> 0
        }
    }

    fun isJavSource(sourceId: String? = null): Boolean {
        val id = (sourceId ?: sourceKey).trim().lowercase(Locale.ROOT)
        return id in JAV_SOURCES
    }

    fun isShortFormSource(sourceId: String? = null): Boolean {
        val id = (sourceId ?: sourceKey).trim().lowercase(Locale.ROOT)
        return id in SHORT_FORM_SOURCES
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
            !clean.startsWith("Rating:", true) &&
            !clean.startsWith("Source:", true)
        ) {
            recordSearchOrFilter(query = clean)
        }
        return result
    }

    fun recordDetails(sourceAnime: SAnime, parsedDetails: SAnime) {
        if (!isEnabled()) return
        val title = parsedDetails.title.ifBlank { sourceAnime.title }.takeIf(String::isNotBlank)
        val desc = parsedDetails.description?.ifBlank { null } ?: sourceAnime.description?.ifBlank { null }
        val dur = extractDurationMinutes(sourceAnime.title, parsedDetails.title, desc)
        val tagsList = splitMultiEntities(parsedDetails.genre)
        val perfList = splitMultiEntities(parsedDetails.artist)
        val studioList = splitMultiEntities(parsedDetails.author)
        val url = sourceAnime.url.ifBlank { parsedDetails.url }
        rememberMeta(url, title, desc, tagsList, perfList, studioList, emptyList(), dur)
        recordBrowse(
            url = url,
            title = title,
            tagsList = tagsList,
            performersList = perfList,
            studiosList = studioList,
            durationMinutes = dur,
        )
    }

    fun recordLibraryAnime(anime: SAnime) {
        if (!isEnabled() || anime.title.isBlank()) return
        val dur = extractDurationMinutes(anime.title, anime.description)
        val tagsList = splitMultiEntities(anime.genre)
        val perfList = splitMultiEntities(anime.artist)
        val studioList = splitMultiEntities(anime.author)
        rememberMeta(anime.url, anime.title, anime.description, tagsList, perfList, studioList, emptyList(), dur)
        recordBrowse(
            url = anime.url,
            title = anime.title,
            tagsList = tagsList,
            performersList = perfList,
            studiosList = studioList,
            durationMinutes = dur,
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
            durationMinutes = meta?.durationMinutes ?: extractDurationMinutes(fallbackTitle),
        )
    }

    fun attachPreferences(screen: PreferenceScreen) {
        syncSeedFromPreferences()
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SMART_TASTE_KEY
            title = "Smart Taste learning & re-rank (shared +18)"
            summary = "Learns core themes, video duration (25m+ non-JAV), stars & tags across all +18 extensions."
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SOFT_ANCHORS_KEY
            title = "Priority soft-mandatory themes"
            dialogTitle = "Soft-mandatory themes (comma-separated)"
            dialogMessage = "Strongly boosted & queried when supported by the source, and relaxed automatically if a source has few results. Type 'off' to disable."
            val currentVal = preferences.getString(PREF_SOFT_ANCHORS_KEY, DEFAULT_SOFT_ANCHORS_TEXT) ?: DEFAULT_SOFT_ANCHORS_TEXT
            setDefaultValue(currentVal)
            text = currentVal
            summary = "Active: ${getSoftAnchors().joinToString(", ").ifBlank { "Off" }}"
            setOnPreferenceChangeListener { pref, newValue ->
                val v = newValue as? String
                setSoftAnchors(v)
                pref.summary = "Active: ${getSoftAnchors().joinToString(", ").ifBlank { "Off" }}"
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_MIN_NON_JAV_DUR_KEY
            title = "Non-JAV preferred video duration"
            entries = arrayOf(
                "Auto (Learned — currently ${preferredNonJavMinMinutes()}m+)",
                "15+ min (soft)",
                "20+ min (soft)",
                "25+ min (soft)",
                "30+ min (soft)",
                "Off (any length)",
            )
            entryValues = arrayOf("auto", "15", "20", "25", "30", "0")
            val currentDur = preferences.getString(PREF_MIN_NON_JAV_DUR_KEY, "auto") ?: "auto"
            setDefaultValue(currentDur)
            value = currentDur
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                (newValue as? String)?.let(::setPreferredNonJavMinMinutesPref)
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TASTE_SEED_KEY
            title = "Seed taste from favorite titles / categories"
            dialogTitle = "Paste favorite titles, categories, or stars"
            dialogMessage = "Separate by commas or new lines (e.g. threesome, foursome, dp, gangbang, slut, or full video titles). Syncs instantly across all +18 extensions."
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
        description: String?,
        tagsList: List<String>,
        performersList: List<String>,
        studiosList: List<String>,
        categoriesList: List<String>,
        durationMinutes: Int?,
    ) {
        val key = normalizeUrlKey(url) ?: return
        if (recentMetaByUrl.size > 200) recentMetaByUrl.clear()
        recentMetaByUrl[key] = CachedVideoMeta(
            title = title,
            description = description,
            tags = tagsList,
            performers = performersList,
            studios = studiosList,
            categories = categoriesList,
            durationMinutes = durationMinutes,
        )
    }

    fun hasLearnedProfile(): Boolean = synchronized(lock) {
        ensureFreshLoadedLocked()
        if (ensureBaselineBootstrapLocked()) persistLocked()
        concepts.isNotEmpty() || performers.isNotEmpty() || tags.isNotEmpty() || keywords.isNotEmpty() || categories.isNotEmpty()
    }

    fun snapshot(): ProfileSnapshot = synchronized(lock) {
        ensureFreshLoadedLocked()
        if (ensureBaselineBootstrapLocked()) persistLocked()
        val nonJavAvg = if (nonJavDurCount > 0) (nonJavDurSum / nonJavDurCount).roundToInt() else DEFAULT_NON_JAV_AVG_MINUTES
        val javAvg = if (javDurCount > 0) (javDurSum / javDurCount).roundToInt() else DEFAULT_JAV_AVG_MINUTES
        ProfileSnapshot(
            topSources = topKeys(sourceUsage, 15),
            topConcepts = topConceptsRanked(14),
            topPerformers = topKeys(performers, 8),
            topTags = topKeys(tags, 10),
            topKeywords = topKeys(keywords, 12),
            topBigrams = topKeys(bigrams, 8),
            topStudios = topKeys(studios, 6),
            topCategories = topKeys(categories, 6),
            watchedCount = watchedUrls.size,
            totalInteractions = totalInteractions,
            softAnchors = getSoftAnchors(),
            preferredNonJavMinMinutes = preferredNonJavMinMinutes(),
            nonJavAvgMinutes = nonJavAvg,
            javAvgMinutes = javAvg,
        )
    }

    private fun topConceptsRanked(limit: Int): List<String> {
        val anchors = getSoftAnchors().toSet()
        return concepts.entries
            .filter { (k, _) -> k !in FORMAT_ONLY_CONCEPTS }
            .sortedByDescending { (k, w) ->
                val spec = CONCEPT_SPECIFICITY[k] ?: 1.0
                val anchorBoost = if (k in anchors) 1.35 else 1.0
                w * spec * anchorBoost
            }
            .take(limit)
            .map { it.key }
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
            return "No taste learned yet. Open/play videos or tap here to enter core themes/titles."
        }
        val parts = buildList {
            if (snap.topConcepts.isNotEmpty()) add("Core: ${snap.topConcepts.take(5).joinToString(", ")}")
            if (snap.preferredNonJavMinMinutes > 0) add("Non-JAV: ${snap.preferredNonJavMinMinutes}m+ (avg ${snap.nonJavAvgMinutes}m)")
            if (snap.topSources.isNotEmpty()) add("Sources: ${snap.topSources.take(4).joinToString(", ")}")
            if (snap.topPerformers.isNotEmpty()) add("Stars: ${snap.topPerformers.take(2).joinToString(", ")}")
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
        durationMinutes: Int? = null,
        sourceOverride: String? = null,
    ) {
        val urlKey = normalizeUrlKey(url)
        val effDuration = durationMinutes ?: extractDurationMinutes(title)
        val effSource = resolveEffectiveSourceId(sourceOverride, title, url)
        synchronized(lock) {
            ensureFreshLoadedLocked()
            ensureBaselineBootstrapLocked()
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
            recordDurationLocked(effDuration, effSource, weight = 1)
            absorbSignalsLocked(
                title = title,
                tagsList = tagsList,
                performersList = performersList,
                studiosList = studiosList,
                categoriesList = categoriesList,
                weight = WEIGHT_BROWSE,
                effectiveSourceId = effSource,
            )
            totalInteractions++
            persistLocked()
            Log.i(TAG, "recordBrowse[$effSource] dur=${effDuration ?: -1}m title='${title?.take(42)}' core=${topConceptsRanked(5)}")
        }
    }

    fun recordPlay(
        url: String?,
        title: String?,
        tagsList: Collection<String> = emptyList(),
        performersList: Collection<String> = emptyList(),
        studiosList: Collection<String> = emptyList(),
        categoriesList: Collection<String> = emptyList(),
        durationMinutes: Int? = null,
        sourceOverride: String? = null,
    ) {
        val effDuration = durationMinutes ?: extractDurationMinutes(title)
        val effSource = resolveEffectiveSourceId(sourceOverride, title, url)
        synchronized(lock) {
            ensureFreshLoadedLocked()
            ensureBaselineBootstrapLocked()
            applyDecayIfNeededLocked()
            markKnownLocked(url, title)
            recordDurationLocked(effDuration, effSource, weight = 3)
            absorbSignalsLocked(
                title = title,
                tagsList = tagsList,
                performersList = performersList,
                studiosList = studiosList,
                categoriesList = categoriesList,
                weight = WEIGHT_PLAY,
                effectiveSourceId = effSource,
            )
            totalInteractions++
            persistLocked()
            Log.i(TAG, "recordPlay[$effSource] dur=${effDuration ?: -1}m title='${title?.take(42)}' core=${topConceptsRanked(5)}")
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
            ensureBaselineBootstrapLocked()
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
                effectiveSourceId = sourceKey,
            )
            totalInteractions++
            persistLocked()
        }
    }

    fun applySeedText(rawSeed: String?, force: Boolean = false) {
        val cleaned = rawSeed?.trim().orEmpty()
        synchronized(lock) {
            ensureFreshLoadedLocked()
            ensureBaselineBootstrapLocked()
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
                    effectiveSourceId = sourceKey,
                )
            }
            lastAppliedSeed = cleaned
            totalInteractions += lines.size
            persistLocked()
            Log.i(TAG, "applySeedText[$sourceKey] lines=${lines.size} core=${topConceptsRanked(5)}")
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
            sourceDurAvg.clear()
            recentBrowseAtMs.clear()
            totalInteractions = 0
            lastAppliedSeed = ""
            bootVersion = CURRENT_BOOT_VERSION
            nonJavDurSum = DEFAULT_NON_JAV_AVG_MINUTES * 3.0
            nonJavDurCount = 3
            javDurSum = DEFAULT_JAV_AVG_MINUTES * 2.0
            javDurCount = 2
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
     * One-time baseline prior injection so the engine starts smart out of the box
     * with the user's explicit core concepts (`threesome`, `foursome`, `dp`, `gangbang`, `slut`)
     * and non-JAV long-form duration prior (`25m+`), without ever overwriting higher weights
     * learned from the Library or future browsing.
     */
    private fun ensureBaselineBootstrapLocked(): Boolean {
        if (bootVersion >= CURRENT_BOOT_VERSION) return false
        bootVersion = CURRENT_BOOT_VERSION
        val priors = linkedMapOf(
            "threesome" to 9.0,
            "foursome" to 9.0,
            "dp" to 7.5,
            "gangbang" to 7.5,
            "slut" to 6.5,
        )
        for ((concept, minWeight) in priors) {
            if ((concepts[concept] ?: 0.0) < minWeight) {
                concepts[concept] = minWeight
            }
        }
        // Keep a soft baseline duration prior for non-JAV (28m avg -> 25m+ target) until real watches shift it.
        if (nonJavDurCount < 3) {
            nonJavDurSum += DEFAULT_NON_JAV_AVG_MINUTES * (3 - nonJavDurCount)
            nonJavDurCount = 3
        }
        return true
    }

    private fun recordDurationLocked(durationMinutes: Int?, srcId: String, weight: Int) {
        if (durationMinutes == null || durationMinutes !in 1..360) return
        val cleanSrc = srcId.trim().lowercase(Locale.ROOT)
        if (cleanSrc.isNotBlank() && cleanSrc != "default" && cleanSrc != "smartforyou") {
            val prev = sourceDurAvg[cleanSrc]
            sourceDurAvg[cleanSrc] = if (prev == null) {
                durationMinutes.toDouble()
            } else {
                prev * 0.78 + durationMinutes * 0.22
            }
        }
        when {
            cleanSrc in JAV_SOURCES -> {
                if (javDurCount > 40) {
                    javDurSum *= 0.6
                    javDurCount = (javDurCount * 0.6).roundToInt().coerceAtLeast(1)
                }
                javDurSum += durationMinutes * weight
                javDurCount += weight
            }

            cleanSrc in SHORT_FORM_SOURCES -> Unit

            else -> {
                // Non-JAV live-action tube video
                if (nonJavDurCount > 50) {
                    nonJavDurSum *= 0.6
                    nonJavDurCount = (nonJavDurCount * 0.6).roundToInt().coerceAtLeast(1)
                }
                nonJavDurSum += durationMinutes * weight
                nonJavDurCount += weight
            }
        }
    }

    /**
     * Multi-factor candidate scorer combining:
     * 1. Learned core concepts (weighted by specificity) + multi-concept synergy
     * 2. Soft-mandatory anchor bonus (`threesome`, `foursome`, `dp`, `gangbang`, `slut`)
     *    — strongly boosts matching & multi-anchor combos without starving non-matching sources
     * 3. Adaptive duration fit (non-JAV `25m+` preference vs JAV full-length vs short-form hentai)
     * 4. Performers, explicit tags, studios, bigrams, and title keywords.
     */
    fun scoreCandidate(
        title: String,
        url: String,
        extraTags: Collection<String> = emptyList(),
        extraPerformers: Collection<String> = emptyList(),
        durationMinutes: Int? = null,
        sourceHint: String? = null,
    ): Double = synchronized(lock) {
        ensureFreshLoadedLocked()
        if (extractKnownKeys(url, title).any { it in watchedUrls }) {
            return@synchronized -1_000.0
        }

        val lowerTitle = title.lowercase(Locale.ROOT)
        val tokens = tokenizeTitle(title)
        val titleBigrams = extractBigrams(tokens)
        val candidateConcepts = extractConcepts(title, extraTags)
        val anchors = getSoftAnchors().toSet()

        var score = 0.0

        // 1. Core Canonical Concept match (highest signal — weighted by theme specificity!)
        var matchedConceptCount = 0
        var matchedAnchorCount = 0
        for (concept in candidateConcepts) {
            if (concept in FORMAT_ONLY_CONCEPTS) continue
            val weight = concepts[concept] ?: 0.0
            val spec = CONCEPT_SPECIFICITY[concept] ?: 1.0
            if (weight > 0.0) {
                score += logWeight(weight) * 4.8 * spec
                matchedConceptCount++
            }
            if (concept in anchors) {
                matchedAnchorCount++
            }
        }
        if (matchedConceptCount >= 2) {
            score += (matchedConceptCount - 1) * 3.8
        }
        // Soft-mandatory anchor bonus: combos like (threesome + dp) or (gangbang + slut) soar!
        if (matchedAnchorCount == 1) {
            score += 3.5
        } else if (matchedAnchorCount >= 2) {
            score += 3.5 + (matchedAnchorCount - 1) * 5.2
        }

        // 2. Adaptive Duration Fit (JAV vs Non-JAV vs Short-Form)
        val effSource = resolveEffectiveSourceId(sourceHint, title, url)
        val effDur = durationMinutes ?: extractDurationMinutes(title)
        if (effDur != null && effDur > 0) {
            when {
                effSource in SHORT_FORM_SOURCES -> {
                    if (effDur >= 5) score += 1.5
                }

                effSource in JAV_SOURCES -> {
                    when {
                        effDur >= 60 -> score += 1.5
                        effDur < 10 -> score -= 6.0 // demote short JAV trailers/samples
                    }
                }

                else -> {
                    val targetMin = preferredNonJavMinMinutes()
                    if (targetMin >= 15) {
                        score += when {
                            effDur in targetMin..120 -> 7.5
                            effDur > 120 -> 6.0
                            effDur >= (targetMin - 7).coerceAtLeast(15) -> 1.8
                            effDur >= 12 -> -2.8
                            else -> -9.5 // short <12m clips demoted strongly on non-JAV tubes
                        }
                    }
                }
            }
        }

        // 3. Performer / Actress / Pornstar match
        for ((name, weight) in performers) {
            if (name.length >= 4 && lowerTitle.contains(name)) {
                score += logWeight(weight) * 3.6
            }
        }
        for (p in extraPerformers) {
            val norm = normalizeEntity(p) ?: continue
            performers[norm]?.let { score += logWeight(it) * 3.8 }
        }

        // 4. Explicit Tag / Genre match
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

        // 5. Studio / Channel match
        for ((st, weight) in studios) {
            if (st.length >= 3 && lowerTitle.contains(st)) {
                score += logWeight(weight) * 1.5
            }
        }

        // 6. Title bigrams & keywords (gentle secondary tie-breaker)
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
     * Re-ranks candidates by taste + duration + anchor score while optionally excluding watched items.
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
            val finalScore = if (blendWithOriginalRank && taste > -500.0) {
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
     * Builds targeted discovery queries prioritized by CORE CONCEPTS & soft anchors first
     * (e.g. `threesome`, `foursome`, `dp`, `gangbang`, `slut`), then performers, then tags.
     */
    fun buildTitleSearchQueries(maxQueries: Int = 5): List<String> = synchronized(lock) {
        ensureFreshLoadedLocked()
        if (ensureBaselineBootstrapLocked()) persistLocked()
        val queries = mutableListOf<String>()

        val topCore = topConceptsRanked(8)
        for (c in topCore) {
            if (queries.size >= maxQueries) break
            queries += c
        }

        for (anchor in getSoftAnchors()) {
            if (queries.size >= maxQueries) break
            if (queries.none { it.equals(anchor, ignoreCase = true) }) queries += anchor
        }

        if (queries.size < maxQueries) {
            for (p in topKeys(performers, 4)) {
                if (queries.size >= maxQueries) break
                if (queries.none { it.equals(p, ignoreCase = true) }) queries += p
            }
        }

        if (queries.size < maxQueries) {
            for (t in topKeys(tags, 6)) {
                if (queries.size >= maxQueries) break
                if (queries.none { it.equals(t, ignoreCase = true) }) queries += t
            }
        }

        if (queries.isEmpty()) {
            val topKw = topKeys(keywords, 4)
            if (topKw.isNotEmpty()) queries += topKw.first()
        }

        queries.distinct().take(maxQueries)
    }

    /**
     * Extracts canonical adult concepts from title + tags/categories (English & Arabic),
     * uniting abbreviations, slang, and synonyms (`3some`/`ffm` -> `threesome`, `4some`/`4p` -> `foursome`,
     * `dp`/`double penetration`/`spitroast` -> `dp`, `gangbang`/`train`/`3on1` -> `gangbang`, `slut`/`whore`/`bitch` -> `slut`).
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

    /**
     * Extracts video duration in whole minutes from card titles (`[32:15]`, `[1:38:04]`, `[120 min]`,
     * `[SexTB • 120m]`) or detail descriptions (`Duration: 34:12`, `Runtime: 118 min`).
     */
    fun extractDurationMinutes(vararg texts: String?): Int? {
        for (raw in texts) {
            if (raw.isNullOrBlank()) continue
            // 1. H:MM:SS (e.g. 1:38:04 or 02:05:10)
            HMS_REGEX.find(raw)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: return@let
                val min = m.groupValues[2].toIntOrNull() ?: return@let
                val sec = m.groupValues[3].toIntOrNull() ?: return@let
                if (h in 0..8 && min in 0..59 && sec in 0..59) {
                    val total = h * 60 + min + if (sec >= 30) 1 else 0
                    if (total > 0) return total
                }
            }
            // 2. MM:SS (e.g. 44:51 or 28:15)
            MS_REGEX.find(raw)?.let { m ->
                val min = m.groupValues[1].toIntOrNull() ?: return@let
                val sec = m.groupValues[2].toIntOrNull() ?: return@let
                if (min in 0..360 && sec in 0..59) {
                    val total = min + if (sec >= 30) 1 else 0
                    if (total > 0) return total
                }
            }
            // 3. Explicit hours + minutes (e.g. "1h 25m", "2 hr 10 min")
            HOUR_MIN_REGEX.find(raw)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: return@let
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h in 1..8 && min in 0..59) return h * 60 + min
            }
            // 4. Explicit minutes ("120 min", "45 mins", "35m", "45 دقيقة")
            MIN_ONLY_REGEX.find(raw)?.let { m ->
                val min = m.groupValues[1].toIntOrNull() ?: return@let
                if (min in 1..360) return min
            }
        }
        return null
    }

    private fun resolveEffectiveSourceId(sourceHint: String?, title: String?, url: String?): String {
        val hint = sourceHint?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (hint.isNotBlank() && hint != "default" && hint != "smartforyou") return hint

        // Detect from SmartForYou badge prefix e.g. "[PornHub • 32:15]" or "[SexTB]"
        if (!title.isNullOrBlank()) {
            val badge = BADGE_PREFIX_REGEX.find(title)?.groupValues?.getOrNull(1)
                ?.substringBefore('•')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            BADGE_TO_SOURCE_ID[badge]?.let { return it }
        }

        // Detect from URL host
        if (!url.isNullOrBlank()) {
            val lower = url.lowercase(Locale.ROOT)
            for ((hostNeedle, id) in HOST_TO_SOURCE_ID) {
                if (hostNeedle in lower) return id
            }
        }
        return sourceKey.trim().lowercase(Locale.ROOT)
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
        effectiveSourceId: String,
    ) {
        val srcId = effectiveSourceId.trim().lowercase(Locale.ROOT)
        if (srcId.isNotBlank() && srcId != "default" && srcId != "smartforyou") {
            bump(sourceUsage, srcId, weight, MAX_SOURCES)
        }

        val expandedTags = tagsList.flatMap { splitMultiEntities(it) }
        val extractedConcepts = extractConcepts(title, expandedTags + categoriesList)
        for (concept in extractedConcepts) {
            if (concept in FORMAT_ONLY_CONCEPTS) continue
            val spec = CONCEPT_SPECIFICITY[concept] ?: 1.0
            bump(concepts, concept, weight * 1.45 * spec, MAX_CONCEPTS)
        }

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

        for (rawTag in expandedTags) {
            val norm = normalizeEntity(rawTag) ?: continue
            if (norm in GENERIC_TAG_STOPWORDS) continue
            bump(tags, norm, weight, MAX_TAGS)
        }

        for (rawPerf in performersList.flatMap { splitMultiEntities(it) }) {
            val norm = normalizeEntity(rawPerf) ?: continue
            if (norm.length < 3 || norm in GENERIC_TAG_STOPWORDS) continue
            bump(performers, norm, weight * 1.25, MAX_PERFORMERS)
        }

        for (rawStudio in studiosList.flatMap { splitMultiEntities(it) }) {
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
        sourceDurAvg.clear()
        bootVersion = 0
        nonJavDurSum = DEFAULT_NON_JAV_AVG_MINUTES * 3.0
        nonJavDurCount = 3
        javDurSum = DEFAULT_JAV_AVG_MINUTES * 2.0
        javDurCount = 2

        runCatching {
            val raw = preferences.getString(storageKey, null)?.takeIf(String::isNotBlank) ?: return@runCatching
            val root = JSONObject(raw)
            lastDecayDay = root.optLong("day", currentEpochDay())
            totalInteractions = root.optInt("n", 0)
            lastAppliedSeed = root.optString("seed", "")
            bootVersion = root.optInt("boot", 0)
            if (root.has("nj_ds") && root.has("nj_dc")) {
                nonJavDurSum = root.optDouble("nj_ds", DEFAULT_NON_JAV_AVG_MINUTES * 3.0)
                nonJavDurCount = root.optInt("nj_dc", 3).coerceAtLeast(0)
            }
            if (root.has("jv_ds") && root.has("jv_dc")) {
                javDurSum = root.optDouble("jv_ds", DEFAULT_JAV_AVG_MINUTES * 2.0)
                javDurCount = root.optInt("jv_dc", 2).coerceAtLeast(0)
            }
            readWeightMap(root.optJSONObject("sdur"), sourceDurAvg)
            readWeightMap(root.optJSONObject("src"), sourceUsage)
            readWeightMap(root.optJSONObject("cn"), concepts)
            FORMAT_ONLY_CONCEPTS.forEach { concepts.remove(it) }
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
            if (concepts.isEmpty() && (tags.isNotEmpty() || keywords.isNotEmpty())) {
                for ((t, w) in tags) {
                    for (c in extractConcepts(t)) {
                        if (c !in FORMAT_ONLY_CONCEPTS) bump(concepts, c, w * 1.2, MAX_CONCEPTS)
                    }
                }
                for ((k, w) in keywords) {
                    for (c in extractConcepts(k)) {
                        if (c !in FORMAT_ONLY_CONCEPTS) bump(concepts, c, w * 0.9, MAX_CONCEPTS)
                    }
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
                put("boot", bootVersion)
                put("nj_ds", Math.round(nonJavDurSum * 10.0) / 10.0)
                put("nj_dc", nonJavDurCount)
                put("jv_ds", Math.round(javDurSum * 10.0) / 10.0)
                put("jv_dc", javDurCount)
                put("sdur", writeWeightMap(sourceDurAvg, MAX_SOURCES))
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
        private const val CURRENT_BOOT_VERSION = 2

        const val PREF_SMART_TASTE_KEY = "pref_smart_taste_enabled"
        const val PREF_TASTE_SEED_KEY = "pref_smart_taste_seed"
        const val PREF_TASTE_RESET_KEY = "pref_smart_taste_reset"
        const val PREF_SOFT_ANCHORS_KEY = "pref_smart_taste_soft_anchors"
        const val PREF_MIN_NON_JAV_DUR_KEY = "pref_smart_taste_nonjav_min_dur"

        private const val DEFAULT_NON_JAV_MIN_MINUTES = 25
        private const val DEFAULT_NON_JAV_AVG_MINUTES = 28
        private const val DEFAULT_JAV_AVG_MINUTES = 120
        private const val DEFAULT_SOFT_ANCHORS_TEXT = "threesome, foursome, dp, gangbang, slut"
        private val DEFAULT_SOFT_ANCHORS = listOf("threesome", "foursome", "dp", "gangbang", "slut")

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
        private const val MAX_CONCEPTS = 55
        private const val MAX_KEYWORDS = 80
        private const val MAX_BIGRAMS = 60
        private const val MAX_TAGS = 60
        private const val MAX_PERFORMERS = 50
        private const val MAX_STUDIOS = 40
        private const val MAX_CATEGORIES = 20
        private const val MAX_WATCHED_URLS = 600

        private val JAV_SOURCES = setOf(
            "sextb",
            "supjav",
            "supjav2",
            "jable",
            "missav",
            "javgg",
            "javguru",
            "javdb",
        )
        private val SHORT_FORM_SOURCES = setOf(
            "rule34video", "hanime", "hahomoe", "hentaimama", "hstream", "oppaistream",
            "arabshentai", "hentaiaraby", "nxxhentai",
        )

        private val BADGE_TO_SOURCE_ID = mapOf(
            "sextb" to "sextb",
            "pornhub" to "pornhub",
            "xvideos" to "xvideos",
            "xnxx" to "xnxx",
            "xmovies" to "xmoviesforyou",
            "xmoviesforyou" to "xmoviesforyou",
            "supjav" to "supjav",
            "jable" to "jable",
            "missav" to "missav",
            "javgg" to "javgg",
            "javguru" to "javguru",
            "aflamk1" to "aflamk1",
            "sexalarab" to "sexalarab",
            "arabx" to "arabx",
            "arabxn" to "arabxn",
            "sexmahali" to "sexmahali",
            "rule34" to "rule34video",
            "rule34video" to "rule34video",
        )

        private val HOST_TO_SOURCE_ID = listOf(
            "sextb.net" to "sextb",
            "pornhub.com" to "pornhub",
            "xvideos.com" to "xvideos",
            "xnxx.com" to "xnxx",
            "xmoviesforyou.com" to "xmoviesforyou",
            "supjav.com" to "supjav",
            "jable.tv" to "jable",
            "missav." to "missav",
            "javgg.net" to "javgg",
            "jav.guru" to "javguru",
            "aflamk1." to "aflamk1",
            "sexalarab." to "sexalarab",
            "arabx." to "arabx",
            "arabxn." to "arabxn",
            "sexmahali." to "sexmahali",
            "rule34video." to "rule34video",
        )

        private val BADGE_PREFIX_REGEX = Regex("""^\[([^\]]{2,30})\]""")
        private val HMS_REGEX = Regex("""(?<!\d)(\d{1,2}):([0-5]\d):([0-5]\d)(?!\d)""")
        private val MS_REGEX = Regex("""(?<!\d)(\d{1,3}):([0-5]\d)(?!\d)""")
        private val HOUR_MIN_REGEX = Regex("""(?i)(?<!\d)(\d{1,2})\s*(?:h|hr|hrs|hour|hours)\s*(?:(\d{1,2})\s*(?:m|min|mins|minute|minutes))?""")
        private val MIN_ONLY_REGEX = Regex("""(?i)(?<!\d)(\d{1,3})\s*(?:mins?|minutes?|m\b|دقيقة|دقائق)""")

        private val BRACKET_METADATA_REGEX = Regex("""\[[^\]]*\]|\([^)]*\)""")
        private val JAV_CODE_REGEX = Regex("""\b[a-z]{2,6}-\d{2,5}(?:-[a-z]{1,4})?\b""", RegexOption.IGNORE_CASE)
        private val JAV_BASE_CODE_REGEX = Regex("""\b([a-z]{2,6})-(\d{2,5})(?:-[a-z]{1,4})?\b""")
        private val VIEWKEY_REGEX = Regex("""viewkey=([a-z0-9]+)""")
        private val WGCZ_ID_REGEX = Regex("""/video\.([a-z0-9]+)/""")
        private val WORD_SPLIT_REGEX = Regex("""[^\p{L}\p{N}]+""")
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        // Canonical adult concept patterns — rich English & Arabic title/tag vocabulary.
        private val CONCEPT_PATTERNS: List<Pair<String, Regex>> = listOf(
            "threesome" to Regex("""\b(?:threesomes?|3some|3-some|3p|ffm|mmf|mff|fmf|2\s*on\s*1|two\s+on\s+one|double\s+(?:blowjob|fellatio|pussy|team)|two\s+(?:girls|guys|cocks|dicks|lovers|women|sluts|sisters|wives)|sandwiched)\b|ثلاثي"""),
            "foursome" to Regex("""\b(?:foursomes?|4some|4-some|4p|ffmm|mmff|fffm|mfff|mfmf|fmfm|couple\s+swap|partner\s+swap|swingers?)\b|رباعي"""),
            "dp" to Regex("""\b(?:dp|dap|dvp|double\s*penetration|double\s*penetrat\w*|double\s*anal|double\s*vaginal|double\s*stuffed|two\s+(?:cocks|dicks)\s+(?:in|inside)|both\s+holes|spit\s*roast\w*|spitroast\w*|airtight)\b|اختراق\s*مزدوج"""),
            "gangbang" to Regex("""\b(?:gang\s*bang\w*|gang\s*fuck\w*|ran\s+a\s+train|multiple\s+(?:men|guys|cocks|dicks)|3\s*on\s*1|4\s*on\s*1|5\s*on\s*1|reverse\s*gangbang)\b|نيك\s*جماعي|جماعي"""),
            "slut" to Regex("""\b(?:sluts?|slutty|whores?|bitch(?:es)?|cum\s*slut|cock\s*slut|anal\s*slut|dirty\s+slut|nympho\w*|insatiable|dick\s*hungry|cock\s*hungry)\b|شرموط\w*|عاهر\w*"""),
            "orgy" to Regex("""\b(?:orgy|orgies|group\s*sex|harem|5some|5p|multi\s*partner|sex\s*party)\b|حفلة\s*جنس"""),
            "creampie" to Regex("""\b(?:creampies?|cream\s*pie|nakadashi|cum\s*inside|cumming\s*inside|ejaculat\w*\s+into|internal\s+cumshot|unprotected\s+sex)\b|قذف\s*داخل"""),
            "milf" to Regex("""\b(?:milfs?|mature(?:\s+woman)?|married\s+woman|housewife|step\s*mom|stepmother|young\s+wife|cheating\s+wife)\b|امهات|زوجة|ناضج"""),
            "big tits" to Regex("""\b(?:big\s*tits?|huge\s*tits?|busty|big\s*boobs?|large\s*breasts?|titty\s*fuck|titfuck|paizuri|[g-k]-?cup|plump\s*breasts?)\b"""),
            "big ass" to Regex("""\b(?:big\s*ass(?:es)?|huge\s*butt|pawg|beautiful\s*butt|nice\s*ass|bubble\s*butt|phat\s*ass)\b"""),
            "anal" to Regex("""\b(?:anal|ass\s*fuck\w*|butt\s*fuck\w*)\b|خلفي|طيز"""),
            "blowjob" to Regex("""\b(?:blowjobs?|deep\s*throat\w*|fellatio|face\s*fuck\w*)\b|مص"""),
            "squirt" to Regex("""\b(?:squirt(?:ing)?)\b"""),
            "lesbian" to Regex("""\b(?:lesbians?|girl\s+on\s+girl|scissoring|tribbing)\b|سحاق"""),
            "cuckold" to Regex("""\b(?:cuckold\w*|ntr|netorare|cheating|affair|adultery)\b|ديوث|خيانة"""),
            "stepfamily" to Regex("""\b(?:incest|step\s*family|step\s*sister|step\s*brother|step\s*daughter|step\s*father|older\s+sister|younger\s+sister)\b|محارم"""),
            "uncensored" to Regex("""\b(?:uncensored|decensored|reducing\s*mosaic|ltx(?:\s+decensored)?|uncensored\s*leaked)\b"""),
            "amateur" to Regex("""\b(?:amateurs?|homemade|fc2ppv)\b"""),
            "pov" to Regex("""\b(?:pov|subjective|first\s*person)\b"""),
            "massage" to Regex("""\b(?:massage|nuru|oil\s*massage|oiled)\b|مساج"""),
            "cosplay" to Regex("""\b(?:cosplay|maid|nurse|school\s*girl|school\s*uniform|secretary|office\s*lady|teacher|stewardess|lingerie|pantyhose|stockings)\b"""),
            "bdsm" to Regex("""\b(?:bdsm|bondage|restraint|confinement|spanking|femdom|submissive|masochist\w*|tied\s+up)\b"""),
            "hardcore" to Regex("""\b(?:hardcore|rough\s*sex|nasty)\b"""),
            "interracial" to Regex("""\b(?:interracial|bbc|black\s+man|blacked)\b"""),
            "latina" to Regex("""\b(?:latinas?)\b"""),
            "asian" to Regex("""\b(?:asians?|japanese|korean|jav)\b"""),
            "arab" to Regex("""\b(?:arab|arabic|hijab)\b|سكس\s*عربي|مصري"""),
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

        private val FORMAT_ONLY_CONCEPTS = setOf("uncensored", "english subtitle")

        private val CONCEPT_SPECIFICITY = mapOf(
            "foursome" to 1.90,
            "dp" to 1.90,
            "gangbang" to 1.85,
            "threesome" to 1.80,
            "orgy" to 1.75,
            "slut" to 1.65,
            "cuckold" to 1.55,
            "anal" to 1.50,
            "lesbian" to 1.50,
            "bdsm" to 1.50,
            "stepfamily" to 1.45,
            "squirt" to 1.45,
            "massage" to 1.45,
            "interracial" to 1.45,
            "cosplay" to 1.35,
            "feet" to 1.45,
            "public" to 1.40,
            "hentai" to 1.45,
            "arab" to 1.45,
            "pov" to 1.25,
            "milf" to 1.15,
            "creampie" to 1.05,
            "big tits" to 0.70,
            "big ass" to 0.70,
            "hardcore" to 0.62,
            "blowjob" to 0.68,
            "masturbation" to 0.68,
            "amateur" to 0.74,
            "asian" to 0.62,
            "facial" to 0.74,
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
            "morning", "fight", "fighting", "secret", "room", "body", "really", "turned",
            "ended", "couldn", "cannot", "finally", "completely", "continuously",
        )
    }
}
