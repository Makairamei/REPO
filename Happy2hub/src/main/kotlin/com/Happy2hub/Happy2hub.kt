package com.Happy2hub

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
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

class Happy2hub : MainAPI() {
    override var mainUrl = "https://happy2hub.eu"
    override var name = "Happy2hub"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "ullu-a" to "Ullu",
        "tag/primeplay-watch-online" to "Primeplay",
        "tag/altt-watch-online" to "Altt",
        "tag/bigshots-ott-watch-online" to "Bigshots",
        "tag/naari-magazine-watch-online" to "Naari",
        "tag/desiflix-originals-watch-online" to "Desiflix",
        "tag/idiot-boxx-watch-online" to "Idiot Boxx",
        "tag/hotshots-watch-online" to "Hotshots",
        "tag/mx-player-watch-online" to "MX Player",
        "tag/namastey-flix-originals" to "Namastey Flix",
        "tag/mojflix-watch-online" to "Mojflix",
        "tag/mangoflix-watch-online" to "Mangoflix",
        "tag/hothit-watch-online" to "Hothit",
        "tag/18" to "All Videos",
        "tag/brazzersexxtra" to "Brazzer",
        "tag/porn" to "All Porn",

        "category/web-series" to "Web Series",
        "category/movies" to "Movies",
        "category/hindi" to "Hindi",
        "category/uncut" to "Uncut",
        "category/short-film" to "Short Film"
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
        val document = app.get(url, headers = headers, timeout = 30L).document

