package com.nonton01

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
import com.nonton01.Nonton01Utils.pageUrls
import com.nonton01.Nonton01Utils.searchUrl

class Nonton01Provider : MainAPI() {
    override var mainUrl = Nonton01Seeds.MAIN_URL
    override var name = "Nonton01"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    override val mainPage = mainPageOf(*Nonton01Seeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var results: List<SearchResponse> = emptyList()
        for (url in pageUrls(mainUrl, request.data, page)) {
            results = runCatching {
                val document = app.get(url, headers = Nonton01Utils.siteHeaders, referer = mainUrl).document
                Nonton01Parser.parseListing(this, document)
            }.getOrElse { emptyList() }
            if (results.isNotEmpty()) break
        }

        return newHomePageResponse(listOf(HomePageList(request.name, results)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return runCatching {
            val url = searchUrl(mainUrl, query)
            val document = app.get(url, headers = Nonton01Utils.siteHeaders, referer = mainUrl).document
            Nonton01Parser.parseListing(this, document)
        }.getOrElse { emptyList() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return runCatching {
            val document = app.get(url, headers = Nonton01Utils.siteHeaders, referer = mainUrl).document
            Nonton01Parser.parseLoadResponse(this, url, document)
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return Nonton01Extractor.loadLinks(name, mainUrl, data, subtitleCallback, callback)
    }
}
