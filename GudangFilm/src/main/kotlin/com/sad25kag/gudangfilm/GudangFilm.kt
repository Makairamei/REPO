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

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

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
        val url = buildPageUrl(request.data, page)
        val document = try {
            app.get(url, headers = headers, referer = mainUrl).document
        } catch (_: Throwable) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = parseListing(document)
        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = keyword.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/$slug/"
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in urls) {
            val document = try {
                app.get(url, headers = headers, referer = mainUrl).document
            } catch (_: Throwable) {
                continue
            }

            parseListing(document)
                .filter { response ->
                    response.name.contains(keyword, ignoreCase = true) ||
                        response.url.contains(slug, ignoreCase = true) ||
                        keyword.length <= 3
                }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) break
        }

        if (results.isEmpty() && keyword.length <= 4) {
            val fallbackPages = listOf(
                mainUrl,
                "$mainUrl/genre/action/",
                "$mainUrl/genre/drama/",
                "$mainUrl/genre/horror/",
                "$mainUrl/tv/"
            )

            for (url in fallbackPages) {
                val document = try {
                    app.get(url, headers = headers, referer = mainUrl).document
                } catch (_: Throwable) {
                    continue
                }

                parseListing(document).forEach { results[it.url] = it }
                if (results.isNotEmpty()) break
            }
        }

        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url, mainUrl) ?: return null
        val document = try {
            app.get(pageUrl, headers = headers, referer = mainUrl).document
        } catch (_: Throwable) {
            return null
        }

        val rawTitle = document.selectFirst("h1, h1.entry-title, .entry-title, meta[property=og:title]")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val text = cleanText(document.text())

        val tags = document.select("a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Watch", true) }
            .distinct()
            .take(20)

        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)

        val year = document.selectFirst("a[href*='/year/']")
            ?.text()
            ?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()

        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote")
            ?.text()
            ?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val description = cleanText(
            document.selectFirst(
                "meta[property=og:description], meta[name=description], .entry-content p, .post-content p, .description, .desc, .sinopsis, .storyline, [itemprop=description]"
            )?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            }
        )

        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val episodes = parseEpisodes(document, pageUrl)
        val recommendations = parseRecommendations(document, pageUrl)
        val type = inferType(pageUrl, title, text, episodes.isNotEmpty())

        return if (type == TvType.TvSeries || episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes.ifEmpty {
                listOf(newEpisode(pageUrl) {
                    this.name = "Episode 1"
                    this.episode = 1
                })
            }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
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
        val startUrl = fixUrl(data, mainUrl) ?: return false
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(url: String, referer: String, source: String = name): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!fixed.isPlayableMedia()) return false

            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false

            if (fixed.contains(".m3u8", true)) {
                val links = try {
                    generateM3u8(source, fixed, referer, headers = headers + mapOf("Referer" to referer))
                } catch (_: Throwable) {
                    emptyList()
                }

                links.forEach { link ->
                    val linkKey = link.url.substringBefore("#")
                    if (emitted.add(linkKey)) callback(link)
                }

                if (links.isNotEmpty()) return true
            }

            callback(
                newExtractorLink(source, source, fixed, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = qualityFromUrl(fixed)
                    this.headers = headers + mapOf(
                        "Referer" to referer,
                        "Accept" to "*/*"
                    )
                }
            )

            return true
        }

        suspend fun tryExtractor(url: String, referer: String): Boolean {
            var emittedByExtractor = false
            try {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    if (link.url.isPlayableMedia()) {
                        val key = link.url.substringBefore("#")
                        if (emitted.add(key)) {
                            emittedByExtractor = true
                            callback(link)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return emittedByExtractor
        }

        suspend fun resolve(url: String, referer: String, depth: Int = 0): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (depth > 5 || !visited.add(fixed)) return false
            if (fixed.isNoiseUrl()) return false

            if (fixed.isPlayableMedia()) {
                return emitDirect(fixed, referer)
            }

            var localFound = false
            if (tryExtractor(fixed, referer)) localFound = true

            val response = try {
                app.get(fixed, headers = headers + mapOf("Referer" to referer), referer = referer)
            } catch (_: Throwable) {
                return localFound
            }

            val document = response.document
            val html = normalizeHtml(response.text.ifBlank { document.html() })

            collectSubtitles(document, fixed, subtitleCallback)

            extractDirectMedia(html, fixed).forEach { media ->
                if (emitDirect(media, fixed)) localFound = true
            }

            val ajaxLinks = resolveAjaxPlayers(document, fixed)
            ajaxLinks.forEach { player ->
                if (player.isPlayableMedia()) {
                    if (emitDirect(player, fixed)) localFound = true
                } else if (resolve(player, fixed, depth + 1)) {
                    localFound = true
                }
            }

            val embeds = linkedSetOf<String>()
            collectElementLinks(document, fixed).forEach { embeds.add(it) }
            extractIframeLinks(html, fixed).forEach { embeds.add(it) }
            extractEmbedLinks(html, fixed).forEach { embeds.add(it) }
            extractBase64Links(html, fixed).forEach { embeds.add(it) }

            embeds
                .filterNot { it.isNoiseUrl() }
                .forEach { embed ->
                    if (embed.isPlayableMedia()) {
                        if (emitDirect(embed, fixed)) localFound = true
                    } else if (resolve(embed, fixed, depth + 1)) {
                        localFound = true
                    }
                }

            return localFound
        }

        if (resolve(startUrl, "$mainUrl/")) found = true
        return found
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val fixed = fixUrl(data, mainUrl) ?: mainUrl
        if (page <= 1) return fixed

        return if (fixed.endsWith("/")) {
            fixed + "page/$page/"
        } else {
            fixed.trimEnd('/') + "/page/$page/"
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(listingSelector).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.size < 8) {
            document.select("a[href]").forEach { anchor ->
                anchor.toSearchResult()?.let { results[it.url] = it }
            }
        }

        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val container = closest("article, .post, .item, .movie, .film, .card, .ml-item, .result-item, li, .col, .box") ?: this
        val anchor = when {
            `is`("a[href]") -> this
            else -> container.selectFirst(
                "h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href][title], a[href]"
            )
        } ?: return null

        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null

        val image = container.selectFirst("img[alt], img[data-src], img[data-original], img[data-lazy-src], img[src]")
            ?: anchor.selectFirst("img")

        val rawTitle = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) } ?: return null

        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(href) }
        if (!isUsefulTitle(title)) return null

        val poster = image?.imageUrl(mainUrl)
            ?: container.styleImage(mainUrl)
            ?: return null

        val text = container.text()
        val type = inferType(href, title, text, false)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()

        val score = container.selectFirst(".rating, .score, .imdb, .vote")
            ?.text()
            ?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], a[href*='/eps/'], a[href*='season'], .episode-list a[href], .episodes a[href], .episodios a[href], [class*=episode] a[href], [id*=episode] a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
            if (!isContentUrl(href)) return@forEachIndexed

            val text = cleanText(element.text())
            val epNum = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d{1,4})""")
                .find("$text $href")
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: (index + 1)

            episodes[href] = newEpisode(href) {
                name = text.ifBlank { "Episode $epNum" }
                episode = epNum
            }
        }

        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> {
        return document.select(".related, .rekomendasi, .recommend, section, .owl-carousel")
            .flatMap { section -> section.select(listingSelector).mapNotNull { it.toSearchResult() } }
            .distinctBy { it.url }
            .filterNot { it.url == currentUrl }
            .take(16)
    }

    private suspend fun resolveAjaxPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        document.select("li.dooplay_player_option, .dooplay_player_option, [data-post][data-nume][data-type]").forEach { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

            val body = try {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = ajaxHeaders(pageUrl),
                    referer = pageUrl
                ).text
            } catch (_: Throwable) {
                ""
            }

            collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
        }

        val postId = document.selectFirst("#muvipro_player_content_id, [data-postid], [data-post]")
            ?.let { it.attr("data-postid").ifBlank { it.attr("data-post").ifBlank { it.attr("data-id") } } }

        if (!postId.isNullOrBlank()) {
            document.select("[data-tab], .tab-content[id], .player-content[id], .tab-pane[id]").forEach { tab ->
                val tabId = tab.attr("data-tab").ifBlank { tab.attr("id") }
                if (tabId.isBlank()) return@forEach

                val body = try {
                    app.post(
                        ajaxUrl,
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to postId
                        ),
                        headers = ajaxHeaders(pageUrl),
                        referer = pageUrl
                    ).text
                } catch (_: Throwable) {
                    ""
                }

                collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
            }
        }

        return links.toList()
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalizeHtml(html)
        val links = linkedSetOf<String>()

        extractDirectMedia(normalized, baseUrl).forEach { links.add(it) }
        extractIframeLinks(normalized, baseUrl).forEach { links.add(it) }
        extractEmbedLinks(normalized, baseUrl).forEach { links.add(it) }
        extractBase64Links(normalized, baseUrl).forEach { links.add(it) }

        Regex("""(?i)"(?:embed_url|url|src|file|source)"\s*:\s*"([^"]+)"""")
            .findAll(normalized)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], " +
                "a[href*='streamtape'], a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], " +
                "a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], a[href*='filelions'], a[href*='.mp4'], a[href*='.m3u8'], " +
                "[data-url], [data-src], [data-embed], [data-iframe], [data-link], [data-file], [data-video], [data-player]"
        ).forEach { element ->
            listOf(
                "data-litespeed-src",
                "data-src",
                "data-url",
                "data-embed",
                "data-iframe",
                "data-link",
                "data-file",
                "data-video",
                "data-player",
                "src",
                "href"
            ).forEach { attr ->
                val raw = element.attr(attr)
                if (raw.isNotBlank()) decodePossibleUrl(raw, baseUrl)?.let { links.add(it) }
            }
        }

        return links.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.vtt], a[href$=.srt], a[href*='.vtt'], a[href*='.srt']").forEach { element ->
            val subUrl = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(SubtitleFile(label, subUrl))
        }
    }

    private fun extractIframeLinks(html: String, baseUrl: String): List<String> {
        return Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .toList()
    }

    private fun extractEmbedLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|hubcloud|short|sht|/e/|/v/|/d/)[^'"]*)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun extractBase64Links(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded ->
                collectLinksFromHtml(decoded, baseUrl).forEach { links.add(it) }
            }

        return links.toList()
    }

    private fun extractDirectMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()

        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*)(?:\?[^'"]*)?)['"]""")
            .findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }

        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*)(?:\?[^\s'"<>\\]*)?""")
            .findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }

        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }

        return links.toList()
    }

    private fun decodePossibleUrl(value: String, baseUrl: String): String? {
        val decoded = urlDecode(value)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ',', ';')

        fixUrl(decoded, baseUrl)?.let { return it }

        decodeBase64(decoded)?.let { html ->
            extractDirectMedia(html, baseUrl).firstOrNull()?.let { return it }
            extractIframeLinks(html, baseUrl).firstOrNull()?.let { return it }
            if (html.startsWith("http", true) || html.startsWith("//")) {
                fixUrl(html, baseUrl)?.let { return it }
            }
        }

        return null
    }

    private fun normalizeHtml(value: String): String {
        return urlDecode(
            value.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        )
    }

    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null

        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)

        return try {
            String(Base64.getDecoder().decode(padded))
        } catch (_: Throwable) {
            try {
                String(Base64.getUrlDecoder().decode(padded))
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun findPoster(document: Document, baseUrl: String): String? {
        listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            ".poster img",
            ".thumb img",
            ".cover img",
            ".entry-content img",
            "img[itemprop=image]",
            "article img"
        ).forEach { selector ->
            val element = document.selectFirst(selector) ?: return@forEach

            if (element.tagName().equals("meta", true)) {
                fixUrl(element.attr("content"), baseUrl)?.takeIf { it.isImageLike() }?.let { return cleanImageUrl(it) }
            } else {
                element.imageUrl(baseUrl)?.let { return cleanImageUrl(it) }
            }
        }

        return document.body()?.styleImage(baseUrl)?.let { cleanImageUrl(it) }
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-original"),
            attr("data-lazy-src"),
            attr("data-lazy"),
            attr("data-wpfc-original-src"),
            attr("src"),
            attr("srcset").substringBefore(" ")
        )

        return candidates
            .mapNotNull { fixUrl(it, baseUrl) }
            .firstOrNull { it.isImageLike() && !it.isAdImage() }
            ?.let { cleanImageUrl(it) }
    }

    private fun Element.styleImage(baseUrl: String): String? {
        val style = attr("style") + " " + select("[style]").joinToString(" ") { it.attr("style") }

        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { fixUrl(it, baseUrl) }
            ?.takeIf { it.isImageLike() && !it.isAdImage() }
            ?.let { cleanImageUrl(it) }
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.selectFirst("a.next, .pagination a:contains(Next), .page-numbers.next, a[href*='/page/${page + 1}/']") != null
    }

    private fun inferType(url: String, title: String, text: String, hasEpisodes: Boolean): TvType {
        val combined = "$url $title $text".lowercase(Locale.ROOT)

        return when {
            hasEpisodes -> TvType.TvSeries
            combined.contains("/tv/") -> TvType.TvSeries
            combined.contains("tv series") -> TvType.TvSeries
            combined.contains("episode") || combined.contains("season") -> TvType.TvSeries
            combined.contains("korea") || combined.contains("japan") || combined.contains("china") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(
            value.orEmpty()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()
                .trim('"', '\'', ',', ';')
        )

        if (
            raw.isBlank() ||
            raw == "#" ||
            raw.equals("null", true) ||
            raw.startsWith("javascript:", true) ||
            raw.startsWith("mailto:", true) ||
            raw.startsWith("tel:", true) ||
            raw.startsWith("data:", true) ||
            raw.startsWith("blob:", true) ||
            raw.startsWith("about:", true)
        ) return null

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> try {
                URI(baseUrl).resolve(raw).toString()
            } catch (_: Throwable) {
                origin(baseUrl) + "/" + raw.trimStart('/')
            }
        }
    }

    private fun origin(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Throwable) {
            mainUrl
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            return false
        }

        val host = uri.host.orEmpty()
        if (!host.contains("huazai6.com", true)) return false

        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank()) return false

        val first = path.substringBefore("/")
        val blocked = setOf(
            "genre",
            "year",
            "country",
            "tag",
            "category",
            "page",
            "dmca",
            "privacy-policy",
            "contact",
            "beranda",
            "wp-admin",
            "wp-content",
            "feed",
            "tv"
        )

        if (first.lowercase(Locale.ROOT) in blocked) return false
        if (url.contains("?s=", true)) return false
        if (url.contains("youtube.com", true) || url.contains("youtu.be", true)) return false

        return true
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanText(value)
        if (text.length < 2) return false

        val lower = text.lowercase(Locale.ROOT)
        return lower !in setOf(
            "home",
            "beranda",
            "watch",
            "watch movie",
            "trailer",
            "kategori",
            "tahun",
            "negara",
            "sharer",
            "tweet",
            "next",
            "previous",
            "film semi"
        ) && !lower.contains("gudang film") &&
            !lower.contains("arwana") &&
            !lower.contains("slot") &&
            !lower.contains("togel") &&
            !lower.contains("bet")
    }

    private fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .replace(Regex("(?i)\\s+download\\s+.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun titleFromUrl(url: String): String {
        val slug = try {
            URI(url).path.trim('/').substringAfterLast('/')
        } catch (_: Throwable) {
            url.substringAfterLast("/")
        }
            .substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")

        return slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
    }

    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }

    private fun cleanImageUrl(value: String): String {
        return value.replace(Regex("""-\d+x\d+(?=\.)"""), "")
    }

    private fun String.isImageLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains("image.tmdb.org") ||
            lower.contains("/images/")
    }

    private fun String.isAdImage(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("arwana") ||
            lower.contains("slot") ||
            lower.contains("togel") ||
            lower.contains("bet") ||
            lower.contains("dewa") ||
            lower.contains("logo") ||
            lower.contains("favicon")
    }

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (
            lower.endsWith(".html") ||
            lower.endsWith(".htm") ||
            lower.endsWith(".php") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.contains("mime=image") ||
            lower.contains("=image/")
        ) return false

        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("videoplayback") ||
            lower.contains("mime=video") ||
            (lower.contains("googlevideo.com") && lower.contains("videoplayback"))
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") ||
            lower.contains("telegram") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram") ||
            lower.contains("whatsapp") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("youtube.com") ||
            lower.contains("youtu.be") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)

        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            lower.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun ajaxHeaders(pageUrl: String): Map<String, String> {
        return headers + mapOf(
            "Referer" to pageUrl,
            "Origin" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "*/*"
        )
    }

    private val listingSelector = listOf(
        "article",
        ".post",
        ".item",
        ".movie",
        ".film",
        ".card",
        ".ml-item",
        ".result-item",
        ".owl-item",
        ".swiper-slide",
        "div[class*=movie]",
        "div[class*=film]",
        "a[href]"
    ).joinToString(", ")
}