        val home = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    ".page-numbers:contains(»), " +
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

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "div.content-wrap > div > div > div, " +
                "article, " +
                ".post, " +
                ".item, " +
                ".entry, " +
                ".thumbnail, " +
                ".blog-post, " +
                "a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "h4 a[href], " +
                    "h3 a[href], " +
                    "h2 a[href], " +
                    ".entry-title a[href], " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("h4 a")?.text()?.trim(),
            selectFirst("h3 a")?.text()?.trim(),
            selectFirst("h2 a")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true) &&
                !it.equals("Search", true)
        }?.cleanTitle()
            ?: return null

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

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "tag/",
            "category/",
            "page/",
            "wp-login",
            "privacy",
            "dmca",
            "contact",
            "terms",
            "about"
        )

        return blockedPrefixes.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val results = linkedMapOf<String, SearchResponse>()

        for (page in 1..10) {
            val url = if (page == 1) {
                "$mainUrl/?s=$encoded"
            } else {
                "$mainUrl/page/$page/?s=$encoded"
            }

            val document = runCatching {
                app.get(url, headers = headers, timeout = 30L).document
            }.getOrNull() ?: break

            val pageResults = parseCards(document)

            if (pageResults.isEmpty()) break

            pageResults.forEach { item ->
                results[item.url] = item
            }

            val hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    ".page-numbers:contains(»), " +
                    "a[href*='/page/${page + 1}/']"
            ) != null

            if (!hasNext) break
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, timeout = 30L).document

        val title = document.selectFirst(
            "meta[property=og:title], " +
                "h1.entry-title, " +
                "h1, " +
                ".entry-title"
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
                    "img.wp-post-image, " +
                    ".entry-content img, " +
                    "article img, " +
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
                ".entry-content p, " +
                ".entry-content, " +
                "article"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "a[href*='/tag/'], " +
                "a[href*='/category/'], " +
                ".tags a, " +
                ".cat-links a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document, url, poster)

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.NSFW,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.tags = tags
            recommendations = parseCards(document)
                .filter { it.url != url }
                .distinctBy { it.url }
        }
    }

    private suspend fun parseEpisodes(
        document: Document,
        url: String,
        poster: String?
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        val externalPage = document.select(
            "div.entry-content.clearfix p a[href], " +
                ".entry-content p a[href], " +
                "article p a[href]"
        ).firstOrNull { element ->
            val href = element.attr("href")
            href.isNotBlank() && !href.contains(mainUrl)
        }?.attr("href")?.trim()

        val sourceDocument = if (!externalPage.isNullOrBlank()) {
            runCatching {
                app.get(externalPage, headers = headers, referer = url, timeout = 30L).document
            }.getOrDefault(document)
        } else {
            document
        }

        sourceDocument.select(
            "div.entry-content.clearfix h4:contains(Episode), " +
                "div.entry-content.clearfix h5:contains(Episode), " +
                ".entry-content h4:contains(Episode), " +
                ".entry-content h5:contains(Episode), " +
                "h4:contains(Episode), " +
                "h5:contains(Episode)"
        ).forEachIndexed { index, episodeHeader ->
            val epText = episodeHeader.text().trim()
            val epNum = extractEpisodeNumber(epText) ?: index + 1
            val episodeLinks = linkedSetOf<String>()

            var nextElement = episodeHeader.nextElementSibling()

            while (nextElement != null) {
                val tag = nextElement.tagName().lowercase()

                if (
                    tag == "h4" ||
                    tag == "h5" ||
                    nextElement.text().contains("Episode", ignoreCase = true)
                ) {
                    break
                }

                nextElement.select("a[href]").forEach { linkElement ->
                    val link = linkElement.attr("href").trim()

                    if (link.isNotBlank()) {
                        episodeLinks.add(link)
                    }
                }

                nextElement = nextElement.nextElementSibling()
            }

            if (episodeLinks.isNotEmpty()) {
                episodes["Episode $epNum"] = newEpisode(
                    episodeLinks.joinToString(",")
                ) {
                    name = "Episode $epNum"
                    episode = epNum
                    posterUrl = poster
                }
            }
        }

        if (episodes.isEmpty()) {
            val directLinks = collectPlayableLinks(sourceDocument)

            if (directLinks.isNotEmpty()) {
                episodes["Episode 1"] = newEpisode(
                    directLinks.joinToString(",")
                ) {
                    name = "Episode 1"
                    episode = 1
                    posterUrl = poster
                }
            }
        }

        return episodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        name = "Episode 1"
                        episode = 1
                        posterUrl = poster
                    }
                )
            }
    }

    private fun collectPlayableLinks(document: Document): List<String> {
        val links = linkedSetOf<String>()

        document.select(
            "iframe[src], " +
                "embed[src], " +
                "video[src], " +
                "source[src], " +
                "a[href*='.mp4'], " +
                "a[href*='.m3u8'], " +
                "a[href*='voe'], " +
                "a[href*='pixeldrain'], " +
                "a[href*='filemoon'], " +
                "a[href*='streamtape'], " +
                "a[href*='dood'], " +
                "a[href*='mixdrop'], " +
                "a[href*='streamwish'], " +
                "a[href*='vidhide']"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) {
                links.add(fixUrl(raw))
            }
        }

        return links.toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linksList = data.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var found = false

        for (rawLink in linksList) {
            val link = fixUrl(rawLink)

            when {
                link.contains(".m3u8", true) -> {
                    generateM3u8(
                        source = name,
                        streamUrl = link,
                        referer = mainUrl
                    ).forEach(callback)

                    found = true
                }

                link.contains(".mp4", true) -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = link,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = mainUrl
                            quality = getQualityFromName(link).takeIf {
                                it != Qualities.Unknown.value
                            } ?: Qualities.Unknown.value
                        }
                    )

                    found = true
                }

                else -> {
                    val success = loadExtractor(
                        link,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )

                    if (success) found = true
                }
            }
        }

        if (!found && data.startsWith(mainUrl)) {
            val document = app.get(data, headers = headers, timeout = 30L).document

            collectPlayableLinks(document).forEach { link ->
                val success = loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )

                if (success) found = true
            }
        }

        return found
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

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+Happy2hub.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Watch Online.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Download.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}