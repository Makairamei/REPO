package com.reynime

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
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
import java.util.Base64

class ReynimeProvider : MainAPI() {
    override var mainUrl = "https://reynime.my.id"
    override var name = "Reynime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "series" to "Daftar Donghua",
        "series?sort=popular" to "Populer",
        "series?sort=latest" to "Terbaru Ditambahkan",
        "series?status=ongoing" to "Ongoing",
        "series?status=completed" to "Completed",
        "series?type=movie" to "Movie",
        "series?type=ova" to "OVA",
        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/comedy" to "Comedy",
        "genre/drama" to "Drama",
        "genre/fantasy" to "Fantasy",
        "genre/martial-arts" to "Martial Arts",
        "genre/romance" to "Romance",
        "genre/mystery" to "Mystery",
        "genre/sci-fi" to "Sci-Fi",
        "genre/supernatural" to "Supernatural",
        "genre/thriller" to "Thriller",
        "genre/historical" to "Historical",
        "genre/isekai" to "Isekai",
        "genre/xianxia" to "Xianxia",
        "genre/xuanhuan" to "Xuanhuan",
        "genre/wuxia" to "Wuxia",
        "genre/donghua" to "Donghua"
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
        val candidates = buildPageCandidates(request.data, page)
        var hasNext = false

