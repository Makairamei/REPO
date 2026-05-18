package com.drakorid

import com.excloud.BuildConfig
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DrakoridProvider : MainAPI() {
    override var mainUrl = "https://drakorid.co"
    override var name = "Drakor.id"
    override var lang = "id"
    override val hasMainPage = true
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
        "kategori/drama-korea" to "Drama Korea",
        "kategori/drama-china" to "Drama China",
        "kategori/variety-show" to "Variety Show",
        "kategori/film-korea" to "Film Korea",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageNo = if (page <= 0) 1 else page
        val url = "$mainUrl/${request.data}/$pageNo"
        val document = app.get(
            url,
            headers = baseHeaders,
            cookies = requestCookies(),
            referer = mainUrl
        ).document

        val items = document.select("div.card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
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

        val title = document.selectFirst("h3.title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.product-detail-header center img")?.attr("abs:src")
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
        val linkData = runCatching { parseJson<LinkData>(data) }.getOrNull()
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
                        loadExtractor(embedUrl, pageUrl, subtitleCallback, callback)
                        anySourceFound = true
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

        val isMovie = href.contains("/film", ignoreCase = true)

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
