package com.nonton01

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
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
import java.net.URLEncoder

class Nonton01 : MainAPI() {
    override var mainUrl = "https://01ntn.cc"
    override var name = "01Nonton"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val baseFallbacks = listOf(
        "https://01ntn.cc",
        "https://01ntn.link",
        "https://45.14.224.51",
        "https://01nonton.com"
    )

    private val providerHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "movies" to "Movies",
        "film-semi" to "Film Semi",
        "drakor" to "Drakor",
        "dracin" to "Dracin",

        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/animation" to "Animation",
        "genre/biography" to "Biography",
        "genre/comedy" to "Comedy",
        "genre/crime" to "Crime",
        "genre/documentary" to "Documentary",
        "genre/drama" to "Drama",
        "genre/family" to "Family",
        "genre/fantasy" to "Fantasy",
        "genre/history" to "History",
        "genre/horror" to "Horror",
        "genre/music" to "Music",
        "genre/mystery" to "Mystery",
        "genre/romance" to "Romance",
        "genre/science-fiction" to "Sci-Fi",
        "genre/thriller" to "Thriller",
        "genre/war" to "War",
        "genre/western" to "Western",

        "country/indonesia" to "Indonesia",
        "country/korea" to "Korea",
        "country/china" to "China",
        "country/japan" to "Japan",
        "country/thailand" to "Thailand",
        "country/india" to "India"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = getWorkingBaseUrl(request.data)
        val url = buildPageUrl(base, request.data, page)
        val document = app.get(url, headers = providerHeaders, timeout = 25L).document

