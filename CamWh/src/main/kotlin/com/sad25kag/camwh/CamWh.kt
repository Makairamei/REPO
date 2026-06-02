package com.sad25kag.camwh

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class CamWh : MainAPI() {
    override var mainUrl = "https://camwh.com"
    override var name = "CamWh"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Update Terbaru",
        "$mainUrl/top-rated/" to "Rating Tertinggi",
        "$mainUrl/most-popular/" to "Paling Dilihat",

        // Valid categories from https://camwh.com/categories/
        "$mainUrl/categories/male-videos/" to "Male Videos",
        "$mainUrl/categories/onlyfans-leaks/" to "Onlyfans Leaks",
        "$mainUrl/categories/video-record/" to "Video Record",
        "$mainUrl/categories/new-record-private/" to "New Record Private",
        "$mainUrl/categories/chaturbate-record-video/" to "Chaturbate Record Video",
        "$mainUrl/categories/fansy-content/" to "Fansy Content",
        "$mainUrl/categories/video-record-chaturbate/" to "Video Record Chaturbate",
        "$mainUrl/categories/camwhores-archive/" to "Camwhores Archive",
        "$mainUrl/categories/naked-girl/" to "Naked Girl",
        "$mainUrl/categories/hot-orgasm/" to "Hot Orgasm",
        "$mainUrl/categories/cam-video/" to "Cam Video",
        "$mainUrl/categories/chaturbate-clip/" to "Chaturbate Clip",
        "$mainUrl/categories/private-video-hd/" to "Private Video HD",
        "$mainUrl/categories/chaturbate-video/" to "Chaturbate Video",
        "$mainUrl/categories/cam-clip/" to "Cam Clip",
        "$mainUrl/categories/hot-ticket-show/" to "Hot Ticket Show",
        "$mainUrl/categories/naked-camgirl/" to "Naked Camgirl",
        "$mainUrl/categories/camwhores-private/" to "Camwhores Private",
        "$mainUrl/categories/chaturbate-record/" to "Chaturbate Record",
        "$mainUrl/categories/private-videos/" to "Private Videos",
        "$mainUrl/categories/hot-show/" to "Hot Show",
        "$mainUrl/categories/camwhores-storage/" to "Camwhores Storage",
        "$mainUrl/categories/video-private-chaturbate/" to "Video Private Chaturbate",
        "$mainUrl/categories/video-private-best/" to "Video Private Best",
        "$mainUrl/categories/patreon-video/" to "Patreon Video",
        "$mainUrl/categories/strip-leaks/" to "Strip Leaks",
        "$mainUrl/categories/naked-model/" to "Naked Model",
        "$mainUrl/categories/private-record-video/" to "Private Record Video",
        "$mainUrl/categories/chaturbate-video-record/" to "Chaturbate Video Record",
        "$mainUrl/categories/nude-girl/" to "Nude Girl",
        "$mainUrl/categories/best-video-record/" to "Best Video Record",
        "$mainUrl/categories/best-cam-private/" to "Best Cam Private",
        "$mainUrl/categories/cum-goal/" to "Cum Goal",
        "$mainUrl/categories/ticket-show/" to "Ticket Show",
        "$mainUrl/categories/best-private/" to "Best Private",
        "$mainUrl/categories/spy-record/" to "Spy Record",
        "$mainUrl/categories/video-chaturbate/" to "Video Chaturbate",
        "$mainUrl/categories/sexy-model/" to "Sexy Model",
        "$mainUrl/categories/orgasm-video/" to "Orgasm Video",
        "$mainUrl/categories/chaturbate-hidden-show/" to "Chaturbate Hidden Show",
        "$mainUrl/categories/anal-private/" to "Anal Private",
        "$mainUrl/categories/first-squirt/" to "First Squirt",
        "$mainUrl/categories/stripchat-leaks/" to "Stripchat Leaks",
        "$mainUrl/categories/record/" to "Record",
        "$mainUrl/categories/new-strip-videos/" to "New Strip Videos",
        "$mainUrl/categories/latest-stripchat/" to "Latest Stripchat",
        "$mainUrl/categories/hot-stripchat/" to "Hot Stripchat",
        "$mainUrl/categories/trans-videos/" to "Trans Videos",
        "$mainUrl/categories/newest-strip-videos/" to "Newest Strip Videos",
        "$mainUrl/categories/latest-record/" to "Latest Record",
        "$mainUrl/categories/naked/" to "Naked",
        "$mainUrl/categories/cam-girl/" to "Cam Girl"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(
            buildPagedUrl(request.data, page),
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val items = document.select("div.item, .list-videos .item, .thumb, .video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page&from_albums=1"

        val document = app.get(
            searchUrl,
            headers = defaultHeaders,
            referer = "$mainUrl/search/$encodedQuery/"
        ).document

        val results = document.select("div.item, .list-videos .item, .thumb, .video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("div.headline h1, h1")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: throw ErrorLoadingException("Judul tidak ditemukan.")

        val poster = fixUrlNull(
            document.selectFirst("div.fp-poster img, meta[property=og:image], link[rel=image_src]")
                ?.let { element ->
                    when (element.tagName()) {
                        "meta" -> element.attr("content")
                        "link" -> element.attr("href")
                        else -> element.attr("src").ifBlank { element.attr("data-original") }
                    }
                }
        )

        val description = document.selectFirst("div.item:contains(Description:) em, meta[name=description], meta[property=og:description]")
            ?.let { element ->
                if (element.tagName() == "meta") element.attr("content") else element.text()
            }
            ?.trim()
            ?.ifBlank { null }

        val actors = document.select("div.item:contains(Tags:) a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val tags = document.select("div.item:contains(Categories:) a, .categories a")
            .map { translateTag(it.text().trim()) }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select("div.list-videos div.item, .related-videos div.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String = name) {
            val videoUrl = rawUrl
                ?.resolveCamWhVideoUrl()
                ?.takeIf { it.isNotBlank() }
                ?: return

            if (!emitted.add(videoUrl)) return

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = videoUrl,
                    type = inferType(videoUrl)
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(label)
                    this.headers = streamHeaders()
                }
            )
        }


        suspend fun extractFromHtml(html: String) {
            val patterns = listOf(
                Regex("""video_alt_url\d*\s*[:=]\s*['"]([^'"]+)""", RegexOption.IGNORE_CASE),
                Regex("""video_url\d*\s*[:=]\s*['"]([^'"]+)""", RegexOption.IGNORE_CASE),
                Regex("""contentUrl"\s*:\s*"([^"]+/get_file/3/[^"]+)""", RegexOption.IGNORE_CASE),
                Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)""", RegexOption.IGNORE_CASE),
                Regex("""source\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)""", RegexOption.IGNORE_CASE),
                Regex("""['"](https?://[^'"]+/get_file/3/[^'"]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""['"](https?://[^'"]+\.(?:mp4|m3u8)(?:\?[^'"]*)?)['"]""", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    emitDirect(match.groupValues.getOrNull(1), "$name - Direct")
                }
            }
        }

        val response = app.get(
            data,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        )
        val document = response.document

        extractFromHtml(response.text)

        for (element in document.select("video source[src], video[src], source[src]")) {
            emitDirect(element.attr("src"), "$name - Video")
        }

        for (element in document.select("iframe[src], iframe[data-src], [data-video], [data-url]")) {
            val iframeUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }

            if (iframeUrl.isNotBlank()) {
                try {
                    loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                } catch (_: Exception) {
                    // Ignore broken iframe fallback and continue direct extraction.
                }
            }
        }

        if (emitted.isNotEmpty()) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        val webview = WebViewResolver(
            interceptUrl = Regex(""".*/get_file/.*"""),
            userAgent = USER_AGENT,
            useOkhttp = false
        )

        var capturedFileUrl = ""

        webview.resolveUsingWebView(
            url = data,
            referer = "$mainUrl/",
            requestCallBack = { request ->
                val currentUrl = request.url.toString()

                if (currentUrl.contains("/get_file/")) {
                    capturedFileUrl = currentUrl
                    true
                } else {
                    false
                }
            }
        )

        if (capturedFileUrl.isNotBlank()) {
            val redirected = app.get(
                capturedFileUrl,
                headers = streamHeaders(),
                referer = "$mainUrl/",
                allowRedirects = false
            ).headers["Location"] ?: capturedFileUrl

            emitDirect(redirected, "$name - WebView")
        }

        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".title, strong, .video-title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        if (title.isBlank()) return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-original")
                ?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        if (page <= 1) return rawUrl
        val clean = rawUrl.trimEnd('/')
        return when {
            clean.contains("from=") -> clean.replace(Regex("""from=\d+"""), "from=$page")
            clean.contains("?") -> "$clean&from=$page"
            else -> "$clean/$page/"
        }
    }

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun streamHeaders(): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Range" to "bytes=0-"
        )
    }

    private suspend fun String.resolveCamWhVideoUrl(): String? {
        val decoded = decodeEscapedUrl()
            .removePrefix("function/0/")
            .trim()

        if (decoded.isBlank()) return null
        if (decoded.contains("videos_screenshots", ignoreCase = true)) return null
        if (decoded.contains("/screenshots/", ignoreCase = true)) return null
        if (decoded.endsWith(".jpg", ignoreCase = true) || decoded.endsWith(".png", ignoreCase = true)) return null

        val fixed = fixUrl(decoded)
        if (!fixed.contains("/get_file/", ignoreCase = true)) return fixed

        return runCatching {
            app.get(
                fixed,
                headers = streamHeaders(),
                referer = "$mainUrl/",
                allowRedirects = false
            ).headers["Location"] ?: fixed
        }.getOrDefault(fixed)
    }

    private fun String.decodeEscapedUrl(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .let { value ->
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
    }

    private fun translateTag(tag: String): String {
        return when (tag.lowercase()) {
            "latest videos" -> "Video Terbaru"
            "top rated videos" -> "Rating Tertinggi"
            "most viewed videos" -> "Paling Dilihat"
            "webcam" -> "Webcam"
            "amateur" -> "Amateur"
            "solo" -> "Solo"
            "public" -> "Public"
            "blonde" -> "Blonde"
            "brunette" -> "Brunette"
            else -> tag
        }
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}