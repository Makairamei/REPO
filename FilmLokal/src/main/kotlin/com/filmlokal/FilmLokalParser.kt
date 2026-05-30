package com.filmlokal

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.filmlokal.FilmLokalUtils.absoluteUrl
import com.filmlokal.FilmLokalUtils.cleanText
import com.filmlokal.FilmLokalUtils.durationMinutes
import com.filmlokal.FilmLokalUtils.isValidPoster
import com.filmlokal.FilmLokalUtils.isVideoUrl
import com.filmlokal.FilmLokalUtils.typeFromUrlOrTitle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object FilmLokalParser {
    private val cardSelectors = listOf(
        "article",
        ".ml-item",
        ".movie",
        ".movie-item",
        ".result-item",
        ".film",
        ".post",
        ".item:has(img)",
        ".owl-item:has(img)",
        ".swiper-slide:has(img)",
        "a[href]:has(img)"
    ).joinToString(",")

    private const val IMAGE_SELECTOR =
        "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[srcset]"

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        val containers = document.select(cardSelectors)
            .ifEmpty { document.select("a[href]:has(img)") }

        containers.mapNotNull { parseCard(api, it) }
            .forEach { results[it.url] = it }

        if (results.isEmpty()) {
            document.select("a[href]").mapNotNull { parseLooseAnchor(api, it) }
                .forEach { results[it.url] = it }
        }

        return results.values.take(72)
    }

    private fun parseLooseAnchor(api: MainAPI, anchor: Element): SearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val text = listOf(
            anchor.attr("title"),
            anchor.text(),
            anchor.selectFirst("img[alt]")?.attr("alt"),
            href.substringAfterLast('/').replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() } ?: return null

        val title = cleanTitle(text).ifBlank { return null }
        if (title.length < 2 || isBadTitle(title)) return null

        val poster = extractPoster(api.mainUrl, anchor.selectFirst(IMAGE_SELECTOR), anchor)
        val type = typeFromUrlOrTitle(href, title)

        return if (type == TvType.TvSeries) {
            api.newTvSeriesSearchResponse(title, href, type) { posterUrl = poster }
        } else {
            api.newMovieSearchResponse(title, href, type) { posterUrl = poster }
        }
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val link = when {
            element.tagName().equals("a", ignoreCase = true) && element.hasAttr("href") -> element
            else -> element.selectFirst("a[href]:has(img)")
                ?: element.selectFirst("h1 a[href], h2 a[href], h3 a[href], .title a[href], .entry-title a[href], a[title][href], a[href]")
                ?: return null
        }

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val image = link.selectFirst(IMAGE_SELECTOR)
            ?: element.selectFirst(IMAGE_SELECTOR)
            ?: element.parent()?.selectFirst(IMAGE_SELECTOR)

        val title = cleanTitle(
            element.selectFirst("h1, h2, h3, .title, .entry-title")?.text()
                ?: link.attr("title").ifBlank { link.attr("aria-label") }.ifBlank { link.text() }.ifBlank {
                    image?.attr("alt").orEmpty().ifBlank { image?.attr("title").orEmpty() }
                }.ifBlank {
                    href.substringAfterLast('/').replace("-", " ")
                }
        ).ifBlank { return null }

        if (title.length < 2 || isBadTitle(title)) return null

        val poster = extractPoster(api.mainUrl, image, link)
            ?: extractPoster(api.mainUrl, image, element)

        val type = typeFromUrlOrTitle(href, title)
        return if (type == TvType.TvSeries) {
            api.newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
            }
        } else {
            api.newMovieSearchResponse(title, href, type) {
                posterUrl = poster
            }
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1, .title, .heading")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { return null }

        val contentRoot = document.selectFirst("article, .entry-content, .single, .post, main") ?: document
        val poster = extractDetailPoster(api.mainUrl, title, document)
            ?: extractPoster(
                api.mainUrl,
                contentRoot.selectFirst(".poster img, .thumb img, .mvic-thumb img, .wp-post-image, img[src*='uploads'], img[alt], img[title]"),
                contentRoot
            )
            ?: absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
                ?.takeIf { isValidPoster(it) }

        val plot = cleanText(
            document.selectFirst(".desc, .description, .entry-content p, .sinopsis, .synopsis")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select(
            "a[href*='/genre/'], a[href*='/country/'], a[href*='/year/'], a[href*='/quality/'], " +
                "a[href*='/action/'], a[href*='/adventure/'], a[href*='/animation/'], a[href*='/comedy/'], " +
                "a[href*='/crime/'], a[href*='/drama/'], a[href*='/fantasy/'], a[href*='/horror/'], " +
                "a[href*='/mystery/'], a[href*='/romance/'], a[href*='/sci-fi/'], a[href*='/thriller/']"
        )
            .map { cleanTitle(it.text()) }
            .filter { it.length in 2..30 }
            .filterNot { tag ->
                tag.equals("Watch", true) ||
                    tag.equals("Trailer", true) ||
                    tag.equals("Download", true) ||
                    tag.equals("Home", true) ||
                    tag.equals("Movie Ads", true)
            }
            .distinct()
            .take(16)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)
        val type = if (episodes.isNotEmpty()) TvType.TvSeries else typeFromUrlOrTitle(url, title)

        return if (type == TvType.TvSeries) {
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
                duration = durationMinutes(document.text())
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val selectors = ".eps a[href], .episode a[href], .episodes a[href], .episodelist a[href], a[href*='episode'], a[href*='season']"
        val episodes = document.select(selectors).mapNotNull { anchor ->
            val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return@mapNotNull null
            if (!isVideoUrl(href)) return@mapNotNull null
            if (href.substringBefore("#") == fallbackUrl.substringBefore("#")) return@mapNotNull null
            val normalized = href.substringBefore("#")
            if (!seen.add(normalized)) return@mapNotNull null

            val text = cleanTitle(anchor.text())
                .ifBlank { cleanTitle(anchor.attr("title")) }
                .ifBlank { "Episode" }

            api.newEpisode(href) {
                name = text
            }
        }

        return episodes
    }

    private fun extractDetailPoster(baseUrl: String, title: String, document: Document): String? {
        val tokens = titleTokens(title)
        val candidates = mutableListOf<Pair<Int, String>>()

        document.select(IMAGE_SELECTOR).forEach { image ->
            val sourceText = listOf(
                image.attr("alt"),
                image.attr("title"),
                image.attr("aria-label"),
                image.attr("src"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original")
            ).joinToString(" ").lowercase()

            val ancestry = generateSequence(image) { element -> element.parent() }
                .take(5)
                .joinToString(" ") { "${it.id()} ${it.className()}" }
                .lowercase()

            imageSourceCandidates(image).forEach { raw ->
                val poster = absoluteUrl(baseUrl, raw)?.let { upscalePosterUrl(it) } ?: return@forEach
                if (!isValidPoster(poster)) return@forEach

                val candidateText = "$sourceText ${poster.substringAfterLast('/').substringBefore('?').lowercase()}"
                val tokenHits = tokens.count { candidateText.contains(it) }
                var score = tokenHits * 30

                if (tokens.isNotEmpty() && tokenHits >= minOf(2, tokens.size)) score += 80
                if (ancestry.contains("poster") || ancestry.contains("thumb") || ancestry.contains("mvic") || ancestry.contains("post-thumbnail")) score += 40
                if (poster.contains("/wp-content/uploads/", ignoreCase = true)) score += 20
                if (candidateText.contains("filmlokal") || candidateText.contains("logo")) score -= 150

                candidates += (score to poster)
            }
        }

        return candidates
            .filter { it.first > 0 }
            .distinctBy { it.second }
            .sortedByDescending { it.first }
            .firstOrNull()
            ?.second
    }

    private fun imageSourceCandidates(image: Element): List<String> {
        val sources = mutableListOf<String>()
        listOf("data-src", "data-lazy-src", "data-original", "data-wpfc-original-src", "src").forEach { attr ->
            image.attr(attr).takeIf { it.isNotBlank() }?.let { sources += it }
        }
        listOf("data-srcset", "srcset").forEach { attr ->
            image.attr(attr)
                .split(',')
                .map { it.trim().substringBefore(' ') }
                .filter { it.isNotBlank() }
                .forEach { sources += it }
        }
        return sources.distinct()
    }

    private fun titleTokens(title: String): List<String> {
        val ignored = setOf("sub", "dub", "the", "movie", "film", "and", "with", "untuk", "nonton", "watch")
        return cleanText(title.lowercase())
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 && it !in ignored && it.toIntOrNull() == null }
            .take(6)
    }

    private fun upscalePosterUrl(url: String): String {
        return url.replace(Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp)(?:\\?|$))", RegexOption.IGNORE_CASE), "")
    }

    fun extractPoster(baseUrl: String, image: Element?, container: Element? = null): String? {
        val candidates = mutableListOf<String?>()
        if (image != null) {
            candidates += imageSourceCandidates(image)
        }
        if (container != null) {
            candidates += container.selectFirst("noscript img[src]")?.attr("src")
            candidates += container.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img[src]")?.let {
                imageSourceCandidates(it).firstOrNull()
            }
            val style = container.attr("style")
                .ifBlank { container.selectFirst("[style*=background]")?.attr("style").orEmpty() }
            candidates += Regex("""url\((['\"]?)(.*?)\1\)""").find(style)?.groupValues?.getOrNull(2)
            candidates += container.selectFirst("meta[property=og:image]")?.attr("content")
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it) }
            .firstOrNull { isValidPoster(it) }
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^permalink:\\s*"), "")
            .replace(Regex("(?i)^watch\\s+movie\\s*"), "")
            .replace(Regex("(?i)^watch\\s*"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+-\\s+filmlokal$"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia$"), " Sub")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isBadTitle(title: String): Boolean {
        val low = title.lowercase()
        return low == "home" ||
            low == "movie" ||
            low == "movies" ||
            low == "more movie" ||
            low == "watch movie" ||
            low == "trailer" ||
            low == "download" ||
            low.startsWith("download via")
    }
}
