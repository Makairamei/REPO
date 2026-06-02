package com.javfc

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.javfc.JavFCUtils.pageUrl
import com.javfc.JavFCUtils.urlEncoded

class JavFCProvider : MainAPI() {
    override var mainUrl = JavFCSeeds.MAIN_URL
    override var name = "JavFC"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(*JavFCSeeds.mainPagePairs())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = pageUrl(mainUrl, request.data, page)
        val document = app.get(url, headers = JavFCUtils.headers).document
        val results = JavFCParser.parseListing(this, document)
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val offset = ((page - 1) * 24).coerceAtLeast(0)
        val url = "$mainUrl/search?per_page=$offset&q=${query.urlEncoded()}"
        val document = app.get(url, headers = JavFCUtils.headers).document
        val results = JavFCParser.parseListing(this, document)
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = JavFCUtils.headers).document
        return JavFCParser.parseLoadResponse(this, url, document)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return JavFCExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
