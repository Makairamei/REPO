package com.juraganfilm

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
import java.util.Base64

class JuraganFilmProvider : MainAPI() {
    override var mainUrl = "https://tv44.juragan.film"
    override var name = "JuraganFilm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/kategori-film/box-office/" to "Film Box Office",
        "/film-seri/" to "Film Seri",
        "/kategori-film/ongoing/" to "Ongoing",
        "/kategori-film/drama-serial-korea/" to "Drakor",
        "/kategori-film/drama-serial-mandarin/" to "Mandarin",
        "/kategori-film/drama-serial-jepang/" to "Jepang",
        "/kategori-film/drama-serial-thailand/" to "Thailand",
        "/kategori-film/action/" to "Action",
        "/kategori-film/comedy/" to "Comedy",
        "/kategori-film/drama/" to "Drama",
        "/kategori-film/horror/" to "Horror",
        "/kategori-film/romance/" to "Romance",
        "/kategori-film/sci-fi/" to "Sci-Fi",
        "/kategori-film/anime/" to "Anime"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, referer = mainUrl, timeout = 20000L).document
        val items = parseCards(document).distinctBy { canonicalUrl(it.url) }
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(document, page))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val urls = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/"
        )

        for (url in urls) {
            val document = runCatching {
                app.get(url, headers = headers, referer = mainUrl, timeout = 20000L).document
            }.getOrNull() ?: continue

            val results = parseCards(document)
                .distinctBy { canonicalUrl(it.url) }
                .filter { response ->
                    keyword.split(Regex("\\s+")).any { part ->
                        part.length < 2 || response.name.contains(part, ignoreCase = true)
                    }
                }
                .ifEmpty { parseCards(document).distinctBy { canonicalUrl(it.url) } }

            if (results.isNotEmpty()) {
                return newSearchResponseList(results, hasNext = hasNextPage(document, page))
            }
        }

        return newSearchResponseList(emptyList(), hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val fixedUrl = fixUrl(url, mainUrl) ?: url
        val document = app.get(fixedUrl, headers = headers, referer = mainUrl, timeout = 20000L).document

        val title = parseTitle(document, fixedUrl)
        val poster = parsePoster(document)
        val tags = parseTags(document)
        val plot = parsePlot(document)
        val year = parseYear(document, title)
        val rating = parseRating(document)
        val runtime = parseRuntime(document)
        val actors = parseActors(document)
        val recommendations = parseCards(document)
            .distinctBy { canonicalUrl(it.url) }
            .filter { canonicalUrl(it.url) != canonicalUrl(fixedUrl) }
            .take(12)

        val episodes = parseEpisodes(document, fixedUrl, title, poster)
        return if (isSeriesPage(fixedUrl, document) || episodes.size > 1) {
            newTvSeriesLoadResponse(title, fixedUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(
                title,
                fixedUrl,
                TvType.Movie,
                LoadData(url = fixedUrl, title = title, poster = poster, episode = null).toJson()
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                this.duration = runtime
                addActors(actors)
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
        val parsed = runCatching { AppUtils.parseJson<LoadData>(data) }.getOrNull()
            ?: LoadData(url = data, title = null, poster = null, episode = null)

        val startUrl = fixUrl(parsed.url, mainUrl) ?: return false
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer)?.replace(".txt", ".m3u8") ?: return false
            if (isBadUrl(fixed) || !isDirectMedia(fixed)) return false
            val key = canonicalUrl(fixed)
            if (!emitted.add(key)) return false

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixed,
                    type = if (isHls(fixed)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value }
                        ?: qualityFromUrl(fixed)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to origin(referer),
                        "Accept" to "*/*"
                    )
                }
            )
            LicenseClient.trackActivity(name, "PLAY", data)
        return true
        }

        suspend fun tryExtractor(link: String, referer: String): Boolean {
            val fixed = fixUrl(link, referer) ?: return false
            if (isBadUrl(fixed) || isDirectMedia(fixed)) return emitDirect(fixed, referer)

            var localFound = false
            runCatching {
                loadExtractor(fixed, referer, subtitleCallback) { extractorLink ->
                    val key = canonicalUrl(extractorLink.url)
                    if (emitted.add(key)) {
                        localFound = true
                        callback(extractorLink)
                    }
                }
            }
            return localFound
        }

        suspend fun collectFromPage(url: String, referer: String): List<String> {
            val fixed = fixUrl(url, referer) ?: return emptyList()
            if (!visited.add(canonicalUrl(fixed))) return emptyList()

            val response = runCatching {
                app.get(fixed, headers = headers, referer = referer, timeout = 20000L)
            }.getOrNull() ?: return emptyList()

            val html = response.text.cleanEscaped()
            val document = response.document
            val links = linkedSetOf<String>()

            extractSubtitleUrls(html, fixed).forEach { subtitle ->
                subtitleCallback(newSubtitleFile(subtitle.first, subtitle.second))
            }

            collectElementLinks(document, fixed).forEach(links::add)
            extractMp4ShortcodeLinks(html, fixed, subtitleCallback).forEach(links::add)
            extractPlayableUrls(html, fixed).forEach(links::add)
            extractEncodedUrls(html, fixed).forEach(links::add)
            extractDownloadLinks(document, fixed).forEach { download ->
                links.add(download)
                links.add(download.substringBefore("&force=1") + "&force=1")
            }

            return links
                .mapNotNull { fixUrl(it, fixed) }
                .filterNot { isBadUrl(it) }
                .distinctBy { canonicalUrl(it) }
        }

        val candidates = linkedSetOf<String>()
        candidates.add(startUrl)
        collectFromPage(startUrl, mainUrl).forEach(candidates::add)

        candidates.toList().forEach { candidate ->
            when {
                emitDirect(candidate, startUrl) -> found = true
                tryExtractor(candidate, startUrl) -> found = true
            }
        }
        if (found) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        candidates
            .filter { !isDirectMedia(it) && !isBadUrl(it) }
            .take(12)
            .forEach { page ->
                collectFromPage(page, startUrl).forEach { nested ->
                    when {
                        emitDirect(nested, page) -> found = true
                        tryExtractor(nested, page) -> found = true
                    }
                    if (found) return@forEach
                }
                if (found) return@forEach
            }

        return found
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim()
        val base = when {
            clean.startsWith("http", true) -> clean.trimEnd('/')
            clean == "/" || clean.isBlank() -> mainUrl
            else -> mainUrl.trimEnd('/') + "/" + clean.trim('/')
        }
        return if (page <= 1) {
            if (clean == "/" || clean.isBlank()) mainUrl else "$base/"
        } else {
            "$base/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select("article:has(a[href]), .post:has(a[href]), .item:has(a[href]), .ml-item:has(a[href]), .movie:has(a[href]), .result:has(a[href]), .latestpost:has(a[href])")
            .forEach { element ->
                element.toSearchResponse()?.let { results[canonicalUrl(it.url)] = it }
            }

        document.select("a[href]").forEach { anchor ->
            val href = fixUrl(anchor.attr("href"), mainUrl) ?: return@forEach
            if (!isDetailUrl(href) || isBadUrl(href)) return@forEach
            val card = anchor.closest("article, .post, .item, .ml-item, .movie, .result, .latestpost") ?: anchor.parent() ?: anchor
            val item = card.toSearchResponse(anchor) ?: anchor.toSearchResponse(anchor) ?: return@forEach
            val key = canonicalUrl(item.url)
            val old = results[key]
            if (old == null || old.name.length < item.name.length || old.name.contains("Watch", true)) {
                results[key] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResponse(preferredAnchor: Element? = null): SearchResponse? {
        val anchor = preferredAnchor
            ?: selectFirst("a[href*='/nonton-'], a[href*='/film-seri/'], h2 a[href], h3 a[href], a[href]")
            ?: return null

        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isDetailUrl(href) || isBadUrl(href)) return null

        val title = listOfNotNull(
            selectFirst("h1, h2, h3, .entry-title, .title")?.text(),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.attr("title"),
            anchor.text(),
            href.substringAfterLast('/').replace("-", " ")
        ).firstOrNull { isUsableTitle(it) }?.cleanTitle() ?: return null

        val poster = parseImage(this)
        val year = Regex("""\b(19|20)\d{2}\b""").find(text())?.value?.toIntOrNull()
        val quality = getSearchQuality(
            selectFirst(".quality, .mli-quality, .jtip-quality")?.text()
                ?: Regex("""\b(4K|HD|WEBRip|BluRay|CAM|HDCAM|HDRip|DVDRip)\b""", RegexOption.IGNORE_CASE)
                    .find(text())?.value
        )
        val type = if (href.contains("/film-seri/", true) || text().contains("EPS", true) || title.contains("Season", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                this.quality = quality
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                this.quality = quality
            }
        }
    }

    private fun parseEpisodes(document: Document, currentUrl: String, title: String, poster: String?): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        val text = document.text()
        val base = currentUrl.trimEnd('/').replace(Regex("""/\d+$"""), "")
        val isSeriesLike = currentUrl.contains("/film-seri/", true) ||
            text.contains("Pilih Episode", true) ||
            text.contains("Tayangan", true) ||
            Regex("""\bEPS\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)

        fun addEpisode(url: String, number: Int?, label: String? = null) {
            val fixedUrl = fixUrl(url, currentUrl) ?: return
            val key = canonicalUrl(fixedUrl)
            if (episodes.containsKey(key)) return
            episodes[key] = newEpisode(
                LoadData(url = fixedUrl, title = title, poster = poster, episode = number).toJson()
            ) {
                this.name = label ?: number?.let { "Episode $it" } ?: title
                this.episode = number
                this.posterUrl = poster
            }
        }

        if (isSeriesLike) {
            val currentEpisode = Regex("""/(\d+)/?$""").find(currentUrl)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            addEpisode(
                url = if (currentEpisode <= 1) "$base/" else "${base.trimEnd('/')}/$currentEpisode/",
                number = currentEpisode,
                label = "Episode $currentEpisode"
            )
        }

        document.select("a[href]").forEach { anchor ->
            val href = fixUrl(anchor.attr("href"), currentUrl) ?: return@forEach
            if (!href.startsWith(base, true) && canonicalUrl(href) != canonicalUrl(base)) return@forEach

            val label = anchor.text().trim()
            val episodeNumber = label.toIntOrNull()
                ?: Regex("""(?:episode|eps?)\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""/(\d+)/?$""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@forEach

            if (episodeNumber <= 0 || episodeNumber > 3000) return@forEach
            addEpisode(href, episodeNumber, "Episode $episodeNumber")
        }

        if (episodes.isEmpty()) {
            val currentEpisode = Regex("""/(\d+)/?$""").find(currentUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""EPS\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            addEpisode(
                url = currentUrl,
                number = currentEpisode,
                label = currentEpisode?.let { "Episode $it" } ?: title
            )
        }

        return episodes.values.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    private fun parseTitle(document: Document, url: String): String {
        return listOfNotNull(
            document.selectFirst("h1.entry-title, h1, h3")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        ).firstOrNull { isUsableTitle(it) }?.cleanTitle()
            ?: url.substringAfterLast('/').replace("-", " ").cleanTitle()
    }

    private fun parsePoster(document: Document): String? {
        return fixUrl(
            document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
                ?: parseImage(document),
            mainUrl
        )
    }

    private fun parseImage(element: Element): String? {
        val image = element.selectFirst("img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]")
            ?: return null
        val fromSet = image.attr("data-srcset").ifBlank { image.attr("srcset") }
            .split(",")
            .map { it.trim().substringBefore(" ") }
            .lastOrNull { it.isNotBlank() }
        return fixUrl(
            image.attr("abs:data-src").ifBlank { image.attr("data-src") }
                .ifBlank { image.attr("abs:data-lazy-src") }
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("abs:data-original") }
                .ifBlank { image.attr("data-original") }
                .ifBlank { fromSet.orEmpty() }
                .ifBlank { image.attr("abs:src") }
                .ifBlank { image.attr("src") },
            mainUrl
        )?.takeIf { !isBadImage(it) }
    }

    private fun parseTags(document: Document): List<String> {
        return document.select("a[href*='/kategori-film/'], a[href*='/genre/'], a[href*='/tag/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.equals("Home", true) && !it.matches(Regex("""\d{4}""")) }
            .distinct()
            .take(20)
    }

    private fun parsePlot(document: Document): String? {
        val meta = document.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
        if (!meta.isNullOrBlank()) return meta.cleanSpaces()
        return document.select("p")
            .map { it.text().cleanSpaces() }
            .firstOrNull { it.length > 80 && !it.contains("Keywords:", true) && !it.contains("JuraganFilm", true) }
    }

    private fun parseYear(document: Document, title: String): Int? {
        return Regex("""(?:Year:|Release:)?\s*\b((?:19|20)\d{2})\b""", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b((?:19|20)\d{2})\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseRating(document: Document): String? {
        return Regex("""(?:average|rata-rata)\s+([\d.,]+)""", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.replace(",", ".")
            ?: Regex("""\b([\d.]+)\s*out of\s*10""", RegexOption.IGNORE_CASE)
                .find(document.text())?.groupValues?.getOrNull(1)
    }

    private fun parseRuntime(document: Document): Int? {
        return Regex("""(?:Duration:)?\s*(\d{2,3})\s*(?:Min|min)""", RegexOption.IGNORE_CASE)
            .find(document.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseActors(document: Document): List<Actor> {
        val text = document.text()
        val raw = Regex("""Cast:\s*(.*?)(?:Director:|Sinopsis|Release:|Language:|$)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.length in 2..80 }
            .distinct()
            .take(20)
            .map { Actor(it) }
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(
            "iframe[src], iframe[data-src], embed[src], video[src], video[data-src], source[src], " +
                "a[href], [data-src], [data-url], [data-file], [data-video], [data-iframe], [onclick]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-file"),
                element.attr("data-video"),
                element.attr("data-iframe"),
                element.attr("href"),
                element.attr("onclick")
            ).filter { it.isNotBlank() }.forEach { raw ->
                extractPlayableUrls(raw, baseUrl).forEach(links::add)
                fixUrl(raw, baseUrl)?.let { fixed ->
                    if (isLikelyPlayable(fixed)) links.add(fixed)
                }
            }
        }
        return links.toList()
    }

    private suspend fun extractMp4ShortcodeLinks(
        text: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<String> {
        val links = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex("""(?i)\[MP4[^\]]*plugin\s*=\s*["'”]([^"'”]+)["'”][^\]]*]""")
            .findAll(clean)
            .forEach { match ->
                val block = match.value
                val media = fixUrl(match.groupValues[1], baseUrl)
                if (media != null) links.add(media)
                Regex("""(?i)\bsub\s*=\s*["'”]([^"'”]+)["'”]""")
                    .find(block)?.groupValues?.getOrNull(1)
                    ?.let { fixUrl(it, baseUrl) }
                    ?.let { subtitleCallback(newSubtitleFile("Indonesian", it)) }
            }

        Regex("""(?i)\b(?:plugin|file|src|source|video|url)\s*=\s*["'”]([^"'”]+(?:\.mp4|\.m3u8|\.txt)[^"'”]*)["'”]""")
            .findAll(clean)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .forEach(links::add)

        return links.toList()
    }

    private fun extractDownloadLinks(document: Document, baseUrl: String): List<String> {
        return document.select("a[href*='action=download'], a[href*='/file/']")
            .mapNotNull { fixUrl(it.attr("href"), baseUrl) }
            .filterNot { isBadUrl(it) }
            .distinct()
    }

    private fun extractPlayableUrls(text: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex("""https?://[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.replace(".txt", ".m3u8") }
            .mapNotNull { fixUrl(it, baseUrl) }
            .forEach(urls::add)

        Regex("""//[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { "https:${it.value}".replace(".txt", ".m3u8") }
            .mapNotNull { fixUrl(it, baseUrl) }
            .forEach(urls::add)

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|majorplay|jeniusplay|filemoon|streamwish|wishfast|doodstream|dood\.|vidhide|vidguard|voe|mixdrop|mp4upload|lulu|lulustream|streamtape)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .forEach(urls::add)

        Regex("""(?i)(?:src|href|data-src|data-url|data-file|file|url|source)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(clean)
            .mapNotNull { fixUrl(it.groupValues[1].replace(".txt", ".m3u8"), baseUrl) }
            .filter { isLikelyPlayable(it) }
            .forEach(urls::add)

        return urls.filterNot { isBadUrl(it) }.distinctBy { canonicalUrl(it) }
    }

    private fun extractEncodedUrls(text: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()
            ?.takeIf { it != clean }
            ?.let { extractPlayableUrls(it.cleanEscaped(), baseUrl).forEach(urls::add) }

        Regex("""(?i)atob\(["']([^"']{12,})["']\)""")
            .findAll(clean)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> extractPlayableUrls(decoded.cleanEscaped(), baseUrl).forEach(urls::add) }

        Regex("""(?i)(?:base64|data|hash)\s*[:=]\s*["']([A-Za-z0-9+/_=-]{20,})["']""")
            .findAll(clean)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> extractPlayableUrls(decoded.cleanEscaped(), baseUrl).forEach(urls::add) }

        return urls.distinctBy { canonicalUrl(it) }
    }

    private fun extractSubtitleUrls(text: String, baseUrl: String): List<Pair<String, String>> {
        val subtitles = linkedSetOf<Pair<String, String>>()
        Regex("""https?://[^"'\\\s<>]+?\.(?:srt|vtt|ass)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text.cleanEscaped())
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .forEach { subtitles.add("Subtitle" to it) }

        Regex("""(?i)\bsub\s*=\s*["'”]([^"'”]+)["'”]""")
            .findAll(text.cleanEscaped())
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .forEach { subtitles.add("Indonesian" to it) }

        return subtitles.toList()
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst(
            "a.next, a[rel=next], .pagination a:contains(Next), .nav-links a:contains(Next), " +
                "a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}']"
        ) != null
    }

    private fun isSeriesPage(url: String, document: Document): Boolean {
        val text = document.text()
        return url.contains("/film-seri/", true) ||
            text.contains("Pilih Episode", true) ||
            text.contains("Episodes:", true) ||
            Regex("""EPS\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private fun isDetailUrl(url: String): Boolean {
        val value = url.lowercase()
        if (!value.startsWith(mainUrl.lowercase())) return false
        if (isBadUrl(value)) return false
        val path = runCatching { URI(value).path.orEmpty().trim('/') }.getOrDefault(value)
        if (path.isBlank()) return false
        if (path.startsWith("kategori-film/") || path.startsWith("tag/") || path.startsWith("author/") ||
            path.startsWith("page/") || path.startsWith("file/") || path.startsWith("wp-") ||
            path.startsWith("blog/") || path == "film-seri" || path.startsWith("film-seri/page/") ||
            path == "dmca" || path == "disclaimer" || path == "privacy-policy"
        ) return false
        return path.startsWith("nonton-") || path.startsWith("film-seri/nonton-")
    }

    private fun isUsableTitle(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.length < 2) return false
        return !listOf(
            "Watch", "Watch Movie", "Trailer", "Home", "More Movie", "Download", "Refresh Link",
            "JuraganFilm", "JURAGANFILM", "Latest Movie", "Nonton Film Sub Indo"
        ).any { text.equals(it, true) }
    }

    private fun getSearchQuality(check: String?): SearchQuality? {
        val s = check ?: return null
        return when {
            s.contains("4k", true) || s.contains("2160", true) -> SearchQuality.FourK
            s.contains("cam", true) -> SearchQuality.Cam
            s.contains("hd", true) || s.contains("1080", true) || s.contains("720", true) -> SearchQuality.HD
            s.contains("bluray", true) || s.contains("blu-ray", true) -> SearchQuality.BlueRay
            s.contains("web", true) -> SearchQuality.WebRip
            else -> null
        }
    }

    private fun fixUrl(url: String?, baseUrl: String): String? {
        val clean = url.cleanEscaped().trim().trim('"', '\'', ',', ';')
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true) || clean.startsWith("mailto:", true)) return null
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> origin(baseUrl) + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun origin(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun canonicalUrl(url: String): String = url.substringBefore("#").substringBefore("?").trimEnd('/').lowercase()

    private fun isDirectMedia(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains(".mp4", true) || url.contains(".webm", true)
    }

    private fun isSubtitleMedia(url: String): Boolean {
        return url.contains(".srt", true) || url.contains(".vtt", true) || url.contains(".ass", true)
    }

    private fun isHls(url: String): Boolean = url.contains(".m3u8", true)

    private fun isLikelyPlayable(url: String): Boolean {
        val value = url.lowercase()
        return isDirectMedia(value) || listOf(
            "embed", "player", "stream", "majorplay", "jeniusplay", "filemoon", "streamwish", "wishfast",
            "vidhide", "vidguard", "voe.", "mixdrop", "mp4upload", "streamtape", "dood", "lulu"
        ).any { value.contains(it) }
    }

    private fun isBadUrl(url: String): Boolean {
        val value = url.lowercase()
        if (isDirectMedia(value) || isSubtitleMedia(value)) return false
        return listOf(
            "facebook.com", "twitter.com", "api.whatsapp.com", "telegram", "instagram.com", "youtube.com",
            "googlesyndication", "doubleclick", "ads", "banner", "neoparty", "klik4.me", "pasang-iklan",
            "dmca", "privacy", "disclaimer", "wp-json", "wp-admin", "xmlrpc.php"
        ).any { value.contains(it) }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() || value.startsWith("data:image") || value.contains("blank") ||
            value.contains("placeholder") || value.contains("loader") || value.contains("loading") ||
            value.contains("logo") || value.contains("favicon") || value.endsWith(".svg")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1440", true) -> Qualities.P1440.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
            ?: runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull()
    }

    private fun String?.cleanEscaped(): String {
        return this.orEmpty()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanSpaces()
            .replace(Regex("""(?i)\s*[-–|]\s*JuraganFIlm.*$"""), "")
            .replace(Regex("""(?i)^Nonton\s+Film\s+"""), "Nonton ")
            .replace(Regex("""(?i)\s+Sub\s+Indo\s+Sub\s+Ind.*$"""), " Sub Indo")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanSpaces(): String = replace(Regex("""\s+"""), " ").trim()
}

data class LoadData(
    val url: String,
    val title: String? = null,
    val poster: String? = null,
    val episode: Int? = null
)
