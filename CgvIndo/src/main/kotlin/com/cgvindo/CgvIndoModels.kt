package com.cgvindo

internal data class CgvIndoCard(
    val title: String,
    val url: String,
    val poster: String? = null,
    val typeHint: String? = null
)

internal data class CgvIndoEpisode(
    val name: String,
    val url: String,
    val poster: String? = null
)

internal data class CgvIndoPlayerOption(
    val post: String?,
    val nume: String?,
    val type: String?,
    val server: String? = null,
    val label: String? = null
)
