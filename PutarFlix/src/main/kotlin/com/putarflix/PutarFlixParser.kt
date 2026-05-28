package com.putarflix

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PutarFlixParser {
    private val cardSelectors = listOf(
        "article",
        ".result-item",
        ".items .item",
        ".item",
        ".movie",
        ".ml-item",
        ".post",
        ".poster",
        ".boxinfo",
        "a[href]:has(img)"
    ).joinToString(",")

    private val sectionSelectors = listOf(
        "section",
        ".module",
        ".section",
        ".widget",
        ".movies-list",
        ".items",
        ".content-area",
        ".main-content"
    ).joinToString(",")

    private val metadataSelectors = listOf(
        ".mvic-info",
        ".movie-info",
        ".entry-meta",
        ".postmeta",
        ".sbox",
        ".extra",
        ".data",
        ".info-content",
        ".entry-content"
    ).joinToString(",")

    fun parseHomeSections(api: MainAPI, doc: Document, fallbackName: String): List<HomePageList> {
        val sections = doc.select(sectionSelectors).mapNotNull { section ->
            val title = sectionTitle(section) ?: return@mapNotNull null
            val items = section.select(cardSelectors)
                .mapNotNull { parseCard(api, it) }
                .distinctBy { it.url }
                .take(24)
            if (items.isEmpty()) return@mapNotNull null
            HomePageList(title, items, false)
        }.distinctBy { it.name }.take(12)

        if (sections.isNotEmpty()) return sections

        val fallback = parseCards(api, doc).take(30)
        return if (fallback.isEmpty()) emptyList() else listOf(HomePageList(fallbackName, fallback, false))
    }

    fun parseCards(api: MainAPI, doc: Document, query: String? = null): List<SearchResponse> {
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return doc.select(cardSelectors)
            .mapNotNull { parseCard(api, it) }
            .filter { needle == null || it.name.lowercase().contains(needle) }
            .distinctBy { it.url }
            .take(80)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val anchor = bestContentAnchor(element) ?: return null
        val url = PutarFlixUtils.absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!PutarFlixUtils.isContentUrl(url)) return null

        val image = anchor.selectFirst("img") ?: element.selectFirst("img")
        val title = PutarFlixUtils.cleanTitle(
            element.selectFirst("h1 a, h2 a, h3 a, .entry-title a, .data h3 a, .data h2 a, .title a")?.text()
                ?: anchor.attr("title")
                    .ifBlank { image?.attr("alt").orEmpty() }
                    .ifBlank { element.selectFirst("h1, h2, h3, .entry-title, .title, .tt")?.text().orEmpty() }
                    .ifBlank { anchor.text() }
        )
        if (title.isBlank() || title.equals("Watch", true) || title.equals("Watch Movie", true)) return null

        val text = element.text()
        val poster = PutarFlixUtils.pickImage(api.mainUrl, image, element)
        val type = PutarFlixUtils.typeFrom(url, title, text)
        val quality = element.selectFirst(".quality, .Qlty, .mli-quality, span:contains(HD), span:contains(CAM)")?.text()
        val year = PutarFlixUtils.extractYear(text) ?: PutarFlixUtils.extractYear(title)

        return api.newMovieSearchResponse(title, url, type) {
            posterUrl = poster
            quality?.let(PutarFlixUtils::cleanText)?.takeIf { it.isNotBlank() }?.let { addQuality(it) }
            this.year = year
        }
    }

    suspend fun parseLoad(api: MainAPI, url: String, doc: Document): LoadResponse? {
        val title = PutarFlixUtils.cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1.entry-title, h1")?.text()
                ?: doc.title()
        ).ifBlank { return null }

        val metadataText = doc.select(metadataSelectors)
            .joinToString(" ") { it.text() }
            .ifBlank { doc.selectFirst("article, main, .single, .content")?.text().orEmpty() }
        val descriptionText = doc.select(".entry-content p, .wp-content p, .description, .desc, .storyline")
            .firstOrNull { it.text().length > 30 }
            ?.text()

        val poster = PutarFlixUtils.extractMetaImage(api.mainUrl, doc)
        val plot = PutarFlixUtils.cleanText(
            doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: descriptionText
        ).ifBlank { null }

        val tags = doc.select("a[rel=tag], .sgeneros a, .genxed a, .mta a, a[href*='/category/']")
            .map { PutarFlixUtils.cleanText(it.text()) }
            .filter { it.length in 2..32 && !it.equals("Watch", true) }
            .distinct()
            .take(20)

        val recommendations = parseCards(api, doc).filterNot { it.url == url }.take(12)
        val year = PutarFlixUtils.extractYear(metadataText) ?: PutarFlixUtils.extractYear(title)
        val duration = PutarFlixUtils.extractDuration(metadataText)
        val rating = PutarFlixUtils.extractRating(doc.selectFirst(".rating, .imdb, .starstruck-rating, .dt_rating_vgs")?.text())
            ?: PutarFlixUtils.extractRating(metadataText)

        val episodes = parseEpisodes(api, doc)
        val type = PutarFlixUtils.typeFrom(url, title, metadataText)

        return if (type == TvType.TvSeries && episodes.isNotEmpty() && !url.contains("/eps/")) {
            api.newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, type, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.duration = duration
                this.score = Score.from10(rating)
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, doc: Document): List<Episode> {
        return doc.select("a[href*='/eps/'], .episodios a[href], .episode a[href], .se-c a[href], .eplister a[href], .all-episodes a[href]")
            .mapNotNull { anchor ->
                val epUrl = PutarFlixUtils.absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return@mapNotNull null
                if (!epUrl.contains("/eps/")) return@mapNotNull null
                val rawName = PutarFlixUtils.cleanText(anchor.text()).ifBlank {
                    PutarFlixUtils.cleanTitle(anchor.attr("title"))
                }.ifBlank { epUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ') }
                api.newEpisode(epUrl) {
                    name = rawName
                    season = PutarFlixUtils.seasonNumber(rawName)
                    episode = PutarFlixUtils.episodeNumber(rawName)
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 }.thenBy { it.name })
    }

    private fun bestContentAnchor(element: Element): Element? {
        if (element.tagName().equals("a", true) && element.hasAttr("href")) return element
        return element.selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .data h3 a[href], .data h2 a[href], .title a[href]")
            ?: element.selectFirst("a[href*='/eps/'], a[href*='/tv/'], a[href]:has(img)")
            ?: element.selectFirst("a[href]")
    }

    private fun sectionTitle(section: Element): String? {
        return listOfNotNull(
            section.selectFirst("h1, h2, h3, .widget-title, .section-title, .module-title, .block-title")?.text(),
            section.attr("aria-label").takeIf { it.isNotBlank() }
        ).map(PutarFlixUtils::cleanTitle).firstOrNull { it.isNotBlank() && it.length < 80 }
    }
}
