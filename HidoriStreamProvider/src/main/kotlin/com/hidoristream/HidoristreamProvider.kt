package com.hidoristream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HidoristreamProvider : MainAPI() {
    override var mainUrl = "https://v3.hidoristream.online"
    override var name = "HidoriStream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        var context: android.content.Context? = null

        fun getType(text: String?): TvType {
            val value = text.orEmpty()

            return when {
                value.contains("movie", true) -> TvType.AnimeMovie
                value.contains("ova", true) -> TvType.OVA
                value.contains("special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(text: String?): ShowStatus {
            val value = text.orEmpty()

            return when {
                value.contains("ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing" to "Ongoing Anime",
        "anime/?status=completed" to "Completed Anime",
        "anime/?order=latest" to "Just Added",
        "anime/?order=popular" to "Most Popular",
        "anime/?order=rating" to "Top Rating",
        "anime/?type=movie" to "Movie",
        "anime/?type=ova" to "OVA",
        "anime/?type=special" to "Special",

        "anime/?genre=action" to "Action",
        "anime/?genre=adventure" to "Adventure",
        "anime/?genre=comedy" to "Comedy",
        "anime/?genre=drama" to "Drama",
        "anime/?genre=ecchi" to "Ecchi",
        "anime/?genre=fantasy" to "Fantasy",
        "anime/?genre=harem" to "Harem",
        "anime/?genre=horror" to "Horror",
        "anime/?genre=isekai" to "Isekai",
        "anime/?genre=magic" to "Magic",
        "anime/?genre=mecha" to "Mecha",
        "anime/?genre=music" to "Music",
        "anime/?genre=mystery" to "Mystery",
        "anime/?genre=psychological" to "Psychological",
        "anime/?genre=romance" to "Romance",
        "anime/?genre=school" to "School",
        "anime/?genre=sci-fi" to "Sci-Fi",
        "anime/?genre=seinen" to "Seinen",
        "anime/?genre=shoujo" to "Shoujo",
        "anime/?genre=shounen" to "Shounen",
        "anime/?genre=slice-of-life" to "Slice of Life",
        "anime/?genre=sports" to "Sports",
        "anime/?genre=supernatural" to "Supernatural",
        "anime/?genre=thriller" to "Thriller"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val items = parseAnimeCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    "a[href*='page=${page + 1}']"
            ) != null || items.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        data: String,
        page: Int
    ): String {
        val clean = data.trimStart('/')

        return when {
            clean.startsWith("http", true) -> {
                if (page <= 1) clean
                else if (clean.contains("?")) "$clean&page=$page" else "$clean?page=$page"
            }

            page <= 1 -> "$mainUrl/$clean"

            clean.contains("?") -> "$mainUrl/$clean&page=$page"

            else -> "$mainUrl/$clean?page=$page"
        }
    }

    private fun parseAnimeCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "div.listupd article.bs, " +
                "article.bs, " +
                ".bs, " +
                ".listupd .bsx, " +
                ".bsx, " +
                "article a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("div.tt")?.text()?.trim(),
            selectFirst(".tt")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        return newAnimeSearchResponse(
            title,
            href,
            getType(title)
        ) {
            posterUrl = poster
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blocked = listOf(
            "genre/",
            "studio/",
            "producer/",
            "season/",
            "status/",
            "type/",
            "page/",
            "jadwal",
            "privacy",
            "dmca",
            "contact"
        )

        return blocked.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/${page.coerceAtLeast(1)}/?s=$encoded"
        }

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = parseAnimeCards(document)
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    "a[href*='/page/${page + 1}/']"
            ) != null || results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst("div.bigcontent img, .bigcontent img, .thumb img, img.wp-post-image")
                ?.getImageAttr()
        )

        val description = document.select(
            "div.entry-content p, " +
                ".entry-content p, " +
                ".entry-content, " +
                ".sinopsis, " +
                ".synopsis"
        ).joinToString("\n") { it.text().trim() }
            .trim()
            .takeIf { it.isNotBlank() }

        val speText = document.select("div.spe span, .spe span")
            .joinToString(" ") { it.text() }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(speText)
            ?.value
            ?.toIntOrNull()

        val duration = parseDuration(speText)
        val type = getType(speText)
        val status = getStatus(speText)

        val tags = document.select(
            "div.genxed a, " +
                ".genxed a, " +
                "a[href*='/genre/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select(
            "span:has(b:matchesOwn(Artis:)) a, " +
                "span:contains(Artis) a, " +
                ".artist a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }

        val rating = document.selectFirst("div.rating strong, .rating strong")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()

        val trailer = document.selectFirst("div.bixbox.trailer iframe, .trailer iframe")
            ?.getIframeAttr()

        val recommendations = document.select("div.listupd article.bs, article.bs, .listupd .bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodeElements = document.select(
            "div.eplister ul li a[href], " +
                ".eplister ul li a[href], " +
                ".episodelist a[href], " +
                "a[href*='episode']"
        ).distinctBy { it.attr("href") }

        val episodes = if (episodeElements.isEmpty() || type == TvType.AnimeMovie) {
            listOf(
                newEpisode(url) {
                    name = title
                    episode = 1
                    posterUrl = poster
                    description?.let { this.description = it }
                    duration?.let { this.runTime = it }
                }
            )
        } else {
            episodeElements.reversed().mapIndexed { index, element ->
                val epNum = extractEpisodeNumber(element.text(), element.attr("href")) ?: index + 1

                newEpisode(fixUrl(element.attr("href"))) {
                    name = element.text().trim().ifBlank { "Episode $epNum" }.cleanTitle()
                    episode = epNum
                    posterUrl = poster
                }
            }
        }

        return newAnimeLoadResponse(
            title,
            url,
            type
        ) {
            engName = title
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            showStatus = status
            this.recommendations = recommendations
            this.duration = duration ?: 0
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
            addScore(rating)
            addActors(actors.map { Actor(it) })
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.selectFirst("div.player-embed iframe, .player-embed iframe, iframe")
            ?.getIframeAttr()
            ?.let { embedLinks.add(httpsify(it)) }

        document.select("select.mirror option[value]:not([disabled]), select option[value]:not([disabled])")
            .forEach { option ->
                val value = option.attr("value").trim()
                if (value.isBlank()) return@forEach

                runCatching {
                    val decoded = base64Decode(value.replace("\\s".toRegex(), ""))
                    val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                    val mirrorUrl = iframe?.getIframeAttr()
                    if (!mirrorUrl.isNullOrBlank()) {
                        embedLinks.add(httpsify(mirrorUrl))
                    }
                }
            }

        document.select(
            "div.dlbox li span.e a[href], " +
                ".dlbox a[href], " +
                "a[href*='.m3u8'], " +
                "a[href*='.mp4'], " +
                "source[src], " +
                "video[src]"
        ).forEach { element ->
            val raw = element.attr("href")
                .ifBlank { element.attr("src") }
                .trim()

            if (raw.isBlank()) return@forEach

            val fixed = fixUrl(raw)

            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        extractMediaUrls(html).forEach { url ->
            when {
                url.contains(".m3u8", true) || url.contains(".mp4", true) -> directLinks.add(url)
                url.startsWith("http", true) -> embedLinks.add(url)
            }
        }

        var found = false

        directLinks.forEach { link ->
            if (link.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = data
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(link).takeIf {
                            it != Qualities.Unknown.value
                        } ?: qualityFromUrl(link)
                    }
                )
            }

            found = true
        }

        embedLinks.distinct().forEach { embed ->
            val success = loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )

            if (success) found = true
        }

        return found
    }

    private fun extractMediaUrls(html: String): List<String> {
        val links = linkedSetOf<String>()
        val cleaned = html.cleanEscaped()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""(?:file|src|source|url|videoUrl)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .map { fixUrl(it) }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun parseDuration(text: String): Int? {
        val h = Regex("""(\d+)\s*(?:hr|hour|jam)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        val m = Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        val total = h * 60 + m

        return total.takeIf { it > 0 }
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)-?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src")
            ?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(
                Regex(
                    """\b(Sub(\s*)?(title)?\s*Indonesia|Subtitle\s*Indonesia|Sub\s*Indo)\b""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}