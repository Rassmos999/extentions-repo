package eu.kanade.tachiyomi.animeextension.ar.hentaiaraby

import kotlinx.serialization.Serializable

@Serializable
data class VideosListResponse(
    val success: Boolean = false,
    val data: VideosListData? = null,
)

@Serializable
data class VideosListData(
    val items: List<VideoItemDto> = emptyList(),
)

@Serializable
data class SearchVideosResponse(
    val success: Boolean = false,
    val data: SearchVideosData? = null,
)

@Serializable
data class SearchVideosData(
    val videos: List<VideoItemDto> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
data class PaginationDto(
    val page: Int = 1,
    val limit: Int = 25,
    val total: Int = 0,
    val totalPages: Int = 1,
)

@Serializable
data class CategoryVideosResponse(
    val success: Boolean = false,
    val data: CategoryVideosData? = null,
)

@Serializable
data class CategoryVideosData(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val videos: List<VideoItemDto> = emptyList(),
    val currentPage: Int = 1,
    val totalPages: Int = 1,
)

@Serializable
data class VideoItemDto(
    val id: String = "",
    val slug: String = "",
    val title: String = "",
    val description: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val quality: String? = null,
    val episode: Int? = null,
    val createdAt: String? = null,
    val views: Long = 0L,
    val likes: Long = 0L,
)

@Serializable
data class VideoDetailResponse(
    val success: Boolean = false,
    val data: VideoDetailDto? = null,
)

@Serializable
data class VideoDetailDto(
    val id: String = "",
    val slug: String = "",
    val title: String = "",
    val description: String? = null,
    val metaDescription: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val quality: String? = null,
    val episode: Int? = null,
    val createdAt: String? = null,
    val views: Long = 0L,
    val likes: Long = 0L,
    val videoPath: String? = null,
    val mp4Path: String? = null,
    val videoType: String? = null,
    val categories: List<CategoryDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    val user: UserDto? = null,
    val series: SeriesDto? = null,
)

@Serializable
data class CategoryDto(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
)

@Serializable
data class SubtitleDto(
    val language: String = "",
    val filePath: String = "",
)

@Serializable
data class UserDto(
    val username: String? = null,
)

@Serializable
data class SeriesDto(
    val id: String = "",
    val name: String? = null,
    val videos: List<SeriesVideoDto> = emptyList(),
)

@Serializable
data class SeriesVideoDto(
    val id: String = "",
    val slug: String = "",
    val title: String = "",
    val thumbnail: String? = null,
    val duration: String? = null,
    val quality: String? = null,
    val episode: Int? = null,
    val createdAt: String? = null,
)
