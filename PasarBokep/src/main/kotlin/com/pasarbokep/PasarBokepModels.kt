package com.pasarbokep

data class PasarBokepCategory(
    val name: String,
    val path: String,
    val horizontalImages: Boolean = false,
)

data class PasarBokepCard(
    val title: String,
    val url: String,
    val posterUrl: String? = null,
)

data class PasarBokepLoadData(
    val pageUrl: String,
    val referer: String,
)
