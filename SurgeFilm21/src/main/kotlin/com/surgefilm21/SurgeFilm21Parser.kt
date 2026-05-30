package com.surgefilm21

import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object SurgeFilm21Parser {
    private val cardSelectors = listOf(
        "article", ".movie-item", ".film-item", ".poster", ".item", ".card", ".grid a[href]",
        ".swiper-slide", ".splide__slide", ".poster-card", "a[href]"
    ).joinToString(",")

    fun parseHomeItems(api: MainAPI, document: Document, baseUrl: String, defaultType: TvType = TvType.Movie): List<SearchResponse> {
        return document.select(cardSelectors)
            .mapNotNull { it.toSearchResponse(api, baseUrl, defaultType) }
            .distinctBy { it.url }
            .filter { it.name.length >= 2 }
    }

    fun parseSectionItems(api: MainAPI, document: Document, sectionName: String, baseUrl: String, defaultType: TvType = TvType.Movie): List<SearchResponse> {
        val exact = document.select("h1,h2,h3,h4,.section-title,.title")
            .firstOrNull { it.text().contains(sectionName, ignoreCase = true) }

        val scopes = listOfNotNull(
            exact?.parent(),
            exact?.parent()?.parent(),
            exact?.parent()?.parent()?.parent(),
            exact?.nextElementSibling()
        ).distinct()

        val scoped = scopes.flatMap { scope ->
            scope.select(cardSelectors).mapNotNull { it.toSearchResponse(api, baseUrl, defaultType) }
        }.distinctBy { it.url }

        return scoped.ifEmpty { parseHomeItems(api, document, baseUrl, defaultType) }
    }

    private fun Element.toSearchResponse(api: MainAPI, baseUrl: String, defaultType: TvType = TvType.Movie): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absUrlSf21(baseUrl) ?: return null
        if (!href.contains("surgafilm21.homes", true)) return null
        if (href.isNoiseUrlSf21()) return null

        val title = listOf(
            attr("title"),
            selectFirst("h1,h2,h3,h4,.title,.name,.film-title,.movie-title")?.text(),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.attr("title"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitleSf21() ?: return null

        if (title.length < 2) return null
        if (title.equals("lihat semua", true) || title.equals("watch now", true) || title.equals("favorite", true)) return null

        val poster = posterUrl(this, baseUrl)
        val type = inferType(title, href, defaultType)

        return when (type) {
            TvType.TvSeries, TvType.AsianDrama, TvType.Anime -> api.newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
            else -> api.newMovieSearchResponse(title, href, type) { posterUrl = poster }
        }
    }

    fun posterUrl(element: Element, baseUrl: String): String? {
        val candidates = mutableListOf<String>()
        element.select("img, source, [style], [data-src], [data-original], [data-lazy-src]").forEach { img ->
            listOf(
                img.attr("data-src"), img.attr("data-original"), img.attr("data-lazy-src"),
                img.attr("data-image"), img.attr("data-poster"), img.attr("poster"), img.attr("src")
            ).filter { it.isNotBlank() }.forEach(candidates::add)

            listOf("srcset", "data-srcset").forEach { attr ->
                img.attr(attr).split(",").map { it.trim().substringBefore(" ").trim() }.filter { it.isNotBlank() }.forEach(candidates::add)
            }

            Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
                .find(img.attr("style"))?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.let(candidates::add)
        }

        return candidates.distinct()
            .filterNot { it.lowercase().contains("logo") || it.lowercase().contains("favicon") || it.lowercase().contains("placeholder") }
            .sortedByDescending { it.posterCandidateScoreSf21() }
            .firstOrNull()?.absUrlSf21(baseUrl)
    }

    fun parseTitle(document: Document): String {
        return listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.selectFirst("h2")?.text(),
            document.title()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitleSf21().orEmpty()
    }

    fun parsePoster(document: Document, pageUrl: String): String? {
        return listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst("video[poster]")?.attr("poster")
        ).firstOrNull { !it.isNullOrBlank() }?.absUrlSf21(pageUrl) ?: posterUrl(document.body(), pageUrl)
    }

    fun parsePlot(document: Document): String? {
        return listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".synopsis,.sinopsis,.description,.overview,.entry-content,.content,.movie-desc")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.cleanSf21()?.takeIf { it.length > 10 }
    }

    fun parseTags(document: Document): List<String> {
        return document.select("a[href*='/genre/'], a[href*='/category/'], a[href*='/country/'], .genre a, .genres a, .category a, .tag a")
            .map { it.text().cleanSf21() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()
    }

    fun parseYear(document: Document, title: String): Int? {
        return Regex("""\((19\d{2}|20\d{2})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: document.selectFirst(".year,a[href*='/year/']")?.text()?.filter { it.isDigit() }?.toIntOrNull()
    }

    fun parseRating(document: Document): Int? {
        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .imdb, .score")?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
        return rating?.times(1000)?.toInt()
    }

    fun parseEpisodes(api: MainAPI, document: Document, pageUrl: String): List<Episode> {
        val candidates = document.select("a[href*='episode'], a[href*='/eps'], a[href*='?episode='], .episode a[href], .episodes a[href], .eps a[href], .eplist a[href], .list-episode a[href]")
        return candidates.mapNotNull { element ->
            val href = element.attr("href").absUrlSf21(pageUrl) ?: return@mapNotNull null
            if (href == pageUrl) return@mapNotNull null
            val number = Regex("""(?i)(?:episode|eps|ep|e)[-_\s]*(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""^\D*(\d+)\D*$""").find(element.text().trim())?.groupValues?.getOrNull(1)?.toIntOrNull()
            api.newEpisode(href) {
                name = element.text().cleanSf21().takeIf { it.isNotBlank() && it.length > 1 } ?: number?.let { "Episode $it" } ?: "Episode"
                episode = number
            }
        }.distinctBy { it.data }
    }

    fun inferType(title: String, url: String, defaultType: TvType = TvType.Movie): TvType {
        val text = "$title $url".lowercase()
        return when {
            text.contains("anime") && text.contains("movie") -> TvType.AnimeMovie
            text.contains("animation") || text.contains("animasi") || text.contains("cartoon") -> TvType.Cartoon
            text.contains("series") || text.contains("season") || text.contains("episode") || url.isSeriesLikeSf21() -> TvType.TvSeries
            text.contains("drama china") || text.contains("drama korea") || text.contains("thai") || text.contains("thailand") -> TvType.AsianDrama
            text.contains("semi") || text.contains("dewasa") -> TvType.NSFW
            else -> defaultType
        }
    }

    fun parseDocumentFromHtml(html: String, baseUrl: String): Document = Jsoup.parse(html.cleanSf21(), baseUrl)
}
