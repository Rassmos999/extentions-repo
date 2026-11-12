package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeWorldIndiaFilters {

    private class SectionFilter :
        UriPartFilter(
            "Section",
            arrayOf(
                Pair("Series", "/series"),
                Pair("Movies", "/movies"),
            ),
        )

    private class LanguageFilter :
        UriPartFilter(
            "Language",
            arrayOf(
                Pair("Default (Extension Language)", ""),
                Pair("All Languages", "__all__"),
                Pair("Hindi", "/category/language/hindi"),
                Pair("English", "/category/language/english"),
                Pair("Japanese", "/category/language/japanese"),
                Pair("Tamil", "/category/language/tamil"),
                Pair("Telugu", "/category/language/telugu"),
                Pair("Malayalam", "/category/language/malayalam"),
                Pair("Bengali", "/category/language/bengali"),
                Pair("Kannada", "/category/language/kannada"),
                Pair("Korean", "/category/language/korean"),
                Pair("Marathi", "/category/language/marathi"),
            ),
        )

    private class NetworkFilter :
        UriPartFilter(
            "Network",
            arrayOf(
                Pair("Any", ""),
                Pair("Crunchyroll", "/category/network/crunchyroll"),
                Pair("Netflix", "/category/network/netflix"),
                Pair("Prime Video", "/category/network/prime-video"),
                Pair("Disney+", "/category/network/disney"),
                Pair("Disney Channel", "/category/network/disney-channel"),
                Pair("Cartoon Network", "/category/network/cartoon-network"),
                Pair("Hungama TV", "/category/network/hungama-tv"),
                Pair("Sony YAY", "/category/network/sony-yay"),
            ),
        )

    private class FranchiseFilter :
        UriPartFilter(
            "Franchise",
            arrayOf(
                Pair("Any", ""),
                Pair("Naruto", "/category/franchise/naruto"),
                Pair("Dragon Ball", "/category/franchise/dragon-ball"),
                Pair("Pokemon", "/category/franchise/pokemon"),
                Pair("Ben 10", "/category/franchise/ben-10"),
                Pair("Beyblade", "/category/franchise/beyblade"),
                Pair("Doraemon", "/category/franchise/doraemon"),
                Pair("Shinchan", "/category/franchise/shinchan"),
                Pair("Transformers", "/category/franchise/transformers"),
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("Any", ""),
                Pair("Action", "/category/genre/action"),
                Pair("Adventure", "/category/genre/adventure"),
                Pair("Animation", "/category/genre/animation"),
                Pair("Comedy", "/category/genre/comedy"),
                Pair("Drama", "/category/genre/drama"),
                Pair("Fantasy", "/category/genre/fantasy"),
                Pair("Isekai", "/category/genre/isekai"),
                Pair("Martial Arts", "/category/genre/martial-arts"),
                Pair("Mystery", "/category/genre/mystery"),
                Pair("Romance", "/category/genre/romance"),
                Pair("Sci-Fi", "/category/genre/sci-fi"),
                Pair("Shounen", "/category/genre/shounen"),
                Pair("Supernatural", "/category/genre/supernatural"),
                Pair("Thriller", "/category/genre/thriller"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    val filters: AnimeFilterList
        get() = AnimeFilterList(
            AnimeFilter.Header("Note: Text search ignores browse filters"),
            SectionFilter(),
            LanguageFilter(),
            NetworkFilter(),
            FranchiseFilter(),
            GenreFilter(),
        )

    fun resolveFilterPath(filters: AnimeFilterList, defaultLangSlug: String): String {
        val network = filters.filterIsInstance<NetworkFilter>().firstOrNull()?.toUriPart().orEmpty()
        if (network.isNotEmpty()) return network

        val franchise = filters.filterIsInstance<FranchiseFilter>().firstOrNull()?.toUriPart().orEmpty()
        if (franchise.isNotEmpty()) return franchise

        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        if (genre.isNotEmpty()) return genre

        val langPath = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.toUriPart().orEmpty()
        if (langPath == "__all__") {
            return filters.filterIsInstance<SectionFilter>().firstOrNull()?.toUriPart() ?: "/series"
        }
        if (langPath.isNotEmpty()) return langPath

        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.toUriPart() ?: "/series"
        if (section != "/series") return section

        return if (defaultLangSlug.isNotBlank()) {
            "/category/language/$defaultLangSlug"
        } else {
            "/series"
        }
    }
}
