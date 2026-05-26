package com.indo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Rebahin : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.autos"
    override var name = "Rebahin"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Single visible entry. The actual homepage rows are built below from the source routes,
    // so Cloudstream renders poster rows instead of a plain text category catalog.
    override val mainPage = mainPageOf("/" to "Featured")

    private data class HomeRoute(val title: String, val path: String)

    private val homeRoutes = listOf(
        HomeRoute("Featured", "/"),
        HomeRoute("Film Terbaru", "/nonton-drama/"),
        HomeRoute("Movie", "/movies/"),
        HomeRoute("Serial TV", "/series/"),
        HomeRoute("Populer", "/genre/populer/"),
        HomeRoute("Romance", "/genre/romance/"),
        HomeRoute("Drama", "/genre/drama/"),
        HomeRoute("Action", "/genre/action/"),
        HomeRoute("Sci-Fi", "/genre/sci-fi/"),
        HomeRoute("Horror", "/genre/horror/"),
        HomeRoute("Comedy", "/genre/comedy/"),
        HomeRoute("Crime", "/genre/crime/"),
        HomeRoute("Animation", "/genre/animation/"),
        HomeRoute("Drama Korea", "/genre/drama-korea/"),
        HomeRoute("Thailand Series", "/genre/thailand-series/"),
        HomeRoute("China", "/genre/drama-china/"),
        HomeRoute("Japan", "/genre/drama-jepang/")
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    private val mirrors = listOf(
        "https://rebahinxxi3.autos",
        "https://rebahinxxi3.com",
        "https://rebahinxxi3.ink",
        "https://rebahinxxi3.rest",
        "https://rebahinxxi3.cyou",
        "https://windowsxpuser.com",
        "http://178.62.98.100"
    )

    private data class ParsedItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.trim()
        val isHome = data.isBlank() || data == "/" ||
            data.equals("home", true) || data.equals("featured", true) ||
            data.contains("__home__", true) || data.removeSuffix("/") == mainUrl.removeSuffix("/")

        // Home mode: build several visual poster rows from the real source categories.
        // If one route fails, keep the remaining rows alive instead of returning a black screen.
        if (isHome && page <= 1) {
            val rows = mutableListOf<HomePageList>()
            homeRoutes.forEach { route ->
                val items = runCatching {
                    parseSearchItems(fetchDocument(route.path))
                        .distinctBy { it.url }
                        .take(24)
                }.getOrDefault(emptyList())

                if (items.isNotEmpty()) rows.add(HomePageList(route.title, items))
            }

            if (rows.isNotEmpty()) return newHomePageResponse(rows, hasNext = false)

            // Last-resort fallback: parse the homepage once. This prevents a blank provider page
            // when Cloudstream/source routing differs from the expected request data.
            val fallback = runCatching {
                parseSearchItems(fetchDocument("/"))
                    .distinctBy { it.url }
                    .take(40)
            }.getOrDefault(emptyList())

            return newHomePageResponse(
                listOf(HomePageList("Film Terbaru", fallback)),
                hasNext = fallback.isNotEmpty()
            )
        }

        val targetPath = if (isHome) "/" else data
        val doc = fetchDocument(pagedUrl(targetPath, page))
        val items = parseSearchItems(doc).distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name.ifBlank { "Rebahin" }, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val htmlResults = runCatching {
            parseSearchItems(fetchDocument("/?s=$encoded"))
        }.getOrDefault(emptyList())

        if (htmlResults.isNotEmpty()) return htmlResults

        // WordPress fallback. Some mirrors keep the REST API open even when search markup changes.
        return runCatching {
            val apiUrl = "$mainUrl/wp-json/wp/v2/search?search=$encoded&per_page=20"
            val json = app.get(apiUrl, headers = headers).text
            Regex("""\{[^{}]*"title"\s*:\s*"([^"]+)"[^{}]*"url"\s*:\s*"([^"]+)"[^{}]*\}""")
                .findAll(json)
                .mapNotNull { match ->
                    val title = match.groupValues[1].cleanText()
                    val url = match.groupValues[2].cleanEscaped()
                    if (title.isBlank() || !isValidContentUrl(url)) return@mapNotNull null
                    newMovieSearchResponse(title, url, TvType.Movie)
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.removeSuffix("/play/").removeSuffix("/play") + "/"
        val doc = fetchDocument(cleanUrl)

        val title = cleanTitle(
            doc.selectFirst("h1, h1.entry-title, .sheader .data h1, .data h1")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: "Rebahin"
        )

        val poster = firstImage(doc)
        val plot = doc.selectFirst(".wp-content p, .entry-content p, .description, .storyline, .sinopsis, .content p")
            ?.text()
            ?.cleanText()
            .orEmpty()

        val tags = doc.select("a[href*=/category/], a[href*=/genre/], .sgeneros a, .genres a, .gmr-movie-genre a, a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && it.length <= 40 }
            .distinct()

        val year = (doc.selectFirst("a[href*=/year/], .date, .release, .year")?.text()
            ?: Regex("""\b(19|20)\d{2}\b""").find(doc.text())?.value)
            ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }

        val isSeries = isSeriesDoc(doc, cleanUrl, tags)
        if (isSeries) {
            val episodes = parseEpisodes(doc).ifEmpty {
                listOf(
                    newEpisode(resolvePlayablePage(cleanUrl, doc)) {
                        this.name = title
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        return newMovieLoadResponse(title, cleanUrl, TvType.Movie, resolvePlayablePage(cleanUrl, doc)) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val safeCallback: (ExtractorLink) -> Unit = {
            found = true
            callback(it)
        }

        val pages = linkedSetOf<String>()
        pages.add(data)
        if (!data.trimEnd('/').endsWith("/play")) {
            pages.add(data.trimEnd('/') + "/play/")
        }

        for (page in pages) {
            val doc = runCatching { fetchDocument(page) }.getOrNull() ?: continue
            val referer = doc.location().ifBlank { page }
            val html = doc.html().cleanEscaped()

            extractPlayerOptions(doc).forEach { (post, nume, type) ->
                requestDooplayPlayer(post, nume, type, referer).forEach { raw ->
                    handlePlayable(raw, referer, subtitleCallback, safeCallback)
                }
            }

            extractCandidateLinks(doc, html, referer).forEach { raw ->
                handlePlayable(raw, referer, subtitleCallback, safeCallback)
            }
        }

        return found
    }

    private suspend fun handlePlayable(
        raw: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = normalizeUrl(raw, referer)
            .replace("/embed-", "/")
            .trim()

        if (fixed.isBlank() || !fixed.startsWith("http", true) || isBadLink(fixed)) return

        when {
            isDirectVideo(fixed) -> emitDirectLink(fixed, referer, callback)
            else -> runCatching { loadExtractor(fixed, referer, subtitleCallback, callback) }
        }
    }

    private suspend fun emitDirectLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(url).takeIf { it != Qualities.Unknown.value }
                    ?: Qualities.Unknown.value
            }
        )
    }

    private suspend fun requestDooplayPlayer(
        post: String,
        nume: String,
        type: String,
        referer: String
    ): List<String> {
        if (post.isBlank() || nume.isBlank()) return emptyList()

        val ajaxUrls = listOf(
            "$mainUrl/wp-admin/admin-ajax.php",
            rootOf(referer) + "/wp-admin/admin-ajax.php"
        ).distinct()

        val results = linkedSetOf<String>()
        ajaxUrls.forEach { ajaxUrl ->
            runCatching {
                val response = app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type.ifBlank { "movie" }
                    ),
                    headers = headers,
                    referer = referer
                ).text.cleanEscaped()

                val json = runCatching { JSONObject(response) }.getOrNull()
                listOf(
                    json?.optString("embed_url").orEmpty(),
                    json?.optString("iframe").orEmpty(),
                    json?.optString("html").orEmpty(),
                    response
                ).flatMap { extractUrlsFromText(it) }.forEach { results.add(it) }
            }
        }
        return results.toList()
    }

    private fun extractPlayerOptions(doc: Document): List<Triple<String, String, String>> {
        val options = mutableListOf<Triple<String, String, String>>()
        doc.select("[data-post][data-nume], .dooplay_player_option, .player-option, li[id*=player-option]").forEach { el ->
            val post = el.attr("data-post").ifBlank { el.attr("data-id") }
            val nume = el.attr("data-nume").ifBlank { el.attr("data-server") }.ifBlank { el.attr("data-n") }
            val type = el.attr("data-type").ifBlank { el.attr("data-tipo") }
            if (post.isNotBlank() && nume.isNotBlank()) {
                options.add(Triple(post, nume, type.ifBlank { "movie" }))
            }
        }

        Regex("""data-post=["'](\d+)["'][^>]+data-nume=["'](\d+)["'][^>]+data-type=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(doc.html())
            .forEach { options.add(Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3])) }

        return options.distinct()
    }

    private fun extractCandidateLinks(doc: Document, html: String, referer: String): List<String> {
        val links = linkedSetOf<String>()

        doc.select(
            "iframe[src], embed[src], video[src], source[src], " +
                "a[href], [data-src], [data-url], [data-link], [data-href], [data-iframe], [data-frame], [data-video]"
        ).forEach { el ->
            listOf("src", "href", "data-src", "data-url", "data-link", "data-href", "data-iframe", "data-frame", "data-video")
                .map { el.attr(it) }
                .filter { it.isNotBlank() }
                .forEach { links.add(normalizeUrl(it, referer)) }
        }

        extractUrlsFromText(html).forEach { links.add(normalizeUrl(it, referer)) }
        extractBase64Payloads(html).forEach { payload ->
            extractUrlsFromText(payload).forEach { links.add(normalizeUrl(it, referer)) }
        }

        return links.filter { isValidCandidate(it) }.distinct()
    }

    private fun extractUrlsFromText(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.trimEnd(',', ';', ')', ']') }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrNull() }
            .forEach { urls.add(it) }

        Regex("""(?:file|url|src|link|embed_url|iframe)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1] }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun extractBase64Payloads(text: String): List<String> {
        val results = mutableListOf<String>()
        Regex("""(?:atob|Base64\.decode)\(["']([A-Za-z0-9+/=]{24,})["']\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { match ->
                runCatching {
                    String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                }.getOrNull()?.let { results.add(it.cleanEscaped()) }
            }
        return results
    }

    private fun parseSearchItems(doc: Document): List<SearchResponse> {
        val parsed = linkedMapOf<String, ParsedItem>()
        val selectors = listOf(
            "article",
            "div[id^=post-]",
            ".items article",
            ".result-item article",
            ".ml-item",
            ".movie",
            ".movies .movie",
            ".item",
            ".items .item",
            ".poster",
            ".post",
            ".post-item",
            ".box-item",
            ".halim-item",
            ".card",
            ".gmr-movie-item",
            ".gmr-item-modulepost",
            ".pelicula",
            ".module .content .items > *",
            ".movies-list > *",
            ".series-list > *",
            ".owl-item",
            ".slider .item"
        ).joinToString(",")

        doc.select(selectors).forEach { element ->
            parseItem(element)?.let { item -> parsed.putIfAbsent(item.url, item) }
        }

        // Fallback anchor scan. Rebahin often puts a Trailer button before the real Tonton Film
        // button/title link, so the parser must walk all internal anchors and use nearby poster data.
        doc.select("a[href]").forEach { anchor ->
            parseAnchorItem(anchor)?.let { item -> parsed.putIfAbsent(item.url, item) }
        }

        return parsed.values.map { item ->
            when (item.type) {
                TvType.TvSeries -> newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) {
                    this.posterUrl = item.poster
                }
                else -> newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                    this.posterUrl = item.poster
                }
            }
        }
    }

    private fun parseItem(element: Element): ParsedItem? {
        val anchor = element.select("h2 a[href], h3 a[href], h4 a[href], .title a[href], .data a[href], a[href]")
            .firstOrNull { isRealContentAnchor(it) } ?: return null
        return parseAnchorWithContainer(anchor, element)
    }

    private fun parseAnchorItem(anchor: Element): ParsedItem? {
        if (!isRealContentAnchor(anchor)) return null
        val container = listOfNotNull(
            anchor.closest("article"),
            anchor.closest(".item"),
            anchor.closest(".movie"),
            anchor.closest(".ml-item"),
            anchor.closest(".post"),
            anchor.closest(".post-item"),
            anchor.closest(".box-item"),
            anchor.closest(".card"),
            anchor.closest(".gmr-movie-item"),
            anchor.parent(),
            anchor
        ).first()
        return parseAnchorWithContainer(anchor, container)
    }

    private fun parseAnchorWithContainer(anchor: Element, element: Element): ParsedItem? {
        val url = normalizeUrl(anchor.attr("href"), mainUrl)
        if (!isValidContentUrl(url)) return null

        val titleAnchor = element.selectFirst("h1 a[href], h2 a[href], h3 a[href], h4 a[href], .title a[href], .data h2, .data h3, .tt, .entry-title")
        val rawTitle = listOf(
            titleAnchor?.attr("title").orEmpty(),
            titleAnchor?.text().orEmpty(),
            anchor.attr("title"),
            anchor.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            element.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            anchor.text(),
            element.text().lineSequence().firstOrNull().orEmpty()
        ).firstOrNull { value ->
            val clean = value.cleanText()
            clean.length > 2 &&
                !clean.equals("Tonton", true) &&
                !clean.equals("Tonton Film", true) &&
                !clean.equals("Trailer", true) &&
                !clean.equals("Lihat Semua", true)
        }.orEmpty()

        val title = cleanTitle(rawTitle)
        if (title.length < 2 || isBadTitle(title)) return null

        val poster = findImage(element) ?: findImage(anchor)
        val text = element.text()
        val type = if (text.contains("TV Show", true) ||
            text.contains("Serial TV", true) ||
            text.contains("Eps:", true) ||
            url.contains("/tv/", true) ||
            url.contains("/episode/", true)
        ) TvType.TvSeries else TvType.Movie

        return ParsedItem(title, url, poster, type)
    }

    private fun isRealContentAnchor(anchor: Element): Boolean {
        val href = normalizeUrl(anchor.attr("href"), mainUrl)
        val text = anchor.text().cleanText()
        return isValidContentUrl(href) &&
            !href.contains("youtube", true) &&
            !text.equals("Trailer", true) &&
            !text.equals("Home", true) &&
            !text.startsWith("Lihat Semua", true)
    }

    private fun findImage(element: Element): String? {
        return element.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[src], source[srcset]")?.let { img ->
            listOf("data-src", "data-lazy-src", "data-original", "data-wpfc-original-src", "src", "srcset")
                .firstNotNullOfOrNull { attr ->
                    img.attr(attr)
                        .split(",")
                        .firstOrNull()
                        ?.substringBefore(" ")
                        ?.takeIf { it.isNotBlank() }
                }?.let { normalizeUrl(it, mainUrl) }
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        return doc.select(
            "a[href*=/episode/], a[href*=/episodes/], .episodios a[href], .episodes a[href], " +
                ".se-c a[href], .se-a a[href], .eplister a[href], .episode-list a[href], " +
                ".wp-content a[href*='episode'], a[href*='/play/']"
        ).mapNotNull { a ->
            val href = normalizeUrl(a.attr("href"), mainUrl)
            val label = a.text().cleanText().ifBlank { a.attr("title").cleanText() }
            if (!isValidContentUrl(href) || label.contains("Trailer", true)) return@mapNotNull null

            val season = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("""(?:Episode|Eps?\.?|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(\d{1,4})\b""").find(label)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = label.ifBlank { "Episode ${episode ?: ""}".trim() }
                this.season = season
                this.episode = episode
            }
        }.distinctBy { it.data }
    }

    private fun isSeriesDoc(doc: Document, url: String, tags: List<String>): Boolean {
        val text = (doc.selectFirst(".sheader, .extra, .meta, .entry-content")?.text() ?: doc.text()).take(2000)
        return tags.any { it.equals("Serial TV", true) || it.equals("TV Show", true) } ||
            text.contains("TV Show", true) ||
            text.contains("Serial TV", true) ||
            text.contains("Episode", true) ||
            url.contains("/tv/", true) ||
            url.contains("/episode/", true)
    }

    private fun resolvePlayablePage(url: String, doc: Document): String {
        // rebahinxxi3 often keeps the iframe/player on the detail page itself.
        // windowsxpuser variants may use /play/. Start from detail when a player marker exists.
        if (doc.selectFirst("iframe[src], video[src], source[src], [data-post][data-nume], .player, .player-area, #player") != null) {
            return url
        }

        doc.select("a[href*='/play/'], a[href*='?player='], a[href*='watch']").firstOrNull { a ->
            !a.text().contains("Trailer", true) && !a.attr("href").contains("youtube", true)
        }?.attr("href")?.let { return normalizeUrl(it, url) }

        return if (url.trimEnd('/').endsWith("/play")) url else url.trimEnd('/') + "/play/"
    }

    private suspend fun fetchDocument(pathOrUrl: String): Document {
        val firstUrl = normalizeUrl(pathOrUrl, mainUrl)
        runCatching { return app.get(firstUrl, headers = headers).document }

        val path = pathOnly(firstUrl)
        mirrors.forEach { mirror ->
            val candidate = mirror.trimEnd('/') + path
            runCatching { return app.get(candidate, headers = headers).document }
        }

        return app.get(firstUrl, headers = headers).document
    }

    private fun pagedUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http", true)) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized
        return normalized.trimEnd('/') + "/page/$page/"
    }

    private fun normalizeUrl(raw: String, base: String): String {
        var url = raw.cleanEscaped().trim()
        if (url.isBlank() || url.startsWith("javascript:", true) || url.startsWith("#")) return ""
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return rootOf(base) + url
        if (!url.startsWith("http", true)) return rootOf(base) + "/" + url.trimStart('/')
        return url
    }

    private fun rootOf(url: String): String {
        return Regex("""https?://[^/]+""", RegexOption.IGNORE_CASE).find(url)?.value ?: mainUrl
    }

    private fun pathOnly(url: String): String {
        val path = url.substringAfter(rootOf(url), "/")
        return "/" + path.trimStart('/')
    }

    private fun firstImage(doc: Document): String? {
        return listOf(
            "meta[property=og:image]" to "content",
            ".poster img" to "src",
            ".sheader .poster img" to "src",
            "img.wp-post-image" to "src",
            ".gmr-movie-thumb img" to "src",
            "img" to "src"
        ).firstNotNullOfOrNull { (selector, attr) ->
            doc.selectFirst(selector)?.attr(attr)?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
        }
    }

    private fun cleanTitle(title: String): String {
        return title.cleanText()
            .replace(Regex("""(?i)^nonton\s+"""), "")
            .replace(Regex("""(?i)\s+(subtitle\s+indonesia|sub\s*indo|streaming|download).*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|')
    }

    private fun String.cleanText(): String {
        return this.cleanEscaped()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun isValidContentUrl(url: String): Boolean {
        if (!url.startsWith("http", true)) return false
        if (isBadLink(url)) return false
        val path = pathOnly(url).lowercase().trimEnd('/') + "/"
        if (path in setOf("/", "/movies/", "/series/", "/nonton-drama/", "/privacy/", "/dmca/", "/report/", "/pasang-iklan/")) return false
        return !listOf(
            "/category/", "/country/", "/year/", "/tag/", "/genre/", "/quality/",
            "/wp-content/", "/wp-json/", "/feed/", "/privacy", "/dmca", "/report", "/pasang-iklan"
        ).any { url.contains(it, true) }
    }

    private fun isValidCandidate(url: String): Boolean {
        if (url.isBlank() || isBadLink(url)) return false
        return url.startsWith("http", true) && !url.contains("/wp-content/uploads/", true)
    }

    private fun isBadTitle(title: String): Boolean {
        val clean = title.cleanText()
        return clean.length < 2 ||
            clean.equals("Tonton Film", true) ||
            clean.equals("Trailer", true) ||
            clean.equals("Lihat Semua", true) ||
            clean.equals("Home", true) ||
            clean.equals("HD", true) ||
            clean.equals("HDCAM", true) ||
            clean.equals("HDTS", true)
    }

    private fun isBadLink(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("facebook.com") || lower.contains("twitter.com") ||
            lower.contains("instagram.com") || lower.contains("t.me/") ||
            lower.contains("whatsapp") || lower.contains("doubleclick") ||
            lower.contains("googlesyndication") || lower.contains("/ads") ||
            lower.contains("/cdn-cgi/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".css") || lower.endsWith(".js")
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".webm") || lower.contains("videoplayback")
    }
}