        val home = parseSearchCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = hasNextPage(document, page)
        )
    }

    private suspend fun getWorkingBaseUrl(path: String = ""): String {
        val current = mainUrl.trimEnd('/')
        val candidates = (listOf(current) + baseFallbacks).distinct()

        for (base in candidates) {
            val url = buildPageUrl(base, path, 1)

            val ok = runCatching {
                val document = app.get(
                    url,
                    headers = providerHeaders,
                    timeout = 12L
                ).document

                val cards = parseSearchCards(document)
                cards.isNotEmpty() || document.selectFirst("form[role=search], input[name=s], .search-form") != null
            }.getOrDefault(false)

            if (ok) {
                mainUrl = base
                return base
            }
        }

        return current
    }

    private fun buildPageUrl(
        baseUrl: String,
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            page <= 1 && cleanPath.isBlank() -> baseUrl
            page <= 1 -> "$baseUrl/$cleanPath/"
            cleanPath.isBlank() -> "$baseUrl/page/$page/"
            else -> "$baseUrl/$cleanPath/page/$page/"
        }
    }

    private fun parseSearchCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a[href]):has(img), " +
                ".post:has(a[href]):has(img), " +
                ".item:has(a[href]):has(img), " +
                ".movie:has(a[href]):has(img), " +
                ".film:has(a[href]):has(img), " +
                ".ml-item:has(a[href]):has(img), " +
                ".grid-item:has(a[href]):has(img), " +
                ".box:has(a[href]):has(img), " +
                ".result-item:has(a[href]):has(img), " +
                ".latest-post:has(a[href]):has(img), " +
                ".content article:has(a[href]), " +
                ".movie-list article:has(a[href]), " +
                ".movies-list article:has(a[href]), " +
                "div[class*=movie]:has(a[href]):has(img), " +
                "div[class*=film]:has(a[href]):has(img), " +
                "div[class*=poster]:has(a[href]):has(img), " +
                "div[class*=thumb]:has(a[href]):has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                "h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]:has(img)"
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
                "a[href][title], " +
                    "h2 a[href], " +
                    "h3 a[href], " +
                    ".title a[href], " +
                    ".entry-title a[href], " +
                    ".movie-title a[href], " +
                    ".film-title a[href], " +
                    ".poster a[href], " +
                    ".thumb a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlSafe(anchor.attr("href"))
        if (!href.startsWith("http", true)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".movie-title")?.text(),
            selectFirst(".film-title")?.text(),
            anchor.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = selectFirst(
            "img[data-src], " +
                "img[data-lazy-src], " +
                "img[data-original], " +
                "img[data-srcset], " +
                "img[srcset], " +
                "img[src]"
        )?.getImageAttr()
            ?.let { fixUrlSafe(it, href) }
            ?.takeIf { !isBadImage(it) }

        return newMovieSearchResponse(
            title,
            href,
            getTypeFromUrl(href, title, emptyList())
        ) {
            posterUrl = poster
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrDefault(url)
            .lowercase()
            .trimEnd('/')

        if (path.isBlank() || path == "/") return true

        val exactBlocked = setOf(
            "/movies",
            "/film-semi",
            "/drakor",
            "/dracin",
            "/genre",
            "/country",
            "/year",
            "/tag",
            "/category",
            "/privacy",
            "/dmca",
            "/contact",
            "/terms",
            "/about",
            "/download-apk"
        )

        if (exactBlocked.contains(path)) return true

        val prefixBlocked = listOf(
            "/page/",
            "/search",
            "/feed",
            "/wp-json",
            "/wp-content",
            "/wp-admin",
            "/author/",
            "/category/"
        )

        return prefixBlocked.any { path.startsWith(it) }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val base = getWorkingBaseUrl()
        val q = URLEncoder.encode(keyword, "UTF-8")
        val searchUrls = listOf(
            if (page <= 1) "$base/?s=$q" else "$base/page/${page.coerceAtLeast(1)}/?s=$q",
            if (page <= 1) "$base/search/$q/" else "$base/search/$q/page/${page.coerceAtLeast(1)}/"
        )

        var bestResults: List<SearchResponse> = emptyList()
        var hasNext = false

        for (searchUrl in searchUrls) {
            val document = runCatching {
                app.get(searchUrl, headers = providerHeaders, timeout = 25L).document
            }.getOrNull() ?: continue

            val results = parseSearchCards(document)
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

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = fixUrlSafe(url)
        val document = app.get(fixedUrl, headers = providerHeaders, timeout = 25L).document

        val title = document.selectFirst(
            "h1, " +
                "h1.entry-title, " +
                ".entry-title, " +
                ".movie-title, " +
                ".film-title, " +
                ".video-title, " +
                "meta[property=og:title], " +
                "meta[name=twitter:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst(
            "meta[property=og:image], " +
                "meta[name=twitter:image], " +
                "video[poster], " +
                ".poster img, " +
                ".thumb img, " +
                ".player img, " +
                ".entry-content img, " +
                "img.wp-post-image"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                element.hasAttr("poster") -> element.attr("poster")
                else -> element.getImageAttr()
            }
        }?.let { fixUrlSafe(it, fixedUrl) }
            ?.takeIf { !isBadImage(it) }

        val plot = document.selectFirst(
            "meta[property=og:description], " +
                "meta[name=description], " +
                ".entry-content p, " +
                ".description, " +
                ".sinopsis, " +
                ".summary, " +
                ".movie-desc, " +
                ".storyline"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanPlot()

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/category/'], " +
                "a[href*='/tag/'], " +
                ".genres a, " +
                ".genre a, " +
                ".tags a, " +
                ".category a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        val recommendations = document.select(
            ".related article, " +
                ".related-posts article, " +
                ".related a[href], " +
                ".post:has(a[href]):has(img), " +
                ".item:has(a[href]):has(img), " +
                ".movie:has(a[href]):has(img), " +
                ".film:has(a[href]):has(img)"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.filter { it.url != fixedUrl }
            .distinctBy { it.url }

        return newMovieLoadResponse(
            title,
            fixedUrl,
            getTypeFromUrl(fixedUrl, title, tags),
            fixedUrl
        ) {
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
        val pageUrl = fixUrlSafe(data)
        val response = app.get(
            pageUrl,
            headers = providerHeaders,
            referer = mainUrl,
            timeout = 25L
        )

        val document = response.document
        val html = response.text.cleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectPlayableCandidates(
            text = html,
            document = document,
            baseUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        collectDooPlayAjaxEmbeds(
            pageUrl = pageUrl,
            document = document,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            collectPlayableCandidates(
                text = unpacked.cleanEscaped(),
                document = Jsoup.parse(unpacked),
                baseUrl = pageUrl,
                directLinks = directLinks,
                embedLinks = embedLinks
            )
        }

        var found = emitDirectLinks(
            links = directLinks,
            referer = pageUrl,
            callback = callback
        )

        val nestedDirect = linkedSetOf<String>()
        val nestedEmbeds = linkedSetOf<String>()

        for (embed in embedLinks.distinct().take(24)) {
            val success = runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback, callback)
            }.getOrDefault(false)

            if (success) found = true

            val nested = resolveNestedLinks(embed, pageUrl)

            nested.first.forEach { nestedDirect.add(it) }
            nested.second.forEach { nestedEmbeds.add(it) }
        }

        if (emitDirectLinks(nestedDirect, pageUrl, callback)) {
            found = true
        }

        for (embed in nestedEmbeds.distinct().take(16)) {
            val success = runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback, callback)
            }.getOrDefault(false)

            if (success) found = true

            val deep = resolveNestedLinks(embed, pageUrl)
            if (emitDirectLinks(deep.first, embed, callback)) {
                found = true
            }
        }

        return found
    }

    private suspend fun collectDooPlayAjaxEmbeds(
        pageUrl: String,
        document: Document,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val origin = getOrigin(pageUrl)
        val ajaxEndpoints = listOf(
            "$origin/wp-admin/admin-ajax.php",
            "$origin/wp-admin/admin-ajax.php?action=doo_player_ajax"
        ).distinct()

        val players = document.select(
            "[data-post][data-nume][data-type], " +
                "[data-id][data-nume][data-type], " +
                ".dooplay_player_option[data-post], " +
                ".dooplay_player_option[data-id], " +
                ".playex[data-post], " +
                ".playex[data-id]"
        )

        players.forEach { element ->
            val post = element.attr("data-post")
                .ifBlank { element.attr("data-id") }
                .trim()
            val nume = element.attr("data-nume")
                .ifBlank { element.attr("data-server") }
                .ifBlank { "1" }
                .trim()
            val type = element.attr("data-type")
                .ifBlank { "movie" }
                .trim()

            if (post.isBlank()) return@forEach

            ajaxEndpoints.forEach { endpoint ->
                val text = runCatching {
                    app.post(
                        url = endpoint,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = providerHeaders + mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "application/json,text/html,text/plain,*/*",
                            "Origin" to origin
                        ),
                        referer = pageUrl,
                        timeout = 15L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                if (text.isBlank()) return@forEach

                collectPlayableCandidates(
                    text = text,
                    document = Jsoup.parse(text),
                    baseUrl = pageUrl,
                    directLinks = directLinks,
                    embedLinks = embedLinks
                )

                extractIframeLikeUrls(text).forEach {
                    addCandidate(it, pageUrl, directLinks, embedLinks)
                }
            }
        }

        document.select(
            "[data-embed], [data-iframe], [data-video], [data-url], [data-link], [data-src]"
        ).forEach { element ->
            val raw = element.attr("data-embed")
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-src") }
                .trim()

            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(
        embedUrl: String,
        referer: String
    ): Pair<List<String>, List<String>> {
        if (shouldSkipPlaybackUrl(embedUrl)) return emptyList<String>() to emptyList()

        val response = runCatching {
            app.get(
                embedUrl,
                headers = providerHeaders + mapOf(
                    "Referer" to referer,
                    "Origin" to getOrigin(referer),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                referer = referer,
                timeout = 20L
            )
        }.getOrNull() ?: return emptyList<String>() to emptyList()

        val direct = linkedSetOf<String>()
        val embeds = linkedSetOf<String>()
        val html = response.text.cleanEscaped()
        val document = response.document

        collectPlayableCandidates(
            text = html,
            document = document,
            baseUrl = embedUrl,
            directLinks = direct,
            embedLinks = embeds
        )

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            collectPlayableCandidates(
                text = unpacked.cleanEscaped(),
                document = Jsoup.parse(unpacked),
                baseUrl = embedUrl,
                directLinks = direct,
                embedLinks = embeds
            )
        }

        return direct.toList() to embeds.toList()
    }

    private fun collectPlayableCandidates(
        text: String,
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        extractVideoUrls(text).forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        extractIframeLikeUrls(text).forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        document.select(
            "video source[src], " +
                "source[src], " +
                "video[src], " +
                "video[poster], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-lazy-src], " +
                "embed[src], " +
                "object[data], " +
                "a[href], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file], " +
                "[data-url], " +
                "[data-link], " +
                "[data-embed], " +
                "[data-iframe]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("href") }
                .trim()

            val textLabel = element.text().lowercase()

            if (
                raw.isNotBlank() &&
                !raw.startsWith("#") &&
                !raw.startsWith("javascript", true) &&
                !textLabel.contains("trailer")
            ) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private fun addCandidate(
        rawUrl: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (rawUrl.isBlank()) return

        val fixed = fixUrlSafe(rawUrl.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || shouldSkipPlaybackUrl(fixed)) return

        when {
            fixed.contains(".m3u8", true) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) && isLikelyEmbed(fixed) -> embedLinks.add(fixed)
        }
    }

    private fun emitDirectLinks(
        links: Collection<String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false

        links.distinct().forEach { raw ->
            val link = raw.replace(".txt", ".m3u8")
            if (shouldSkipPlaybackUrl(link)) return@forEach

            if (link.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to getOrigin(referer),
                        "Accept" to "*/*"
                    )
                ).forEach(callback)
                emitted = true
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
                            "Origin" to getOrigin(referer),
                            "Accept" to "*/*"
                        )
                    }
                )
                emitted = true
            }
        }

        return emitted
    }

    private fun extractIframeLikeUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.groupValues[1] }
            .forEach { urls.add(it) }

        Regex(
            """"(?:embed|iframe|player|html|url|link|source)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.groupValues[1].cleanEscaped() }
            .filter {
                it.contains("iframe", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true) ||
                    it.startsWith("http", true) ||
                    it.startsWith("/", true)
            }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|jeniusplay|majorplay|dood|streamtape|filemoon|vidhide|voe|mixdrop|streamwish|earnvid|filelions|vidguard|lulustream|lulu|mp4upload)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".pagination a:contains(Berikutnya), " +
                ".pagination a[href*='/page/${page + 1}'], " +
                "a[href*='/page/${page + 1}/'], " +
                "a[href*='paged=${page + 1}']"
        ) != null
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .firstOrNull {
                    it.isNotBlank() && !isBadImage(it)
                }
        }

        return fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").ifBlank { attr("data-src") }.takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").ifBlank { attr("data-lazy-src") }.takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").ifBlank { attr("data-original") }.takeIf { it.isNotBlank() }
            ?: attr("abs:src").ifBlank { attr("src") }.takeIf { it.isNotBlank() }
    }

    private fun fixUrlSafe(
        url: String,
        baseUrl: String = mainUrl
    ): String {
        return normalizeUrl(url, baseUrl)
    }

    private fun isLikelyEmbed(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "/embed/",
            "/player/",
            "jeniusplay",
            "majorplay",
            "dood",
            "streamtape",
            "filemoon",
            "vidhide",
            "voe",
            "mixdrop",
            "streamwish",
            "wish",
            "earnvid",
            "filelions",
            "vidguard",
            "lulustream",
            "lulu",
            "mp4upload",
            "short.icu",
            "streamhub",
            "drive.google"
        ).any { value.contains(it) }
    }

    private fun shouldSkipPlaybackUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("mailto:") ||
            value.contains("javascript:") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("google-analytics") ||
            value.contains("googletagmanager") ||
            value.contains("doubleclick") ||
            value.contains("histats") ||
            value.contains("ads.") ||
            value.contains("/ads/") ||
            value.contains("banner") ||
            value.contains("trailer")
    }

    private fun getTypeFromUrl(
        url: String,
        title: String,
        tags: List<String>
    ): TvType {
        val check = (url + " " + title + " " + tags.joinToString(" ")).lowercase()

        return when {
            check.contains("drakor") ||
                check.contains("dracin") ||
                check.contains("drama korea") ||
                check.contains("drama china") ||
                check.contains("drama cina") -> TvType.AsianDrama

            check.contains("series") ||
                check.contains("tv series") ||
                check.contains("episode") ||
                check.contains("season") -> TvType.TvSeries

            else -> TvType.Movie
        }
    }

    private fun isBadTitle(text: String): Boolean {
        val value = text.cleanTitle().lowercase()

        return value.isBlank() ||
            value == "home" ||
            value == "next" ||
            value == "previous" ||
            value == "movies" ||
            value == "film semi" ||
            value == "drakor" ||
            value == "dracin" ||
            value == "search" ||
            value == "terbaru" ||
            value == "genre" ||
            value == "country" ||
            value == "download apk" ||
            value == "link terbaru"
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

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+01Nonton.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+01Nonton.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Nonton\s+Movie\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', ':')
            .trim()
    }

    private fun String.cleanPlot(): String? {
        return this
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
