package com.sad25kag.filmapik

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Filmapik : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://filmapik.fitness"
    override var name = "FilmApik🎃"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "category/box-office/page/%d/" to "Box Office",
        "latest/page/%d/" to "Film Terbaru",
        "tvshows/page/%d/" to "Serial Terbaru",

        "category/action/page/%d/" to "Action",
        "category/comedy/page/%d/" to "Comedy",
        "category/drama/page/%d/" to "Drama",
        "category/thriller/page/%d/" to "Thriller",
        "category/horror/page/%d/" to "Horror",
        "category/family/page/%d/" to "Family",
        "category/crime/page/%d/" to "Crime",
        "category/adventure/page/%d/" to "Adventure",
        "category/science-fiction/page/%d/" to "Science Fiction",

        "tvshows-genre/k-drama/page/%d/" to "Drama Korea",
        "tvshows-genre/west-series/page/%d/" to "West Series",
        "tvshows-genre/drama-mandarin/page/%d/" to "Drama Mandarin",
        "tvshows-genre/drama-india/page/%d/" to "Drama India",
        "tvshows-genre/drama-singapore/page/%d/" to "Drama Singapore",
        "tvshows-genre/drama-jepang/page/%d/" to "Drama Jepang",
        "tvshows-genre/drama-thailand/page/%d/" to "Drama Thailand",
        "tvshows-genre/drama-lainnya/page/%d/" to "Drama Lainnya",

        "country/usa/page/%d/" to "USA",
        "country/united-states/page/%d/" to "United States",
        "country/japan/page/%d/" to "Japan",
        "country/canada/page/%d/" to "Canada",
        "country/france/page/%d/" to "France",
        "country/india/page/%d/" to "India",
        "country/germany/page/%d/" to "Germany",
        "country/south-korea/page/%d/" to "South Korea",

        "release-year/2026/page/%d/" to "Tahun 2026",
        "release-year/2025/page/%d/" to "Tahun 2025",
        "release-year/2024/page/%d/" to "Tahun 2024",
        "release-year/2023/page/%d/" to "Tahun 2023",
        "release-year/2022/page/%d/" to "Tahun 2022",
        "release-year/2021/page/%d/" to "Tahun 2021",
        "release-year/2020/page/%d/" to "Tahun 2020"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data.format(page)}"
        val document = app.get(url).document

        val items = document.select(
            "div.items.normal article.item, " +
                "div.items article.item, " +
                "article.item, " +
                "div.result-item article, " +
                "div.result-item, " +
                "div.movies article, " +
                "div#archive-content article"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a.nextpostslink, " +
                    ".pagination a:contains(Next), " +
                    ".pagination a.next"
            ) != null || items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")

        val endpoints = listOf(
            "$mainUrl/?s=$q",
            "$mainUrl?s=$q",
            "$mainUrl/?s=$q&post_type[]=post&post_type[]=tv"
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in endpoints) {
            val document = runCatching { app.get(url).document }.getOrNull() ?: continue

            document.select(
                "div.result-item article, " +
                    "div.result-item, " +
                    "article.item, " +
                    "div.items article.item, " +
                    "div.search-page article"
            ).mapNotNull { it.toSearchResult() }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1[itemprop=name], " +
                ".sheader h1, " +
                ".sheader h2, " +
                "h1.entry-title, " +
                "#info h2"
        )?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst(
            ".sheader .poster img, " +
                ".poster img, " +
                "img[itemprop=image], " +
                "meta[property=og:image]"
        )?.let { el ->
            when {
                el.hasAttr("content") -> el.attr("content")
                el.hasAttr("data-src") -> el.attr("data-src")
                el.hasAttr("data-lazy-src") -> el.attr("data-lazy-src")
                else -> el.attr("src")
            }
        }?.let { fixUrl(it) }
            ?.fixImageQuality()

        val tags = document.select(
            "span.sgeneros a, " +
                ".sgeneros a, " +
                ".genres a, " +
                "a[href*=/category/]"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select(
            ".info-more span.tagline, " +
                ".cast, " +
                ".actors"
        ).firstOrNull {
            it.text().contains("Actors", true) ||
                it.text().contains("Stars", true) ||
                it.text().contains("Cast", true)
        }?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val description = document.selectFirst(
            "div[itemprop=description], " +
                ".wp-content, " +
                ".entry-content, " +
                ".desc, " +
                ".entry, " +
                ".storyline"
        )?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Tidak ada deskripsi."

        val pageText = document.selectFirst(
            "#info, " +
                ".sheader, " +
                ".extra, " +
                ".data, " +
                ".info-more"
        )?.text().orEmpty()

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(pageText)
            ?.value
            ?.toIntOrNull()

        val rating = document.selectFirst(
            "#repimdb strong, " +
                ".imdb_r .a, " +
                ".rating, " +
                ".imdb, " +
                "[itemprop=ratingValue]"
        )?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()

        val duration = document.selectFirst(
            "span.runtime, " +
                ".runtime, " +
                ".duration"
        )?.text()
            ?.let { Regex("""(\d+)""").find(it)?.value }
            ?.toIntOrNull()

        val recommendations = document.select(
            "#single_relacionados article, " +
                ".related article, " +
                ".owl-item article"
        ).mapNotNull { it.toRecommendResult() }
            .distinctBy { it.url }

        val episodes = parseEpisodes(document)

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                addActors(actors)
                this.plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                rating?.let { this.score = Score.from10(it) }
            }
        }

        val playUrl = document.selectFirst(
            "#clickfakeplayer[href], " +
                ".fakeplayer a[href], " +
                "a[href*=/player/], " +
                "a[href*=/stream/]"
        )?.attr("href")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }

        return newMovieLoadResponse(title, playUrl ?: url, TvType.Movie, playUrl ?: url) {
            this.posterUrl = poster
            this.year = year
            addActors(actors)
            this.plot = description
            this.tags = tags
            this.duration = duration ?: 0
            this.recommendations = recommendations
            rating?.let { this.score = Score.from10(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCaster: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val links = linkedSetOf<String>()

        document.select(
            "div.pframe iframe[src], " +
                ".pframe iframe[src], " +
                "iframe[src], " +
                "embed[src]"
        ).forEach { iframe ->
            iframe.attr("src")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { links.add(fixUrl(it)) }
        }

        document.select(
            "li.dooplay_player_option[data-url], " +
                ".dooplay_player_option[data-url]"
        ).forEach { option ->
            option.attr("data-url")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { links.add(fixUrl(it)) }
        }

        document.select(
            "div#download a[href], " +
                "#download a[href], " +
                "a.myButton[href], " +
                "a[href*='buzzheavier.com'], " +
                "a[href*='strp2p.site']"
        ).forEach { a ->
            a.attr("href")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { links.add(fixUrl(it)) }
        }

        links.forEach { rawUrl ->
            val resolvedUrl = runCatching { resolveIframe(rawUrl) }.getOrDefault(rawUrl)
            loadExtractor(resolvedUrl, data, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val seasonBlocks = document.select(
            "#seasons .se-c, " +
                ".seasons .se-c"
        )

        if (seasonBlocks.isNotEmpty()) {
            seasonBlocks.forEach { block ->
                val seasonNumber = block.selectFirst(
                    ".se-q .se-t, " +
                        ".season-number"
                )?.text()
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull()
                    ?: 1

                block.select(
                    ".se-a ul.episodios li a[href], " +
                        "ul.episodios li a[href]"
                ).forEachIndexed { index, ep ->
                    val epUrl = fixUrl(ep.attr("href"))

                    val epName = ep.text()
                        .trim()
                        .ifBlank { "Episode ${index + 1}" }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = index + 1
                        }
                    )
                }
            }
        } else {
            document.select(
                "ul.episodios li a[href], " +
                    ".episodios a[href], " +
                    ".episode-list a[href]"
            ).forEachIndexed { index, ep ->
                val epUrl = fixUrl(ep.attr("href"))

                val epName = ep.text()
                    .trim()
                    .ifBlank { "Episode ${index + 1}" }

                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        }

        return episodes.distinctBy { it.data }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = selectFirst(
            "div.details div.title a[href], " +
                "div.data h3 a[href], " +
                "h3 a[href], " +
                "h2 a[href], " +
                ".title a[href], " +
                "a[href][title]"
        )

        val anchor = titleAnchor ?: selectFirst(
            "div.thumbnail a[href], " +
                "div.image div.thumbnail a[href], " +
                ".poster a[href], " +
                "a[href]"
        ) ?: return null

        val href = anchor.attr("href")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        val rawTitle = listOf(
            titleAnchor?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("div.details div.title")?.text()?.trim(),
            selectFirst("div.data h3")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("movies", true) &&
                !it.equals("movie", true) &&
                !it.equals("tv-shows", true) &&
                !it.equals("tvshows", true) &&
                !it.equals("lihat lebih", true)
        } ?: return null

        val title = rawTitle.cleanTitle().ifBlank { return null }

        val poster = selectFirst(
            "img[src], " +
                "img[data-src], " +
                "img[data-lazy-src]"
        )?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                else -> img.attr("src")
            }
        }?.let { fixUrl(it) }
            ?.fixImageQuality()

        val rating = selectFirst(
            "div.rating, " +
                ".imdb, " +
                ".tmdb, " +
                ".score"
        )?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()

        val typeText = text()

        val type = when {
            selectFirst("span.tvshows, span.tv, .tvshows, .tv-show") != null -> TvType.TvSeries
            href.contains("/tvshows/", true) -> TvType.TvSeries
            href.contains("/series/", true) -> TvType.TvSeries
            typeText.contains("Ep.", true) -> TvType.TvSeries
            typeText.contains("S.", true) && typeText.contains("Ep", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null

        val image = selectFirst(
            "img[src], " +
                "img[data-src], " +
                "img[data-lazy-src]"
        )

        val href = anchor.attr("href")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: return null

        val title = listOf(
            image?.attr("alt")?.trim(),
            anchor.attr("title").trim(),
            anchor.text().trim()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = image?.let { img ->
            when {
                img.hasAttr("data-src") -> img.attr("data-src")
                img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                else -> img.attr("src")
            }
        }?.let { fixUrl(it) }
            ?.fixImageQuality()

        val type = if (href.contains("/tvshows/", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private suspend fun resolveIframe(url: String, depth: Int = 0): String {
        if (depth >= 5) return url

        val response = app.get(url, allowRedirects = true)
        val document = response.document

        document.selectFirst("iframe[src], embed[src]")
            ?.attr("src")
            ?.trim()
            ?.let {
                if (it.isNotBlank()) {
                    return resolveIframe(fixUrl(it), depth + 1)
                }
            }

        document.select(
            "meta[http-equiv=refresh], " +
                "meta[http-equiv=Refresh]"
        ).forEach { meta ->
            val refreshUrl = meta.attr("content")
                .substringAfter("URL=", "")
                .ifBlank { meta.attr("content").substringAfter("url=", "") }
                .trim()

            if (refreshUrl.isNotBlank()) {
                return resolveIframe(fixUrl(refreshUrl), depth + 1)
            }
        }

        val scripts = document.select("script").html()

        val scriptPatterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""window\.location\s*=\s*["']([^"']+)["']"""),
            Regex("""window\.location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""src:\s*["']([^"']+)["']""")
        )

        for (regex in scriptPatterns) {
            val match = regex.find(scripts) ?: continue
            val scriptUrl = match.groupValues.getOrNull(1)?.trim().orEmpty()

            if (scriptUrl.isNotBlank() && scriptUrl.startsWith("http")) {
                return resolveIframe(scriptUrl, depth + 1)
            }
        }

        return response.url
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null

        val match = Regex("""-\d*x\d*(?=\.)""")
            .find(this)
            ?.value

        return if (match != null) {
            this.replace(match, "")
        } else {
            this
        }
    }

    private fun fixUrl(url: String): String {
        val cleanUrl = url.trim()

        return when {
            cleanUrl.startsWith("http", true) -> cleanUrl
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }
}