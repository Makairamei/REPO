package com.nonton01

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.nonton01.Nonton01Utils.absoluteUrl
import com.nonton01.Nonton01Utils.cleanText
import com.nonton01.Nonton01Utils.durationMinutes
import com.nonton01.Nonton01Utils.isValidPoster
import com.nonton01.Nonton01Utils.isVideoUrl
import com.nonton01.Nonton01Utils.typeFromUrlOrTitle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object Nonton01Parser {
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
        "a[href]:has(img)"
    ).joinToString(",")

    private const val IMAGE_SELECTOR =
        "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[srcset]"

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val primary = document.select(cardSelectors)
        val containers: List<Element> = if (primary.isNotEmpty()) {
            primary.toList()
        } else {
            document.select("a[href]:has(img)").toList()
        }

        return containers
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val link = when {
            element.tagName().equals("a", ignoreCase = true) && element.hasAttr("href") -> element
            else -> element.selectFirst("a[href]:has(img)")
                ?: element.selectFirst("h2 a[href], h3 a[href], .title a[href], a[title][href]")
                ?: return null
        }

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val image = link.selectFirst(IMAGE_SELECTOR)
            ?: element.selectFirst(IMAGE_SELECTOR)
            ?: element.parent()?.selectFirst(IMAGE_SELECTOR)
            ?: return null

        val title = cleanTitle(
            link.attr("title").ifBlank { link.attr("aria-label") }.ifBlank { link.text() }.ifBlank {
                image.attr("alt").ifBlank { image.attr("title") }
            }
        ).ifBlank { return null }

        val poster = extractPoster(api.mainUrl, image, link) ?: extractPoster(api.mainUrl, image, element)
        if (!isValidPoster(poster)) return null

        val type = typeFromUrlOrTitle(href, title)
        return api.newMovieSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = extractDetailTitle(document, url) ?: return null

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
            "a[href*='/genre/'], " +
                "a[href*='/country/'], " +
                "a[href*='/year/'], " +
                "a[href*='/quality/'], " +
                "a[href*='/action/'], " +
                "a[href*='/adventure/'], " +
                "a[href*='/animation/'], " +
                "a[href*='/comedy/'], " +
                "a[href*='/crime/'], " +
                "a[href*='/drama/'], " +
                "a[href*='/fantasy/'], " +
                "a[href*='/horror/'], " +
                "a[href*='/mystery/'], " +
                "a[href*='/romance/'], " +
                "a[href*='/sci-fi/'], " +
                "a[href*='/thriller/']"
        )
            .map { cleanTitle(it.text()) }
            .filter { it.length in 2..30 }
            .filterNot { tag ->
                tag.equals("Watch", true) ||
                    tag.equals("Trailer", true) ||
                    tag.equals("Download", true) ||
                    tag.equals("Home", true)
            }
            .distinct()
            .take(16)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseRecommendations(api, document, url)
        val type = if (episodes.size > 1) TvType.TvSeries else typeFromUrlOrTitle(url, title)

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
            val normalized = href.substringBefore("#")
            if (!seen.add(normalized)) return@mapNotNull null

            val text = cleanTitle(anchor.text())
                .ifBlank { cleanTitle(anchor.attr("title")) }
                .ifBlank { "Episode" }

            api.newEpisode(href) {
                name = text
            }
        }

        return episodes.ifEmpty {
            listOf(
                api.newEpisode(fallbackUrl) {
                    name = "Movie"
                }
            )
        }
    }

    private fun parseRecommendations(api: MainAPI, document: Document, currentUrl: String): List<SearchResponse> {
        val relatedSelectors = listOf(
            ".related",
            ".recommended",
            ".recommend",
            ".similar",
            ".more-like-this",
            ".film-recommend",
            ".movie-related",
            ".movies-related",
            "#related",
            "#recommended"
        ).joinToString(",")

        val relatedResults = document.selectFirst(relatedSelectors)
            ?.select(cardSelectors)
            ?.mapNotNull { parseCard(api, it) }
            .orEmpty()

        return (relatedResults.ifEmpty { parseListing(api, document) })
            .filterNot { it.url == currentUrl }
            .distinctBy { it.url }
            .take(12)
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
                if (candidateText.contains("nonton01") || candidateText.contains("logo")) score -= 150

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
        val ignored = setOf("sub", "dub", "the", "movie", "film", "and", "with", "untuk")
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
            .mapNotNull { absoluteUrl(baseUrl, it)?.let { poster -> upscalePosterUrl(poster) } }
            .firstOrNull { isValidPoster(it) }
    }

    private fun extractDetailTitle(document: Document, url: String): String? {
        val descriptionTitle = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.let { text ->
                Regex("""(?i)\bnonton\s+(.+?)\s+(?:streaming|subtitle|sub\b|film\b|movie\b|online\b|gratis\b)""")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
            }

        val candidates = listOfNotNull(
            descriptionTitle,
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.title(),
            document.selectFirst("h1.entry-title, h1.post-title, .single-title h1, .entry-header h1")?.text(),
            document.selectFirst("h1")?.text(),
            titleFromUrl(url)
        )

        return candidates
            .asSequence()
            .map { cleanDetailTitle(it) }
            .firstOrNull { title ->
                title.length >= 3 &&
                    !title.equals("Trailer", ignoreCase = true) &&
                    !title.equals("Download", ignoreCase = true) &&
                    !title.equals("Watch", ignoreCase = true) &&
                    !title.equals("Movie", ignoreCase = true)
            }
    }

    private fun titleFromUrl(url: String): String {
        val slug = url.trimEnd('/')
            .substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                if (part.all { ch -> ch.isDigit() }) part
                else part.lowercase().replaceFirstChar { ch -> ch.uppercase() }
            }
    }

    private fun cleanDetailTitle(value: String?): String {
        return cleanTitle(value)
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+streaming\\s+movies?.*$"), "")
            .replace(Regex("(?i)\\s+streaming\\s+film.*$"), "")
            .replace(Regex("(?i)\\s+film\\s+terbaru.*$"), "")
            .replace(Regex("(?i)\\s+online\\s+dan\\s+gratis.*$"), "")
            .replace(Regex("(?i)\\s+terlengkap\\s+subtitle.*$"), "")
            .replace(Regex("(?i)\\s+01nonton.*$"), "")
            .replace(Regex("(?i)\\s+-\\s+trailer$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^permalink:\\s*"), "")
            .replace(Regex("(?i)\\s+-\\s+nonton01$"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia$"), " Sub")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
