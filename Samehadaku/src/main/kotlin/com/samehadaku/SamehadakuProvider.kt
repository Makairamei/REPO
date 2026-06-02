package com.samehadaku

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.samehadaku.SamehadakuUtils.buildPageUrl
import com.samehadaku.SamehadakuUtils.encode
import com.samehadaku.SamehadakuUtils.headers
import com.samehadaku.SamehadakuUtils.logSafe
import com.samehadaku.SamehadakuUtils.originOf

class SamehadakuProvider : MainAPI() {
    override var mainUrl = SamehadakuSeeds.MAIN_URL
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        *SamehadakuSeeds.mainPage.map { it.data to it.name }.toTypedArray()
    )

    private fun mirrorUrls(url: String): List<String> {
        if (!url.startsWith("http", true)) return SamehadakuSeeds.mirrors.map { it.trimEnd('/') + "/" + url.trimStart('/') }
        val origin = originOf(url)
        return SamehadakuSeeds.mirrors
            .plus(origin)
            .map { mirror -> url.replaceFirst(origin, mirror.trimEnd('/')) }
            .distinct()
    }

    private suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        retries: Int = 2
    ): com.lagradost.nicehttp.NiceResponse? {
        var last: Throwable? = null
        val candidates = mirrorUrls(url)
        candidates.forEachIndexed { candidateIndex, candidateUrl ->
            repeat(retries.coerceAtLeast(1)) { attempt ->
                try {
                    return app.get(
                        candidateUrl,
                        referer = referer,
                        headers = headers,
                        timeout = 30L
                    )
                } catch (throwable: Throwable) {
                    last = throwable
                    if (candidateIndex == candidates.lastIndex && attempt < retries - 1) {
                        kotlinx.coroutines.delay(500L * (attempt + 1))
                    }
                }
            }
        }
        logSafe(last ?: Exception("Samehadaku request failed: $url"))
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val category = SamehadakuSeeds.mainPage.firstOrNull { it.name == request.name }
        val pageUrl = buildPageUrl(request.data, page)
        val mode = category?.mode ?: SamehadakuCategoryMode.Listing
        val isPaged = category?.paged ?: true

        val liveResults = safeGet(pageUrl)?.document?.let { document ->
            SamehadakuParser.parseByMode(this, document, pageUrl, mainUrl, mode)
        }.orEmpty()

        val results = liveResults.ifEmpty { fallbackResults(request.name, mode) }
        return newHomePageResponse(listOf(HomePageList(request.name, results, isPaged)))
    }

    private fun fallbackResults(name: String, mode: SamehadakuCategoryMode): List<SearchResponse> {
        val seeds = when (mode) {
            SamehadakuCategoryMode.HomeTop -> SamehadakuSeeds.fallbackTop
            SamehadakuCategoryMode.HomeMovie -> SamehadakuSeeds.fallbackMovies
            else -> SamehadakuSeeds.fallbackLatest
        }
        val filtered = when {
            name.contains("Movie", true) || name.contains("Film", true) -> seeds.filter { it.movie }.ifEmpty { SamehadakuSeeds.fallbackMovies }
            else -> seeds
        }
        return filtered.map { item ->
            newAnimeSearchResponse(
                item.title,
                item.url,
                if (item.movie) TvType.AnimeMovie else TvType.Anime
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val results = linkedMapOf<String, SearchResponse>()
        for (page in 1..5) {
            val path = if (page <= 1) "$mainUrl/?s=${encode(query)}" else "$mainUrl/page/$page/?s=${encode(query)}"
            val document = safeGet(path)?.document ?: break
            val pageResults = SamehadakuParser.parseListing(this, document, path, mainUrl)
            if (pageResults.isEmpty()) break
            pageResults.forEach { results[it.url] = it }
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val firstResponse = safeGet(url) ?: return null
        val entryUrl = if (url.contains("/anime/", true)) {
            url
        } else {
            SamehadakuParser.parseAnimeUrlFromEpisode(firstResponse.document, url, mainUrl) ?: url
        }

        val document = if (entryUrl == url) firstResponse.document else safeGet(entryUrl)?.document ?: return null
        val meta = SamehadakuParser.parseMeta(document, entryUrl, mainUrl) ?: return null
        val episodes = SamehadakuParser.parseEpisodes(this, document, entryUrl, mainUrl)
        val recommendations = SamehadakuParser.parseRecommendations(this, document, entryUrl, mainUrl)

        return newAnimeLoadResponse(meta.title, entryUrl, meta.type) {
            engName = meta.title
            posterUrl = meta.poster
            backgroundPosterUrl = meta.background ?: meta.poster
            year = meta.year
            showStatus = meta.status
            plot = meta.description
            tags = meta.tags
            addScore(meta.score)
            addTrailer(meta.trailer)
            addEpisodes(DubStatus.Subbed, episodes)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return SamehadakuExtractor.loadLinks(
            data = data,
            mainUrl = mainUrl,
            headers = headers,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}
