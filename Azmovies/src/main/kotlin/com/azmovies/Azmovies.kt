package com.azmovies

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class Azmovies : MainAPI() {
    override var mainUrl = "https://azmovies.to"
    override var name = "AzMovies"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage =
        mainPageOf(
            "$mainUrl/search?q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=newest&page=%d" to "Terbaru",
            "$mainUrl/search?q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Unggulan",
            "$mainUrl/search?q=&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=rating&page=%d" to "Rating Tertinggi",
            "$mainUrl/search?q=&genre=Action&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Aksi",
            "$mainUrl/search?q=&genre=Adventure&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Petualangan",
            "$mainUrl/search?q=&genre=Animation&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Animasi",
            "$mainUrl/search?q=&genre=Comedy&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Komedi",
            "$mainUrl/search?q=&genre=Crime&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Kriminal",
            "$mainUrl/search?q=&genre=Documentary&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Dokumenter",
            "$mainUrl/search?q=&genre=Drama&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Drama",
            "$mainUrl/search?q=&genre=Family&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Keluarga",
            "$mainUrl/search?q=&genre=Fantasy&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Fantasi",
            "$mainUrl/search?q=&genre=History&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Sejarah",
            "$mainUrl/search?q=&genre=Horror&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Horor",
            "$mainUrl/search?q=&genre=Music&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Musik",
            "$mainUrl/search?q=&genre=Mystery&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Misteri",
            "$mainUrl/search?q=&genre=Romance&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Romantis",
            "$mainUrl/search?q=&genre=Science%20Fiction&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Fiksi Ilmiah",
            "$mainUrl/search?q=&genre=TV%20Movie&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Film TV",
            "$mainUrl/search?q=&genre=Thriller&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Thriller",
            "$mainUrl/search?q=&genre=War&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Perang",
            "$mainUrl/search?q=&genre=Western&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured&page=%d" to "Western",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Jangan pakai String.format() di URL karena genre seperti Science%20Fiction
        // akan dibaca sebagai formatter conversion dan bikin error: Conversion = 'F'.
        val pageUrl = request.data.replace("%d", page.toString())
        val document = request(pageUrl).document
        val home = document.select("#movies-container a.poster").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url =
            "$mainUrl/search?q=${query.urlEncode()}&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured"
        return request(url).document.select("#movies-container a.poster").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.movie-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.movie-poster img")?.attr("src").toAbsoluteUrl()
        val backgroundPoster = document.selectFirst("div.movie-hero")?.attr("style")?.extractBackgroundUrl()
        val year = document.selectFirst("div.movie-meta span:not(.movie-rating)")?.text()?.trim()?.toIntOrNull()
        val duration = document.select("div.movie-meta span.has-icon").firstOrNull()?.text()?.toMinutes()
        val ratingText = document.selectFirst("span.movie-rating")?.text()?.trim()
        val plot = document.selectFirst("div.movie-overview p")?.text()?.trim()
        val tags = document.select("div.movie-genres a")
            .map { translateGenre(it.text().trim()) }
            .filter { it.isNotBlank() }
        val actors =
            document.select("div.movie-cast .cast-card").mapNotNull { card ->
                val actorName = card.selectFirst("strong")?.text()?.trim() ?: return@mapNotNull null
                val role = card.selectFirst("span")?.text()?.trim()
                val image = card.selectFirst("img")?.attr("src").toAbsoluteUrl()
                ActorData(Actor(actorName, image), roleString = role)
            }
        val recommendations =
            document
                .select("div.simple-carousel a.poster")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            backgroundPosterUrl = backgroundPoster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.duration = duration
            addScore(ratingText)
            this.actors = actors
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = request(data)
        val document = response.document
        val seenSubtitles = linkedSetOf<String>()
        val servers = extractServerButtons(response.text, document)

        servers.forEach { button ->
            val rawUrl = button.url.replace("&amp;", "&").trim()
            if (rawUrl.isBlank()) return@forEach

            extractSubtitle(rawUrl)?.let { subtitle ->
                if (seenSubtitles.add(subtitle.url)) {
                    subtitleCallback(subtitle)
                }
            }

            val label = buildString {
                append(button.server.ifBlank { "Server" })
                val quality = button.quality.trim()
                if (quality.isNotBlank()) {
                    append(" ")
                    append(quality)
                }
            }.trim()

            runCatching {
                if (rawUrl.contains("vidsrc.xyz", true)) {
                    val handled = loadVidsrc(rawUrl, button.quality, callback)
                    if (!handled) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = label.ifBlank { "VidSrc" },
                                url = rawUrl,
                                type = com.lagradost.cloudstream3.utils.INFER_TYPE,
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = getQualityFromName(button.quality)
                            },
                        )
                    }
                } else {
                    loadExtractor(rawUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }.onFailure {
                callback(
                    newExtractorLink(
                        source = name,
                        name = label.ifBlank { name },
                        url = rawUrl,
                        type = com.lagradost.cloudstream3.utils.INFER_TYPE,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(button.quality)
                    },
                )
            }
        }

        if (servers.isEmpty()) {
            document
                .select("iframe[src], iframe[data-src], iframe[data-litespeed-src], a[href*='vidsrc'], a[href*='myvidplay'], a[href*='hqq'], a[href*='byse']")
                .mapNotNull { element ->
                    when {
                        element.hasAttr("data-litespeed-src") -> element.attr("data-litespeed-src")
                        element.hasAttr("data-src") -> element.attr("data-src")
                        element.hasAttr("src") -> element.attr("src")
                        else -> element.attr("href")
                    }.trim().takeIf { it.isNotBlank() }
                }
                .distinct()
                .forEach { raw ->
                    runCatching {
                        loadExtractor(raw.toAbsoluteUrl() ?: raw, "$mainUrl/", subtitleCallback, callback)
                    }
                }
        }

        return true
    }

    private suspend fun loadVidsrc(
        url: String,
        qualityLabel: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedUrl = normalizeVidsrcUrl(url)
        val browserHeaders =
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to USER_AGENT,
            )
        val embedResponse = app.get(embedUrl, referer = "$mainUrl/", headers = browserHeaders)
        val twoEmbedHash = extractVidsrcServerHash(embedResponse.text, "2Embed") ?: return false
        val rcpUrl = "//cloudnestra.com/rcp/$twoEmbedHash".toAbsoluteUrl(getBaseUrl(embedResponse.url)) ?: return false
        val rcpResponse = app.get(rcpUrl, referer = "${getBaseUrl(embedResponse.url)}/", headers = browserHeaders)
        val srcrcpUrl =
            Regex("""/srcrcp/[^'"\s<]+""")
                .find(rcpResponse.text)
                ?.value
                ?.toAbsoluteUrl(getBaseUrl(rcpResponse.url))
                ?: return false
        val twoEmbedResponse = app.get(srcrcpUrl, referer = "${getBaseUrl(rcpResponse.url)}/", headers = browserHeaders)
        val xpsUrl =
            Regex("""https://streamsrcs\.2embed\.cc/xps\?[^'"\s<]+""", RegexOption.IGNORE_CASE)
                .find(twoEmbedResponse.text)
                ?.value
                ?: return false
        val xpsResponse = app.get(xpsUrl, referer = "${getBaseUrl(twoEmbedResponse.url)}/", headers = browserHeaders)
        val xpassSlug = xpsResponse.document.selectFirst("iframe#framesrc")?.attr("src")?.trim().takeIf { !it.isNullOrBlank() } ?: return false
        val xpassUrl = "https://play.xpass.top/e/movie/$xpassSlug"
        val xpassResponse = app.get(xpassUrl, referer = "${getBaseUrl(xpsResponse.url)}/", headers = browserHeaders)
        val playlistUrl =
            Regex(""""playlist":"([^"]+/playlist\.json)"""")
                .find(xpassResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.toAbsoluteUrl(getBaseUrl(xpassResponse.url))
                ?: return false
        val playlistResponse =
            app.get(
                playlistUrl,
                referer = xpassUrl,
                headers = mapOf("Accept" to "application/json,text/plain,*/*", "User-Agent" to USER_AGENT),
            )
        val streamUrl =
            Regex(""""file"\s*:\s*"([^"]+)"""")
                .findAll(playlistResponse.text)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .map { it.decodeJsonUrl() }
                .firstOrNull { it.contains(".m3u8", true) }
                ?: return false
        val streamHeaders =
            mapOf(
                "Accept" to "*/*",
                "Referer" to "https://play.xpass.top/",
                "Origin" to "https://play.xpass.top",
                "User-Agent" to USER_AGENT,
            )

        val generatedLinks =
            M3u8Helper.generateM3u8(
                "VidSrc",
                streamUrl,
                "https://play.xpass.top/",
                headers = streamHeaders,
            )

        if (generatedLinks.isEmpty()) return false
        generatedLinks.forEach(callback)
        return true
    }

    private fun normalizeVidsrcUrl(url: String): String {
        if (!url.contains("vidsrc", true) && !url.contains("vsembed", true)) return url
        if (url.contains("autoplay=", true)) return url
        val joiner = if (url.contains("?")) "&" else "?"
        return "$url${joiner}autoplay=1"
    }

    private fun extractVidsrcServerHash(
        html: String,
        serverName: String,
    ): String? {
        return Regex(
            """<div\s+class=["']server["'][^>]*data-hash=["']([^"']+)["'][^>]*>\s*${Regex.escape(serverName)}\s*</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
    }

    private fun String.decodeJsonUrl(): String {
        return replace("\\u0026", "&").replace("\\/", "/")
    }

    private fun translateGenre(name: String): String {
        return when (name.trim().lowercase()) {
            "action" -> "Aksi"
            "adventure" -> "Petualangan"
            "animation" -> "Animasi"
            "comedy" -> "Komedi"
            "crime" -> "Kriminal"
            "documentary" -> "Dokumenter"
            "drama" -> "Drama"
            "family" -> "Keluarga"
            "fantasy" -> "Fantasi"
            "history" -> "Sejarah"
            "horror" -> "Horor"
            "music" -> "Musik"
            "mystery" -> "Misteri"
            "romance" -> "Romantis"
            "science fiction", "sci-fi" -> "Fiksi Ilmiah"
            "tv movie" -> "Film TV"
            "thriller" -> "Thriller"
            "war" -> "Perang"
            "western" -> "Western"
            else -> name.trim()
        }
    }

    private suspend fun request(url: String): NiceResponse {
        val response =
            app.get(
                url,
                headers = headers,
                timeout = 30L,
            )
        if (!response.isVerificationPage()) return response

        val token = response.text.substringAfter("var verifyToken = \"", "").substringBefore("\"")
        if (token.isBlank()) return response
        val cookies = response.cookies

        app.post(
            "$mainUrl/verified",
            headers =
                headers +
                    mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to mainUrl,
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
            cookies = cookies,
            requestBody =
                mapOf("token" to token)
                    .toJson()
                    .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
        )

        return app.get(
            url,
            headers = headers,
            cookies = cookies,
            timeout = 30L,
        )
    }

    private fun NiceResponse.isVerificationPage(): Boolean {
        return text.contains("Verifying your browser", true) && text.contains("var verifyToken")
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        val title = selectFirst("span.poster__title")?.text()?.trim() ?: return null
        val poster =
            (
                selectFirst("img.poster__img")?.attr("data-src")
                    ?: selectFirst("img.poster__img")?.attr("src")
            ).toAbsoluteUrl()
        val year = selectFirst("span.badge")?.text()?.trim()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun extractSubtitle(url: String): SubtitleFile? {
        val subtitleUrl = url.substringAfter("c1_file=", "").substringBefore("&").urlDecode()
        if (subtitleUrl.isBlank() || !subtitleUrl.contains(".vtt", true)) return null
        val label = url.substringAfter("c1_label=", "English").substringBefore("&").urlDecode()
        return SubtitleFile(label.ifBlank { "English" }, subtitleUrl)
    }

    private fun extractServerButtons(html: String, document: org.jsoup.nodes.Document): List<ServerButton> {
        val regexButtons =
            Regex(
                """<button[^>]*class=["'][^"']*server-btn[^"']*["'][^>]*data-url=["']([^"']+)["'][^>]*data-server=["']([^"']*)["'][^>]*data-quality=["']([^"']*)["'][^>]*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).findAll(html).map {
                ServerButton(
                    url = it.groupValues[1],
                    server = it.groupValues[2],
                    quality = it.groupValues[3],
                )
            }.toList()

        val domButtons =
            document.select("button.server-btn[data-url]").map {
                ServerButton(
                    url = it.attr("data-url"),
                    server = it.attr("data-server"),
                    quality = it.attr("data-quality"),
                )
            }

        return (regexButtons + domButtons).distinctBy { "${it.server}|${it.url}" }
    }

    private fun String.extractBackgroundUrl(): String? {
        return Regex("""url\(['"]?([^'")]+)""").find(this)?.groupValues?.getOrNull(1)
    }

    private fun String.toMinutes(): Int? {
        val parts = trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    private fun String.urlEncode(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    private fun String.urlDecode(): String {
        return URLDecoder.decode(this, "UTF-8")
    }

    private fun String?.toAbsoluteUrl(): String? {
        val value = this?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return value.toAbsoluteUrl(mainUrl)
    }

    private fun String?.toAbsoluteUrl(baseUrl: String): String? {
        val value = this?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$baseUrl$value"
            else -> value
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

    private data class ServerButton(
    val url: String,
    val server: String,
    val quality: String,
)

    private val headers =
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        )
}
