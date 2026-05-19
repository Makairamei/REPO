package com.Donghuastream

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class Donghuastream : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update&page=" to "Recently Updated",
        "anime/?status=completed&type=&order=update&page=" to "Completed",
        "anime/?status=&type=special&sub=&order=update&page=" to "Special Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        context?.let {
        }

        val document = app.get(
            "$mainUrl/${request.data}$page"
        ).document

        val home = document.select("div.listupd > article")
            .mapNotNull {
                it.toSearchResult()
            }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    home,
                    false
                )
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = this.selectFirst("div.bsx > a")
            ?.attr("title")
            ?.trim()
            ?: return null

        val href = fixUrl(
            this.selectFirst("div.bsx > a")
                ?.attr("href")
                ?: return null
        )

        val posterUrl = this.selectFirst("img")
            ?.getImageAttr()

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {

            val document = app.get(
                "$mainUrl/page/$i/?s=$query"
            ).document

            val results = document.select("div.listupd > article")
                .mapNotNull {
                    it.toSearchResult()
                }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    private fun Element.toRecommendResult(): SearchResponse? {

        val title = this.selectFirst("div.tt")
            ?.text()
            ?.trim()
            ?: return null

        val href = this.selectFirst("a")
            ?.attr("href")
            ?: return null

        val posterUrl = this.selectFirst("img")
            ?.getImageAttr()

        return newAnimeSearchResponse(
            title,
            fixUrl(href),
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()

        val href = document.selectFirst(".eplister li > a")
            ?.attr("href")
            .orEmpty()

        var poster = document.selectFirst("div.ime > img")
            ?.getImageAttr()
            .orEmpty()

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull {
                it.toRecommendResult()
            }

        val description = document.selectFirst("div.entry-content")
            ?.text()
            ?.trim()

        val type = document.selectFirst(".spe")
            ?.text()
            .orEmpty()

        val tvTag = if (
            type.contains("Movie", true)
        ) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return if (tvTag == TvType.Anime) {

            val epPage = document.selectFirst(".eplister li > a")
                ?.attr("href")
                .orEmpty()

            val doc = app.get(epPage).document

            val episodes = doc.select("div.episodelist > ul > li")
                .map { info ->

                    val href1 = fixUrl(
                        info.selectFirst("a")
                            ?.attr("href")
                            .orEmpty()
                    )

                    val epText = info.select("a span")
                        .text()

                    val episode = Regex("(\\d+)")
                        .find(epText)
                        ?.groupValues
                        ?.getOrNull(1)

                    val posterr = info.selectFirst("img")
                        ?.getImageAttr()

                    newEpisode(href1) {
                        this.name = epText
                        this.episode = episode?.toIntOrNull()
                        this.posterUrl = posterr
                    }
                }

            if (poster.isEmpty()) {
                poster = document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")
                    .orEmpty()
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes.reversed()
            ) {

                this.posterUrl = poster
                this.plot = description
                this.recommendations = recommendations
            }

        } else {

            if (poster.isEmpty()) {
                poster = document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content")
                    .orEmpty()
            }

            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                href
            ) {

                this.posterUrl = poster
                this.plot = description
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val html = app.get(data).document

        val options = html.select("option[data-index]")

        for (option in options) {

            val base64 = option.attr("value")

            if (base64.isBlank()) continue

            val label = option.text().trim()

            val decodedHtml = try {
                base64Decode(base64)
            } catch (e: Exception) {

                Log.w(
                    "Donghuastream",
                    "Base64 decode failed"
                )

                continue
            }

            val iframeUrl = Jsoup.parse(decodedHtml)
                .selectFirst("iframe")
                ?.attr("src")
                ?.let(::httpsify)

            if (iframeUrl.isNullOrEmpty()) continue

            when {

                "vidmoly" in iframeUrl -> {

                    val cleanedUrl = iframeUrl
                        .replace("\\", "")

                    loadExtractor(
                        cleanedUrl,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }

                iframeUrl.endsWith(".mp4") -> {

                    callback.invoke(
                        newExtractorLink(
                            name,
                            label,
                            iframeUrl,
                            INFER_TYPE
                        ) {

                            this.referer = mainUrl

                            this.quality =
                                getQualityFromName(label)
                        }
                    )
                }

                else -> {

                    loadExtractor(
                        iframeUrl,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String? {

        return when {

            this.hasAttr("data-src") ->
                fixUrlNull(this.attr("data-src"))

            this.hasAttr("data-lazy-src") ->
                fixUrlNull(this.attr("data-lazy-src"))

            this.hasAttr("srcset") ->
                fixUrlNull(
                    this.attr("srcset")
                        .substringBefore(" ")
                )

            else ->
                fixUrlNull(this.attr("src"))
        }
    }
}