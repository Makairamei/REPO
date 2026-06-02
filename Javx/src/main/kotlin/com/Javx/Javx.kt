package com.Javx

import android.util.Log
import com.lagradost.cloudstream3.HomePageList
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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Javx : MainAPI() {
    override var name = "Javx"
    override var mainUrl = "https://javx.org"
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "?filter=latest" to "New Releases",
        "videos" to "Popular Videos",
        "english-subtitles" to "English Subtitle",

        "category/amateur" to "Amateur",
        "category/beautiful-girl" to "Beautiful Girl",
        "category/big-tits" to "Big Tits",
        "category/married-woman" to "Married Woman",
        "category/milf" to "Milf",
        "category/cheating-wife" to "Cheating Wife",
        "category/adultery" to "Adultery",
        "category/affair" to "Affair",
        "category/cuckold" to "Cuckold",
        "category/creampie" to "Creampie",
        "category/cosplay" to "Cosplay",
        "category/bbw" to "BBW",
        "category/bdsm" to "BDSM",
        "category/anal" to "Anal",
        "category/4k" to "4K"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, timeout = 25L).document

        val responseList = parseCards(document).distinctBy { it.url }
        return newHomePageResponse(
            HomePageList(request.name, responseList, isHorizontalImages = true),
            hasNext = hasNextPage(document, page) || responseList.isNotEmpty()
        )
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim().trim('/')

        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"

            clean.startsWith("http", true) -> {
                if (page <= 1) clean else clean.trimEnd('/') + "/page/$page/"
            }

            clean.startsWith("?") && page <= 1 -> "$mainUrl/$clean"
            clean.startsWith("?") -> "$mainUrl/page/$page/$clean"

            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a[href]):has(img), " +
                ".video-item:has(a[href]):has(img), " +
                ".item:has(a[href]):has(img), " +
                ".post:has(a[href]):has(img), " +
                ".thumb-block:has(a[href]):has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { item -> results[item.url] = item }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
                element.toSearchResult()?.let { item -> results[item.url] = item }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href][title], h2 a[href], h3 a[href], a[href]:has(img), a[href]")
                ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            anchor.attr("title"),
            selectFirst("h2 a, h3 a, .entry-title a, .title a")?.text(),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast('/').replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        val posterUrl = image?.getImageUrl()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("referer" to mainUrl)
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(Next), " +
                "a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}']"
        ) != null
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()
        if (path.isBlank()) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        val blocked = listOf(
            "category/",
            "categories",
            "studios",
            "channels",
            "models",
            "actress",
            "dmca",
            "privacy-policy",
            "2257",
            "login",
            "register",
            "wp-content",
            "wp-json",
            "feed"
        )

        return blocked.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    private fun isBadTitle(title: String): Boolean {
        val clean = title.trim()
        return clean.isBlank() ||
            clean.equals("More videos", true) ||
            clean.equals("Latest videos", true) ||
            clean.equals("Most viewed videos", true) ||
            clean.equals("Longest videos", true) ||
            clean.equals("Popular videos", true) ||
            clean.equals("Home", true) ||
            clean.equals("Categories", true) ||
            clean.equals("Channels", true) ||
            clean.equals("Actress", true) ||
            clean.equals("English Subtitle", true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = app.get(url, headers = headers, timeout = 25L).document
        val results = parseCards(document).distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = hasNextPage(document, page) || results.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = headers, timeout = 25L).document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1, h1.entry-title")?.text(),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: name

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".entry-content p, article p")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val poster = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("video[poster], article img, img.wp-post-image, img")
                ?.getImageUrl()

        val tags = document.select("a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            posterHeaders = mapOf("referer" to mainUrl)
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(data, headers = headers, referer = mainUrl, timeout = 25L).document
        var found = false

        document.select("#sourcetabs a[href], #sourcetabs a[data-src], #sourcetabs a[data-embed], iframe[src], iframe[data-src]")
            .mapNotNull { element ->
                element.attr("href")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-embed") }
                    .ifBlank { element.attr("src") }
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .mapNotNull { raw -> fixUrlNull(raw) }
            .distinct()
            .forEach { embed ->
                Log.d("Javx", embed)
                val success = loadExtractor(embed, data, subtitleCallback, callback)
                if (success) found = true
            }

        return found
    }

    private fun Element.getImageUrl(): String? {
        return attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+JAVX\.ORG\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+⋆.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

class StreamwishHG : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}
