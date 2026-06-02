package com.sad25kag.Animexin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/demon/" to "Demon",
        "genres/fantasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/martial-arts/" to "Martial Arts",
        "genres/romance/" to "Romance",
        "genres/supernatural/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val link = if (request.data.contains("genres")) {
            "$mainUrl/${request.data}page/$page"
        } else {
            "$mainUrl/${request.data}&page=$page"
        }

        val document = app.get(link).documentLarge
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.select("a.next, li.next a").isNotEmpty()
        )
    }


    private fun inferTvType(title: String, href: String, cardText: String = ""): TvType {
        val haystack = "$title $href $cardText".lowercase()
        return if (
            haystack.contains("/anime/?type=movie") ||
            haystack.contains(" movie") ||
            haystack.contains("movie ")
        ) {
            TvType.Movie
        } else {
            TvType.Anime
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val cardText = text()
        if (cardText.contains("Upcoming", true)) return null

        val anchor = selectFirst("div.bsx > a, a[href]") ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (title.isBlank() || !isContentUrl(href)) return null

        val posterUrl = fixUrlNull(selectFirst("div.bsx > a img, img")?.getsrcAttribute().orEmpty())

        return newAnimeSearchResponse(title, href, inferTvType(title, href, cardText)) {
            this.posterUrl = posterUrl
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val clean = url.substringBefore("?").trimEnd('/')
        if (!clean.startsWith(mainUrl)) return false
        val path = clean.removePrefix(mainUrl).trim('/')
        if (path.isBlank()) return false
        if (
            path.startsWith("genres/") ||
            path.startsWith("anime/") ||
            path.startsWith("tag/") ||
            path.startsWith("page/") ||
            path.startsWith("az-list") ||
            path.startsWith("release-date") ||
            path.startsWith("bookmark")
        ) return false

        return path.contains("-episode-", true) || !path.contains("/")
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = linkedSetOf<SearchResponse>()
        val queryTokens = query.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

        fun SearchResponse.matchesQuery(): Boolean {
            if (queryTokens.isEmpty()) return name.contains(query, true)
            return queryTokens.all { token -> name.contains(token, true) }
        }

        val searchUrls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/page/$page/?s=$encodedQuery"
        ).distinct()

        searchUrls.forEach { url ->
            runCatching {
                app.get(url).documentLarge
                    .select("div.listupd > article")
                    .mapNotNull { it.toSearchResult() }
                    .filter { it.matchesQuery() }
                    .forEach { results.add(it) }
            }
        }

        if (results.isEmpty()) {
            val fallbackPages = if (page <= 1) 1..3 else page..page
            fallbackPages.forEach { fallbackPage ->
                runCatching {
                    app.get("$mainUrl/anime/?status=&order=latest&page=$fallbackPage").documentLarge
                        .select("div.listupd > article")
                        .mapNotNull { it.toSearchResult() }
                        .filter { it.matchesQuery() }
                        .forEach { results.add(it) }
                }
            }
        }

        return results.toList().toNewSearchResponseList()
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).documentLarge
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.select("div.thumb img").attr("src").ifEmpty {
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvtag = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val episodeRegex = Regex("(\\d+)")
            val episodes = document.select(
                "div.eplister > ul > li, .eplister li, .episodelist li, ul li"
            ).mapNotNull { info ->
                val anchor = info.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!href.contains("-episode-", true)) return@mapNotNull null

                val posterEpisode = info.selectFirst("a img, img")?.attr("src").orEmpty()
                val epText = info.selectFirst("div.epl-num, .epl-num, a span")?.text()?.ifBlank { anchor.text() } ?: anchor.text()
                val epnum = episodeRegex.find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(href) {
                    this.episode = epnum
                    this.name = epnum?.let { "Episode $it" } ?: epText
                    this.posterUrl = posterEpisode.ifBlank { poster }
                }
            }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }

            if (episodes.isEmpty()) {
                throw ErrorLoadingException("No episodes found")
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
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
        val document = app.get(data).documentLarge
        val servers = document.select(".mobius option, #mobius option, select option")
        val candidates = linkedSetOf<String>()


        fun isPlayerCandidate(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("dailymotion.com") ||
                lower.contains("dai.ly") ||
                lower.contains("odysee.com") ||
                lower.contains("mega.nz") ||
                lower.contains("rumble.com") ||
                lower.contains("dood") ||
                lower.contains(".m3u8") ||
                lower.contains(".mp4")
        }

        fun addCandidate(raw: String?) {
            val url = raw?.trim()
                ?.replace("&amp;", "&")
                ?.replace("\\/", "/")
                ?.takeIf { it.isNotBlank() }
                ?: return

            val fixed = when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$mainUrl$url"
                else -> url
            }

            if (fixed.contains("ok.ru", true)) return
            if (fixed.startsWith("http", true) && isPlayerCandidate(fixed)) candidates.add(fixed)
        }

        servers.forEach { server ->
            val value = server.attr("value")
            if (value.isBlank()) return@forEach

            runCatching {
                val decoded = base64Decode(value)
                val doc = Jsoup.parse(decoded)

                doc.select("iframe[src], iframe[data-src], source[src], video[src], a[href]").forEach { element ->
                    addCandidate(element.attr("src"))
                    addCandidate(element.attr("data-src"))
                    addCandidate(element.attr("href"))
                }

                listOf(
                    Regex("""(?i)src=["']([^"']+)["']"""),
                    Regex("""(?i)data-src=["']([^"']+)["']"""),
                    Regex("""(?i)file["']?\s*:\s*["']([^"']+)["']""")
                ).forEach { regex ->
                    regex.findAll(decoded).forEach { match ->
                        addCandidate(match.groupValues[1])
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            document.select("div.player-area iframe, iframe[src], iframe[data-src]").forEach { iframe ->
                addCandidate(iframe.attr("src"))
                addCandidate(iframe.attr("data-src"))
            }
        }

        var found = false
        val safeCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        candidates.forEach { link ->
            runCatching {
                loadExtractor(
                    link,
                    referer = data,
                    subtitleCallback = subtitleCallback,
                    callback = safeCallback
                )
            }
        }

        return found
    }

    private fun Element.getsrcAttribute(): String {
        val src = attr("src")
        val dataSrc = attr("data-src")
        return src.takeIf { it.startsWith("http") } ?: dataSrc.takeIf { it.startsWith("http") } ?: ""
    }
}
