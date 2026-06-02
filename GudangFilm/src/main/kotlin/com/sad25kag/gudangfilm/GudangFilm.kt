package com.sad25kag.gudangfilm

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class GudangFilm : MainAPI() {
    override var mainUrl = "https://www.huazai6.com"
    override var name = "GudangFilm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/" to "Update Terbaru",
        "/tv/" to "TV Series",
        "/genre/action/" to "Action",
        "/genre/horror/" to "Horror",
        "/genre/adventure/" to "Adventure",
        "/genre/comedy/" to "Comedy",
        "/genre/crime/" to "Crime",
        "/genre/drama/" to "Drama",
        "/genre/fantasy/" to "Fantasy",
        "/genre/mystery/" to "Mystery",
        "/genre/romance/" to "Romance",
        "/genre/science-fiction/" to "Science Fiction",
        "/genre/thriller/" to "Thriller",
        "/year/2025/" to "Tahun 2025",
        "/year/2024/" to "Tahun 2024",
        "/year/2023/" to "Tahun 2023",
        "/year/2022/" to "Tahun 2022",
        "/year/2021/" to "Tahun 2021",
        "/year/2020/" to "Tahun 2020",
        "/year/2019/" to "Tahun 2019",
        "/year/2018/" to "Tahun 2018",
        "/country/korea/" to "Korea",
        "/country/japan/" to "Japan",
        "/country/hong-kong/" to "Hong Kong",
        "/country/italy/" to "Italy",
        "/country/usa/" to "USA",
        "/country/germany/" to "Germany",
        "/country/france/" to "France",
        "/country/china/" to "China",
        "/genre/semi-jepang/" to "Semi Jepang",
        "/genre/semi-philippines/" to "Semi Philippines",
        "/genre/semi-korea/" to "Semi Korea"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = try {
            app.get(pageUrl(request.data, page), headers = headers, referer = mainUrl).document
        } catch (_: Throwable) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        return newHomePageResponse(request.name, parseListing(document), hasNext = hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = slugify(keyword)
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/$slug/"
        )
        val results = linkedMapOf<String, SearchResponse>()
        urls.forEach { url ->
            val document = try { app.get(url, headers = headers, referer = mainUrl).document } catch (_: Throwable) { return@forEach }
            parseListing(document)
                .filter { it.name.contains(keyword, true) || it.url.contains(slug, true) || keyword.length <= 3 }
                .forEach { results[contentKey(it.url)] = it }
            if (results.isNotEmpty()) return results.values.take(60)
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val page = fixUrl(url, mainUrl) ?: return null
        val response = try { app.get(page, headers = headers, referer = mainUrl) } catch (_: Throwable) { return null }
        val document = response.document
        val html = normalize(response.text.ifBlank { document.html() })
        val rawTitle = document.selectFirst("h1.entry-title, h1, .entry-title, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(page) }
        if (title.isBlank()) return null

        val poster = findPoster(document, page)
        val text = cleanText(document.text())
        val tags = document.select("a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Watch", true) && !it.contains("gudang", true) }
            .distinct()
            .take(20)
        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = document.selectFirst("a[href*='/year/']")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], .entry-content p, .post-content p, .description, .desc, .sinopsis, .storyline, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, page)
        val recommendations = parseRecommendations(document, page)
        val sourceType = sourceType(document, html)
        val type = inferType(page, title, text, episodes.size, sourceType)

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, page, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, page, type, page) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
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
        val startUrl = fixUrl(data, mainUrl) ?: return false
        val emitted = linkedSetOf<String>()
        val visitedPages = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(url: String, referer: String, source: String = name): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!fixed.isPlayableMedia()) return false
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false
            if (fixed.contains("m3u8", true)) {
                val links = try { generateM3u8(source, fixed, referer, headers = headers + mapOf("Referer" to referer)) } catch (_: Throwable) { emptyList() }
                links.forEach { link ->
                    val linkKey = link.url.substringBefore("#")
                    if (emitted.add(linkKey)) callback(link)
                }
                if (links.isNotEmpty()) LicenseClient.trackActivity(name, "PLAY")
        return true
            }
            callback(newExtractorLink(source, source, fixed, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = qualityFromUrl(fixed)
                this.headers = headers + mapOf("Referer" to referer, "Accept" to "*/*")
            })
            LicenseClient.trackActivity(name, "PLAY")
        return true
        }

        suspend fun emitExtractor(url: String, referer: String): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (fixed.isNoiseUrl()) return false
            if (fixed.isPlayableMedia()) return emitDirect(fixed, referer)
            var localFound = false
            try {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (emitted.add(key)) {
                        localFound = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
            return localFound
        }

        suspend fun inspectPage(url: String, referer: String): List<String> {
            val fixed = fixUrl(url, referer) ?: return emptyList()
            if (!visitedPages.add(fixed)) return emptyList()
            val response = try { app.get(fixed, headers = headers + mapOf("Referer" to referer), referer = referer) } catch (_: Throwable) { return emptyList() }
            val document = response.document
            val html = normalize(response.text.ifBlank { document.html() })
            collectSubtitles(document, fixed, subtitleCallback)
            val links = linkedSetOf<String>()
            collectAjaxPlayers(document, html, fixed).forEach { links.add(it) }
            collectElementLinks(document, fixed).forEach { links.add(it) }
            collectLinksFromHtml(html, fixed).forEach { links.add(it) }
            return links.filterNot { it.isNoiseUrl() }
        }

        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(startUrl to "$mainUrl/")
        var rounds = 0
        while (queue.isNotEmpty() && rounds < 36) {
            rounds++
            val (url, referer) = queue.removeFirst()
            if (url.isPlayableMedia()) {
                if (emitDirect(url, referer)) found = true
                continue
            }
            if (emitExtractor(url, referer)) found = true
            inspectPage(url, referer).forEach { next ->
                when {
                    next.isPlayableMedia() -> if (emitDirect(next, url)) found = true
                    shouldFollow(next) -> queue.add(next to url)
                    else -> if (emitExtractor(next, url)) found = true
                }
            }
        }
        return found
    }

    private fun pageUrl(data: String, page: Int): String {
        val fixed = fixUrl(data, mainUrl) ?: mainUrl
        if (page <= 1) return fixed
        return fixed.trimEnd('/') + "/page/$page/"
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(cardSelector).forEach { element -> element.toSearchResult()?.let { results[contentKey(it.url)] = it } }
        if (results.size < 6) {
            document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href]")
                .forEach { anchor -> anchor.toSearchResult()?.let { results[contentKey(it.url)] = it } }
        }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null
        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src], img[srcset]") ?: anchor.selectFirst("img")
        val title = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null
        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl) ?: anchor.findNearbyImage(mainUrl) ?: return null
        val text = cleanText(container.text())
        val type = inferType(href, title, text, 0, null)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull() ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val score = container.selectFirst(".rating, .score, .imdb, .vote")?.text()?.replace(",", ".")?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        document.select(".episode-list, .episodes, .episodios, .season, .seasons, .tvseason, .tvshows, [class*=episode], [id*=episode], [class*=season], [id*=season]")
            .select("a[href]")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
                if (!isContentUrl(href)) return@forEachIndexed
                val combined = "${element.text()} $href".lowercase(Locale.ROOT)
                if (!combined.contains("episode") && !combined.contains("eps") && !combined.contains("season")) return@forEachIndexed
                val title = cleanText(element.text())
                val ep = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d{1,4})""").find("$title $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?i)(?:/|-)(\d{1,4})(?:/|$)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                episodes[href] = newEpisode(href) {
                    name = title.ifBlank { "Episode $ep" }
                    episode = ep
                }
            }
        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> =
        document.select(".related, .rekomendasi, .recommend, section, .owl-carousel")
            .flatMap { section -> section.select(cardSelector).mapNotNull { it.toSearchResult() } }
            .distinctBy { contentKey(it.url) }
            .filterNot { contentKey(it.url) == contentKey(currentUrl) }
            .take(16)

    private suspend fun collectAjaxPlayers(document: Document, html: String, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val playerOptions = document.select("li.dooplay_player_option, .dooplay_player_option, .dooplay_player, [data-post][data-nume][data-type], [data-post][data-type], [data-id][data-nume]")
        playerOptions.forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { "1" } }
            val type = option.attr("data-type").ifBlank { sourceType(document, html) ?: "movie" }
            if (post.isBlank()) return@forEach
            listOf("doo_player_ajax", "doo_ajax_player", "player_ajax", "muvipro_player_content").forEach { action ->
                val body = try {
                    app.post(ajaxUrl, data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type), headers = ajaxHeaders(pageUrl), referer = pageUrl).text
                } catch (_: Throwable) { "" }
                collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
            }
        }
        Regex("""(?i)(?:post|id)['"]?\s*[:=]\s*['"]?(\d{2,})['"]?""").findAll(html).map { it.groupValues[1] }.distinct().take(4).forEach { post ->
            listOf("movie", "tv").forEach { type ->
                (1..8).forEach { nume ->
                    val body = try {
                        app.post(ajaxUrl, data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume.toString(), "type" to type), headers = ajaxHeaders(pageUrl), referer = pageUrl).text
                    } catch (_: Throwable) { "" }
                    collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
                }
            }
        }
        return links.toList()
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalize(html)
        val links = linkedSetOf<String>()
        val parsed = try { Jsoup.parse(normalized, baseUrl) } catch (_: Throwable) { null }
        parsed?.let { collectElementLinks(it, baseUrl).forEach { link -> links.add(link) } }
        directMedia(normalized, baseUrl).forEach { links.add(it) }
        iframeLinks(normalized, baseUrl).forEach { links.add(it) }
        embeddedLinks(normalized, baseUrl).forEach { links.add(it) }
        base64Links(normalized, baseUrl).forEach { links.add(it) }
        Regex("""(?i)"(?:embed_url|iframe_url|player_url|url|src|file|source|link)"\s*:\s*"([^"]+)"""").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source|link)\s*[:=]\s*['"]([^'"]+)['"]""").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        return links.toList()
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .player iframe[data-src], [id*=player] iframe[src], [class*=player] iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], a[href*='streamtape'], " +
                "a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], " +
                "a[href*='filelions'], a[href*='hubcloud'], a[href*='gdplayer'], a[href*='gdriveplayer'], a[href*='sht'], a[href*='short'], a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("data-src").ifBlank { element.attr("data-litespeed-src").ifBlank { element.attr("href") } } }
            fixUrl(value, baseUrl)?.let { if (!it.isNoiseUrl()) links.add(it) }
        }
        return links.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(SubtitleFile(label, url))
        }
    }

    private fun iframeLinks(html: String, baseUrl: String): List<String> =
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""").findAll(html).mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.toList()

    private fun embeddedLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|hubcloud|short|sht|/e/|/v/|/d/)[^'"]*)['"]""")
            .findAll(html).mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        return links.toList()
    }

    private fun base64Links(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach { links.add(it) } }
        Regex("""(?i)Base64\.decode\(['"]([^'"]+)['"]\)""").findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach { links.add(it) } }
        return links.toList()
    }

    private fun directMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*|/hls/[^'"]+|/stream/[^'"]+)(?:\?[^'"]*)?)['"]""").findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*|/hls/[^\s'"<>\\]+|/stream/[^\s'"<>\\]+)(?:\?[^\s'"<>\\]*)?""").findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        return links.toList()
    }

    private fun decodePossibleUrl(value: String, baseUrl: String): String? {
        val decoded = urlDecode(value).replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';')
        fixUrl(decoded, baseUrl)?.let { return it }
        decodeBase64(decoded)?.let { html ->
            directMedia(html, baseUrl).firstOrNull()?.let { return it }
            iframeLinks(html, baseUrl).firstOrNull()?.let { return it }
            embeddedLinks(html, baseUrl).firstOrNull()?.let { return it }
            if (html.startsWith("http", true) || html.startsWith("//")) fixUrl(html, baseUrl)?.let { return it }
        }
        return null
    }

    private fun sourceType(document: Document, html: String): String? {
        val dataType = document.selectFirst("[data-type]")?.attr("data-type")?.lowercase(Locale.ROOT)
        if (!dataType.isNullOrBlank()) return dataType
        return Regex("""(?i)['"]type['"]\s*:\s*['"](movie|tv|episode)['"]""").find(html)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
    }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int, sourceType: String?): TvType {
        val clean = cleanText("$title $text").lowercase(Locale.ROOT)
        val path = try { URI(url).path.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { "" }
        return when {
            episodeCount > 0 || sourceType == "tv" || sourceType == "episode" || path.contains("/episode/") -> TvType.TvSeries
            clean.contains("korea") || clean.contains("japan") || clean.contains("china") || clean.contains("thailand") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return !lower.isNoiseUrl() && (
            lower.contains("huazai6.com") || lower.contains("sht") || lower.contains("short") || lower.contains("embed") || lower.contains("player") ||
                lower.contains("stream") || lower.contains("drive") || lower.contains("gofile") || lower.contains("dood") || lower.contains("filemoon") ||
                lower.contains("vidhide") || lower.contains("vidguard") || lower.contains("voe") || lower.contains("mp4upload") || lower.contains("uqload") ||
                lower.contains("hubcloud") || lower.contains("gdplayer") || lower.contains("gdriveplayer") || lower.contains("krakenfiles") || lower.contains("filelions")
            )
    }

    private fun ajaxHeaders(referer: String): Map<String, String> = headers + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    private fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(value.orEmpty().replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';'))
        if (raw.isBlank() || raw == "#" || raw.equals("null", true) || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("tel:", true) || raw.startsWith("data:", true) || raw.startsWith("blob:", true) || raw.startsWith("about:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> try { URI(baseUrl).resolve(raw).toString() } catch (_: Throwable) { origin(baseUrl) + "/" + raw.trimStart('/') }
        }
    }

    private fun origin(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Throwable) { mainUrl }

    private fun isContentUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Throwable) { return false }
        val host = uri.host.orEmpty()
        if (!host.contains("huazai6.com", true)) return false
        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank()) return false
        val first = path.substringBefore("/").lowercase(Locale.ROOT)
        val blocked = setOf("genre", "year", "country", "tag", "category", "page", "dmca", "privacy-policy", "contact", "beranda", "wp-admin", "wp-content", "feed", "tv")
        if (first in blocked) return false
        if (url.contains("?s=", true) || url.contains("youtube.com", true) || url.contains("youtu.be", true)) return false
        LicenseClient.trackActivity(name, "PLAY")
        return true
    }

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.selectFirst("a.next, .pagination a:contains(Next), .page-numbers.next, a[href*='/page/${page + 1}/']") != null

    private fun findPoster(document: Document, baseUrl: String): String? {
        listOf("meta[property=og:image]", "meta[name=twitter:image]", ".poster img", ".thumb img", ".cover img", ".entry-content img", "img[itemprop=image]", "article img").forEach { selector ->
            val element = document.selectFirst(selector) ?: return@forEach
            if (element.tagName().equals("meta", true)) {
                fixUrl(element.attr("content"), baseUrl)?.takeIf { it.isImageLike() }?.let { return cleanImageUrl(it) }
            } else {
                element.imageUrl(baseUrl)?.let { return cleanImageUrl(it) }
            }
        }
        return document.body()?.styleImage(baseUrl)?.let { cleanImageUrl(it) }
    }

    private fun Element.bestContainer(): Element {
        var current: Element? = this
        repeat(7) {
            val node = current ?: return this
            val hasImage = node.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src], img[srcset]") != null
            val links = node.select("a[href]").count { fixUrl(it.attr("href"), mainUrl)?.let { href -> isContentUrl(href) } == true }
            if (hasImage && links in 1..4) return node
            current = node.parent()
        }
        return closest("article, .post, .item, .movie, .film, .card, .ml-item, .result-item, .owl-item, .swiper-slide, li, .col, .box") ?: this
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val values = listOf(attr("data-src"), attr("data-original"), attr("data-lazy-src"), attr("data-lazy"), attr("data-wpfc-original-src"), attr("src"), attr("srcset").substringBefore(" "))
        return values.mapNotNull { fixUrl(it, baseUrl) }.firstOrNull { it.isImageLike() && !it.isAdImage() }?.let { cleanImageUrl(it) }
    }

    private fun Element.styleImage(baseUrl: String): String? {
        val style = attr("style") + " " + select("[style]").joinToString(" ") { it.attr("style") }
        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE).find(style)?.groupValues?.getOrNull(2)?.let { fixUrl(it, baseUrl) }?.takeIf { it.isImageLike() && !it.isAdImage() }?.let { cleanImageUrl(it) }
    }

    private fun Element.findNearbyImage(baseUrl: String): String? =
        selectFirst("img")?.imageUrl(baseUrl) ?: parent()?.selectFirst("img")?.imageUrl(baseUrl) ?: parent()?.parent()?.selectFirst("img")?.imageUrl(baseUrl)

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        val lower = text.lowercase(Locale.ROOT)
        return lower !in setOf("home", "beranda", "watch", "watch movie", "watch film", "trailer", "kategori", "tahun", "negara", "sharer", "tweet", "next", "previous", "film semi") &&
            !lower.contains("gudang film") && !lower.contains("arwana") && !lower.contains("slot") && !lower.contains("togel") && !lower.contains("bet")
    }

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
        .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*gudang\\s*film\\s*$"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*gudangfilm\\s*$"), "")
        .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
        .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
        .replace(Regex("(?i)\\s+download\\s+.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanDescription(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*gudang\\s*film\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanText(value: String?): String = value.orEmpty().replace("\u00a0", " ").replace(Regex("\\s+"), " ").trim()

    private fun titleFromUrl(url: String): String {
        val slug = try { URI(url).path.trim('/').substringAfterLast('/') } catch (_: Throwable) { url.substringAfterLast("/") }
            .substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")
        return slug.split("-").filter { it.isNotBlank() }.joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }.let { cleanTitle(it) }
    }

    private fun slugify(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun normalize(value: String): String = urlDecode(value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))
    private fun urlDecode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Throwable) { value }
    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try { String(Base64.getDecoder().decode(padded)) } catch (_: Throwable) { try { String(Base64.getUrlDecoder().decode(padded)) } catch (_: Throwable) { null } }
    }

    private fun cleanImageUrl(value: String): String = value.replace(Regex("""-\d+x\d+(?=\.)"""), "")
    private fun contentKey(url: String): String = url.substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.isImageLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains("image.tmdb.org") || lower.contains("/images/")
    }

    private fun String.isAdImage(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("arwana") || lower.contains("slot") || lower.contains("togel") || lower.contains("bet") || lower.contains("dewa") || lower.contains("logo") || lower.contains("favicon")
    }

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".php") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.contains("mime=image") || lower.contains("=image/")) return false
        return lower.contains(".m3u8") || lower.contains("m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains("videoplayback") || lower.contains("mime=video") || lower.contains("/hls/") || (lower.contains("googlevideo.com") && lower.contains("videoplayback"))
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("whatsapp") || lower.contains("mailto:") || lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("google-analytics") || lower.contains("/wp-content/") || lower.contains("/wp-json/") || lower.contains(".css") || lower.contains(".js") || lower.contains("favicon") || lower.contains("logo") || lower.contains("arwana") || lower.contains("slot") || lower.contains("togel") || lower.contains("bet")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private val cardSelector = listOf(
        "article", ".post", ".item", ".movie", ".film", ".ml-item", ".result-item", ".owl-item", ".swiper-slide", ".poster", ".thumbnail", ".box", ".col"
    ).joinToString(",")
}
