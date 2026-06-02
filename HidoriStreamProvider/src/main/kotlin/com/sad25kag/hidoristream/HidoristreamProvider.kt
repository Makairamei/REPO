package com.sad25kag.hidoristream

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
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class HidoristreamProvider : MainAPI() {
    override var mainUrl = "https://hidoristream.my.id"
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
                value.contains("ona", true) -> TvType.OVA
                value.contains("special", true) -> TvType.OVA
                value.contains("music", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(text: String?): ShowStatus {
            val value = text.orEmpty()

            return when {
                value.contains("ongoing", true) -> ShowStatus.Ongoing
                value.contains("upcoming", true) -> ShowStatus.Ongoing
                value.contains("hiatus", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private val sourceDomains = listOf(
        "https://hidoristream.my.id",
        "https://v4.hidoristream.online",
        "https://v3.hidoristream.online",
        "https://v2.hidoristream.online",
        "https://v1.hidoristream.online",
        "https://hidoristream.com"
    )

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing" to "Ongoing",
        "anime/?status=completed" to "Completed",
        "anime/?order=popular" to "Popular",
        "anime/?order=rating" to "Top Rating",

        "anime/?type=movie" to "Movie",
        "anime/?type=bd" to "BD / Batch",
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val documents = buildPageUrls(request.data, page).mapNotNull { url ->
            runCatching { fetchDocument(url) }.getOrNull()
        }

        val items = documents.flatMap { document ->
            document.select(cardSelector)
                .mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = documents.any { it.hasNextPage(page) } || items.size >= 18
        )
    }

    private fun buildPageUrls(
        data: String,
        page: Int
    ): List<String> {
        val clean = data.trim()

        return if (clean.startsWith("group:", ignoreCase = true)) {
            clean.removePrefix("group:")
                .split("|")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { buildPageUrl(it, page) }
        } else {
            listOf(buildPageUrl(clean, page))
        }
    }

    private fun buildPageUrl(
        data: String,
        page: Int
    ): String {
        val clean = data.trimStart('/')
        val base = if (clean.startsWith("http", true)) clean else "$mainUrl/$clean"

        if (page <= 1) return base

        return when {
            base.contains("?page=", true) -> base.replace(Regex("page=\\d+"), "page=$page")
            base.contains("?") -> "$base&page=$page"
            base.endsWith("/") -> "${base}page/$page/"
            else -> "$base/page/$page/"
        }
    }

    private val cardSelector = listOf(
        "div.listupd article.bs",
        ".listupd article",
        ".listupd .bs",
        ".bsx",
        "article.bs",
        "article[itemscope]",
        ".postbody article",
        ".postbody .bs",
        ".result article"
    ).joinToString(", ")

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val link = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (link.isBlank()) return null

        val title = selectFirst("div.tt, .tt, h2, h3")?.text()?.trim()
            ?: anchor.attr("title").trim().ifBlank { null }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: anchor.text().trim().ifBlank { null }
            ?: return null

        val cleanTitle = title.cleanTitle()
        if (cleanTitle.length < 2) return null
        if (cleanTitle.equals("Selanjutnya", true) || cleanTitle.equals("Next", true)) return null

        val poster = selectFirst("img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        val meta = listOf(
            selectFirst(".typez, .type, .bt .type")?.text(),
            selectFirst(".status, .bt .status")?.text(),
            text()
        ).joinToString(" ")

        return newAnimeSearchResponse(
            cleanTitle,
            fixUrl(link),
            getType(meta.ifBlank { cleanTitle })
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
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

        val document = fetchDocument(url)

        val results = document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.hasNextPage(page) || results.size >= 18
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = fetchDocument(url)

        val title = document.selectFirst("h1.entry-title, h1, .entry-title")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = document.selectFirst("div.bigcontent img, .bigcontent img, .thumb img, .infox img, img.wp-post-image")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }

        val description = document.select(
            "div.entry-content p, " +
                ".entry-content p, " +
                ".entry-content, " +
                ".sinopsis, " +
                ".synopsis, " +
                ".desc"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }

        val infoText = document.select("div.spe span, .spe span, .info-content span, .infox span")
            .joinToString(" ") { it.text() }
            .ifBlank { document.text() }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(infoText)
            ?.value
            ?.toIntOrNull()

        val duration = parseDuration(infoText)
        val type = getType(infoText)
        val status = getStatus(infoText)

        val tags = document.select(
            "div.genxed a, " +
                ".genxed a, " +
                ".genre-info a, " +
                ".mgen a, " +
                "a[href*='/genre/'], " +
                "a[href*='genre=']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select(
            "span:has(b:matchesOwn(Artis:)) a, " +
                "span:contains(Artis) a, " +
                ".artist a, " +
                "a[href*='/cast/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val rating = document.selectFirst("div.rating strong, .rating strong, .rtg, .score, span[itemprop=ratingValue]")
            ?.text()
            ?.replace("Rating", "", ignoreCase = true)
            ?.trim()

        val trailer = document.selectFirst("div.bixbox.trailer iframe, .trailer iframe, iframe[src*='youtube']")
            ?.getIframeAttr()

        val recommendations = document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodeElements = document.select(
            "div.eplister ul li a[href], " +
                ".eplister ul li a[href], " +
                ".episodelist a[href], " +
                ".episodelist ul li a[href], " +
                ".eplister a[href], " +
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
                val href = element.attr("abs:href").ifBlank { element.attr("href") }
                val epNum = extractEpisodeNumber(element.text(), href) ?: index + 1

                newEpisode(fixUrl(href)) {
                    name = element.text().trim().ifBlank { "Episode $epNum" }.cleanTitle()
                    episode = epNum
                    posterUrl = poster
                    duration?.let { this.runTime = it }
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
        LicenseClient.trackActivity(name, "LOAD", data)
        var html = ""
        var pageUrl = data
        var document: Document? = null

        candidateUrls(data).forEach { candidate ->
            if (document != null) return@forEach

            runCatching {
                app.get(
                    candidate,
                    headers = headers,
                    referer = mainUrl,
                    timeout = 30L
                )
            }.getOrNull()?.let { response ->
                pageUrl = candidate
                html = response.text.cleanEscaped()
                document = response.document
            }
        }

        val doc = document ?: return false
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        fun addRawLink(rawUrl: String?) {
            val raw = rawUrl?.trim()?.cleanEscaped().orEmpty()
            if (raw.isBlank()) return
            if (raw.startsWith("javascript", true) || raw.startsWith("#")) return

            val decoded = raw.decodeUrlSafe()
            val fixed = normalizePlayableUrl(decoded, pageUrl)

            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        doc.select(
            "div.player-embed iframe[src], " +
                ".player-embed iframe[src], " +
                "#pembed iframe[src], " +
                ".embed-responsive iframe[src], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src]"
        ).forEach { addRawLink(it.getIframeAttr()) }

        doc.select("select.mirror option[value]:not([disabled]), select option[value]:not([disabled]), option[value]")
            .forEach { option ->
                val value = option.attr("value").trim()
                if (value.isBlank()) return@forEach

                addRawLink(value)

                runCatching {
                    val decodedHtml = base64Decode(value.replace("\\s".toRegex(), ""))
                    Jsoup.parse(decodedHtml).select("iframe[src], source[src], video[src], a[href]")
                        .forEach { element ->
                            addRawLink(
                                element.getIframeAttr()
                                    ?: element.attr("src").ifBlank { element.attr("href") }
                            )
                        }
                }
            }

        doc.select(
            "div.dlbox li span.e a[href], " +
                ".dlbox a[href], " +
                ".soraddlx a[href], " +
                ".soraddl a[href], " +
                ".download-eps a[href], " +
                ".download a[href], " +
                ".mirror a[href], " +
                "a[href*='.m3u8'], " +
                "a[href*='.mp4'], " +
                "a[href*='hidoristream'], " +
                "a[href*='streamwish'], " +
                "a[href*='hglink'], " +
                "a[href*='hgcloud'], " +
                "a[href*='ghbrisk'], " +
                "a[href*='dhcplay'], " +
                "a[href*='4meplayer'], " +
                "a[href*='p2pplay'], " +
                "a[href*='embed4me'], " +
                "a[href*='upns'], " +
                "a[href*='veev'], " +
                "a[href*='terabox'], " +
                "a[href*='buzzheavier'], " +
                "a[href*='gofile'], " +
                "a[href*='pixeldrain'], " +
                "source[src], " +
                "video[src]"
        ).forEach { element ->
            addRawLink(
                element.attr("href")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("data-src") }
            )
        }

        extractMediaAndEmbedUrls(html).forEach { addRawLink(it) }
        extractBase64Embeds(html).forEach { decoded ->
            Jsoup.parse(decoded).select("iframe[src], source[src], video[src], a[href]")
                .forEach { element ->
                    addRawLink(
                        element.getIframeAttr()
                            ?: element.attr("src").ifBlank { element.attr("href") }
                    )
                }
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(link, pageUrl, callback)
            found = true
        }

        embedLinks
            .filterNot { it == pageUrl }
            .distinct()
            .take(24)
            .forEach { embed ->
                val success = loadExtractor(
                    embed,
                    pageUrl,
                    subtitleCallback,
                    callback
                )

                if (success) found = true
            }

        return found
    }

    private suspend fun fetchDocument(urlOrPath: String): Document {
        candidateUrls(urlOrPath).forEach { candidate ->
            runCatching {
                return app.get(
                    candidate,
                    headers = headers,
                    timeout = 30L
                ).document
            }
        }

        return app.get(urlOrPath, headers = headers, timeout = 30L).document
    }

    private fun candidateUrls(urlOrPath: String): List<String> {
        val clean = urlOrPath.trim()
        if (clean.isBlank()) return emptyList()

        if (!clean.startsWith("http", true)) {
            val path = "/" + clean.trimStart('/')
            return sourceDomains.map { it.trimEnd('/') + path }.distinct()
        }

        val current = runCatching { URI(clean) }.getOrNull() ?: return listOf(clean)
        val rawPath = current.rawPath?.ifBlank { "/" } ?: "/"
        val rawQuery = current.rawQuery?.let { "?$it" }.orEmpty()
        val pathWithQuery = rawPath + rawQuery

        return (listOf(clean) + sourceDomains.map { it.trimEnd('/') + pathWithQuery }).distinct()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".hpage a:contains(Next), " +
                "a[href*='page=${page + 1}'], " +
                "a[href*='/page/${page + 1}/']"
        ) != null
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (link.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = referer,
                headers = headers
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(link).takeIf {
                        it != Qualities.Unknown.value
                    } ?: qualityFromUrl(link)
                }
            )
        }
    }

    private fun extractMediaAndEmbedUrls(html: String): List<String> {
        val links = linkedSetOf<String>()
        val cleaned = html.cleanEscaped()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""(?:file|src|source|url|videoUrl|embed|iframe)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true) ||
                    it.contains("hidoristream", true) ||
                    it.startsWith("http", true)
            }
            .forEach { links.add(it) }

        Regex("""<(?:iframe|source|video)[^>]+(?:src|data-src|data-litespeed-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.decodeUrlSafe() }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun extractBase64Embeds(html: String): List<String> {
        val decoded = linkedSetOf<String>()

        Regex("""(?:atob|base64Decode)\(["']([A-Za-z0-9+/=]{24,})["']\)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { encoded ->
                runCatching { base64Decode(encoded) }
                    .getOrNull()
                    ?.takeIf { it.contains("iframe", true) || it.contains("source", true) }
                    ?.let { decoded.add(it) }
            }

        Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(40)
            .forEach { encoded ->
                runCatching { base64Decode(encoded) }
                    .getOrNull()
                    ?.takeIf { it.contains("iframe", true) || it.contains("source", true) }
                    ?.let { decoded.add(it) }
            }

        return decoded.toList()
    }

    private fun normalizePlayableUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped()
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = runCatching {
                    URI(baseUrl).let { "${it.scheme}://${it.host}" }
                }.getOrDefault(mainUrl)
                origin.trimEnd('/') + clean
            }
            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
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
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.decodeUrlSafe(): String {
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
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
