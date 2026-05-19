package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Rebahin : MainAPI() {
    // URL asli Rebahin sudah diperbarui ke IP server terbaru yang kamu minta
    override var mainUrl = "http://178.62.98.100"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Film Terbaru",
        "$mainUrl/tv/page/" to "Series Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val home = doc.select("article, div.item").mapNotNull { article ->
            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = article.selectFirst("h2, h3")?.text()?.trim()
                ?: a.attr("title").removePrefix("Permalink to:").trim().ifBlank { null }
                ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            val isSeries = href.contains("/tv/")
            
            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
        
        return newHomePageResponse(
            listOf(HomePageList(request.name, home)),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, div.item").mapNotNull { article ->
            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val title = article.selectFirst("h2, h3")?.text()?.trim()
                ?: a.attr("title").removePrefix("Permalink to:").trim().ifBlank { null }
                ?: return@mapNotNull null
            val poster = article.selectFirst("img")?.attr("src")?.ifBlank { null }
            val isSeries = href.contains("/tv/")

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.gmr-movie-thumb img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.entry-content p")?.text()?.trim() ?: ""
        val tags = doc.select("span.gmr-movie-genre a").map { it.text().trim() }
        val year = doc.selectFirst("span.gmr-movie-year a")?.text()?.trim()?.toIntOrNull()
        val isSeries = url.contains("/tv/")

        if (isSeries) {
            val episodes = doc.select("div.gmr-listseries a").mapNotNull { a ->
                val epHref = a.attr("href").ifBlank { null } ?: return@mapNotNull null
                val epTitle = a.text().trim()
                val seasonMatch = Regex("Season\\s*(\\d+)").find(epTitle)
                val epMatch = Regex("Episode\\s*(\\d+)").find(epTitle)
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                val ep = epMatch?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(epHref) {
                    this.name = epTitle
                    this.season = season
                    this.episode = ep
                }
            }.distinctBy { it.data }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Download links langsung di HTML: #download .gmr-download-list li a
        doc.select("div#download ul.gmr-download-list li a[href]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            loadExtractor(fixUrl(href), data, subtitleCallback, callback)
        }

        // Fallback: coba iframe jika ada (untuk beberapa halaman yang embed langsung)
        doc.select("div.tab-content iframe, div.gmr-embed-responsive iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { null } ?: return@forEach
            if (!src.contains("youtube")) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }

        return true
    }
}