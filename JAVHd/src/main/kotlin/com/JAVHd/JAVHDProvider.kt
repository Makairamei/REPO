package com.JAVHd

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class JAVHDProvider : MainAPI() {
    override var mainUrl = "https://javhd.today"
    override var name = "JAV HD"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
        "/releaseday/" to "Release Day",
        "/recent/" to "Latest Update",
        "/popular/today/" to "Most View Today",
        "/popular/week/" to "Most View Week",
        "/popular/month/" to "Most View Month",
        "/popular/year/" to "Most View Year",
        "/rated/" to "Top Rated",
        "/downloaded/" to "Most Downloaded",
        "/longest/" to "Longest",
        "/watched/" to "Watched",

        "$mainUrl/creampie/recent/%d/?ajax=1" to "Creampie",
        "$mainUrl/big-tits/recent/%d/?ajax=1" to "Big Tits",
        "$mainUrl/married-woman/recent/%d/?ajax=1" to "Married Woman",
        "$mainUrl/beautiful-girl/recent/%d/?ajax=1" to "Beautiful Girl",
        "$mainUrl/mature-woman/recent/%d/?ajax=1" to "Mature Woman",
        "$mainUrl/cuckold/recent/%d/?ajax=1" to "Cuckold",
        "$mainUrl/squirting/recent/%d/?ajax=1" to "Squirting",
        "$mainUrl/hardcore/recent/%d/?ajax=1" to "Hardcore",
        "$mainUrl/cosplay/recent/%d/?ajax=1" to "Cosplay",
        "$mainUrl/massage/recent/%d/?ajax=1" to "Massage",

        "$mainUrl/jav-sub/recent/%d/?ajax=1" to "Jav Sub",
        "$mainUrl/jav-sub/popular/year/%d/?ajax=1" to "Jav Sub Popular",
        "$mainUrl/jav-sub/rated/%d/?ajax=1" to "Jav Sub Top Rated",
        "$mainUrl/jav-sub/watched/%d/?ajax=1" to "Jav Sub Watched",
        "$mainUrl/chinese-sub/recent/%d/?ajax=1" to "Chinese Sub",

        "$mainUrl/uncensored-jav/recent/%d/?ajax=1" to "Uncensored",
        "$mainUrl/uncensored-jav/popular/year/%d/?ajax=1" to "Uncensored Popular",
        "$mainUrl/reducing-mosaic/recent/%d/?ajax=1" to "Reducing Mosaic",
        "$mainUrl/reducing-mosaic/popular/year/%d/?ajax=1" to "Reducing Mosaic Popular",
        "$mainUrl/amateur/recent/%d/?ajax=1" to "Amateur",
        "$mainUrl/amateur/popular/year/%d/?ajax=1" to "Amateur Popular"
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
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = getPageDocument(request.data, page)
        val responseList = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = responseList,
                isHorizontalImages = false
            ),
            hasNext = responseList.isNotEmpty() || hasNextPage(document, page)
        )
    }

    private suspend fun getPageDocument(
        data: String,
        page: Int
    ): Document {
        val url = buildPageUrl(data, page)
        val responseText = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        ).text

        if (url.contains("ajax=1", ignoreCase = true)) {
            val html = runCatching {
                JSONObject(responseText).optString("html")
            }.getOrDefault(responseText)

            return Jsoup.parse(html, mainUrl)
        }

        return Jsoup.parse(responseText, mainUrl)
    }

    private fun buildPageUrl(
        data: String,
        page: Int
    ): String {
        val clean = data.trim()

        return when {
            clean.contains("%d") -> clean.format(page + 1)
            clean.startsWith("http", ignoreCase = true) -> clean
            page <= 1 -> mainUrl.trimEnd('/') + "/" + clean.trimStart('/')
            clean.isBlank() -> "$mainUrl/$page"
            else -> mainUrl.trimEnd('/') + "/" + clean.trim('/').trimEnd('/') + "/$page"
        }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                "a[href$='/${page + 1}'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "div.video, " +
                ".video, " +
                "article:has(a.thumbnail), " +
                "article:has(.video-thumb), " +
                "li:has(a.thumbnail)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
                element.toSearchResult()?.let { item ->
                    results[item.url] = item
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a.thumbnail[href], " +
                    ".thumbnail[href], " +
                    ".video-title a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixLocalUrl(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst(".video-thumb img, img") ?: anchor.selectFirst("img")
        val posterUrl = image?.getImageUrl()

        val title = listOf(
            selectFirst(".video-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl, url).trim('/').lowercase()

        return path.isBlank() || listOf(
            "login",
            "signup",
            "user",
            "users",
            "photo",
            "photos",
            "pornstar",
            "pornstars",
            "playlist",
            "playlists",
            "request",
            "news",
            "contact",
            "dmca",
            "privacy",
            "terms",
            "language",
            "download/"
        ).any { path == it.trim('/') || path.startsWith(it) }
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
        val url = "$mainUrl/search/video/?s=$encoded&page=$page&ajax=1"
        val responseText = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        ).text

        val html = runCatching {
            JSONObject(responseText).optString("html")
        }.getOrDefault(responseText)

        val document = Jsoup.parse(html, mainUrl)
        val results = parseCards(document).distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = results.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        ).document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            url.substringAfterLast('/').replace('-', ' ')
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: name

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".video-thumb img, video[poster], img")?.getImageUrl()
        )

        val description = listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst(".video-description, .description, .content")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val tags = document.select("a[href*='/genre/'], a[href*='/tag/'], .tags a, .video-tags a, a[href*='/studio/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val pageUrl = fixLocalUrl(data) ?: data
        val response = app.get(
            pageUrl,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        )

        val document = response.document
        val html = response.text.cleanEscaped()
        val embeds = linkedSetOf<String>()
        val directLinks = linkedSetOf<String>()

        document.select(
            ".button_style .button_choice_server[data-embed], " +
                ".button_choice_server[data-embed], " +
                "[data-embed], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], video[poster], source[src], a[href]"
        ).forEach { element ->
            val raw = element.attr("data-embed")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach

            val decoded = decodeMaybeBase64(raw)
            addPlayableCandidate(decoded, pageUrl, directLinks, embeds)
        }

        extractPlayableUrls(html).forEach { raw ->
            addPlayableCandidate(raw, pageUrl, directLinks, embeds)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addPlayableCandidate(raw, pageUrl, directLinks, embeds)
            }
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(link, pageUrl, callback)
            found = true
        }

        embeds.distinct().take(8).forEach { embed ->
            val success = runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback, callback)
            }.getOrDefault(false)

            if (success) found = true
        }

        getExternalSubtitle(document, subtitleCallback)
        return found
    }

    private fun addPlayableCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isSkippedPlayerUrl(fixed)) return

        when {
            fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) && isKnownPlayerHost(fixed) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                    ?: qualityFromUrl(link)
            }
        )
    }

    private suspend fun getExternalSubtitle(
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        runCatching {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            val javCode = Regex("([a-zA-Z]+-\\d+)").find(title)?.groups?.get(1)?.value ?: return@runCatching
            val query = "$subtitleCatUrl/index.php?search=${URLEncoder.encode(javCode, "UTF-8")}"
            val subDoc = app.get(query, timeout = 15L).document

            subDoc.select("td a").forEach { item ->
                if (!item.text().contains(javCode, ignoreCase = true)) return@forEach

                val fullUrl = "$subtitleCatUrl/${item.attr("href").trimStart('/')}"
                val pageDoc = app.get(fullUrl, timeout = 10L).document

                pageDoc.select(".col-md-6.col-lg-4").forEach { subItem ->
                    val language = subItem.select(".sub-single span:nth-child(2)").text()
                        .replace("\uD83D\uDC4D \uD83D\uDC4E", "")
                        .trim()
                        .ifBlank { "Subtitle" }

                    val download = subItem.selectFirst(".sub-single span:nth-child(3) a:contains(Download)")
                        ?: return@forEach

                    val url = "$subtitleCatUrl${download.attr("href")}" 
                    subtitleCallback(newSubtitleFile(language, url))
                }
            }
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:streamtape|stbturbo|turbovid|mycloudz|cloudwish|streamwish|wishfast|filemoon|vidhide|voe|dood|mixdrop|mp4upload|embed|player)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|hls|embedUrl)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) || isKnownPlayerHost(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun decodeMaybeBase64(value: String): String {
        val clean = value.trim()
        if (clean.startsWith("http", true) || clean.startsWith("//") || clean.startsWith("/")) return clean

        return runCatching { base64Decode(clean) }.getOrDefault(clean)
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.trim()

        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                "$origin$clean"
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun fixLocalUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return fixUrlNull(normalizeUrl(value, mainUrl))
    }

    private fun Element.getImageUrl(): String? {
        val raw = attr("abs:data-src").ifBlank { attr("abs:data-lazy-src") }
            .ifBlank { attr("abs:data-original") }
            .ifBlank { attr("abs:src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("src") }

        return fixUrlNull(raw)?.takeIf { !it.contains("blank", true) && !it.contains("placeholder", true) }
    }

    private fun isKnownPlayerHost(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "streamtape",
            "stbturbo",
            "turbovid",
            "mycloudz",
            "cloudwish",
            "streamwish",
            "wishfast",
            "filemoon",
            "vidhide",
            "voe",
            "dood",
            "mixdrop",
            "mp4upload",
            "embed",
            "player"
        ).any { value.contains(it) }
    }

    private fun isSkippedPlayerUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") || value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("ads") ||
            value.contains("banner") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") || value.contains("mailto:")
    }

    private fun isBadTitle(value: String): Boolean {
        val text = value.trim().lowercase()
        return text.isBlank() || text == "home" ||
            text == "login" ||
            text == "signup" ||
            text == "next" ||
            text == "previous" || text == "download" ||
            text == "watch" ||
            text.contains("theporndude")
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

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+Javhd.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
