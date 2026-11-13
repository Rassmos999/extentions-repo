package eu.kanade.tachiyomi.animeextension.all.jable

internal interface Intl {
    val popular: String
    val latestUpdate: String
    val sortLatestUpdate: String
    val sortMostView: String
    val sortMostFavorite: String
    val sortRecentBest: String
    val hotDay: String
    val hotWeek: String
    val hotMonth: String
    val hotAll: String
    val filterPopularSortTitle: String
    val filterTagsSortTitle: String
    val filterTagTitle: String
    val filterCategoryTitle: String
    val catBdsm: String
    val catSexOnly: String
    val catChineseSubtitle: String
    val catInsult: String
    val catUniform: String
    val catRoleplay: String
    val catPrivateCam: String
    val catUncensored: String
    val catPov: String
    val catGroupsex: String
    val catPantyhose: String
    val catLesbian: String
}

internal class JableIntl private constructor(delegate: Intl) : Intl by delegate {
    constructor(lang: String) : this(
        when (lang) {
            "zh" -> ZH()
            "ja" -> JA()
            "en" -> EN()
            else -> ZH()
        },
    )
}

internal class ZH : Intl {
    override val popular: String = "熱度優先"
    override val latestUpdate: String = "新片優先"
    override val sortLatestUpdate: String = "最近更新"
    override val sortMostView: String = "最多觀看"
    override val sortMostFavorite: String = "最高收藏"
    override val sortRecentBest: String = "近期最佳"
    override val hotDay: String = "今日熱門"
    override val hotWeek: String = "本周熱門"
    override val hotMonth: String = "本月熱門"
    override val hotAll: String = "所有時間"
    override val filterPopularSortTitle: String = "熱門排序"
    override val filterTagsSortTitle: String = "通用排序"
    override val filterTagTitle: String = "標籤"
    override val filterCategoryTitle: String = "主題"
    override val catBdsm: String = "主奴調教"
    override val catSexOnly: String = "直接開啪"
    override val catChineseSubtitle: String = "中文字幕"
    override val catInsult: String = "凌辱快感"
    override val catUniform: String = "制服誘惑"
    override val catRoleplay: String = "角色劇情"
    override val catPrivateCam: String = "盜攝偷拍"
    override val catUncensored: String = "無碼解放"
    override val catPov: String = "男友視角"
    override val catGroupsex: String = "多P群交"
    override val catPantyhose: String = "絲襪美腿"
    override val catLesbian: String = "女同歡愉"
}

internal class JA : Intl {
    override val popular: String = "人気優先"
    override val latestUpdate: String = "新作優先"
    override val sortLatestUpdate: String = "最近更新"
    override val sortMostView: String = "最も見ら"
    override val sortMostFavorite: String = "最もお気に入"
    override val sortRecentBest: String = "最近ベスト"
    override val hotDay: String = "今日のヒット"
    override val hotWeek: String = "今週のヒット"
    override val hotMonth: String = "今月のヒット"
    override val hotAll: String = "全ての時間"
    override val filterPopularSortTitle: String = "人気ソート"
    override val filterTagsSortTitle: String = "一般ソート"
    override val filterTagTitle: String = "タグ"
    override val filterCategoryTitle: String = "テーマ"
    override val catBdsm: String = "ビーディーエスエム"
    override val catSexOnly: String = "直接せいこう"
    override val catChineseSubtitle: String = "中国語字幕"
    override val catInsult: String = "陵辱"
    override val catUniform: String = "職業色々"
    override val catRoleplay: String = "ドラマ"
    override val catPrivateCam: String = "個人撮影"
    override val catUncensored: String = "無修正"
    override val catPov: String = "彼氏視点"
    override val catGroupsex: String = "グループセックス"
    override val catPantyhose: String = "パンスト"
    override val catLesbian: String = "レズ"
}

internal class EN : Intl {
    override val popular: String = "Hot"
    override val latestUpdate: String = "Newest"
    override val sortLatestUpdate: String = "Recent Update"
    override val sortMostView: String = "Most Viewed"
    override val sortMostFavorite: String = "Most Favorite"
    override val sortRecentBest: String = "Best Recently"
    override val hotDay: String = "Today"
    override val hotWeek: String = "This Week"
    override val hotMonth: String = "This Month"
    override val hotAll: String = "All Time"
    override val filterPopularSortTitle: String = "Popular Sorting"
    override val filterTagsSortTitle: String = "General Sorting"
    override val filterTagTitle: String = "Tag"
    override val filterCategoryTitle: String = "Category"
    override val catBdsm: String = "BDSM"
    override val catSexOnly: String = "Sex Only"
    override val catChineseSubtitle: String = "Chinese Subtitle"
    override val catInsult: String = "Insult"
    override val catUniform: String = "Uniform"
    override val catRoleplay: String = "Roleplay"
    override val catPrivateCam: String = "Private Cam"
    override val catUncensored: String = "Uncensored"
    override val catPov: String = "POV"
    override val catGroupsex: String = "Group Sex"
    override val catPantyhose: String = "Pantyhose"
    override val catLesbian: String = "Lesbian"
}
