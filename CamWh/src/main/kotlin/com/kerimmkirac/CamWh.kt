package com.kerimmkirac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking

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

        // Pakai route search internal supaya kategori tidak kosong / cuma tampil sebagai teks.
        // Beberapa halaman /categories/ di CamWh sering tidak mengembalikan card video untuk Cloudstream.
        "search:anal" to "Kategori Anal",
        "search:oral" to "Kategori Oral",
        "search:boobs" to "Kategori Boobs",
        "search:lesbian" to "Kategori Lesbian",
        "search:solo" to "Kategori Solo",
        "search:public" to "Kategori Public",
        "search:webcam" to "Kategori Webcam",
        "search:blonde" to "Blonde",
        "search:brunette" to "Brunette",
        "search:big tits" to "Big Tits",
        "search:amateur" to "Amateur",
        "search:asian" to "Asian",
        "search:cosplay" to "Cosplay",
        "search:hd" to "HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (request.data.startsWith("search:")) {
            val keyword = request.data.removePrefix("search:").trim()
            fetchSearchItems(keyword, page)
        } else {
            val document = app.get(
                buildPagedUrl(request.data, page),
                headers = defaultHeaders,
                referer = "$mainUrl/"
            ).document

            val parsedItems = document.select(cardSelector)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            // Kalau halaman kategori/tag kosong, jangan biarkan Cloudstream cuma menampilkan judul kategori sebagai teks.
            parsedItems.ifEmpty {
                fetchSearchItems(request.name.cleanCategoryKeyword(), page)
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = fetchSearchItems(query, page)
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    private suspend fun fetchSearchItems(query: String, page: Int): List<SearchResponse> {
        val cleanQuery = query.cleanCategoryKeyword()
        if (cleanQuery.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/search/$encodedQuery/?mode=async&function=get_block&block_id=list_videos_videos_list_search_result&q=$encodedQuery&category_ids=&sort_by=&from_videos=$page&from_albums=1"

        val document = app.get(
            searchUrl,
            headers = defaultHeaders,
            referer = "$mainUrl/search/$encodedQuery/"
        ).document

        return document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
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

        val recommendations = document.select(cardSelector)
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
        val emitted = linkedSetOf<String>()

        fun emitDirect(rawUrl: String?, label: String = name) {
            val videoUrl = rawUrl
                ?.decodeEscapedUrl()
                ?.takeIf { it.isNotBlank() }
                ?: return

            if (!emitted.add(videoUrl)) return

            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = videoUrl,
                        type = inferType(videoUrl)
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName(label)
                        this.headers = streamHeaders(data)
                    }
                )
            }
        }

        fun extractFromHtml(html: String) {
            val patterns = listOf(
                Regex("""video_alt_url\d*\s*[:=]\s*['"]([^'"]+)""", RegexOption.IGNORE_CASE),
                Regex("""video_url\d*\s*[:=]\s*['"]([^'"]+)""", RegexOption.IGNORE_CASE),
                Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)""", RegexOption.IGNORE_CASE),
                Regex("""source\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)""", RegexOption.IGNORE_CASE),
                Regex("""['"](https?://[^'"]+/(?:get_file|contents|videos)/[^'"]+)['"]""", RegexOption.IGNORE_CASE),
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

        document.select("video source[src], video[src], source[src]").forEach { element ->
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
                }
            }
        }

        if (emitted.isNotEmpty()) return true

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
                headers = streamHeaders(data),
                allowRedirects = false
            ).headers["Location"] ?: capturedFileUrl

            emitDirect(redirected, "$name - WebView")
        }

        return emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".title, strong, .video-title, .thumb-title, .item-title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { anchor.text().trim() }

        if (title.isBlank()) return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-thumb")?.takeIf { it.isNotBlank() }
                ?: img?.attr("srcset")?.firstSrcSetUrl()
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: attr("style").extractCssImageUrl()
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

    private fun streamHeaders(refererUrl: String): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Referer" to refererUrl,
            "Origin" to mainUrl
        )
    }

    private fun String.decodeEscapedUrl(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
            .let { value ->
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
    }

    private fun String.cleanCategoryKeyword(): String {
        return lowercase()
            .replace("kategori", "")
            .replace("terbaru", "")
            .replace("rating tertinggi", "")
            .replace("paling dilihat", "")
            .replace("big tits", "big tits")
            .trim()
            .ifBlank { this.trim() }
    }

    private fun String.firstSrcSetUrl(): String? {
        return split(",")
            .firstOrNull()
            ?.trim()
            ?.substringBefore(" ")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.extractCssImageUrl(): String? {
        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(2)
            ?.takeIf { it.isNotBlank() }
    }

    private val cardSelector = listOf(
        "div.item",
        ".list-videos .item",
        ".thumb",
        ".video-item",
        ".item-video",
        ".video",
        ".video-card",
        ".cards .card",
        "li.video"
    ).joinToString(",")

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
