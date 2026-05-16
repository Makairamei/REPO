package com.shortmax

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class ShortMax : MainAPI() {
    override var mainUrl = "https://www.shorttv.live"
    override var name = "ShortMax 📱"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    companion object {
        // 🚀 KOORDINAT ASLI HASIL BURUANMU!
        private const val BASE_API_URL = "https://api.shorttv.live" 
        
        const val ENDPOINT_RECOMMEND = "$BASE_API_URL/gapi/v1/movie/recommendList"
        const val ENDPOINT_SEARCH = "$BASE_API_URL/gapi/v1/movie/search"
        const val ENDPOINT_DETAIL = "$BASE_API_URL/gapi/v1/movie/detail"
        const val ENDPOINT_VIDEO = "$BASE_API_URL/gapi/v1/movie/episodePlayInfo"
    }

    // Menu Halaman Utama
    override val mainPage = mainPageOf(
        ENDPOINT_RECOMMEND to "Rekomendasi Utama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Menembak endpoint dengan parameter page dan pageSize standar API mereka
        val targetUrl = "${request.data}?page=$page&pageSize=20"
        val responseText = app.get(targetUrl).text
        val json = tryParseJson<ShortPlayListResponse>(responseText)

        val homeResults = json?.results.orEmpty().mapNotNull { item ->
            val id = item.shortPlayId ?: return@mapNotNull null
            newTvSeriesSearchResponse(item.name.orEmpty(), id.toString(), TvType.AsianDrama) {
                this.posterUrl = item.cover
            }
        }.distinctBy { it.url }

        val hasNextPage = json?.isEnd == false
        return newHomePageResponse(HomePageList(request.name, homeResults), hasNextPage)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        // Menyesuaikan query parameter sesuai intercept pencarian asli
        val targetUrl = "$ENDPOINT_SEARCH?keyword=${URLEncoder.encode(cleanQuery, "UTF-8")}&page=1&pageSize=20"
        val responseText = app.get(targetUrl).text
        val json = tryParseJson<ShortPlayListResponse>(responseText)

        return json?.results.orEmpty().mapNotNull { item ->
            val id = item.shortPlayId ?: return@mapNotNull null
            newTvSeriesSearchResponse(item.name.orEmpty(), id.toString(), TvType.AsianDrama) {
                this.posterUrl = item.cover
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val playId = url.trim()
        if (playId.isBlank()) throw ErrorLoadingException("ID Drama Tidak Valid")

        // Memanggil detail drama pakai shortPlayId asli
        val targetUrl = "$ENDPOINT_DETAIL?shortPlayId=$playId"
        val responseText = app.get(targetUrl).text
        val detailData = tryParseJson<ShortPlayDetailResponse>(responseText)?.data 
            ?: throw ErrorLoadingException("Gagal Memuat Detail Konten")

        val title = detailData.shortPlayName?.takeIf { it.isNotBlank() } ?: "ShortDrama"
        val poster = detailData.picUrl
        val plotSummary = detailData.summary
        val totalEp = detailData.totalEpisodes ?: 1

        val episodes = (1..totalEp).map { epNum ->
            val loadDataPayload = EpisodePayload(playId = playId, episodeNum = epNum).toJsonString()
            newEpisode(loadDataPayload) {
                this.name = "Episode $epNum"
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = plotSummary
            this.tags = detailData.labelResponseList.orEmpty().mapNotNull { it.labelName }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseJson<EpisodePayload>(data)
        val playId = payload.playId ?: return false
        val epNum = payload.episodeNum ?: return false

        // Memanggil link streaming berdasarkan shortPlayId dan episodeNum asli
        val targetUrl = "$ENDPOINT_VIDEO?shortPlayId=$playId&episodeNum=$epNum"
        val responseText = app.get(targetUrl).text
        val videoObj = tryParseJson<VideoPlayResponse>(responseText)?.episode ?: return false
        val videoMap = videoObj.videoUrl ?: return false

        videoMap.forEach { (qualityKey, streamUrl) ->
            if (!streamUrl.isNullOrBlank()) {
                val mappedQuality = when (qualityKey) {
                    "video_1080" -> Qualities.P1080.value
                    "video_720"  -> Qualities.P720.value
                    "video_480"  -> Qualities.P480.value
                    else         -> Qualities.Unknown.value
                }

                val cleanLabel = qualityKey.replace("video_", "") + "p"

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "ShortMax - $cleanLabel",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = mappedQuality
                        this.referer = "$mainUrl/"
                    }
                )
            }
        }
        return true
    }

    // --- STRUKTUR MODEL DATA KELAS ---
    data class ShortPlayListResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("isEnd") val isEnd: Boolean? = null,
        @JsonProperty("total") val total: Int? = null,
        @JsonProperty("results") val results: List<ShortPlayItem>? = null
    )

    data class ShortPlayItem(
        @JsonProperty("shortPlayId") val shortPlayId: Int? = null,
        @JsonProperty("shortPlayCode") val shortPlayCode: Long? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("summary") val summary: String? = null,
        @JsonProperty("collectNum") val collectNum: Long? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("horizontalCover") val horizontalCover: String? = null,
        @JsonProperty("genre") val genre: List<String>? = null
    )

    data class ShortPlayDetailResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("data") val data: DetailDataHub? = null
    )

    data class DetailDataHub(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("shortPlayName") val shortPlayName: String? = null,
        @JsonProperty("summary") val summary: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("picUrl") val picUrl: String? = null,
        @JsonProperty("lockBegin") val lockBegin: Int? = null,
        @JsonProperty("updateEpisode") val updateEpisode: Int? = null,
        @JsonProperty("labelResponseList") val labelResponseList: List<LabelItem>? = null
    )

    data class LabelItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("labelName") val labelName: String? = null
    )

    data class VideoPlayResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("shortPlayId") val shortPlayId: Int? = null,
        @JsonProperty("episode") val episode: EpisodeStreamContainer? = null
    )

    data class EpisodeStreamContainer(
        @JsonProperty("episodeNum") val episodeNum: Int? = null,
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("needDecrypt") val needDecrypt: Boolean? = null,
        @JsonProperty("videoUrl") val videoUrl: Map<String, String>? = null
    )

    data class EpisodePayload(
        @JsonProperty("playId") val playId: String? = null,
        @JsonProperty("episodeNum") val episodeNum: Int? = null
    ) {
        fun toJsonString(): String = this.toJson()
    }
}
