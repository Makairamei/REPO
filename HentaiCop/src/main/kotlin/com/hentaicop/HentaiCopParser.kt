package com.hentaicop

import com.hentaicop.HentaiCopUtils.absoluteUrl
import com.hentaicop.HentaiCopUtils.cleanText
import com.hentaicop.HentaiCopUtils.cleanTitle
import com.hentaicop.HentaiCopUtils.episodeNumber
import com.hentaicop.HentaiCopUtils.isEpisodeUrl
import com.hentaicop.HentaiCopUtils.isPlayablePageUrl
import com.hentaicop.HentaiCopUtils.statusFromText
import com.hentaicop.HentaiCopUtils.titleFromSlug
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object HentaiCopParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val results = linkedSetOf<AnimeSearchResponse>()

        document.select(
            "article.bs.styletwo, article.bs, .listupd article, .listupd .bs, .bsx, .serieslist li, .soralist li, .eplister li, .swiper-slide, .postbody article"
        ).forEach { element ->
            parseCard(api, element)?.let { results.add(it) }
        }

        if (results.size < 8) {
            document.select("a[href]").forEach { anchor ->
                parseAnchorCard(api, anchor)?.let { results.add(it) }
            }
        }

        return results
            .distinctBy { it.url }
            .filter { it.name.length > 2 && it.url.isNotBlank() }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): AnimeSearchResponse? {
        val link = element.selectFirst("a[href*='/series/']")
            ?: element.selectFirst("a[href*='-episode-']")
            ?: element.selectFirst("h2 a[href], h3 a[href], .tt a[href], .entry-title a[href], .epl-title a[href], a[href]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null

        val targetUrl = href
        val image = element.selectFirst("img")
        val rawTitle = link.attr("oldtitle")
            .ifBlank { link.attr("title") }
            .ifBlank { image?.attr("alt").orEmpty() }
            .ifBlank { image?.attr("title").orEmpty() }
            .ifBlank { link.text() }
            .ifBlank { element.selectFirst(".tt, h2, h3, .entry-title, .epl-title")?.text().orEmpty() }
            .ifBlank { titleFromSlug(targetUrl) }

        val title = cleanTitle(rawTitle).ifBlank { titleFromSlug(targetUrl) }
        val poster = image?.let {
            absoluteUrl(
                api.mainUrl,
                it.attr("data-src")
                    .ifBlank { it.attr("data-lazy-src") }
                    .ifBlank { it.attr("data-original") }
                    .ifBlank { it.attr("src") }
            )
        }

        return api.newAnimeSearchResponse(title, targetUrl, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun parseAnchorCard(api: MainAPI, anchor: Element): AnimeSearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isPlayablePageUrl(href)) return null
        val targetUrl = href
        val text = cleanTitle(
            anchor.attr("title")
                .ifBlank { anchor.text() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
        ).ifBlank { titleFromSlug(targetUrl) }
        if (text.equals("home", true) || text.equals("download", true) || text.equals("lihat semua", true)) return null
        val poster = anchor.selectFirst("img")?.let {
            absoluteUrl(api.mainUrl, it.attr("data-src").ifBlank { it.attr("src") })
        }
        return api.newAnimeSearchResponse(text, targetUrl, TvType.NSFW) {
            posterUrl = poster
        }
    }

    fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanTitle(
            document.selectFirst(".entry-title, h1.entry-title, h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ).ifBlank { titleFromSlug(url) }

        val poster = absoluteUrl(
            api.mainUrl,
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.wp-post-image, .thumb img, .poster img, .bigcover img, .infox img")?.let {
                    it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
                }
        )

        val tags = document.select(".genxed a, a[href*='/genre/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinct()
            .take(20)

        val infoText = document.select(".spe, .info-content, .entry-content, .postbody").text()
        val plot = cleanText(
            document.selectFirst(".entry-content p, .synopsis p, .desc p, .entry-content")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )
        val year = Regex("(?i)(?:released|rilis|tahun)[:\\s]+(\\d{4})").find(infoText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val status = statusFromText(infoText)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)

        return api.newAnimeLoadResponse(title, url, TvType.NSFW) {
            posterUrl = poster
            this.tags = tags
            this.plot = plot
            this.year = year
            this.showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val episodes = mutableListOf<Episode>()

        document.select(
            ".eplister li a[href], .episodelist li a[href], .episode-list a[href], .soraddlx a[href], a[href*='-episode-']"
        ).forEach { a ->
            val href = absoluteUrl(api.mainUrl, a.attr("href")) ?: return@forEach
            if (!isEpisodeUrl(href)) return@forEach
            val key = href.substringBefore('#')
            if (!seen.add(key)) return@forEach
            val ep = episodeNumber(a.text()) ?: episodeNumber(href) ?: (episodes.size + 1)
            val name = cleanText(
                a.selectFirst(".epl-title")?.text()
                    ?: a.attr("title")
                    ?: a.text()
            ).ifBlank { "Episode $ep" }
            episodes.add(
                api.newEpisode(href) {
                    this.name = name
                    this.episode = ep
                    this.posterUrl = a.selectFirst("img")?.let { img ->
                        absoluteUrl(api.mainUrl, img.attr("data-src").ifBlank { img.attr("src") })
                    }
                }
            )
        }

        if (episodes.isNotEmpty()) return episodes

        return listOf(
            api.newEpisode(fallbackUrl) {
                name = "Movie"
                episode = episodeNumber(fallbackUrl)
            }
        )
    }
}
