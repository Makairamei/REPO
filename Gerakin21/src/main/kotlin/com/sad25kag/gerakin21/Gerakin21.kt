package com.sad25kag.gerakin21

import com.lagradost.cloudstream3.HomePageList
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Gerakin21 : MainAPI() {
    override var mainUrl = "https://gerakin21.cloud"
    override var name = "Gerakin21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val providerHosts = setOf(
        "gerakin21.cloud",
        "www.gerakin21.cloud"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",

        // Current Gerakin21 menu structure.
        "category/movie/" to "Semua Film",
        "category/superhero/" to "Superhero",
        "category/sweet/" to "Sweet",
        "category/anime/" to "Anime",

        "tv/" to "Semua Serial",
        "quality/ongoing/" to "Serial On-Going",
        "quality/end/" to "Serial END",
        "category/serial-indonesia/" to "Serial Indonesia",
        "category/serial-thailand/" to "Serial Thailand",

        // Film semi rows from the active site menu.
        "category/brother-musang/" to "Brother Musang",
        "category/indo-lokal/" to "Indo Lokal",
        "category/jav-sub-indo/" to "Jepang JAV",
        "category/pinoy/" to "Pinoy",
        "category/semi-korea/" to "Semi Korea",
        "vivamax/" to "Vivamax",
        "category/kelas-bintang/" to "Kelas Bintang",
        "category/togefilm/" to "TOGEFILM",

        // Genre rows use /category/* on the current site, not /genre/*.
        "category/action/" to "Action",
        "category/adventure/" to "Adventure",
        "category/comedy/" to "Comedy",
        "category/crime/" to "Crime",
        "category/drama/" to "Drama",
        "category/fantasy/" to "Fantasy",
        "category/history/" to "History",
        "category/mystery/" to "Mystery",
        "category/romance/" to "Romance",
        "category/science-fiction/" to "Science Fiction",
        "category/thriller/" to "Thriller",

        "country/indonesia/" to "Indonesia",
        "country/australia/" to "Australia",
        "country/canada/" to "Canada",
        "country/china/" to "China",
        "country/india/" to "India",
        "country/ireland/" to "Ireland",
        "country/japan/" to "Japan",
        "country/korea/" to "Korea",
        "country/new-zealand/" to "New Zealand",
        "country/philippines/" to "Philippines",
        "country/thailand/" to "Thailand",
        "country/usa/" to "USA",
        "country/united-kingdom/" to "United Kingdom",

        "year/2026/" to "2026",
        "year/2025/" to "2025",
        "year/2024/" to "2024",
        "year/2023/" to "2023",
        "year/2022/" to "2022",
        "year/2021/" to "2021",
        "year/2020/" to "2020",
        "year/2019/" to "2019",
        "year/2018/" to "2018",
        "year/2017/" to "2017",
        "year/2016/" to "2016",
        "year/2015/" to "2015"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = commonHeaders,
            referer = "$mainUrl/"
        ).document

        if (request.data.isBlank() && page <= 1) {
            val rows = parseHomeRows(document)
            if (rows.isNotEmpty()) return newHomePageResponse(rows)
        }

        val items = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNextPage(document, page)
        )
    }


    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        document.select(
            "section:has(a[href]), div:has(> h2):has(a[href]), div:has(> h3):has(a[href]), " +
                ".home-section:has(a[href]), .block:has(a[href]), .module:has(a[href]), .content:has(a[href])"
        ).forEach { section ->
            val rowTitle = section.selectFirst("h2, h3, .section-title, .widget-title, .heading")
                ?.text()
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() && !it.isUiText() }
                ?: return@forEach

            val items = parseCards(Jsoup.parse(section.html(), mainUrl))
                .distinctBy { it.url }
                .take(24)

            if (items.size >= 3 && !rowTitle.isBlockedHomeRow()) {
                rows.add(HomePageList(rowTitle, items, isHorizontalImages = false))
            }
        }

        return rows.distinctBy { it.name }.take(8)
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')

        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cleanPath/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a[href]), " +
                ".item:has(a[href]), " +
                ".ml-item:has(a[href]), " +
                ".movie:has(a[href]), " +
                ".film:has(a[href]), " +
                ".post:has(a[href]), " +
                ".grid-item:has(a[href]), " +
                ".result-item:has(a[href]), " +
                ".latest-post:has(a[href]), " +
                ".content article:has(a[href]), " +
                ".movie-list article:has(a[href]), " +
                ".movies-list article:has(a[href]), " +
                ".items article:has(a[href]), " +
                ".listing article:has(a[href]), " +
                ".ml-item:has(a[href]), .item:has(a[href]), .result-item:has(a[href]), " +
                "div[class*=poster]:has(a[href]), div[class*=thumb]:has(a[href]), " +
                "div[class*=movie]:has(a[href]), " +
                "div[class*=film]:has(a[href]), " +
                "div[class*=item]:has(a[href])"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select(
                "h2 a[href], h3 a[href], .title a[href], .entry-title a[href], " +
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
                "h1 a[href], " +
                    "h2 a[href], " +
                    "h3 a[href], " +
                    "h4 a[href], " +
                    ".title a[href], " +
                    ".entry-title a[href], " +
                    ".movie-title a[href], " +
                    ".film-title a[href], " +
                    "a[href*='/movie/'], " +
                    "a[href*='/tv/'], " +
                    "a[href*='/episode/'], " +
                    ".poster a[href], " +
                    ".thumb a[href], " +
                    "a[href]:has(img), " +
                    "a[href][title], " +
                    "a[href]"
            ) ?: return null
        }

        val href = resolveUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isProviderUrl(href)) return null
        if (isBlockedUrl(href)) return null

        val image = findPosterElement(this, anchor)
        val title = listOf(
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h4")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".movie-title")?.text(),
            selectFirst(".film-title")?.text(),
            selectFirst(".jt")?.text(),
            anchor.text()
        ).mapNotNull { it?.cleanTitle()?.takeIf { clean -> clean.isNotBlank() && !clean.isUiText() } }
            .firstOrNull()
            ?: return null

        if (title.length < 2) return null

        val poster = extractPosterUrl(this, anchor) ?: return null

        val type = getTypeFromUrl(href, title, text())

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/').lowercase() }
            .getOrDefault(url.substringAfter(mainUrl, "").trim('/').lowercase())

        if (path.isBlank()) LicenseClient.trackActivity(name, "PLAY")
        return true

        val exactBlocked = setOf(
            "trending",
            "movies",
            "tv-series",
            "genre",
            "country",
            "year",
            "tag",
            "category",
            "privacy",
            "dmca",
            "contact",
            "terms",
            "about",
            "disclaimer",
            "login",
            "register"
        )

        if (path in exactBlocked) LicenseClient.trackActivity(name, "PLAY")
        return true

        val prefixBlocked = listOf(
            "genre/",
            "country/",
            "year/",
            "tag/",
            "category/",
            "page/",
            "search",
            "feed",
            "wp-json",
            "wp-content",
            "wp-admin"
        )

        if (prefixBlocked.any { path.startsWith(it) }) LicenseClient.trackActivity(name, "PLAY")
        return true

        val lower = url.lowercase()
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val q = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$q",
            "$mainUrl/search/$q/",
            "$mainUrl/search?keyword=$q"
        )

        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = commonHeaders, referer = "$mainUrl/").document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { results[it.url] = it }
            if (results.isNotEmpty()) return@forEach
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = commonHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst(
            "h1.title, h1[itemprop=name], h1.entry-title, .entry-title, " +
                ".s-title, .movie-title, .film-title, " +
                "meta[property=og:title], meta[name=title]"
        )?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.text()
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")
                .substringBefore("?")
                .replace("-", " ")
                .cleanTitle()

        val poster = document.selectFirst(
            "meta[property=og:image], meta[name=twitter:image], " +
                "div.poster img, .poster img, .s-cover img, .thumb img, " +
                ".entry-content img, img.wp-post-image, img"
        )?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.getImageAttr()
        }?.let { fixUrlNull(it) }
            ?.takeIf { !isBadImage(it) }

        val description = document.selectFirst(
            "meta[property=og:description], meta[name=description], " +
                "div.wp-content, .entry-content, .s-desc, .sinopsis, " +
                ".description, .summary, .movie-desc, .storyline"
        )?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.text()
        }?.cleanPlot()

        val tags = document.select(
            "a[href*='/genre/'], a[href*='/category/'], a[href*='/tag/'], " +
                ".genres a, .genre a, .tags a, .category a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !it.isUiText() }
            .distinct()

        val recommendations = document.select(
            ".related article:has(a), .related-posts article:has(a), " +
                ".related a[href]:has(img), .post:has(a), .item:has(a), " +
                ".movie:has(a), .film:has(a)"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.filter { it.url != url }
            .distinctBy { it.url }

        val type = getTypeFromUrl(url, title, tags.joinToString(" ") + " " + document.text())
        val episodes = parseEpisodes(document, url)

        return if (type == TvType.TvSeries || episodes.size > 1) {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.ifEmpty {
                    listOf(
                        newEpisode(url) {
                            name = "Episode 1"
                            episode = 1
                        }
                    )
                }
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
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
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(document: Document, fallbackUrl: String): List<com.lagradost.cloudstream3.Episode> {
        val result = linkedMapOf<String, com.lagradost.cloudstream3.Episode>()

        document.select(
            "ul.episodes li a[href], ul.episodes a[href], " +
                "div.episode-list a[href], .episode-list a[href], " +
                ".episodios li a[href], .eplister a[href], .episodelist a[href], " +
                "[class*=episode] a[href], [class*=eps] a[href], " +
                "a[href*='/episode/'], a[href*='episode-'], a[href*='eps-']"
        ).forEachIndexed { index, episodeElement ->
            val href = resolveUrl(episodeElement.attr("href"), fallbackUrl) ?: return@forEachIndexed
            if (!isProviderUrl(href) || isBlockedUrl(href)) return@forEachIndexed

            val epTitle = listOf(
                episodeElement.selectFirst(".title")?.text(),
                episodeElement.selectFirst(".nama-episode")?.text(),
                episodeElement.selectFirst(".epl-title")?.text(),
                episodeElement.attr("title"),
                episodeElement.text()
            ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
                ?.cleanTitle()
                ?: "Episode ${index + 1}"

            val epNumber = extractEpisodeNumber(epTitle, href) ?: index + 1
            result[href] = newEpisode(href) {
                name = epTitle.ifBlank { "Episode $epNumber" }
                episode = epNumber
            }
        }

        return result.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val discovered = linkedSetOf<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        val crawled = mutableSetOf<String>()
        var found = false

        fun queueUrl(raw: String?, base: String) {
            val fixed = resolveUrl(raw, base) ?: return
            if (isBadPlaybackUrl(fixed)) return
            if (discovered.add(fixed)) queue.add(fixed to base)
        }

        fun extractFromText(text: String, base: String) {
            val cleaned = text.decodeEscaped()

            Regex("""https?://[^"'<>\s\\]+?\.(?:m3u8|mp4)(?:\?[^"'<>\s\\]*)?""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .forEach { queueUrl(it.value, base) }

            Regex("""//[^"'<>\s\\]+?\.(?:m3u8|mp4)(?:\?[^"'<>\s\\]*)?""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .forEach { queueUrl("https:${it.value}", base) }

            Regex("""https?%3A%2F%2F[^"'<>\s\\]+?(?:\.m3u8|\.mp4)[^"'<>\s\\]*""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .forEach { match ->
                    queueUrl(runCatching { URLDecoder.decode(match.value, "UTF-8") }.getOrDefault(match.value), base)
                }

            Regex(
                """(?:file|src|source|url|video|videoUrl|video_url|streamUrl|stream_url|link|embed|player)\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(cleaned).forEach { match ->
                queueUrl(match.groupValues[1], base)
            }

            Regex("""(?:https?:)?//[^"'<>\s\\]+/(?:embed|player|stream|video)[^"'<>\s\\]*""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .forEach { queueUrl(it.value, base) }

            Regex("""https?://(?:[^"'<>\s\\]+\.)?(?:streamtape|filemoon|vidhide|voe|dood|d000d|mixdrop|streamwish|wish|filelions|vidguard|vidplay|uqload|mp4upload|ok\.ru|sendvid|lulustream|wolfstream|streamruby|dropload|embedwish|bysezoxexe)[^"'<>\s\\]*""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .forEach { queueUrl(it.value, base) }

            Regex("""https?://fufafilm\.(?:upns\.pro|strp2p\.com)/#[A-Za-z0-9_-]+(?:&dl=1)?""", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .forEach { queueUrl(it.value, base) }
        }

        fun extractFromDoc(doc: Document, base: String) {
            doc.select(
                "iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-litespeed-src], " +
                    "embed[src], object[data], source[src], video[src], video[data-src], " +
                    "a[href*='embed'], a[href*='player'], a[href*='stream'], " +
                    "a[href*='streamtape'], a[href*='filemoon'], a[href*='vidhide'], " +
                    "a[href*='voe'], a[href*='dood'], a[href*='mixdrop'], " +
                    "a[href*='streamwish'], a[href*='wish'], a[href*='filelions'], " +
                    "a[href*='vidguard'], a[href*='mp4upload'], a[href*='ok.ru'], " +
                    "a[href*='fufafilm.upns.pro'], a[href*='fufafilm.strp2p.com'], " +
                    "a[href*='bysezoxexe.com'], a[href*='byse'], " +
                    "[data-url], [data-src], [data-video], [data-file], [data-embed], [data-player], " +
                    "option[value]"
            ).forEach { element ->
                queueUrl(element.attr("src"), base)
                queueUrl(element.attr("data-src"), base)
                queueUrl(element.attr("data-lazy-src"), base)
                queueUrl(element.attr("data-litespeed-src"), base)
                queueUrl(element.attr("href"), base)
                queueUrl(element.attr("data"), base)
                queueUrl(element.attr("data-url"), base)
                queueUrl(element.attr("data-video"), base)
                queueUrl(element.attr("data-file"), base)
                queueUrl(element.attr("data-embed"), base)
                queueUrl(element.attr("data-player"), base)

                val optionValue = element.attr("value").trim()
                if (optionValue.isNotBlank()) {
                    queueUrl(optionValue, base)
                    runCatching {
                        val decoded = String(android.util.Base64.decode(optionValue, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        extractFromText(decoded, base)
                    }
                }
            }

            doc.select("script").forEach { script ->
                extractFromText(script.data(), base)
                extractFromText(script.html(), base)
            }

            extractFromText(doc.html(), base)
        }

        val first = app.get(
            data,
            headers = commonHeaders,
            referer = "$mainUrl/",
            timeout = 30L
        )

        extractFromDoc(first.document, data)

        var safety = 0
        while (queue.isNotEmpty() && safety++ < 80) {
            val (next, referer) = queue.removeFirst()
            if (!crawled.add(next)) continue
            if (isBadPlaybackUrl(next)) continue

            when {
                tryResolveFufafilm(next, referer, callback) -> {
                    found = true
                }

                isDirectM3u8(next) -> {
                    generateM3u8(
                        source = name,
                        streamUrl = next,
                        referer = referer,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer
                        )
                    ).forEach(callback)
                    found = true
                }

                isDirectVideo(next) -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = next,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.quality = qualityFromName(next)
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to referer
                            )
                        }
                    )
                    found = true
                }

                isExtractorHost(next) -> {
                    if (runCatching { loadExtractor(next, referer, subtitleCallback, callback) }.getOrDefault(false)) {
                        found = true
                    }
                }

                shouldCrawl(next) -> {
                    val response = runCatching {
                        app.get(
                            next,
                            headers = commonHeaders,
                            referer = referer,
                            timeout = 25L
                        )
                    }.getOrNull() ?: continue

                    extractFromDoc(response.document, next)
                    extractFromText(response.text, next)
                }

                else -> {
                    if (runCatching { loadExtractor(next, referer, subtitleCallback, callback) }.getOrDefault(false)) {
                        found = true
                    }
                }
            }
        }

        if (!found) {
            discovered.forEach { link ->
                if (isExtractorHost(link)) {
                    found = runCatching { loadExtractor(link, data, subtitleCallback, callback) }.getOrDefault(false) || found
                }
            }
        }

        return found
    }


    private suspend fun tryResolveFufafilm(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        if (host != "fufafilm.upns.pro" && host != "fufafilm.strp2p.com") return false

        val videoId = uri.rawFragment
            ?.substringBefore("&")
            ?.substringBefore("?")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val playerBase = "${uri.scheme ?: "https"}://$host"
        val apiUrl = "$playerBase/api/v1/video?id=$videoId&w=1280&h=720&r=${URI(mainUrl).host.orEmpty()}"
        val encrypted = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*"
                ),
                referer = "$mainUrl/",
                timeout = 25L
            ).text.trim()
        }.getOrNull()?.takeIf { it.matches(Regex("""^[0-9a-fA-F]+$""")) } ?: return false

        val decrypted = runCatching { decryptFufafilmPayload(encrypted) }.getOrNull() ?: return false
        val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: return false
        var emitted = false

        suspend fun emitHls(raw: String?, sourceName: String) {
            val fixed = resolveUrl(raw, playerBase) ?: return
            if (!fixed.contains(".m3u8", true)) return
            generateM3u8(
                source = sourceName,
                streamUrl = fixed,
                referer = playerBase,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to playerBase
                )
            ).forEach(callback)
            emitted = true
        }

        emitHls(json.optString("source"), "$name Fufafilm")
        emitHls(json.optString("cf"), "$name Fufafilm CF")

        val hlsVideoTiktok = json.optString("hlsVideoTiktok").takeIf { it.isNotBlank() }
        if (hlsVideoTiktok != null) {
            val version = runCatching {
                val config = JSONObject(json.optString("streamingConfig"))
                config.optJSONObject("adjust")
                    ?.optJSONObject("Tiktok")
                    ?.optJSONObject("params")
                    ?.optString("v")
            }.getOrNull()?.takeIf { it.isNotBlank() }

            val resolved = resolveUrl(hlsVideoTiktok, playerBase)?.let { hls ->
                if (version != null && !hls.contains("?", true)) "$hls?v=$version" else hls
            }
            emitHls(resolved, "$name Fufafilm Tiktok")
        }

        return emitted
    }

    private fun decryptFufafilmPayload(hex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val decrypted = cipher.doFinal(hex.hexToBytes())
        return String(decrypted, Charsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = trim()
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = clean.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".pagination a:contains(Berikutnya), " +
                ".pagination a[href*='/page/${page + 1}'], " +
                ".nav-links a[href*='/page/${page + 1}'], " +
                "a[href*='/page/${page + 1}/'], " +
                "a[href*='paged=${page + 1}']"
        ) != null
    }


    private fun String.isBlockedHomeRow(): Boolean {
        val lower = cleanTitle().lowercase()
        return lower in setOf(
            "serial china",
            "serial korea",
            "semi barat",
            "horror",
            "horor",
            "sci-fi",
            "science fiction"
        )
    }

    private fun findPosterElement(element: Element, anchor: Element): Element? {
        val candidates = listOfNotNull(
            element,
            anchor,
            element.parent(),
            element.parent()?.parent(),
            anchor.parent(),
            anchor.parent()?.parent()
        ).distinct()

        candidates.forEach { box ->
            box.select("img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-img], img[poster], source[srcset], source[data-srcset]")
                .firstOrNull { img ->
                    val url = img.getImageAttr().orEmpty()
                    url.isNotBlank() && !isBadImage(url)
                }?.let { return it }
        }
        return null
    }

    private fun extractPosterUrl(element: Element, anchor: Element): String? {
        findPosterElement(element, anchor)?.getImageAttr()?.let { raw ->
            fixUrlNull(raw)?.takeIf { !isBadImage(it) }?.let { return it }
        }

        val boxes = listOfNotNull(element, anchor, element.parent(), element.parent()?.parent()).distinct()
        boxes.forEach { box ->
            val style = box.attr("style")
            Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
                .find(style)
                ?.groupValues
                ?.getOrNull(2)
                ?.trim()
                ?.let { raw -> fixUrlNull(raw)?.takeIf { !isBadImage(it) } }
                ?.let { return it }
        }

        return null
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src").ifBlank { attr("data-src") }
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src").ifBlank { attr("data-lazy-src") }
            hasAttr("data-original") -> attr("abs:data-original").ifBlank { attr("data-original") }
            hasAttr("data-img") -> attr("abs:data-img").ifBlank { attr("data-img") }
            hasAttr("data-image") -> attr("abs:data-image").ifBlank { attr("data-image") }
            hasAttr("data-poster") -> attr("abs:data-poster").ifBlank { attr("data-poster") }
            hasAttr("poster") -> attr("abs:poster").ifBlank { attr("poster") }
            hasAttr("data-srcset") -> attr("abs:data-srcset").ifBlank { attr("data-srcset") }.split(",").lastOrNull()?.substringBefore(" ")?.trim()
            hasAttr("srcset") -> attr("abs:srcset").ifBlank { attr("srcset") }.split(",").lastOrNull()?.substringBefore(" ")?.trim()
            hasAttr("src") -> attr("abs:src").ifBlank { attr("src") }
            else -> null
        }
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw
            ?.trim()
            ?.decodeEscaped()
            ?.takeIf { it.isNotBlank() && it != "#" && !it.equals("none", true) && !it.equals("null", true) }
            ?: return null

        if (clean.startsWith("javascript", true) || clean.startsWith("mailto:", true) || clean.startsWith("tel:", true)) {
            return null
        }

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> "$mainUrl$clean"
                else -> URI(base).resolve(clean).toString()
            }
        }.getOrElse {
            runCatching { fixUrl(clean) }.getOrNull()
        }
    }

    private fun isProviderUrl(url: String): Boolean {
        return runCatching {
            val host = URI(url).host.orEmpty().lowercase().removePrefix("www.")
            providerHosts.any { host == it.removePrefix("www.") }
        }.getOrDefault(url.startsWith(mainUrl))
    }

    private fun isExtractorHost(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "streamtape", "filemoon", "vidhide", "voe.sx", "voe.", "dood", "d000d",
            "mixdrop", "streamwish", "wishfast", "filelions", "vidguard", "vidplay",
            "uqload", "mp4upload", "ok.ru", "sendvid", "lulustream", "wolfstream",
            "streamruby", "dropload", "embedwish", "short.icu", "abyss.to", "fastream",
            "streamhub", "vidmoly", "upstream", "filegram", "streamsb",
            "fufafilm.upns.pro", "fufafilm.strp2p.com", "bysezoxexe.com", "byse"
        ).any { lower.contains(it) }
    }

    private fun shouldCrawl(url: String): Boolean {
        val lower = url.lowercase()
        if (isDirectM3u8(lower) || isDirectVideo(lower)) return false
        if (isExtractorHost(lower)) return false

        return isProviderUrl(url) ||
            lower.contains("/embed") ||
            lower.contains("/player") ||
            lower.contains("/stream") ||
            lower.contains("ajax") ||
            lower.contains("api/source") ||
            lower.contains("api/player")
    }

    private fun isDirectM3u8(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8")
    }

    private fun isDirectVideo(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv")
    }

    private fun isBadPlaybackUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com/") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("mailto:") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("histats") ||
            lower.contains("googletagmanager") ||
            lower.contains("google-analytics") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("wp-json") ||
            lower.contains("/wp-content/themes/") ||
            lower.contains("/wp-content/plugins/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".woff") ||
            lower.endsWith(".woff2") ||
            lower.endsWith(".ttf")
    }

    private fun getTypeFromUrl(url: String, title: String, extra: String): TvType {
        val check = "$url $title $extra".lowercase()

        return when {
            check.contains("tv-series") ||
                check.contains("series") ||
                check.contains("episode") ||
                check.contains("season") ||
                Regex("""\bs\d{1,2}e\d{1,3}\b""").containsMatchIn(check) -> TvType.TvSeries

            else -> TvType.Movie
        }
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)[-/\s]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractYear(text: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun qualityFromName(url: String): Int {
        return getQualityFromName(url).takeIf {
            it != Qualities.Unknown.value
        } ?: when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            url.contains("240", true) -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.decodeEscaped(): String {
        val cleaned = replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")

        return if (cleaned.contains("%3A%2F%2F", true)) {
            runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
    }

    private fun String.isUiText(): Boolean {
        val lower = trim().lowercase()
        if (lower.isBlank()) LicenseClient.trackActivity(name, "PLAY")
        return true
        if (lower.length <= 1) LicenseClient.trackActivity(name, "PLAY")
        return true
        if (lower.matches(Regex("""^\d+$"""))) LicenseClient.trackActivity(name, "PLAY")
        return true

        return lower in setOf(
            "home", "next", "previous", "prev", "movies", "movie", "tv series",
            "trending", "search", "genre", "country", "year", "tag", "category",
            "watch", "watch now", "tonton", "download", "trailer", "play", "login",
            "register", "read more", "more", "lihat semua", "nonton", "nonton movie", "nonton film",
            "hd", "sd", "cam", "ts", "hdrip", "bluray", "web-dl", "18+", "uncensored"
        )
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)^\s*permalink\s+to\s*:\s*"""), "")
            .replace(Regex("""\s+-\s+Gerakin21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+Gerakin21.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s+Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full\s+Movie.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+Film\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+Movie\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanPlot(): String? {
        return replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() && it.length > 20 }
    }

    private fun isBadImage(url: String): Boolean {
        val lower = url.lowercase()
        return lower.isBlank() ||
            lower.startsWith("data:") ||
            lower.contains("logo") ||
            lower.contains("icon") ||
            lower.contains("avatar") ||
            lower.contains("favicon") ||
            lower.contains("placeholder") ||
            lower.contains("no-image") ||
            lower.endsWith(".svg")
    }
}
