package com.sad25kag.hentaiworld

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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HentaiWorld : MainAPI() {
    override var mainUrl = "https://hentaiworld.tv"
    override var name = "HentaiWorld"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "all-episodes/" to "All Episodes",
        "hentai-videos/tag/uncensored/" to "Uncensored",
        "hentai-videos/tag/vanilla/" to "Vanilla",
        "hentai-videos/tag/big-boobs/" to "Big Boobs",
        "hentai-videos/tag/milf/" to "MILF",
        "hentai-videos/tag/school-girl/" to "School Girl",
        "hentai-videos/tag/cosplay/" to "Cosplay",
        "hentai-videos/tag/fantasy/" to "Fantasy",
        "hentai-videos/tag/nurse/" to "Nurse",
        "hentai-videos/tag/threesome/" to "Threesome",
        "hentai-videos/tag/public-sex/" to "Public Sex",

        "hentai-videos/tag/ahegao/" to "Ahegao",
        "hentai-videos/tag/bdsm/" to "BDSM",
        "hentai-videos/tag/bondage/" to "Bondage",
        "hentai-videos/tag/creampie/" to "Creampie",
        "hentai-videos/tag/harem/" to "Harem",
        "hentai-videos/tag/hd/" to "HD",
        "hentai-videos/tag/maid/" to "Maid",
        "hentai-videos/tag/monster/" to "Monster",
        "hentai-videos/tag/teacher/" to "Teacher",
        "hentai-videos/tag/tentacle/" to "Tentacle"
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

        val home = document.select("a.card-container[href]")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    "a[href*='/page/${page + 1}/']"
            ) != null || home.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            page <= 1 && cleanPath.isBlank() -> mainUrl
            page <= 1 -> "$mainUrl/$cleanPath/"
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a.card-container[href], a[href]")) {
            this
        } else {
            selectFirst("a.card-container[href], a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.contains("hentaiworld.tv", true)) return null
        if (isBlockedUrl(href)) return null

        val parent = anchor.parent()
        val image = anchor.selectFirst("img")
            ?: parent?.selectFirst("img")
            ?: return null

        val title = listOf(
            anchor.selectFirst(".video-title-text")?.text()?.trim(),
            parent?.selectFirst(".video-title-text")?.text()?.trim(),
            parent?.selectFirst("h2, h3, h4, .title, .entry-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            image.attr("alt").trim(),
            image.attr("title").trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image.getImageAttr())

        return newMovieSearchResponse(
            title,
            href,
            TvType.NSFW
        ) {
            posterUrl = poster
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter("hentaiworld.tv").trim('/').lowercase()

        if (path.isBlank()) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        val blocked = listOf(
            "hentai-videos/tag/",
            "tag/",
            "category/",
            "page/",
            "contact",
            "privacy",
            "terms",
            "dmca"
        )

        return blocked.any {
            path == it.trimEnd('/') || path.startsWith(it)
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
            .lowercase()

        val url = "$mainUrl/search/$encoded/page/${page.coerceAtLeast(1)}/"

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = document.select("a.card-container[href]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    "a[href*='/page/${page + 1}/']"
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
                    "div.left-content img, " +
                    ".left-content img, " +
                    "video[poster], " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            }
        )

        val plot = document.selectFirst(
            "p.episode-description:not(:has(strong)), " +
                "meta[property=og:description], " +
                ".description, " +
                ".entry-content p"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.filter { it.code < 128 }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val year = document.selectFirst("div.extra span.C a, .extra span.C a")
            ?.text()
            ?.toIntOrNull()

        val score = document.selectFirst("span.dt_rating_vgs, .dt_rating_vgs")
            ?.text()
            ?.trim()

        val duration = document.selectFirst("span.runtime, .runtime")
            ?.text()
            ?.substringBefore(" ")
            ?.toIntOrNull()

        val studioElements = document.select("p.episode-description:has(strong:contains(Studio)) a")
        val castElements = document.select("span.valor a")

        val tags = document.select(
            "div.video-tags a, " +
                ".video-tags a, " +
                "a[href*='/hentai-videos/tag/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .toMutableList()

        studioElements.forEach { studio ->
            val studioName = studio.text().trim()
            if (studioName.isNotBlank()) {
                tags.add("Studio: $studioName")
            }
        }

        val actors = (studioElements + castElements)
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val recommendations = document.select(
            "div.crp_related div.swiper-slide, " +
                ".crp_related .swiper-slide, " +
                "a.card-container[href]"
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
            this.plot = plot
            this.year = year
            this.tags = tags.distinct()
            this.score = Score.from10(score)
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val link = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a.crp_link[href], a.card-container[href], a[href]") ?: return null
        }

        val href = fixUrlNull(link.attr("href")) ?: return null

        if (!href.contains("hentaiworld.tv", true)) return null
        if (isBlockedUrl(href)) return null

        val parent = link.parent()
        val image = link.selectFirst("img")
            ?: parent?.selectFirst("img")

        val name = listOf(
            selectFirst(".crp_title")?.text()?.trim(),
            link.selectFirst(".video-title-text")?.text()?.trim(),
            parent?.selectFirst(".video-title-text")?.text()?.trim(),
            link.attr("title").trim(),
            image?.attr("alt")?.trim(),
            image?.attr("title")?.trim()
        ).firstOrNull {
            !it.isNullOrBlank()
        }?.cleanTitle() ?: return null

        val poster = fixUrlNull(image?.getImageAttr())

        return newMovieSearchResponse(
            name,
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
        val response = app.get(
            data,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        )

        val html = response.text.cleanEscaped()
        val document = response.document

        val directLinks = linkedSetOf<String>()

        document.select(
            "video source[src], " +
                "source[src], " +
                "video[src], " +
                "a[href*='.mp4'], " +
                "a[href*='.m3u8']"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) {
                directLinks.add(fixUrl(raw))
            }
        }

        Regex("""https://hentaiworld\.tv/video-player\.html\?videos/[^'"\s<>]+""")
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { iframeUrl ->
                val fixedIframe = iframeUrl.replace(" ", "%20")
                val videoPath = fixedIframe.substringAfter("?").replace(" ", "%20")

                val playerHtml = runCatching {
                    app.get(
                        fixedIframe,
                        headers = headers,
                        referer = data,
                        timeout = 30L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                val prefix = Regex("""let\s+prefix\s*=\s*['"](https?://[^'"]+)['"]""")
                    .find(playerHtml)
                    ?.groupValues
                    ?.getOrNull(1)

                if (!prefix.isNullOrBlank()) {
                    directLinks.add("$prefix$videoPath")
                }

                extractMediaUrls(playerHtml).forEach { directLinks.add(it) }
            }

        extractMediaUrls(html).forEach { directLinks.add(it) }

        var found = false

        directLinks.distinct().forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = if (link.contains(".m3u8", true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    referer = data
                    quality = getQualityFromName(link).takeIf {
                        it != Qualities.Unknown.value
                    } ?: qualityFromUrl(link)
                }
            )

            found = true
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

        Regex("""(?:file|src|source|url|videoUrl)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true)
            }
            .map { fixUrl(it) }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun Element.getImageAttr(): String? {
        fun bestFromSrcset(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() &&
                        !it.contains("blank", true) &&
                        !it.contains("placeholder", true) &&
                        !it.endsWith(".svg", true)
                }
        }

        val srcset = bestFromSrcset(attr("data-srcset"))
            ?: bestFromSrcset(attr("data-lazy-srcset"))
            ?: bestFromSrcset(attr("srcset"))

        if (!srcset.isNullOrBlank()) {
            return fixUrl(srcset)
        }

        val raw = when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("data-original") -> attr("data-original")
            hasAttr("data-full") -> attr("data-full")
            hasAttr("data-thumb") -> attr("data-thumb")
            hasAttr("src") -> attr("src")
            else -> null
        }?.trim()

        return raw?.takeIf {
            it.isNotBlank() &&
                !it.contains("blank", true) &&
                !it.contains("placeholder", true) &&
                !it.endsWith(".svg", true)
        }?.let { fixUrl(it) }
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("&lt;", "")
            .replace("&gt;", "")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+HentaiWorld.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+HentaiWorld\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}