package com.samehadaku

import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType

data class SamehadakuCategory(
    val name: String,
    val data: String,
    val paged: Boolean = true,
    val mode: SamehadakuCategoryMode = SamehadakuCategoryMode.Listing
)

data class SamehadakuSeedItem(
    val title: String,
    val url: String,
    val movie: Boolean = false
)

enum class SamehadakuCategoryMode {
    HomeLatest,
    HomeTop,
    HomeMovie,
    Listing,
    Schedule
}

data class SamehadakuMeta(
    val title: String,
    val poster: String? = null,
    val background: String? = null,
    val type: TvType = TvType.Anime,
    val status: ShowStatus? = null,
    val year: Int? = null,
    val score: String? = null,
    val description: String? = null,
    val trailer: String? = null,
    val tags: List<String> = emptyList()
)

data class SamehadakuEpisodeSource(
    val url: String,
    val qualityName: String = "Samehadaku"
)

data class SamehadakuAjaxPlayer(
    val post: String,
    val nume: String,
    val type: String,
    val label: String
)
