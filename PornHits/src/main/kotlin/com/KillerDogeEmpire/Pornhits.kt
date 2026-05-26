package com.Phisher98

import android.util.Base64
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Pornhits : MainAPI() {
    override var mainUrl = "https://www.pornhits.com"
    override var name = "Pornhits"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/main/?p=%d&s=l" to "Latest",
        "$mainUrl/main/?p=%d&s=pd" to "Popular last day",
        "$mainUrl/main/?p=%d&s=bd" to "Top Rated day",
        "$mainUrl/main/?p=%d&s=pw" to "Popular last week",
        "$mainUrl/main/?p=%d&s=bw" to "Top Rated week",
        "$mainUrl/main/?p=%d&s=pm" to "Popular last month",
        "$mainUrl/main/?p=%d&s=bm" to "Top Rated month",
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Cache-Control" to "no-cache",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val preferredUrl = request.data.format(page)
        val fallbackUrl = preferredUrl.replace("/main/?", "/videos.php?")

        val document = getDocumentWithFallback(
            preferredUrl,
            fallbackUrl
        )

        val home = (document?.let { parseCards(it) } ?: emptyList<SearchResponse>())
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty() && document != null && hasNextPage(document, page)
        )
    }

    private suspend fun getDocumentWithFallback(vararg urls: String): Document? {
        for (url in urls.distinct()) {
            val result = runCatching {
                app.get(
                    url,
                    headers = headers,
                    referer = mainUrl,
                    timeout = 30L
                ).document
            }.getOrNull()

            if (result != null && parseCards(result).isNotEmpty()) {
                return result
            }
        }

        return urls.firstOrNull()?.let { url ->
            runCatching {
                app.get(
                    url,
                    headers = headers,
                    referer = mainUrl,
                    timeout = 30L
                ).document
            }.getOrNull()
        }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".pagination a[href*='p=${page + 1}'], " +
                "a[href*='p=${page + 1}&'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article.item:has(a), " +
                "div.item:has(a), " +
                ".list-videos article:has(a), " +
                ".video-item:has(a), " +
                ".video:has(a), " +
                ".thumb:has(a), " +
                ".card:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select(
                "a[href*='/video']:has(img), " +
                    "a[href*='videos.php']:has(img), " +
                    "a[href*='view_video']:has(img), " +
                    "a[href]:has(img)"
            ).forEach { element ->
                element.toSearchResult()?.let { results[it.url] = it }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a[href*='/video'], " +
                    "a[href*='videos.php'], " +
                    "a[href*='view_video'], " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            selectFirst("div.item-info h2.title")?.text(),
            selectFirst("h2.title")?.text(),
            selectFirst(".title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && !isNoiseTitle(it) }
            ?.cleanTitle()
            ?: return null

        val poster = fixUrlNull(
            image?.attr("data-original").takeIf { !it.isNullOrBlank() }
                ?: image?.attr("data-src").takeIf { !it.isNullOrBlank() }
                ?: image?.attr("data-lazy-src").takeIf { !it.isNullOrBlank() }
                ?: image?.attr("src").takeIf { !it.isNullOrBlank() }
        )?.cleanEscaped()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun isNoiseTitle(value: String): Boolean {
        val text = value.trim()
        return text.length < 2 || text.equals("Home", true) ||
            text.equals("Next", true) ||
            text.equals("Previous", true) ||
            text.equals("Pornhits", true) ||
            text.equals("Advertisement", true) ||
            text.contains("javascript", true)
    }

    private fun isBlockedUrl(url: String): Boolean {
        return url.contains("/login", true) ||
            url.contains("/signup", true) ||
            url.contains("/channels", true) ||
            url.contains("/models", true) ||
            url.contains("/pornstars", true) ||
            url.contains("/categories", true) ||
            url.contains("#")
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val urls = listOf(
            "$mainUrl/search/videos/$encodedQuery/$page/",
            "$mainUrl/videos.php?p=$page&q=$encodedQuery",
            "$mainUrl/main/?p=$page&q=$encodedQuery"
        )

        val results = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val document = getDocumentWithFallback(url) ?: continue
            parseCards(document).forEach { results[it.url] = it }
            if (results.isNotEmpty()) break
        }

        return newSearchResponseList(results.values.toList(), results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        ).document

        val title = listOf(
            document.selectFirst("section.video-holder div.video-info div.info-holder article#tab_video_info div.headline h1")?.text(),
            document.selectFirst("h1.video-title")?.text(),
            document.selectFirst(".video-info h1")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: name

        val poster = listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            Regex("""thumbnailUrl["']?\s*[:=]\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
                .find(document.html())?.groupValues?.getOrNull(1),
            document.selectFirst("video[poster]")?.attr("poster"),
            document.selectFirst(".video-holder img[src]")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.let { fixUrlNull(it.cleanEscaped()) }

        val tags = document.select(
            "#tab_video_info .block-details .info h3.item a, " +
                ".tags a, " +
                "a[href*='/categories/'], " +
                "a[href*='/tags/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = document.select(
            "div.related-videos div.list-videos article.item, " +
                ".related-videos article.item, " +
                ".related .item, " +
                ".list-videos article"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(
                data,
                headers = headers,
                referer = mainUrl,
                timeout = 30L
            )
        }.getOrNull() ?: return false

        val html = response.text.cleanEscaped()
        val document = response.document
        val directLinks = linkedMapOf<String, Int>()
        val embedLinks = linkedSetOf<String>()

        parseInitPlayer(html, directLinks)
        parseJsonLikePlayer(html, directLinks)
        parseEncodedValues(html, directLinks)
        extractPlayableUrls(html).forEach { link ->
            directLinks[normalizeUrl(link, data)] = qualityFromUrl(link)
        }

        document.select(
            "video source[src], " +
                "video[src], " +
                "source[src], " +
                "a[href*='.m3u8'], " +
                "a[href*='.mp4'], " +
                "a[href*='.webm']"
        ).forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            addCandidate(raw, data, directLinks, embedLinks)
        }

        document.select(
            "iframe[src], " +
                "embed[src], " +
                "a[href*='/embed/'], " +
                "a[href*='player'], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file], " +
                "[data-url]"
        ).forEach { element ->
            listOf("src", "href", "data-src", "data-video", "data-file", "data-url").forEach { attr ->
                addCandidate(element.attr(attr), data, directLinks, embedLinks)
            }
        }

        var found = false

        directLinks.entries.distinctBy { it.key }.forEach { (link, quality) ->
            if (emitDirectLink(link, data, quality, callback)) found = true
        }

        embedLinks.distinct().forEach { embed ->
            val extractorFound = runCatching {
                loadExtractor(embed, data, subtitleCallback, callback)
            }.getOrDefault(false)

            if (extractorFound) {
                found = true
            } else {
                resolveNestedLinks(embed, data).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)
                    if (isDirectVideo(fixed)) {
                        if (emitDirectLink(fixed, embed, qualityFromUrl(fixed), callback)) found = true
                    } else if (!isAdUrl(fixed)) {
                        val nestedFound = runCatching {
                            loadExtractor(fixed, embed, subtitleCallback, callback)
                        }.getOrDefault(false)
                        if (nestedFound) found = true
                    }
                }
            }
        }

        return found
    }

    private fun parseInitPlayer(html: String, directLinks: MutableMap<String, Int>) {
        val isVhq = html.contains("VHQ", true)
        Regex(
            """window\.initPlayer\s*\((.*?)\)\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).forEach { match ->
            val payload = match.groupValues[1]
            Regex("""["']([АВСDЕFGHIJKLМNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,~]{16,})["']""")
                .findAll(payload)
                .mapNotNull { customBase64DecoderOrNull(it.groupValues[1]) }
                .forEach { decoded ->
                    parseVideoArray(decoded, isVhq, directLinks)
                }
        }
    }

    private fun parseJsonLikePlayer(html: String, directLinks: MutableMap<String, Int>) {
        Regex(
            """(?:vpage_data|player_data|videoData|flashvars|sources)\s*=\s*(\{.*?\}|\[.*?\])\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).forEach { match ->
            val json = match.groupValues[1].cleanEscaped()
            parseVideoArray(json, false, directLinks)
            extractPlayableUrls(json).forEach { directLinks[it] = qualityFromUrl(it) }
        }

        Regex(
            """["'](?:file|src|source|video_url|videoUrl|hls|url)["']\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { match ->
            val value = decodeMaybe(match.groupValues[1])
            addDirectValue(value, directLinks)
        }
    }

    private fun parseEncodedValues(html: String, directLinks: MutableMap<String, Int>) {
        Regex("""(?:atob|decodeURIComponent)\s*\(\s*["']([^"']{20,})["']\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { decodeMaybe(it.groupValues[1]) }
            .forEach { decoded ->
                extractPlayableUrls(decoded).forEach { directLinks[it] = qualityFromUrl(it) }
                parseVideoArray(decoded, false, directLinks)
            }

        Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
            .findAll(html)
            .map { decodeMaybe(it.groupValues[1]) }
            .forEach { decoded ->
                extractPlayableUrls(decoded).forEach { directLinks[it] = qualityFromUrl(it) }
            }
    }

    private fun parseVideoArray(raw: String, isVhq: Boolean, directLinks: MutableMap<String, Int>) {
        val clean = raw.trim().cleanEscaped()
        if (clean.isBlank()) return

        val array = runCatching {
            when {
                clean.startsWith("[") -> JSONArray(clean)
                clean.startsWith("{") -> {
                    val obj = JSONObject(clean)
                    when {
                        obj.has("videos") -> obj.getJSONArray("videos")
                        obj.has("sources") -> obj.getJSONArray("sources")
                        else -> JSONArray().put(obj)
                    }
                }
                else -> JSONArray()
            }
        }.getOrNull() ?: return

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val format = item.optString("format")
                .ifBlank { item.optString("label") }
                .ifBlank { item.optString("quality") }

            val rawUrl = listOf(
                item.optString("video_url"),
                item.optString("file"),
                item.optString("src"),
                item.optString("url")
            ).firstOrNull { it.isNotBlank() } ?: continue

            var url = decodeMaybe(rawUrl)
            if (isVhq && url.isNotBlank() && !url.contains(".m3u8", true)) {
                url += if (url.contains("?")) "&f=video.m3u8" else "?f=video.m3u8"
            }

            addDirectValue(url, directLinks, qualityFromFormat(format))
            if (isVhq) break
        }
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableMap<String, Int>,
        embedLinks: MutableSet<String>
    ) {
        val decoded = decodeMaybe(raw).trim()
        if (decoded.isBlank()) return

        val fixed = normalizeUrl(decoded, baseUrl)
        if (fixed.isBlank() || isAdUrl(fixed)) return

        if (isDirectVideo(fixed)) {
            directLinks[fixed] = qualityFromUrl(fixed)
        } else if (fixed.startsWith("http", true)) {
            embedLinks.add(fixed)
        }
    }

    private fun addDirectValue(raw: String, directLinks: MutableMap<String, Int>, quality: Int? = null) {
        val fixed = normalizeUrl(raw, mainUrl)
        if (fixed.isBlank() || isAdUrl(fixed)) return
        if (isDirectVideo(fixed)) {
            directLinks[fixed] = quality ?: qualityFromUrl(fixed)
        }
    }

    private suspend fun resolveNestedLinks(url: String, referer: String): List<String> {
        val text = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        val links = linkedSetOf<String>()
        extractPlayableUrls(text).forEach { links.add(it) }

        Regex(
            """["'](?:file|src|source|video_url|videoUrl|hls|url)["']\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { match ->
            links.add(decodeMaybe(match.groupValues[1]))
        }

        return links.filter { it.isNotBlank() }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex(
            """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|/videoplayback)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).map { it.value.cleanEscaped() }.forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|%2Fvideoplayback)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).mapNotNull {
            runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrNull()
        }.forEach { urls.add(it.cleanEscaped()) }

        return urls.filterNot { isAdUrl(it) }
    }

    private fun emitDirectLink(
        url: String,
        referer: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = normalizeUrl(url, referer)
        if (!isDirectVideo(fixed) || isAdUrl(fixed)) return false

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = fixed,
                type = if (isHlsLike(fixed)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality.takeIf { it != Qualities.Unknown.value }
                    ?: qualityFromUrl(fixed)
            }
        )
        return true
    }

    private fun isDirectVideo(url: String): Boolean {
        return isHlsLike(url) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains("/videoplayback", true)
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains("f=video.m3u8", true) ||
            url.contains("application/x-mpegurl", true)
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "doubleclick.net",
            "googletagmanager.com",
            "google-analytics.com",
            "magsrv.com",
            "tsyndicate.com",
            "trafget.com",
            "onclck",
            "clickunder",
            "exoclick",
            "juicyads",
            "adserver",
            "ads.",
            "/ads/",
            "banner",
            "popunder"
        ).any { lower.contains(it) }
    }

    private fun qualityFromFormat(format: String): Int {
        val clean = format.lowercase()
        return when {
            clean.contains("2160") || clean.contains("4k") -> Qualities.P2160.value
            clean.contains("1080") || clean.contains("fullhd") -> Qualities.P1080.value
            clean.contains("720") || clean.contains("hq") -> Qualities.P720.value
            clean.contains("480") || clean.contains("lq") -> Qualities.P480.value
            clean.contains("360") -> Qualities.P360.value
            clean.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityFromUrl(url: String): Int {
        val parsed = getQualityFromName(url)
        if (parsed != Qualities.Unknown.value) return parsed
        return qualityFromFormat(url)
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String {
        val clean = raw.trim().cleanEscaped()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (clean.isBlank() || clean.startsWith("javascript", true)) return ""

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http", true) -> clean
            clean.startsWith("/") -> runCatching {
                val uri = URI(baseUrl)
                "${uri.scheme}://${uri.host}$clean"
            }.getOrDefault(fixUrl(clean))
            else -> fixUrl(clean)
        }
    }

    private fun decodeMaybe(raw: String): String {
        var value = raw.trim().cleanEscaped()
        if (value.isBlank()) return ""

        repeat(2) {
            if (value.contains("%2F", true) || value.contains("%3A", true) || value.contains("%3D", true)) {
                value = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            }
        }

        if (value.startsWith("http", true) || value.startsWith("//")) return value

        customBase64DecoderOrNull(value)?.let { decoded ->
            if (decoded.contains("http", true) || decoded.trim().startsWith("[") || decoded.trim().startsWith("{")) {
                return decoded.cleanEscaped()
            }
        }

        if (value.length > 20 && value.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            runCatching {
                String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()?.let { decoded ->
                if (decoded.contains("http", true) || decoded.trim().startsWith("[") || decoded.trim().startsWith("{")) {
                    return decoded.cleanEscaped()
                }
            }
        }

        return value
    }

    private fun customBase64DecoderOrNull(encodedString: String): String? {
        val base64CharacterSet = "АВСDЕFGHIJKLМNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,~"
        val sanitized = encodedString.replace("[^АВСDЕFGHIJKLМNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,~]".toRegex(), "")
        if (sanitized.length < 4 || sanitized.length % 4 != 0) return null

        val builder = StringBuilder()
        var currentIndex = 0

        return runCatching {
            while (currentIndex + 3 < sanitized.length) {
                val first = base64CharacterSet.indexOf(sanitized[currentIndex++])
                val second = base64CharacterSet.indexOf(sanitized[currentIndex++])
                val third = base64CharacterSet.indexOf(sanitized[currentIndex++])
                val fourth = base64CharacterSet.indexOf(sanitized[currentIndex++])

                if (first < 0 || second < 0 || third < 0 || fourth < 0) return null

                val firstChar = (first shl 2) or (second shr 4)
                val secondChar = ((15 and second) shl 4) or (third shr 2)
                val thirdChar = ((3 and third) shl 6) or fourth

                builder.append(firstChar.toChar())
                builder.append(secondChar.toChar())
                builder.append(thirdChar.toChar())
            }

            URLDecoder.decode(builder.toString(), "UTF-8")
        }.getOrNull()
    }

    private fun String.cleanTitle(): String {
        return this.cleanEscaped()
            .replace(" - Pornhits", "", ignoreCase = true)
            .replace("PornHits.com", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|')
    }

    private fun String.cleanEscaped(): String {
        return this.replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u002F", "/")
            .trim()
    }
}
