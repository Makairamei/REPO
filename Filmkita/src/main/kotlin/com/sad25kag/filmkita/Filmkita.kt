package com.sad25kag.filmkita

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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Element

class Filmkita : MainAPI() {
    override var mainUrl = "https://s7.iix.llc"
    override var name = "Filmkita"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "page/%d/" to "Film Terbaru",
        "tv/page/%d/" to "TV Series",
        "best-rating/page/%d/" to "Best Rating",

        "category/action/page/%d/" to "Action",
        "category/adventure/page/%d/" to "Adventure",
        "category/animation/page/%d/" to "Animation",
        "category/comedy/page/%d/" to "Comedy",
        "category/crime/page/%d/" to "Crime",
        "category/documentary/page/%d/" to "Documentary",
        "category/drama/page/%d/" to "Drama",
        "category/family/page/%d/" to "Family",
        "category/fantasy/page/%d/" to "Fantasy",
        "category/history/page/%d/" to "History",
        "category/horror/page/%d/" to "Horror",
        "category/music/page/%d/" to "Music",
        "category/mystery/page/%d/" to "Mystery",
        "category/romance/page/%d/" to "Romance",
        "category/science-fiction/page/%d/" to "Science Fiction",
        "category/thriller/page/%d/" to "Thriller",
        "category/tv-movie/page/%d/" to "TV Movie",
        "category/war/page/%d/" to "War",
        "category/western/page/%d/" to "Western",

        "country/usa/page/%d/" to "USA",
        "country/united-states/page/%d/" to "United States",
        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/japan/page/%d/" to "Japan",
        "country/china/page/%d/" to "China",
        "country/india/page/%d/" to "India",
        "country/thailand/page/%d/" to "Thailand",
        "country/philippines/page/%d/" to "Philippines",
        "country/united-kingdom/page/%d/" to "United Kingdom",

        "year/2026/page/%d/" to "Tahun 2026",
        "year/2025/page/%d/" to "Tahun 2025",
        "year/2024/page/%d/" to "Tahun 2024",
        "year/2023/page/%d/" to "Tahun 2023",
        "year/2022/page/%d/" to "Tahun 2022",
        "year/2021/page/%d/" to "Tahun 2021",
        "year/2020/page/%d/" to "Tahun 2020"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = fixUrl(request.data.format(page))
        val document = app.get(url).document

        val home = document.select(
            "article.item, " +
                "article.item.col-md-20, " +
                "div.gmr-box-content article, " +
                "div.row article"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a.nextpostslink, " +
                    ".pagination a:contains(Next), " +
                    "ul.pagination li a:contains(»)"
            ) != null || home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val q = URLEncoder.encode(query.trim(), "UTF-8")