        for (url in candidates) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 25L)
            }.getOrNull() ?: continue

            val document = response.document
            val items = parseCards(document)
                .ifEmpty { parseCards(Jsoup.parse(response.text.cleanEscaped())) }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                hasNext = document.selectFirst(
                    "a[rel=next], a.next, .pagination a:contains(Next), a[href*='page=${page + 1}'], button:contains(Load More)"
                ) != null || response.text.contains("page\":${page + 1}")

                return newHomePageResponse(
                    request.name,
                    items,
                    hasNext = hasNext
                )
            }
        }

        return newHomePageResponse(request.name, emptyList(), hasNext = false)
    }

    private fun buildPageCandidates(data: String, page: Int): List<String> {
        val clean = data.trim('/').trim()
        val normalizedGenre = clean.removePrefix("genre/")

        fun withPage(url: String): String {
            if (page <= 1) return url
            return if (url.contains("?")) "$url&page=$page" else "${url.trimEnd('/')}/page/$page"
        }

        return when {
            clean.isBlank() -> listOf(
                withPage(mainUrl),
                "$mainUrl/?page=$page",
                "$mainUrl/series?page=$page",
                "$mainUrl/api/series?page=$page",
                "$mainUrl/api/episodes?page=$page"
            )

            clean.startsWith("genre/") -> listOf(
                "$mainUrl/$clean?page=$page",
                "$mainUrl/series?genre=$normalizedGenre&page=$page",
                "$mainUrl/series?genres=$normalizedGenre&page=$page",
                "$mainUrl/search?genre=$normalizedGenre&page=$page",
                "$mainUrl/api/series?genre=$normalizedGenre&page=$page",
                "$mainUrl/api/series?genres=$normalizedGenre&page=$page"
            )

            clean.contains("?") -> listOf(
                "$mainUrl/$clean&page=$page",
                "$mainUrl/api/$clean&page=$page"
            )

            else -> listOf(
                withPage("$mainUrl/$clean"),
                "$mainUrl/$clean?page=$page",
                "$mainUrl/api/$clean?page=$page"
            )
        }.distinct()
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a):has(img), " +
                ".card:has(a):has(img), " +
                ".series-card:has(a):has(img), " +
                ".anime-card:has(a):has(img), " +
                ".grid a[href*='/series/']:has(img), " +
                "a[href*='/series/']:has(img), " +
                "a[href*='/anime/']:has(img), " +
                "a[href*='/donghua/']:has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        parseNextDataCards(document.html()).forEach { results[it.url] = it }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a[href*='/series/'], a[href*='/anime/'], a[href*='/donghua/'], a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedCatalogUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(image?.getImageAttr())

        val title = listOf(
            selectFirst("h1, h2, h3, .title, .name, .font-semibold, .text-lg, .text-xl")?.text(),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        val type = getTypeFromText("$href ${text()} $title")

        return newAnimeSearchResponse(
            title,
            href,
            type
        ) {
            posterUrl = poster
        }
    }

    private fun parseNextDataCards(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()

        Regex(
            """\{[^{}]*?"(?:title|name)"\s*:\s*"((?:\\.|[^"])*)"[^{}]*?"(?:slug)"\s*:\s*"([^"]+)"[^{}]*?\}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(clean).forEach { match ->
            val title = match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .cleanTitle()
            val slug = match.groupValues[2].trim('/').cleanEscaped()
            if (title.isBlank() || slug.isBlank() || isBadTitle(title)) return@forEach

            val nearby = clean.substring(
                (match.range.first - 600).coerceAtLeast(0),
                (match.range.last + 900).coerceAtMost(clean.length)
            )

            val poster = Regex(
                """"(?:poster|cover|image|thumbnail|thumb)"\s*:\s*"([^"]+)""",
                RegexOption.IGNORE_CASE
            ).find(nearby)?.groupValues?.getOrNull(1)?.cleanEscaped()?.let {
                normalizeUrl(it, mainUrl)
            }

            val href = if (slug.startsWith("http", true)) slug else "$mainUrl/series/$slug"
            results[href] = newAnimeSearchResponse(title, href, getTypeFromText(title)) {
                posterUrl = poster
            }
        }

        Regex(
            """"(?:url|href)"\s*:\s*"([^"]*/series/[^"]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val href = normalizeUrl(match.groupValues[1].cleanEscaped(), mainUrl)
            val nearby = clean.substring(
                (match.range.first - 800).coerceAtLeast(0),
                (match.range.last + 900).coerceAtMost(clean.length)
            )
            val title = Regex(
                """"(?:title|name|alt)"\s*:\s*"((?:\\.|[^"])*)""",
                RegexOption.IGNORE_CASE
            ).find(nearby)?.groupValues?.getOrNull(1)?.cleanTitle()
                ?: href.substringAfterLast("/").replace("-", " ").cleanTitle()

            if (!isBadTitle(title)) {
                val poster = Regex(
                    """"(?:poster|cover|image|thumbnail|thumb)"\s*:\s*"([^"]+)""",
                    RegexOption.IGNORE_CASE
                ).find(nearby)?.groupValues?.getOrNull(1)?.cleanEscaped()?.let {
                    normalizeUrl(it, mainUrl)
                }
                results[href] = newAnimeSearchResponse(title, href, getTypeFromText(title)) {
                    posterUrl = poster
                }
            }
        }

        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/series?search=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/api/search?q=$encoded",
            "$mainUrl/api/series?q=$encoded",
            "$mainUrl/api/series?search=$encoded"
        )

        for (url in attempts) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 25L)
            }.getOrNull() ?: continue

            val fromHtml = parseCards(response.document)
            val fromRaw = parseNextDataCards(response.text.cleanEscaped())
            val results = (fromHtml + fromRaw).distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = headers, timeout = 25L)
        val document = response.document
        val html = response.text.cleanEscaped()

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1, .text-2xl, .text-3xl, .font-bold, .font-semibold")?.text(),
            Regex(""""title"\s*:\s*"((?:\\.|[^"])*)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: name

        val poster = listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[alt*='$title'], img.h-full, img.w-full, .poster img, .cover img, img")?.getImageAttr(),
            Regex(""""(?:poster|cover|image|thumbnail|thumb)"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() }?.let { normalizeUrl(it, url) }

        val description = listOf(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".synopsis, .description, .desc, article p, main p")?.text(),
            Regex(""""(?:synopsis|description|overview)"\s*:\s*"((?:\\.|[^"])*)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() && it.length > 20 }?.cleanTitle()

        val tags = document.select(
            "a[href*='genre'], a[href*='genres'], a[href*='tag'], .genre a, .genres a, .tags a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        val actors = document.select("a[href*='studio'], a[href*='producer'], .studio a, .producer a")
            .map { Actor(it.text().trim()) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }

        val trailer = document.selectFirst("iframe[src*='youtube'], a[href*='youtube'], a[href*='youtu.be']")
            ?.let { it.attr("src").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() }

        val episodes = parseEpisodes(document, html, url, poster)
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        name = title
                        episode = 1
                        posterUrl = poster
                        description?.let { this.description = it }
                    }
                )
            }

        val recommendations = parseCards(document)
            .filter { it.url != url }
            .distinctBy { it.url }

        return newAnimeLoadResponse(
            title,
            url,
            getTypeFromText("$title ${tags.joinToString(" ")} $url")
        ) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
            addActors(actors)
            addTrailer(trailer)
            this.recommendations = recommendations
        }
    }

    private fun parseEpisodes(
        document: Document,
        html: String,
        pageUrl: String,
        poster: String?
    ): List<Episode> {
        val results = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='/episode'], a[href*='/watch'], a[href*='/stream'], a[href*='/eps'], " +
                "a[href*='episode'], a[href*='ep-'], a[href*='/series/']:matchesOwn((?i)EP\\s*\\d+), " +
                "button[data-url], [data-url*='/episode'], [data-url*='/watch'], [data-href*='/episode']"
        ).forEachIndexed { index, element ->
            val raw = element.attr("href")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-href") }
                .trim()

            val href = fixUrlNull(raw) ?: return@forEachIndexed
            if (!href.startsWith(mainUrl)) return@forEachIndexed
            if (href == pageUrl || isBlockedCatalogUrl(href)) return@forEachIndexed

            val text = element.text().trim().ifBlank { element.attr("title") }
            val epNumber = extractEpisodeNumber(text, href) ?: (index + 1)

            results[href] = newEpisode(href) {
                name = text.cleanTitle().ifBlank { "Episode $epNumber" }
                episode = epNumber
                posterUrl = poster
            }
        }

        Regex(
            """"(?:url|href)"\s*:\s*"([^"]*/(?:episode|watch|stream|eps?)[^"]*)""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEachIndexed { index, match ->
            val href = normalizeUrl(match.groupValues[1].cleanEscaped(), pageUrl)
            if (!href.startsWith(mainUrl) || href == pageUrl || isBlockedCatalogUrl(href)) return@forEachIndexed

            val nearby = html.substring(
                (match.range.first - 600).coerceAtLeast(0),
                (match.range.last + 800).coerceAtMost(html.length)
            )
            val label = Regex(
                """"(?:title|name|episode|label)"\s*:\s*"((?:\\.|[^"])*)""",
                RegexOption.IGNORE_CASE
            ).find(nearby)?.groupValues?.getOrNull(1)?.cleanTitle().orEmpty()

            val epNumber = extractEpisodeNumber(label, href) ?: (index + 1)
            results[href] = newEpisode(href) {
                name = label.ifBlank { "Episode $epNumber" }
                episode = epNumber
                posterUrl = poster
            }
        }

        return results.values
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(data, mainUrl)
        val response = runCatching {
            app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 25L)
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text.cleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectCandidatesFromDocument(document, pageUrl, directLinks, embedLinks)
        extractPlayableUrls(html).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
        extractSubtitles(html, pageUrl, subtitleCallback)

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            val cleanUnpacked = unpacked.cleanEscaped()
            extractPlayableUrls(cleanUnpacked).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
            extractSubtitles(cleanUnpacked, pageUrl, subtitleCallback)
        }

        decodeBase64Payloads(html).forEach { decoded ->
            extractPlayableUrls(decoded).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
            Jsoup.parse(decoded).select("iframe[src], source[src], video[src], a[href]").forEach { element ->
                val raw = element.attr("src").ifBlank { element.attr("href") }
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
            extractSubtitles(decoded, pageUrl, subtitleCallback)
        }

        tryApiPlayback(pageUrl, html, directLinks, embedLinks, subtitleCallback)

        var found = false

        for (link in directLinks.distinct().sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { hostPriority(it) })) {
            val emitted = emitDirectLink(link, pageUrl, callback)
            if (emitted) found = true
        }

        if (found) return true

        for (embed in prioritizeEmbeds(embedLinks).take(12)) {
            val success = runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            if (success) return true

            val nested = resolveNestedLinks(embed, pageUrl)
            for (raw in nested) {
                val fixed = normalizeUrl(raw, embed).replace(".txt", ".m3u8")
                when {
                    isBadMediaUrl(fixed) || shouldSkipUrl(fixed) -> Unit
                    isDirectVideo(fixed) -> {
                        val emitted = emitDirectLink(fixed, embed, callback)
                        if (emitted) return true
                    }
                    fixed.startsWith("http", true) -> {
                        val nestedSuccess = runCatching {
                            loadExtractor(fixed, embed, subtitleCallback, callback)
                        }.getOrDefault(false)
                        if (nestedSuccess) return true
                    }
                }
            }
        }

        return false
    }

    private suspend fun tryApiPlayback(
        pageUrl: String,
        html: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val pathParts = runCatching { URI(pageUrl).path.trim('/').split('/').filter { it.isNotBlank() } }
            .getOrDefault(emptyList())

        val ids = linkedSetOf<String>()
        pathParts.forEach { part ->
            if (part.length in 1..80 && !part.equals("series", true) && !part.equals("episode", true) && !part.equals("watch", true)) {
                ids.add(part)
            }
        }

        Regex(""""(?:id|episodeId|videoId|postId|slug)"\s*:\s*"?([A-Za-z0-9_-]{1,80})"?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { ids.add(it) }

        val endpointTemplates = listOf(
            "$mainUrl/api/episode/%s",
            "$mainUrl/api/episodes/%s",
            "$mainUrl/api/watch/%s",
            "$mainUrl/api/stream/%s",
            "$mainUrl/api/video/%s",
            "$mainUrl/api/source/%s",
            "$mainUrl/api/player/%s",
            "$mainUrl/api/episode/%s/stream",
            "$mainUrl/api/watch/%s/stream"
        )

        ids.take(8).forEach { id ->
            endpointTemplates.forEach { template ->
                val endpoint = template.format(URLEncoder.encode(id, "UTF-8"))
                val text = runCatching {
                    app.get(
                        endpoint,
                        headers = headers + mapOf("Accept" to "application/json,text/plain,*/*", "X-Requested-With" to "XMLHttpRequest"),
                        referer = pageUrl,
                        timeout = 12L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                if (text.isBlank()) return@forEach

                extractPlayableUrls(text).forEach { addCandidate(it, endpoint, directLinks, embedLinks) }
                extractSubtitles(text, endpoint, subtitleCallback)

                val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
                if (decoded != text) {
                    extractPlayableUrls(decoded).forEach { addCandidate(it, endpoint, directLinks, embedLinks) }
                    extractSubtitles(decoded, endpoint, subtitleCallback)
                }
            }
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
                "a[href], [data-url], [data-src], [data-video], [data-file], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach

            val label = element.text().lowercase()
            if (label.contains("trailer") || label.contains("report")) return@forEach

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(url: String, referer: String): List<String> {
        if (shouldSkipUrl(url)) return emptyList()

        val response = runCatching {
            app.get(url, headers = headers, referer = referer, timeout = 15L)
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        val results = linkedSetOf<String>()

        response.document.select(
            "iframe[src], iframe[data-src], source[src], video[src], embed[src], object[data], a[href], " +
                "[data-url], [data-src], [data-file], [data-video], [data-embed]"
        ).forEach { element ->
            val raw = element.attr("data-video")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
            if (raw.isNotBlank()) results.add(normalizeUrl(raw, url))
        }

        results.addAll(extractPlayableUrls(text))

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            results.addAll(extractPlayableUrls(unpacked.cleanEscaped()))
        }

        return results
            .map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
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

        if (fixed.isBlank() || isBadMediaUrl(fixed) || shouldSkipUrl(fixed)) return

        when {
            isDirectVideo(fixed) -> directLinks.add(fixed)
            fixed.startsWith("http", true) && isLikelyEmbed(fixed) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isBadMediaUrl(link) || shouldSkipUrl(link)) return false

        return if (isHlsLike(link)) {
            val generated = runCatching {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                )
            }.getOrNull().orEmpty()

            generated.forEach(callback)
            generated.isNotEmpty()
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(link)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
            true
        }
    }

    private suspend fun extractSubtitles(
        text: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val clean = text.cleanEscaped()

        Regex(
            """"(?:label|lang|language)"\s*:\s*"([^"]+)"[^{}]{0,300}"(?:file|url|src|path)"\s*:\s*"([^"]+\.(?:vtt|srt|ass)[^"]*)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = normalizeSubtitleLabel(match.groupValues[1])
            val url = normalizeUrl(match.groupValues[2].cleanEscaped(), baseUrl)
            if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile(label, url))
        }

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val url = match.value.cleanEscaped()
            if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile("Subtitle", url))
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8" )}" }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.mkv|\.txt|embed|player|stream)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|video|videoUrl|video_url|stream|streamUrl|stream_url|hls|hlsUrl|hls_url|embed|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { isDirectVideo(it) || isLikelyEmbed(it) }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|watch|video|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|mediafire|mega|drive\.google|ok\.ru|dailymotion|rumble)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun decodeBase64Payloads(text: String): List<String> {
        val results = linkedSetOf<String>()

        Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(30)
            .forEach { token ->
                runCatching {
                    String(Base64.getDecoder().decode(token), Charsets.UTF_8)
                }.getOrNull()?.takeIf {
                    it.contains("http", true) || it.contains("iframe", true) || it.contains("m3u8", true)
                }?.let { results.add(it.cleanEscaped()) }
            }

        return results.toList()
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
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
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            value.contains("reynime.my.id") -> 0
            value.contains("filemoon") -> 1
            value.contains("streamwish") || value.contains("wishfast") -> 2
            value.contains("dood") -> 3
            value.contains("streamtape") -> 4
            value.contains("vidhide") -> 5
            value.contains("vidguard") -> 6
            value.contains("voe") -> 7
            value.contains("mixdrop") -> 8
            value.contains("mp4upload") -> 9
            value.contains("ok.ru") -> 10
            value.contains("dailymotion") -> 11
            value.contains("rumble") -> 12
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
    }

    private fun isDirectVideo(url: String): Boolean {
        return isHlsLike(url) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".mkv", true)
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains("application/x-mpegurl", true)
    }

    private fun isLikelyEmbed(url: String): Boolean {
        val value = url.lowercase()
        return value.startsWith("http") && (
            value.contains("embed") ||
                value.contains("player") ||
                value.contains("stream") ||
                value.contains("watch") ||
                value.contains("video") ||
                value.contains("filemoon") ||
                value.contains("streamwish") ||
                value.contains("wishfast") ||
                value.contains("dood") ||
                value.contains("streamtape") ||
                value.contains("vidhide") ||
                value.contains("vidguard") ||
                value.contains("voe") ||
                value.contains("mixdrop") ||
                value.contains("mp4upload") ||
                value.contains("ok.ru") ||
                value.contains("dailymotion") ||
                value.contains("rumble")
            )
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("javascript") ||
            value.startsWith("mailto:") ||
            value.startsWith("#") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("googletagmanager") ||
            value.contains("cloudflareinsights") ||
            value.contains("recaptcha") ||
            value.contains("/login") ||
            value.contains("/register") ||
            value.contains("/privacy") ||
            value.contains("/contact")
    }

    private fun isBadMediaUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adservice") ||
            value.contains("adsterra") ||
            value.contains("popads") ||
            value.contains("/ads/") ||
            value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("banner") ||
            value.contains("tracking") ||
            value.contains("analytics")
    }

    private fun isBlockedCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.trim('/').lowercase() }
            .getOrDefault(url.lowercase())
        return path.isBlank() ||
            path.startsWith("genre") ||
            path.startsWith("tag") ||
            path.startsWith("search") ||
            path.startsWith("login") ||
            path.startsWith("register") ||
            path.startsWith("privacy") ||
            path.startsWith("contact") ||
            path.startsWith("api")
    }

    private fun getTypeFromText(text: String): TvType {
        return when {
            text.contains("movie", true) || text.contains("film", true) -> TvType.AnimeMovie
            text.contains("ova", true) || text.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
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

    private fun normalizeSubtitleLabel(label: String): String {
        return when {
            label.contains("indonesia", true) || label.equals("id", true) || label.contains("bahasa", true) -> "Indonesian"
            label.isBlank() -> "Subtitle"
            else -> label
        }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull { it.isNotBlank() }
        }

        return fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun isBadTitle(title: String): Boolean {
        val value = title.lowercase().trim()
        return value.isBlank() ||
            value == "home" ||
            value == "login" ||
            value == "register" ||
            value == "search" ||
            value == "genre" ||
            value == "watch" ||
            value == "episode" ||
            value == "episodes" ||
            value.contains("tentang reynime")
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
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace(Regex("""\s+[-|]\s+Reynime\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
