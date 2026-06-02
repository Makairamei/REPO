package com.putarflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class PutarFlixProvider : MainAPI() {
    override var mainUrl = PutarFlixSeeds.MAIN_URL
    override var name = PutarFlixSeeds.SITE_NAME
    override var lang = PutarFlixSeeds.LANGUAGE
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(*PutarFlixSeeds.mainPages.map { it.path to it.name }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = PutarFlixUtils.pageUrl(request.data, page)
        val doc = app.get(url, referer = mainUrl).document
        val sections = if (request.data == "/" && page <= 1) {
            PutarFlixParser.parseHomeSections(this, doc, request.name)
        } else {
            val cards = PutarFlixParser.parseCards(this, doc).take(30)
            if (cards.isEmpty()) emptyList() else listOf(HomePageList(request.name, cards, false))
        }
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        if (query.isBlank()) return emptyList()
        val doc = app.get("$mainUrl/?s=${PutarFlixUtils.encode(query)}", referer = mainUrl).document
        return PutarFlixParser.parseCards(this, doc).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val doc = app.get(url, referer = mainUrl).document
        return PutarFlixParser.parseLoad(this, url, doc)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return PutarFlixExtractor.extract(data, subtitleCallback, callback)
    }
}
