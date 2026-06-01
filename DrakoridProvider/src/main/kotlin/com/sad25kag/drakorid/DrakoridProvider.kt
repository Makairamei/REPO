package com.sad25kag.drakorid

import com.excloud.BuildConfig
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class DrakoridProvider : MainAPI() {
    override var mainUrl = "https://drakorid.co"
    override var name = "Drakor.id"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val cookieHeader = BuildConfig.DRAKORID_COOKIE.trim().takeIf { it.isNotBlank() }

    // FIX #1: cookieMap is the single source of truth for cookies.
    // Previously, cookies were sent BOTH via the "Cookie" header AND via the cookies map,
    // causing duplicate cookie headers which can confuse servers.
    private val cookieMap: Map<String, String> by lazy {
        cookieHeader?.split(";")
            ?.mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = token.substring(0, idx).trim()
                val value = token.substring(idx + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            ?.toMap()
            .orEmpty()
    }

    private val baseHeaders = mapOf(
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    )

    override val mainPage = mainPageOf(
        "list" to "Terbaru",
        "ongoing" to "Ongoing",
        "kategori/drama-korea" to "Drama Korea",
        "kategori/drama-china" to "Drama China",
        "kategori/drama-thailand" to "Drama Thailand",
        "kategori/film-korea" to "Film Korea",
        "kategori/film-thailand" to "Film Thailand",
        "kategori/film-china" to "Film China",
        "kategori/variety-show" to "Variety Show",

        "kategori/romance" to "Romance",
        "kategori/comedy" to "Comedy",
        "kategori/action" to "Action",
        "kategori/fantasy" to "Fantasy",
        "kategori/horror" to "Horror",
        "kategori/thriller" to "Thriller",
        "kategori/adventure" to "Adventure",
        "kategori/school" to "School",
        "kategori/melodrama" to "Melodrama",
        "kategori/web-drama" to "Web Drama",
        "kategori/time-travel" to "Time Travel",
        "kategori/historical" to "Historical",
        "kategori/sport" to "Sport",
        "kategori/politic" to "Politic",
        "kategori/running-man" to "Running Man",
        "kategori/mystery" to "Mystery",
        "kategori/crime" to "Crime",
        "kategori/legal" to "Legal",
        "kategori/documentary" to "Documentary",
        "kategori/family" to "Family"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageNo = if (page <= 0) 1 else page
        val url = buildPageUrl(request.data, pageNo)
        val document = app.get(
            url,
            headers = baseHeaders,
            cookies = requestCookies(),
            referer = mainUrl
        ).document

        val items = document.select("div.card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a[href*='/${pageNo + 1}'], a[href*='/page/${pageNo + 1}/'], .pagination a, a.next").isNotEmpty()
        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = hasNext || items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")

        val document = app.get(
            "$mainUrl/cari.html?q=$encoded",
            headers = baseHeaders,
            cookies = requestCookies(),
            referer = mainUrl
        ).document
        return document.select("div.card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = baseHeaders,
            cookies = requestCookies(),
            referer = mainUrl
        ).document

        val title = document.selectFirst("h3.title, h1, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul Drakor.id tidak ditemukan")

        val poster = document.selectFirst("div.product-detail-header center img, meta[property=og:image], img[src*='image'], img[src*='poster']")
            ?.let { it.attr("abs:src").ifBlank { it.attr("content") } }
            ?.takeIf { it.isNotBlank() }
        val tags = document.select("#kategoriMe .chip-label")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { emptyList() }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val plot = document.selectFirst("#deskripsi p")
            ?.text()
            ?.replace(Regex("^Sinopsis\\s*", RegexOption.IGNORE_CASE), "")
            ?.trim()

        val trailer = document.selectFirst("#actionSheetTrailer iframe")?.attr("src")

        val actors = document.select("#section_artist h5")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val slug = extractSlug(url, document)
        val mType = Regex("var\\s+mTipe\\s*=\\s*(\\d+)")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 2

        val episodeOptions = document.select("#formPilihEpisode option[value]")
            .mapNotNull {
                val value = it.attr("value").trim()
                if (value == "0" || value.isBlank()) return@mapNotNull null
                val epNo = value.toIntOrNull() ?: return@mapNotNull null
                epNo to it.text().trim()
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (mType == 1 || episodeOptions.isEmpty()) {
            val movieData = LinkData(slug = slug, episode = 1).toJson()
            return newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }

        val episodes = episodeOptions.map { (epNo, epText) ->
            newEpisode(LinkData(slug = slug, episode = epNo).toJson()) {
                name = epText.ifBlank { "Episode $epNo" }
                episode = epNo
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = data.toLinkData()
        if (linkData == null || linkData.slug.isBlank()) return false

        val methods = listOf("fast", "lite", "max")
        val visitedPages = linkedSetOf<String>()
        val directMediaUrls = linkedSetOf<String>()

        methods.forEach { method ->
            visitedPages.add("$mainUrl/download-$method/${linkData.slug}/${linkData.episode}")
            visitedPages.add("$mainUrl/watch-$method/${linkData.slug}/${linkData.episode}")
            visitedPages.add("$mainUrl/watch-s$method/${linkData.slug}/${linkData.episode}")
        }

        // FIX #2: Track whether ANY source was found (direct or via iframe extractor).
        // Previously, return value depended only on directMediaUrls — so if only
        // iframes were found and loaded, the function returned false (= CloudStream
        // showed "No links found" even though videos were already loading).
        var anySourceFound = false

        visitedPages.forEach { pageUrl ->
            // FIX #4: Log errors instead of silently swallowing them.
            // Previously, runCatching{} with no onFailure made debugging impossible.
            runCatching {
                val document = app.get(
                    pageUrl,
                    headers = baseHeaders,
                    cookies = requestCookies(),
                    referer = "$mainUrl/nonton/${linkData.slug}/"
                ).document
                directMediaUrls += extractDirectMediaUrls(document)

                document.select("iframe[src], video source[src]")
                    .mapNotNull {
                        val src = it.attr("abs:src").trim()
                        src.takeIf { s -> s.startsWith("http") }
                    }
                    .forEach { embedUrl ->
                        if (emitDrakorIdPlayer(embedUrl, pageUrl, callback)) {
                            anySourceFound = true
                        } else {
                            loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)
                            anySourceFound = true
                        }
                    }
            }.onFailure { e ->
                e.printStackTrace()
            }
        }

        directMediaUrls.forEach { mediaUrl ->
            val quality = getQualityFromName(mediaUrl).let {
                if (it == Qualities.Unknown.value) inferQuality(mediaUrl) else it
            }
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ${qualityLabel(quality)}",
                    url = mediaUrl,
                ) {
                    this.quality = quality
                    this.referer = mainUrl
                }
            )
            anySourceFound = true
        }

        return anySourceFound
    }


    private fun String.toLinkData(): LinkData? {
        return runCatching {
            val json = JSONObject(this)
            LinkData(
                slug = json.optString("slug").trim(),
                episode = json.optInt("episode", 1).takeIf { it > 0 } ?: 1
            )
        }.getOrNull()
    }

    private suspend fun emitDrakorIdPlayer(
        embedUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!embedUrl.contains("/player/bunny.php", ignoreCase = true)) return false

        val video = embedUrl.extractQueryParam("v")
            ?.decodeDrakorIdBase64Url()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return false

        val quality = getQualityFromName(video).let {
            if (it == Qualities.Unknown.value) inferQuality(video) else it
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name Bunny ${qualityLabel(quality)}",
                url = video,
                type = if (video.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = mainUrl
                this.headers = baseHeaders + mapOf(
                    "Referer" to pageUrl,
                    "Origin" to mainUrl
                )
            }
        )
        return true
    }

    private fun String.extractQueryParam(name: String): String? {
        return substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.decodeDrakorIdBase64Url(): String? {
        val decodedParam = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
        val padded = decodedParam.replace(" ", "+").let { value ->
            value + "=".repeat((4 - value.length % 4) % 4)
        }
        return runCatching { base64Decode(padded) }.getOrNull()
    }

    // FIX #1 (continued): requestHeaders() no longer injects "Cookie" into the headers map.
    // Cookies are handled exclusively via requestCookies() → cookies parameter of app.get/post,
    // which is the correct and non-duplicating approach.
    private fun requestCookies(): Map<String, String> = cookieMap

    // FIX #3: toSearchResult() detects Movie vs Series from the URL.
    // Previously, ALL results were tagged as AsianDrama — even film pages like /film-korea/.
    // Now, URLs containing "/film" get TvType.Movie and use newMovieSearchResponse.
    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a[href*=/nonton/], a[href*=/go/]") ?: return null
        val href = linkEl.attr("abs:href")
            .ifBlank { linkEl.attr("href") }
            .let {
                when {
                    it.startsWith("http") -> it
                    it.startsWith("/") -> "$mainUrl$it"
                    else -> "$mainUrl/$it"
                }
            }

        val title = this.selectFirst("h5")
            ?.attr("data-original-title")
            ?.trim()
            ?.ifBlank { this.selectFirst("h5")?.text()?.trim() }
            ?: return null

        val poster = this.selectFirst("img")?.attr("abs:src")?.trim()

        val cardText = text()
        val isMovie = href.contains("/film", ignoreCase = true) ||
            cardText.contains("Film Korea", ignoreCase = true) ||
            cardText.contains("Film China", ignoreCase = true) ||
            cardText.contains("Film Thailand", ignoreCase = true) ||
            title.contains("Movie", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)\s+-\s+Drakor\.id.*$"""), "")
            .replace(Regex("""(?i)^Nonton\s+"""), "")
            .trim()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim().trim('/')
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> {
                "${clean.trimEnd('/')}/$page"
            }
            clean.isBlank() -> "$mainUrl/list/$page"
            else -> "$mainUrl/$clean/$page"
        }
    }

    private fun extractSlug(url: String, document: Document): String {
        val fromScript = Regex("var\\s+link\\s*=\\s*\"([^\"]+)\"")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (fromScript != null) return fromScript

        return url.substringAfter("/nonton/")
            .substringBefore("/")
            .substringBefore("?")
            .trim()
    }

    private fun extractDirectMediaUrls(document: Document): Set<String> {
        val links = linkedSetOf<String>()

        document.select("a[href], source[src], video[src]").forEach { el ->
            val raw = el.attr("abs:href").ifBlank { el.attr("abs:src") }.trim()
            if (raw.isBlank()) return@forEach
            if (raw.contains(".mp4") || raw.contains(".m3u8")) {
                links.add(raw)
            }
        }

        val regex = Regex("https?://[^\"'\\s]+(?:\\.mp4|\\.m3u8)[^\"'\\s]*")
        regex.findAll(document.html()).forEach { links.add(it.value) }

        return links
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(quality: Int): String {
        return if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"
    }

    data class LinkData(
        val slug: String,
        val episode: Int,
    )
}
