package com.sad25kag.desisins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Desisins : MainAPI() {
    override var mainUrl = "https://desisins.com"
    private val shortsUrl = "https://shorts.desisins.com"

    override var name = "Desisins."
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/category/reloaded/" to "Reloaded",
        "$shortsUrl/" to "Shorts",

        "$mainUrl/category/role-play/" to "Role Play",
        "$mainUrl/category/mms/" to "MMS",
        "$mainUrl/category/desi-phoren/" to "NRI",

        "$mainUrl/category/genre/anal/" to "Anal",
        "$mainUrl/category/genre/bdsm/" to "BDSM",
        "$mainUrl/category/genre/blowjob/" to "Blowjob",
        "$mainUrl/category/genre/creampie/" to "Creampie",
        "$mainUrl/category/genre/dirty-talk/" to "Dirty Talk",
        "$mainUrl/category/genre/foursome/" to "Foursome",
        "$mainUrl/category/genre/horny/" to "Horny",
        "$mainUrl/category/genre/lesbian/" to "Lesbian",
        "$mainUrl/category/genre/tease/" to "Tease",
        "$mainUrl/category/genre/threesome/" to "Threesome",

        "$mainUrl/tag/hardcore/" to "Hardcore",
        "$mainUrl/tag/premium/" to "Premium",
        "$mainUrl/tag/scandals/" to "Scandals",
        "$mainUrl/tag/ticket-shows/" to "Ticket Shows",

        "$mainUrl/tag/i-likes/" to "I Likes",
        "$mainUrl/tag/videos/" to "Videos",
        "$mainUrl/tag/shows/" to "Shows",
        "$mainUrl/tag/stars/" to "Stars",

        "$mainUrl/category/livex/" to "LiveX",
        "$mainUrl/category/live-shows/" to "Live Shows",
        "$mainUrl/category/solo/" to "Solo",

        "$mainUrl/category/powershot/" to "PowerShot",
        "$mainUrl/category/models/" to "Models",
        "$mainUrl/category/viral-stars/" to "Viral Stars",

        "$mainUrl/category/chit-chat/" to "Chit Chat",
        "$mainUrl/category/vidmag/" to "VidMag",
        "$mainUrl/category/teaser/" to "Teaser",
        "$mainUrl/category/wksh/" to "WKSH",

        "$shortsUrl/category/channel/big-shots/" to "Shorts: Big Shots",
        "$shortsUrl/category/channel/bull/" to "Shorts: Bull",
        "$shortsUrl/category/channel/ullu/" to "Shorts: Ullu",
        "$shortsUrl/category/channel/prime-play/" to "Shorts: Prime Play",
        "$shortsUrl/category/channel/hunters/" to "Shorts: Hunters",
        "$shortsUrl/category/channel/voovi/" to "Shorts: Voovi",
        "$shortsUrl/category/channel/fliz/" to "Shorts: Fliz",
        "$shortsUrl/category/channel/digiflix/" to "Shorts: DigiFlix",
        "$shortsUrl/category/channel/hot-shots/" to "Shorts: Hot Shots",
        "$shortsUrl/category/channel/prime-shots/" to "Shorts: Prime Shots",
        "$shortsUrl/category/channel/balloons/" to "Shorts: Balloons",
        "$shortsUrl/category/channel/besharms/" to "Shorts: Besharms",
        "$shortsUrl/category/channel/kooku/" to "Shorts: Kooku",
        "$shortsUrl/category/channel/moodx/" to "Shorts: MoodX",
        "$shortsUrl/category/channel/rabbit/" to "Shorts: Rabbit",

        "$shortsUrl/category/channel/bindass/" to "Shorts: Bindass",
        "$shortsUrl/category/channel/up11/" to "Shorts: UP11",
        "$shortsUrl/category/channel/tadka/" to "Shorts: Tadka",
        "$shortsUrl/category/channel/bmz/" to "Shorts: BMZ",
        "$shortsUrl/category/channel/hotty-notty/" to "Shorts: Hotty Notty",
        "$shortsUrl/category/channel/mixedbag/" to "Shorts: MixedBag",
        "$shortsUrl/category/channel/movies-web-series/" to "Shorts: Movies/Web Series",
        "$shortsUrl/category/channel/uncut/" to "Shorts: Uncut",

        "$shortsUrl/category/genre/3some/" to "Shorts: 3Some",
        "$shortsUrl/category/genre/amateur/" to "Shorts: Amateur",
        "$shortsUrl/category/genre/bhabhi/" to "Shorts: Bhabhi",
        "$shortsUrl/category/genre/cheating/" to "Shorts: Cheating",
        "$shortsUrl/category/genre/couple/" to "Shorts: Couple",
        "$shortsUrl/category/genre/milf/" to "Shorts: MILF",
        "$shortsUrl/category/genre/orgasm/" to "Shorts: Orgasm",
        "$shortsUrl/category/genre/passionate/" to "Shorts: Passionate",
        "$shortsUrl/category/genre/tharki/" to "Shorts: Tharki",

        "$shortsUrl/category/shows/" to "Shorts: Shows",
        "$shortsUrl/category/top-stars/" to "Shorts: Top Stars",
        "$shortsUrl/category/trending/" to "Shorts: Trending"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document

        val items = document.select(
            "div.home_post_cont, article, .post, .g1-collection-item, .entry-tpl-grid, .item"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val fallbackItems = if (items.isNotEmpty()) {
            items
        } else {
            document.select("h3 > a[href], h2 > a[href], .entry-title a[href]")
                .mapNotNull { anchor -> anchor.parent()?.toSearchResult() ?: anchor.toSearchResultFromAnchor() }
                .distinctBy { it.url }
        }

        val hasNext = document.select("a.next, .next a, .pagination a, a[href*='/page/${page + 1}/']")
            .any { it.attr("href").isNotBlank() } || fallbackItems.size >= 10

        return newHomePageResponse(
            HomePageList(request.name, fallbackItems, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        listOf(mainUrl, shortsUrl).forEach { base ->
            runCatching {
                val document = app.get("$base/?s=$encoded", headers = headers, referer = "$base/").document
                results += document.select(
                    "div.home_post_cont, article, .post, .g1-collection-item, .entry-tpl-grid, .item"
                ).mapNotNull { it.toSearchResult() }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, referer = "$mainUrl/").document

        val title = document.selectFirst("h1, .entry-title")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: "Desisins"

        val poster = findPosterUrl(document.body(), url)
            ?: document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
                ?.attr("content")
                ?.takeIf { it.isValidImageValue() }
                ?.let { absoluteUrlOrNull(it, url) }

        val description = document.selectFirst("meta[property=og:description], meta[name=description], div.g1-meta, .entry-summary")
            ?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }
            ?.trim()
            ?.ifBlank { null }

        val tags = document.select("a[rel=category tag], .tags a, .entry-categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select(
            "div.home_post_cont, article, .post, .g1-collection-item, .entry-tpl-grid"
        ).mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(tags)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val document = app.get(data, headers = headers, referer = "$mainUrl/").document
        val html = document.html()
        val candidates = linkedSetOf<String>()

        Regex("""docid=([a-zA-Z0-9]+)""")
            .findAll(html)
            .mapTo(candidates) { "https://lulustream.com/e/${it.groupValues[1]}" }

        document.select("iframe[src], iframe[data-src], a[href*='lulustream'], a[href*='lulu'], video source[src], source[src]")
            .mapNotNullTo(candidates) { element ->
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("href") }
                raw.takeIf { it.isNotBlank() }?.let { absoluteUrl(it, data) }
            }

        Regex("""https?://[^"'\\\s<>]+(?:lulustream|lulu|stream|embed)[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapTo(candidates) { it.value.replace("\\/", "/") }

        var found = false
        candidates.forEach { link ->
            runCatching {
                if (loadExtractor(link, data, subtitleCallback, callback)) {
                    found = true
                }
            }
        }

        return found || candidates.isNotEmpty()
    }

    private fun absoluteUrl(rawUrl: String, baseUrl: String = mainUrl): String {
        val value = rawUrl.trim().replace("\\/", "/").replace("&amp;", "&")
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"

        val base = when {
            baseUrl.startsWith("http://") || baseUrl.startsWith("https://") -> baseUrl.trimEnd('/')
            else -> mainUrl.trimEnd('/')
        }

        val domain = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.getOrNull(1) ?: mainUrl.trimEnd('/')
        return if (value.startsWith('/')) {
            "$domain$value"
        } else {
            "$domain/$value"
        }
    }

    private fun absoluteUrlOrNull(rawUrl: String?, baseUrl: String = mainUrl): String? {
        return rawUrl?.takeIf { it.isNotBlank() }?.let { absoluteUrl(it, baseUrl) }
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (page <= 1) return rawUrl
        val clean = rawUrl.trimEnd('/')
        return when {
            clean.contains("?") -> "$clean&paged=$page"
            else -> "$clean/page/$page/"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h3 > a[href], h2 > a[href], .entry-title a[href], a[href]")
            ?: return null

        return anchor.toSearchResultFromAnchor(this)
    }

    private fun Element.toSearchResultFromAnchor(container: Element = this): SearchResponse? {
        val href = attr("href").ifBlank { selectFirst("a[href]")?.attr("href").orEmpty() }
        val fixedHref = absoluteUrlOrNull(href, mainUrl) ?: return null

        if (!fixedHref.contains("desisins.com", true)) return null
        if (fixedHref.contains("/category/", true) || fixedHref.contains("/tag/", true)) return null

        val title = attr("title").trim()
            .ifBlank { text().trim() }
            .ifBlank { container.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        if (title.isBlank()) return null

        val poster = findPosterUrl(container, fixedHref)

        return newMovieSearchResponse(title, fixedHref, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun findPosterUrl(container: Element, pageUrl: String): String? {
        val boxes = listOfNotNull(
            container,
            container.parent(),
            container.parent()?.parent(),
            container.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImageFromElement(box, pageUrl)?.let { return it }

            box.select("img, source, a, span, div").forEach { element ->
                extractImageFromElement(element, pageUrl)?.let { return it }
            }
        }

        return null
    }

    private fun extractImageFromElement(element: Element, pageUrl: String): String? {
        val candidates = mutableListOf<String>()
        val attrs = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-bg",
            "data-background",
            "data-thumb",
            "data-image",
            "src",
            "poster"
        )

        attrs.forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isValidImageValue()) candidates.add(value)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val value = element.attr(attr).trim()
            value.split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .filter { it.isValidImageValue() }
                .forEach(candidates::add)
        }

        val style = element.attr("style")
        if (style.isNotBlank()) {
            Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
                .find(style)
                ?.groupValues
                ?.getOrNull(2)
                ?.trim()
                ?.takeIf { it.isValidImageValue() }
                ?.let(candidates::add)
        }

        return candidates
            .distinct()
            .sortedByDescending { it.posterScore() }
            .firstOrNull()
            ?.let { absoluteUrlOrNull(it, pageUrl) }
    }

    private fun String.isValidImageValue(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false

        val lower = lowercase()
        if (lower.contains("blank") ||
            lower.contains("placeholder") ||
            lower.contains("spacer") ||
            lower.contains("favicon") ||
            lower.contains("cropped") ||
            lower.contains("logo") ||
            lower.contains("banner") ||
            lower.contains("header") ||
            lower.endsWith(".svg")
        ) return false

        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains("/wp-content/uploads/")
    }

    private fun String.posterScore(): Int {
        val lower = lowercase()
        var score = 0

        if (lower.contains("thumb")) score += 8
        if (lower.contains("poster")) score += 8
        if (lower.contains("featured")) score += 6
        if (lower.contains("uploads")) score += 4
        if (lower.contains(".webp")) score += 3
        if (lower.contains(".jpg") || lower.contains(".jpeg")) score += 2

        if (lower.contains("150x150")) score -= 4
        if (lower.contains("100x")) score -= 4
        if (lower.contains("32x32")) score -= 10
        if (lower.contains("cropped") || lower.contains("logo") || lower.contains("banner")) score -= 20

        return score
    }
}
