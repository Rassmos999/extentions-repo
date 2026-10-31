package eu.kanade.tachiyomi.animeextension.all.javdb

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal open class UriPartFilter(name: String, private val options: List<Pair<String, String>>) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun toUriPart() = options[state].second
}

internal class SectionFilter : UriPartFilter("Section", JavDBFilters.SECTIONS) {
    /** Tag pages exist for censored (`/tags`), uncensored and western only. */
    fun tagPath() = when (toUriPart()) {
        "/uncensored" -> "/uncensored"
        "/western" -> "/western"
        else -> ""
    }
}

internal class ShowFilter : UriPartFilter("Show", JavDBFilters.SHOW)
internal class SortFilter : UriPartFilter("Sort", JavDBFilters.SORT)
internal class RankingFilter : UriPartFilter("Ranking (ignores the filters above)", JavDBFilters.RANKINGS)
internal class SearchTypeFilter : UriPartFilter("Search in", JavDBFilters.SEARCH_TYPES)
internal class SearchSortFilter : UriPartFilter("Search order", JavDBFilters.SEARCH_SORT)
internal class EntitySortFilter : UriPartFilter("Actor/maker/series order (* except first)", JavDBFilters.ENTITY_SORT)

internal class TagCheckBox(name: String, val group: String, val id: String) : AnimeFilter.CheckBox(name)
internal class TagGroupFilter(name: String, tags: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, tags)

internal fun AnimeFilterList.searchType() = firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: "all"

internal fun AnimeFilterList.ranking() = firstInstanceOrNull<RankingFilter>()?.toUriPart().orEmpty()

/** Checked tags as `c4` → [15, 17]. */
internal fun AnimeFilterList.selectedTags(): Map<String, List<String>> = filterIsInstance<TagGroupFilter>()
    .flatMap { group -> group.state.filter { it.state } }
    .groupBy({ it.group }, { it.id })

internal object JavDBFilters {

    fun filterList() = AnimeFilterList(
        AnimeFilter.Header("Text search uses only the two search filters"),
        SearchTypeFilter(),
        SearchSortFilter(),
        EntitySortFilter(),
        AnimeFilter.Separator(),
        RankingFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Browse (* = log in through WebView first)"),
        SectionFilter(),
        ShowFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("* Tags: browse by tags (members only, uses the section above)"),
        *TAG_GROUPS.map { (name, tags) ->
            TagGroupFilter(name, tags.map { (group, id, tag) -> TagCheckBox(tag, group, id) })
        }.toTypedArray(),
    )

    val SECTIONS = listOf(
        "All" to "",
        "Censored" to "/censored",
        "Uncensored *" to "/uncensored",
        "Western" to "/western",
        "FC2 *" to "/fc2",
        "Anime *" to "/anime",
    )

    // `vft`
    val SHOW = listOf(
        "Everything" to "",
        "Downloadable (magnets)" to "1",
        "With subtitles" to "2",
        "With reviews" to "3",
        "Playable" to "4",
        "Playable, Chinese subtitles" to "5",
    )

    // `vst`
    val SORT = listOf(
        "Default" to "",
        "Release date" to "1",
        "Magnet update date" to "2",
        "Playable update date" to "3",
    )

    val RANKINGS = listOf(
        "None" to "",
        "Daily · Censored" to "/rankings/movies?p=daily&t=censored",
        "Weekly · Censored" to "/rankings/movies?p=weekly&t=censored",
        "Monthly · Censored" to "/rankings/movies?p=monthly&t=censored",
        "Daily · Uncensored *" to "/rankings/movies?p=daily&t=uncensored",
        "Weekly · Uncensored *" to "/rankings/movies?p=weekly&t=uncensored",
        "Monthly · Uncensored *" to "/rankings/movies?p=monthly&t=uncensored",
        "Daily · Western" to "/rankings/movies?p=daily&t=western",
        "Weekly · Western" to "/rankings/movies?p=weekly&t=western",
        "Monthly · Western" to "/rankings/movies?p=monthly&t=western",
        "Daily · FC2 *" to "/rankings/movies?p=daily&t=fc2",
        "Weekly · FC2 *" to "/rankings/movies?p=weekly&t=fc2",
        "Monthly · FC2 *" to "/rankings/movies?p=monthly&t=fc2",
        "TOP250 *" to "/rankings/top",
        "Top played *" to "/rankings/playback",
        "FANZA award" to "/rankings/fanza_award",
    )

    // `f` on /search
    val SEARCH_TYPES = listOf(
        "Movies" to "all",
        "Playable movies" to "playable",
        "Single-actress works" to "single",
        "Downloadable (magnets)" to "download",
        "With subtitles" to "cnsub",
        "With preview images" to "preview",
        "ID prefix (ROE → all ROE-xxx)" to "code",
        "Actor → their movies" to "actor",
        "Maker → its movies" to "maker",
        "Series → its movies" to "series",
        "Director → their movies" to "director",
    )

    // `sb` on /search
    val SEARCH_SORT = listOf(
        "Relevance" to "",
        "Release date" to "1",
    )

    // `sort_type` on actor/maker/series/director pages
    val ENTITY_SORT = listOf(
        "Most recent" to "",
        "Top rated *" to "1",
        "Most viewed *" to "2",
        "Most wanted *" to "3",
        "Most watched *" to "4",
    )

