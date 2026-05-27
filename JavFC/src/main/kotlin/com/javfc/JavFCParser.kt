package com.javfc

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.javfc.JavFCUtils.absoluteUrl
import com.javfc.JavFCUtils.cleanText
import com.javfc.JavFCUtils.isLikelyMovieUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object JavFCParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val primary = document.select(".movie-container > div")
        val fallback = document.select(".movie-item, .video-item, .item, article, .col-md-3, .col-sm-4")
        val candidates = if (primary.isNotEmpty()) primary else fallback

        return candidates
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val label = cleanText(element.selectFirst(".label, .badge")?.text())
        if (label.equals("Actor", ignoreCase = true) || label.equals("Seller", ignoreCase = true)) return null

        val link = element.selectFirst(".movie-title a[href]")
            ?: element.selectFirst("h3 a[href], h2 a[href], .title a[href]")
            ?: element.selectFirst("a[href$=.html]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isLikelyMovieUrl(href)) return null

        val image = element.selectFirst("img")
        val title = cleanText(
            link.text().ifBlank {
                image?.attr("alt").orEmpty().ifBlank { image?.attr("title").orEmpty() }
            }
        ).ifBlank { return null }

        val poster = image?.let {
            absoluteUrl(
                api.mainUrl,
                it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
            )
        }


        return api.newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanText(
            document.selectFirst("h1.title, .title, h1, .video-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { return null }

        val poster = absoluteUrl(
            api.mainUrl,
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("#info img, .poster img, .movie-cover img, .cover img, .thumb img")?.let {
                    it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
                }
        )

        val plot = cleanText(
            document.selectFirst(".description, .desc, #description, .movie-description, .info-description")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select("a[href*=/genre/], a[href*='search?q='], a[href*='search?per_page=']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinct()
            .take(12)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)

        return if (episodes.size > 1) {
            api.newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, TvType.NSFW, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val episodeLinks = document.select(".season a[href]:not([data-toggle]), .episodes a[href], .episode a[href]")
            .mapNotNull { a ->
                val href = absoluteUrl(api.mainUrl, a.attr("href")) ?: return@mapNotNull null
                if (!isLikelyMovieUrl(href)) return@mapNotNull null
                val epName = cleanText(a.text()).ifBlank { "Episode" }
                api.newEpisode(href, initializer = { name = epName })
            }
            .distinctBy { it.data }

        return episodeLinks.ifEmpty {
            listOf(api.newEpisode(fallbackUrl, initializer = { name = "Movie" }))
        }
    }
}
