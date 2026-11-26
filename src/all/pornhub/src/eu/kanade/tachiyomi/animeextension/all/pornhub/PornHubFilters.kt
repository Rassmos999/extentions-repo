package eu.kanade.tachiyomi.animeextension.all.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

internal object PornHubFilters {

    const val FOR_YOU_SORT = "for_you"

    fun findCategoryValueByName(tagName: String): String? {
        val needle = tagName.trim().lowercase()
        if (needle.isBlank()) return null
        return CATEGORY_OPTIONS.firstOrNull { (label, value) ->
            value.isNotBlank() && (
                label.equals(needle, ignoreCase = true) ||
                    label.lowercase().contains(needle) ||
                    needle.contains(label.lowercase())
                )
        }?.second
    }

    data class FilterData(
        val sort: String = "ht",
        val categories: List<String> = emptyList(),
        val orientation: String = "",
        val production: String = "",
        val duration: String = "",
        val quality: String = "",
        val pornstar: String = "",
        val targetType: String = "video",
        val targetName: String = "",
    ) {
        val category: String get() = categories.firstOrNull().orEmpty()
    }

    fun createFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply in Search (empty query OK)."),
        SortFilter(),
        CategoryGroup(CATEGORY_OPTIONS.filter { it.second.isNotBlank() }.map { CategoryCheckBox(it.first, it.second) }),
        OrientationFilter(),
        ProductionFilter(),
        DurationFilter(),
        QualityFilter(),
        AnimeFilter.Separator(),
        PornstarFilter(),
        TargetTypeFilter(),
        TargetNameFilter(),
    )

    fun extract(filters: AnimeFilterList): FilterData {
        var sort = "ht"
        var categories = emptyList<String>()
        var orientation = ""
        var production = ""
        var duration = ""
        var quality = ""
        var pornstar = ""
        var targetType = "video"
        var targetName = ""

        for (filter in filters) {
            when (filter) {
                is SortFilter -> sort = filter.selected
                is CategoryGroup -> categories = filter.state.filter { it.state }.map { it.value }.distinct().take(4)
                is OrientationFilter -> orientation = filter.selected
                is ProductionFilter -> production = filter.selected
                is DurationFilter -> duration = filter.selected
                is QualityFilter -> quality = filter.selected
                is PornstarFilter -> pornstar = filter.selected
                is TargetTypeFilter -> targetType = filter.selected
                is TargetNameFilter -> targetName = filter.state.trim()
                else -> {}
            }
        }
        return FilterData(
            sort = sort,
            categories = categories,
            orientation = orientation,
            production = production,
            duration = duration,
            quality = quality,
            pornstar = pornstar,
            targetType = targetType,
            targetName = targetName,
        )
    }

    private open class SelectFilter(
        displayName: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, options.map { it.first }.toTypedArray()) {
        val selected: String get() = options[state].second
    }

    private class SortFilter :
        SelectFilter(
            "Sort",
            arrayOf(
                "Hot / Trending" to "ht",
                "For You (Smart Taste)" to FOR_YOU_SORT,
                "Most Recent" to "cm",
                "Most Viewed" to "mv",
                "Top Rated" to "tr",
                "Longest" to "lg",
                "Most Relevant" to "mr",
            ),
        )

    private class OrientationFilter :
        SelectFilter(
            "Orientation",
            arrayOf(
                "Straight (Default)" to "",
                "Gay" to "gay",
                "Transgender" to "transgender",
            ),
        )

    private class ProductionFilter :
        SelectFilter(
            "Production",
            arrayOf(
                "All" to "",
                "Professional" to "professional",
                "Homemade / Amateurs" to "homemade",
            ),
        )

    private class DurationFilter :
        SelectFilter(
            "Duration",
            arrayOf(
                "Any" to "",
                "Under 10 min" to "max:10",
                "10+ min" to "min:10",
                "20+ min" to "min:20",
                "30+ min" to "min:30",
            ),
        )

    private class QualityFilter :
        SelectFilter(
            "Quality",
            arrayOf(
                "Any" to "",
                "HD only" to "hd",
            ),
        )

    private class TargetTypeFilter :
        SelectFilter(
            "Search Target",
            arrayOf(
                "Videos (Default)" to "video",
                "Pornstar (/pornstar/{name})" to "pornstar",
                "Model (/model/{name})" to "model",
                "Channel (/channels/{name})" to "channel",
                "User (/users/{name})" to "user",
            ),
        )

    private class TargetNameFilter : AnimeFilter.Text("Pornstar / Model / Channel / User Name")

    private class CategoryCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    private class CategoryGroup(categories: List<CategoryCheckBox>) : AnimeFilter.Group<CategoryCheckBox>("Categories (multi-select, up to 4)", categories)

    private val CATEGORY_OPTIONS = arrayOf(
        "Any" to "",
        "18-25 (Teen)" to "path:teen",
        "60FPS" to "105",
        "Amateur" to "3",
        "Anal" to "35",
        "Arab" to "98",
        "Asian" to "1",
        "Babe" to "path:babe",
        "Babysitter (18+)" to "89",
        "BBW" to "6",
        "Behind The Scenes" to "141",
        "Big Ass" to "4",
        "Big Dick" to "7",
        "Big Tits" to "8",
        "Bisexual Male" to "76",
        "Blonde" to "9",
        "Blowjob" to "13",
        "Bondage" to "10",
        "Brazilian" to "102",
        "British" to "96",
        "Brunette" to "11",
        "Bukkake" to "14",
        "Cartoon" to "86",
        "Casting" to "90",
        "Celebrity" to "12",
        "Closed Captions" to "732",
        "College (18+)" to "path:college",
        "Compilation" to "57",
        "Cosplay" to "241",
        "Creampie" to "15",
        "Cuckold" to "242",
        "Cumshot" to "16",
        "Czech" to "100",
        "Double Penetration" to "72",
        "Ebony" to "17",
        "Euro" to "55",
        "Exclusive" to "115",
        "Feet" to "93",
        "Female Orgasm" to "502",
        "Fetish" to "18",
        "Fingering" to "592",
        "Fisting" to "19",
        "French" to "94",
        "Funny" to "32",
        "Gaming" to "881",
        "Gangbang" to "80",
        "German" to "95",
        "Handjob" to "20",
        "Hardcore" to "21",
        "HD Porn" to "38",
        "Hentai" to "path:hentai",
        "Indian" to "101",
        "Interracial" to "25",
        "Italian" to "97",
        "Japanese" to "111",
        "Korean" to "103",
        "Latina" to "26",
        "Lesbian" to "27",
        "Massage" to "78",
        "Masturbation" to "22",
        "Mature" to "28",
        "MILF" to "29",
        "Muscular Men" to "512",
        "Music" to "121",
        "Old/Young (18+)" to "181",
        "Orgy" to "2",
        "Parody" to "201",
        "Party" to "53",
        "Pissing" to "211",
        "Podcast" to "891",
        "Pornstar" to "path:pornstar",
        "POV" to "41",
        "Public" to "24",
        "Pussy Licking" to "131",
        "Reality" to "31",
        "Red Head" to "42",
        "Role Play" to "81",
        "Romantic" to "522",
        "Rough Sex" to "67",
        "Russian" to "99",
        "School (18+)" to "88",
        "Small Tits" to "59",
        "Smoking" to "91",
        "Solo Female" to "492",
        "Solo Male" to "92",
        "Squirt" to "69",
        "Step Fantasy" to "444",
        "Strap On" to "542",
        "Striptease" to "33",
        "Tattooed Women" to "562",
        "Threesome" to "65",
        "Toys" to "23",
        "Transgender" to "83",
        "Verified Amateurs" to "138",
        "Verified Couples" to "482",
        "Verified Models" to "139",
        "Vintage" to "43",
        "Webcam" to "61",
    )

    private class PornstarFilter :
        SelectFilter(
            "Popular Pornstars",
            arrayOf(
                "Any" to "",
                "Angela White" to "angela-white",
                "Riley Reid" to "riley-reid",
                "Mia Khalifa" to "mia-khalifa",
                "Brandi Love" to "brandi-love",
                "Abella Danger" to "abella-danger",
                "Lana Rhoades" to "lana-rhoades",
                "Adriana Chechik" to "adriana-chechik",
                "Sasha Grey" to "sasha-grey",
                "Lisa Ann" to "lisa-ann",
                "Eva Elfie" to "eva-elfie",
                "Nicole Aniston" to "nicole-aniston",
                "Lena Paul" to "lena-paul",
                "Violet Myers" to "violet-myers",
                "Autumn Falls" to "autumn-falls",
                "Alexis Texas" to "alexis-texas",
                "Asa Akira" to "asa-akira",
                "Madison Ivy" to "madison-ivy",
                "Kendra Lust" to "kendra-lust",
                "Ava Addams" to "ava-addams",
                "Elsa Jean" to "elsa-jean",
                "Dillion Harper" to "dillion-harper",
                "Leah Gotti" to "leah-gotti",
                "Mia Malkova" to "mia-malkova",
                "Johnny Sins" to "johnny-sins",
            ),
        )
}
