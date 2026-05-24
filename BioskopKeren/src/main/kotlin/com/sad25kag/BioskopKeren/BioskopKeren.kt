package com.sad25kag.BioskopKeren

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class BioskopKerenPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BioskopKeren())
    }
}

class BioskopKeren : MainAPI() {
    override var mainUrl = "https://kebioskop21.cfd"
    override var name = "BioskopKeren"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "category/movie/" to "Movie",
        "category/box-office/" to "Box Office",
        "category/serial-asia/" to "TV Series Asia",
        "category/k-movie/" to "Film Korea",
        "category/korea/" to "Drama Korea",
        "category/west/" to "TV Series West",
        "category/action/" to "Action",
        "category/adventure/" to "Adventure",
        "category/animasi/" to "Animation",
        "category/biography/" to "Biography",
        "category/comedy/" to "Comedy",
        "category/crime/" to "Crime",
        "category/drama/" to "Drama",
        "category/family/" to "Family",
        "category/fantasy/" to "Fantasy",
        "category/history/" to "History",
        "category/horor/" to "Horror",
        "category/mystery/" to "Mystery",
        "category/romance/" to "Romance",
        "category/sci-fi/" to "Sci-Fi",
        "category/sport/" to "Sport",
        "category/thriller/" to "Thriller",
        "category/mandarin/" to "Chinese",
        "category/india/" to "Hindi",
        "category/japan/" to "Japan",
        "category/thailand/" to "Thailand",
        "category/film-tahun-2025-terbaru/" to "2025",
        "category/2024/" to "2024",
        "category/2023/" to "2023",
        "category/2022/" to "2022",
        "category/2021/" to "2021",
        "category/2020/" to "2020"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val siteHosts = listOf(
        "kebioskop21.cfd",
        "bioskop-keren.com",
        "bioskopkeren.now",
        "bioskop-keren.com.now"
    )

