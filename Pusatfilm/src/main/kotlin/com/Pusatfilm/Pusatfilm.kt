package com.pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class Pusatfilm : MainAPI() {
    override var mainUrl = "https://v3.pusatfilm21info.com"
    override var name = "Pusatfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/country/australia/page/%d/" to "Australia",
        "$mainUrl/country/canada/page/%d/" to "Canada",
        "$mainUrl/country/china/page/%d/" to "China",
        "$mainUrl/country/colombia/page/%d/" to "Colombia",
        "$mainUrl/country/france/page/%d/" to "France",
        "$mainUrl/country/germany/page/%d/" to "Germany",
        "$mainUrl/country/hong-kong/page/%d/" to "Hong Kong",
        "$mainUrl/country/iceland/page/%d/" to "Iceland",
        "$mainUrl/country/india/page/%d/" to "India",
        "$mainUrl/country/ireland/page/%d/" to "Ireland",
        "$mainUrl/country/italy/page/%d/" to "Italy",
        "$mainUrl/country/japan/page/%d/" to "Japan",
        "$mainUrl/country/korea/page/%d/" to "Korea",
        "$mainUrl/country/malaysia/page/%d/" to "Malaysia",
        "$mainUrl/country/mexico/page/%d/" to "Mexico",
        "$mainUrl/country/netherlands/page/%d/" to "Netherlands",
        "$mainUrl/country/russian-federation/page/%d/" to "Russian Federation",
        "$mainUrl/country/south-africa/page/%d/" to "South Africa",
        "$mainUrl/country/spain/page/%d/" to "Spain",
        "$mainUrl/country/taiwan/page/%d/" to "Taiwan",
        "$mainUrl/country/thailand/page/%d/" to "Thailand",
        "$mainUrl/country/turkey/page/%d/" to "Turkey",
        "$mainUrl/country/united-kingdom/page/%d/" to "United Kingdom",
        "$mainUrl/country/usa/page/%d/" to "USA"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = request.data.format(page)
        val doc = app.get(url).document
        val home = doc.select("article, .post, .item, .ml-item, .movie-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, home, isHorizontalImages = true)),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/page/1/?s=$encodedQuery"
        )

        for (url in urls) {
            val results = runCatching {
                app.get(url).document
                    .select("article, .post, .item, .ml-item, .movie-item")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrDefault(emptyList())

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title, h1, .single-title, .post-title")
            ?.text()
            ?.cleanTitle()
            ?: ""

        val poster = doc.selectFirst(
            ".poster img, .thumb img, .post-thumbnail img, img.wp-post-image, article img, meta[property=og:image]"
        )?.let { element ->
            if (element.tagName() == "meta") element.attr("content") else element.getImageAttr()
        }?.let { fixUrlNull(it) }

        val plot = doc.selectFirst(
            ".entry-content p, .desc, .description, .synopsis, .post-content p"
        )?.text()?.trim()

        val tags = doc.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = doc.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
        val recommendations = doc.select(".related article, .film-rekomendasi article, .rekomendasi article, .post, article")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(12)

        val tvType = if (doc.text().contains(Regex("S\\d+\\s*E\\d+|Season\\s+\\d+|Episode\\s+\\d+", RegexOption.IGNORE_CASE))) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(data).document
        val pageHtml = document.outerHtml()
        val collected = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], iframe[data-lazy-src], embed[src], source[src], video[src], " +
                "a[href], button[data-src], button[data-url], button[data-link], button[data-frame], " +
                "div[data-src], div[data-url], div[data-link], div[data-frame], li[data-src], li[data-url], li[data-frame]"
        ).forEach { element ->
            listOf("src", "data-src", "data-lazy-src", "data-url", "data-link", "data-frame", "data-embed", "href")
                .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .flatMap { expandCandidate(it, data) }
                .forEach { collected.add(it) }
        }

        extractUrlsFromText(pageHtml, data).forEach { collected.add(it) }

        var found = false
        collected
            .filter { it.isPlayableCandidate() }
            .distinct()
            .forEach { link ->
                found = true
                when {
                    link.isDirectVideo() -> emitDirectLink(link, data, callback)
                    else -> {
                        val loaded = runCatching {
                            loadExtractor(link, data, subtitleCallback, callback)
                        }.getOrDefault(false)

                        if (!loaded) {
                            runCatching {
                                val nestedDoc = app.get(link, referer = data).document
                                nestedDoc.select("iframe[src], iframe[data-src], a[href], source[src], video[src]")
                                    .flatMap { element ->
                                        listOf("src", "data-src", "data-url", "data-link", "href")
                                            .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                                            .flatMap { expandCandidate(it, link) }
                                    }
                                    .plus(extractUrlsFromText(nestedDoc.outerHtml(), link))
                                    .filter { it.isPlayableCandidate() && it != link }
                                    .distinct()
                                    .forEach { nested ->
                                        found = true
                                        if (nested.isDirectVideo()) {
                                            emitDirectLink(nested, link, callback)
                                        } else {
                                            loadExtractor(nested, link, subtitleCallback, callback)
                                        }
                                    }
                            }
                        }
                    }
                }
            }

        return found
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst(
            "h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[rel=bookmark], a[href]"
        ) ?: return null

        val rawHref = titleElement.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(rawHref)
        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/genre/") || href.contains("/country/") || href.contains("/year/") || href.contains("youtube.com")) return null

        val title = titleElement.text().ifBlank {
            selectFirst("h2, h3, .entry-title, .title")?.text().orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("alt").orEmpty()
        }.cleanTitle()

        if (title.isBlank()) return null

        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        val tvType = if (text().contains(Regex("S\\d+\\s*E\\d+|Season\\s+\\d+|Episode\\s+\\d+", RegexOption.IGNORE_CASE))) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    private fun Element.getImageAttr(): String {
        return attr("data-src")
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("src") }
            .ifBlank { attr("content") }
            .trim()
    }

    private suspend fun emitDirectLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.cleanupUrl()
        val quality = Regex("(?i)(\\d{3,4})p").find(cleanUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value

        callback.invoke(
            if (cleanUrl.contains(".m3u8", ignoreCase = true)) {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            } else {
                newExtractorLink(
                    source = name,
                    name = name,
                    url = cleanUrl
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            }
        )
    }

    private fun extractUrlsFromText(text: String, referer: String): List<String> {
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val directUrls = Regex("""(?i)(https?:)?//[^\s"'<>]+(?:m3u8|mp4|webm|mkv|avi|embed|file|stream|video|player)[^\s"'<>]*""")
            .findAll(normalized)
            .mapNotNull { it.value.normalizeUrl(referer) }
            .toList()

        val quotedPayloads = Regex("""["']([A-Za-z0-9+/=_-]{40,})["']""")
            .findAll(normalized)
            .flatMap { match -> expandCandidate(match.groupValues[1], referer).asSequence() }
            .toList()

        val encodedUrls = Regex("""(?i)https?%3A%2F%2F[^\s"'<>]+""")
            .findAll(normalized)
            .mapNotNull { safeUrlDecode(it.value).normalizeUrl(referer) }
            .toList()

        return (directUrls + quotedPayloads + encodedUrls)
            .map { it.cleanupUrl() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun expandCandidate(value: String, referer: String): List<String> {
        val output = linkedSetOf<String>()
        val cleaned = value.trim().cleanupUrl()
        if (cleaned.isBlank() || cleaned == "#" || cleaned.startsWith("javascript:", true)) return emptyList()

        cleaned.normalizeUrl(referer)?.let { output.add(it) }
        safeUrlDecode(cleaned).normalizeUrl(referer)?.let { output.add(it) }

        val decodedBase64 = safeBase64Decode(cleaned)
        if (!decodedBase64.isNullOrBlank()) {
            decodedBase64.normalizeUrl(referer)?.let { output.add(it) }
            extractUrlsFromText(decodedBase64, referer).forEach { output.add(it) }
        }

        return output.toList()
    }

    private fun String.normalizeUrl(referer: String): String? {
        val value = trim().cleanupUrl()
        if (value.isBlank() || value == "#") return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            value.startsWith("./") -> referer.substringBeforeLast('/') + value.removePrefix(".")
            else -> null
        }?.cleanupUrl()
    }

    private fun String.cleanupUrl(): String {
        return trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .substringBefore("\"")
            .substringBefore("'")
            .substringBefore("<")
            .removeSuffix("\\")
            .trim()
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (!startsWith("http")) return false
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) return false
        if (lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("discord")) return false
        if (lower.contains("wp-content") && !lower.contains(".mp4") && !lower.contains(".m3u8")) return false
        return lower.contains("kotakajaib") || lower.contains("embed") || lower.contains("player") ||
            lower.contains("stream") || lower.contains("file") || lower.contains("video") ||
            lower.contains("m3u8") || lower.contains("mp4") || lower.contains("webm") ||
            lower.contains("gdplay") || lower.contains("gdrive") || lower.contains("dood") ||
            lower.contains("streamtape") || lower.contains("filemoon") || lower.contains("vidhide") ||
            lower.contains("voe") || lower.contains("mixdrop") || lower.contains("upstream") ||
            lower.contains("sb") || lower.contains("fembed") || lower.contains("vidsrc")
    }

    private fun String.isDirectVideo(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains("videoplayback")
    }

    private fun safeUrlDecode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private fun safeBase64Decode(value: String): String? {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        }.getOrNull()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("(?i)^nonton\\s+film\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .trim()
    }
}
