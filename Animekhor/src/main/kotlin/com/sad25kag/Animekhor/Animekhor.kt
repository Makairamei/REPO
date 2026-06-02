package com.sad25kag.Animekhor

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

open class Animekhor : MainAPI() {
    override var mainUrl = "https://animekhor.org"
    override var name = "Animekhor"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&type=&order=update" to "Recently Updated",
        "anime/?type=comic&order=update" to "Comic Recently Updated",
        "anime/?type=comic" to "Comic Series",
        "anime/?status=&type=ona&sub=&order=update" to "Donghua Recently Updated",
        "anime/?status=&type=ona" to "Donghua Series",
        "anime/?status=&sub=&order=latest" to "Latest Added",
        "anime/?status=&type=&order=popular" to "Popular",
        "anime/?status=completed&order=update" to "Completed"
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val separator = if (data.contains("?")) "&" else "?"
        return "$mainUrl/$data${separator}page=$page"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(buildPageUrl(request.data, page)).document
        val home = document.select("div.listupd > article, article.bs, div.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("li.next a, a.next, a[rel=next]").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("div.bsx > a[href], a[href]") ?: return null
        val href = fixUrl(link.attr("href").takeIf { it.isNotBlank() } ?: return null)
        if (!isContentUrl(href)) return null

        val title = link.attr("title").ifBlank {
            selectFirst("h2, h3, .tt, .entry-title")?.text()?.trim().orEmpty()
        }.ifBlank {
            selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }.ifBlank { text().trim() }.replace(Regex("\\s+"), " ").trim()

        if (title.length < 2) return null

        val posterUrl = selectFirst("div.bsx > a img, img")?.getsrcAttribute()?.let { fixUrlNull(it) }
        val tvType = if (href.contains("/anime/", true)) TvType.Anime else TvType.Movie

        return if (tvType == TvType.Anime) {
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith(mainUrl)) return false
        if (
            lower.contains("/genre/") || lower.contains("/genres/") ||
            lower.contains("/tag/") || lower.contains("/schedule") ||
            lower.contains("/bookmarks") || lower.contains("/history") ||
            lower.contains("/page/") || lower.contains("filter-search") ||
            lower.endsWith("/anime/") || lower.endsWith(mainUrl)
        ) return false

        return lower.contains("/anime/") ||
            lower.contains("episode") ||
            lower.contains("movie-subtitles") ||
            lower.contains("subtitles-english") ||
            lower.contains("subtitles-indonesian")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val queryParts = query.lowercase()
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }

        if (queryParts.isEmpty()) return emptyList()

        val searchResponse = mutableListOf<SearchResponse>()

        suspend fun collectSearchPage(url: String): Boolean {
            val document = runCatching { app.get(url).document }.getOrNull() ?: return false
            val results = document.select("div.listupd > article, article.bs, div.bs")
                .mapNotNull { it.toSearchResult() }
                .filter { result ->
                    val name = result.name.lowercase()
                    queryParts.all { name.contains(it) }
                }

            results.forEach { result ->
                if (searchResponse.none { it.url == result.url }) searchResponse.add(result)
            }

            return results.isNotEmpty()
        }

        // Evidence-based source search path: AnimeKhor's Filter Search / Anime Lists page.
        // The page exposes content cards and pagination, so it is safer than relying only on
        // WordPress ?s= when that endpoint is intermittently unavailable.
        for (page in 1..3) {
            val hasResults = collectSearchPage("$mainUrl/anime/?order=&status=&type=&page=$page")
            if (!hasResults && page > 1) break
        }

        if (searchResponse.isNotEmpty()) return searchResponse.distinctBy { it.url }

        // Last-resort WordPress search fallback. It is kept guarded and query-filtered.
        collectSearchPage("$mainUrl/?s=$encodedQuery")
        for (page in 2..3) {
            val hasResults = collectSearchPage("$mainUrl/page/$page/?s=$encodedQuery")
            if (!hasResults) break
        }

        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1, .entry-title")
            ?.text()?.trim()?.ifBlank { null }
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".thumb img, .poster img, .entry-content img")?.getsrcAttribute()

        val description = document.selectFirst("div.entry-content, .entry-content, .contentdeks")?.text()?.trim()
        val type = document.selectFirst(".spe, .info-content")?.text().orEmpty()

        val episodes = document.select(
            "div.eplister ul li a[href], div.episodelist ul li a[href], div.bixbox.bxcl ul li a[href], ul li a[href*=episode]"
        ).mapNotNull { anchor ->
            val href = fixUrl(anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null)
            if (!isContentUrl(href)) return@mapNotNull null
            val raw = anchor.text().trim().ifBlank { href.substringAfterLast("/").replace("-", " ") }
            newEpisode(href) {
                this.name = raw
                this.episode = parseEpisodeNumber(raw, href)
                this.posterUrl = poster
            }
        }.distinctBy { it.data }.reversed()

        return if (episodes.isNotEmpty() && !type.contains("Movie", true)) {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster.orEmpty())
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrlNull(poster.orEmpty())
                this.plot = description
            }
        }
    }

    private fun parseEpisodeNumber(text: String, url: String): Int? {
        return Regex("""(?i)\b(?:episode|eps|ep)\s*-?\s*(\d+)\b""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-?(\d+)""")
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(data).document
        val candidates = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            normalizePlayerUrl(raw)?.let { candidates.add(it) }
        }

        fun addDecodedMirror(value: String) {
            if (value.isBlank()) return

            val decoded = runCatching { base64Decode(value) }.getOrNull() ?: return
            val decodedDocument = Jsoup.parse(decoded)

            decodedDocument.select("iframe[src]").forEach { iframe ->
                addCandidate(iframe.attr("src"))
            }
        }

        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src]").forEach { iframe ->
            addCandidate(iframe.attr("src"))
        }

        document.select(".mobius select.mirror option[value], select.mirror option[value]").forEach { option ->
            addDecodedMirror(option.attr("value"))
        }

        var found = false
        candidates.forEach { url ->
            if (isOkruUrl(url)) return@forEach

            val referer = if (isAnimekhorPlayer(url)) mainUrl else data
            try {
                loadExtractor(url = url, referer = referer, subtitleCallback = subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            } catch (_: Throwable) {
            }
        }

        return found
    }

    private fun normalizePlayerUrl(raw: String?): String? {
        val cleaned = raw?.trim()
            ?.replace("&amp;", "&")
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() } ?: return null

        val fixed = when {
            cleaned.startsWith("//") -> httpsify(cleaned)
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            cleaned.startsWith("http", true) -> cleaned
            else -> return null
        }

        return fixed.takeUnless { isBadPlayerCandidate(it) }
    }

    private fun isAnimekhorPlayer(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("animekhor.p2pstream.vip") || lower.contains("animekhor.upns.live")
    }

    private fun isBadPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("acceptable.a-ads.com") ||
            lower.contains("/anime/") || lower.contains("/genre/") ||
            lower.contains("/genres/") || lower.contains("/tag/") ||
            lower.contains("/schedule") || lower.contains("facebook.com") ||
            lower.contains("twitter.com") || lower.contains("telegram") ||
            lower.contains("whatsapp") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") ||
            lower.endsWith(".css") || lower.endsWith(".js")
    }

    private fun isOkruUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ok.ru") || lower.contains("odnoklassniki")
    }

    private fun Element.getsrcAttribute(): String {
        val src = attr("src")
        val dataSrc = attr("data-src")
        return src.takeIf { it.startsWith("http") }
            ?: dataSrc.takeIf { it.startsWith("http") }
            ?: ""
    }
}