    private val knownExtractorHosts = listOf(
        "short.icu", "abyss.to", "abysscdn", "filemoon", "streamwish", "wishfast",
        "dood", "streamtape", "vidhide", "vidguard", "voe", "mixdrop", "mp4upload",
        "lulustream", "lulu", "hglink", "hgcloud", "acefile", "krakenfiles",
        "drive.google", "ok.ru", "odnoklassniki", "terabox", "mega.nz",
        "vidsrc", "embed.su", "2embed", "rivestream", "vidlink", "autoembed"
    )

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim('/')
        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = headers, timeout = 30L).document
        val items = parseCards(document).distinctBy { it.url }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = hasNextPage(document, page)
        )
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(Next), .nav-links a:contains(Next), " +
                ".page-numbers.next, a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            "article:has(a)", ".post:has(a)", ".item:has(a)", ".movie:has(a)", ".series:has(a)",
            ".ml-item:has(a)", ".result-item:has(a)", ".list-film:has(a)", ".film-list:has(a)",
            ".items article:has(a)", ".content article:has(a)", ".grid article:has(a)", ".box:has(a)",
            ".moviefilm:has(a)", ".movief:has(a)", "h2 a[href]", "h3 a[href]", "a[href]:has(img)"
        ).joinToString(", ")

        document.select(selectors).forEach { element ->
            if (element.isNoiseElement()) return@forEach
            element.toSearchResult()?.let { item -> results[item.url] = item }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true) && hasAttr("href")) {
            this
        } else {
            selectFirst("h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href]:has(img), a[href]")
                ?: return null
        }

        val href = normalizeUrl(anchor.attr("href"), mainUrl)
        if (!isSiteUrl(href) || isBlockedUrl(href) || isAdUrl(href)) return null

        val box = listOfNotNull(this, parent(), parent()?.parent()).firstOrNull { it.selectFirst("img, [style*=url]") != null } ?: this
        val image = box.selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(image?.getImageAttr() ?: box.getBackgroundImage())?.takeIf { !isBadImage(it) }

        val rawTitle = listOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            box.selectFirst("h1, h2, h3, .entry-title, .title, .judul, .movie-title")?.text(),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
            ?: return null

        val title = rawTitle.cleanTitle()
        if (title.length < 2 || title.isUiText()) return null

        val type = guessType(href, box.text(), title)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(box.text())
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(box.text())
            }
        }
    }

    private fun isSiteUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return siteHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.replace(Regex("""^https?://[^/]+/?""", RegexOption.IGNORE_CASE), "").trim('/').lowercase()
        if (path.isBlank()) return true
        val blocked = listOf(
            "category/", "genre/", "country/", "year/", "tag/", "author/", "page/", "search",
            "wp-content/", "wp-json/", "wp-admin/", "dmca", "privacy", "contact", "sitemap", "feed"
        )
        return blocked.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
        val document = app.get(url, headers = headers, timeout = 30L).document
        return newSearchResponseList(parseCards(document).distinctBy { it.url }, hasNextPage(document, page))
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items
    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, timeout = 30L).document
        val text = document.text()
        val meta = parseMetadata(document)

        val title = document.selectFirst("h1.entry-title, h1, meta[property=og:title]")?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.text()
        }?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').replace("-", " ").cleanTitle()

        val poster = getPoster(document)
        val plot = document.selectFirst(
            "meta[property=og:description], meta[name=description], .entry-content p, .sinopsis p, " +
                ".summary p, .desc p, .description p, .entry-content, .sinopsis, .summary, .desc, .description"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.length > 30 }

        val year = meta["Year"]?.toIntOrNull() ?: meta["Tahun"]?.toIntOrNull() ?: extractYear(title) ?: extractYear(text)
        val duration = parseDuration(meta["Duration"] ?: meta["Durasi"] ?: text)
        val rating = meta["IMDb"] ?: meta["Rating"] ?: parseRating(text)
        val imdbId = Regex("""tt\d{6,10}""", RegexOption.IGNORE_CASE).find(text)?.value

        val tags = (document.select("a[href*='/genre/'], a[href*='/tag/'], a[href*='/category/']").map { it.text().trim() } + meta["Genre"].orEmpty().split(","))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()

        val actors = (document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/pemain/']").map { it.text().trim() } + meta["Cast"].orEmpty().split(","))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be'], iframe[src*='youtube.com'], iframe[src*='youtu.be']")
            ?.let { it.attr("href").ifBlank { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }

        val recommendations = document.select(".related article:has(a), .film-terkait article:has(a), .items article:has(a), .content article:has(a), article:has(a)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodes = parseEpisodeLinks(document, url, poster, plot, duration)
        val type = when {
            url.contains("series", true) -> TvType.TvSeries
            text.contains("Season", true) || text.contains("Episode", true) || episodes.size > 1 -> if (text.contains("Korea", true)) TvType.AsianDrama else TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type == TvType.Movie && episodes.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                addImdbId(imdbId)
            }
        } else {
            newTvSeriesLoadResponse(title, url, type, episodes.ifEmpty {
                listOf(newEpisode(url) {
                    name = title
                    episode = 1
                    posterUrl = poster
                    description = plot
                    duration?.let { runTime = it }
                })
            }) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseEpisodeLinks(document: Document, currentUrl: String, poster: String?, plot: String?, duration: Int?): List<Episode> {
        val links = linkedMapOf<String, Episode>()
        document.select(
            "a[href*='episode'], a[href*='eps'], a[href*='season'], .episodios a[href], .episodes a[href], " +
                ".eplister a[href], .les-content a[href], .season a[href], .serial a[href]"
        ).forEachIndexed { index, element ->
            val href = normalizeUrl(element.attr("href"), currentUrl)
            if (!isSiteUrl(href) || isBlockedUrl(href)) return@forEachIndexed
            val label = element.text().trim().cleanTitle()
            val epNumber = extractEpisodeNumber(label, href) ?: index + 1
            links[href] = newEpisode(href) {
                name = label.ifBlank { "Episode $epNumber" }
                episode = epNumber
                season = extractSeasonNumber(label, href)
                posterUrl = poster
                description = plot
                duration?.let { runTime = it }
            }
        }
        return links.values.sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = mutableSetOf<String>()
        val directLinks = linkedSetOf<Pair<String, String>>()
        val embedQueue = ArrayDeque<Pair<String, String>>()
        var found = false

        fun queue(raw: String?, baseUrl: String, referer: String = baseUrl) {
            val fixed = normalizeUrl(raw.orEmpty().cleanEscaped(), baseUrl).trim()
            if (fixed.isBlank() || isAdUrl(fixed) || isBadPlayableUrl(fixed)) return
            when {
                isDirectMedia(fixed) -> directLinks.add(fixed to referer)
                fixed.startsWith("http", true) && isLikelyPlayable(fixed) -> embedQueue.add(fixed to referer)
            }
        }

        suspend fun parsePage(pageUrl: String, referer: String): String {
            val response = app.get(pageUrl, headers = headers, referer = referer, timeout = 30L)
            val document = response.document
            val html = response.text.cleanEscaped()
            extractSubtitles(pageUrl, html, subtitleCallback)

            document.select(
                "iframe#player[src], iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], " +
                    "source[src], video[src], video source[src], a[data-video], [data-video], [data-src], [data-url], [data-file]"
            ).forEach { element ->
                if (element.isNoiseElement()) return@forEach
                val label = element.text().lowercase()
                val attrs = listOf("data-video", "data-url", "data-file", "data-litespeed-src", "data-src", "src", "href")
                attrs.forEach { attr -> queue(element.attr(attr), pageUrl) }
                if (label.contains("trailer") || label.contains("iklan") || label.contains("advertisement")) return@forEach
            }

            document.select("a[href]").forEach { element ->
                if (element.isNoiseElement()) return@forEach
                val href = element.attr("href")
                val label = element.text().lowercase()
                if (href.startsWith("#") || href.startsWith("javascript", true) || label.contains("trailer")) return@forEach
                if (isLikelyPlayable(href) || isLikelyPlayableText(label)) queue(href, pageUrl)
            }

            extractPlayableUrls(html).forEach { queue(it, pageUrl) }
            return html
        }

        parsePage(data, mainUrl)

        var safety = 0
        while (embedQueue.isNotEmpty() && safety++ < 90) {
            val (embed, referer) = embedQueue.removeFirst()
            if (!visited.add(embed) || isAdUrl(embed) || isBadPlayableUrl(embed)) continue

            if (isDirectMedia(embed)) {
                directLinks.add(embed to referer)
                continue
            }

            if (isKnownExtractorHost(embed)) {
                val success = runCatching { loadExtractor(embed, referer, subtitleCallback, callback) }.getOrDefault(false)
                if (success) found = true
            }

            val nested = when {
                embed.contains("/apidrive.php", true) -> resolveApiDrivePage(embed, referer)
                shouldCrawlEmbed(embed) -> runCatching { parsePage(embed, referer) }.getOrDefault("")
                else -> ""
            }
            if (nested.isNotBlank()) extractPlayableUrls(nested).forEach { queue(it, embed, embed) }
        }

        directLinks.distinct().forEach { (link, referer) ->
            if (!isAdUrl(link) && !isBadPlayableUrl(link)) {
                emitDirectLink(link, referer, callback)
                found = true
            }
        }

        return found
    }

    private suspend fun resolveApiDrivePage(url: String, referer: String): String {
        val pages = mutableListOf<String>()
        runCatching { app.get(url, headers = headers, referer = referer, timeout = 30L).text.cleanEscaped() }.getOrNull()?.let { pages.add(it) }
        val postVariants = listOf(
            mapOf("play" to "play"),
            mapOf("play" to "Play"),
            mapOf("play" to "1"),
            mapOf("submit" to "play")
        )
        postVariants.forEach { payload ->
            runCatching { app.post(url, data = payload, headers = headers, referer = referer, timeout = 30L).text.cleanEscaped() }.getOrNull()?.let { pages.add(it) }
        }
        return pages.joinToString("\n")
    }

    private suspend fun emitDirectLink(link: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHlsLike(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(link)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.mkv|apidrive\.php)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped() }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex("""(?:file|src|source|url|videoSource|videoUrl|embedUrl|embed_url|data-video|data-src|data-file)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains("apidrive.php", true) || isKnownHost(it) }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex("""https?://[^"'\\\s<>]+?(?:apidrive\.php|embed|player|stream|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|acefile|krakenfiles|gdrive|drive\.google|ok\.ru|odnoklassniki|terabox|mega|abyss|short\.icu)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex("""const\s+datas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { encoded ->
                val decoded = runCatching { String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)) }.getOrNull().orEmpty()
                val slug = Regex(""""slug"\s*:\s*"([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.getOrNull(1)
                if (!slug.isNullOrBlank()) {
                    urls.add("https://short.icu/$slug")
                    urls.add("https://abyss.to/?v=$slug")
                    urls.add("https://abysscdn.com/?v=$slug")
                }
            }

        Regex("""[?&]v=([A-Za-z0-9_-]{8,})""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { slug ->
                urls.add("https://short.icu/$slug")
                urls.add("https://abyss.to/?v=$slug")
            }

        return urls.filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
    }

    private fun extractSubtitles(pageUrl: String, html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        Regex("""[?&]sub=([^"'&<>\s]+)""", RegexOption.IGNORE_CASE)
            .findAll("$pageUrl\n$html")
            .map { runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrDefault(it.groupValues[1]) }
            .map { normalizeUrl(it, pageUrl) }
            .filter { it.contains(".srt", true) || it.contains(".vtt", true) }
            .distinct()
            .forEach { subtitleCallback.invoke(SubtitleFile("Indonesia", it)) }
    }

    private fun parseMetadata(document: Document): Map<String, String> {
        val output = linkedMapOf<String, String>()
        document.select("li, p, div, span").forEach { element ->
            val line = element.text().replace(Regex("""\s+"""), " ").trim()
            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@forEach
            val key = parts[0].trim()
            val value = parts[1].trim()
            if (key.matches(Regex("""(?i)(genre|cast|year|tahun|duration|durasi|type|country|rating|imdb|score|director|episode|season)""")) && value.isNotBlank() && value.length < 500) {
                output[key] = value
            }
        }
        return output
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(document.selectFirst("meta[property=og:image], meta[name=twitter:image], .poster img, .thumb img, .post-thumbnail img, article img, img.wp-post-image, img")?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.getImageAttr()
        })?.takeIf { !isBadImage(it) }
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

    private fun Element.getBackgroundImage(): String? {
        return Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.takeIf { it.isNotBlank() && !isBadImage(it) }
    }

    private fun Element.isNoiseElement(): Boolean {
        val value = listOf(id(), className(), parent()?.id(), parent()?.className(), parent()?.parent()?.id(), parent()?.parent()?.className())
            .joinToString(" ")
            .lowercase()
        return listOf("sidebar", "widget", "iklan", "advert", "ads", "banner", "share", "footer", "header", "menu", "navbar", "related-tags")
            .any { value.contains(it) }
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()
        return knownExtractorHosts.any { value.contains(it) } || value.contains("streaming.kebioskop21.pro") || value.contains("apidrive.php") || value.contains("embed") || value.contains("player") || value.contains("stream")
    }

    private fun isKnownExtractorHost(url: String): Boolean {
        val value = url.lowercase()
        return knownExtractorHosts.any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean = url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains("apidrive.php", true) || isKnownHost(url)
    private fun isLikelyPlayableText(text: String): Boolean = listOf("download", "stream", "nonton", "watch", "server", "play", "360p", "480p", "720p", "1080p").any { text.contains(it) }

    private fun shouldCrawlEmbed(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("streaming.kebioskop21.pro") || lower.contains("/apidrive.php") || lower.contains("embed") || lower.contains("player") || lower.contains("short.icu") || lower.contains("abyss")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase().substringBefore("?")
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || url.contains("googlevideo.com/videoplayback", true) || url.contains("video-downloads.googleusercontent.com", true)
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        if (clean.isBlank()) return ""
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                "$origin$clean"
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() || value.startsWith("data:image") || value.contains("blank") || value.contains("placeholder") || value.contains("default") || value.contains("no-image") || value.contains("noimage") || value.contains("loader") || value.contains("loading") || value.contains("lazy") || value.contains("spacer") || value.contains("logo") || value.contains("favicon") || value.contains("banner") || value.endsWith(".svg")
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "vast", "preroll", "doubleclick", "googlesyndication", "adsbygoogle", "banner", "pasang-iklan",
            "groggedrotl", "leatmansures", "decafeligiblyhad", "histats", "googletagmanager", "google-analytics",
            "cloudflareinsights", "pixel.morphify", "ad-placeholder", "empty-without", "without-you", "lokasi-iklan",
            "/images/ad", "/img/ad", "advertisement", "adserver", "popads", "onclick"
        ).any { value.contains(it) }
    }

    private fun isBadPlayableUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.endsWith(".js") || value.endsWith(".css") || value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".png") || value.endsWith(".webp") || value.endsWith(".gif") || value.endsWith(".svg") ||
            (value.contains("/wp-content/uploads/") && !value.contains(".mp4") && !value.contains(".m3u8")) ||
            value.contains("whatsapp.com") || value.contains("facebook.com") || value.contains("twitter.com") || value.contains("x.com/") || value.contains("mailto:")
    }

    private fun guessType(url: String, text: String, title: String): TvType {
        val value = "$url $text $title"
        return when {
            value.contains("korea", true) -> TvType.AsianDrama
            value.contains("series", true) || value.contains("season", true) || value.contains("episode", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractYear(text: String?): Int? = Regex("""\b(19|20)\d{2}\b""").find(text.orEmpty())?.value?.toIntOrNull()
    private fun parseDuration(text: String?): Int? = Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE).find(text.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun parseRating(text: String): String? = Regex("""(?:imdb|rating|score)?\s*([0-9](?:\.[0-9])?|10(?:\.0)?)\s*/\s*10""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)
    private fun extractEpisodeNumber(text: String, href: String): Int? = Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun extractSeasonNumber(text: String, href: String): Int? = Regex("""(?:season|s)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun isHlsLike(url: String): Boolean = url.contains(".m3u8", true)

    private fun qualityFromUrl(url: String): Int = when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("540", true) || url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""^\s*Permalink\s+to:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\s*Nonton\s+(Film|Movie)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+ke?Bioskop21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+BioskopKeren.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.isUiText(): Boolean {
        val v = trim().lowercase()
        if (v.isBlank()) return true
        return listOf("tonton", "watch", "trailer", "download", "home", "movie", "movies", "tv series", "search", "hd", "sd", "cam", "web-dl", "advertisement", "sign up", "login").any { v == it }
    }
}