        val endpoints = listOf(
            "$mainUrl/?s=$q&post_type[]=post&post_type[]=tv",
            "$mainUrl/?s=$q",
            "$mainUrl?s=$q"
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in endpoints) {
            val document = runCatching {
                app.get(url, timeout = 50L).document
            }.getOrNull() ?: continue

            document.select(
                "article.item, " +
                    "article.item.col-md-20, " +
                    "div.gmr-box-content article, " +
                    "div.row article"
            ).mapNotNull { it.toSearchResult() }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val response = app.get(url)
        val document = response.document

        val title = document.selectFirst(
            "h1.entry-title, " +
                "h1[itemprop=name], " +
                ".gmr-movie-title h1"
        )?.text()
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster = fixUrlNull(
            document.selectFirst(
                "figure.pull-left > img, " +
                    ".content-thumbnail img, " +
                    ".gmr-movie-data img, " +
                    "meta[property=og:image]"
            )?.getImageAttr()
        )?.fixImageQuality()

        val tags = document.select(
            "strong:contains(Genre) ~ a, " +
                "a[href*=/category/]"
        ).eachText()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.selectFirst(
            "div.gmr-moviedata strong:contains(Year:) > a, " +
                "a[href*=/year/]"
        )?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""")
                .find(document.selectFirst(".gmr-moviedata, .entry-content")?.text().orEmpty())
                ?.value
                ?.toIntOrNull()

        val tvType = when {
            url.contains("/tv/", true) -> TvType.TvSeries
            document.selectFirst("div.vid-episodes a, div.gmr-listseries a") != null -> TvType.TvSeries
            tags.any { it.equals("TV SERIES", true) } -> TvType.TvSeries
            else -> TvType.Movie
        }

        val description = document.selectFirst(
            "div[itemprop=description] > p, " +
                "div[itemprop=description], " +
                ".entry-content p, " +
                ".gmr-movie-data p"
        )?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val trailer = document.selectFirst(
            "ul.gmr-player-nav li a.gmr-trailer-popup[href], " +
                "a.gmr-trailer-popup[href], " +
                "a[href*='youtube.com'], " +
                "a[href*='youtu.be']"
        )?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val rating = document.selectFirst(
            "div.gmr-meta-rating > span[itemprop=ratingValue], " +
                "span[itemprop=ratingValue], " +
                ".gmr-rating-item"
        )?.text()
            ?.trim()
            ?.replace(",", ".")

        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a, span[itemprop=actors] a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = document.selectFirst(
            "div.gmr-moviedata span[property=duration], " +
                "span[property=duration], " +
                ".runtime"
        )?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val recommendations = (
            document.select("article.item.col-md-20").mapNotNull { it.toRecommendResult() } +
                document.select("div.idmuvi-rp ul li").mapNotNull { it.toRecommendResult() } +
                document.select(".gmr-related-post article").mapNotNull { it.toRecommendResult() }
            ).distinctBy { it.url }

        return if (tvType == TvType.TvSeries) {
            val episodes = parseEpisodes(document = document, fallbackUrl = url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
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
        val baseUrl = getBaseUrl(data)
        val document = app.get(data).document
        val links = linkedSetOf<String>()

        val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

        if (id.isNullOrBlank()) {
            document.select("ul.muvipro-player-tabs li a[href]").amap { element ->
                val playerPageUrl = fixUrl(element.attr("href"))

                val iframe = runCatching {
                    app.get(playerPageUrl).document
                        .selectFirst("div.gmr-embed-responsive iframe, iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                }.getOrNull()

                if (!iframe.isNullOrBlank()) {
                    links.add(iframe)
                }
            }
        } else {
            document.select("div.tab-content-ajax[id]").amap { element ->
                val server = runCatching {
                    app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to element.attr("id"),
                            "post_id" to id,
                        ),
                    ).document
                        .selectFirst("iframe")
                        ?.getIframeAttr()
                        ?.let { httpsify(it) }
                }.getOrNull()

                if (!server.isNullOrBlank()) {
                    links.add(server)
                }
            }
        }

        document.select(
            "ul.gmr-download-list li a[href], " +
                ".gmr-download-list a[href], " +
                "a[href*='hglink.to'], " +
                "a[href*='ghbrisk.com'], " +
                "a[href*='dhcplay.com'], " +
                "a[href*='strp2p.com'], " +
                "a[href*='rpmvid.com'], " +
                "a[href*='dintezuvio.com'], " +
                "a[href*='dingtezuni.com'], " +
                "a[href*='movearnpre.com'], " +
                "a[href*='mivalyo.com'], " +
                "a[href*='bingezove.com'], " +
                "a[href*='minochinos.com'], " +
                "a[href*='ryderjet.com'], " +
                "a[href*='hls-bekop.layarwibu.com']"
        ).forEach { linkElement ->
            val downloadUrl = linkElement.attr("href").trim()
            if (downloadUrl.isNotBlank()) {
                links.add(fixUrl(downloadUrl))
            }
        }

        links.forEach { link ->
            loadExtractor(link, "$baseUrl/", subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document, fallbackUrl: String): List<Episode> {
        val episodeElements = document.select(
            "div.vid-episodes a[href], " +
                "div.gmr-listseries a[href], " +
                ".vid-episodes a[href], " +
                ".gmr-listseries a[href]"
        )

        val episodes = episodeElements.mapIndexed { index, eps ->
            val href = fixUrl(eps.attr("href"))
            val episodeName = eps.text().trim().ifBlank { "Episode ${index + 1}" }
            val episodeNumber = extractEpisodeNumber(episodeName, href) ?: index + 1
            val seasonNumber = extractSeasonNumber(episodeName, href)

            newEpisode(href) {
                name = episodeName
                episode = episodeNumber
                season = seasonNumber
            }
        }.distinctBy { it.data }

        return episodes.ifEmpty {
            listOf(
                newEpisode(fallbackUrl) {
                    name = "Episode 1"
                    episode = 1
                }
            )
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "h2.entry-title > a[href], " +
                "h2 a[href], " +
                ".entry-title a[href], " +
                "a[href][title], " +
                "a[href]"
        ) ?: return null

        val title = listOf(
            anchor.text().trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Tonton", true) &&
                !it.equals("Trailer", true)
        }?.cleanTitle() ?: return null

        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()

        val rating = selectFirst(
            "div.gmr-rating-item, " +
                ".gmr-meta-rating span[itemprop=ratingValue], " +
                "span[itemprop=ratingValue]"
        )?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()

        val typeText = text()

        val isTvSeries = href.contains("/tv/", true) ||
            selectFirst("div.gmr-numbeps > span, span.episode, .gmr-numbeps") != null ||
            title.contains("episode", true) ||
            typeText.contains("TV SERIES", true) ||
            typeText.contains("Eps:", true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val anchor = selectFirst(
            "h2.entry-title > a[href], " +
                "a > span.idmuvi-rp-title, " +
                "a[href]"
        ) ?: return null

        val href = fixUrl(anchor.attr("href"))

        val title = listOf(
            anchor.text().trim(),
            selectFirst("h2.entry-title > a")?.text()?.trim(),
            selectFirst("a > span.idmuvi-rp-title")?.text()?.trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        val isTvSeries = href.contains("/tv/", true) || text().contains("Eps:", true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("content") -> attr("content")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src")
            ?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val match = Regex("""-\d*x\d*(?=\.)""").find(this)?.value
        return if (match != null) replace(match, "") else this
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""(?i)\s+season\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+sub\s+indo.*$"""), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractEpisodeNumber(name: String, href: String): Int? {
        return (
            Regex("""(?:eps?|episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: Regex("""\b(\d+)\b""").find(name)?.groupValues?.getOrNull(1)
            )?.toIntOrNull()
    }

    private fun extractSeasonNumber(name: String, href: String): Int? {
        return (
            Regex("""(?:^|\b)s(?:eason)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex("""-season-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
            )?.toIntOrNull()
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }
}