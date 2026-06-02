package com.sad25kag.Donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Donghub : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update&page={page}" to "Rilisan Terbaru",
        "$mainUrl/anime/?status=ongoing&order=update&page={page}" to "Series Ongoing",
        "$mainUrl/anime/?status=completed&order=update&page={page}" to "Series Completed",
        "$mainUrl/anime/?type=movie&order=update&page={page}" to "Movie",
        "$mainUrl/anime/?type=ona&order=update&page={page}" to "ONA",
        "$mainUrl/anime/?type=special&order=update&page={page}" to "Special",

        "$mainUrl/genres/2d/page/{page}/" to "2D",
        "$mainUrl/genres/action/page/{page}/" to "Action",
        "$mainUrl/genres/adventure/page/{page}/" to "Adventure",
        "$mainUrl/genres/cultivation/page/{page}/" to "Cultivation",
        "$mainUrl/genres/drama/page/{page}/" to "Drama",
        "$mainUrl/genres/fantasy/page/{page}/" to "Fantasy",
        "$mainUrl/genres/historical/page/{page}/" to "Historical",
        "$mainUrl/genres/isekai/page/{page}/" to "Isekai",
        "$mainUrl/genres/martial-arts/page/{page}/" to "Martial Arts",
        "$mainUrl/genres/mystery/page/{page}/" to "Mystery",
        "$mainUrl/genres/psychological/page/{page}/" to "Psychological",
        "$mainUrl/genres/reincarnation/page/{page}/" to "Reincarnation",
        "$mainUrl/genres/romance/page/{page}/" to "Romance",
        "$mainUrl/genres/sci-fi/page/{page}/" to "Sci-Fi",
        "$mainUrl/genres/super-power/page/{page}/" to "Super Power",
        "$mainUrl/genres/supranatural/page/{page}/" to "Supranatural",
        "$mainUrl/genres/urban-fantasy/page/{page}/" to "Urban Fantasy",
        "$mainUrl/genres/war/page/{page}/" to "War"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(
            buildPagedUrl(request.data, page),
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val items = document.select("div.listupd > article, article.bs, .listupd article, .bs, .listo > article, .listo article, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a.next, .pagination a.next, a[href*='page=${page + 1}'], a[href*='/page/${page + 1}/']").isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext || items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a[href], h2 > a[href], h3 > a[href], a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl)

        if (href.isBlank()) return null
        if (href.contains("/genres/", true) ||
            href.contains("/genre/", true) ||
            href.contains("/tag/", true) ||
            href.contains("/page/", true) ||
            href.contains("/author/", true)
        ) return null

        val title = anchor.attr("title").trim()
            .ifBlank { selectFirst(".tt, h2, h3, .entry-title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { anchor.text().trim() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (title.isBlank()) return null

        val poster = findPoster(this, href)
        val lowerText = (title + " " + text()).lowercase()
        val type = if (lowerText.contains("movie")) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        if (query.isBlank()) return emptyList()

        val list = mutableListOf<SearchResponse>()
        val encoded = query.urlEncoded()

        for (i in 1..3) {
            val url = if (i == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$i/?s=$encoded"
            val result = app.get(
                url,
                headers = defaultHeaders,
                referer = "$mainUrl/"
            ).document
                .select("div.listupd > article, article.bs, .listupd article, .bs")
                .mapNotNull { it.toSearchResult() }
                .filterNot { item -> list.any { it.url == item.url } }

            if (result.isEmpty()) break
            list.addAll(result)
        }

        return list
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul Donghub tidak ditemukan")

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

        var poster = document.selectFirst("div.ime > img, .thumb img, .bigcontent img")
            ?.let { findPoster(it, url) }
            ?: document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
                ?.attr("content")
                ?.absoluteUrl(url)

        if (poster.isNullOrBlank()) {
            poster = findPoster(document.body(), url)
        }

        val recommendations = document.select("div.listupd article.bs, .listupd article, article.bs, .listo article, article")
            .mapNotNull { it.toRecommendResult() }
            .distinctBy { it.url }

        val isMovie = infoText.contains("Movie", true) || tags.any { it.equals("Movie", true) }

        val episodes = document
            .select(".eplister li > a[href], .episodelist li > a[href], div.list-episode .episode-item a[href], #episodes a[href]")
            .mapNotNull { it.toEpisodeOrNull(poster) }
            .distinctBy { it.data }

        return if (!isMovie && episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val movieLink = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieLink) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toEpisodeOrNull(seriesPoster: String?): Episode? {
        val href = attr("href").absoluteUrl(mainUrl)
        if (href.isBlank()) return null

        val epTitle = selectFirst(".epl-title, span, .epcur")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: text().trim().ifBlank { "Episode" }

        val episodeNumber = Regex("""(?i)(?:episode|eps|ep|e)\s*\.?\s*(\d{1,4})""")
            .find(epTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(epTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val epPoster = findPoster(this, href) ?: seriesPoster

        return newEpisode(href) {
            name = epTitle
            episode = episodeNumber
            posterUrl = epPoster
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrl(mainUrl)

        if (href.isBlank()) return null
        if (href.contains("/genres/", true) ||
            href.contains("/genre/", true) ||
            href.contains("/tag/", true) ||
            href.contains("/page/", true) ||
            href.contains("/author/", true)
        ) return null

        val title = selectFirst(".tt, h2, h3, .entry-title")?.text()?.trim()
            ?.ifBlank { anchor.attr("title").trim() }
            ?.ifBlank { selectFirst("img")?.attr("alt").orEmpty() }
            ?.ifBlank { anchor.text().trim() }
            ?: return null

        val poster = findPoster(this, href)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
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
                    name,
                    label,
                    finalUrl,
                    INFER_TYPE
                ) {
                    referer = data
                    quality = getQualityFromName(label)
                    headers = streamHeaders(data)
                }
            )
        }

        document.select(".mobius option, option[data-index], select option").forEach { item ->
            val base64 = item.attr("value")
            if (base64.isBlank()) return@forEach

            val decoded = runCatching { base64Decode(base64) }.getOrNull() ?: return@forEach
            val doc = Jsoup.parse(decoded)

            val iframe = doc.selectFirst("iframe[src]")?.attr("src")
                ?: doc.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
                ?: doc.selectFirst("a[href]")?.attr("href")

            val fixedIframe = iframe
                ?.decodeEscaped()
                ?.absoluteUrl(data)
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach

            val label = item.text().trim().ifBlank { fixedIframe.hostName() }

            when {
                fixedIframe.contains(".mp4", true) || fixedIframe.contains(".m3u8", true) -> {
                    emitDirect(fixedIframe, label)
                }
                else -> {
                    runCatching {
                        loadExtractor(fixedIframe, data, subtitleCallback, callback)
                    }.onSuccess { success ->
                        if (success) emitted.add(fixedIframe)
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
        if (!rawUrl.contains("{page}")) {
            if (page <= 1) return rawUrl
            val clean = rawUrl.trimEnd('/')
            return when {
                clean.contains("?") -> "$clean&page=$page"
                else -> "$clean/page/$page/"
            }
        }

        return if (page <= 1) {
            rawUrl
                .replace("/page/{page}/", "/")
                .replace("/page/{page}", "/")
                .replace("page={page}", "page=1")
        } else {
            rawUrl.replace("{page}", page.toString())
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

    private fun String.hostName(): String {
        return runCatching {
            substringAfter("://")
                .substringBefore("/")
                .substringBeforeLast(".")
                .substringAfterLast(".")
        }.getOrDefault(name)
    }

    private fun streamHeaders(refererUrl: String): Map<String, String> {
        return mapOf(
            "Accept" to "*/*",
            "User-Agent" to USER_AGENT,
            "Referer" to refererUrl,
            "Origin" to mainUrl
        )
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
