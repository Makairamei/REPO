package com.nonton01

data class Nonton01Card(
    val title: String,
    val url: String,
    val poster: String? = null,
    val quality: String? = null,
    val rating: String? = null,
    val duration: String? = null
)

data class Nonton01Episode(
    val name: String,
    val url: String,
    val poster: String? = null
)
