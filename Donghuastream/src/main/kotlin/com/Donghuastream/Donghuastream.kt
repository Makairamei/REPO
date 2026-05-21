package com.Donghuastream

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

open class Donghuastream : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?status=&type=&order=update&page={page}" to "Update Terbaru",
        "$mainUrl/anime/?status=completed&type=&order=update&page={page}" to "Completed",
        "$mainUrl/anime/?status=ongoing&type=&order=update&page={page}" to "Ongoing",
        "$mainUrl/anime/?status=&type=movie&order=update&page={page}" to "Movie",
        "$mainUrl/anime/?status=&type=special&order=update&page={page}" to "Special",
        "$mainUrl/anime/list-mode/" to "Anime List",

        "$mainUrl/genres/action/page/{page}/" to "Action",
        "$mainUrl/genres/adventure/page/{page}/" to "Adventure",
        "$mainUrl/genres/another-world/page/{page}/" to "Another World",
        "$mainUrl/genres/assassin/page/{page}/" to "Assassin",
        "$mainUrl/genres/beast/page/{page}/" to "Beast",
        "$mainUrl/genres/drama/page/{page}/" to "Drama",
        "$mainUrl/genres/fantasy/page/{page}/" to "Fantasy",
        "$mainUrl/genres/funny/page/{page}/" to "Funny",
        "$mainUrl/genres/game/page/{page}/" to "Game",
        "$mainUrl/genres/martial-arts/page/{page}/" to "Martial Arts",
        "$mainUrl/genres/monsters/page/{page}/" to "Monsters",
        "$mainUrl/genres/movie/page/{page}/" to "Movie Genre",
        "$mainUrl/genres/mystery/page/{page}/" to "Mystery",
        "$mainUrl/genres/popular/page/{page}/" to "Popular",
        "$mainUrl/genres/reincarnated/page/{page}/" to "Reincarnated",
        "$mainUrl/genres/romance/page/{page}/" to "Romance",
        "$mainUrl/genres/sci-fi/page/{page}/" to "Sci-Fi",
        "$mainUrl/genres/slice-of-life/page/{page}/" to "Slice of Life",
        "$mainUrl/genres/supernatural/page/{page}/" to "Supernatural",
        "$mainUrl/genres/swords-fight/page/{page}/" to "Swords Fight",
        "$mainUrl/genres/thriller/page/{page}/" to "Thriller",
        "$mainUrl/genres/vengeance/page/{page}/" to "Vengeance"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val home = document.select("div.listupd > article, article.bs, .listupd article, .bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a.next, .pagination a.next, a[href*='/page/${page + 1}/'], a[href*='page=${page + 1}']").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext || home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href], a[href*='/anime/'], a[href]")
            ?: return null

        val href = anchor.attr("href").absoluteUrl(mainUrl)
        if (!href.contains("/anime/", true)) return null

        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".tt, h2, h3")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (title.isBlank()) return null

        val posterUrl = findPoster(this, href)

        val type = if (title.contains("movie", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val searchResponse = mutableListOf<SearchResponse>()
        val encodedQuery = query.urlEncoded()

        for (i in 1..3) {
            val url = if (i == 1) {
                "$mainUrl/?s=$encodedQuery"
            } else {
                "$mainUrl/page/$i/?s=$encodedQuery"
            }

            val results = app.get(
                url,
                headers = defaultHeaders,
                referer = "$mainUrl/"
            ).document.select("div.listupd > article, article.bs, .listupd article, .bs")
                .mapNotNull { it.toSearchResult() }
                .filterNot { result -> searchResponse.any { it.url == result.url } }

            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }

        return searchResponse
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val title = selectFirst("div.tt, .tt, h2, h3")?.text()?.trim()
            ?.ifBlank { anchor.attr("title").trim() }
            ?.ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
            ?: return null

        val href = anchor.attr("href").absoluteUrl(mainUrl)
        val posterUrl = findPoster(this, href)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul Donghuastream tidak ditemukan")

        var poster = document.selectFirst("div.ime > img, .thumb img, .bigcontent img")
            ?.let { findPoster(it, url) }
            ?: document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
                ?.attr("content")
                ?.absoluteUrl(url)

        val recommendations = document.select("div.listupd article.bs, .listupd article, article.bs")
            .mapNotNull { it.toRecommendResult() }
            .distinctBy { it.url }

        val description = document.selectFirst("div.entry-content, .entry-content-single, .synopsis, .desc")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val infoText = document.selectFirst(".spe, .info-content, .infotable")
            ?.text()
            .orEmpty()

        val tags = document.select("a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val tvTag = if (infoText.contains("Movie", true) || tags.any { it.equals("Movie", true) }) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val directEpisodeLinks = document.select(".eplister li > a[href], .episodelist li > a[href], .bixbox.bxcl li a[href]")
            .mapNotNull { it.toEpisodeOrNull() }
            .distinctBy { it.data }

        val episodes = if (directEpisodeLinks.isNotEmpty()) {
            directEpisodeLinks
        } else {
            val firstEpisodePage = document.selectFirst(".eplister li > a[href], .episodelist li > a[href]")
                ?.attr("href")
                ?.absoluteUrl(url)

            if (!firstEpisodePage.isNullOrBlank()) {
                runCatching {
                    app.get(firstEpisodePage, headers = defaultHeaders, referer = url)
                        .document
                        .select("div.episodelist > ul > li, .episodelist li, .eplister li")
                        .mapNotNull { it.selectFirst("a[href]")?.toEpisodeOrNull() }
                        .distinctBy { it.data }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }

        if (poster.isNullOrBlank()) {
            poster = findPoster(document.body(), url)
        }

        return if (tvTag == TvType.Anime && episodes.size > 1) {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.Anime,
                episodes.reversed()
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val movieData = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                movieData
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toEpisodeOrNull(): Episode? {
        val href = attr("href").absoluteUrl(mainUrl)
        if (href.isBlank()) return null

        val epText = select("span, .epl-title, .epcur, .entry-title").text()
            .ifBlank { text() }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "Episode" }

        val episode = Regex("""(?i)(?:episode|eps|ep|e)\s*\.?\s*(\d{1,4})""")
            .find(epText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(epText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val poster = findPoster(this, href)

        return newEpisode(href) {
            this.name = epText
            this.episode = episode
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders, referer = "$mainUrl/")
        val document = response.document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String = name) {
            val finalUrl = rawUrl
                ?.decodeEscaped()
                ?.absoluteUrl(data)
                ?.takeIf { it.isNotBlank() }
                ?: return

            if (!emitted.add(finalUrl)) return

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = finalUrl,
                    type = inferType(finalUrl)
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf(
                        "Referer" to data,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                }
            )
        }

        document.select("option[data-index], .mobius option, select option").forEach { option ->
            val base64 = option.attr("value")
            if (base64.isBlank()) return@forEach

            val decodedHtml = runCatching { base64Decode(base64) }.getOrElse {
                Log.w("Donghuastream", "Base64 decode failed")
                return@forEach
            }

            val parsed = Jsoup.parse(decodedHtml)
            val iframeUrl = parsed.selectFirst("iframe[src]")?.attr("src")
                ?: parsed.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
                ?: parsed.selectFirst("a[href]")?.attr("href")

            val finalUrl = iframeUrl
                ?.decodeEscaped()
                ?.absoluteUrl(data)
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach

            val label = option.text().trim().ifBlank { finalUrl.hostName() }

            when {
                finalUrl.contains(".mp4", true) || finalUrl.contains(".m3u8", true) -> {
                    emitDirect(finalUrl, label)
                }
                else -> {
                    runCatching {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }.onSuccess { success ->
                        if (success) emitted.add(finalUrl)
                    }
                }
            }
        }

        val html = response.text
        val patterns = listOf(
            Regex("""file\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            Regex("""source\s*[:=]\s*['"]([^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                emitDirect(match.groupValues.getOrNull(1), "$name - Direct")
            }
        }

        return emitted.isNotEmpty()
    }

    private fun findPoster(element: Element, pageUrl: String): String? {
        val boxes = listOfNotNull(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImage(box, pageUrl)?.let { return it }
            box.select("img, source, div, span, a").forEach { child ->
                extractImage(child, pageUrl)?.let { return it }
            }
        }

        return null
    }

    private fun extractImage(element: Element, pageUrl: String): String? {
        val attrs = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-image",
            "data-img",
            "src",
            "poster"
        )

        attrs.forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isImageCandidate()) return value.absoluteUrl(pageUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val src = element.attr(attr)
                .split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isImageCandidate() }

            if (!src.isNullOrBlank()) return src.absoluteUrl(pageUrl)
        }

        val style = element.attr("style")
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.absoluteUrl(pageUrl) }

        return null
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false
        if (contains("blank", true) || contains("placeholder", true) || contains("spacer", true)) return false
        return contains(".jpg", true) ||
            contains(".jpeg", true) ||
            contains(".png", true) ||
            contains(".webp", true) ||
            contains("/wp-content/uploads/", true)
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        return if (rawUrl.contains("{page}")) {
            if (page <= 1) {
                rawUrl
                    .replace(Regex("""/?page/\{page}/?"""), "/")
                    .replace("page={page}", "page=1")
                    .replace(Regex("""//+$"""), "/")
            } else {
                rawUrl.replace("{page}", page.toString())
            }
        } else if (page <= 1) {
            rawUrl
        } else {
            val clean = rawUrl.trimEnd('/')
            when {
                clean.contains("?") -> "$clean&page=$page"
                else -> "$clean/page/$page/"
            }
        }
    }

    private fun String.absoluteUrl(baseUrl: String): String {
        val value = trim().decodeEscaped()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> {
                val base = baseUrl.substringBeforeLast("/", mainUrl)
                "$base/$value"
            }
        }
    }

    private fun String.decodeEscaped(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun String.hostName(): String {
        return runCatching {
            substringAfter("://")
                .substringBefore("/")
                .substringBeforeLast(".")
                .substringAfterLast(".")
        }.getOrDefault(name)
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}