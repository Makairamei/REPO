package com.sad25kag.cncverse.entities

data class SearchData(
    val head: String,
    val searchResult: List<SearchResult>,
    val type: Int
)
