package com.sad25kag.IndoAV

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Calendar

@CloudstreamPlugin
class IndoAVPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(IndoAV())
    }
}

class IndoAV : MainAPI() {
    override var mainUrl = "https://www.indoav.com"
    override var name = "IndoAV"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "" to "Trending",
        "trending" to "Trending",
        "?filter=terbaru" to "Terbaru",
        "?filter=banyak-dilihat" to "Banyak Dilihat",
        "?filter=banyak-disukai" to "Disukai",
        "?filter=banyak-dikomentari" to "Banyak Dikomentari",
        "?filter=durasi-panjang" to "Durasi Panjang",
        "?filter=random" to "Random",

        "kategori/bokep-indonesia" to "Bokep Indonesia",
        "kategori/bokep-indo" to "Bokep Indo",
        "kategori/bokep-sin" to "Bokep SIN",
        "kategori/bokep-dosa" to "Bokep Dosa",
        "kategori/bokep-barat" to "Bokep Barat",
        "kategori/bokep-asia" to "Bokep Asia",
        "kategori/bokep-jepang" to "Bokep Jepang",
        "kategori/tanpa-sensor" to "Tanpa Sensor",

        "genre/bokep-abg" to "ABG",
        "genre/bokep-jilbab" to "Jilbab",
        "genre/bokep-ukhti" to "Ukhti",
        "genre/bokep-malaysia" to "Malaysia",
        "genre/bokep-lesbian" to "Lesbian",
        "genre/bokepsin" to "BOKEPSIN",
        "genre/bokep-binor" to "Binor",
        "genre/bokep-skandal" to "Skandal",
        "genre/bokep-mahasiswi" to "Mahasiswi",
        "genre/bokep-tiktok" to "Tiktok",
        "genre/bokep-sugar-daddy" to "Sugar Daddy"
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
            timeout = 25L
        ).document

        val staticItems = parseCards(document)
            .filterNot { isBadTitle(it.name) }
            .distinctBy { it.url }

        val dynamicItems = if (staticItems.isEmpty() || hasFeedSkeleton(document)) {
            parseDynamicFeed(document, request.data, page)
        } else {
            emptyList()
        }

        val items = (staticItems + dynamicItems)
            .filterNot { isBadTitle(it.name) }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page) || dynamicItems.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val clean = path.trim()

        return when {
            page <= 1 && clean.isBlank() -> mainUrl
            page <= 1 && clean.startsWith("?") -> "$mainUrl/$clean"
            page <= 1 -> "$mainUrl/${clean.trim('/')}"

            clean.isBlank() -> "$mainUrl/halaman/$page"
            clean.startsWith("?") -> "$mainUrl/halaman/$page$clean"

            clean.contains("?") -> {
                val base = clean.substringBefore("?").trim('/')
                val query = clean.substringAfter("?")
                "$mainUrl/$base/halaman/$page?$query"
            }

            else -> "$mainUrl/${clean.trim('/')}/halaman/$page"
        }
    }

    private fun hasFeedSkeleton(document: Document): Boolean {
        return document.selectFirst("#home-feed-root[data-feed-endpoint], .video-card--skeleton") != null
    }

    private suspend fun parseDynamicFeed(
        document: Document,
        requestPath: String,
        page: Int
    ): List<SearchResponse> {
        val endpoint = document.selectFirst("#home-feed-root[data-feed-endpoint]")
            ?.attr("data-feed-endpoint")
            ?.takeIf { it.isNotBlank() }
            ?: "$mainUrl/site/feed"

        val query = mutableListOf("page=$page")

        if (requestPath.startsWith("?")) {
            requestPath.removePrefix("?")
                .split("&")
                .filter { it.isNotBlank() }
                .forEach { query.add(it) }
        }

        val feedUrl = endpoint + if (endpoint.contains("?")) {
            "&${query.joinToString("&")}"
        } else {
            "?${query.joinToString("&")}"
        }

        val feedText = runCatching {
            app.get(
                feedUrl,
                headers = headers + mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "text/html,application/json,text/plain,*/*"
                ),
                referer = mainUrl,
                timeout = 20L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (feedText.isBlank()) return emptyList()

        val decoded = runCatching {
            URLDecoder.decode(feedText, "UTF-8")
        }.getOrDefault(feedText)

        val feedDocument = Jsoup.parse(decoded)
        val parsed = parseCards(feedDocument)

        if (parsed.isNotEmpty()) return parsed

        return extractSearchResponsesFromRawFeed(decoded)
    }

    private fun extractSearchResponsesFromRawFeed(text: String): List<SearchResponse> {
        val clean = text.cleanEscaped()
        val results = linkedMapOf<String, SearchResponse>()

        Regex(
            """"(?:url|href)"\s*:\s*"([^"]*/video/[^"]+)"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val href = normalizeUrl(match.groupValues[1], mainUrl)
            if (isBlockedUrl(href)) return@forEach

            val nearby = clean.substring(
                (match.range.first - 700).coerceAtLeast(0),
                (match.range.last + 1000).coerceAtMost(clean.length)
            )

            val title = listOfNotNull(
                Regex(""""(?:title|name|alt)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(nearby)?.groupValues?.getOrNull(1),
                href.substringAfterLast("/").replace("-", " ")
            ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }
                ?.cleanTitle()
                ?: return@forEach

            val poster = Regex(""""(?:image|poster|thumbnail|thumb|src)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(nearby)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { normalizeUrl(it, mainUrl) }
                ?.takeIf { !isBadImage(it) }

            results[href] = newMovieSearchResponse(
                title,
                href,
                TvType.NSFW
            ) {
                posterUrl = poster
            }
        }

        Regex(
            """https?://www\.indoav\.com/video/[^"'\\\s<>]+""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val href = normalizeUrl(match.value, mainUrl)
            if (isBlockedUrl(href)) return@forEach

            val title = href.substringAfterLast("/")
                .replace("-", " ")
                .cleanTitle()

            if (title.isBlank() || isBadTitle(title)) return@forEach

            results[href] = newMovieSearchResponse(
                title,
                href,
                TvType.NSFW
            )
        }

        return results.values.toList()
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
                "a[href*='/halaman/${page + 1}'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article.video-card:not(.video-card--skeleton):has(a[href*='/video/']), " +
                "article:not(.video-card--skeleton):has(a[href*='/video/']):has(img), " +
                ".post:has(a[href*='/video/']):has(img), " +
                ".item:has(a[href*='/video/']):has(img), " +
                ".video:has(a[href*='/video/']):has(img), " +
                ".video-item:has(a[href*='/video/']):has(img), " +
                ".grid article:has(a[href*='/video/']):has(img), " +
                ".content article:has(a[href*='/video/']):has(img), " +
                ".card:has(a[href*='/video/']):has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                ".video-card__title a[href*='/video/'], " +
                    ".video-card__link[href*='/video/'], " +
                    "h2 a[href*='/video/'], " +
                    "h3 a[href*='/video/'], " +
                    "a[href*='/video/']:has(img)"
            ).forEach { element ->
                element.toSearchResult()?.let { item ->
                    results[item.url] = item
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        if (hasClass("video-card--skeleton") || selectFirst(".video-card--skeleton") != null) return null

        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                ".video-card__title a[href*='/video/'], " +
                    ".video-card__link[href*='/video/'], " +
                    "h2 a[href*='/video/'], " +
                    "h3 a[href*='/video/'], " +
                    ".title a[href*='/video/'], " +
                    ".entry-title a[href*='/video/'], " +
                    "a[href*='/video/']:has(img), " +
                    "a[href*='/video/']"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null
        if (!href.contains("/video/")) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst(".video-card__title a")?.text(),
            selectFirst("h2 a")?.text(),
            selectFirst("h3 a")?.text(),
            selectFirst(".title a")?.text(),
            selectFirst(".entry-title a")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())
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
            "kategori/",
            "genre/",
            "halaman/",
            "upload",
            "members",
            "terms",
            "syarat",
            "report",
            "hubungi",
            "contact",
            "partners",
            "copyright",
            "transparency",
            "2257",
            "wp-content",
            "wp-json",
            "api/",
            "search",
            "cari"
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

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            if (page <= 1) "$mainUrl/cari?kata-kunci=$encoded" else "$mainUrl/cari?kata-kunci=$encoded&page=$page",
            if (page <= 1) "$mainUrl/search?q=$encoded" else "$mainUrl/search?q=$encoded&page=$page",
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/halaman/$page?s=$encoded",
            if (page <= 1) "$mainUrl/?search=$encoded" else "$mainUrl/halaman/$page?search=$encoded"
        )

        var bestResults: List<SearchResponse> = emptyList()
        var hasNext = false

        for (url in attempts) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    timeout = 25L
                ).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .filterNot { isBadTitle(it.name) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                bestResults = results
                hasNext = hasNextPage(document, page)
                break
            }
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers,
            timeout = 25L
        ).document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[itemprop=name]")?.attr("content"),
            document.selectFirst("h1 b")?.text(),
            document.selectFirst("h1, h1.entry-title")?.text(),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: name

        val poster = getPoster(document)
        val text = document.text()

        val duration = parseRuntime(
            document.selectFirst("meta[itemprop=duration]")?.attr("content")
                ?: document.selectFirst("meta[property=video:duration]")?.attr("content")
                ?: text
        )

        val views = Regex("""(?i)([\d.,kmb]+)\s+views?""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        val tags = document.select(
            "a[href*='/kategori/'], " +
                "a[href*='/genre/'], " +
                "a[href*='/tag/']"
        ).map { it.text().trim().cleanTitle() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Semua Kategori", true) &&
                    !it.equals("Semua Genre", true) &&
                    !isBadTitle(it)
            }
            .distinct()

        val related = document.select(
            "article:has(a[href*='/video/']), " +
                ".related a[href*='/video/'], " +
                "h2 a[href*='/video/'], " +
                "h3 a[href*='/video/'], " +
                ".video-card__title a[href*='/video/']"
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
            plot = listOfNotNull(
                duration?.let { "Durasi: ${it} menit" },
                views?.let { "Dilihat: $it" }
            ).joinToString("\n").ifBlank { null }
            this.tags = tags
            duration?.let { this.duration = it }
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
        val pageUrl = normalizeUrl(data, mainUrl)

        val response = app.get(
            pageUrl,
            headers = headers,
            referer = mainUrl,
            timeout = 20L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectCandidatesFromDocument(
            document = document,
            baseUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        collectIndoAvPlayer(
            pageUrl = pageUrl,
            document = document,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        val decodedOnce = runCatching {
            URLDecoder.decode(html, "UTF-8")
        }.getOrDefault(html)

        if (decodedOnce != html) {
            extractPlayableUrls(decodedOnce.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        val embedUrls = document.select(
            "meta[itemprop=embedURL], " +
                "meta[property=og:video], " +
                "meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "[data-embed], " +
                "[data-iframe]"
        ).mapNotNull {
            it.attr("content")
                .ifBlank { it.attr("data-embed") }
                .ifBlank { it.attr("data-iframe") }
                .ifBlank { it.attr("data-src") }
                .ifBlank { it.attr("src") }
                .takeIf { raw -> raw.isNotBlank() }
                ?.let { raw -> normalizeUrl(raw, pageUrl) }
        }.filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .distinct()

        embedUrls.forEach { embed ->
            addCandidate(embed, pageUrl, directLinks, embedLinks)
        }

        for (embed in embedUrls.take(4)) {
            val embedResponse = runCatching {
                app.get(
                    embed,
                    headers = headers + mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Origin" to mainUrl
                    ),
                    referer = pageUrl,
                    timeout = 20L
                )
            }.getOrNull() ?: continue

            val embedDocument = embedResponse.document
            val embedHtml = embedResponse.text.cleanEscaped()

            collectCandidatesFromDocument(
                document = embedDocument,
                baseUrl = embed,
                directLinks = directLinks,
                embedLinks = embedLinks
            )

            collectIndoAvPlayer(
                pageUrl = pageUrl,
                document = embedDocument,
                directLinks = directLinks,
                embedLinks = embedLinks
            )

            extractPlayableUrls(embedHtml).forEach { raw ->
                addCandidate(raw, embed, directLinks, embedLinks)
            }

            val embedUnpacked = runCatching {
                if (!getPacked(embedHtml).isNullOrEmpty()) getAndUnpack(embedHtml) else null
            }.getOrNull()

            if (!embedUnpacked.isNullOrBlank()) {
                extractPlayableUrls(embedUnpacked.cleanEscaped()).forEach { raw ->
                    addCandidate(raw, embed, directLinks, embedLinks)
                }
            }

            val scriptTexts = collectScriptTexts(embedDocument, embed)

            scriptTexts.forEach { script ->
                extractPlayableUrls(script).forEach { raw ->
                    addCandidate(raw, embed, directLinks, embedLinks)
                }
            }

            tryTokenEndpoints(
                embedUrl = embed,
                document = embedDocument,
                html = embedHtml,
                scripts = scriptTexts,
                directLinks = directLinks,
                embedLinks = embedLinks
            )
        }

        directLinks
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .distinct()
            .sortedWith(
                compareBy<String> { if (isHlsLike(it)) 0 else 1 }
                    .thenBy { hostPriority(it) }
            )
            .forEach { link ->
                emitDirectLink(
                    link = link,
                    referer = pageUrl,
                    callback = callback
                )
            }

        if (directLinks.isNotEmpty()) return true

        for (embed in prioritizeEmbeds(embedLinks).take(12)) {
            val success = loadExtractor(
                embed,
                pageUrl,
                subtitleCallback,
                callback
            )

            if (success) return true

            val nestedLinks = resolveNestedLinks(embed, pageUrl)

            for (nested in nestedLinks) {
                val fixed = normalizeUrl(nested, embed)
                    .replace(".txt", ".m3u8")

                when {
                    isAdUrl(fixed) || shouldSkipUrl(fixed) -> Unit

                    isHlsLike(fixed) ||
                        fixed.contains(".mp4", true) ||
                        fixed.contains(".webm", true) -> {
                        emitDirectLink(
                            link = fixed,
                            referer = embed,
                            callback = callback
                        )
                        return true
                    }

                    fixed.startsWith("http", true) -> {
                        val nestedSuccess = loadExtractor(
                            fixed,
                            embed,
                            subtitleCallback,
                            callback
                        )

                        if (nestedSuccess) return true

                        for (deep in resolveNestedLinks(fixed, embed)) {
                            val deepFixed = normalizeUrl(deep, fixed)
                                .replace(".txt", ".m3u8")

                            when {
                                isAdUrl(deepFixed) || shouldSkipUrl(deepFixed) -> Unit

                                isHlsLike(deepFixed) ||
                                    deepFixed.contains(".mp4", true) ||
                                    deepFixed.contains(".webm", true) -> {
                                    emitDirectLink(
                                        link = deepFixed,
                                        referer = fixed,
                                        callback = callback
                                    )
                                    return true
                                }

                                deepFixed.startsWith("http", true) -> {
                                    val deepSuccess = loadExtractor(
                                        deepFixed,
                                        fixed,
                                        subtitleCallback,
                                        callback
                                    )

                                    if (deepSuccess) return true
                                }
                            }
                        }
                    }
                }
            }
        }

        return false
    }

    private suspend fun collectIndoAvPlayer(
        pageUrl: String,
        document: Document,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val pageSlug = pageUrl
            .substringAfter("/video/", "")
            .substringAfter("/e/", "")
            .substringBefore("?")
            .trim('/')

        val tokenUrls = linkedSetOf<String>()

        document.select("[data-play-token]").forEach { element ->
            val token = element.attr("data-play-token").trim()

            decodeIndoAvEncodedJsonUrls(token).forEach { url ->
                tokenUrls.add(url)
                addCandidate(
                    raw = url,
                    baseUrl = pageUrl,
                    directLinks = directLinks,
                    embedLinks = embedLinks
                )
            }
        }

        val streamLinks = document.select(
            "a.select-stream-link[data-filecode], " +
                "a.select-stream-link[data-play-token], " +
                "[data-filecode][data-play-token]"
        )

        val jobs = linkedSetOf<Pair<String, String>>()

        if (streamLinks.isNotEmpty()) {
            streamLinks.forEach { element ->
                val streamName = element.attr("aria-label")
                    .replace(Regex("""(?i)^Stream\s+"""), "")
                    .trim()
                    .ifBlank { "EM" }

                val fileCode = element.attr("data-filecode")
                    .trim()
                    .ifBlank { pageSlug }

                if (fileCode.isNotBlank()) {
                    jobs.add(fileCode to streamName)
                }
            }
        }

        if (pageSlug.isNotBlank()) {
            jobs.add(pageSlug to "EM")
        }

        tokenUrls.forEach { tokenUrl ->
            val tokenSlug = tokenUrl
                .substringAfter("/e/", "")
                .substringAfter("/embed/", "")
                .substringBefore("?")
                .trim('/')

            if (tokenSlug.isNotBlank()) {
                jobs.add(tokenSlug to "EM")
            }
        }

        jobs.forEach { (code, streamName) ->
            val payload = buildIndoAvLoadPayload(
                code = code,
                streamName = streamName
            )

            val responseText = runCatching {
                app.post(
                    "$mainUrl/video/load/$payload/",
                    data = mapOf(
                        "video" to payload
                    ),
                    headers = headers + mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-REQUESTED-WITH" to "official-app",
                        "Origin" to mainUrl,
                        "Accept" to "text/html,application/json,text/plain,*/*"
                    ),
                    referer = pageUrl,
                    timeout = 15L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (responseText.isBlank()) return@forEach

            val decrypted = decryptIndoAvEncoded(responseText)
                ?.cleanEscaped()
                .orEmpty()

            listOf(responseText, decrypted)
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { playerHtml ->
                    parsePlayerResponse(
                        text = playerHtml,
                        baseUrl = pageUrl,
                        directLinks = directLinks,
                        embedLinks = embedLinks
                    )

                    val playerDocument = Jsoup.parse(playerHtml)

                    collectCandidatesFromDocument(
                        document = playerDocument,
                        baseUrl = pageUrl,
                        directLinks = directLinks,
                        embedLinks = embedLinks
                    )

                    extractPlayableUrls(playerHtml).forEach { raw ->
                        addCandidate(
                            raw = raw,
                            baseUrl = pageUrl,
                            directLinks = directLinks,
                            embedLinks = embedLinks
                        )
                    }
                }
        }
    }

    private fun buildIndoAvLoadPayload(
        code: String,
        streamName: String
    ): String {
        val calendar = Calendar.getInstance()

        val timestamp = ":.e:" +
            calendar.get(Calendar.DAY_OF_MONTH) +
            "/" +
            (calendar.get(Calendar.MONTH) + 1) +
            "/" +
            calendar.get(Calendar.YEAR) +
            "@" +
            calendar.get(Calendar.HOUR_OF_DAY) +
            ":" +
            calendar.get(Calendar.MINUTE) +
            ":" +
            calendar.get(Calendar.SECOND)

        val raw = randomAlphaNum(10) +
            ":.a:" +
            reverseToken(code) +
            timestamp +
            ":.s:" +
            streamName

        var payload = reverseToken(base64EncodeBinary(raw))
        payload = rc4(payload, indoAvSecretKey())
        payload = reverseToken(base64EncodeBinary(payload))

        return payload
    }

    private fun decodeIndoAvEncodedJsonUrls(value: String): List<String> {
        if (value.isBlank()) return emptyList()

        val decoded = decryptIndoAvEncoded(value).orEmpty()
        if (decoded.isBlank()) return emptyList()

        val results = linkedSetOf<String>()

        Regex(
            """"u"\s*:\s*\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { arrayText ->
                Regex(""""([^"]+)"""")
                    .findAll(arrayText)
                    .map { it.groupValues[1].cleanEscaped() }
                    .filter { it.startsWith("http", true) }
                    .forEach { results.add(it) }
            }

        extractPlayableUrls(decoded).forEach { results.add(it) }

        return results.toList()
    }

    private fun decryptIndoAvEncoded(value: String): String? {
        return runCatching {
            val binary = base64DecodeBinary(reverseToken(value.trim()))
            rc4(binary, indoAvSecretKey())
        }.getOrNull()
    }

    private fun indoAvSecretKey(): String {
        val encoded = "rqpSaEddZ156f342cjwOD8vc4/SYtI0ILIo5UUj45apkqA06FzRKvr92GErrdKGZozMV1L52EueOl7B7yO1efjk8uBhSzLOf"
        val once = base64DecodeBinary(encoded)
        return base64DecodeBinary(once)
    }

    private fun reverseToken(value: String): String {
        return value.reversed()
    }

    private fun base64EncodeBinary(value: String): String {
        return Base64.getEncoder().encodeToString(
            value.toByteArray(StandardCharsets.ISO_8859_1)
        )
    }

    private fun base64DecodeBinary(value: String): String {
        return String(
            Base64.getDecoder().decode(value),
            StandardCharsets.ISO_8859_1
        )
    }

    private fun rc4(
        value: String,
        key: String
    ): String {
        if (key.isEmpty()) return value

        val data = value.toByteArray(StandardCharsets.ISO_8859_1)
        val keyBytes = key.toByteArray(StandardCharsets.ISO_8859_1)

        val s = IntArray(256) { it }
        var j = 0

        for (i in 0 until 256) {
            j = (j + s[i] + keyBytes[i % keyBytes.size].toInt().and(0xff)) and 0xff

            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }

        var i = 0
        j = 0

        val output = ByteArray(data.size)

        for (index in data.indices) {
            i = (i + 1) and 0xff
            j = (j + s[i]) and 0xff

            val temp = s[i]
            s[i] = s[j]
            s[j] = temp

            val keyStream = s[(s[i] + s[j]) and 0xff]
            output[index] = (data[index].toInt().and(0xff) xor keyStream).toByte()
        }

        return String(output, StandardCharsets.ISO_8859_1)
    }

    private fun randomAlphaNum(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        return buildString {
            repeat(length) {
                append(chars.random())
            }
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
                "video[src], " +
                "video[data-src], " +
                "video[data-video], " +
                "video[data-file], " +
                "video[data-url], " +
                "video[data-play-token], " +
                "video source[src], " +
                "source[src], " +
                "source[data-src], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "iframe[data-lazy-src], " +
                "iframe[data-original], " +
                "embed[src], " +
                "object[data], " +
                "a[href], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-iframe]"
        ).forEach { element ->
            val href = element.attr("href")
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-original") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { href }
                .trim()

            val label = element.text().lowercase()

            if (
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                shouldSkipUrl(raw) ||
                label.contains("report") ||
                label.contains("upload")
            ) {
                return@forEach
            }

            if (
                element.tagName().equals("meta", true) ||
                element.tagName().equals("video", true) ||
                element.tagName().equals("source", true) ||
                element.tagName().equals("iframe", true) ||
                element.tagName().equals("embed", true) ||
                element.tagName().equals("object", true) ||
                isLikelyPlayable(raw) ||
                isLikelyPlayableText(label)
            ) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private suspend fun tryTokenEndpoints(
        embedUrl: String,
        document: Document,
        html: String,
        scripts: List<String>,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val token = document.selectFirst("[data-play-token]")
            ?.attr("data-play-token")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""data-play-token=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        val fileCode = document.selectFirst("[data-filecode]")
            ?.attr("data-filecode")
            ?.takeIf { it.isNotBlank() }
            ?: Regex("""data-filecode=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)

        if (token.isNullOrBlank() && fileCode.isNullOrBlank()) return

        val endpoints = linkedSetOf<String>()

        endpoints.add("$mainUrl/video/source")
        endpoints.add("$mainUrl/video/stream")
        endpoints.add("$mainUrl/video/play")
        endpoints.add("$mainUrl/video/embed/source")
        endpoints.add("$mainUrl/video/embed/stream")
        endpoints.add("$mainUrl/site/video/source")
        endpoints.add("$mainUrl/site/video/stream")
        endpoints.add("$mainUrl/site/player")
        endpoints.add("$mainUrl/api/video/source")
        endpoints.add("$mainUrl/api/video/stream")
        endpoints.add("$mainUrl/api/embed/source")
        endpoints.add("$mainUrl/api/embed/stream")

        scripts.forEach { script ->
            Regex(
                """["']((?:https?://www\.indoav\.com)?/[^"']*(?:source|stream|play|embed|token|file|img|load|dp)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(script).forEach { match ->
                val endpoint = normalizeUrl(match.groupValues[1], mainUrl)
                if (
                    endpoint.startsWith(mainUrl) &&
                    !endpoint.contains(".js", true) &&
                    !endpoint.contains(".css", true) &&
                    !endpoint.contains(".webp", true)
                ) {
                    endpoints.add(endpoint)
                }
            }
        }

        val payload = mutableMapOf<String, String>()
        token?.let {
            payload["token"] = it
            payload["play_token"] = it
            payload["playToken"] = it
            payload["data_play_token"] = it
        }
        fileCode?.let {
            payload["filecode"] = it
            payload["file_code"] = it
            payload["code"] = it
        }

        for (endpoint in endpoints.take(18)) {
            runCatching {
                val postText = app.post(
                    endpoint,
                    data = payload,
                    headers = headers + mapOf(
                        "Accept" to "application/json,text/html,text/plain,*/*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to mainUrl
                    ),
                    referer = embedUrl,
                    timeout = 12L
                ).text.cleanEscaped()

                parsePlayerResponse(
                    text = postText,
                    baseUrl = endpoint,
                    directLinks = directLinks,
                    embedLinks = embedLinks
                )
            }

            runCatching {
                val query = buildList {
                    token?.let { add("token=${URLEncoder.encode(it, "UTF-8")}") }
                    token?.let { add("play_token=${URLEncoder.encode(it, "UTF-8")}") }
                    fileCode?.let { add("filecode=${URLEncoder.encode(it, "UTF-8")}") }
                    fileCode?.let { add("code=${URLEncoder.encode(it, "UTF-8")}") }
                }.joinToString("&")

                if (query.isBlank()) return@runCatching

                val getUrl = endpoint + if (endpoint.contains("?")) "&$query" else "?$query"

                val getText = app.get(
                    getUrl,
                    headers = headers + mapOf(
                        "Accept" to "application/json,text/html,text/plain,*/*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl
                    ),
                    referer = embedUrl,
                    timeout = 12L
                ).text.cleanEscaped()

                parsePlayerResponse(
                    text = getText,
                    baseUrl = endpoint,
                    directLinks = directLinks,
                    embedLinks = embedLinks
                )
            }
        }
    }

    private fun parsePlayerResponse(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (text.isBlank()) return

        extractPlayableUrls(text).forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        val decoded = runCatching {
            URLDecoder.decode(text, "UTF-8")
        }.getOrDefault(text)

        if (decoded != text) {
            extractPlayableUrls(decoded).forEach { raw ->
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }

        Jsoup.parse(text).select(
            "iframe[src], iframe[data-src], video[src], source[src], embed[src], object[data], a[href], [data-src], [data-video], [data-file], [data-url]"
        ).forEach { element ->
            val raw = element.attr("data-video")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun collectScriptTexts(
        document: Document,
        baseUrl: String
    ): List<String> {
        return document.select("script[src]")
            .mapNotNull { element ->
                normalizeUrl(element.attr("src"), baseUrl)
                    .takeIf {
                        it.startsWith("http", true) &&
                            it.contains(".js", true) &&
                            !it.contains("cloudflareinsights", true) &&
                            !it.contains("googletagmanager", true) &&
                            !it.contains("recaptcha", true) &&
                            !it.contains("plyr", true) &&
                            !it.contains("hls.js", true)
                    }
            }
            .distinct()
            .take(8)
            .mapNotNull { scriptUrl ->
                runCatching {
                    app.get(
                        scriptUrl,
                        headers = headers,
                        referer = baseUrl,
                        timeout = 12L
                    ).text.cleanEscaped()
                }.getOrNull()
            }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        if (shouldSkipUrl(url)) return emptyList()

        val response = runCatching {
            app.get(
                url,
                headers = headers + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Origin" to mainUrl
                ),
                referer = referer,
                timeout = 15L
            )
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        if (text.isBlank()) return emptyList()

        val results = linkedSetOf<String>()

        response.document.select(
            "meta[itemprop=embedURL], " +
                "meta[property=og:video], " +
                "meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "iframe[data-lazy-src], " +
                "video[src], " +
                "video[data-src], " +
                "video[data-video], " +
                "video[data-file], " +
                "source[src], " +
                "embed[src], " +
                "object[data], " +
                "a[href], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-iframe]"
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

            if (raw.isNotBlank()) {
                val fixed = normalizeUrl(raw, url)
                if (!isAdUrl(fixed) && !shouldSkipUrl(fixed)) {
                    results.add(fixed)
                }
            }
        }

        results.addAll(extractPlayableUrls(text))

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            results.addAll(extractPlayableUrls(unpacked.cleanEscaped()))
        }

        val decodedOnce = runCatching {
            URLDecoder.decode(text, "UTF-8")
        }.getOrDefault(text)

        if (decodedOnce != text) {
            results.addAll(extractPlayableUrls(decodedOnce.cleanEscaped()))
        }

        return results
            .map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
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

        if (fixed.isBlank() || isAdUrl(fixed) || shouldSkipUrl(fixed)) return

        when {
            isHlsLike(fixed) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) &&
                isKnownHost(fixed) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("embed", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("player", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("/video/embed/", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("/video/img/", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("/video/load/", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("/e/", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link) || shouldSkipUrl(link)) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHlsLike(link)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
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

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url|contentUrl|stream|streamUrl|stream_url)\s*[:=]\s*["']([^"']+)["']""",
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
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:data-file|data-video|data-url|data-src|data-embed|data-iframe|content)=["']([^"']+)["']""",
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
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|embedan|indoav|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|majorplay|jeniusplay|pornhub|xvideos|xhamster|redtube|spankbang)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isAdUrl(it) }
            .filterNot { shouldSkipUrl(it) }
            .distinct()
            .sortedWith(
                compareBy<String> { hostPriority(it) }
                    .thenBy { it.length }
            )
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("www.indoav.com/video/load") -> 0
            value.contains("www.indoav.com/video/img") -> 1
            value.contains("www.indoav.com/video/embed") -> 2
            value.contains("embedan") -> 3
            value.contains("majorplay") -> 4
            value.contains("jeniusplay") -> 5
            value.contains("hglink") -> 6
            value.contains("hgcloud") -> 7
            value.contains("lulustream") || value.contains("luluvdoo") || value.contains("lulu") -> 8
            value.contains("streamwish") || value.contains("wishfast") -> 9
            value.contains("filemoon") -> 10
            value.contains("vidhide") -> 11
            value.contains("vidguard") -> 12
            value.contains("voe") -> 13
            value.contains("mixdrop") -> 14
            value.contains("mp4upload") -> 15
            value.contains("streamtape") -> 16
            value.contains("dood") -> 17
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "www.indoav.com/video/load",
            "www.indoav.com/video/img",
            "www.indoav.com/video/embed",
            "embedan",
            "embed",
            "player",
            "stream",
            "filemoon",
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
            "luluvdoo",
            "lulu",
            "hglink",
            "hgcloud",
            "majorplay",
            "jeniusplay",
            "pornhub",
            "xvideos",
            "xhamster",
            "redtube",
            "spankbang"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".txt", true) ||
            url.contains("/video/load/", true) ||
            url.contains("/video/embed/", true) ||
            url.contains("/video/img/", true) ||
            isKnownHost(url)
    }

    private fun isLikelyPlayableText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("watch") ||
            text.contains("server") ||
            text.contains("play") ||
            text.contains("mp4") ||
            text.contains("720p") ||
            text.contains("1080p")
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("reddit.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.contains("report") ||
            value.contains("upload") ||
            value.contains("partners") ||
            value.contains("copyright") ||
            value.contains("transparency") ||
            value.contains("2257") ||
            value.contains("theporndude") ||
            value.contains("googletagmanager") ||
            value.contains("recaptcha") ||
            value.contains("cloudflareinsights")
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped().trim()

        return when {
            clean.isBlank() -> ""
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

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "meta[itemprop=thumbnailUrl], " +
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
            }
        )?.takeIf { !isBadImage(it) }
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
            value.contains("theporndude") ||
            value.endsWith(".svg")
    }

    private fun parseRuntime(text: String?): Int? {
        val input = text.orEmpty()

        val iso = Regex("""P0DT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""", RegexOption.IGNORE_CASE)
            .find(input)

        if (iso != null) {
            val h = iso.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val m = iso.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val s = iso.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (h * 60) + m + if (s > 30) 1 else 0
        }

        val seconds = input.toIntOrNull()
        if (seconds != null && seconds > 0) {
            return (seconds / 60) + if (seconds % 60 > 30) 1 else 0
        }

        val hourMinSec = Regex(
            """(?:(\d+)\s*(?:jam|h|hr|hour))?\s*(?:(\d+)\s*(?:menit|m|min))?\s*(?:(\d+)\s*(?:detik|s|sec))?""",
            RegexOption.IGNORE_CASE
        ).find(input)

        val h = hourMinSec?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val m = hourMinSec?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val s = hourMinSec?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0

        if (h > 0 || m > 0 || s > 0) {
            return (h * 60) + m + if (s > 30) 1 else 0
        }

        val compact = Regex("""(?:(\d+)h\s*)?(\d+)m(?:\s*(\d+)s)?""", RegexOption.IGNORE_CASE)
            .find(input)

        if (compact != null) {
            val ch = compact.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val cm = compact.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val cs = compact.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (ch * 60) + cm + if (cs > 30) 1 else 0
        }

        return null
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            (
                url.contains("majorplay", true) &&
                    url.contains("config", true) &&
                    url.contains(".json", true)
                )
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("/ads/") ||
            value.contains("ads.") ||
            value.contains("banner") ||
            value.contains("trafficstars") ||
            value.contains("theporndude") ||
            value.contains("report-content") ||
            value.contains("analytics") ||
            value.contains("histats")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1440", true) -> Qualities.P1440.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) -> Qualities.P480.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isBadTitle(text: String): Boolean {
        val value = text.cleanTitle().lowercase()

        return value.isBlank() ||
            value == "home" ||
            value == "download" ||
            value == "stream" ||
            value == "upload video" ||
            value == "upload videos and earn money" ||
            value == "kategori" ||
            value == "genre" ||
            value == "filter" ||
            value == "trending" ||
            value == "terbaru" ||
            value == "banyak dilihat" ||
            value == "disukai" ||
            value == "banyak dikomentari" ||
            value == "durasi panjang" ||
            value == "random" ||
            value == "semua kategori" ||
            value == "semua genre" ||
            value == "loading content..." ||
            value.contains("skeleton") ||
            value.contains("theporndude") ||
            value.contains("report content")
    }

    private fun String.cleanEscaped(): String {
        return this
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
        return this
            .replace(Regex("""^\s*Nonton\s+Video\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+Indo\s*AV.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Indo\s*AV.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':')
            .trim()
    }
}