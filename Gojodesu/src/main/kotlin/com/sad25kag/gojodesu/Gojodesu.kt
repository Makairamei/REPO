package com.sad25kag.gojodesu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Gojodesu : MainAPI() {
    override var mainUrl = "https://gojodesu.com"
    override var name = "Gojodesu"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "/page/%d/" to "Latest Release",
        "/anime/?status=&type=&order=update" to "Update Anime",
        "/anime/?status=&type=&order=latest" to "Anime Terbaru",
        "/anime/?status=ongoing&type=&order=update" to "Ongoing",
        "/anime/?status=completed&type=&order=update" to "Completed",
        "/anime/?status=&type=&order=popular" to "Popular",
        "/anime/?status=&type=&order=rating" to "Rating",

        "/anime/?status=&type=tv&order=update" to "TV Series",
        "/anime/?status=&type=movie&order=update" to "Movie",
        "/anime/?status=&type=ova&order=update" to "OVA",
        "/anime/?status=&type=ona&order=update" to "ONA",
        "/anime/?status=&type=special&order=update" to "Special",
        "/anime/?status=&type=bd&order=update" to "BD",
        "/anime/?status=&type=music&order=update" to "Music",
        "/anime/?status=&type=live-action&order=update" to "Live Action",

        "/genres/action/page/%d/" to "Action",
        "/genres/adult-cast/page/%d/" to "Adult Cast",
        "/genres/adventure/page/%d/" to "Adventure",
        "/genres/award-winning/page/%d/" to "Award Winning",
        "/genres/comedy/page/%d/" to "Comedy",
        "/genres/drama/page/%d/" to "Drama",
        "/genres/ecchi/page/%d/" to "Ecchi",
        "/genres/erotica/page/%d/" to "Erotica",
        "/genres/fantasy/page/%d/" to "Fantasy",
        "/genres/girls-love/page/%d/" to "Girls Love",
        "/genres/gourmet/page/%d/" to "Gourmet",
        "/genres/horror/page/%d/" to "Horror",
        "/genres/mystery/page/%d/" to "Mystery",
        "/genres/romance/page/%d/" to "Romance",
        "/genres/school/page/%d/" to "School",
        "/genres/sci-fi/page/%d/" to "Sci-Fi",
        "/genres/shounen/page/%d/" to "Shounen",
        "/genres/slice-of-life/page/%d/" to "Slice of Life",
        "/genres/sports/page/%d/" to "Sports",
        "/genres/supernatural/page/%d/" to "Supernatural",
        "/genres/suspense/page/%d/" to "Suspense",

        "/season/spring-2026/page/%d/" to "Spring 2026",
        "/season/winter-2026/page/%d/" to "Winter 2026",
        "/season/fall-2025/page/%d/" to "Fall 2025",
        "/season/summer-2025/page/%d/" to "Summer 2025",
        "/season/spring-2025/page/%d/" to "Spring 2025",
        "/season/winter-2025/page/%d/" to "Winter 2025",
        "/season/fall-2024/page/%d/" to "Fall 2024",
        "/season/summer-2024/page/%d/" to "Summer 2024",
        "/season/spring-2024/page/%d/" to "Spring 2024",
        "/season/winter-2024/page/%d/" to "Winter 2024",
        "/season/fall-2023/page/%d/" to "Fall 2023",
        "/season/summer-2023/page/%d/" to "Summer 2023",
        "/season/spring-2023/page/%d/" to "Spring 2023",
        "/season/fall-2022/page/%d/" to "Fall 2022",
        "/season/fall-2020/page/%d/" to "Fall 2020",
        "/season/spring-2019/page/%d/" to "Spring 2019",
        "/season/fall-2016/page/%d/" to "Fall 2016",
        "/season/fall-2015/page/%d/" to "Fall 2015",
        "/season/spring-2014/page/%d/" to "Spring 2014",
        "/season/summer-2012/page/%d/" to "Summer 2012",
        "/season/fall-2006/page/%d/" to "Fall 2006",
        "/season/spring-2006/page/%d/" to "Spring 2006",
        "/season/spring-2005/page/%d/" to "Spring 2005",
        "/season/winter-2001/page/%d/" to "Winter 2001",
        "/season/fall-1999/page/%d/" to "Fall 1999"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val documents = if (request.data.contains("type=ova", true)) {
            val fallbackTypes = listOf("ova", "special", "ona", "bd")
            fallbackTypes.mapNotNull { type ->
                val data = request.data.replace(Regex("""type=[^&]*"""), "type=$type")
                runCatching { app.get(pageUrl(data, page), referer = "$mainUrl/", headers = commonHeaders).document }.getOrNull()
            }
        } else {
            listOf(app.get(pageUrl(request.data, page), referer = "$mainUrl/", headers = commonHeaders).document)
        }

        val items = documents.flatMap { it.toSearchResults() }.distinctBy { it.url }
        val hasNext = documents.any {
            it.select("a.next.page-numbers, .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/']").isNotEmpty()
        }

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val endpoints = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (endpoint in endpoints) {
            val document = runCatching {
                app.get(endpoint, referer = "$mainUrl/", headers = commonHeaders).document
            }.getOrNull() ?: continue
            document.toSearchResults().forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val fixedUrl = normalizeUrl(url, mainUrl) ?: url
        val document = app.get(fixedUrl, referer = "$mainUrl/", headers = commonHeaders).document

        val title = document.selectFirst(
            ".animefull h1.entry-title, h1.entry-title[itemprop=name], h1.entry-title, meta[property=og:title], title"
        )?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(
            ".animefull .thumb img, .single-info .thumb img, meta[property=og:image], img.wp-post-image"
        )?.imageUrl()?.fixImageQuality()

        val type = getType(document.infoText("Type"), fixedUrl)
        val year = document.infoText("Released")?.extractYear()
        val rating = document.selectFirst("meta[itemprop=ratingValue], .rating strong")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.toScore()
        val status = getStatus(document.infoText("Status"))
        val tags = document.select(".animefull .genxed a[href*='/genres/'], .single-info .genxed a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val plot = document.selectFirst(".bixbox.synp .entry-content, .entry-content[itemprop=description]")
            ?.cleanTextBlock()

        val episodes = document.select(".eplister ul li a[href], .eplister a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = document.select(".bixbox .listupd article.bs, .listupd article.bs")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return newAnimeLoadResponse(title, fixedUrl, type) {
            posterUrl = poster
            backgroundPosterUrl = poster
            this.year = year
            plot?.let { this.plot = it }
            this.tags = tags
            rating?.let { score = Score.from10(it) }
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes.ifEmpty { listOf(newEpisode(fixedUrl)) })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val fixedUrl = normalizeUrl(data, mainUrl) ?: data
        val document = app.get(fixedUrl, referer = "$mainUrl/", headers = commonHeaders).document
        val candidates = linkedSetOf<String>()
        var delivered = 0
        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            delivered++
            callback(link)
        }

        document.select("#embed_holder iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src]")
            .mapNotNullTo(candidates) { it.iframeUrl(fixedUrl) }

        document.select("select.mirror option[value], .mirror option[value]")
            .mapNotNull { option -> option.attr("value").takeIf { it.isNotBlank() } }
            .forEach { value ->
                val decodedHtml = value.decodeBase64Url()
                if (!decodedHtml.isNullOrBlank() && decodedHtml.contains("iframe", true)) {
                    Jsoup.parse(decodedHtml).select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                        .mapNotNullTo(candidates) { it.iframeUrl(fixedUrl) }
                } else {
                    normalizeUrl(value, fixedUrl)?.let { mirrorPage ->
                        runCatching {
                            app.get(mirrorPage, referer = fixedUrl, headers = commonHeaders).document
                                .select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                                .mapNotNullTo(candidates) { it.iframeUrl(mirrorPage) }
                        }
                    }
                }
            }

        document.select("button.server-item[data-frame], [data-frame]").forEach { element ->
            element.attr("data-frame").decodeBase64Url()
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.let { normalizeUrl(httpsify(it), fixedUrl) }
                ?.let { candidates.add(it) }
        }

        candidates
            .filterNot { it.contains("youtube.", true) || it.contains("youtu.be", true) }
            .distinct()
            .forEach { iframe ->
                val handled = if (iframe.contains("kotakajaib", true)) {
                    runCatching { Kotakajaib().getUrl(iframe, fixedUrl, subtitleCallback, trackedCallback) }.isSuccess
                } else {
                    runCatching { loadExtractor(iframe, fixedUrl, subtitleCallback, trackedCallback) }.getOrDefault(false)
                }
                if (!handled && iframe.contains("/file/", true)) {
                    runCatching { Kotakajaib().getUrl(iframe, fixedUrl, subtitleCallback, trackedCallback) }
                }
            }

        return delivered > 0
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("article.bs, .listupd .bs")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url.contains("/anime/", true) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href][itemprop=url], .bsx > a[href], a.tip[href], h2 a[href], a.series[href], a[href]")
            ?: return null
        val href = normalizeUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.contains("/anime/", true)) return null

        val title = listOf(
            anchor.attr("title"),
            selectFirst("h2[itemprop=headline], .tt h2, h4 a.series")?.text(),
            selectFirst("img[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitle() ?: return null

        val poster = selectFirst(".limit img, img")?.imageUrl()?.fixImageQuality()
        val type = getType(selectFirst(".typez")?.text(), href)
        val episode = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        val label = selectFirst(".epl-title")?.text()?.trim().orEmpty()
            .ifBlank { text().replace(Regex("""\s+"""), " ").trim() }
        val episodeNumber = selectFirst(".epl-num")?.text()?.toIntOrNull()
            ?: Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(label)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = label.ifBlank { episodeNumber?.let { "Episode $it" } }
            episode = episodeNumber
        }
    }

    private fun Document.infoText(label: String): String? {
        return select(".infox .spe span, .single-info .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()?.cleanLabel()?.equals(label, true) == true
            }
            ?.clone()
            ?.also { it.select("b").remove() }
            ?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun getType(value: String?, url: String): TvType {
        val text = value.orEmpty()
        return when {
            text.contains("movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
            text.contains("ova", true) || text.contains("special", true) || text.contains("ona", true) || text.contains("bd", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            value?.contains("completed", true) == true || value?.contains("complete", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        val fixed = normalizeUrl(pattern, mainUrl) ?: "$mainUrl/"
        if (page <= 1) return fixed.replace("/page/%d/", "/").replace("page/%d/", "")
        if (fixed.contains("%d")) return fixed.format(page)
        val parts = fixed.split("?", limit = 2)
        val base = parts[0].trimEnd('/')
        val query = parts.getOrNull(1)?.let { "?$it" }.orEmpty()
        return "$base/page/$page/$query"
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = Jsoup.parse(raw).text()
            .trim()
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:data-litespeed-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-litespeed-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
    }

    private fun Element.iframeUrl(baseUrl: String): String? {
        return listOf(
            attr("data-litespeed-src"),
            attr("data-src"),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(httpsify(it), baseUrl) }
    }

    private fun Element.cleanTextBlock(): String? {
        val content = clone()
        content.select("script, style, iframe, .sharedaddy").remove()
        return content.select("p").joinToString("\n") { it.text().trim() }
            .ifBlank { content.text().replace(Regex("""\s+"""), " ").trim() }
            .ifBlank { null }
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)\s+(?:Subtitle\s*Indonesia|Sub\s*Indo|English\s*Subbed)\b.*$"""), "")
            .replace(Regex("""(?i)\s*[-|]\s*GojoDesu.*$"""), "")
            .replace(Regex("""(?i)\bNonton\s+Anime\s+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanLabel(): String {
        return replace(":", "").replace(Regex("""\s+"""), " ").trim()
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("""-\d+x\d+(?=\.[a-zA-Z]{3,4}(?:$|[?#]))"""), "")
            .replace(Regex("""([?&])resize=\d+,\d+"""), "$1")
            .replace(Regex("""[?&]$"""), "")
    }

    private fun String.extractYear(): Int? {
        return Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }

    private fun String.decodeBase64Url(): String? {
        val cleaned = trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()
    }
}
