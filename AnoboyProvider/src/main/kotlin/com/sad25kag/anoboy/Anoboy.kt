package com.sad25kag.anoboy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anoboy : MainAPI() {
    override var mainUrl = "https://anoboy.be"
    override var name = "AnoBoy"
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
        "category/anime/ongoing/" to "Anime Ongoing",
        "anime-list/" to "Anime List",
        "category/donghua/" to "Donghua",
        "category/anime-movie/" to "Movie",
        "category/tokusatsu/" to "Tokusatsu",
        "category/live-action-movie/" to "Live Action",
        "category/studio-ghibli/" to "Studio Ghibli",
        "category/rekomended/" to "Rekomendasi",
        "category/action/" to "Action",
        "category/adventure/" to "Adventure",
        "category/comedy/" to "Comedy",
        "category/demons/" to "Demons",
        "category/drama/" to "Drama",
        "category/ecchi/" to "Ecchi",
        "category/fantasy/" to "Fantasy",
        "category/horror/" to "Horror",
        "category/martial-arts/" to "Martial Arts",
        "category/romance/" to "Romance",
        "category/school/" to "School",
        "category/shounen/" to "Shounen",
        "category/slice-of-life/" to "Slice of Life",
        "category/sports/" to "Sports",
        "category/supernatural/" to "Supernatural"
    )

    private data class CardData(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val episode: Int?
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val raw = data.trim()
        if (raw.isBlank()) return if (page <= 1) mainUrl else "$mainUrl/page/$page/"

        val firstPage = normalizeAnoboyUrl(raw)
        if (page <= 1) return firstPage

        val base = firstPage.substringBefore("?").trimEnd('/')
        val query = firstPage.substringAfter("?", "")
        return if (query.isBlank() || query == firstPage) {
            "$base/page/$page/"
        } else {
            "$base/page/$page/?$query"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val pageUrl = buildPageUrl(request.data, page)
        val document = app.get(pageUrl, headers = defaultHeaders()).document

        val items = collectCards(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        val hasNext = document.selectFirst(
            ".wp-pagenavi a.nextpostslink, a.next, a[rel=next], a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        suspend fun parseSearchPage(url: String): List<CardData> {
            val document = runCatching {
                app.get(url, headers = defaultHeaders()).document
            }.getOrNull() ?: return emptyList()

            return collectCards(document)
                .filter { card ->
                    queryWords.isEmpty() || queryWords.all { word -> card.title.lowercase().contains(word) }
                }
                .distinctBy { it.url }
        }

        val searchResults = parseSearchPage("$mainUrl/?s=$encodedQuery")
        if (searchResults.isNotEmpty()) return searchResults.map { it.toSearchResponse() }

        val slug = query
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        if (slug.isNotBlank()) {
            val directAnime = runCatching {
                val directUrl = "$mainUrl/anime/$slug/"
                val document = app.get(directUrl, headers = defaultHeaders()).document
                val title = document.selectFirst("h1.entry-title, h1, h2.entry-title, .pagetitle h1")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                if (title.isNotBlank()) {
                    CardData(
                        title = cleanTitle(title),
                        url = directUrl,
                        poster = document.selectFirst(".sisi.entry-content img, .deskripsi img, div.bigcontent img, article img")
                            ?.imageAttr()
                            ?.let { fixUrlNull(it) },
                        type = TvType.Anime,
                        episode = null
                    )
                } else {
                    null
                }
            }.getOrNull()

            if (directAnime != null) return listOf(directAnime.toSearchResponse())
        }

        return parseSearchPage("$mainUrl/anime-list/").map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val fixedUrl = normalizeAnoboyUrl(url)
        val document = app.get(fixedUrl, headers = defaultHeaders()).document

        val pageTitle = cleanTitle(
            document.selectFirst("h1.entry-title, h1, h2.entry-title, .pagetitle h1")
                ?.text()
                ?.trim()
                .orEmpty()
                .ifBlank {
                    document.title()
                        .substringBefore("–")
                        .substringBefore("- anoBoy")
                        .substringBefore("AnoBoy")
                        .trim()
                }
        ).ifBlank {
            throw ErrorLoadingException("Judul tidak ditemukan")
        }

        val poster = document.selectFirst(
            ".sisi.entry-content img, .deskripsi img, div.column-three-fourth > img, " +
                "div.column-content > img, div.bigcontent img, div.entry-content img, " +
                ".thumb img, .poster img, .info-content img, article img"
        )?.imageAttr()?.let { fixUrlNull(it) }

        val description = document.selectFirst(".contentdeks")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.select("div.entry-content p, .sisi.entry-content p")
                .joinToString("\n") { it.text() }
                .trim()

        val episodes = parseEpisodeList(document, fixedUrl)

        val tags = document.select(
            "a[href*='/category/'], .genres a, .genre-info a, .info-content a[href*='/category/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinct()

        val recommendations = collectRecommendations(document)
            .distinctBy { it.url }
            .map { it.toSearchResponse() }

        val type = when {
            fixedUrl.contains("/anime-movie/", true) || pageTitle.contains("movie", true) -> TvType.AnimeMovie
            pageTitle.contains("ova", true) || pageTitle.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(pageTitle, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(pageTitle, fixedUrl, type, encodeEpisodeData(fixedUrl, fixedUrl)) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
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
        val (embeddedReferer, requestData) = decodeEpisodeData(data)
        val referer = embeddedReferer ?: mainUrl
        val emittedKeys = linkedSetOf<String>()
        var emitted = false

        fun callbackOnce(link: ExtractorLink) {
            val key = "${link.source.lowercase()}|${canonicalLink(link.url)}"
            if (emittedKeys.add(key)) {
                emitted = true
                callback(link)
            }
        }

        suspend fun processCandidate(raw: String?, baseUrl: String = requestData) {
            val url = resolvePlayerUrl(raw, baseUrl) ?: return
            if (!isPlayerCandidate(url)) return

            if (isDirectMedia(url)) {
                emitDirect(url, referer, ::callbackOnce)
            } else if (url.contains("blogger.com/video.g", ignoreCase = true)) {
                emitBloggerVideo(url, baseUrl, ::callbackOnce)
            } else {
                try {
                    loadExtractor(url, referer, subtitleCallback, ::callbackOnce)
                } catch (_: Exception) {
                }
            }
        }

        if (requestData.startsWith("multi::")) {
            requestData.removePrefix("multi::")
                .split("||")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { processCandidate(it, referer) }
        } else {
            val page = runCatching {
                app.get(
                    requestData,
                    referer = referer,
                    headers = defaultHeaders(referer),
                    timeout = 20L
                ).document
            }.getOrNull()

            if (page != null) {
                collectPlayerCandidates(page)
                    .forEach { processCandidate(it, requestData) }
            }

            processCandidate(requestData, referer)
        }

        return emitted
    }

    private fun collectPlayerCandidates(document: Document): List<String> {
        val candidates = linkedSetOf<String>()

        document.select(
            "#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], iframe[data-lazy-src], " +
                "video[src], video[data-src], source[src], embed[src], object[data]"
        ).forEach { element ->
            candidates.add(element.iframeAttr().orEmpty())
            candidates.add(element.attr("src"))
            candidates.add(element.attr("data-src"))
            candidates.add(element.attr("data-litespeed-src"))
            candidates.add(element.attr("data-lazy-src"))
            candidates.add(element.attr("data"))
        }

        document.select(".mobius select.mirror option[value], select.mirror option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isBlank()) return@forEach

            val decoded = runCatching { base64Decode(value) }.getOrNull()
                ?: runCatching { String(android.util.Base64.decode(value, android.util.Base64.DEFAULT)) }.getOrNull()
                ?: return@forEach

            val decodedDoc = Jsoup.parse(decoded)
            decodedDoc.select("iframe[src], video[src], source[src], embed[src]").forEach { embedded ->
                candidates.add(embedded.iframeAttr().orEmpty())
                candidates.add(embedded.attr("src"))
            }
        }

        document.select(
            "#fplay [data-video], #fplay [data-src], #fplay [data-url], #fplay [data-iframe], " +
                ".player [data-video], .player [data-src], .player [data-url], .player [data-iframe], " +
                ".server [data-video], .server [data-src], .server [data-url], .server [data-iframe]"
        ).forEach { element ->
            candidates.add(element.attr("data-video"))
            candidates.add(element.attr("data-src"))
            candidates.add(element.attr("data-url"))
            candidates.add(element.attr("data-iframe"))
        }

        document.select(
            "a[href*='gofile.io'], a[href*='mp4upload.com'], a[href*='mir.cr'], " +
                "a[href*='ranoz.gg'], a[href*='playerwish.com'], a[href*='blogger.com/video.g'], " +
                "a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            candidates.add(element.attr("href"))
        }

        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun collectCards(document: Document): List<CardData> {
        val selectors = listOf(
            "article.bs",
            "div.bs",
            "div.listupd article",
            "div.listupd div.bs",
            "a[href]:has(div.amv)",
            "a[href]:has(div#amv)",
            ".venz ul li",
            ".latest a[href]",
            ".listupd a[href]",
            ".topten .serieslist li",
            ".result li"
        ).joinToString(", ")

        return document.select(selectors)
            .mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
            .distinctBy { it.url }
    }

    private fun collectRecommendations(document: Document): List<CardData> {
        return document.select(
            "a[href]:has(div.amv), a[href]:has(div#amv), div.listupd article.bs, article.bs, div.bs, .topten .serieslist li"
        ).mapNotNull { it.toCardData() }
            .filterNot { isNavigationTitle(it.title) }
    }

    private fun Element.toCardData(): CardData? {
        val href = when {
            tagName().equals("a", true) -> attr("href")
            else -> selectFirst("a[href]")?.attr("href").orEmpty()
        }.trim()

        val fixedHref = normalizeAnoboyUrl(href)
        if (!isContentUrl(fixedHref)) return null

        val title = cleanTitle(
            attr("title").trim().ifBlank {
                selectFirst("h3.ibox1, h3.ibox, h2, h3, .tt, .entry-title")?.text()?.trim().orEmpty()
            }.ifBlank {
                selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }.ifBlank {
                text().trim()
            }
        )

        if (title.length < 2 || isNavigationTitle(title)) return null

        val episode = parseEpisodeNumber(title) ?: parseEpisodeNumber(fixedHref)
        val type = when {
            fixedHref.contains("/anime-movie/", true) || title.contains("movie", true) -> TvType.AnimeMovie
            title.contains("ova", true) || title.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        val poster = selectFirst("img")?.imageAttr()?.let { fixUrlNull(it) }

        return CardData(
            title = title,
            url = fixedHref,
            poster = poster,
            type = type,
            episode = episode
        )
    }

    private fun CardData.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, url, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun parseEpisodeList(document: Document, referer: String): List<Episode> {
        val anchors = document.select(
            "div.singlelink ul.lcp_catlist li a, div.eplister ul li a, " +
                "div.bixbox.bxcl ul li a, .episodelist ul li a, ul li a[href*='episode']"
        ).filter {
            val href = normalizeAnoboyUrl(it.attr("href"))
            isContentUrl(href) && !href.equals(referer, true)
        }

        return anchors
            .mapNotNull { anchor ->
                val href = normalizeAnoboyUrl(anchor.attr("href"))
                val title = cleanTitle(anchor.text().trim().ifBlank {
                    href.trimEnd('/').substringAfterLast('/').replace("-", " ")
                })
                val episode = parseEpisodeNumber(title) ?: parseEpisodeNumber(href)
                if (episode == null && title.length < 2) return@mapNotNull null

                newEpisode(encodeEpisodeData(referer, href)) {
                    name = title.ifBlank { "Episode ${episode ?: 1}" }
                    this.episode = episode
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private suspend fun emitBloggerVideo(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = extractBloggerDirectVideos(url, referer)
        if (videos.isEmpty()) return false

        videos.forEach { videoUrl ->
            callback(
                newExtractorLink(
                    source = "Blogger",
                    name = "Blogger",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.blogger.com/"
                    this.quality = qualityFromBloggerUrl(videoUrl)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "https://www.blogger.com/",
                        "Accept" to "*/*"
                    )
                }
            )
        }

        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String): List<String> {
        val token = Regex("""[?&]token=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val page = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return emptyList()

        val html = page.text
        val cookies = page.cookies
        val fSid = Regex("""FdrFJe":"(-?\d+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return extractGoogleVideoUrls(html)
        val bl = Regex("""cfb2h":"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return extractGoogleVideoUrls(html)
        val hl = Regex("""lang="([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "id"

        val rpcId = "WcwnYd"
        val reqId = (System.currentTimeMillis() % 90000L + 10000L).toString()
        val payload = """[[["$rpcId","[\"$token\",null,0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = runCatching {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = url,
                cookies = cookies,
                headers = mapOf(
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1"
                )
            ).text
        }.getOrNull() ?: return emptyList()

        return extractGoogleVideoUrls(response)
    }

    private fun extractGoogleVideoUrls(raw: String): List<String> {
        val decoded = decodeBloggerEscapes(raw)
        return Regex("""https://[^\s"'\\]+""")
            .findAll(decoded)
            .map { decodeBloggerEscapes(it.value) }
            .filter { it.contains("googlevideo.com/videoplayback", ignoreCase = true) }
            .distinct()
            .toList()
    }

    private fun decodeBloggerEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }

        return output
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
    }

    private fun qualityFromBloggerUrl(url: String): Int {
        val itag = Regex("""[?&]itag=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return when (itag) {
            37, 96, 137, 248, 299 -> Qualities.P1080.value
            22, 59, 136, 247, 298 -> Qualities.P720.value
            135 -> Qualities.P480.value
            18, 134, 244 -> Qualities.P360.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun emitDirect(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isDirectMedia(link) || isBadUrl(link)) return false

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isM3u8Media(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                    ?: when {
                        link.contains("1080", true) -> Qualities.P1080.value
                        link.contains("720", true) -> Qualities.P720.value
                        link.contains("480", true) -> Qualities.P480.value
                        link.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )

        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }

    private fun normalizeAnoboyUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return mainUrl
        val fixed = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$mainUrl$trimmed"
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "$mainUrl/${trimmed.trimStart('/')}"
        }

        return fixed
            .replace("https://www1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("http://www1.anoboy.boo", mainUrl, ignoreCase = true)
            .replace("https://anoboy.be", mainUrl, ignoreCase = true)
            .replace("http://anoboy.be", mainUrl, ignoreCase = true)
            .replace("https://www.anoboy.be", mainUrl, ignoreCase = true)
            .replace("http://www.anoboy.be", mainUrl, ignoreCase = true)
            .replace("https://anoboy.watch", mainUrl, ignoreCase = true)
            .replace("http://anoboy.watch", mainUrl, ignoreCase = true)
    }

    private fun resolvePlayerUrl(raw: String?, base: String): String? {
        val clean = cleanCandidate(raw)
        if (!isValidCandidate(clean)) return null

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> URI(base).resolve(clean).toString()
                else -> URI(base).resolve(clean).toString()
            }
        }.getOrNull()
    }

    private fun cleanCandidate(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#038;", "&")
            .replace(" ", "%20")
    }

    private fun isValidCandidate(clean: String): Boolean {
        return clean.isNotBlank() &&
            clean != "#" &&
            !clean.equals("none", true) &&
            !clean.equals("null", true) &&
            !clean.startsWith("javascript", true) &&
            !clean.startsWith("about:", true) &&
            !clean.startsWith("data:", true) &&
            !clean.startsWith("blob:", true) &&
            !clean.startsWith("mailto:", true)
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = normalizeAnoboyUrl(url).lowercase()
        val path = runCatching { URI(lower).path.orEmpty() }.getOrDefault(lower)
        val slug = path.trimEnd('/').substringAfterLast('/')

        if (!lower.startsWith(mainUrl.lowercase())) return false
        if (
            path.contains("/category/") ||
            path.contains("/genre/") ||
            path.contains("/tag/") ||
            path.contains("/season/") ||
            path.contains("/studio/") ||
            path.contains("/anime-list") ||
            path.contains("/donghua-list") ||
            path.contains("/page/") ||
            lower.contains("?order=") ||
            lower.contains("?status=") ||
            lower.contains("?type=")
        ) return false

        return slug.contains("episode-") ||
            slug.contains("-subtitle-indonesia") ||
            Regex("/20\\d{2}/\\d{2}/[a-z0-9-]+/?$").containsMatchIn(path)
    }

    private fun isPlayerCandidate(url: String): Boolean {
        val lower = url.lowercase()

        if (isBadUrl(url)) return false
        if (isDirectMedia(url)) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        return lower.contains("blogger.com/video.g") ||
            lower.contains("playerwish.com") ||
            lower.contains("gofile.io") ||
            lower.contains("mp4upload.com") ||
            lower.contains("mir.cr") ||
            lower.contains("ranoz.gg")
    }

    private fun isBadUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("x.com/") ||
            lower.contains("telegram") ||
            lower.contains("whatsapp") ||
            lower.contains("mailto:") ||
            lower.contains("/genres/") ||
            lower.contains("/genre/") ||
            lower.contains("/category/") ||
            lower.contains("/tag/") ||
            lower.contains("/season/") ||
            lower.contains("/studio/") ||
            lower.contains("/anime/?") ||
            lower.contains("?order=") ||
            lower.contains("?status=") ||
            lower.contains("?type=") ||
            lower.contains("adsbygoogle") ||
            lower.contains("googlesyndication") ||
            lower.contains("doubleclick") ||
            lower.contains("analytics") ||
            lower.contains("histats") ||
            lower.contains("safebrowsing") ||
            lower.contains("beacons.gcp.gvt2.com") ||
            lower.contains("dns.google") ||
            lower.contains("cloudflareinsights") ||
            lower.contains("google-analytics") ||
            lower.contains("googletagmanager") ||
            lower.contains("wp-json") ||
            lower.contains("/wp-content/themes/") ||
            lower.endsWith(".css") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".svg")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            path.endsWith(".ts") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("redirector.googlevideo.com/videoplayback") ||
            (lower.contains("gofile.io") && lower.contains("/download/"))
    }

    private fun isM3u8Media(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(url.substringBefore("?").lowercase())
        return path.endsWith(".m3u8") || url.lowercase().contains(".m3u8?")
    }

    private fun canonicalLink(link: String): String {
        return runCatching {
            val uri = URI(link)
            val host = uri.host.orEmpty().removePrefix("www.").lowercase()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            "$host$path"
        }.getOrDefault(link.substringBefore("?").trimEnd('/').lowercase())
    }

    private fun parseEpisodeNumber(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(?i)\b(?:episode|eps|ep)\s*[-:]?\s*(\d+)\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?i)(?:episode|eps|ep)-(\d+)""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\[(?:streaming|download)\]"""), "")
            .replace(Regex("""(?i)\b(?:streaming|download|subtitle indonesia|sub indo|nonton)\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '|')
    }

    private fun isNavigationTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower in setOf(
            "home",
            "az list",
            "anime list",
            "genre all",
            "season all",
            "studio all",
            "status all",
            "type all",
            "order by all",
            "search",
            "download",
            "prev",
            "next",
            "all episodes"
        )
    }

    private fun encodeEpisodeData(referer: String?, payload: String): String {
        if (referer.isNullOrBlank()) return payload
        return "anoboyref::$referer:::$payload"
    }

    private fun decodeEpisodeData(data: String): Pair<String?, String> {
        val prefix = "anoboyref::"
        if (!data.startsWith(prefix)) return null to normalizeAnoboyUrl(data)

        val parts = data.removePrefix(prefix).split(":::", limit = 2)
        return if (parts.size == 2) {
            normalizeAnoboyUrl(parts[0]) to parts[1]
        } else {
            null to data
        }
    }

    private fun defaultHeaders(referer: String = mainUrl): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
    }

    private fun Element.imageAttr(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("abs:src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
    }

    private fun Element?.iframeAttr(): String? {
        return this?.attr("data-litespeed-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }
}
