package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class OppadramaProvider : MainAPI() {
    // Gunakan IP terbaru yang kamu berikan
    override var mainUrl = "http://45.11.57.199"
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Completed", true) -> ShowStatus.Completed
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "Drama Chinese",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Drama Thailand",
        "series/?country%5B%5D=usa&type=Drama&order=update" to "Drama Western",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}${if (request.data.contains("?")) "&" else "?"}page=$page"
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, items), items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        val title = linkElement.attr("title").ifBlank { this.selectFirst("div.tt")?.text() } ?: return null
        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }

        return if (href.contains("/series/") || href.contains("/drama/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Perbaikan: Timeout dari 50L ke 30000L (30 detik)
        val document = app.get("$mainUrl/?s=$query", timeout = 30L).document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }
        val description = document.select("div.entry-content p").joinToString("\n") { it.text() }.trim()
        
        val year = document.selectFirst("span:contains(Dirilis:)")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val tags = document.select("div.genxed a").map { it.text() }
        val actors = document.select("span:has(b:contains(Artis:)) a").map { it.text().trim() }
        val rating = document.selectFirst("div.rating strong")?.text()?.replace("Rating", "")?.trim()?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")

        val episodeElements = document.select("div.eplister ul li a")
        val episodes = episodeElements.reversed().mapIndexed { index, aTag ->
            newEpisode(fixUrl(aTag.attr("href"))) {
                this.name = "Episode ${index + 1}"
                this.episode = index + 1
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // 1. Utama
        document.selectFirst("div.player-embed iframe")?.getIframeAttr()?.let { iframe ->
            loadExtractor(httpsify(iframe), data, subtitleCallback, callback)
        }

        // 2. Mirror (Base64)
        document.select("select.mirror option[value]").forEach { opt ->
            val base64 = opt.attr("value")
            if (base64.isNotBlank() && base64.length > 10) {
                try {
                    val decodedHtml = base64Decode(base64.replace("\\s".toRegex(), ""))
                    val mirrorUrl = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                    if (!mirrorUrl.isNullOrBlank()) {
                        loadExtractor(httpsify(mirrorUrl), data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Download Box
        document.select("div.dlbox li span.e a").forEach { a ->
            val url = a.attr("href")
            if (url.isNotBlank()) loadExtractor(httpsify(url), data, subtitleCallback, callback)
        }

        return true
    }

    private fun Element.getImageAttr(): String = this.attr("abs:data-src").ifBlank { this.attr("abs:src") }
    private fun Element?.getIframeAttr(): String? = this?.attr("data-litespeed-src").takeIf { !it.isNullOrBlank() } ?: this?.attr("src")
}