    // Tag IDs (`cN=ID`) collected from public movie pages; the /tags picker itself is members-only.
    val TAG_GROUPS: List<Pair<String, List<Triple<String, String, String>>>> = listOf(
        "* Theme" to listOf(
            Triple("c1", "2", "Other Fetish"),
            Triple("c1", "23", "Nasty, Hardcore"),
            Triple("c1", "33", "School Stuff"),
            Triple("c1", "51", "Affair"),
            Triple("c1", "63", "Submissive Men"),
            Triple("c1", "64", "Lesbian"),
            Triple("c1", "103", "Fetish"),
            Triple("c1", "107", "Leg Fetish"),
            Triple("c1", "109", "Busty Fetish"),
            Triple("c1", "111", "Prank"),
            Triple("c1", "132", "Lesbian Kiss"),
            Triple("c1", "135", "Beauty Shop"),
            Triple("c1", "145", "Voyeur"),
            Triple("c1", "157", "Cuckold"),
            Triple("c1", "160", "Sweat"),
            Triple("c1", "185", "Evil"),
            Triple("c1", "187", "Virgin Man"),
        ),
        "* Role" to listOf(
            Triple("c2", "1", "School Girls"),
            Triple("c2", "20", "Married Woman"),
            Triple("c2", "30", "College Students"),
            Triple("c2", "32", "Idol"),
            Triple("c2", "44", "Older Sister"),
            Triple("c2", "47", "Various Professions"),
            Triple("c2", "48", "Slut"),
            Triple("c2", "55", "Mother"),
            Triple("c2", "67", "Gal"),
            Triple("c2", "101", "Bride, Young Wife"),
            Triple("c2", "115", "Nurse"),
            Triple("c2", "118", "Cosplayers"),
            Triple("c2", "139", "Sister"),
            Triple("c2", "140", "Widow"),
            Triple("c2", "142", "Female Doctor"),
            Triple("c2", "158", "Female Teacher"),
            Triple("c2", "169", "Model"),
            Triple("c2", "190", "Hostesses"),
            Triple("c2", "201", "Female Investigator"),
            Triple("c2", "265", "Childhood Friend"),
        ),
        "* Outfit" to listOf(
            Triple("c3", "3", "Glasses"),
            Triple("c3", "43", "Cosplay"),
            Triple("c3", "49", "Lingerie"),
            Triple("c3", "58", "Uniform"),
            Triple("c3", "78", "Sailor Suit"),
            Triple("c3", "84", "Swimsuit"),
            Triple("c3", "106", "Pantyhose"),
            Triple("c3", "113", "Maid"),
            Triple("c3", "114", "OL"),
            Triple("c3", "125", "Skirt"),
            Triple("c3", "126", "School Swimsuit"),
            Triple("c3", "143", "Yukata"),
            Triple("c3", "203", "Stewardess"),
        ),
        "* Body" to listOf(
            Triple("c4", "15", "Mature Woman"),
            Triple("c4", "17", "Big Tits"),
            Triple("c4", "22", "Shaved"),
            Triple("c4", "57", "Butt"),
            Triple("c4", "62", "Tall"),
            Triple("c4", "65", "Slender"),
            Triple("c4", "91", "Breasts"),
            Triple("c4", "98", "Big cock"),
            Triple("c4", "104", "BBW"),
            Triple("c4", "131", "Huge Butt"),
            Triple("c4", "217", "Ultra-Huge Tits"),
        ),
        "* Action" to listOf(
            Triple("c5", "14", "Titty Fuck"),
            Triple("c5", "18", "Creampie"),
            Triple("c5", "24", "3p, 4p"),
            Triple("c5", "36", "Dirty Words"),
            Triple("c5", "37", "Cowgirl"),
            Triple("c5", "38", "Masturbation"),
            Triple("c5", "42", "Urination"),
            Triple("c5", "46", "Facials"),
            Triple("c5", "61", "Footjob"),
            Triple("c5", "68", "Squirting"),
            Triple("c5", "69", "Massage"),
            Triple("c5", "72", "Blow"),
            Triple("c5", "74", "Anus"),
            Triple("c5", "81", "Cum"),
            Triple("c5", "102", "Handjob"),
            Triple("c5", "110", "Promiscuity"),
            Triple("c5", "123", "Deep Throating"),
            Triple("c5", "124", "Kiss"),
            Triple("c5", "195", "Piss Drinking"),
            Triple("c5", "197", "Facesitting"),
        ),
        "* Play" to listOf(
            Triple("c6", "21", "Abuse"),
            Triple("c6", "29", "Restraints"),
            Triple("c6", "50", "Toy"),
            Triple("c6", "53", "Confinement"),
            Triple("c6", "66", "Outdoors"),
            Triple("c6", "71", "Lotion"),
            Triple("c6", "76", "Vibe"),
            Triple("c6", "79", "Humiliation"),
            Triple("c6", "90", "Electric Massager"),
            Triple("c6", "170", "Impromptu Sex"),
            Triple("c6", "232", "Cervix"),
        ),
        "* Category" to listOf(
            Triple("c7", "8", "Best, Omnibus"),
            Triple("c7", "10", "4HR+"),
            Triple("c7", "11", "Drama"),
            Triple("c7", "26", "Image Video"),
            Triple("c7", "28", "Solowork"),
            Triple("c7", "45", "POV"),
            Triple("c7", "80", "Debut Production"),
            Triple("c7", "88", "Amateur"),
            Triple("c7", "97", "Documentary"),
            Triple("c7", "148", "Subjectivity"),
            Triple("c7", "168", "Close Up"),
            Triple("c7", "238", "Original Collaboration"),
            Triple("c7", "250", "How To"),
            Triple("c7", "312", "Beautiful Girl Movie"),
            Triple("c7", "330", "Amateur"),
            Triple("c7", "347", "4K"),
            Triple("c7", "348", "Uncensored Crack"),
        ),
    )
}
