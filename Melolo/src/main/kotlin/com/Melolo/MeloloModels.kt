package com.Melolo

/**
 * 📦 ARCHITECTURE LAYER: DATA MODELS
 */

data class ScrapedSearch(
    val title: String,
    val url: String,
    val poster: String? = null,
    val rating: String? = null,
    val episodeText: String? = null,
    val isMovie: Boolean = false,
    val isDub: Boolean = false
)

data class ScrapedDetail(
    val title: String,
    val poster: String,
    val banner: String? = null,
    val description: String = "",
    val year: Int? = null,
    val rating: String? = null,
    val statusText: String? = null,
    val tags: List<String> = emptyList(),
    val trailer: String? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val isComingSoon: Boolean = false
)

data class ScrapedEpisode(
    val name: String? = null,
    val url: String,
    val episodeNum: Int? = null,
    val season: Int? = null,
    val poster: String? = null,
    val description: String? = null,
    val runtime: Int? = null
)

data class ScrapedActor(
    val name: String,
    val image: String? = null
)
