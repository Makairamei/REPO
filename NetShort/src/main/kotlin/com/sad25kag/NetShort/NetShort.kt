package com.sad25kag.NetShort

import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class NetShort : MainAPI() {
    override var mainUrl = "https://netshort.com"
    override var name = "NetShort"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Beranda",

        "id/drama/all-plots" to "Semua Alur",
        "id/dramas/all-plots" to "Semua Alur Legacy",
        "id/all-episodes" to "Semua Episode",

        "id/drama/popular-series" to "Serial Populer",
        "id/dramas/popular-series" to "Serial Populer Legacy",
        "id/drama/epic-dramas" to "Drama Epik",
        "id/dramas/epic-dramas" to "Drama Epik Legacy",

        "id/drama/all-plots?genre=Romantis%20Perkotaan" to "Romantis Perkotaan",
        "id/drama/all-plots?genre=Fantasi%20Perkotaan" to "Fantasi Perkotaan",
        "id/drama/all-plots?genre=Fantasi%20Kreatif" to "Fantasi Kreatif",
        "id/drama/all-plots?genre=Romantis%20Fantasi" to "Romantis Fantasi",
        "id/drama/all-plots?genre=Kehidupan%20Kota" to "Kehidupan Kota",
        "id/drama/all-plots?genre=Persaingan%20Bisnis" to "Persaingan Bisnis",
        "id/drama/all-plots?genre=Konflik%20Keluarga" to "Konflik Keluarga",
        "id/drama/all-plots?genre=Konflik%20Keluarga%20Kaya" to "Konflik Keluarga Kaya",
        "id/drama/all-plots?genre=Keadilan%20Hukum" to "Keadilan Hukum",
        "id/drama/all-plots?genre=Sejarah" to "Sejarah",
        "id/drama/all-plots?genre=Misteri" to "Misteri",
        "id/drama/all-plots?genre=CEO" to "CEO",
        "id/drama/all-plots?genre=Modern" to "Modern",

        "id/drama/all-plots?plot=Serangan%20Balik" to "Serangan Balik",
        "id/drama/all-plots?plot=Balas%20Dendam" to "Balas Dendam",
        "id/drama/all-plots?plot=Menghukum%20Penjahat" to "Menghukum Penjahat",
        "id/drama/all-plots?plot=Identitas%20Rahasia" to "Identitas Rahasia",
        "id/drama/all-plots?plot=Kaya%20Mendadak" to "Kaya Mendadak",
        "id/drama/all-plots?plot=Bangkit%20Kembali" to "Bangkit Kembali",
        "id/drama/all-plots?plot=Hidup%20Kembali" to "Hidup Kembali",
        "id/drama/all-plots?plot=Perjalanan%20Waktu" to "Perjalanan Waktu",
        "id/drama/all-plots?plot=Penyesalan" to "Penyesalan",
        "id/drama/all-plots?plot=Plot%20Twist" to "Plot Twist",
        "id/drama/all-plots?plot=Cinta%20Setelah%20Perceraian" to "Cinta Setelah Perceraian",
        "id/drama/all-plots?plot=Cinta%20Setelah%20Nikah" to "Cinta Setelah Nikah",
        "id/drama/all-plots?plot=Sang%20Juara%20Kembali" to "Sang Juara Kembali",
        "id/drama/all-plots?plot=Pasangan%20Tangguh" to "Pasangan Tangguh",
        "id/drama/all-plots?plot=Pertumbuhan%20Wanita" to "Pertumbuhan Wanita"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/id"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        if (request.data.isBlank()) {
            val rows = parseHomeRows(document)

            if (rows.isNotEmpty()) {
                return newHomePageResponse(
                    rows,
                    hasNext = false
                )
            }
        }

        val filterKeyword = getFilterKeyword(request.data)

        var items = parseCards(document)
            .distinctBy { it.url }

        if (!filterKeyword.isNullOrBlank()) {
            val filtered = items.filter {
                it.name.contains(filterKeyword, ignoreCase = true)
            }

            if (filtered.isNotEmpty()) {
                items = filtered
            }
        }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val clean = path.trim().trim('/')

        return when {
            page <= 1 && clean.isBlank() -> "$mainUrl/id"
            page <= 1 -> "$mainUrl/$clean"

            clean.isBlank() -> "$mainUrl/id/page/$page"

            clean.contains("?") -> {
                val base = clean.substringBefore("?").trim('/')
                val query = clean.substringAfter("?")
                "$mainUrl/$base?page=$page&$query"
            }

            else -> "$mainUrl/$clean?page=$page"
        }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a[rel=next], " +
                "a.next, " +
                ".pagination a:contains(Next), " +
                "a[href*='page=${page + 1}'], " +
                "a[href*='/page/${page + 1}'], " +
                "button:contains(${page + 1})"
        ) != null
    }

    private fun getFilterKeyword(path: String): String? {
        val raw = when {
            path.contains("genre=", true) -> path.substringAfter("genre=").substringBefore("&")
            path.contains("plot=", true) -> path.substringAfter("plot=").substringBefore("&")
            else -> return null
        }

        return runCatching {
            URLDecoder.decode(raw, "UTF-8")
        }.getOrDefault(raw)
            .replace("+", " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        document.select("h1, h2, h3").forEach { heading ->
            val title = heading.text().cleanTitle()

            if (
                title.isBlank() ||
                title.equals("NetShort", true) ||
                title.equals("Tentang", true) ||
                title.equals("Hubungi Kami", true) ||
                title.equals("Komunitas", true) ||
                title.equals("Unduh Aplikasi", true) ||
                title.equals("Bahasa Indonesia", true)
            ) {
                return@forEach
            }

            val items = linkedMapOf<String, SearchResponse>()

            var sibling = heading.nextElementSibling()
            var guard = 0

            while (sibling != null && guard < 12) {
                if (sibling.`is`("h1, h2, h3")) break

                sibling.parseCardsFromElement().forEach {
                    items[it.url] = it
                }

                sibling = sibling.nextElementSibling()
                guard++
            }

            if (items.isEmpty()) {
                heading.parent()?.parseCardsFromElement()?.forEach {
                    items[it.url] = it
                }
            }

            if (items.isNotEmpty()) {
                rows.add(
                    HomePageList(
                        title,
                        items.values.toList().take(24),
                        isHorizontalImages = false
                    )
                )
            }
        }

        if (rows.isEmpty()) {
            val fallback = parseCards(document)

            if (fallback.isNotEmpty()) {
                rows.add(
                    HomePageList(
                        "NetShort",
                        fallback.take(30),
                        isHorizontalImages = false
                    )
                )
            }
        }

        return rows.distinctBy { it.name }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                "li:has(a), " +
                ".swiper-slide:has(a), " +
                ".drama-item:has(a), " +
                ".video-item:has(a), " +
                ".movie-item:has(a), " +
                ".recommend-item:has(a), " +
                ".recommend-list div:has(a), " +
                ".grid div:has(a), " +
                ".list div:has(a), " +
                "main div:has(a[href*='/episode/']), " +
                "main div:has(a[href*='/full-episodes/']), " +
                "main a[href*='/episode/'], " +
                "main a[href*='/full-episodes/']"
        ).forEach { element ->
            element.toSearchResult()?.let {
                results[it.url] = it
            }
        }

        if (results.isEmpty()) {
            document.select(
                "a[href*='/episode/'], " +
                    "a[href*='/full-episodes/'], " +
                    "a[href]:has(img)"
            ).forEach { element ->
                element.toSearchResult()?.let {
                    results[it.url] = it
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.parseCardsFromElement(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        select(
            "article:has(a), " +
                "li:has(a), " +
                ".swiper-slide:has(a), " +
                ".drama-item:has(a), " +
                ".video-item:has(a), " +
                ".movie-item:has(a), " +
                ".recommend-item:has(a), " +
                "div:has(a[href*='/episode/']), " +
                "div:has(a[href*='/full-episodes/']), " +
                "a[href*='/episode/'], " +
                "a[href*='/full-episodes/']"
        ).forEach { element ->
            element.toSearchResult()?.let {
                results[it.url] = it
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a[href*='/id/episode/'], " +
                    "a[href*='/episode/'], " +
                    "a[href*='/id/full-episodes/'], " +
                    "a[href*='/full-episodes/'], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = normalizeUrl(anchor.attr("href"), mainUrl)

        if (href.isBlank()) return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst("[class*=title]")?.text(),
            selectFirst(".name")?.text(),
            selectFirst("[class*=name]")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !isBadTitle(it)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        return newMovieSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
            posterHeaders = headers
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val allowed = listOf(
            "id/episode/",
            "id/full-episodes/",
            "episode/",
            "full-episodes/"
        )

        if (allowed.any { path.startsWith(it) || path.contains("/${it}") }) {
            return false
        }

        val blockedPrefixes = listOf(
            "id/drama",
            "id/dramas",
            "id/all-episodes",
            "id/search",
            "id/blog",
            "id/privacy",
            "id/terms",
            "id/contact",
            "id/about",
            "id/login",
            "id/register",
            "id/download",
            "assets",
            "_nuxt",
            "favicon",
            "api"
        )

        return blockedPrefixes.any {
            path == it.trimEnd('/') || path.startsWith(it)
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")

        val attempts = listOf(
            if (page <= 1) "$mainUrl/id/search?keyword=$encoded" else "$mainUrl/id/search?keyword=$encoded&page=$page",
            if (page <= 1) "$mainUrl/id/search?q=$encoded" else "$mainUrl/id/search?q=$encoded&page=$page",
            if (page <= 1) "$mainUrl/id/all-episodes" else "$mainUrl/id/all-episodes?page=$page",
            if (page <= 1) "$mainUrl/id/drama/all-plots" else "$mainUrl/id/drama/all-plots?page=$page",
            if (page <= 1) "$mainUrl/id/dramas/all-plots" else "$mainUrl/id/dramas/all-plots?page=$page"
        )

        var bestResults: List<SearchResponse> = emptyList()
        var hasNext = false

        for (url in attempts) {
            val document = runCatching {
                app.get(
                    url,
                    headers = headers,
                    timeout = 30L
                ).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .filter { it.name.contains(keyword, ignoreCase = true) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) {
                bestResults = results
                hasNext = hasNextPage(document, page)
                break
            }
        }

        return newSearchResponseList(
            bestResults,
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url, mainUrl)

        val document = app.get(
            fixedUrl,
            headers = headers,
            timeout = 30L
        ).document

        val title = getTitle(document, fixedUrl)
        val poster = getPoster(document)
        val plot = getPlot(document)

        val tags = document.select(
            "a[href*='/tag/'], " +
                "a[href*='/genre/'], " +
                "a[href*='/drama/'], " +
                "a[href*='/dramas/']"
        ).map { it.text().cleanTitle() }
            .filter {
                it.isNotBlank() &&
                    !isBadTitle(it) &&
                    !it.equals("Beranda", true) &&
                    !it.equals("Serial Drama", true) &&
                    !it.equals("Semua Episode", true) &&
                    !it.equals("Unduh", true) &&
                    !it.equals("Blog", true)
            }
            .distinct()
            .take(20)

        val episodes = parseEpisodes(document, fixedUrl)

        val recommendations = parseCards(document)
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }
            .take(18)

        return newTvSeriesLoadResponse(
            title,
            fixedUrl,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            posterHeaders = headers
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun getTitle(
        document: Document,
        url: String
    ): String {
        val h1 = document.selectFirst("h1")
            ?.text()
            ?.cleanTitle()
            .orEmpty()

        val cleanH1 = h1
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+ep\s+\d+.*$"""), "")
            .cleanTitle()

        val metaTitle = document.selectFirst("meta[property=og:title], meta[name=twitter:title]")
            ?.attr("content")
            ?.cleanTitle()
            .orEmpty()
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), "")
            .cleanTitle()

        return cleanH1
            .ifBlank { metaTitle }
            .ifBlank {
                url.substringAfterLast("/")
                    .substringBeforeLast("-ep-")
                    .replace("-", " ")
                    .cleanTitle()
                    .replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
            }
            .ifBlank { name }
    }

    private fun getPlot(document: Document): String? {
        val selectors = listOf(
            "meta[name=description]",
            "meta[property=og:description]",
            ".desc",
            ".description",
            "[class*=desc]",
            "[class*=summary]",
            "p"
        )

        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                val text = element.attr("content")
                    .ifBlank { element.text() }
                    .cleanTitle()

                if (
                    text.length > 40 &&
                    !text.equals("NetShort", true) &&
                    !text.contains("download", true)
                ) {
                    return text
                }
            }
        }

        return null
    }

    private fun parseEpisodes(
        document: Document,
        currentUrl: String
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        val currentEpisode = parseEpisodeNumber(
            document.selectFirst("h1")?.text().orEmpty()
        ) ?: currentUrl.substringAfterLast("-ep-", "")
            .substringBefore("/")
            .toIntOrNull()
            ?: 1

        episodes[currentUrl] = newEpisode(currentUrl) {
            name = "Episode $currentEpisode"
            episode = currentEpisode
        }

        document.select(
            "a[href*='/id/episode/'], " +
                "a[href*='/episode/'], " +
                "a[href*='/id/full-episodes/'], " +
                "a[href*='/full-episodes/']"
        ).forEachIndexed { index, element ->
            val href = normalizeUrl(element.attr("href"), currentUrl)

            if (href.isBlank() || !href.startsWith(mainUrl)) {
                return@forEachIndexed
            }

            val number = parseEpisodeNumber(element.text())
                ?: href.substringAfterLast("-ep-", "")
                    .substringBefore("/")
                    .toIntOrNull()
                ?: index + 1

            episodes[href] = newEpisode(href) {
                name = "Episode $number"
                episode = number
            }
        }

        return episodes.values
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name }
            )
    }

    private fun parseEpisodeNumber(text: String): Int? {
        return Regex("""(?i)(?:episode|ep)?\s*(\d{1,4})""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data, mainUrl)

        val response = app.get(
            pageUrl,
            headers = headers,
            referer = "$mainUrl/id",
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "video[src], " +
                "video source[src], " +
                "source[src], " +
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "a[href]"
        ).forEach { element ->
            val href = element.attr("href")

            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .ifBlank { href }
                .trim()

            if (raw.isBlank()) return@forEach

            if (
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                raw.contains("mailto:", true) ||
                raw.contains("facebook.com", true) ||
                raw.contains("twitter.com", true) ||
                raw.contains("telegram", true) ||
                raw.contains("whatsapp", true)
            ) {
                return@forEach
            }

            if (
                element.tagName().equals("video", true) ||
                element.tagName().equals("source", true) ||
                element.tagName().equals("iframe", true) ||
                isLikelyPlayable(raw)
            ) {
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        if (directLinks.isEmpty() && embedLinks.isEmpty()) {
            collectScriptTexts(document).forEach { scriptText ->
                extractPlayableUrls(scriptText).forEach { raw ->
                    addCandidate(raw, pageUrl, directLinks, embedLinks)
                }
            }
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(
                link = link,
                referer = pageUrl,
                callback = callback
            )
            found = true
        }

        embedLinks.distinct().forEach { embed ->
            val success = loadExtractor(
                embed,
                pageUrl,
                subtitleCallback,
                callback
            )

            if (success) {
                found = true
            } else {
                resolveNestedLinks(embed, pageUrl).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed)
                        .cleanEscaped()
                        .replace(".txt", ".m3u8")

                    when {
                        isAdUrl(fixed) -> Unit

                        isHlsLike(fixed) ||
                            fixed.contains(".mp4", true) ||
                            fixed.contains(".webm", true) -> {
                            emitDirectLink(
                                link = fixed,
                                referer = embed,
                                callback = callback
                            )
                            found = true
                        }

                        fixed.startsWith("http", true) -> {
                            val nestedSuccess = loadExtractor(
                                fixed,
                                embed,
                                subtitleCallback,
                                callback
                            )

                            if (nestedSuccess) found = true
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun collectScriptTexts(document: Document): List<String> {
        val scriptUrls = document.select("script[src]")
            .mapNotNull {
                normalizeUrl(it.attr("src"), mainUrl).takeIf { url ->
                    url.isNotBlank() &&
                        url.startsWith("http", true) &&
                        (
                            url.contains("/_nuxt/", true) ||
                                url.contains(".js", true)
                            )
                }
            }
            .distinct()
            .take(12)

        return scriptUrls.mapNotNull { scriptUrl ->
            runCatching {
                app.get(
                    scriptUrl,
                    headers = headers,
                    referer = "$mainUrl/id",
                    timeout = 30L
                ).text.cleanEscaped()
            }.getOrNull()
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        val text = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        return extractPlayableUrls(text)
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isAdUrl(fixed)) return

        when {
            isHlsLike(fixed) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) &&
                isKnownHost(fixed) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("player", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("embed", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link)) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHlsLike(link)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "Accept" to "*/*"
                )
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|video|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url|contentUrl)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    isKnownHost(it)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|majorplay|jeniusplay)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "embed",
            "player",
            "stream",
            "filemoon",
            "streamwish",
            "wishfast",
            "dood",
            "streamtape",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "mp4upload",
            "lulustream",
            "lulu",
            "hglink",
            "hgcloud",
            "majorplay",
            "jeniusplay"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".txt", true) ||
            isKnownHost(url)
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            (
                url.contains("majorplay", true) &&
                    url.contains("config", true) &&
                    url.contains(".json", true)
                )
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "video[poster], " +
                    ".poster img, " +
                    ".thumb img, " +
                    ".cover img, " +
                    "[class*=poster] img, " +
                    "[class*=cover] img, " +
                    "article img, " +
                    "main img, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !isBadImage(it) }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() &&
                        !isBadImage(it)
                }
        }

        val raw = fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:data-full").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("data-original").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw
            ?.trim()
            ?.takeIf { !isBadImage(it) }
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped().trim()

        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"

            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: mainUrl

                "$origin$clean"
            }

            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault("$mainUrl/${clean.trimStart('/')}")
        }
    }

    private fun isBadTitle(text: String): Boolean {
        val value = text.cleanTitle().lowercase()

        return value.isBlank() ||
            value == "netshort" ||
            value == "home" ||
            value == "beranda" ||
            value == "serial drama" ||
            value == "semua episode" ||
            value == "blog" ||
            value == "unduh" ||
            value == "unduh aplikasi" ||
            value == "play" ||
            value == "play now" ||
            value == "tonton sekarang" ||
            value == "next" ||
            value == "previous" ||
            value == "prev" ||
            value == "share" ||
            value == "login" ||
            value == "register" ||
            value == "bahasa indonesia"
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("data:image") ||
            value.contains("blank") ||
            value.contains("placeholder") ||
            value.contains("default") ||
            value.contains("no-image") ||
            value.contains("noimage") ||
            value.contains("loader") ||
            value.contains("loading") ||
            value.contains("lazy") ||
            value.contains("spacer") ||
            value.contains("logo") ||
            value.contains("favicon") ||
            value.contains("banner") ||
            value.endsWith(".svg")
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("/ads/") ||
            value.contains("advert") ||
            value.contains("banner") ||
            value.contains("tracking") ||
            value.contains("analytics") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1440", true) -> Qualities.P1440.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) -> Qualities.P480.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("\\u002D", "-")
            .replace("\\u005C", "\\")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+"""), " ")
            .replace("Tonton Sekarang", "", ignoreCase = true)
            .replace("Play Now", "", ignoreCase = true)
            .replace("Full Episodes", "", ignoreCase = true)
            .replace("All Episodes", "", ignoreCase = true)
            .replace("NetShort", "", ignoreCase = true)
            .trim(' ', '-', '|', ':')
            .trim()
    }
}