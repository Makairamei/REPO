package com.gomunime

import com.gomunime.GomunimeUtils.pageUrl
import com.gomunime.GomunimeUtils.searchUrl
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink

class GomunimeProvider : MainAPI() {
    override var mainUrl = GomunimeSeeds.MAIN_URL
    override var name = "Gomunime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(*GomunimeSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = pageUrl(mainUrl, request.data, page)
        val document = app.get(url, headers = GomunimeUtils.headers, referer = mainUrl).document
        val results = GomunimeParser.parseListing(this, document)
        return newHomePageResponse(request.name, results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val document = app.get(searchUrl(mainUrl, query), headers = GomunimeUtils.headers, referer = mainUrl).document
        return GomunimeParser.parseListing(this, document)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = GomunimeUtils.headers, referer = mainUrl).document
        return GomunimeParser.parseLoadResponse(this, url, document)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return GomunimeExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
