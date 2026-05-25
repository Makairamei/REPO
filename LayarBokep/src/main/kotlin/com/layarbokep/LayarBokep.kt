package com.layarbokep

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
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

class LayarBokep : MainAPI() {
    override var mainUrl = "https://layarbokep-mobile.ubuntumysec.workers.dev"
    override var name = "LayarBokep"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "category/amateur" to "Amateur",
        "category/asia" to "Asia",
        "category/barat" to "Barat",
        "category/cosplay" to "Cosplay",
        "category/japan" to "Japan",
        "category/jav" to "JAV",
        "category/korea" to "Korea",
        "category/viral" to "Viral",
        "category/indo" to "Indo",
        "category/bokep-indo" to "Bokep Indo",
        "category/uncensored" to "Uncensored"
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
        val pageUrls = buildPageCandidates(request.data, page)
        var bestDocument: Document? = null
        var bestResults: List<SearchResponse> = emptyList()

        for (url in pageUrls) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 25L)
            }.getOrNull() ?: continue

            val text = response.text.cleanEscaped()
            val parsed = parseCards(response.document)
                .ifEmpty { parseCards(Jsoup.parse(text)) }
                .ifEmpty { extractCardsFromRawText(text) }
                .distinctBy { it.url }

            if (parsed.isNotEmpty()) {
                bestDocument = response.document
                bestResults = parsed
                break
            }

            if (bestDocument == null) bestDocument = response.document
        }

        return newHomePageResponse(
            request.name,
            bestResults,
            hasNext = bestDocument?.let { hasNextPage(it, page) } ?: false
        )
    }

    private fun buildPageCandidates(path: String, page: Int): List<String> {
        val cleanPath = path.trim('/')
        val currentPage = page.coerceAtLeast(1)

        val base = when {
            cleanPath.isBlank() -> mainUrl
            cleanPath.startsWith("http", true) -> cleanPath
            else -> "$mainUrl/$cleanPath"
        }.trimEnd('/')

        return if (currentPage <= 1) {
            listOf(base)
        } else {
            listOf(
                "$base/page/$currentPage",
                "$base/page/$currentPage/",
                "$base?paged=$currentPage",
                "$base?page=$currentPage"
            )
        }.distinct()
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a[href]):has(img), " +
                "div.video-item:has(a[href]), " +
                ".video-item:has(a[href]), " +
                ".video-card:has(a[href]), " +
                ".post:has(a[href]):has(img), " +
                ".item:has(a[href]):has(img), " +
                ".card:has(a[href]):has(img), " +
                ".grid article:has(a[href]), " +
                ".content article:has(a[href])"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select(
                "h2 a[href], " +
                    "h3 a[href], " +
                    ".title a[href], " +
                    ".entry-title a[href], " +
                    "a[href]:has(img)"
            ).forEach { element ->
                element.toSearchResult()?.let { results[it.url] = it }
            }
        }

        return results.values.toList()
    }

    private fun extractCardsFromRawText(text: String): List<SearchResponse> {
        val clean = text.cleanEscaped()
        val results = linkedMapOf<String, SearchResponse>()

        Regex(
            """"(?:url|href|link)"\s*:\s*"([^"]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val href = normalizeUrl(match.groupValues[1], mainUrl)
            if (!isContentUrl(href) || isBlockedUrl(href)) return@forEach

            val nearby = clean.substring(
                (match.range.first - 600).coerceAtLeast(0),
                (match.range.last + 900).coerceAtMost(clean.length)
            )

            val title = listOfNotNull(
                Regex(""""(?:title|name|alt)"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
                    .find(nearby)?.groupValues?.getOrNull(1),
                href.substringAfterLast("/").replace("-", " ")
            ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }
                ?.cleanTitle()
                ?: return@forEach

            val poster = Regex(""""(?:image|poster|thumbnail|thumb|src)"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
                .find(nearby)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { normalizeUrl(it, mainUrl) }
                ?.takeIf { !isBadImage(it) }

            results[href] = newMovieSearchResponse(title, href, TvType.NSFW) {
                posterUrl = poster
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
                    ".title a[href], " +
                    ".entry-title a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (!isContentUrl(href) || isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst(".title")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            anchor.attr("aria-label").trim(),
            anchor.attr("title").trim(),
            image?.attr("alt")?.trim(),
            anchor.text().trim(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 3) return null

        val poster = fixUrlNull(image?.getImageAttr())?.takeIf { !isBadImage(it) }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()
        return path.isNotBlank() &&
            !path.startsWith("category/") &&
            !path.startsWith("tag/") &&
            !path.startsWith("page/") &&
            !path.startsWith("search") &&
            !path.startsWith("privacy") &&
            !path.startsWith("dmca") &&
            !path.startsWith("contact") &&
            !path.startsWith("terms") &&
            !path.startsWith("wp-") &&
            !path.startsWith("api/")
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()
        return path.isBlank() || listOf(
            "category/", "tag/", "page/", "search", "privacy", "dmca", "contact",
            "terms", "feed", "wp-content", "wp-json", "wp-admin"
        ).any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".pagination a:contains(Berikutnya), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}'], " +
                "a[href*='paged=${page + 1}'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val q = URLEncoder.encode(keyword, "UTF-8")
        val searchUrls = listOf(
            if (page <= 1) "$mainUrl/?s=$q" else "$mainUrl/page/${page.coerceAtLeast(1)}/?s=$q",
            if (page <= 1) "$mainUrl/search?q=$q" else "$mainUrl/search?page=$page&q=$q",
            if (page <= 1) "$mainUrl/cari?kata-kunci=$q" else "$mainUrl/cari?kata-kunci=$q&page=$page"
        )

        for (url in searchUrls) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 25L)
            }.getOrNull() ?: continue

            val results = parseCards(response.document)
                .ifEmpty { extractCardsFromRawText(response.text.cleanEscaped()) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                return newSearchResponseList(results, hasNext = hasNextPage(response.document, page))
            }
        }

        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = headers, timeout = 25L)
        val document = response.document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[itemprop=name]")?.attr("content"),
            document.selectFirst("h1, h1.entry-title, .entry-title, .video-title")?.text(),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        val poster = getPoster(document)

        val plot = listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".entry-content p, .entry-content, .description, .video-description")?.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.trim()

        val tags = document.select(
            "a[href*='/category/'], a[href*='/tag/'], .tags a, .category a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        val recommendations = parseCards(document)
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data, mainUrl)
        val response = app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 25L)
        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectCandidatesFromDocument(document, pageUrl, directLinks, embedLinks)
        collectWordpressAjaxPlayers(document, pageUrl, directLinks, embedLinks)
        collectWorkerApiCandidates(pageUrl, directLinks, embedLinks)

        extractPlayableUrls(html).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
        extractSubtitles(html, pageUrl, subtitleCallback)

        decodeBase64Payloads(html).forEach { decoded ->
            extractPlayableUrls(decoded).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
            collectCandidatesFromDocument(Jsoup.parse(decoded), pageUrl, directLinks, embedLinks)
            extractSubtitles(decoded, pageUrl, subtitleCallback)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            val cleanUnpacked = unpacked.cleanEscaped()
            extractPlayableUrls(cleanUnpacked).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
            collectCandidatesFromDocument(Jsoup.parse(cleanUnpacked), pageUrl, directLinks, embedLinks)
            extractSubtitles(cleanUnpacked, pageUrl, subtitleCallback)
        }

        var found = false

        directLinks
            .filterNot { isBadPlayableUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { qualitySortRank(it) })
            .forEach { link ->
                emitDirectLink(link, pageUrl, callback)
                found = true
            }

        if (found) return true

        for (embed in prioritizeEmbeds(embedLinks).take(12)) {
            val extractorSuccess = loadExtractor(embed, pageUrl, subtitleCallback, callback)
            if (extractorSuccess) return true

            val nestedLinks = resolveNestedLinks(embed, pageUrl, subtitleCallback)
            for (nested in nestedLinks) {
                val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")
                when {
                    isBadPlayableUrl(fixed) -> Unit
                    isHlsLike(fixed) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                        emitDirectLink(fixed, embed, callback)
                        return true
                    }
                    fixed.startsWith("http", true) -> {
                        if (loadExtractor(fixed, embed, subtitleCallback, callback)) return true
                    }
                }
            }
        }

        return false
    }

    private suspend fun collectWordpressAjaxPlayers(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val players = document.select(
            "#playeroptionsul li[data-post][data-nume][data-type], " +
                ".dooplay_player_option[data-post][data-nume][data-type], " +
                ".player-option[data-post][data-nume][data-type], " +
                "li[data-post][data-nume][data-type]"
        )

        if (players.isEmpty()) return

        players.forEach { player ->
            val post = player.attr("data-post").trim()
            val nume = player.attr("data-nume").trim()
            val type = player.attr("data-type").trim()
            val label = player.text().lowercase()

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach
            if (label.contains("trailer") || nume.contains("trailer", true)) return@forEach

            val ajaxText = runCatching {
                app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept" to "application/json,text/html,text/plain,*/*",
                        "Origin" to mainUrl
                    ),
                    referer = pageUrl,
                    timeout = 20L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (ajaxText.isBlank()) return@forEach

            parsePlayerResponse(ajaxText, pageUrl, directLinks, embedLinks)
        }
    }

    private suspend fun collectWorkerApiCandidates(
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val slug = pageUrl.trimEnd('/').substringAfterLast("/")
        if (slug.isBlank()) return

        val apiUrls = listOf(
            "$mainUrl/api/video/$slug",
            "$mainUrl/api/videos/$slug",
            "$mainUrl/site/video/$slug",
            "$mainUrl/video/$slug/source",
            "$mainUrl/video/$slug/stream",
            "$mainUrl/video/$slug/play"
        )

        apiUrls.forEach { apiUrl ->
            val text = runCatching {
                app.get(
                    apiUrl,
                    headers = headers + mapOf(
                        "Accept" to "application/json,text/html,text/plain,*/*",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    referer = pageUrl,
                    timeout = 12L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (text.isNotBlank() && !text.startsWith("<!DOCTYPE", true)) {
                parsePlayerResponse(text, apiUrl, directLinks, embedLinks)
            }
        }
    }

    private fun parsePlayerResponse(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        extractPlayableUrls(text).forEach { addCandidate(it, baseUrl, directLinks, embedLinks) }

        Regex(
            """"(?:embed_url|embedUrl|iframe|player|url|source|file|src)"\s*:\s*"((?:\\.|[^"])*)""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            val raw = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .cleanEscaped()

            if (raw.isNotBlank()) addCandidate(decodeIframe(raw), baseUrl, directLinks, embedLinks)
        }

        val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        if (decoded != text) {
            extractPlayableUrls(decoded).forEach { addCandidate(it, baseUrl, directLinks, embedLinks) }
            collectCandidatesFromDocument(Jsoup.parse(decoded), baseUrl, directLinks, embedLinks)
        }

        collectCandidatesFromDocument(Jsoup.parse(text), baseUrl, directLinks, embedLinks)
        decodeBase64Payloads(text).forEach { decodedText ->
            extractPlayableUrls(decodedText).forEach { addCandidate(it, baseUrl, directLinks, embedLinks) }
            collectCandidatesFromDocument(Jsoup.parse(decodedText), baseUrl, directLinks, embedLinks)
        }
    }

    private fun decodeIframe(value: String): String {
        val clean = value.cleanEscaped()
        return when {
            clean.contains("<iframe", true) -> clean
            clean.startsWith("http", true) -> clean
            else -> runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        }
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "meta[itemprop=embedURL], " +
                "meta[property=og:video], " +
                "meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "embed[src], object[data], video[src], video[poster], video source[src], source[src], " +
                "a[href], [data-src], [data-video], [data-file], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach
            val label = element.text().lowercase()

            if (raw.startsWith("#") || raw.startsWith("javascript", true)) return@forEach
            if (label.contains("trailer") || label.contains("report")) return@forEach

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<String> {
        if (isBadPlayableUrl(url)) return emptyList()

        val response = runCatching {
            app.get(
                url,
                headers = headers + mapOf("Accept" to "text/html,application/json,text/plain,*/*"),
                referer = referer,
                timeout = 15L
            )
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        val results = linkedSetOf<String>()

        collectCandidatesFromDocument(response.document, url, results, results)
        results.addAll(extractPlayableUrls(text))
        extractSubtitles(text, url, subtitleCallback)

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            val cleanUnpacked = unpacked.cleanEscaped()
            results.addAll(extractPlayableUrls(cleanUnpacked))
            collectCandidatesFromDocument(Jsoup.parse(cleanUnpacked), url, results, results)
            extractSubtitles(cleanUnpacked, url, subtitleCallback)
        }

        decodeBase64Payloads(text).forEach { decoded ->
            results.addAll(extractPlayableUrls(decoded))
            collectCandidatesFromDocument(Jsoup.parse(decoded), url, results, results)
            extractSubtitles(decoded, url, subtitleCallback)
        }

        return results
            .map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filterNot { isBadPlayableUrl(it) }
            .distinct()
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isBadPlayableUrl(fixed)) return

        when {
            isHlsLike(fixed) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> directLinks.add(fixed)
            fixed.startsWith("http", true) && isKnownHost(fixed) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("embed", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("player", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("stream", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("/video/", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isBadPlayableUrl(link)) return

        if (isHlsLike(link)) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
                )
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
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
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
            .filterNot { isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|jeniusplay|majorplay|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|source|src|url|videoSource|videoUrl|video_url|hls|hlsUrl|hls_url|stream|streamUrl|embedUrl|embed_url|contentUrl)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    isKnownHost(it)
            }
            .filterNot { isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:jeniusplay|majorplay|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|hglink|hgcloud|lulustream|embed|player|stream)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun extractSubtitles(
        text: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val clean = text.cleanEscaped()
        Regex(
            """(?:file|src|url|path)\s*[:=]\s*["']([^"']+\.(?:vtt|srt|ass)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val subUrl = normalizeUrl(match.groupValues[1], baseUrl)
            val label = when {
                subUrl.contains("ind", true) || subUrl.contains("indo", true) -> "Indonesian"
                subUrl.contains("eng", true) || subUrl.contains("english", true) -> "English"
                else -> "Subtitle"
            }
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun decodeBase64Payloads(text: String): List<String> {
        val results = linkedSetOf<String>()

        Regex(
            """["']([A-Za-z0-9+/=]{40,})["']"""
        ).findAll(text).forEach { match ->
            val value = match.groupValues[1]
            val decoded = runCatching {
                String(Base64.getDecoder().decode(value))
            }.getOrNull().orEmpty()

            if (
                decoded.contains("iframe", true) ||
                decoded.contains("source", true) ||
                decoded.contains(".m3u8", true) ||
                decoded.contains(".mp4", true) ||
                decoded.contains("embed", true)
            ) {
                results.add(decoded.cleanEscaped())
            }
        }

        return results.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isBadPlayableUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            value.contains("jeniusplay") -> 0
            value.contains("majorplay") -> 1
            value.contains("hglink") || value.contains("hgcloud") -> 2
            value.contains("streamwish") || value.contains("wishfast") -> 3
            value.contains("filemoon") -> 4
            value.contains("vidhide") || value.contains("vidguard") -> 5
            value.contains("voe") -> 6
            value.contains("mixdrop") -> 7
            value.contains("mp4upload") -> 8
            value.contains("streamtape") -> 9
            value.contains("dood") -> 10
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
    }

    private fun qualitySortRank(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> 0
            url.contains("1080", true) -> 1
            url.contains("720", true) -> 2
            url.contains("480", true) -> 3
            url.contains("360", true) -> 4
            else -> 5
        }
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "jeniusplay", "majorplay", "filemoon", "streamwish", "wishfast", "dood",
            "streamtape", "vidhide", "vidguard", "voe", "mixdrop", "mp4upload",
            "hglink", "hgcloud", "lulustream", "embed", "player", "stream"
        ).any { value.contains(it) }
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true)
    }

    private fun isBadPlayableUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("banner") ||
            value.contains("/ads/") ||
            value.contains("mailto:") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("report")
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
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

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], meta[name=twitter:image], meta[itemprop=thumbnailUrl], " +
                    "video[poster], .player img, div.player img, .poster img, .thumb img, " +
                    "img.wp-post-image, article img, img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull { it.isNotBlank() && !isBadImage(it) }
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

        return raw?.trim()?.takeIf { !isBadImage(it) }
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
            value.endsWith(".svg")
    }

    private fun isBadTitle(title: String): Boolean {
        val value = title.trim().lowercase()
        return value.isBlank() ||
            value == "home" ||
            value == "next" ||
            value == "previous" ||
            value == "search" ||
            value == "trailer" ||
            value == "download" ||
            value == "report" ||
            value.length < 3
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
            .replace(Regex("""\s+-\s+LayarBokep.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
