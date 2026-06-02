package com.sad25kag.hentaicity

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HentaiCityProvider : MainAPI() {
    override var name = "HentaiCity"
    override var mainUrl = "https://www.hentaicity.com"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "search/video/uncensored" to "Uncensored",
        "search/video/japanese" to "Japanese",
        "search/video/english-dubbed" to "English Dubbed",
        "search/video/hd" to "HD",
        "search/video/full" to "Full Videos",
        "search/video/series" to "Series",
        "search/video/ova" to "OVA",
        "search/video/romance" to "Romance",
        "search/video/fantasy" to "Fantasy",
        "search/video/action" to "Action",
        "search/video/comedy" to "Comedy",
        "search/video/school" to "School",
        "search/video/horror" to "Horror",
        "search/video/drama" to "Drama"
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
        if (request.data.isBlank() && page <= 1) {
            val document = app.get(
                mainUrl,
                headers = headers,
                timeout = 30L
            ).document

            val rows = parseHomeRows(document)

            if (rows.isNotEmpty()) {
                return newHomePageResponse(rows, hasNext = false)
            }
        }

        val url = buildPageUrl(request.data, page)
        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val list = parseVideoCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            list,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    "a.next, " +
                    ".pagination a:contains(Next), " +
                    "a[href*='page=${page + 1}'], " +
                    "a[href*='/${page + 1}/']"
            ) != null || list.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl/?page=$page"
            page <= 1 -> "$mainUrl/$cleanPath"
            cleanPath.contains("search/video/", true) -> "$mainUrl/$cleanPath/$page"
            cleanPath.endsWith(".html", true) -> "$mainUrl/${cleanPath.removeSuffix(".html")}_$page.html"
            else -> "$mainUrl/$cleanPath?page=$page"
        }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        fun addRow(title: String, selector: String) {
            val items = document.select(selector)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                rows.add(HomePageList(title, items))
            }
        }

        addRow("New Hentai Releases", "div.new-releases div.item, .new-releases .item")
        addRow("Most Popular Videos", "h2:contains(Most Popular Videos) ~ div.thumb-list div.outer-item > div.item, div.thumb-list div.outer-item > div.item")
        addRow("Recent Videos", "h2:contains(Recent Videos) ~ div#taglink.thumb-list div.recent > div.item, div#taglink.thumb-list div.item")
        addRow("Videos", "section.content div.thumb-list div.outer-item > div.item, div.outer-item > div.item")

        if (rows.isEmpty()) {
            val fallback = parseVideoCards(document)
            if (fallback.isNotEmpty()) {
                rows.add(HomePageList("Beranda", fallback))
            }
        }

        return rows.distinctBy { it.name }
    }

    private fun parseVideoCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "section.content > div.thumb-list div.outer-item > div.item, " +
                "div.outer-item > div.item, " +
                "div.thumb-list div.item, " +
                "div.new-releases div.item, " +
                "div.item"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "a.thumb-img[href], " +
                "p > a.video-title[href], " +
                "a.video-title[href], " +
                ".video-title a[href], " +
                "a[href*='/video/']"
        ) ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (!href.contains("/video/", true)) return null

        val title = listOf(
            selectFirst("p > a.video-title")?.text()?.trim(),
            selectFirst("a.video-title")?.text()?.trim(),
            selectFirst(".video-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true) &&
                !it.equals("Home", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(
            selectFirst("img.thumbtrailer__image")?.getImageAttr()
                ?: selectFirst("img")?.getImageAttr()
        )

        val quality = if (selectFirst("span.flag-hd, .flag-hd") != null) {
            SearchQuality.HD
        } else {
            SearchQuality.SD
        }

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            posterUrl = poster
            this.quality = quality
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
            .replace("+", "-")

        val url = if (page <= 1) {
            "$mainUrl/search/video/$encoded"
        } else {
            "$mainUrl/search/video/$encoded/$page"
        }

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = parseVideoCards(document)
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    "a.next, " +
                    ".pagination a:contains(Next), " +
                    "a[href*='/$page/']"
            ) != null || results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val title = document.selectFirst(
            "h1.video-title, " +
                "h1, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(
                "div#playerz video[poster], " +
                    "meta[property=og:image], " +
                    "video[poster], " +
                    "img[itemprop=thumbnailUrl], " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            }
        )

        val synopsis = document.selectFirst(
            "div.detail-box > div.ubox-text, " +
                "meta[property=og:description], " +
                ".description, " +
                ".details"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> Jsoup.parse(element.html().replace("<br>", "\n")).text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "div#taglink a:not(:has(svg)), " +
                "div.tags a, " +
                "a[href*='/tag/'], " +
                "a[href*='/genre/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val rating = document.selectFirst("div.info span:contains('%'), span:contains('%')")
            ?.text()
            ?.replace("%", "")
            ?.trim()
            ?.toDoubleOrNull()
            ?.div(10.0)

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(document.selectFirst("div.fp_title div.information, .information")?.text().orEmpty())
            ?.value
            ?.toIntOrNull()

        val recommendations = document.select(
            "div#related_videos div.outer-item > div.item, " +
                "div#related_videos div.item, " +
                ".related div.item, " +
                "div.outer-item > div.item"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            url
        ) {
            posterUrl = poster
            plot = synopsis
            this.tags = tags
            this.score = Score.from10(rating)
            this.recommendations = recommendations
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val response = app.get(
            data,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "div#playerz video source[src], " +
                "video source[src], " +
                "source[src], " +
                "video[src], " +
                "iframe[src], " +
                "embed[src], " +
                "a[href*='.m3u8'], " +
                "a[href*='.mp4'], " +
                "a[href*='embed'], " +
                "a[href*='player']"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach

            val fixed = fixUrl(raw)

            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        fixUrlNull(document.selectFirst("meta[property=og:video:url]")?.attr("content"))
            ?.let { directLinks.add(it) }

        extractMediaUrls(html).forEach { url ->
            when {
                url.contains(".m3u8", true) || url.contains(".mp4", true) -> directLinks.add(url)
                url.startsWith("http", true) -> embedLinks.add(url)
            }
        }

        var found = false

        directLinks.forEach { link ->
            if (link.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = data
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = getQualityFromName(link).takeIf {
                            it != Qualities.Unknown.value
                        } ?: Qualities.P480.value
                    }
                )
            }

            found = true
        }

        embedLinks.forEach { embed ->
            val success = loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )

            if (success) found = true
        }

        return found
    }

    private fun extractMediaUrls(html: String): List<String> {
        val links = linkedSetOf<String>()
        val cleaned = html.cleanEscaped()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""(?:file|src|source|url|videoUrl|poster)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .map { fixUrl(it) }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("data-preview") -> attr("abs:data-preview")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
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
            .replace(Regex("""\s+-\s+HentaiCity.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+HentaiCity\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}