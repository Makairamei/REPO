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
import com.nonton01.Nonton01Utils.mirrorUrlsFor
import com.nonton01.Nonton01Utils.pageUrls
import com.nonton01.Nonton01Utils.searchUrl
import com.nonton01.Nonton01Utils.siteHeadersFor

class Nonton01Provider : MainAPI() {
    override var mainUrl = Nonton01Seeds.MAIN_URL
    override var name = "Nonton01"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.NSFW)

    override val mainPage = mainPageOf(*Nonton01Seeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = firstNonEmptyPageResult(pageUrls(mainUrl, request.data, page))
        return if (results.isNotEmpty()) {
            newHomePageResponse(listOf(HomePageList(request.name, results)))
        } else {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val candidates = Nonton01Seeds.MIRROR_URLS.map { searchUrl(it, query) }
        return firstNonEmptyPageResult(candidates)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        for (candidate in mirrorUrlsFor(url)) {
            val response = runCatching {
                val origin = Nonton01Utils.originOf(candidate) ?: mainUrl
                val document = app.get(candidate, headers = siteHeadersFor(origin), referer = origin).document
                Nonton01Parser.parseLoadResponse(this, candidate, document)
            }.getOrNull()
            if (response != null) return response
        }
        return null
    }

    private suspend fun firstNonEmptyPageResult(urls: List<String>): List<SearchResponse> {
        for (url in urls) {
            val results = runCatching {
                val origin = Nonton01Utils.originOf(url) ?: mainUrl
                val document = app.get(url, headers = siteHeadersFor(origin), referer = origin).document
                Nonton01Parser.parseListing(this, document)
            }.getOrElse { emptyList() }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
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
