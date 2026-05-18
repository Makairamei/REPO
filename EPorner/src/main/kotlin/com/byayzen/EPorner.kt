package com.byayzen

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class EPorner : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "EPorner"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most recent",
        "$mainUrl/most-viewed/" to "Most viewed",
        "$mainUrl/top-rated/" to "Top rated",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/tag/cowgirl/" to "Cowgirl",
        "$mainUrl/tag/riding/" to "Riding",
        "$mainUrl/tag/turkish/" to "Turkish",
        "$mainUrl/cat/housewives/" to "Housewives"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/$page/"
        val home = app.get(url).document.select("div#vidresults div.mb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("p.title a")?.text() ?: return null
        val href = mainUrl + (this.selectFirst("p.title a")?.attr("href") ?: return null)
        val poster = this.selectFirst("div.img img")?.attr("src") ?: ""
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}/"
        return app.get(url).document.select("div#vidresults div.mb").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCaster: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var videoFound = false
        val url = data
        val resolver = WebViewResolver(
            """www\.eporner\.com/xhr/video/.*""".toRegex(),
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            useOkhttp = true
        )

        try {
            val capturedUrl = app.get(url, interceptor = resolver).url
            if (capturedUrl.contains("/xhr/video/")) {
                val responseText = app.get(capturedUrl).text

                """"(\d{3,4}p)[^"]*"\s*:\s*\{\s*"labelShort"\s*:\s*"[^"]*"\s*,\s*"src"\s*:\s*"([^"]+)"""".toRegex()
                    .findAll(responseText).forEach { match ->
                        val videoUrl = match.groupValues[2]
                        val rawQuality = match.groupValues[1] // Contoh: "1080p"
                        
                        if (!videoUrl.contains("/dload/")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.eporner.com/"
                                    // BYPASS TOTAL: Ambil angkanya doang (1080) pakai Kotlin murni, gak butuh API CloudStream!
                                    this.quality = rawQuality.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                                }
                            )
                            videoFound = true
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("EPorner", "loadLinks failed for url=$url: ${e.message}")
        }

        return videoFound
    }
}