package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class YunshanID : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshan ID 🏔️"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "latest" to "Rilisan Terbaru",
        "ongoing" to "Donghua Ongoing",
        "completed" to "Donghua Completed",
        "movie" to "Donghua Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl/api/donghuas").text
        val allDonghuas = parseJson<List<DonghuaResponse>>(response)

        val items = when (request.data) {
            "ongoing" -> allDonghuas.filter { it.status == "On-Going" }.map { it.toSearchResponse() }
            "completed" -> allDonghuas.filter { it.status == "Completed" }.map { it.toSearchResponse() }
            "movie" -> allDonghuas.filter { it.type?.contains("Movie", ignoreCase = true) == true }.map { it.toSearchResponse() }
            else -> allDonghuas.sortedByDescending { it.lastUpdate }.map { it.toSearchResponse() }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/api/donghuas").text
        val allDonghuas = parseJson<List<DonghuaResponse>>(response)
        return allDonghuas
            .filter { it.title?.contains(query, ignoreCase = true) == true }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.split("/").last()
        val response = app.get("$mainUrl/api/donghua/$id").text
        val detail = parseJson<DonghuaDetailResponse>(response)

        val title = detail.title ?: ""
        val poster = detail.posterUrl ?: detail.poster ?: ""
        val description = detail.synopsis
        val type = if (detail.type?.contains("Movie", ignoreCase = true) == true) TvType.Movie else TvType.Anime

        val episodes = detail.episodes?.map { ep ->
            newEpisode(ep) {
                this.name = "Episode ${ep.epNumber}"
                this.episode = ep.epNumber
                this.data = mapper.writeValueAsString(ep)
            }
        }?.sortedByDescending { it.episode } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = parseJson<Episode>(data)

        ep.videoUrl?.let { url ->
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }

        ep.servers?.forEach { server ->
            val serverUrl = server.url ?: server.embedUrl
            if (!serverUrl.isNullOrBlank()) {
                loadExtractor(serverUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun DonghuaResponse.toSearchResponse(): SearchResponse {
        val type = if (this.type?.contains("Movie", ignoreCase = true) == true) TvType.Movie else TvType.Anime
        return newMovieSearchResponse(this.title ?: "", "$mainUrl/api/donghua/${this.id}", type) {
            this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.poster
        }
    }

    data class DonghuaResponse(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("last_update") val lastUpdate: String?,
        @JsonProperty("view_count") val viewCount: Int?
    )

    data class DonghuaDetailResponse(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("episodes") val episodes: List<Episode>?
    )

    data class Episode(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("ep_number") val epNumber: Int?,
        @JsonProperty("video_url") val videoUrl: String?,
        @JsonProperty("servers") val servers: List<Server>?
    )

    data class Server(
        @JsonProperty("name") val name: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("embed_url") val embedUrl: String?
    )
}