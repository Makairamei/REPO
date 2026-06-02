package com.layarkaca

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv9.lk21official.cc"
    private val seriesUrl = "https://tv3.nontondrama.my"
    private val searchUrl = "https://gudangvape.com"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        // Kategori utama
        "$mainUrl/populer/page/" to "Most Popular Movies",
        "$mainUrl/rating/page/" to "IMDb Rated",
        "$mainUrl/most-commented/page/" to "Most Commented",
        "$seriesUrl/latest-series/page/" to "Latest Series",
        "$mainUrl/latest/page/" to "Latest Movies",

        // Genre movies LK21
        "$mainUrl/genre/action/page/" to "Action Movie",
        "$mainUrl/genre/adventure/page/" to "Adventure Movie",
        "$mainUrl/genre/animation/page/" to "Animation Movie",
        "$mainUrl/genre/biography/page/" to "Biography Movie",
        "$mainUrl/genre/comedy/page/" to "Comedy Movie",
        "$mainUrl/genre/crime/page/" to "Crime Movie",
        "$mainUrl/genre/documentary/page/" to "Documentary Movie",
        "$mainUrl/genre/drama/page/" to "Drama Movie",
        "$mainUrl/genre/family/page/" to "Family Movie",
        "$mainUrl/genre/fantasy/page/" to "Fantasy Movie",
        "$mainUrl/genre/history/page/" to "History Movie",
        "$mainUrl/genre/horror/page/" to "Horror Movie",
        "$mainUrl/genre/mystery/page/" to "Mystery Movie",
        "$mainUrl/genre/romance/page/" to "Romance Movie",
        "$mainUrl/genre/sci-fi/page/" to "Sci-Fi Movie",
        "$mainUrl/genre/sport/page/" to "Sport Movie",
        "$mainUrl/genre/thriller/page/" to "Thriller Movie",
        "$mainUrl/genre/war/page/" to "War Movie",
        "$mainUrl/genre/western/page/" to "Western Movie",

        // Negara movies LK21
        "$mainUrl/country/australia/page/" to "Australia",
        "$mainUrl/country/canada/page/" to "Canada",
        "$mainUrl/country/china/page/" to "China",
        "$mainUrl/country/france/page/" to "France",
        "$mainUrl/country/germany/page/" to "Germany",
        "$mainUrl/country/hong-kong/page/" to "Hong Kong",
        "$mainUrl/country/india/page/" to "India",
        "$mainUrl/country/indonesia/page/" to "Indonesia",
        "$mainUrl/country/italy/page/" to "Italy",
        "$mainUrl/country/japan/page/" to "Japan",
        "$mainUrl/country/malaysia/page/" to "Malaysia",
        "$mainUrl/country/philippines/page/" to "Philippines",
        "$mainUrl/country/russia/page/" to "Russia",
        "$mainUrl/country/south-korea/page/" to "South Korea",
        "$mainUrl/country/spain/page/" to "Spain",
        "$mainUrl/country/taiwan/page/" to "Taiwan",
        "$mainUrl/country/thailand/page/" to "Thailand",

        // Genre series NontonDrama
        "$seriesUrl/genre/action/page/" to "Action Series",
        "$seriesUrl/genre/animation/page/" to "Animation Series",
        "$seriesUrl/genre/comedy/page/" to "Comedy Series",
        "$seriesUrl/genre/crime/page/" to "Crime Series",
        "$seriesUrl/genre/documentary/page/" to "Documentary Series",
        "$seriesUrl/genre/drama/page/" to "Drama Series",
        "$seriesUrl/genre/family/page/" to "Family Series",
        "$seriesUrl/genre/mystery/page/" to "Mystery Series",
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
        val pageUrl = buildPagedUrl(request.data, page)

        val document = app.get(
            pageUrl,
            headers = headers,
            timeout = 30L
        ).document

        val items = parseCards(document, pageUrl)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
        )
    }

    private fun buildPagedUrl(
        base: String,
        page: Int
    ): String {
        val clean = base.trimEnd('/')

        return when {
            page <= 1 && base.endsWith("/page/") -> clean.removeSuffix("/page")
            page <= 1 -> base
            base.endsWith("/page/") -> "$base$page"
            base.contains("?") -> "$base&page=$page"
            else -> "${clean}/page/$page"
        }
    }

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}'], " +
                "a[href*='page=${page + 1}']"
        ) != null
    }

    private fun parseCards(
        document: Document,
        baseUrl: String
    ): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "#archive-content article, " +
                "div.items.normal article, " +
                "article:has(a):has(img), " +
                "li.slider article, " +
                ".slider article, " +
                ".item:has(a):has(img), " +
                ".card:has(a):has(img), " +
                "div.card.border-0, " +
                ".result-item:has(a):has(img)"
        ).forEach { element ->
            element.toSearchResult(baseUrl)?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
                element.toSearchResult(baseUrl)?.let { item ->
                    results[item.url] = item
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "h3.poster-title a[href], " +
                    "h3 a[href], " +
                    ".poster-title a[href], " +
                    ".title a[href], " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = normalizeUrl(anchor.attr("href"), baseUrl)
            .takeIf { it.startsWith("http", true) }
            ?: return null

        if (isBlockedCatalogUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(image?.getImageAttr())

        val title = listOf(
            selectFirst("h3.poster-title")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".poster-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Series", true) &&
                !it.equals("Movies", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val type = guessType(href, text())

        return if (type == TvType.TvSeries || type == TvType.AsianDrama) {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun isBlockedCatalogUrl(url: String): Boolean {
        val value = url.lowercase()
        val path = runCatching { URI(url).path.trim('/') }.getOrDefault(value)

        if (path.isBlank()) LicenseClient.trackActivity(name, "PLAY")
        return true

        return listOf(
            "genre/",
            "country/",
            "year/",
            "tag/",
            "actor/",
            "director/",
            "author/",
            "login",
            "register",
            "privacy",
            "dmca",
            "contact",
            "wp-admin",
            "wp-content",
            "wp-json"
        ).any { path.startsWith(it) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        return search(query, 1).items
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")

        val attempts = listOf(
            "$searchUrl/search.php?s=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded",
            "$seriesUrl/?s=$encoded"
        )

        for (url in attempts) {
            val text = runCatching {
                app.get(
                    url,
                    headers = headers,
                    referer = mainUrl,
                    timeout = 30L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (text.isBlank()) continue

            if (text.trimStart().startsWith("{")) {
                val apiResults = parseSearchJson(text)
                if (apiResults.isNotEmpty()) {
                    return newSearchResponseList(apiResults, hasNext = false)
                }
            }

            val document = Jsoup.parse(text, url)
            val htmlResults = parseCards(document, url).distinctBy { it.url }

            if (htmlResults.isNotEmpty()) {
                return newSearchResponseList(
                    htmlResults,
                    hasNext = hasNextPage(document, page)
                )
            }
        }

        return newSearchResponseList(emptyList(), hasNext = false)
    }

    private fun parseSearchJson(text: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        runCatching {
            val root = JSONObject(text)
            val data = root.optJSONArray("data") ?: JSONArray()

            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val title = item.optString("title").cleanTitle()
                val slug = item.optString("slug")
                val type = item.optString("type")

                if (title.isBlank() || slug.isBlank()) continue

                val poster = item.optString("poster")
                    .takeIf { it.isNotBlank() }
                    ?.let { "https://poster.lk21.party/wp-content/uploads/$it" }

                val link = when {
                    slug.startsWith("http", true) -> slug
                    type.equals("series", true) -> "$seriesUrl/${slug.trimStart('/')}"
                    else -> "$mainUrl/${slug.trimStart('/')}"
                }

                if (type.equals("series", true)) {
                    results.add(
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    )
                } else {
                    results.add(
                        newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        ).document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1.entry-title")?.text(),
            document.selectFirst("div.data h1")?.text(),
            document.selectFirst("h1, .movie-info h1")?.text(),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: name

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    ".poster img, " +
                    ".thumb img, " +
                    "img.wp-post-image, " +
                    "article img, " +
                    "img"
            )?.let { element ->
                if (element.hasAttr("content")) element.attr("content") else element.getImageAttr()
            }
        )

        val description = listOf(
            document.selectFirst(".synopsis")?.text(),
            document.selectFirst(".wp-content p")?.text(),
            document.selectFirst(".entry-content p")?.text(),
            document.selectFirst(".meta-info")?.text(),
            document.selectFirst("meta[name=description]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/country/'], " +
                "a[href*='/category/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val recommendations = parseCards(document, url)
            .filter { it.url != url }
            .distinctBy { it.url }

        val episodeList = parseEpisodes(document, url, poster)
        val isSeries = episodeList.size > 1 ||
            document.select("#season-data, script#season-data, .episode, a[href*='episode']").isNotEmpty() ||
            url.contains(seriesUrl.substringAfter("://"), true)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList.ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun parseEpisodes(
        document: Document,
        url: String,
        poster: String?
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.selectFirst("script#season-data, #season-data")?.data()?.takeIf { it.isNotBlank() }?.let { json ->
            runCatching {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val arr = root.optJSONArray(seasonKey) ?: return@forEach
                    val seasonNumber = seasonKey.filter { it.isDigit() }.toIntOrNull()

                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val rawSlug = ep.optString("slug")
                        if (rawSlug.isBlank()) continue

                        val link = normalizeUrl(rawSlug, url)
                        val epNum = ep.optInt("episode_no", i + 1)
                        val epTitle = ep.optString("title").ifBlank { "Episode $epNum" }

                        episodes[link] = newEpisode(link) {
                            this.name = epTitle.cleanTitle()
                            this.season = seasonNumber
                            this.episode = epNum
                            this.posterUrl = poster
                            ep.optString("release_date").takeIf { it.isNotBlank() }?.let { addDate(it) }
                        }
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            document.select(
                "a[href*='episode'], " +
                    "a[href*='/eps/'], " +
                    ".episode a[href], " +
                    ".eps a[href], " +
                    ".eplister a[href], " +
                    ".season a[href]"
            ).forEachIndexed { index, element ->
                val href = normalizeUrl(element.attr("href"), url)
                if (!href.startsWith("http", true) || isBlockedCatalogUrl(href)) return@forEachIndexed

                val text = element.text().trim()
                val epNum = extractEpisodeNumber(text, href) ?: index + 1
                val season = extractSeasonNumber(text, href)

                episodes[href] = newEpisode(href) {
                    this.name = text.ifBlank { "Episode $epNum" }.cleanTitle()
                    this.episode = epNum
                    this.season = season
                    this.posterUrl = poster
                }
            }
        }

        return episodes.values
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
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
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectDooplayEmbeds(
            document = document,
            pageUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        collectCandidatesFromDocument(
            document = document,
            baseUrl = pageUrl,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

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

        val decoded = runCatching {
            URLDecoder.decode(html, "UTF-8")
        }.getOrDefault(html)

        if (decoded != html) {
            extractPlayableUrls(decoded.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        var found = false

        directLinks
            .filterNot { isJunkLink(it) }
            .distinct()
            .sortedWith(compareBy<String> { if (it.isDirectHls()) 0 else 1 }.thenBy { it.length })
            .forEach { link ->
                emitDirectVideo(
                    source = name,
                    streamUrl = link,
                    referer = pageUrl,
                    callback = callback
                )
                found = true
            }

        if (found) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        embedLinks
            .filterNot { isJunkLink(it) }
            .distinct()
            .take(12)
            .amap { embed ->
                val success = runCatching {
                    loadExtractor(
                        embed,
                        pageUrl,
                        subtitleCallback,
                        callback
                    )
                }.getOrDefault(false)

                if (success) {
                    found = true
                    return@amap
                }

                val nested = resolveNestedLinks(embed, pageUrl)
                nested.forEach { nestedUrl ->
                    val fixed = normalizeUrl(nestedUrl, embed).replace(".txt", ".m3u8")

                    when {
                        isJunkLink(fixed) -> Unit
                        fixed.isDirectVideoUrl() -> {
                            emitDirectVideo(
                                source = name,
                                streamUrl = fixed,
                                referer = embed,
                                callback = callback
                            )
                            found = true
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

                            if (nestedSuccess) found = true
                        }
                    }
                }
            }

        return found
    }

    private suspend fun collectDooplayEmbeds(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val base = getBaseUrl(pageUrl)
        val options = document.select(
            "#playeroptionsul li[data-post][data-nume][data-type], " +
                ".dooplay_player_option[data-post][data-nume][data-type], " +
                ".player-option[data-post][data-nume][data-type], " +
                "li[data-post][data-nume][data-type], " +
                "div[data-post][data-nume][data-type]"
        )

        options.forEach { option ->
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach
            if (nume.contains("trailer", true) || option.text().contains("trailer", true)) return@forEach

            val ajaxText = runCatching {
                app.post(
                    url = "$base/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = pageUrl,
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to base
                    ),
                    timeout = 20L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            parsePlayerPayload(ajaxText, pageUrl, directLinks, embedLinks)
        }
    }

    private fun parsePlayerPayload(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (text.isBlank()) return

        runCatching {
            val json = JSONObject(text)
            listOf(
                json.optString("embed_url"),
                json.optString("url"),
                json.optString("src"),
                json.optString("link")
            ).forEach { value ->
                if (value.isNotBlank()) {
                    val decoded = decodePossibleEmbed(value)
                    addCandidate(decoded, baseUrl, directLinks, embedLinks)

                    Jsoup.parse(decoded).select("iframe[src], iframe[data-src], source[src], video[src]").forEach { element ->
                        val raw = element.attr("data-src")
                            .ifBlank { element.attr("src") }
                            .trim()

                        addCandidate(raw, baseUrl, directLinks, embedLinks)
                    }
                }
            }
        }

        extractPlayableUrls(text).forEach { raw ->
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }

        Jsoup.parse(text).select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "source[src], video[src], embed[src], object[data], a[href], " +
                "[data-src], [data-video], [data-file], [data-url], [data-embed]"
        ).forEach { element ->
            val raw = element.attr("data-video")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private fun decodePossibleEmbed(value: String): String {
        val clean = value.cleanEscaped()

        return when {
            clean.contains("<iframe", true) -> clean
            clean.startsWith("http", true) -> clean
            else -> runCatching {
                URLDecoder.decode(clean, "UTF-8")
            }.getOrDefault(clean)
        }
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], video[data-src], video source[src], source[src], embed[src], object[data], " +
                "a[href], [data-src], [data-video], [data-file], [data-url], [data-embed], [data-iframe]"
        ).forEach { element ->
            val label = element.text().lowercase()
            val raw = element.attr("content")
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (
                raw.isBlank() ||
                raw.startsWith("#") ||
                raw.startsWith("javascript", true) ||
                label.contains("trailer")
            ) {
                return@forEach
            }

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        val response = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 20L
            )
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        val results = linkedSetOf<String>()

        collectCandidatesFromDocument(response.document, url, results, results)
        extractPlayableUrls(text).forEach { results.add(normalizeUrl(it, url)) }

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { results.add(normalizeUrl(it, url)) }
        }

        return results
            .filterNot { isJunkLink(it) }
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

        if (fixed.isBlank() || isJunkLink(fixed)) return

        when {
            fixed.isDirectVideoUrl() -> directLinks.add(fixed)
            fixed.startsWith("http", true) && isKnownEmbedHost(fixed) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("embed", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("player", true) -> embedLinks.add(fixed)
            fixed.startsWith("http", true) && fixed.contains("stream", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectVideo(
        source: String,
        streamUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = streamUrl.replace(".txt", ".m3u8")

        if (fixed.isDirectHls()) {
            generateM3u8(
                source = source,
                streamUrl = fixed,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to getBaseUrl(referer)
                )
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = source,
                    name = source,
                    url = fixed,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(fixed).takeIf {
                        it != Qualities.Unknown.value
                    } ?: qualityFromUrl(fixed)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to getBaseUrl(referer)
                    )
                }
            )
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isJunkLink(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isJunkLink(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt|jeniusplay|majorplay|emturbovid|hownetwork|f16|p2p|streamwish|filemoon|dood|streamtape|vidhide|voe|mixdrop)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isJunkLink(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|video_url|playUrl|play_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.isDirectVideoUrl() ||
                    isKnownEmbedHost(it) ||
                    it.contains("embed", true) ||
                    it.contains("player", true)
            }
            .filterNot { isJunkLink(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun isKnownEmbedHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "emturbovid",
            "hownetwork",
            "playeriframe",
            "p2p",
            "f16",
            "jeniusplay",
            "majorplay",
            "e2e.majorplay",
            "m3u8.majorplay",
            "streamwish",
            "filemoon",
            "dood",
            "streamtape",
            "vidhide",
            "voe",
            "mixdrop",
            "hglink"
        ).any { value.contains(it) }
    }

    private fun isJunkLink(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("mailto:") ||
            value.contains("trailer") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("googletagmanager") ||
            value.contains("cloudflareinsights") ||
            value.contains("recaptcha") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("/ads/") ||
            value.contains("banner") ||
            value.contains("tracking") ||
            value.contains("analytics")
    }

    private fun String.isDirectHls(): Boolean {
        return contains(".m3u8", true)
    }

    private fun String.isDirectVideoUrl(): Boolean {
        return contains(".m3u8", true) ||
            contains(".mp4", true) ||
            contains(".webm", true)
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
            clean.startsWith("/") -> "${getBaseUrl(baseUrl)}$clean"
            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault("${getBaseUrl(baseUrl)}/${clean.trimStart('/')}")
        }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private fun guessType(
        url: String,
        text: String
    ): TvType {
        val combined = "$url $text"

        return when {
            combined.contains(seriesUrl.substringAfter("://"), true) -> TvType.TvSeries
            combined.contains("series", true) ||
                combined.contains("episode", true) ||
                combined.contains("season", true) -> TvType.TvSeries
            combined.contains("drama", true) ||
                combined.contains("korea", true) ||
                combined.contains("china", true) ||
                combined.contains("thailand", true) -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractSeasonNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:season|s)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
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

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+LK21\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Layarkaca21\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
