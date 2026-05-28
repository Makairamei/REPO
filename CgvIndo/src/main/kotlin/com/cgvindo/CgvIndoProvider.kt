package com.cgvindo

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
import com.cgvindo.CgvIndoUtils.pageUrls
import com.cgvindo.CgvIndoUtils.searchUrl

class CgvIndoProvider : MainAPI() {
    override var mainUrl = CgvIndoSeeds.MAIN_URL
    override var name = CgvIndoSeeds.SITE_NAME
    override var lang = CgvIndoSeeds.LANG
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(*CgvIndoSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var results: List<SearchResponse> = emptyList()
        var sections: List<HomePageList> = emptyList()
        for (url in pageUrls(mainUrl, request.data, page)) {
            val parsed = runCatching {
                val document = app.get(url, headers = CgvIndoUtils.siteHeaders, referer = mainUrl).document
                val homeSections = if (page <= 1 && request.name.equals("Beranda", ignoreCase = true)) CgvIndoParser.parseHomeSections(this, document) else emptyList()
                val cards = CgvIndoParser.parseCards(this, document)
                homeSections to cards
            }.getOrNull() ?: continue
            sections = parsed.first
            results = parsed.second
            if (sections.isNotEmpty() || results.isNotEmpty()) break
        }

        return when {
            sections.isNotEmpty() -> newHomePageResponse(sections)
            results.isNotEmpty() -> newHomePageResponse(listOf(HomePageList(request.name, results)))
            else -> newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runCatching {
            val document = app.get(searchUrl(mainUrl, query), headers = CgvIndoUtils.siteHeaders, referer = mainUrl).document
            CgvIndoParser.parseCards(this, document, query)
        }.getOrElse { emptyList() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            val document = app.get(url, headers = CgvIndoUtils.siteHeaders, referer = mainUrl).document
            CgvIndoParser.parseLoad(this, url, document)
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return CgvIndoExtractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
