package com.cgvindo

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object CgvIndoParser {
    private val containerSelector = listOf(
        "article", ".post", ".item", ".bs", ".thumb", ".movie", ".ml-item", ".module .content .items article",
        ".listupd article", ".result-item", ".card", ".film-poster", ".poster"
    ).joinToString(",")

    private val cardAnchorSelector = listOf(
        "a[href]:has(img)", "h2 a[href]", "h3 a[href]", ".title a[href]", ".tt a[href]", ".entry-title a[href]"
    ).joinToString(",")

    private val sectionSelector = listOf(
        "section", ".section", ".widget", ".content-area", ".main-content", ".block-area", ".bixbox", ".module"
    ).joinToString(",")

    fun parseHomeSections(api: MainAPI, doc: Document): List<HomePageList> {
        return doc.select(sectionSelector).mapNotNull { section ->
            val title = extractSectionTitle(section) ?: return@mapNotNull null
            val cards = section.select(containerSelector)
                .mapNotNull { parseCard(api, it) }
                .ifEmpty { section.select(cardAnchorSelector).mapNotNull { parseCard(api, it) } }
                .distinctBy { it.url }
                .take(24)
            if (cards.isEmpty()) return@mapNotNull null
            HomePageList(title, cards)
        }.distinctBy { it.name }.take(10)
    }

    fun parseCards(api: MainAPI, doc: Document, query: String? = null): List<SearchResponse> {
        val needle = query?.trim()?.lowercase()
        val containers = doc.select(containerSelector).mapNotNull { parseCard(api, it) }
        val anchors = doc.select(cardAnchorSelector).mapNotNull { parseCard(api, it) }
        return (containers + anchors)
            .filter { needle == null || it.name.lowercase().contains(needle) }
            .distinctBy { it.url }
            .take(60)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val anchor = when {
            element.tagName().equals("a", true) -> element
            else -> element.selectFirst(cardAnchorSelector) ?: element.selectFirst("a[href]") ?: return null
        }
        val href = CgvIndoUtils.absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!CgvIndoUtils.isTitleUrl(href)) return null

        val image = anchor.selectFirst("img") ?: element.selectFirst("img")
        val poster = CgvIndoUtils.pickImage(api.mainUrl, image, element) ?: return null
        val rawTitle = listOf(
            anchor.attr("title"),
            image?.attr("alt").orEmpty(),
            element.selectFirst("h2, h3, .tt, .entry-title, .title")?.text().orEmpty(),
            anchor.text()
        ).firstOrNull { CgvIndoUtils.cleanTitle(it).isNotBlank() }.orEmpty()
        val title = CgvIndoUtils.cleanTitle(rawTitle)
        if (title.isBlank() || title.equals("Trailer", ignoreCase = true)) return null

        val type = CgvIndoUtils.typeFromUrlOrTitle(href, title)
        return api.newMovieSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    suspend fun parseLoad(api: MainAPI, url: String, doc: Document): LoadResponse? {
        val title = detailTitle(url, doc) ?: return null
        val poster = CgvIndoUtils.extractDetailPoster(url, doc, title)
        val plot = CgvIndoUtils.cleanText(
            doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst(".entry-content p, .desc, .description, .sinopsis, .synopsis, article p")?.text()
        )

        val tags = doc.select("a[rel=tag], .genxed a, .sgeneros a, a[href*='/genre/']")
            .map { CgvIndoUtils.cleanText(it.text()) }
            .filter { it.length in 2..35 }
            .distinctBy { it.lowercase() }
            .take(15)

        val recommendations = parseRecommendations(api, doc, url)
        val episodes = parseEpisodes(api, doc, url)
        val type = if (episodes.size > 1) TvType.TvSeries else CgvIndoUtils.typeFromUrlOrTitle(url, title)

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            api.newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, type, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun detailTitle(url: String, doc: Document): String? {
        val candidates = listOfNotNull(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:title]")?.attr("content"),
            doc.selectFirst("h1.entry-title, h1.post-title, h1.title, article h1, h1")?.text(),
            doc.title(),
            url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        )
        return candidates.map { CgvIndoUtils.cleanTitle(it) }
            .firstOrNull { it.isNotBlank() && !it.equals("Trailer", ignoreCase = true) }
    }

    private fun parseRecommendations(api: MainAPI, doc: Document, currentUrl: String): List<SearchResponse> {
        val related = doc.select(".related, .recommended, .similar, #related, .relacionados, .you-may-like")
        val source = if (related.isNotEmpty()) related else doc.select("body")
        return source.flatMap { section ->
            section.select(containerSelector).mapNotNull { parseCard(api, it) } +
                section.select(cardAnchorSelector).mapNotNull { parseCard(api, it) }
        }.filterNot { it.url == currentUrl }
            .distinctBy { it.url }
            .take(12)
    }

    private fun parseEpisodes(api: MainAPI, doc: Document, fallbackUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val selectors = listOf(
            ".eplister a[href]", ".episodios a[href]", ".episode a[href]", ".episodes a[href]",
            ".episodelist a[href]", "a[href*='episode']", "a[href*='eps']"
        ).joinToString(",")
        val seen = linkedSetOf<String>()
        return doc.select(selectors).mapNotNull { ep ->
            val epUrl = CgvIndoUtils.absoluteUrl(fallbackUrl, ep.attr("href")) ?: return@mapNotNull null
            if (!CgvIndoUtils.isTitleUrl(epUrl)) return@mapNotNull null
            if (!seen.add(epUrl.substringBefore("#"))) return@mapNotNull null
            api.newEpisode(epUrl) {
                name = CgvIndoUtils.cleanText(ep.text()).ifBlank { "Episode" }
                posterUrl = CgvIndoUtils.pickImage(fallbackUrl, ep.selectFirst("img"), ep)
            }
        }
    }

    private fun extractSectionTitle(section: Element): String? {
        return listOfNotNull(
            section.selectFirst("h2, h3, .widget-title, .block-title, .section-title, .releases h2")?.text(),
            section.attr("aria-label").takeIf { it.isNotBlank() }
        ).map(CgvIndoUtils::cleanTitle).firstOrNull { it.isNotBlank() && !it.equals("Trailer", ignoreCase = true) }
    }
}
