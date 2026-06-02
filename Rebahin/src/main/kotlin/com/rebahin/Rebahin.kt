package com.rebahin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

open class Rebahin : MainAPI() {
    companion object {
        // Single point of domain rotation. Update here when the site moves.
        const val DOMAIN = "https://rebahinxxi3.biz"

        val baseHeaders =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )
    }

    override var mainUrl = DOMAIN
    private var directUrl: String? = null
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    open var mainServer = DOMAIN
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    /**
     * Wraps app.get with up to [maxRetries] attempts, uniform headers, and a 30s timeout.
     * Returns null on exhaustion so callsites stay null-safe.
     */
    private suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        maxRetries: Int = 3,
    ): com.lagradost.nicehttp.NiceResponse? {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                return app.get(
                    url,
                    referer = referer,
                    headers = baseHeaders,
                    timeout = 30L,
                )
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            }
        }
        logError(lastError ?: Exception("Rebahin safeGet failed: $url"))
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val urls =
            listOf(
                Pair("Featured", "xtab1"),
                Pair("Film Terbaru", "xtab2"),
                Pair("Romance", "xtab3"),
                Pair("Drama", "xtab4"),
                Pair("Action", "xtab5"),
                Pair("Scifi", "xtab6"),
                Pair("Tv Series Terbaru", "stab1"),
                Pair("Anime Series", "stab2"),
                Pair("Drakor Series", "stab3"),
                Pair("West Series", "stab4"),
                Pair("China Series", "stab5"),
                Pair("Japan Series", "stab6"),
            )

        // Fan out all 12 tab fetches concurrently. Was previously a serial for-loop
        // which made first paint take 12× longer than necessary.
        val items =
            urls.amap { (header, tab) ->
                val home =
                    safeGet("$mainUrl/wp-content/themes/indoxxi/ajax-top-$tab.php")
                        ?.document
                        ?.select("div.ml-item")
                        ?.mapNotNull { it.toSearchResult() }
                        .orEmpty()
                if (home.isNotEmpty()) HomePageList(header, home) else null
            }.filterNotNull()

        if (items.isEmpty()) throw ErrorLoadingException()
        return newHomePageResponse(items)
    }

    fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("span.mli-info > h2")?.text() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val type =
            if (this.select("span.mli-quality").isNotEmpty()) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) {
            val posterUrl = fixUrlNull(this.select("img").attr("src"))
            val quality = getQualityFromString(this.select("span.mli-quality").text().trim())
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            val posterUrl =
                fixUrlNull(
                    this.select("img").attr("src").ifEmpty {
                        this.select("img").attr("data-original")
                    },
                )
            val episode =
                this
                    .select("div.mli-eps > span")
                    .text()
                    .replace(Regex("[^0-9]"), "")
                    .toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document =
            safeGet("$mainUrl/?s=$encoded")?.document ?: return emptyList()
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val req = safeGet(url)
            ?: throw ErrorLoadingException("Rebahin page unreachable: $url")
        directUrl = getBaseUrl(req.url)
        val document = req.document
        val title = document.selectFirst("h3[itemprop=name]")?.ownText()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ").trim().ifBlank { "Untitled" }
        val poster =
            document
                .select(".mvic-desc > div.thumb.mvic-thumb")
                .attr("style")
                .substringAfter("url(")
                .substringBeforeLast(")")
                .ifBlank { null }
        val tags = document.select("span[itemprop=genre]").map { it.text() }

        val year =
            document
                .selectFirst(".mvici-right > p:nth-child(3)")
                ?.ownText()
                ?.trim()
                ?.let { Regex("([0-9]{4}?)-").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val tvType = if (url.contains("/series/")) TvType.TvSeries else TvType.Movie
        val description = document.select("span[itemprop=reviewBody] > p").text().trim()
        val trailer = fixUrlNull(document.selectFirst("div.modal-body-trailer iframe")?.attr("src"))
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()
        val duration =
            document
                .selectFirst(".mvici-right > p:nth-child(1)")
                ?.ownText()
                ?.replace(Regex("[^0-9]"), "")
                ?.toIntOrNull()
        val actors = document.select("span[itemprop=actor] > a").map { it.select("span").text() }

        val baseLink = fixUrlNull(document.select("div#mv-info > a").attr("href"))
            ?: throw ErrorLoadingException("Rebahin: no source link found on $url")

        return if (tvType == TvType.TvSeries) {
            val episodes =
                safeGet(baseLink)
                    ?.document
                    ?.select("div#list-eps > a")
                    ?.map { Pair(it.text(), it.attr("data-iframe")) }
                    ?.groupBy { it.first }
                    ?.map { eps ->
                        newEpisode(
                            eps.value.map { fixUrl(base64Decode(it.second)) }.toString(),
                        ) {
                            this.name = eps.key
                            this.episode = eps.key.filter { it.isDigit() }.toIntOrNull()
                        }
                    }.orEmpty()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val links =
                app
                    .get(baseLink)
                    .document
                    .select("div#server-list div.server-wrapper div[id*=episode]")
                    .map { fixUrl(base64Decode(it.attr("data-iframe"))) }
                    .toString()
            newMovieLoadResponse(title, url, TvType.Movie, links) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        data.removeSurrounding("[", "]").split(",").map { it.trim() }.amap { link ->
            safeApiCall {
                when {
                    link.startsWith(mainServer) ->
                        invokeLokalSource(link, subtitleCallback, callback)
                    else -> {
                        val resolvedCount = AtomicInteger(0)
                        val ref = directUrl?.let { "$it/" } ?: "$mainServer/"
                        loadExtractor(link, ref, subtitleCallback) { ext ->
                            resolvedCount.incrementAndGet()
                            callback.invoke(ext)
                        }
                        if (resolvedCount.get() == 0) {
                            val host =
                                runCatching { URI(link).host }
                                    .getOrNull()
                                    ?.removePrefix("www.") ?: "Embed"
                            callback.invoke(
                                newExtractorLink(host, host, link) {
                                    this.referer = ref
                                    this.quality = Qualities.Unknown.value
                                },
                            )
                        }
                    }
                }
            }
        }

        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }

    private suspend fun invokeLokalSource(
        url: String,
        subCallback: (SubtitleFile) -> Unit,
        sourceCallback: (ExtractorLink) -> Unit,
    ) {
        val document =
            runCatching {
                app.get(
                    url,
                    allowRedirects = false,
                    referer = directUrl,
                    headers = baseHeaders + mapOf("Accept" to
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"),
                    timeout = 30L,
                ).document
            }.getOrNull() ?: return

        document.select("script").find { it.data().contains("window.juicyData") }?.data()?.let { script ->
            Regex("\"file\":\\s?\"(.+.m3u8)\"").find(script)?.groupValues?.getOrNull(1)?.let { link ->
                M3u8Helper
                    .generateM3u8(
                        name,
                        link,
                        referer = "$mainServer/",
                        headers = mapOf("Accept" to "*/*", "Origin" to mainServer),
                    ).forEach(sourceCallback)
            }

            val subData =
                Regex("\"?tracks\"?:\\s\\n?\\[(.*)],").find(script)?.groupValues?.getOrNull(1)
                    ?: Regex("\"?tracks\"?:\\s\\n?\\[\\s*(?s:(.+)],\\n\\s*\"sources)")
                        .find(script)
                        ?.groupValues
                        ?.getOrNull(1)
            tryParseJson<List<Tracks>>("[$subData]")?.map {
                subCallback.invoke(
                    SubtitleFile(
                        getLanguage(it.label ?: return@map null),
                        if (it.file?.contains(".srt") == true) it.file else return@map null,
                    ),
                )
            }
        }
    }

    private fun getLanguage(str: String): String = when {
        str.contains("indonesia", true) || str.contains("bahasa", true) -> "Indonesian"
        else -> str
    }

    private fun getBaseUrl(url: String): String = URI(url).let { "${it.scheme}://${it.host}" }

    private data class Tracks(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )
}
