package com.sad25kag.Anichinmoe

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class Anichin : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie",
        "genres/action/?order=update" to "Action",
        "genres/adventure/?order=update" to "Adventure",
        "genres/comedy/?order=update" to "Comedy",
        "genres/demons/?order=update" to "Demons",
        "genres/drama/?order=update" to "Drama",
        "genres/ecchi/?order=update" to "Ecchi",
        "genres/fantasy/?order=update" to "Fantasy",
        "genres/game/?order=update" to "Game",
        "genres/harem/?order=update" to "Harem",
        "genres/historical/?order=update" to "Historical",
        "genres/horror/?order=update" to "Horror",
        "genres/isekai/?order=update" to "Isekai",
        "genres/magic/?order=update" to "Magic",
        "genres/martial-arts/?order=update" to "Martial Arts",
        "genres/mecha/?order=update" to "Mecha",
        "genres/military/?order=update" to "Military",
        "genres/music/?order=update" to "Music",
        "genres/mystery/?order=update" to "Mystery",
        "genres/psychological/?order=update" to "Psychological",
        "genres/romance/?order=update" to "Romance",
        "genres/school/?order=update" to "School",
        "genres/sci-fi/?order=update" to "Sci-Fi",
        "genres/shoujo/?order=update" to "Shoujo",
        "genres/shounen/?order=update" to "Shounen",
        "genres/slice-of-life/?order=update" to "Slice of Life",
        "genres/space/?order=update" to "Space",
        "genres/sports/?order=update" to "Sports",
        "genres/supernatural/?order=update" to "Supernatural",
        "genres/thriller/?order=update" to "Thriller",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get("${mainUrl}/${request.data}&page=$page").document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false,
            ),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").trim()
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val searchResponse = mutableListOf<SearchResponse>()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$encodedQuery").document
            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

            if (results.isEmpty()) break
            searchResponse.addAll(results)
        }

        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(fixUrl(url)).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvType = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".eplister li").mapNotNull { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty()).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val epTitle = ep.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                val epSub = ep.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
                val epDate = ep.selectFirst(".epl-date")?.text()?.trim().orEmpty()
                val cleanTitle = epTitle
                    .replace(Regex("Episode\\s*\\d+\\s*Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                    .replace("Subtitle Indonesia", "")
                    .trim()
                val name = "— $cleanTitle $epSub Indonesia".trim()
                val desc = if (epDate.isNotEmpty()) "Rilis: $epDate" else null

                newEpisode(link) {
                    this.name = name
                    this.posterUrl = fixUrlNull(poster)
                    this.description = desc
                }
            }.reversed()

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val movieHref = document.selectFirst(".eplister li > a")?.attr("href")?.let { fixUrl(it) } ?: url

            newMovieLoadResponse(title, movieHref, TvType.Movie, movieHref) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        // Lightweight license ping to server (non-blocking)
        runCatching { ServerBridge.pingLicense("PLAY") }
        val document = app.get(fixUrl(data)).document
        val candidates = linkedSetOf<String>()

        fun addCandidate(value: String?) {
            if (value.isNullOrBlank()) return
            decodeServerUrls(value).forEach { candidates.add(it) }
        }

        document.select(".mobius option[value], #server option[value], #player option[value], #mirror option[value], select[name*=server] option[value], .server option[value], .serverlist option[value], select option[value], option[value]").forEach { server ->
            addCandidate(server.attr("value"))
        }

        document.select("iframe[src], embed[src], source[src]").forEach { element ->
            addCandidate(element.attr("src"))
        }

        document.select("[data-src], [data-lazy-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]").forEach { element ->
            addCandidate(element.attr("data-src"))
            addCandidate(element.attr("data-lazy-src"))
            addCandidate(element.attr("data-url"))
            addCandidate(element.attr("data-link"))
            addCandidate(element.attr("data-video"))
            addCandidate(element.attr("data-embed"))
            addCandidate(element.attr("data-player"))
            addCandidate(element.attr("data-file"))
        }

        extractKnownVideoUrls(document.html()).forEach { candidates.add(it) }

        var foundLinks = false
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            foundLinks = true
            callback.invoke(link)
        }

        candidates.forEach { href ->
            try {
                loadExtractor(href, data, subtitleCallback, safeCallback)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w("Anichin", "Failed loading server: $href", error)
            }
        }

        return foundLinks
    }

    private fun decodeServerUrls(value: String): List<String> {
        val decodedValues = linkedSetOf<String>()
        val cleanValue = value.trim().htmlUnescape()
        if (cleanValue.isBlank()) return emptyList()

        decodedValues.add(cleanValue)
        runCatching { URLDecoder.decode(cleanValue, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape()) }

        decodeBase64Value(cleanValue)?.let { decodedValues.add(it.htmlUnescape()) }

        return decodedValues
            .flatMap { extractKnownVideoUrls(it) }
            .distinct()
    }

    private fun decodeBase64Value(value: String): String? {
        val normalized = value.trim()
        if (normalized.length < 8) return null

        return runCatching { base64Decode(normalized) }.getOrNull()
            ?: runCatching {
                val fixed = normalized
                    .replace('-', '+')
                    .replace('_', '/')
                    .let { raw ->
                        val padding = (4 - raw.length % 4) % 4
                        raw + "=".repeat(padding)
                    }

                String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
            }.getOrNull()
    }

    private fun extractKnownVideoUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        val decodedText = rawText
            .htmlUnescape()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")

        val urls = linkedSetOf<String>()

        Jsoup.parse(decodedText).select("iframe[src], embed[src], source[src], a[href]").forEach { element ->
            val attr = when {
                element.hasAttr("src") -> element.attr("src")
                else -> element.attr("href")
            }
            normalizeVideoUrl(attr)?.let { urls.add(it) }
        }

        Regex("""https?:\\?/\\?/[^"' <>\])]+""")
            .findAll(decodedText)
            .mapNotNull { normalizeVideoUrl(it.value) }
            .forEach { urls.add(it) }

        Regex("""(?i)(?:file|url|src|embed|video)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(decodedText)
            .mapNotNull { normalizeVideoUrl(it.groupValues[1]) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeVideoUrl(url: String): String? {
        val fixed = url.trim()
            .trim('"', '\'', ' ', '\n', '\r', '\t')
            .replace("\\/", "/")
            .htmlUnescape()

        if (fixed.isBlank()) return null

        val absolute = when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("http://") || fixed.startsWith("https://") -> fixed
            else -> return null
        }

        val supportedHosts = listOf(
            "dailymotion.com",
            "geo.dailymotion.com",
            "ok.ru",
            "odnoklassniki.ru",
            "rumble.com",
            "rubyvidhub.com",
            "streamruby.com",
            "streamruby.net",
            "vidguard.to",
            "bembed.net",
            "listeamed.net",
            "vgfplay.com",
            "vidhide",
            "filelions",
            "streamwish",
            "dood",
            // extended mirrors
            "filemoon",
            "filemoon.sx",
            "filemoon.to",
            "voe.sx",
            "voe.io",
            "mp4upload.com",
            "streamtape.com",
            "sbplay",
            "sbembed",
            "watchsb",
            "viewsb",
            "mixdrop.co",
            "mixdrop.sx",
            "streamlare",
            "videovard",
            "yourupload",
            "gofile.io"
        )

        return absolute
            .takeIf { candidate -> supportedHosts.any { candidate.contains(it, ignoreCase = true) } }
            ?.let { fixUrl(it) }
    }

    private fun String.htmlUnescape(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
