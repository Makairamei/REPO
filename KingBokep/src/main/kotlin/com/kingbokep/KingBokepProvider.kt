package com.kingbokep

import com.lagradost.cloudstream3.HomePageList
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
import com.kingbokep.KingBokepUtils.pageUrl
import com.kingbokep.KingBokepUtils.searchUrl

class KingBokepProvider : MainAPI() {
    override var mainUrl = KingBokepSeeds.MAIN_URL
    override var name = "KingBokep"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(*KingBokepSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = pageUrl(mainUrl, request.data, page)
        val document = app.get(url, headers = KingBokepUtils.siteHeaders).document
        val results = KingBokepParser.parseListing(this, document)
        return newHomePageResponse(listOf(HomePageList(request.name, results, isHorizontalImages = true)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val url = searchUrl(mainUrl, query)
        val document = app.get(url, headers = KingBokepUtils.siteHeaders).document
        return KingBokepParser.parseListing(this, document)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = KingBokepUtils.siteHeaders, referer = mainUrl).document
        return KingBokepParser.parseLoadResponse(this, url, document)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return KingBokepExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
