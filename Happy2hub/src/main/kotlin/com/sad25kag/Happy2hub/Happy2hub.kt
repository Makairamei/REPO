package com.sad25kag.Happy2hub

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.VPNStatus
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
        "tag/brazzersexxtra" to "Brazzer",
        "tag/porn" to "All Porn",
        "tag/18" to "All Videos",

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
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = headers,
            timeout = 30L
        ).document

        val home = parseHomeCards(document)

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

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')

        return when {
            page <= 1 && cleanPath.isBlank() -> mainUrl
            page <= 1 -> "$mainUrl/$cleanPath/"
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun parseHomeCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        // Selector lama dipertahankan karena ini yang sebelumnya normal:
        // gambar muncul, judul benar, dan kartu tidak tercampur menu/header.
        document.select("div.content-wrap > div > div > div").forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h4 a[href], h3 a[href], h2 a[href]")
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("h4 a")?.text()?.trim(),
            selectFirst("h3 a")?.text()?.trim(),
            selectFirst("h2 a")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("a img[alt]")?.attr("alt")?.trim()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        if (title.length < 3) return null

        val poster = fixUrlNull(
            selectFirst("a img")?.getImageAttr()
                ?: selectFirst("img")?.getImageAttr()
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

        return blockedPrefixes.any {
            path == it.trimEnd('/') || path.startsWith(it)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
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

            val pageResults = parseHomeCards(document)
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
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = headers, timeout = 30L).document

        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.entry-title, h1")
                ?.text()
                ?.trim()
                ?.cleanTitle()
            ?: url.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.wp-post-image, .entry-content img, article img, img")?.getImageAttr()
        )

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?: document.selectFirst(".entry-content p, .entry-content, article")
                ?.text()
                ?.trim()

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
            recommendations = parseHomeCards(document)
                .filter { it.url != url }
                .distinctBy { it.url }
        }
    }

    private suspend fun parseEpisodes(
        document: Document,
        pageUrl: String,
        poster: String?
    ): List<Episode> {
        val allEpisodes = linkedMapOf<String, Episode>()

        val sourceDocuments = mutableListOf<Document>()
        sourceDocuments.add(document)

        // Happy2hub sering mengarah dulu ke halaman external/perantara.
        // Kita ambil beberapa kandidat, bukan cuma link pertama.
        document.select(
            "div.entry-content.clearfix p a[href], " +
                ".entry-content p a[href], " +
                "article p a[href], " +
                "a[href*='watch'], " +
                "a[href*='download'], " +
                "a[href*='episode']"
        ).mapNotNull { element ->
            element.attr("href").trim().takeIf { it.isNotBlank() }
        }.distinct()
            .take(8)
            .forEach { href ->
                val fixed = fixUrl(href)

                // External page atau playable link langsung.
                if (!fixed.contains(mainUrl, ignoreCase = true) || isPlayableUrl(fixed)) {
                    val externalDoc = runCatching {
                        app.get(
                            fixed,
                            headers = headers,
                            referer = pageUrl,
                            timeout = 30L
                        ).document
                    }.getOrNull()

                    if (externalDoc != null) {
                        sourceDocuments.add(externalDoc)
                    }
                }
            }

        sourceDocuments.forEach { sourceDocument ->
            parseEpisodeBlocks(sourceDocument, poster).forEach { episode ->
                allEpisodes[episode.data] = episode
            }
        }

        if (allEpisodes.isEmpty()) {
            sourceDocuments.forEach { sourceDocument ->
                val playableLinks = collectPlayableLinks(sourceDocument)

                if (playableLinks.isNotEmpty()) {
                    allEpisodes["Episode 1"] = newEpisode(playableLinks.joinToString(",")) {
                        name = "Episode 1"
                        episode = 1
                        posterUrl = poster
                    }
                    return@forEach
                }
            }
        }

        return allEpisodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(pageUrl) {
                        name = "Episode 1"
                        episode = 1
                        posterUrl = poster
                    }
                )
            }
    }

    private fun parseEpisodeBlocks(
        document: Document,
        poster: String?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val episodeHeaders = document.select(
            "div.entry-content.clearfix h2:contains(Episode), " +
                "div.entry-content.clearfix h3:contains(Episode), " +
                "div.entry-content.clearfix h4:contains(Episode), " +
                "div.entry-content.clearfix h5:contains(Episode), " +
                ".entry-content h2:contains(Episode), " +
                ".entry-content h3:contains(Episode), " +
                ".entry-content h4:contains(Episode), " +
                ".entry-content h5:contains(Episode), " +
                "h2:contains(Episode), " +
                "h3:contains(Episode), " +
                "h4:contains(Episode), " +
                "h5:contains(Episode)"
        )

        episodeHeaders.forEachIndexed { index, header ->
            val epText = header.text().trim()
            val epNo = extractEpisodeNumber(epText) ?: index + 1
            val links = linkedSetOf<String>()

            var current = header.nextElementSibling()

            while (current != null) {
                val tag = current.tagName().lowercase()
                val text = current.text()

                val isNextEpisodeHeader = (
                    tag == "h2" ||
                        tag == "h3" ||
                        tag == "h4" ||
                        tag == "h5"
                    ) && text.contains("Episode", ignoreCase = true)

                if (isNextEpisodeHeader) break

                current.collectPlayableLinksFromElement().forEach { link ->
                    links.add(link)
                }

                current = current.nextElementSibling()
            }

            if (links.isNotEmpty()) {
                episodes.add(
                    newEpisode(links.joinToString(",")) {
                        name = "Episode $epNo"
                        episode = epNo
                        posterUrl = poster
                    }
                )
            }
        }

        return episodes
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
                "a[href*='vidhide'], " +
                "a[href*='vidoza'], " +
                "a[href*='luluvdo'], " +
                "a[href*='dailymotion']"
        ).forEach { element ->
            element.collectPlayableLinksFromElement().forEach { link ->
                links.add(link)
            }
        }

        extractMediaUrls(document.html()).forEach { link ->
            links.add(link)
        }

        return links.toList()
    }

    private fun Element.collectPlayableLinksFromElement(): List<String> {
        val links = linkedSetOf<String>()

        select(
            "iframe[src], " +
                "embed[src], " +
                "video[src], " +
                "source[src], " +
                "a[href], " +
                "[data-src], " +
                "[data-url], " +
                "[data-video], " +
                "[data-file]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .trim()

            if (raw.isBlank()) return@forEach

            val fixed = fixUrl(raw)

            if (isPlayableUrl(fixed)) {
                links.add(fixed)
            }
        }

        return links.toList()
    }

    private fun isPlayableUrl(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains("voe.", true) ||
            url.contains("voe.sx", true) ||
            url.contains("pixeldrain", true) ||
            url.contains("filemoon", true) ||
            url.contains("streamtape", true) ||
            url.contains("dood", true) ||
            url.contains("mixdrop", true) ||
            url.contains("streamwish", true) ||
            url.contains("vidhide", true) ||
            url.contains("vidoza", true) ||
            url.contains("luluvdo", true) ||
            url.contains("dailymotion", true) ||
            url.contains("/embed/", true)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val links = data.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var found = false

        for (rawLink in links) {
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
                    val directSuccess = loadExtractor(
                        link,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )

                    if (directSuccess) {
                        found = true
                    } else {
                        val nestedLinks = resolveNestedLinks(link)

                        nestedLinks.forEach { nested ->
                            val success = when {
                                nested.contains(".m3u8", true) -> {
                                    generateM3u8(
                                        source = name,
                                        streamUrl = nested,
                                        referer = link
                                    ).forEach(callback)
                                    true
                                }

                                nested.contains(".mp4", true) -> {
                                    callback(
                                        newExtractorLink(
                                            source = name,
                                            name = name,
                                            url = nested,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            referer = link
                                            quality = getQualityFromName(nested).takeIf {
                                                it != Qualities.Unknown.value
                                            } ?: Qualities.Unknown.value
                                        }
                                    )
                                    true
                                }

                                else -> loadExtractor(
                                    nested,
                                    link,
                                    subtitleCallback,
                                    callback
                                )
                            }

                            if (success) found = true
                        }
                    }
                }
            }
        }

        if (!found && data.startsWith(mainUrl)) {
            val document = runCatching {
                app.get(data, headers = headers, timeout = 30L).document
            }.getOrNull()

            document?.let {
                collectPlayableLinks(it).forEach { link ->
                    val success = loadExtractor(
                        link,
                        data,
                        subtitleCallback,
                        callback
                    )

                    if (success) found = true
                }
            }
        }

        return found
    }

    private suspend fun resolveNestedLinks(url: String): List<String> {
        val document = runCatching {
            app.get(
                url,
                headers = headers,
                referer = mainUrl,
                timeout = 30L
            ).document
        }.getOrNull() ?: return emptyList()

        return collectPlayableLinks(document)
    }

    private fun extractMediaUrls(html: String): List<String> {
        val cleaned = html.cleanEscaped()
        val links = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""https?://[^"'\\\s<>]+?(?:voe\.sx|pixeldrain|filemoon|streamtape|dood|mixdrop|streamwish|vidhide|vidoza|luluvdo|dailymotion)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .map { it.value.cleanEscaped() }
            .forEach { links.add(it) }

        Regex("""(?:file|src|source|url|video|videoUrl|data-src|data-url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter { isPlayableUrl(it) }
            .forEach { links.add(fixUrl(it)) }

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

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
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