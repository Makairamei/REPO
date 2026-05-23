package com.sad25kag.cncverse.entities

data class EpisodesData(
    val episodes: List<Episode>?,
    val nextPage: Int,
    val nextPageSeason: String,
    val nextPageShow: Int,
)
