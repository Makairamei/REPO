package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.base64Decode

class Nomat : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    
    override var mainUrl = "https://nomat.site"
    private var directUrl: String? = null
    override var name = "Nomat"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "slug/film-baru-terpopuler" to "Film Baru Terpopuler",
        "slug/film-box-office" to "Film Box Office",
        "slug/film-serial-anime" to "Serial Anime",
        "slug/film-serial-tv-korea" to "Serial Drama Korea",
        
        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/animation" to "Animation",
        "genre/comedy" to "Comedy",
        "genre/crime" to "Crime",
        "genre/drama" to "Drama",
        "genre/fantasy" to "Fantasy",
        "genre/horror" to "Horror",
        "genre/mystery" to "Mystery",
        "genre/romance" to "Romance",
        "genre/sci-fi" to "Sci-Fi",
        "genre/thriller" to "Thriller",
        
        "country/indonesia" to "Indonesia",
        "country/japan" to "Japan",
        "country/korea" to "Korea",
        "country/china" to "China",
        "country/thailand" to "Thailand",
        "country/usa" to "USA"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}/page/$page"
        }

        val doc = app.get(url).document
        val home = doc.select("article.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title, h3, a[title]")?.text() ?: this.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val isTvSeries = href.contains("/series/") || href.contains("/tv/") || this.select(".type-series").isNotEmpty()

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.getImageAttr())
        val tags = document.select("div.sgeneros a").map { it.text() }
        val plot = document.selectFirst("div.wp-content p")?.text()
        val isTvSeries = url.contains("/series/") || url.contains("/tv/") || document.select(".episodiotitle").isNotEmpty()
        
        // Perbaikan: Tidak di-convert ke Float karena parameter addScore adalah String?
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()
        val recommendations = document.select("article.item").mapNotNull { it.toSearchResult() }

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("ul.episodios li").forEach { epElement ->
                val epTitle = epElement.selectFirst(".episodiotitle a")?.text() ?: "Episode"
                val epUrl = epElement.selectFirst(".episodiotitle a")?.attr("href") ?: return@forEach
                val seasonNum = epElement.selectFirst(".numerando")?.text()?.split("-")?.firstOrNull()?.trim()?.toIntOrNull()
                val epNum = epElement.selectFirst(".numerando")?.text()?.split("-")?.lastOrNull()?.trim()?.toIntOrNull()

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addScore(rating)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                addScore(rating)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return try {
            val nhDoc = app.get(data, referer = mainUrl, timeout = 100L).document

            nhDoc.select("div.server-item").forEach { el ->
                val encoded = el.attr("data-url")
                if (encoded.isNotBlank()) {
                    try {
                        val decoded = base64Decode(encoded)
                        loadExtractor(decoded, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            
            nhDoc.select("iframe[src]").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
