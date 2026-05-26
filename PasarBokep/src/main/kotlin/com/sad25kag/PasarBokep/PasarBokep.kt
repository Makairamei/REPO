package com.sad25kag.PasarBokep

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

@CloudstreamPlugin
class PasarBokepPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PasarBokep())
    }
}

class PasarBokep : MainAPI() {
    override var mainUrl = "https://pasarbokep.com"
    override var name = "PasarBokep"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "category/bokep-indo/" to "Indonesia",
        "category/bokep-korea/" to "Korea",
        "category/bokep-jepang/" to "Jepang",
        "category/jepang-uncensored/" to "Jepang Uncensored",
        "category/bokep-barat/" to "Barat / Western"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val jsonHeaders = headers + mapOf(
        "Accept" to "application/json,text/plain,*/*",
        "X-Requested-With" to "XMLHttpRequest"
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

        val items = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val clean = path.trim('/')

        return when {
            page <= 1 && clean.isBlank() -> mainUrl
            page <= 1 -> "$mainUrl/$clean/"
            clean.isBlank() -> "$mainUrl/page/$page/"
            clean.startsWith("page/") -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
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
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                ".post:has(a), " +
                ".item:has(a), " +
                ".video:has(a), " +
                ".video-item:has(a), " +
                ".content article:has(a), " +
                ".grid article:has(a), " +
                ".card:has(a), " +
                "h2:has(a), " +
                "h3:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                "a[href]:has(img), " +
                    "h2 a[href], " +
                    "h3 a[href], " +
                    "main a[href], " +
                    ".content a[href]"
            ).forEach { element ->
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
                "h2 a[href], " +
                    "h3 a[href], " +
                    ".entry-title a[href], " +
                    ".title a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = normalizeUrl(
            anchor.attr("abs:href").ifBlank { anchor.attr("href") },
            mainUrl
        )

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Download", true) &&
                !it.equals("Stream", true) &&
                !it.equals("Home", true) &&
                !it.equals("Latest videos", true) &&
                !it.equals("Random videos", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true) &&
                !it.equals("Last", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null
        if (isUnsafeTitle(title)) return null

        val poster = image?.getImageAttr()
            ?.let { normalizeUrl(it, href) }
            ?.let { fixUrlNull(it) }
            ?.takeIf { !isBadImage(it) }

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            posterUrl = poster
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "category/",
            "tag/",
            "author/",
            "page/",
            "search",
            "dmca",
            "copyright",
            "contact",
            "lapor-link-rusak",
            "wp-content",
            "wp-json",
            "wp-admin",
            "feed",
            "login",
            "register",
            "reset-password"
        )

        return blockedPrefixes.any {
            path == it.trimEnd('/') || path.startsWith(it)
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = encode(keyword)
        val attempts = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/page/$page/?search=$encoded"
        )

        var bestResults: List<SearchResponse> = emptyList()
        var hasNext = false

        for (url in attempts) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    timeout = 30L
                ).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                bestResults = results
                hasNext = hasNextPage(document, page)
                break
            }
        }

        if (bestResults.isEmpty()) {
            val apiResults = searchViaWpApi(keyword, page)
            bestResults = apiResults
            hasNext = apiResults.size >= 20
        }

        return newSearchResponseList(
            bestResults,
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    private suspend fun searchViaWpApi(query: String, page: Int): List<SearchResponse> {
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=${encode(query)}&page=$page&per_page=20&_embed=1"
        val response = runCatching {
            app.get(
                apiUrl,
                headers = jsonHeaders,
                referer = mainUrl,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull() ?: return emptyList()

        return tryParseJson<List<WpPost>>(response)
            ?.mapNotNull { it.toSearchResult() }
            ?.distinctBy { it.url }
            .orEmpty()
    }

    private fun WpPost.toSearchResult(): SearchResponse? {
        val link = link?.takeIf { it.startsWith("http", true) } ?: return null
        if (!link.startsWith(mainUrl) || isBlockedUrl(link)) return null

        val title = title?.rendered
            ?.htmlToText()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() && !isUnsafeTitle(it) }
            ?: return null

        val poster = firstImage()
            ?.let { normalizeUrl(it, link) }
            ?.takeIf { !isBadImage(it) }

        return newMovieSearchResponse(
            title,
            link,
            TvType.NSFW
        ) {
            posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(
            url,
            headers = headers,
            timeout = 30L
        )

        val document = response.document
        val wpPost = fetchWpPost(url)

        val title = wpPost?.title?.rendered
            ?.htmlToText()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1, h1.entry-title")
                ?.text()
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")
                .replace("-", " ")
                .cleanTitle()

        val poster = wpPost?.firstImage()
            ?.let { normalizeUrl(it, url) }
            ?.takeIf { !isBadImage(it) }
            ?: getPoster(document)

        val apiPlot = wpPost?.content?.rendered
            ?.htmlToText()
            ?.cleanDescription()
            ?.takeIf { it.length > 30 }

        val plot = apiPlot ?: document.selectFirst(
            ".entry-content p, " +
                ".post-content p, " +
                ".content p, " +
                "article p"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 30 }
            ?.cleanDescription()

        val tags = document.select(
            "a[href*='/category/'], " +
                "a[href*='/tag/']"
        ).map { it.text().trim() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Home", true) &&
                    !it.equals("Latest videos", true) &&
                    !isUnsafeTitle(it)
            }
            .distinct()

        val related = document.select(
            "article:has(a), " +
                ".related a[href], " +
                "h2 a[href], " +
                "h3 a[href]"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            url
        ) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            recommendations = related
            addActors(emptyList<Actor>())
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
        val baseHtml = response.text.cleanEscaped()
        val apiHtml = fetchWpPost(data)?.allRenderedHtml().orEmpty()
        val unpackedHtml = runCatching { getAndUnpack(baseHtml) }.getOrDefault(baseHtml)
        val combinedHtml = listOf(baseHtml, apiHtml, unpackedHtml)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "video[src], " +
                "video[poster], " +
                "video source[src], " +
                "source[src], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "iframe[srcdoc], " +
                "embed[src], " +
                "object[data], " +
                "button[data-src], " +
                "button[data-url], " +
                "button[data-video], " +
                "div[data-src], " +
                "div[data-url], " +
                "div[data-video], " +
                "a[href], " +
                "a[data-src], " +
                "a[data-url], " +
                "a[data-video]"
        ).forEach { element ->
            val candidates = listOf(
                element.attr("data-litespeed-src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data"),
                element.attr("src"),
                element.attr("href"),
                element.attr("onclick"),
                element.attr("srcdoc")
            ).filter { it.isNotBlank() }

            val label = element.text().lowercase()

            candidates.forEach { raw ->
                if (isBlockedCandidate(raw, label)) return@forEach

                if (
                    element.tagName().equals("video", true) ||
                    element.tagName().equals("source", true) ||
                    element.tagName().equals("iframe", true) ||
                    element.tagName().equals("embed", true) ||
                    isLikelyPlayable(raw) ||
                    isLikelyPlayableText(label)
                ) {
                    addCandidate(raw, data, directLinks, embedLinks)
                    extractPlayableUrls(raw).forEach { nestedRaw ->
                        addCandidate(nestedRaw, data, directLinks, embedLinks)
                    }
                }
            }
        }

        extractPlayableUrls(combinedHtml).forEach { raw ->
            addCandidate(raw, data, directLinks, embedLinks)
        }

        extractBase64Payloads(combinedHtml).forEach { decoded ->
            extractPlayableUrls(decoded).forEach { raw ->
                addCandidate(raw, data, directLinks, embedLinks)
            }
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(
                link = link,
                referer = data,
                callback = callback
            )
            found = true
        }

        embedLinks.distinct().forEach { embed ->
            if (embed == data || embed.startsWith(mainUrl) && isBlockedUrl(embed)) return@forEach

            val success = runCatching {
                loadExtractor(
                    embed,
                    data,
                    subtitleCallback,
                    callback
                )
            }.getOrDefault(false)

            if (success) {
                found = true
            } else {
                resolveNestedLinks(embed, data).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)
                        .cleanEscaped()
                        .replace(".txt", ".m3u8")

                    when {
                        fixed.isBlank() || isAdUrl(fixed) -> Unit

                        isDirectVideoUrl(fixed) -> {
                            emitDirectLink(
                                link = fixed,
                                referer = embed,
                                callback = callback
                            )
                            found = true
                        }

                        fixed.startsWith("http", true) && fixed != embed -> {
                            val nestedSuccess = runCatching {
                                loadExtractor(
                                    fixed,
                                    embed,
                                    subtitleCallback,
                                    callback
                                )
                            }.getOrDefault(false)

                            if (nestedSuccess) found = true
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        val text = runCatching {
            val response = app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()

            val unpacked = runCatching { getAndUnpack(response) }.getOrDefault(response)
            "$response\n$unpacked"
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        // Ekstrak URL standar dan base64
        val baseUrls = extractPlayableUrls(text) + extractBase64Payloads(text).flatMap { extractPlayableUrls(it) }

        // FIX: Otomatis parse JSON API dari custom player (seperti majorplay/jeniusplay)
        val jsonUrls = Regex("""https?://[^"'\\\s<>]+?\.json(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
            .toList()

        val jsonExtracted = jsonUrls.flatMap { jsonUrl ->
            runCatching {
                val jsonResp = app.get(jsonUrl, headers = jsonHeaders, referer = url).text.cleanEscaped()
                extractPlayableUrls(jsonResp)
            }.getOrDefault(emptyList())
        }

        return baseUrls + jsonExtracted
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val expanded = raw.cleanEscaped().decodeIfNeeded()
        val fixed = normalizeUrl(expanded, baseUrl)
            .cleanEscaped()
            .replace(".txt", ".m3u8")

        if (fixed.isBlank() || isAdUrl(fixed)) return
        if (!fixed.startsWith("http", true)) return

        when {
            isDirectVideoUrl(fixed) -> directLinks.add(fixed)
            fixed.startsWith(mainUrl) && isBlockedUrl(fixed) -> Unit
            isKnownHost(fixed) || looksLikeEmbed(fixed) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link)) return

        callback(
            newExtractorLink(
                source = name,
                name = qualityName(link),
                url = link,
                type = if (isHlsLike(link)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer
                // FIX: Penambahan Header mutlak agar terhindar dari error blokir CORS saat playback
                this.headers = mapOf(
                    "Origin" to "https://${hostOf(referer).ifBlank { hostOf(mainUrl) }}",
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                )
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        listOf(
            Regex(
                """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://[^"'\\\s<>]+?googlevideo\.com/videoplayback[^"'\\\s<>]*""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://[^"'\\\s<>]+?(?:/manifest/hls_playlist|/api/manifest/)[^"'\\\s<>]*""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """https?://[^"'\\\s<>]+?(?:embed|player|stream|filemoon|filelions|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|luluvdo|lulu|hglink|hgcloud|majorplay|jeniusplay|pornhub|xvideos|xhamster|redtube|spankbang|uqload|sendvid|vidmoly)[^"'\\\s<>]*""",
                RegexOption.IGNORE_CASE
            )
        ).forEach { regex ->
            regex.findAll(clean)
                .map { it.value.cleanEscaped().decodeIfNeeded().replace(".txt", ".m3u8") }
                .filterNot { isAdUrl(it) }
                .forEach { urls.add(it) }
        }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|googlevideo\.com%2Fvideoplayback)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.decodeIfNeeded() }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|video|videoSource|videoUrl|video_url|download|downloadUrl|download_url|embed|embedUrl|embed_url|iframe|hls|m3u8|contentUrl)\s*[=:]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().decodeIfNeeded().replace(".txt", ".m3u8") }
            .filter {
                isDirectVideoUrl(it) ||
                    isKnownHost(it) ||
                    looksLikeEmbed(it)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:atob|decodeURIComponent)\(["']([^"']+)["']\)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.decodeIfNeeded() }
            .filter {
                isDirectVideoUrl(it) ||
                    isKnownHost(it) ||
                    looksLikeEmbed(it)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun extractBase64Payloads(text: String): List<String> {
        val values = linkedSetOf<String>()

        Regex(
            """(?:atob|Base64\.decode|base64_decode)\(["']([A-Za-z0-9+/=_-]{16,})["']\)""",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { encoded ->
                decodeBase64(encoded)?.let { values.add(it.cleanEscaped()) }
            }

        Regex(
            """["']([A-Za-z0-9+/=_-]{40,})["']""",
            RegexOption.IGNORE_CASE
        ).findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { encoded ->
                val decoded = decodeBase64(encoded) ?: return@forEach
                if (decoded.contains("http", true) || decoded.contains("m3u8", true)) {
                    values.add(decoded.cleanEscaped())
                }
            }

        return values.toList()
    }

    private fun decodeBase64(value: String): String? {
        return runCatching {
            val fixed = value.replace('-', '+').replace('_', '/')
            String(Base64.getDecoder().decode(fixed), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun isBlockedCandidate(raw: String, label: String): Boolean {
        return raw.startsWith("#") ||
            raw.startsWith("javascript", true) ||
            raw.contains("facebook.com", true) ||
            raw.contains("twitter.com", true) ||
            raw.contains("telegram", true) ||
            raw.contains("whatsapp", true) ||
            raw.contains("mailto:", true) ||
            label.contains("lapor") ||
            label.contains("contact") ||
            label.contains("dmca")
    }

    private fun isKnownHost(url: String): Boolean {
        val host = hostOf(url)
        val value = url.lowercase()

        return listOf(
            "filemoon",
            "filelions",
            "streamwish",
            "wishfast",
            "dood",
            "streamtape",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "mp4upload",
            "lulustream",
            "luluvdo",
            "hglink",
            "hgcloud",
            "majorplay",
            "jeniusplay",
            "uqload",
            "sendvid",
            "vidmoly",
            "pornhub",
            "xvideos",
            "xhamster",
            "redtube",
            "spankbang"
        ).any { host.contains(it) } || value.contains("/embed/") || value.contains("/player/")
    }

    private fun looksLikeEmbed(url: String): Boolean {
        val value = url.lowercase()
        val host = hostOf(url)

        if (!url.startsWith("http", true)) return false
        if (host.contains("googlevideo.com")) return false
        if (isDirectVideoUrl(url)) return false

        return value.contains("/embed") ||
            value.contains("/player") ||
            value.contains("/stream") ||
            value.contains("?id=") ||
            value.contains("?v=") ||
            value.contains("/e/")
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return isDirectVideoUrl(url) ||
            isKnownHost(url) ||
            looksLikeEmbed(url) ||
            url.contains("googlevideo.com", true) ||
            url.contains("videoplayback", true)
    }

    private fun isLikelyPlayableText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("watch") ||
            text.contains("server") ||
            text.contains("play") ||
            text.contains("mp4") ||
            text.contains("m3u8") ||
            text.contains("720p") ||
            text.contains("1080p")
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        val value = url.lowercase().substringBefore("?")
        
        return isHlsLike(url) ||
            value.endsWith(".mp4") ||
            value.endsWith(".webm") ||
            value.endsWith(".mkv") ||
            (url.contains("googlevideo.com", ignoreCase = true) && url.contains("videoplayback", ignoreCase = true))
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped().decodeIfNeeded()

        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: mainUrl
                "$origin$clean"
            }

            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
    }

    private suspend fun fetchWpPost(url: String): WpPost? {
        val slug = url.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?slug=${encode(slug)}&_embed=1"

        val text = runCatching {
            app.get(
                apiUrl,
                headers = jsonHeaders,
                referer = mainUrl,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull() ?: return null

        return tryParseJson<List<WpPost>>(text)?.firstOrNull()
    }

    private fun WpPost.firstImage(): String? {
        return jetpackFeaturedMediaUrl?.takeIf { it.isNotBlank() }
            ?: embedded?.featuredMedia?.firstOrNull()?.sourceUrl?.takeIf { it.isNotBlank() }
            ?: content?.rendered?.let { html ->
                Jsoup.parse(html).selectFirst("img[src], img[data-src], img[data-lazy-src]")?.getImageAttr()
            }
    }

    private fun WpPost.allRenderedHtml(): String {
        return listOfNotNull(
            title?.rendered,
            content?.rendered,
            excerpt?.rendered
        ).joinToString("\n").cleanEscaped()
    }

    private fun getPoster(document: Document): String? {
        return document.selectFirst(
            "meta[property=og:image], " +
                "meta[name=twitter:image], " +
                "video[poster], " +
                ".poster img, " +
                ".thumb img, " +
                "article img, " +
                "img"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                element.hasAttr("poster") -> element.attr("poster")
                else -> element.getImageAttr()
            }
        }?.let { normalizeUrl(it, mainUrl) }
            ?.let { fixUrlNull(it) }
            ?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() &&
                        !isBadImage(it)
                }
        }

        val raw = fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:data-full").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw
            ?.trim()
            ?.takeIf { !isBadImage(it) }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("data:image") ||
            value.contains("blank") ||
            value.contains("placeholder") ||
            value.contains("default") ||
            value.contains("no-image") ||
            value.contains("noimage") ||
            value.contains("loader") ||
            value.contains("loading") ||
            value.contains("lazy") ||
            value.contains("spacer") ||
            value.contains("logo") ||
            value.contains("favicon") ||
            value.contains("banner") ||
            value.endsWith(".svg")
    }

    private fun isUnsafeTitle(text: String): Boolean {
        val value = text.lowercase()

        return value.contains("pemerkosaan") ||
            value.contains("diperkosa") ||
            value.contains("rape") ||
            value.contains("forced") ||
            value.contains("sedarah") ||
            value.contains("incest") ||
            value.contains("anak kecil") ||
            value.contains("dibawah umur") ||
            value.contains("di bawah umur") ||
            value.contains("underage") ||
            value.contains("minor") ||
            value.contains("anak smp") ||
            value.contains("smp ") ||
            value.contains(" smp")
    }

    private fun isHlsLike(url: String): Boolean {
        // Abaikan parameter query untuk mengecek ektensi asli
        val value = url.lowercase().substringBefore("?")
        
        return value.endsWith(".m3u8") ||
            value.endsWith(".m3u") ||
            url.contains("/manifest/hls_playlist", ignoreCase = true) ||
            url.contains("m3u8", ignoreCase = true)
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adserver") ||
            value.contains("/ads/") ||
            value.contains("banner") ||
            value.contains("report-content") ||
            value.contains("popads")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) -> Qualities.P480.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityName(url: String): String {
        val quality = when {
            url.contains("2160", true) || url.contains("4k", true) -> "4K"
            url.contains("1080", true) -> "1080p"
            url.contains("720", true) -> "720p"
            url.contains("540", true) -> "540p"
            url.contains("480", true) -> "480p"
            url.contains("360", true) -> "360p"
            isHlsLike(url) -> "HLS"
            else -> "Direct"
        }

        return "$name - $quality"
    }

    private fun hostOf(url: String): String {
        return runCatching {
            URI(url).host.orEmpty().lowercase()
        }.getOrDefault("")
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun String.decodeIfNeeded(): String {
        val value = this.trim()
        if (!value.contains("%")) return value

        return runCatching {
            URLDecoder.decode(value, "UTF-8")
        }.getOrDefault(value)
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#039;", "'")
            .replace("\\\"", "\"")
            .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            .trim()
    }

    private fun String.htmlToText(): String {
        return Jsoup.parse(this.cleanEscaped()).text().cleanEscaped()
    }

    private fun String.cleanTitle(): String {
        return this
            .htmlToText()
            .replace(Regex("""\s+\|\s+Pasarbokep.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Pasarbokep.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanDescription(): String {
        return this
            .htmlToText()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpPost(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("title") val title: WpRendered? = null,
        @JsonProperty("content") val content: WpRendered? = null,
        @JsonProperty("excerpt") val excerpt: WpRendered? = null,
        @JsonProperty("jetpack_featured_media_url") val jetpackFeaturedMediaUrl: String? = null,
        @JsonProperty("_embedded") val embedded: WpEmbedded? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpRendered(
        @JsonProperty("rendered") val rendered: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpEmbedded(
        @JsonProperty("wp:featuredmedia") val featuredMedia: List<WpMedia>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class WpMedia(
        @JsonProperty("source_url") val sourceUrl: String? = null
    )
}
