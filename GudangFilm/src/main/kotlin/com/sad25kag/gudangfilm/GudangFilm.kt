package com.sad25kag.gudangfilm

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
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class GudangFilm : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://itoshii-movie.com"
    override var name = "GudangFilm🎉"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "page/%d/" to "Update Terbaru",
        "movie/page/%d/" to "Movie",
        "serial-tv-terbaru/page/%d/" to "Serial TV",
        "animasi/page/%d/" to "Animasi",
        "box-office/page/%d/" to "Box Office",
        "populer/page/%d/" to "Populer",
        "best-rating/page/%d/" to "Best Rating",

        "action/page/%d/" to "Action",
        "action-adventure/page/%d/" to "Action & Adventure",
        "adventure/page/%d/" to "Adventure",
        "animation/page/%d/" to "Animation",
        "comedy/page/%d/" to "Comedy",
        "crime/page/%d/" to "Crime",
        "documentary/page/%d/" to "Documentary",
        "drama/page/%d/" to "Drama",
        "family/page/%d/" to "Family",
        "fantasy/page/%d/" to "Fantasy",
        "history/page/%d/" to "History",
        "horror/page/%d/" to "Horror",
        "music/page/%d/" to "Music",
        "mystery/page/%d/" to "Mystery",
        "reality/page/%d/" to "Reality",
        "romance/page/%d/" to "Romance",
        "sci-fi-fantasy/page/%d/" to "Sci-Fi & Fantasy",
        "science-fiction/page/%d/" to "Science Fiction",
        "thriller/page/%d/" to "Thriller",
        "tv-movie/page/%d/" to "TV Movie",
        "war/page/%d/" to "War",

        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/japan/page/%d/" to "Japan",
        "country/china/page/%d/" to "China",
        "country/india/page/%d/" to "India",
        "country/thailand/page/%d/" to "Thailand",
        "country/philippines/page/%d/" to "Philippines",
        "country/usa/page/%d/" to "USA",
        "country/united-kingdom/page/%d/" to "United Kingdom",
        "country/australia/page/%d/" to "Australia",
        "country/canada/page/%d/" to "Canada",
        "country/ireland/page/%d/" to "Ireland",
        "country/new-zealand/page/%d/" to "New Zealand"
    )

    private val desktopHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val safePage = page.coerceAtLeast(1)
        val route = request.data.format(safePage)
        val url = "$mainUrl/${route.trimStart('/')}"
        val document = app.get(url, headers = desktopHeaders).document

        val home = document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    ".pagination a:contains(Next), " +
                    ".page-numbers.next, " +
                    ".page-numbers:contains(»), " +
                    "a[href*='/page/${safePage + 1}/']"
            ) != null
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(titleAnchorSelector) ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isNavigationUrl(href)) return null

        val rawTitle = listOf(
            selectFirst("h2.entry-title > a")?.text()?.trim(),
            selectFirst("h3.entry-title > a")?.text()?.trim(),
            selectFirst(".entry-title a")?.text()?.trim(),
            selectFirst("h2 a")?.text()?.trim(),
            selectFirst("h3 a")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull { it.isUsefulTitle() } ?: return null

        val title = rawTitle.cleanTitle()
            .removePrefix("Nonton film ")
            .removeSuffix(" terbaru di Dutamovie21")
            .cleanTitle()

        if (title.length < 2) return null

        val posterUrl = fixUrlNull(selectFirst("a > img, img")?.getImageAttr())?.fixImageQuality()
        val quality = select("div.gmr-qual, div.gmr-quality-item > a, a[href*='/quality/'], .quality, .gmr-quality")
            .text()
            .trim()
            .replace("-", "")
        val ratingText = selectFirst("div.gmr-rating-item, .rating, [itemprop=ratingValue]")?.ownText()?.trim()
        val tvType = getTypeFromUrl(href, title, text())

        return if (tvType == TvType.TvSeries || tvType == TvType.Anime) {
            val episode = extractEpisodeNumber(title) ?: extractEpisodeNumber(text())
            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality.isNotBlank()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
        val document = app.get(
            "$mainUrl?s=$encodedQuery",
            timeout = 30L,
            headers = desktopHeaders
        ).document

        return document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = desktopHeaders).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: url.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst("figure.pull-left > img, .poster img, img.wp-post-image, .content-thumbnail img, article img")
                ?.getImageAttr()
        )?.fixImageQuality()

        val tags = document.select(
            "strong:contains(Genre) ~ a, " +
                "a[href*='/genre/'], " +
                ".gmr-moviedata a[href*='/']"
        ).eachText()
            .map { it.cleanTitle() }
            .filter { it.isNotBlank() && !it.equals("Trailer", true) && !it.equals("Tonton", true) }
            .distinct()

        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a, a[href*='/year/'], a[href*='/release/']")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("""\((19|20)\d{2}\)""")
                .find(title)
                ?.value
                ?.filter { it.isDigit() }
                ?.toIntOrNull()

        val tvType = getTypeFromUrl(url, title, document.text())
        val description = document.selectFirst(
            "div[itemprop=description] > p, " +
                "div[itemprop=description], " +
                ".entry-content p, " +
                ".gmr-movie-data p, " +
                ".gmr-description p"
        )?.text()?.trim()

        val trailer = document.selectFirst(
            "ul.gmr-player-nav li a.gmr-trailer-popup[href], " +
                "a.gmr-trailer-popup[href], " +
                "a[href*='youtube.com'], a[href*='youtu.be']"
        )?.attr("href")?.takeIf { it.isNotBlank() }

        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue], [itemprop=ratingValue]")
            ?.text()
            ?.trim()

        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a, a[href*='/cast/'], a[href*='/actors/']")
            .map { it.text().cleanTitle() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = document.selectFirst("div.gmr-moviedata span[property=duration], span[property=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val recommendations = document.select(cardSelector)
            .mapNotNull { it.toRecommendResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            val episodes = parseEpisodes(url, document)

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    private suspend fun parseEpisodes(
        url: String,
        currentDocument: org.jsoup.nodes.Document
    ): List<Episode> {
        val seriesUrl = currentDocument.selectFirst(
            "a.button.button-shadow.active[href], " +
                "a[href*='/season/'][href], " +
                "a[href*='/serial-tv/'][href]"
        )?.attr("href")?.takeIf { it.isNotBlank() }
            ?: url.substringBefore("/eps/")

        val seriesDoc = runCatching {
            app.get(seriesUrl, headers = desktopHeaders).document
        }.getOrDefault(currentDocument)

        val episodes = linkedMapOf<String, Episode>()
        var episodeCounter = 1

        seriesDoc.select(
            "div.gmr-listseries a.button.button-shadow[href], " +
                ".gmr-listseries a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href], " +
                "a[href*='/eps/'], " +
                "a[href*='episode']"
        ).forEach { eps ->
            val href = fixUrlNull(eps.attr("href")) ?: return@forEach
            val name = eps.text().cleanTitle()

            if (name.contains("View All Episodes", ignoreCase = true)) return@forEach
            if (href == seriesUrl) return@forEach
            if (!name.contains("Eps", ignoreCase = true) &&
                !name.contains("Episode", ignoreCase = true) &&
                !href.contains("/eps/", true)
            ) return@forEach

            val season = Regex("""(?:Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val epNum = Regex("""(?:Eps|Episode|Ep)\s*\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""/eps/(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: episodeCounter++

            episodes[href] = newEpisode(href) {
                this.name = name.ifBlank { "Episode $epNum" }
                this.season = season
                this.episode = epNum
            }
        }

        return episodes.values
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 9999 })
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = listOf(
            selectFirst("h2.entry-title > a, h3.entry-title > a, .entry-title a, h2 a, h3 a")?.text(),
            selectFirst("a[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt")
        ).firstOrNull { it.isUsefulTitle() }
            ?.cleanTitle()
            ?.removePrefix("Nonton film ")
            ?.removeSuffix(" terbaru di Dutamovie21")
            ?.cleanTitle()
            ?: return null

        val href = fixUrlNull(
            selectFirst("h2.entry-title > a, h3.entry-title > a, .entry-title a, h2 a, h3 a, a[href]")?.attr("href")
        ) ?: return null

        if (isNavigationUrl(href)) return null

        val img = selectFirst("div.content-thumbnail img, img")
        val posterUrl = img?.getImageAttr()

        return newMovieSearchResponse(title, href, getTypeFromUrl(href, title, text())) {
            this.posterUrl = fixUrlNull(posterUrl)?.fixImageQuality()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val baseUrl = getBaseUrl(data)
        val document = app.get(data, headers = desktopHeaders).document
        val embedLinks = linkedSetOf<String>()
        var delivered = false

        collectDirectEmbeds(document, data, embedLinks)

        val postId = document.selectFirst("div#muvipro_player_content_id, [data-post], [data-postid], [data-id]")
            ?.let {
                it.attr("data-id")
                    .ifBlank { it.attr("data-post") }
                    .ifBlank { it.attr("data-postid") }
            }
            ?.takeIf { it.isNotBlank() }

        document.select(
            "ul.muvipro-player-tabs li a[href], " +
                "ul.gmr-player-nav li a[href], " +
                ".gmr-player-nav a[href], " +
                ".movie-player-nav a[href], " +
                ".player-tabs a[href], " +
                "a[data-url], a[data-src], a[data-iframe], a[data-link], a[data-href]"
        ).forEach { ele ->
            val raw = listOf(
                ele.attr("data-url"),
                ele.attr("data-src"),
                ele.attr("data-iframe"),
                ele.attr("data-link"),
                ele.attr("data-href"),
                ele.attr("href")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            val normalized = raw.trim()

            when {
                normalized.startsWith("#") -> {
                    document.selectFirst(normalized)
                        ?.let { collectDirectEmbeds(it, data, embedLinks) }
                }

                normalized.startsWith("http", true) || normalized.startsWith("//") -> {
                    val tabUrl = httpsify(fixUrl(normalized))
                    val iframe = runCatching {
                        val tabDoc = app.get(tabUrl, referer = data, headers = desktopHeaders).document
                        collectDirectEmbeds(tabDoc, tabUrl, embedLinks)
                        tabDoc.selectFirst("div.gmr-embed-responsive iframe, iframe, embed[src]")
                            ?.getIframeAttr()
                            ?.let { httpsify(it) }
                    }.getOrNull()

                    if (!iframe.isNullOrBlank()) embedLinks.add(iframe)
                }
            }
        }

        if (!postId.isNullOrBlank()) {
            document.select("div.tab-content-ajax[id], .tab-content[id], .player-content[id], [data-tab]")
                .forEach { ele ->
                    val tabId = ele.attr("id").ifBlank { ele.attr("data-tab") }.trim()
                    if (tabId.isBlank()) return@forEach

                    val iframe = runCatching {
                        app.post(
                            "$baseUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to tabId,
                                "post_id" to postId
                            ),
                            referer = data,
                            headers = ajaxHeaders(data, baseUrl)
                        ).document
                            .also { collectDirectEmbeds(it, data, embedLinks) }
                            .selectFirst("iframe, embed[src]")
                            ?.getIframeAttr()
                            ?.let { httpsify(it) }
                    }.getOrNull()

                    if (!iframe.isNullOrBlank()) embedLinks.add(iframe)
                }
        }

        embedLinks
            .map { it.trim() }
            .filter { it.isLikelyVideoHost() }
            .distinct()
            .take(24)
            .forEach { embed ->
                val success = runCatching {
                    loadExtractor(embed, data, subtitleCallback, callback)
                }.getOrDefault(false)

                if (success) delivered = true
            }

        return delivered
    }

    private fun collectDirectEmbeds(
        root: Element,
        pageUrl: String,
        output: MutableSet<String>
    ) {
        root.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "source[src], " +
                "video[src], " +
                "ul.gmr-download-list li a[href], " +
                ".gmr-download-list a[href], " +
                ".download a[href], " +
                "a[href*='/download/'], " +
                "a[href*='/dl/'], " +
                "a[href*='dood'], " +
                "a[href*='streamtape'], " +
                "a[href*='filemoon'], " +
                "a[href*='veev'], " +
                "a[href*='hglink'], " +
                "a[href*='hgcloud'], " +
                "a[href*='ghbrisk'], " +
                "a[href*='ryderjet'], " +
                "a[href*='movearnpre'], " +
                "a[href*='minochinos'], " +
                "a[href*='mivalyo'], " +
                "a[href*='bingezove'], " +
                "a[href*='dintezuvio'], " +
                "a[href*='dingtezuni'], " +
                "a[href*='p2pplay'], " +
                "a[href*='4meplayer'], " +
                "a[href*='embed4me'], " +
                "a[href*='upns.live']"
        ).forEach { element ->
            val raw = listOf(
                element.attr("data-litespeed-src"),
                element.attr("data-src"),
                element.attr("src"),
                element.attr("href")
            ).firstOrNull { it.isNotBlank() }?.trim()

            if (!raw.isNullOrBlank()) {
                output.add(httpsify(fixUrl(raw)))
            }
        }

        Regex("""(?:"|')((?:https?:)?//[^"']+?(?:m3u8|mp4|embed|download|/e/|/v/|/d/)[^"']*)(?:"|')""")
            .findAll(root.html())
            .map { it.groupValues[1].replace("\\/", "/").replace("&amp;", "&") }
            .filter { it.isLikelyVideoHost() || it.contains(".m3u8", true) || it.contains(".mp4", true) }
            .forEach { output.add(httpsify(fixUrl(it))) }
    }

    private fun getTypeFromUrl(url: String, title: String = "", text: String = ""): TvType {
        val combined = "$url $title $text"
        return when {
            url.contains("/animasi/", true) || url.contains("/anime/", true) -> TvType.Anime
            combined.contains("TV Show", true) -> TvType.TvSeries
            combined.contains("Eps:", true) || combined.contains("Episode", true) -> TvType.TvSeries
            url.contains("/tv/", true) -> TvType.TvSeries
            url.contains("/serial-tv", true) -> TvType.TvSeries
            url.contains("/eps/", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("""(?:Eps|Episode|Ep)\s*\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src")
            ?.takeIf { it.isNotBlank() }
            ?: this?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return replace(regex, "")
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ").trim()
    }

    private fun String?.isUsefulTitle(): Boolean {
        if (isNullOrBlank()) return false
        val clean = cleanTitle()
        return clean.length >= 2 &&
            !clean.equals("Home", true) &&
            !clean.equals("Next", true) &&
            !clean.equals("Previous", true) &&
            !clean.equals("Tonton", true) &&
            !clean.equals("Tonton Film", true) &&
            !clean.equals("Trailer", true)
    }

    private fun String.isLikelyVideoHost(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains("dood") ||
            lower.contains("streamtape") ||
            lower.contains("filemoon") ||
            lower.contains("veev") ||
            lower.contains("hglink") ||
            lower.contains("hgcloud") ||
            lower.contains("ghbrisk") ||
            lower.contains("ryderjet") ||
            lower.contains("movearnpre") ||
            lower.contains("minochinos") ||
            lower.contains("mivalyo") ||
            lower.contains("bingezove") ||
            lower.contains("dintezuvio") ||
            lower.contains("dingtezuni") ||
            lower.contains("p2pplay") ||
            lower.contains("4meplayer") ||
            lower.contains("embed4me") ||
            lower.contains("upns.live")
    }

    private fun isNavigationUrl(url: String): Boolean {
        return url.contains("/tag/", true) ||
            url.contains("/country/", true) ||
            url.contains("/genre/", true) ||
            url.contains("/category/", true) ||
            url.contains("/quality/", true) ||
            url.contains("/director/", true) ||
            url.contains("/cast/", true) ||
            url.contains("/year/", true) ||
            url.contains("youtube.com", true) ||
            url.contains("youtu.be", true)
    }

    private fun ajaxHeaders(pageUrl: String, baseUrl: String): Map<String, String> {
        return mapOf(
            "Referer" to pageUrl,
            "Origin" to baseUrl,
            "User-Agent" to USER_AGENT,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "*/*"
        )
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    private val cardSelector = listOf(
        "article.item",
        "article",
        ".item",
        ".gmr-box-content",
        ".content-thumbnail",
        ".movies-list .movie",
        ".post"
    ).joinToString(", ")

    private val titleAnchorSelector = listOf(
        "h2.entry-title > a[href]",
        "h3.entry-title > a[href]",
        ".entry-title a[href]",
        "h2 a[href]",
        "h3 a[href]",
        "a[title][href]"
    ).joinToString(", ")
}
