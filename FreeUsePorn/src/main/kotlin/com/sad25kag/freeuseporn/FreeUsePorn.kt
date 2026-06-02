package com.sad25kag.freeuseporn

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FreeUsePorn : MainAPI() {
    override var mainUrl = "https://www.freeuseporn.com"
    override var name = "FreeUsePorn"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/videos/general-freeuse" to "General Freeuse",
        "$mainUrl/videos/free-service" to "Free Service",
        "$mainUrl/videos/mind-control" to "Mind Control",
        "$mainUrl/videos/forced" to "Forced",
        "$mainUrl/videos/japanese" to "Japanese",
        "$mainUrl/videos/time-stop" to "Time Stop",
        "$mainUrl/videos/ignored-sex" to "Ignored Sex",
        "$mainUrl/videos/glory-hole" to "Glory Hole",

        "$mainUrl/videos/hypno" to "Hypno",
        "$mainUrl/videos/maid" to "Maid",
        "$mainUrl/videos/creampie" to "Creampie",
        "$mainUrl/videos/cosplay" to "Cosplay",
        "$mainUrl/videos/amateur" to "Amateur",
        "$mainUrl/videos/compilation" to "Compilation"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): com.lagradost.cloudstream3.HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = "${request.data}?page=${page.coerceAtLeast(1)}"
        val document = app.get(url).document

        val home = document
            .select("div#videos-list a.group")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a[rel=next], a:contains(Next), .pagination a[href*='page=${page + 1}']"
            ) != null || home.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search/videos/$q?page=${page.coerceAtLeast(1)}").document

        val results = document
            .select("div#videos-list a.group")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a[rel=next], a:contains(Next), .pagination a[href*='page=${page + 1}']"
            ) != null
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document
            .selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document
            .select("a[href*='/search/videos/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document
            .select("div.related-video")
            .mapNotNull { it.toRecommendResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(data).document
        val videos = document.select("source[src]")

        if (videos.isEmpty()) return false

        videos.forEach { video ->
            val link = video.attr("src").trim()
            if (link.isBlank()) return@forEach

            val quality = video.attr("res").toIntOrNull()
                ?: video.attr("label").replace(Regex("\\D"), "").toIntOrNull()
                ?: 0

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fixUrl(link),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                }
            )
        }

        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                    img.hasAttr("srcset") -> img.attr("srcset").substringBefore(" ")
                    else -> img.attr("src")
                }
            }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("span.text-sm.font-bold")?.text()?.trim()
            ?: selectFirst("h3")?.text()?.trim()
            ?: return null

        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.let { img ->
                when {
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                    img.hasAttr("srcset") -> img.attr("srcset").substringBefore(" ")
                    else -> img.attr("src")
                }
            }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}