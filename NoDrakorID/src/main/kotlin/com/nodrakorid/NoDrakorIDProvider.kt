package com.nodrakorid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class NoDrakorIDProvider : MainAPI() {
    override var mainUrl = NoDrakorIDSepeda.MAIN_URL
    override var name = NoDrakorIDSepeda.SITE_NAME
    override var lang = NoDrakorIDSepeda.LANGUAGE
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    override val mainPage = mainPageOf(*NoDrakorIDSepeda.mainPages.map { it.path to it.name }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = NoDrakorIDUtils.pageUrl(request.data, page)
        val doc = app.get(url, referer = mainUrl, headers = NoDrakorIDUtils.browserHeaders).document
        val cards = if (request.data == "/" && page <= 1) {
            NoDrakorIDParser.parseHomeCards(this, doc)
        } else {
            NoDrakorIDParser.parseCards(this, doc).take(36)
        }
        return newHomePageResponse(listOf(HomePageList(request.name, cards, true)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        if (query.isBlank()) return emptyList()
        val encoded = NoDrakorIDUtils.encode(query)
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )
        for (url in searchUrls) {
            val result = runCatching {
                val doc = app.get(url, referer = mainUrl, headers = NoDrakorIDUtils.browserHeaders).document
                NoDrakorIDParser.parseCards(this, doc, query)
            }.getOrNull().orEmpty()
            if (result.isNotEmpty()) return result.distinctBy { it.url }.take(60)
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val clean = NoDrakorIDUtils.absoluteUrl(mainUrl, url) ?: url
        val doc = app.get(clean, referer = mainUrl, headers = NoDrakorIDUtils.browserHeaders).document
        return NoDrakorIDParser.parseLoad(this, clean, doc)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return NoDrakorIDExtractor.extract(data, subtitleCallback, callback)
    }
}
