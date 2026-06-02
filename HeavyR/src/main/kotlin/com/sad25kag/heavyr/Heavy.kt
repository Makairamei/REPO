package com.sad25kag.heavyr

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
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Heavy : MainAPI() {
    override var mainUrl = "https://heavy-r.com"
    override var name = "Heavy-R"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "free_porn/amateur.html" to "Amateur",
        "free_porn/anal.html" to "Anal",
        "free_porn/asian.html" to "Asian",
        "free_porn/ass.html" to "Ass",
        "free_porn/bdsm.html" to "BDSM",
        "free_porn/big-dick.html" to "Big Dick",
        "free_porn/big-tits.html" to "Big Tits",
        "free_porn/blonde.html" to "Blonde",
        "free_porn/brunette.html" to "Brunette",
        "free_porn/creampie.html" to "Creampie",
        "free_porn/deepthroat.html" to "Deepthroat",
        "free_porn/dp.html" to "Double Penetration",
        "free_porn/ebony.html" to "Ebony",
        "free_porn/fetish.html" to "Fetish",
        "free_porn/fisting.html" to "Fisting",
        "free_porn/gangbang.html" to "Gangbang",
        "free_porn/hardcore.html" to "Hardcore",
        "free_porn/indian.html" to "Indian",
        "free_porn/interracial.html" to "Interracial",
        "free_porn/latina.html" to "Latina",
        "free_porn/lesbian.html" to "Lesbian",
        "free_porn/massage.html" to "Massage",
        "free_porn/mature.html" to "Mature",
        "free_porn/milf.html" to "MILF",
        "free_porn/orgy.html" to "Orgy",
        "free_porn/pov.html" to "POV",
        "free_porn/public.html" to "Public",
        "free_porn/redhead.html" to "Redhead",
        "free_porn/rough.html" to "Rough",
        "free_porn/squirt.html" to "Squirt",
        "free_porn/teen.html" to "Teen",
        "free_porn/threesome.html" to "Threesome"
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
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val home = document.select(
            "div.video-item, " +
                ".video-item, " +
                "li.video-item, " +
                ".thumb-block, " +
                ".item"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    "a[href*='_${page + 1}.html']"
            ) != null || home.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            page <= 1 -> "$mainUrl/$cleanPath"
            cleanPath.endsWith(".html", ignoreCase = true) -> {
                "$mainUrl/${cleanPath.removeSuffix(".html")}_$page.html"
            }
            else -> "$mainUrl/$cleanPath/$page"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "h4.title a[href], " +
                ".title a[href], " +
                "a[href*='/video/'], " +
                "a[href]"
        ) ?: return null

        val title = listOf(
            selectFirst("h4.title a")?.text()?.trim(),
            selectFirst(".title a")?.text()?.trim(),
            selectFirst(".title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true) &&
                !it.equals("Home", true)
        }?.cleanTitle() ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null
        if (title.length < 3) return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

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

        if (path.isBlank()) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        val blocked = listOf(
            "free_porn/",
            "channels/",
            "pornstars/",
            "members/",
            "login",
            "signup",
            "search",
            "contact",
            "privacy",
            "terms"
        )

        return blocked.any { path == it.trimEnd('/') || path.startsWith(it) && !path.contains(".html") }
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
            .lowercase()

        val url = "$mainUrl/search/${encoded}_${page.coerceAtLeast(1)}.html"

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = document.select(
            "div.video-item, " +
                ".video-item, " +
                "li.video-item, " +
                ".thumb-block, " +
                ".item"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    "a[href*='_${page + 1}.html']"
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
                "meta[property=og:image], " +
                    "video[poster], " +
                    "div.player img, " +
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

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "div.video-data p, " +
                ".video-data, " +
                ".description"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "div.tags a[title], " +
                "div.tags a, " +
                "a[href*='/free_porn/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select(
            "div.recent-uploads a.item, " +
                ".recent-uploads a[href], " +
                ".related a[href], " +
                "div.video-item, " +
                ".video-item"
        ).mapNotNull { it.toRecommendationResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        return newMovieLoadResponse(
            title,
            url,
            TvType.NSFW,
            url
        ) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("span.title")?.text()?.trim(),
            selectFirst(".title")?.text()?.trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.attr("title").trim(),
            anchor.text().trim()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(
            data,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        ).document

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "video#video-file source[src], " +
                "video source[src], " +
                "source[src], " +
                "video[src], " +
                "iframe[src], " +
                "embed[src], " +
                "a[href*='.mp4'], " +
                "a[href*='.m3u8'], " +
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

        extractMediaUrls(document.html()).forEach { link ->
            when {
                link.contains(".m3u8", true) || link.contains(".mp4", true) -> directLinks.add(link)
                link.startsWith("http", true) -> embedLinks.add(link)
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
                        } ?: qualityFromUrl(link)
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

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html.cleanEscaped())
            .map { it.value.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""(?:file|src|source|videoUrl|url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html.cleanEscaped())
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
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
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
            .replace(Regex("""\s+-\s+Heavy-R.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Heavy-R\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}