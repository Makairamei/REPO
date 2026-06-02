package com.melongmovie

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
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
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Melongmovie : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://tv12.melongmovies.com"
    override var name = "Melongmovie"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "latest-movies/page/%d/" to "Latest Movies",
        "latest-episodes/page/%d/" to "Latest Episodes",
        "popular/page/%d/" to "Popular",
        "series-list/page/%d/" to "Series List",

        "genre/action/page/%d/" to "Action",
        "genre/drama/page/%d/" to "Drama",
        "genre/thriller/page/%d/" to "Thriller",
        "genre/adventure/page/%d/" to "Adventure",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/sci-fi/page/%d/" to "Sci-Fi",
        "genre/horror/page/%d/" to "Horror",
        "genre/fantasy/page/%d/" to "Fantasy",
        "genre/crime/page/%d/" to "Crime",
        "genre/romance/page/%d/" to "Romance",
        "genre/animation/page/%d/" to "Animation",
        "genre/family/page/%d/" to "Family",
        "genre/war/page/%d/" to "War",
        "genre/mystery/page/%d/" to "Mystery",
        "genre/military/page/%d/" to "Military",

        "quality/bluray/page/%d/" to "Bluray",
        "quality/webdl/page/%d/" to "WebDL",
        "quality/hdrip/page/%d/" to "HDRip",
        "quality/webrip/page/%d/" to "WEBRip",
        "quality/dvdrip/page/%d/" to "DVDRip",
        "quality/hc-hdrip/page/%d/" to "HC-HDRip",

        "country/usa/page/%d/" to "USA",
        "country/uk/page/%d/" to "UK",
        "country/japan/page/%d/" to "Japan",
        "country/china/page/%d/" to "China",
        "country/canada/page/%d/" to "Canada",
        "country/south-korea/page/%d/" to "South Korea",
        "country/france/page/%d/" to "France",
        "country/germany/page/%d/" to "Germany",
        "country/india/page/%d/" to "India",
        "country/hong-kong/page/%d/" to "Hong Kong",
        "country/thailand/page/%d/" to "Thailand",
        "country/new-zealand/page/%d/" to "New Zealand"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = "$mainUrl/${request.data.format(page.coerceAtLeast(1)).trimStart('/')}"
        val document = app.get(url, headers = headers, timeout = 25L).document

        val items = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/advanced-search/?keyword=$encoded"
        )

        for (url in attempts) {
            val document = runCatching {
                app.get(url, headers = headers, timeout = 25L).document
            }.getOrNull() ?: continue

            val results = parseCards(document).distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = headers, timeout = 25L).document

        val title = listOf(
            document.selectFirst("h1.entry-title, h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: name

        val poster = getPoster(document)

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".wp-content p, " +
                ".sinopsis p, " +
                ".sinopsis, " +
                ".description, " +
                ".desc"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 20 }

        val pageText = document.text()
        val type = guessType(url, pageText, title)

        val tags = document.select("a[href*='/genre/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select(
            "a[href*='/stars/'], " +
                "a[href*='/star/'], " +
                "a[href*='/cast/'], " +
                "li:contains(Stars:) a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val rating = Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\s*/\s*\d+""")
            .find(pageText)
            ?.groupValues
            ?.getOrNull(1)
            ?: document.selectFirst(".rating, .imdb, [itemprop=ratingValue]")?.text()

        val year = extractYear(pageText)
        val duration = parseDuration(pageText)

        val trailer = document.selectFirst(
            "a[href*='youtube.com'], " +
                "a[href*='youtu.be'], " +
                "iframe[src*='youtube.com'], " +
                "iframe[src*='youtu.be']"
        )?.let {
            it.attr("href")
                .ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }

        val recommendations = document.select(
            ".related article, " +
                ".related-post article, " +
                ".los article.box, " +
                "article.box, " +
                "article:has(a):has(img)"
        ).mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return if (type == TvType.TvSeries || type == TvType.AsianDrama) {
            val episodes = parseEpisodes(document, url, poster, plot)

            newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodes.ifEmpty {
                    listOf(
                        newEpisode(url) {
                            name = title
                            episode = 1
                            posterUrl = poster
                            description = plot
                        }
                    )
                }
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addScore(rating)
                addTrailer(trailer)
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
        val pageUrl = normalizeUrl(data, mainUrl)

        val response = app.get(
            pageUrl,
            headers = headers,
            referer = mainUrl,
            timeout = 25L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectStaticPlayers(document, pageUrl, directLinks, embedLinks)
        collectDooplayAjax(document, pageUrl, directLinks, embedLinks)
        collectMuviproAjax(document, pageUrl, directLinks, embedLinks)

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        var found = false

        directLinks
            .filterNot { isBadPlayableUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { hostPriority(it) })
            .forEach { link ->
                emitDirectLink(link, pageUrl, callback)
                found = true
            }

        if (found) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        prioritizeEmbeds(embedLinks)
            .take(12)
            .forEach { embed ->
                val success = runCatching {
                    loadExtractor(
                        embed,
                        pageUrl,
                        subtitleCallback,
                        callback
                    )
                }.getOrDefault(false)

                if (success) LicenseClient.trackActivity(name, "PLAY", data)
        return true

                resolveNestedLinks(embed, pageUrl).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")

                    when {
                        isBadPlayableUrl(fixed) -> Unit

                        isHlsLike(fixed) ||
                            fixed.contains(".mp4", true) ||
                            fixed.contains(".webm", true) -> {
                            emitDirectLink(fixed, embed, callback)
                            LicenseClient.trackActivity(name, "PLAY", data)
        return true
                        }

                        fixed.startsWith("http", true) -> {
                            val nestedSuccess = runCatching {
                                loadExtractor(
                                    fixed,
                                    embed,
                                    subtitleCallback,
                                    callback
                                )
                            }.getOrDefault(false)

                            if (nestedSuccess) LicenseClient.trackActivity(name, "PLAY", data)
        return true
                        }
                    }
                }
            }

        return false
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            ".los article.box, " +
                "div.los article, " +
                "article.box, " +
                "article:has(a):has(img), " +
                "div.item:has(a):has(img), " +
                ".items article:has(a):has(img), " +
                ".content article:has(a):has(img), " +
                ".movie-item:has(a):has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
                element.toSearchResult()?.let { item ->
                    results[item.url] = item
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("h2 a[href], h3 a[href], .title a[href], .poster-title a[href], a[href]:has(img), a[href]")
                ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isNavigationUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val title = listOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".poster-title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("View More", true) &&
                !it.equals("Trailer", true) &&
                !it.equals("Home", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())

        val type = guessType(href, text(), title)

        return if (type == TvType.TvSeries || type == TvType.AsianDrama) {
            newTvSeriesSearchResponse(
                title,
                href,
                type
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
                score = parseScore(text())
            }
        } else {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
                score = parseScore(text())
            }
        }
    }

    private fun parseEpisodes(
        document: Document,
        currentUrl: String,
        poster: String?,
        plot: String?
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], " +
                "a[href*='ep-'], " +
                "a[href*='/season-'], " +
                ".episode-list a[href], " +
                ".eplister a[href], " +
                ".episodelist a[href], " +
                ".les-content a[href], " +
                ".season a[href], " +
                ".series a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed
            if (!href.startsWith(mainUrl) || isNavigationUrl(href)) return@forEachIndexed

            val text = element.text().trim()
            val episodeNumber = extractEpisodeNumber("$text $href") ?: index + 1
            val seasonNumber = extractSeasonNumber("$text $href") ?: 1

            episodes[href] = newEpisode(href) {
                name = text.ifBlank { "Episode $episodeNumber" }.cleanTitle()
                season = seasonNumber
                episode = episodeNumber
                posterUrl = poster
                description = plot
            }
        }

        return episodes.values
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
    }

    private suspend fun collectDooplayAjax(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val options = document.select(
            "#playeroptionsul li[data-post][data-nume][data-type], " +
                ".dooplay_player_option[data-post][data-nume][data-type], " +
                ".player-option[data-post][data-nume][data-type], " +
                "li[data-post][data-nume][data-type], " +
                "div[data-post][data-nume][data-type]"
        )

        if (options.isEmpty()) return

        options.forEach { option ->
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach
            if (nume.contains("trailer", true) || option.text().contains("trailer", true)) return@forEach

            val ajaxText = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = pageUrl,
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    timeout = 18L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            parsePlayerPayload(ajaxText, pageUrl, directLinks, embedLinks)
        }
    }

    private suspend fun collectMuviproAjax(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val postId = document.selectFirst("#muvipro_player_content_id[data-id], div#muvipro_player_content_id")
            ?.attr("data-id")
            ?.trim()
            ?: return

        if (postId.isBlank()) return

        document.select("div.tab-content-ajax[id], .tab-content-ajax[id]").forEach { tab ->
            val tabId = tab.attr("id").trim()
            if (tabId.isBlank()) return@forEach

            val payload = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tabId,
                        "post_id" to postId
                    ),
                    referer = pageUrl,
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    timeout = 18L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            parsePlayerPayload(payload, pageUrl, directLinks, embedLinks)
        }
    }

    private fun collectStaticPlayers(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "iframe[data-lazy-src], " +
                "embed[src], " +
                "object[data], " +
                "video[src], " +
                "video[data-src], " +
                "video source[src], " +
                "source[src], " +
                "a[href], " +
                "[data-src], " +
                "[data-file], " +
                "[data-video], " +
                "[data-url], " +
                "[data-embed], " +
                "[data-iframe]"
        ).forEach { element ->
            val label = element.text().lowercase()

            if (
                label.contains("trailer") ||
                label.contains("facebook") ||
                label.contains("twitter") ||
                label.contains("whatsapp") ||
                label.contains("telegram")
            ) {
                return@forEach
            }

            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-lazy-src") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) {
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }
    }

    private fun parsePlayerPayload(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (text.isBlank()) return

        extractPlayableUrls(text).forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        val decoded = runCatching {
            URLDecoder.decode(text, "UTF-8")
        }.getOrDefault(text)

        if (decoded != text) {
            extractPlayableUrls(decoded).forEach { raw ->
                addCandidate(raw, baseUrl, directLinks, embedLinks)
            }
        }

        Jsoup.parse(text).select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], source[src], embed[src], object[data], " +
                "a[href], [data-src], [data-file], [data-video], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        if (isBadPlayableUrl(url)) return emptyList()

        val response = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 18L
            )
        }.getOrNull() ?: return emptyList()

        val results = linkedSetOf<String>()
        val text = response.text.cleanEscaped()

        collectStaticPlayers(response.document, url, results, results)
        extractPlayableUrls(text).forEach { results.add(normalizeUrl(it, url)) }

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach {
                results.add(normalizeUrl(it, url))
            }
        }

        return results
            .map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filterNot { isBadPlayableUrl(it) }
            .distinct()
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

        if (fixed.isBlank() || isBadPlayableUrl(fixed)) return

        when {
            isHlsLike(fixed) ||
                fixed.contains(".mp4", true) ||
                fixed.contains(".webm", true) -> directLinks.add(fixed)

            fixed.startsWith("http", true) &&
                isKnownPlayableHost(fixed) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("embed", true) -> embedLinks.add(fixed)

            fixed.startsWith("http", true) &&
                fixed.contains("player", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isBadPlayableUrl(link)) return

        if (isHlsLike(link)) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to getBaseUrl(referer),
                    "Accept" to "*/*"
                )
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to getBaseUrl(referer),
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
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:melongfilm|strp2p|4meplayer|dingtezuni|dintezuvio|hglink|earnvids|streamwish|wishfast|filemoon|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|melongfilm|strp2p|4meplayer|dingtezuni|dintezuvio|hglink|earnvids|streamwish|wishfast|filemoon|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    isKnownPlayableHost(it)
            }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isBadPlayableUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("melongfilm.strp2p") -> 0
            value.contains("4meplayer") -> 1
            value.contains("ukokoko") -> 1
            value.contains("dingtezuni") -> 2
            value.contains("dintezuvio") -> 2
            value.contains("earnvids") -> 2
            value.contains("hglink") -> 3
            value.contains("streamwish") || value.contains("wishfast") -> 4
            value.contains("filemoon") -> 5
            value.contains("vidhide") -> 6
            value.contains("vidguard") -> 7
            value.contains("voe") -> 8
            value.contains("mixdrop") -> 9
            value.contains("mp4upload") -> 10
            value.contains("dood") -> 11
            value.contains("streamtape") -> 12
            value.contains("embed") -> 30
            value.contains("player") -> 31
            else -> 50
        }
    }

    private fun isKnownPlayableHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "melongfilm.strp2p",
            "4meplayer",
            "ukokoko",
            "dingtezuni",
            "dintezuvio",
            "earnvids",
            "hglink",
            "streamwish",
            "wishfast",
            "filemoon",
            "dood",
            "streamtape",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "mp4upload"
        ).any { value.contains(it) }
    }

    private fun isBadPlayableUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("whatsapp") ||
            value.contains("telegram") ||
            value.contains("mailto:") ||
            value.contains("trailer") ||
            value.contains("ads") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("popads") ||
            value.contains("tracking") ||
            value.contains("analytics") ||
            value.contains(".jpg") ||
            value.contains(".jpeg") ||
            value.contains(".png") ||
            value.contains(".webp") ||
            value.contains(".gif") ||
            value.contains(".css") ||
            value.contains(".js")
    }

    private fun isNavigationUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl, "").trim('/').lowercase()

        if (path.isBlank()) LicenseClient.trackActivity(name, "PLAY")
        return true

        val blocked = listOf(
            "genre/",
            "quality/",
            "country/",
            "stars/",
            "director/",
            "tag/",
            "page/",
            "movie-lists",
            "series-list",
            "popular",
            "advanced-search",
            "cara-download",
            "privacy",
            "dmca",
            "contact",
            "wp-admin",
            "wp-content",
            "wp-json",
            "feed"
        )

        return blocked.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".nav-links a:contains(Next), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    "img.wp-post-image, " +
                    ".poster img, " +
                    ".thumb img, " +
                    "article img, " +
                    "img"
            )?.let {
                when {
                    it.hasAttr("content") -> it.attr("content")
                    else -> it.getImageAttr()
                }
            }
        )
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull { it.isNotBlank() }
        }

        return fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun guessType(
        url: String,
        body: String,
        title: String
    ): TvType {
        val text = "$url $body $title"

        return when {
            text.contains("season", true) ||
                text.contains("episode", true) ||
                Regex("""\bS\d+\s*EP\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                text.contains(" TV ", true) ||
                text.contains("/series/", true) -> TvType.TvSeries

            text.contains("korea", true) ||
                text.contains("china", true) ||
                text.contains("thailand", true) -> TvType.AsianDrama

            else -> TvType.Movie
        }
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text.orEmpty())
            ?.value
            ?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\bEP\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractSeasonNumber(text: String): Int? {
        return Regex("""(?:season|s)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseDuration(text: String): Int? {
        val h = Regex("""(\d+)\s*(?:h|hr|hour|jam)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        val m = Regex("""(\d+)\s*(?:m|min|menit)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        val total = h * 60 + m

        return total.takeIf { it > 0 }
    }

    private fun parseScore(text: String): Score? {
        val rating = Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\s*/\s*\d+""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

        return Score.from10(rating)
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
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
            clean.startsWith("/") -> getBaseUrl(baseUrl).trimEnd('/') + clean
            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true)
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Melongmovie.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
