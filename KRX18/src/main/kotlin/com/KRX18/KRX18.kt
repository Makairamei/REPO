package com.KRX18

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element


class KRX18 : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "KRX 18"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "movies" to "Recently added",
        "genre/eng-sub" to "English SUB",
        "genre/korea" to "Korea",
        "genre/china" to "China",
        "genre/japan" to "Japan",
        "genre/thailand" to "Thailand",
        "genre/philippines" to "Philippines",
        
        // Tambahan kategori baru sesuai screenshot
        "genre/australia" to "Australia",
        "genre/belgium" to "Belgium",
        "genre/canada" to "Canada",
        "genre/denmark" to "Denmark",
        "genre/france" to "France",
        "genre/germany" to "Germany",
        "genre/india" to "India",
        "genre/indonesia" to "Indonesia",
        "genre/italy" to "Italy",
        "genre/netherlands" to "Netherlands",
        "genre/poland" to "Poland",
        "genre/russia" to "Russia",
        "genre/singapore" to "Singapore",
        "genre/spain" to "Spain",
        "genre/switzerland" to "Switzerland",
        "genre/taiwan" to "Taiwan",
        "genre/uk" to "UK",
        "genre/usa" to "USA"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get("$mainUrl/${request.data}/?page/$page").document
        val home = document.select("#archive-content article,div.items.normal article")
            .map { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text()
        val href = fixUrl(this.select("h3 a").attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val document = app.get("$mainUrl/search/videos?search_query=$query").document
        val searchResponse = document.select("div.card.border-0").map { it.toSearchResult() }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document
        val title = document.selectFirst("div.data h1")?.text() ?: "Unknown"
        val poster =
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: "Unknown"
        val tags =
            document.select("div:nth-child(2) > div.video-details__item_links a").map { it.text() }
        val description = document.selectFirst("div.wp-content p")?.text()?.trim() ?: "Unknown"
        val recommendations = document.select("div.card.border-0").map { it.toSearchResult() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val req = app.get(data).document
        req.select("ul#playeroptionsul li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            if (!nume.contains("trailer")) {
                val source = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = mainUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).parsed<ResponseHash>().embed_url
                loadExtractor(source,subtitleCallback,callback)
                Log.d("Phisher source",source)
            }
        }
        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )
}
