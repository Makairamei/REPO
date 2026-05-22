package com.maxstream

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Maxstream : MainAPI() {
    override var mainUrl = "https://maxstream.tv"
    override var name = "MAXStream"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "home" to "Beranda",
        "movies" to "Movies",
        "tv-series" to "TV Series",
        "tv-channels" to "TV Channels",
        "live" to "Live",
        "shorts" to "Shorts"
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
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers).document

        val home = parseCards(document, request.data)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = false
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            cleanPath.isBlank() -> "$mainUrl/home"
            page <= 1 -> "$mainUrl/$cleanPath"
            else -> "$mainUrl/$cleanPath?page=$page"
        }
    }

    private fun parseCards(
        document: Document,
        section: String
    ): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "a[href], " +
                "article a[href], " +
                ".card a[href], " +
                ".item a[href], " +
                ".content a[href], " +
                ".swiper-slide a[href], " +
                "[data-testid] a[href]"
        ).forEach { element ->
            element.toSearchResult(section)?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(section: String): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val rawTitle = listOf(
            selectFirst("h1")?.text()?.trim(),
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst(".title")?.text()?.trim(),
            selectFirst("[class*=title]")?.text()?.trim(),
            anchor.attr("aria-label").trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("drawer-home", true) &&
                !it.equals("shorts-title", true) &&
                !it.equals("drawer-live", true) &&
                !it.equals("web-drawer-tv-channels", true) &&
                !it.equals("drawer-tv-shows", true) &&
                !it.equals("drawer-movies", true) &&
                !it.equals("auth-login", true) &&
                !it.equals("Search", true) &&
                !it.equals("Menu", true) &&
                !it.equals("MAXStream Logo", true)
        } ?: return null

        val title = rawTitle.cleanTitle()
        if (title.length < 2) return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        val type = getTypeFromUrl(href, section)

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blocked = listOf(
            "home",
            "login",
            "onboarding",
            "auth",
            "signup",
            "profile",
            "account",
            "package",
            "packages",
            "subscription",
            "privacy",
            "terms"
        )

        if (blocked.any { path == it || path.startsWith("$it/") }) return true

        // Drawer/category route tetap boleh dibuka sebagai row, tapi jangan dijadikan kartu.
        val menuOnly = listOf(
            "movies",
            "tv-series",
            "tv-channels",
            "live",
            "shorts"
        )

        return menuOnly.any { path == it }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")

        val urls = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search/$encoded",
            "$mainUrl/movies",
            "$mainUrl/tv-series",
            "$mainUrl/home"
        )

        val results = linkedMapOf<String, SearchResponse>()

        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = headers).document
            }.getOrNull() ?: return@forEach

            parseCards(document, url.substringAfter(mainUrl).trim('/'))
                .filter { item ->
                    item.name.contains(keyword, ignoreCase = true)
                }
                .forEach { item ->
                    results[item.url] = item
                }
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst(
            "h1, " +
                "h1[class*=title], " +
                "[class*=title] h1, " +
                "meta[property=og:title], " +
                "meta[name=title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "img[class*=poster], " +
                    "img[class*=cover], " +
                    "picture img, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "meta[name=description], " +
                ".description, " +
                "[class*=description], " +
                ".synopsis, " +
                "[class*=synopsis]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val type = getTypeFromUrl(url, "")

        val recommendations = parseCards(document, "")
            .filter { it.url != url }
            .distinctBy { it.url }

        return if (type == TvType.TvSeries) {
            val episodes = parseEpisodes(document, url)

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                plot = description
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                plot = description
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(
        document: Document,
        fallbackUrl: String
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], " +
                "a[href*='season'], " +
                "[class*=episode] a[href], " +
                "[class*=season] a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
            if (!href.startsWith(mainUrl)) return@forEachIndexed

            val text = element.text().trim()
            val epNum = extractEpisodeNumber(text, href) ?: index + 1

            episodes[href] = newEpisode(href) {
                name = text.ifBlank { "Episode $epNum" }
                episode = epNum
            }
        }

        return episodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(fallbackUrl) {
                        name = "Episode 1"
                        episode = 1
                    }
                )
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = headers)
        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        extractMediaUrls(html).forEach { mediaUrl ->
            val fixed = fixUrl(mediaUrl)

            when {
                fixed.contains(".m3u8", true) -> directLinks.add(fixed)
                fixed.contains(".mp4", true) -> directLinks.add(fixed)
                else -> embedLinks.add(fixed)
            }
        }

        document.select(
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
                else -> embedLinks.add(fixed)
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
                        } ?: Qualities.Unknown.value
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

    private fun getTypeFromUrl(
        url: String,
        section: String
    ): TvType {
        val value = "$url $section"

        return when {
            value.contains("tv-series", true) -> TvType.TvSeries
            value.contains("series", true) -> TvType.TvSeries
            value.contains("episode", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
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

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)-?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractMediaUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map {
                runCatching {
                    java.net.URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""(?:file|src|source|url|video)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .forEach { urls.add(it) }

        return urls.toList()
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
            .replace(Regex("""\s+-\s+MAXStream.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+MAXStream\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}