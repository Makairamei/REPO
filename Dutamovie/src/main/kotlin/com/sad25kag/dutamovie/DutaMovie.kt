package com.sad25kag.dutamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Element

open class DutaMovie : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://malcontentgames.com"
    private var directUrl: String? = null
    override var name = "DutaMovie"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    

    override val mainPage =
            mainPageOf(
                    "movie/page/%d/" to "Movie",
                    "serial-tv/page/%d/" to "Serial TV",
                    "animasi/page/%d/" to "Animasi",
                    "category/box-office/page/%d/" to "Box Office",
                    "category/serial-tv/page/%d/" to "Serial TV Update",
                    "category/animation/page/%d/" to "Animation Update",

                    "action/page/%d/" to "Action",
                    "adventure/page/%d/" to "Adventure",
                    "comedy/page/%d/" to "Comedy",
                    "crime/page/%d/" to "Crime",
                    "drama/page/%d/" to "Drama",
                    "fantasy/page/%d/" to "Fantasy",
                    "horror/page/%d/" to "Horror",
                    "mystery/page/%d/" to "Mystery",
                    "romance/page/%d/" to "Romance",
                    "science-fiction/page/%d/" to "Science Fiction",
                    "thriller/page/%d/" to "Thriller",

                    "country/indonesia/page/%d/" to "Indonesia",
                    "country/korea/page/%d/" to "Korea",
                    "country/japan/page/%d/" to "Japan",
                    "country/china/page/%d/" to "China",
                    "country/india/page/%d/" to "India",
                    "country/thailand/page/%d/" to "Thailand",
                    "country/philippines/page/%d/" to "Philippines",
                    "country/usa/page/%d/" to "USA",
                    "country/united-kingdom/page/%d/" to "United Kingdom",
                    "country/canada/page/%d/" to "Canada",
                    "country/australia/page/%d/" to "Australia",
                    "country/hong-kong/page/%d/" to "Hong Kong",
                    "country/ireland/page/%d/" to "Ireland",
                    "country/new-zealand/page/%d/" to "New Zealand",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Popup dinonaktifkan agar homepage provider tidak mengganggu navigasi.
        // context?.let { StarPopupHelper.showStarPopupIfNeeded(it) }
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val ratingText = this.selectFirst("div.gmr-rating-item")?.ownText()?.trim()
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality =
                this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            val episode =
                    Regex("Episode\\s?([0-9]+)")
                            .find(title)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                            ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encodedQuery = URLEncoder.encode(keyword, "UTF-8")
        val document =
                app.get("${mainUrl}?s=$encodedQuery&post_type[]=post&post_type[]=tv", timeout = 50L)
                        .document
        val results = document.select("article.item").mapNotNull { it.toSearchResult() }
        return results.distinctBy { it.url }
    }

    private fun Element.toRecommendResult(): SearchResponse? {

    // Ambil judul dari <h2 class="entry-title"><a>
    val title = selectFirst("h2.entry-title > a")
        ?.text()
        ?.trim()
        ?: return null

    // Ambil link dari anchor di entry-title
    val href = selectFirst("h2.entry-title > a")
        ?.attr("href")
        ?.trim()
        ?: return null

    // Poster dari elemen img di content-thumbnail
    val img = selectFirst("div.content-thumbnail img")
    val posterUrl =
        img?.attr("src")
            ?.ifBlank { img.attr("data-src") }
            ?.ifBlank { img.attr("srcset")?.split(" ")?.firstOrNull() }

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = fixUrlNull(posterUrl)
    }
}


    override suspend fun load(url: String): LoadResponse {
    // Pakai Desktop User-Agent agar website tidak mengirim halaman mobile
    val desktopHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    val fetch = app.get(url, headers = desktopHeaders)
    val document = fetch.document

    val title =
        document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.trim()
            .orEmpty()

    val poster =
        fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()

    val tags = document.select("strong:contains(Genre) ~ a").eachText()

    val year =
        document.select("div.gmr-moviedata strong:contains(Year:) > a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

    val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
    val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
    val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
    val rating =
        document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
            ?.text()?.trim()

    val actors =
        document.select("div.gmr-moviedata").last()
            ?.select("span[itemprop=actors]")?.map {
                it.select("a").text()
            }

    val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
        ?.text()
        ?.replace(Regex("\\D"), "")
        ?.toIntOrNull()

    val recommendations = document
    .select("article.item.col-md-20")
    .mapNotNull { it.toRecommendResult() }


    // =========================
    //  MOVIE
    // =========================

    if (tvType == TvType.Movie) {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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


    // =========================
    //  TV SERIES MODE
    // =========================

    // Tombol “View All Episodes” → URL halaman series
    val seriesUrl =
        document.selectFirst("a.button.button-shadow.active")?.attr("href")
            ?: url.substringBefore("/eps/")

    val seriesDoc = app.get(seriesUrl, headers = desktopHeaders).document

    val episodeElements =
        seriesDoc.select("div.gmr-listseries a.button.button-shadow")

    // Nomor episode manual (agar tidak lompat)
    var episodeCounter = 1

    val episodes = episodeElements.mapNotNull { eps ->
        val href = fixUrl(eps.attr("href")).trim()
        val name = eps.text().trim()

        // Skip tombol "View All Episodes"
        if (name.contains("View All Episodes", ignoreCase = true)) return@mapNotNull null

        // Skip jika href sama dengan halaman series
        if (href == seriesUrl) return@mapNotNull null

        // Skip elemen non-episode
        if (!name.contains("Eps", ignoreCase = true)) return@mapNotNull null

        // Ambil season (default 1)
        val regex = Regex("""S(\d+)\s*Eps""", RegexOption.IGNORE_CASE)
        val match = regex.find(name)
        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        // Nomor episode final
        val epNum = episodeCounter++

        newEpisode(href) {
            this.name = name
            this.season = season
            this.episode = epNum
        }
    }

    // Return response TV Series
    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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



    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val baseUrl = getBaseUrl(data)
    directUrl = baseUrl

    val document = app.get(data).document
    val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")
    var delivered = false

    // 🎬 Ambil iframe player dari tab biasa
    if (id.isNullOrEmpty()) {
        for (ele in document.select("ul.muvipro-player-tabs li a[href]")) {
            val tabUrl = fixUrl(ele.attr("href"))
            val iframe = app.get(tabUrl)
                .document
                .selectFirst("div.gmr-embed-responsive iframe, iframe")
                ?.getIframeAttr()
                ?.let { httpsify(it) }
                ?: continue

            val success = loadExtractor(iframe, "$baseUrl/", subtitleCallback, callback)
            if (success) delivered = true
        }
    } else {
        // 🎬 Ambil iframe player dari AJAX muvipro
        for (ele in document.select("div.tab-content-ajax[id]")) {
            val tabId = ele.attr("id").trim()
            if (tabId.isBlank()) continue

            val server = app.post(
                "$baseUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to tabId,
                    "post_id" to id
                ),
                headers = mapOf(
                    "Referer" to data,
                    "Origin" to baseUrl,
                    "User-Agent" to USER_AGENT,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).document
                .selectFirst("iframe")
                ?.getIframeAttr()
                ?.let { httpsify(it) }
                ?: continue

            val success = loadExtractor(server, "$baseUrl/", subtitleCallback, callback)
            if (success) delivered = true
        }
    }

    // Fallback download links
    document.select("ul.gmr-download-list li a[href], a[href*='/download/'], a[href*='/dl/']").forEach { linkEl ->
        val downloadUrl = linkEl.attr("href").trim()
        if (downloadUrl.isNotBlank()) {
            val success = loadExtractor(fixUrl(downloadUrl), data, subtitleCallback, callback)
            if (success) delivered = true
        }
    }

    return delivered
}



    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }
}
