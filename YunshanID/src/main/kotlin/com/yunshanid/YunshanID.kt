package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*

class YunshanID : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshan ID 🏔️"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        const val API_BASE = "https://yunshanid.site/api"
    }

    // Mengelompokkan kategori di CloudStream menggunakan filter internal JSON
    override val mainPage = mainPageOf(
        "latest" to "Rilisan Terbaru",
        "ongoing" to "Donghua Ongoing",
        "completed" to "Donghua Completed",
        "movie" to "Donghua Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Panggil langsung endpoint daftar donghua
        val response = app.get("$API_BASE/donghuas").text
        val items = tryParseJson<List<YunshanItem>>(response) ?: emptyList()
        
        // Lakukan pemfilteran data secara lokal berdasarkan request halaman
        val filteredItems = when (request.data) {
            "ongoing" -> items.filter { it.status?.contains("On-Going", true) == true }
            "completed" -> items.filter { it.status?.contains("Completed", true) == true }
            "movie" -> items.filter { it.type?.contains("Movie", true) == true }
            else -> items // "latest" menampilkan semua
        }

        val homeResults = filteredItems.map { item ->
            newAnimeSearchResponse(item.title, item.id.toString(), TvType.Anime) {
                this.posterUrl = item.posterUrl ?: item.poster
            }
        }

        // Karena API mereka langsung melempar semua data, kita matikan hasNext (false)
        return newHomePageResponse(HomePageList(request.name, homeResults), false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$API_BASE/donghuas").text
        val items = tryParseJson<List<YunshanItem>>(response) ?: emptyList()

        // Fitur pencarian lokal: mencocokkan judul secara case-insensitive
        return items.filter { it.title.contains(query, true) }.map { item ->
            newAnimeSearchResponse(item.title, item.id.toString(), TvType.Anime) {
                this.posterUrl = item.posterUrl ?: item.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url
        // Mengambil detail berdasarkan ID donghua yang dipilih
        val response = app.get("$API_BASE/donghua/$id").text
        
        // Mengantisipasi jika API mengembalikan objek tunggal atau array isi 1
        val item = tryParseJson<YunshanItem>(response) 
            ?: tryParseJson<List<YunshanItem>>(response)?.firstOrNull()
            ?: throw ErrorLoadingException("Detail Donghua gagal dimuat")

        val title = item.title
        val poster = item.posterUrl ?: item.poster
        val description = item.synopsis
        val tvType = if (item.type?.contains("Movie", true) == true) TvType.Movie else TvType.TvSeries

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, "$id-1") {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Mengurutkan episode dari nomor terkecil ke terbesar
            val episodes = item.episodesMap?.sorted()?.map { epNum ->
                newEpisode("$id-$epNum") {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("-")
        if (parts.size < 2) return false
        val animeId = parts[0]
        val epNum = parts[1]

        // 1. Tembak langsung rute api/watch hasil temuanmu!
        val watchResponse = app.get("$API_BASE/watch/$animeId/$epNum").text

        // 2. Mencari URL pemutar video (seperti ok.ru atau iframe lainnya) di dalam text response
        // Menggunakan regex untuk menangkap link url di dalam string JSON tersebut
        val linkRegex = """https?://[^\s"'\\]+""".toRegex()
        val foundLinks = linkRegex.findAll(watchResponse).map { it.value }.toList()

        var linkFound = false
        for (link in foundLinks) {
            // Jika link mengarah ke provider video yang dikenali CloudStream (misal ok.ru)
            if (link.contains("ok.ru") || link.contains("odnoklassniki") || link.contains("video")) {
                loadExtractor(link, subtitleCallback, callback)
                linkFound = true
            }
        }

        // Jika extractor bawaan tidak sengaja melewatkannya, kita coba paksa muat link pertama yang valid
        if (!linkFound && foundLinks.isNotEmpty()) {
            val primaryLink = foundLinks.first()
            loadExtractor(primaryLink, subtitleCallback, callback)
            linkFound = true
        }

        return linkFound
    }
}

data class YunshanItem(
    val id: Int,
    val title: String,
    val synopsis: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    val poster: String? = null,
    val status: String? = null,
    val type: String? = null,
    @JsonProperty("episodes_map") val episodesMap: List<Int>? = null
)