package com.javfc

data class JavFCCategory(
    val name: String,
    val path: String,
    val mode: JavFCPageMode = JavFCPageMode.Path
)

enum class JavFCPageMode {
    Path,
    Search
}

data class JavFCVideoCard(
    val title: String,
    val url: String,
    val poster: String? = null,
    val quality: String? = null
)
