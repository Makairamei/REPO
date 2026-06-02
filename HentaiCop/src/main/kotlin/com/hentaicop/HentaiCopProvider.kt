package com.hentaicop

import com.hentaicop.HentaiCopUtils.pageUrl
import com.hentaicop.HentaiCopUtils.searchUrl
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

class HentaiCopProvider : MainAPI() {
    override var mainUrl = HentaiCopSeeds.MAIN_URL
    override var name = "HentaiCop"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = mainPageOf(*HentaiCopSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = pageUrl(mainUrl, request.data, page)
        val document = app.get(url, headers = HentaiCopUtils.headers, referer = mainUrl).document
        val items = HentaiCopParser.parseListing(this, document)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val document = app.get(searchUrl(mainUrl, query), headers = HentaiCopUtils.headers, referer = mainUrl).document
        return HentaiCopParser.parseListing(this, document)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = HentaiCopUtils.headers, referer = mainUrl).document
        return HentaiCopParser.parseLoadResponse(this, url, document)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return HentaiCopExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
