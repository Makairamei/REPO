package com.Donghuastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

open class Donghuastream : MainAPI() {
    
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "donghua/?status=&type=&order=update&page=" to "Recently Updated",
        "donghua/?status=completed&type=&order=update&page=" to "Completed",
        "donghua/?status=&type=&order=popular&page=" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("page=")) {
            "$mainUrl/${request.data}$page"
        } else {
            "$mainUrl/${request.data}/page/$page/"
        }
        val document = app.get(url).document
        val home = document.select("div.listupd article.bs, div.listupd div.bs, .listupd .chor").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt, h2, h3")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.getImageAttr()?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article.bs, div.listupd div.bs, .listupd .chor").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, title")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: ""
        val poster = document.selectFirst("div.thumb img, .poster img")?.getImageAttr()?.fixImageQuality()
        val description = document.selectFirst(".entry-content p, .contt")?.text()?.trim()
        
        val episodes = document.select("div.eplister ul li, ul.clor li").mapNotNull { li ->
            val epHref = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epTitle = li.selectFirst(".epl-num, .epl-title")?.text()?.trim() ?: "Episode"
            newEpisode(epHref) {
                this.name = epTitle
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Ambil Link dari Dropdown Server / Mirror Options (Mendukung Base64 & Data Embed)
        document.select("select.mirror option, div.player-source select option, ul.mplayer li, .mirroroption .option").forEach { server ->
            val value = server.attr("value").takeIf { it.isNotEmpty() }
                ?: server.attr("data-embed").takeIf { it.isNotEmpty() }
                ?: server.attr("data-video").takeIf { it.isNotEmpty() }

            if (value != null) {
                // Mencoba men-decode jika value berupa Base64 cipher player
                val decoded = try {
                    base64Decode(value)
                } catch (_: Exception) { "" }

                val iframeUrl = if (decoded.isNotEmpty() && decoded.contains("iframe", true)) {
                    org.jsoup.Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.let(::httpsify)
                } else {
                    value.let(::httpsify)
                }

                if (!iframeUrl.isNullOrEmpty() && iframeUrl.startsWith("http")) {
                    val label = server.text().trim().ifEmpty { "Server" }
                    if (iframeUrl.endsWith(".mp4")) {
                        callback(
                            newExtractorLink(label, label, url = iframeUrl, INFER_TYPE) {
                                this.referer = ""
                                this.quality = getQualityFromName(label)
                            }
                        )
                    } else {
                        loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        // 2. Ambil Link dari Iframe Langsung di Halaman (Metode Fallback Lama)
        document.select("div.embed-holder iframe, div.video-content iframe, div.player iframe, iframe#player").forEach { iframe ->
            val iframeUrl = iframe.getIframeAttr()?.let(::httpsify)
            if (!iframeUrl.isNullOrEmpty() && iframeUrl.startsWith("http")) {
                val label = "Iframe Player"
                if (iframeUrl.endsWith(".mp4")) {
                    callback(
                        newExtractorLink(label, label, url = iframeUrl, INFER_TYPE) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                } else {
                    loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }
}
