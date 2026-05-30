package com.sad25kag.Indo18

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random

@CloudstreamPlugin
class Indo18Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Indo18())
    }
}

class Indo18 : MainAPI() {
    override var mainUrl = "https://www.indo18.com"
    override var name = "Indo18"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Newest",
        "?filter=latest" to "Latest",
        "?filter=popular" to "Best",
        "?filter=most-viewed" to "Most Viewed",
        "?filter=longest" to "Longest",
        "?filter=random" to "Random",
        "category/artis/" to "Artis",
        "category/babes/" to "Babes",
        "category/bang/" to "Bang",
        "category/bang-pov/" to "Bang POV",
        "category/bispak/" to "Bispak",
        "category/cam/" to "CAM",
        "category/janda/" to "Janda",
        "category/jilbab/" to "Jilbab",
        "category/live-show/" to "Live Show",
        "category/mahasiswi/" to "Mahasiswi",
        "category/masturbasi/" to "Masturbasi",
        "category/ngintip/" to "Ngintip",
        "category/pembantu/" to "Pembantu",
        "category/pns/" to "PNS",
        "category/scandal/" to "Scandal",
        "category/tante/" to "Tante",
        "category/video-indonesia/" to "Video Indonesia"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, referer = mainUrl, timeout = 25L).document
        val items = parseCards(document).distinctBy { canonicalUrl(it.url) }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(document, page))
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim()
        return when {
            page <= 1 && clean.isBlank() -> mainUrl
            page <= 1 && clean.startsWith("?") -> "$mainUrl/$clean"
            page <= 1 -> "$mainUrl/${clean.trim('/')}"
            clean.isBlank() -> "$mainUrl/page/$page"
            clean.startsWith("?") -> "$mainUrl/page/$page$clean"
            clean.contains("?") -> {
                val base = clean.substringBefore("?").trim('/')
                val query = clean.substringAfter("?")
                "$mainUrl/$base/page/$page?$query"
            }
            else -> "$mainUrl/${clean.trim('/')}/page/$page"
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(Next), .page-numbers.next, " +
                "a[href*='/page/${page + 1}'], a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(
            "article:has(a), .post:has(a), .item:has(a), .video:has(a), .video-item:has(a), " +
                ".content article:has(a), .grid article:has(a), .card:has(a), h2:has(a), h3:has(a), main a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { item -> results[canonicalUrl(item.url)] = item }
        }
        if (results.isEmpty()) {
            document.select("a[href]:has(img), h2 a[href], h3 a[href], main a[href], .content a[href]")
                .forEach { element -> element.toSearchResult()?.let { item -> results[canonicalUrl(item.url)] = item } }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst(
            "h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]:has(img), a[href]"
        ) ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
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
        ).firstOrNull { isUsableTitle(it) }?.cleanTitle() ?: return null
        if (title.length < 2 || isUnsafeTitle(title)) return null
        val poster = fixUrl(image?.getImageAttr(), mainUrl)?.takeIf { !isBadImage(it) }
        val score = Regex("""(\d{1,3})%""").find(text())?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.div(10.0)?.toString()
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
            this.score = Score.from10(score)
        }
    }

    private fun isUsableTitle(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return false
        return !listOf(
            "Newest", "Best", "Most viewed", "Longest", "Random", "Download complete video now!",
            "Share", "Home", "Categories", "Actors", "Tags", "Next", "Last"
        ).any { text.equals(it, true) }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()
        if (path.isBlank()) return true
        val blockedPrefixes = listOf(
            "category/", "categories", "actors", "actor/", "tags", "tag/", "page/", "search",
            "content-removal", "privacy", "dmca", "contact", "wp-content", "wp-json", "wp-admin",
            "feed", "login", "register", "reset-password"
        )
        return blockedPrefixes.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/page/$page?search=$encoded"
        )
        for (url in attempts) {
            val document = runCatching { app.get(url, headers = headers, referer = mainUrl, timeout = 25L).document }.getOrNull() ?: continue
            val results = parseCards(document).distinctBy { canonicalUrl(it.url) }
            if (results.isNotEmpty()) return newSearchResponseList(results, hasNext = hasNextPage(document, page))
        }
        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items
    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url, mainUrl) ?: url
        val document = app.get(fixedUrl, headers = headers, referer = mainUrl, timeout = 25L).document
        val title = document.selectFirst("h1, h1.entry-title")?.text()?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: fixedUrl.substringAfterLast("/").replace("-", " ").cleanTitle()
        val poster = getPoster(document)
        val text = document.text()
        val views = Regex("""(?i)(?:views?|ditonton)\s*:?\s*([\d.,kmb]+)""").find(text)?.groupValues?.getOrNull(1)
        val rating = Regex("""(\d{1,3})%""").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.div(10.0)?.toString()
        val tags = document.select("a[href*='/category/'], a[href*='/tag/']").map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Home", true) && !it.equals("Categories", true) && !isUnsafeTitle(it) }
            .distinct()
        val related = document.select("article:has(a), .related a[href], h2 a[href], h3 a[href], main a[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { canonicalUrl(it.url) }
            .filter { canonicalUrl(it.url) != canonicalUrl(fixedUrl) }
        return newMovieLoadResponse(title, fixedUrl, TvType.NSFW, fixedUrl) {
            posterUrl = poster
            plot = views?.let { "Views: $it" }
            this.tags = tags
            this.score = Score.from10(rating)
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
        val startUrl = fixUrl(data, mainUrl) ?: return false
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer)?.replace(".txt", ".m3u8") ?: return false
            if (isAdUrl(fixed) || !isPlayableMedia(fixed)) return false
            val key = canonicalUrl(fixed)
            if (!emitted.add(key)) return false
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixed,
                    type = if (isHlsLike(fixed)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(fixed)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
            return true
        }

        suspend fun tryExtractor(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer) ?: return false
            if (isAdUrl(fixed) || shouldSkipUrl(fixed)) return false
            var localFound = false
            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) { extractorLink ->
                    val key = canonicalUrl(extractorLink.url)
                    if (emitted.add(key)) {
                        localFound = true
                        callback(extractorLink)
                    }
                }
            }
            return localFound
        }

        suspend fun collectFromPage(url: String, referer: String): List<String> {
            val response = runCatching {
                app.get(url, headers = headers + mapOf("Origin" to mainUrl), referer = referer, timeout = 20L)
            }.getOrNull() ?: return emptyList()
            val document = response.document
            val html = response.text.cleanEscaped()
            val links = linkedSetOf<String>()

            collectElementLinks(document, url).forEach { links.add(it) }
            extractPlayableUrls(html, url).forEach { links.add(it) }
            extractDoodstreamLinks(html, url).forEach { links.add(it) }
            extractPackedLinks(html, url).forEach { links.add(it) }
            extractEncodedLinks(html, url).forEach { links.add(it) }
            extractDownloadButtons(document, url).forEach { links.add(it) }
            return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
        }

        val visitedPages = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        candidates.add(startUrl)
        collectFromPage(startUrl, mainUrl).forEach { candidates.add(it) }

        candidates.toList().forEach { candidate ->
            when {
                emitDirect(candidate, startUrl) -> found = true
                tryDoodstream(candidate, startUrl, callback) -> found = true
                tryExtractor(candidate, startUrl) -> found = true
            }
        }
        if (found) return true

        prioritizeEmbeds(candidates)
            .filter { visitedPages.add(canonicalUrl(it)) }
            .take(18)
            .forEach { embed ->
                if (tryExtractor(embed, startUrl)) {
                    found = true
                    return@forEach
                }
                collectFromPage(embed, startUrl).forEach { nested ->
                    when {
                        emitDirect(nested, embed) -> found = true
                        tryDoodstream(nested, embed, callback) -> found = true
                        tryExtractor(nested, embed) -> found = true
                    }
                    if (found) return@forEach
                }
            }

        return found
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val results = linkedSetOf<String>()
        document.select(
            "meta[itemprop=embedURL], meta[property=og:video], meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], video[src], video[data-src], video[data-video], " +
                "video source[src], source[src], source[data-src], iframe[src], iframe[data-src], " +
                "iframe[data-litespeed-src], iframe[data-lazy-src], iframe[data-original], embed[src], object[data], " +
                "a[href], button[data-url], button[data-video], button[data-file], [data-src], [data-video], " +
                "[data-file], [data-url], [data-embed], [data-iframe], [onclick]"
        ).forEach { element ->
            val values = listOf(
                element.attr("content"),
                element.attr("data-litespeed-src"),
                element.attr("data-lazy-src"),
                element.attr("data-original"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-url"),
                element.attr("data-embed"),
                element.attr("data-iframe"),
                element.attr("data-src"),
                element.attr("data"),
                element.attr("src"),
                element.attr("href"),
                element.attr("onclick")
            )
            values.filter { it.isNotBlank() }.forEach { raw ->
                extractPlayableUrls(raw, baseUrl).forEach { results.add(it) }
                extractDoodstreamLinks(raw, baseUrl).forEach { results.add(it) }
                fixUrl(raw, baseUrl)?.let { fixed ->
                    if (isLikelyPlayable(fixed) || isKnownHost(fixed)) results.add(fixed)
                }
            }
        }
        return results.toList()
    }

    private fun extractDownloadButtons(document: Document, baseUrl: String): List<String> {
        val results = linkedSetOf<String>()
        document.select("a[href], button, [onclick]").forEach { element ->
            val text = element.text().lowercase()
            val raw = element.attr("href").ifBlank { element.attr("data-url") }.ifBlank { element.attr("onclick") }
            if (raw.isBlank()) return@forEach
            val looksLikeSource = text.contains("download") || text.contains("server") || text.contains("watch") || text.contains("play") || isKnownHost(raw)
            if (!looksLikeSource) return@forEach
            extractPlayableUrls(raw, baseUrl).forEach { results.add(it) }
            extractDoodstreamLinks(raw, baseUrl).forEach { results.add(it) }
            fixUrl(raw, baseUrl)?.let { results.add(it) }
        }
        return results.toList()
    }

    private fun extractPackedLinks(html: String, baseUrl: String): List<String> {
        val unpacked = runCatching { if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null }.getOrNull()
        return unpacked?.let { unpackedHtml ->
            (extractPlayableUrls(unpackedHtml.cleanEscaped(), baseUrl) + extractDoodstreamLinks(unpackedHtml.cleanEscaped(), baseUrl)).distinct()
        }.orEmpty()
    }

    private fun extractEncodedLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val clean = html.cleanEscaped()
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.takeIf { it != clean }?.let {
            extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractDoodstreamLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
        }
        Regex("""(?i)atob\(['\"]([^'\"]+)['\"]\)""").findAll(clean).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach {
            extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractDoodstreamLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
        }
        Regex("""(?i)(?:base64|data|hash)\s*[:=]\s*['\"]([A-Za-z0-9+/=_-]{20,})['\"]""").findAll(clean).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach {
            extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
            extractDoodstreamLinks(it.cleanEscaped(), baseUrl).forEach { link -> links.add(link) }
        }
        return links.toList()
    }

    private fun extractDoodstreamLinks(text: String, baseUrl: String = mainUrl): List<String> {
        val links = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        val source = if (decoded != clean) "$clean $decoded" else clean
        Regex(
            """(?i)(?:https?:)?//(?:www\.)?(?:doodstream\.(?:com|co|link)|dood\.(?:watch|to|so|la|pm|re|ws|sh|wf|cx|yt|li|one|video)|d0000d\.com|dooood\.com|do0od\.com|doods\.(?:pro|to)|doodcdn\.co|ds2play\.com)/(?:e|d|embed)/[A-Za-z0-9_-]+(?:[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(source).map { it.value }.forEach { raw ->
            normalizeDoodstreamUrl(raw, baseUrl)?.let { links.add(it) }
        }
        Regex(
            """(?i)(?:doodstream\.(?:com|co|link)|dood\.(?:watch|to|so|la|pm|re|ws|sh|wf|cx|yt|li|one|video)|d0000d\.com|dooood\.com|do0od\.com|doods\.(?:pro|to)|doodcdn\.co|ds2play\.com)[^A-Za-z0-9_-]+(?:e|d|embed)[^A-Za-z0-9_-]+([A-Za-z0-9_-]{6,})""",
            RegexOption.IGNORE_CASE
        ).findAll(source).forEach { match ->
            val host = match.value.substringBefore(match.groupValues[1]).replace(Regex("""[^A-Za-z0-9.:-]+$"""), "").substringAfterLast("//", match.value.substringBefore(match.groupValues[1]).substringBefore("/"))
            val id = match.groupValues[1]
            normalizeDoodstreamUrl("https://$host/e/$id", baseUrl)?.let { links.add(it) }
        }
        Regex(
            """(?i)(?:src|href|data-src|data-url|data-video|data-file|file|url)\s*[:=]\s*["']([^"']*(?:doodstream\.(?:com|co|link)|dood\.|d0000d\.com|dooood\.com|do0od\.com|doods\.|doodcdn\.co|ds2play\.com)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(source).mapNotNull { it.groupValues.getOrNull(1) }.forEach { raw ->
            fixUrl(raw, baseUrl)?.let { links.add(it) }
        }
        return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
    }

    private fun extractPlayableUrls(text: String, baseUrl: String = mainUrl): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { it.value }.forEach { fixUrl(it.replace(".txt", ".m3u8"), baseUrl)?.let(urls::add) }
        Regex("""//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { "https:${it.value}" }.forEach { fixUrl(it.replace(".txt", ".m3u8"), baseUrl)?.let(urls::add) }
        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .forEach { fixUrl(it.replace(".txt", ".m3u8"), baseUrl)?.let(urls::add) }
        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url|contentUrl|data-file|data-video|data-url|data-src|data-embed|data-iframe|content)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).mapNotNull { it.groupValues.getOrNull(1) }.forEach { raw ->
            val fixed = fixUrl(raw.replace(".txt", ".m3u8"), baseUrl)
            if (fixed != null && (isLikelyPlayable(fixed) || isKnownHost(fixed))) urls.add(fixed)
        }
        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|jomblo|playmogo|filemoon|streamwish|wishfast|doodstream|dood\.|d0000d|dooood|do0od|doods|doodcdn|ds2play|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|luluvdoo|lulu|hglink|hgcloud|majorplay|jeniusplay|pornhub|xvideos|xhamster|redtube|spankbang)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).map { it.value }.forEach { fixUrl(it, baseUrl)?.let(urls::add) }
        return urls.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.distinct()
    }

    private suspend fun tryDoodstream(link: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val doodUrl = normalizeDoodstreamUrl(link, referer) ?: return false
        if (!isDoodstreamHost(doodUrl)) return false

        if (tryExtractor(doodUrl, referer)) return true

        val response = runCatching {
            app.get(
                doodUrl,
                headers = headers + mapOf(
                    "Referer" to referer,
                    "Origin" to origin(doodUrl)
                ),
                referer = referer,
                timeout = 20L
            )
        }.getOrNull() ?: return false

        val html = response.text.cleanEscaped()
        extractPlayableUrls(html, doodUrl).forEach { media ->
            if (media.contains(".m3u8", true) || media.contains(".mp4", true) || media.contains(".webm", true)) {
                return emitDirect(media, doodUrl)
            }
        }

        val passPath = Regex("""(?i)(/pass_md5/[^'"\\\s<>]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)pass_md5['"]?\s*[:=]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)
                ?.let { if (it.startsWith("/")) it else "/pass_md5/$it" }
            ?: return false

        val doodOrigin = origin(doodUrl)
        val token = passPath.substringAfterLast("/").substringBefore("?")
        val directSeed = runCatching {
            app.get(
                doodOrigin + passPath,
                headers = headers + mapOf(
                    "Referer" to doodUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = doodUrl,
                timeout = 20L
            ).text.cleanEscaped().trim()
        }.getOrNull()?.takeIf { it.startsWith("http", true) } ?: return false

        val finalUrl = if (directSeed.contains("token=", true)) {
            directSeed
        } else {
            directSeed + randomAlphaNum(10) + "?token=$token&expiry=${System.currentTimeMillis()}"
        }
        callback(
            newExtractorLink(
                source = "$name Doodstream",
                name = "$name Doodstream",
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = doodUrl
                this.quality = qualityFromUrl(finalUrl)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to doodUrl,
                    "Origin" to doodOrigin,
                    "Accept" to "*/*"
                )
            }
        )
        return true
    }

    private fun normalizeDoodstreamUrl(raw: String, baseUrl: String): String? {
        val fixed = fixUrl(raw, baseUrl) ?: return null
        if (!isDoodstreamHost(fixed)) return null
        return fixed
            .replace("/d/", "/e/", ignoreCase = true)
            .replace("/embed/", "/e/", ignoreCase = true)
            .substringBefore("&autoplay")
            .substringBefore("?download")
    }

    private fun randomAlphaNum(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links.filterNot { isAdUrl(it) || shouldSkipUrl(it) }.filter { isKnownHost(it) || it.startsWith("http", true) }
            .distinctBy { canonicalUrl(it) }
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            isDoodstreamHost(value) -> 0
            value.contains("jomblo.org") -> 1
            value.contains("playmogo.com") -> 2
            value.contains("majorplay") -> 3
            value.contains("jeniusplay") -> 4
            value.contains("hglink") -> 4
            value.contains("hgcloud") -> 5
            value.contains("lulustream") || value.contains("luluvdoo") || value.contains("lulu") -> 6
            value.contains("streamwish") || value.contains("wishfast") -> 7
            value.contains("filemoon") -> 8
            value.contains("vidhide") -> 9
            value.contains("vidguard") -> 10
            value.contains("voe") -> 11
            value.contains("mixdrop") -> 12
            value.contains("mp4upload") -> 13
            value.contains("streamtape") -> 14
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
    }

    private fun isDoodstreamHost(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doodstream.com") ||
            value.contains("doodstream.co") ||
            value.contains("doodstream.link") ||
            value.contains("dood.watch") ||
            value.contains("dood.to") ||
            value.contains("dood.so") ||
            value.contains("dood.la") ||
            value.contains("dood.pm") ||
            value.contains("dood.re") ||
            value.contains("dood.ws") ||
            value.contains("dood.sh") ||
            value.contains("dood.wf") ||
            value.contains("dood.cx") ||
            value.contains("dood.yt") ||
            value.contains("dood.li") ||
            value.contains("dood.one") ||
            value.contains("dood.video") ||
            value.contains("d0000d.com") ||
            value.contains("dooood.com") ||
            value.contains("do0od.com") ||
            value.contains("doods.pro") ||
            value.contains("doods.to") ||
            value.contains("doodcdn.co") ||
            value.contains("ds2play.com")
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()
        return isDoodstreamHost(value) || listOf(
            "jomblo.org", "playmogo.com", "filemoon", "streamwish", "wishfast", "streamtape",
            "vidhide", "vidguard", "voe", "mixdrop", "mp4upload", "lulustream", "luluvdoo", "lulu",
            "hglink", "hgcloud", "majorplay", "jeniusplay", "pornhub", "xvideos", "xhamster", "redtube", "spankbang",
            "embed", "player", "stream"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".webm", true) ||
            url.contains(".txt", true) || isKnownHost(url)
    }

    private fun isPlayableMedia(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".webm", true)
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("facebook.com") || value.contains("twitter.com") || value.contains("telegram") ||
            value.contains("whatsapp") || value.contains("mailto:") || value.contains("content-removal") ||
            value.contains("privacy") || value.contains("dmca") || value.contains("copy") || value.contains("share")
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        val clean = url.cleanEscaped().trim().trim('"', '\'', ',', ';')
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true) || clean.startsWith("mailto:", true)) return null
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> origin(baseUrl) + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun origin(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun getPoster(document: Document): String? {
        return fixUrl(
            document.selectFirst(
                "meta[property=og:image], meta[name=twitter:image], video[poster], .poster img, .thumb img, article img, img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            },
            mainUrl
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? = value?.split(",")?.map { it.trim().substringBefore(" ") }?.lastOrNull { it.isNotBlank() && !isBadImage(it) }
        return fromSrcSet(attr("data-srcset"))
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
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() || value.startsWith("data:image") || value.contains("blank") ||
            value.contains("placeholder") || value.contains("default") || value.contains("no-image") ||
            value.contains("noimage") || value.contains("loader") || value.contains("loading") ||
            value.contains("lazy") || value.contains("spacer") || value.contains("logo") ||
            value.contains("favicon") || value.contains("banner") || value.endsWith(".svg")
    }

    private fun isUnsafeTitle(text: String): Boolean {
        val value = text.lowercase()
        return value.contains("smp") || value.contains("sma") || value.contains("smk") ||
            value.contains("sekolah") || value.contains("pelajar") || value.contains("siswi") ||
            value.contains("anak kecil") || value.contains("dibawah umur") || value.contains("underage") ||
            value.contains("rape") || value.contains("dipaksa") || value.contains("pemerkosaan") ||
            value.contains("incest") || value.contains("sedarah")
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) || (url.contains("majorplay", true) && url.contains("config", true) && url.contains(".json", true))
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vast") || value.contains("preroll") || value.contains("doubleclick") ||
            value.contains("googlesyndication") || value.contains("ads") || value.contains("banner") ||
            value.contains("content-removal") || value.contains("popads") || value.contains("onclick") ||
            value.contains("adsterra") || value.contains("tracking") || value.contains("analytics") ||
            value.contains("histats") || value.contains("cloudflareinsights")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1440", true) -> Qualities.P1440.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) || url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .getOrElse { runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull() }
    }

    private fun canonicalUrl(url: String): String = url.substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun String?.cleanEscaped(): String {
        return this.orEmpty()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("\\u002D", "-")
            .replace("\\u005C", "\\")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+\|\s+INDO18\.COM.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+INDO18\.COM.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
