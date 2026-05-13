package com.betbet.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class YunshanidProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "category/movie/page/%d/" to "Movie",
        "category/tv-series/page/%d/" to "TV Series",
        "category/anime/page/%d/" to "Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val path = if (page == 1) {
            request.data
                .replace("/page/%d/", "/")
                .replace("page/%d/", "")
        } else {
            request.data.format(page)
        }

        val document = app.get("$mainUrl/$path").document

        val homeList = document
            .select("article, .bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    homeList,
                    isHorizontalImages = false
                )
            ),
            hasNext = homeList.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = selectFirst(".tt, h2")
            ?.text()
            ?.trim()
            ?: return null

        val href = this@YunshanidProvider.fixUrl(
            selectFirst("a")?.attr("href")
                ?: return null
        )

        val poster = selectFirst("img")?.attr("src")

        val typeLabel = select(".type")
            .text()
            .lowercase()

        val type = when {
            typeLabel.contains("tv") ||
            typeLabel.contains("series") -> TvType.TvSeries

            typeLabel.contains("anime") ||
            typeLabel.contains("donghua") -> TvType.Anime

            else -> TvType.Movie
        }

        return if (type == TvType.Movie) {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = poster
            }

        } else {

            newAnimeSearchResponse(
                title,
                href,
                type
            ) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        return app.get("$mainUrl/?s=$query")
            .document
            .select("article, .bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun cleanupTitle(rawTitle: String): String {

        return rawTitle
            .replace(
                Regex(
                    "^(Nonton\\s+|Download\\s+)",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    "\\s+Sub\\s+Indo.*$",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val rawTitle = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?: "No Title"

        val title = cleanupTitle(rawTitle)

        val poster = document
            .selectFirst(".poster img, .thumb img")
            ?.attr("src")

        val plot = document
            .selectFirst(".entry-content p, .synopsis p")
            ?.text()

        val rating = document
            .selectFirst(".rating strong, .imdb-rating")
            ?.text()
            ?.toDoubleOrNull()

        val tags = document
            .select(".genredesc a, .genre a")
            .map { it.text().trim() }

        val episodes = document
            .select(".eplister li, .list-episode li")
            .mapNotNull {

                val epName = it
                    .select(".ep-num, .epl-num")
                    .text()
                    .ifBlank { "Episode" }

                val href = it
                    .selectFirst("a")
                    ?.attr("href")
                    ?: return@mapNotNull null

                val epHref = this@YunshanidProvider.fixUrl(href)

                val date = it
                    .select(".epl-date, .date")
                    .text()

                Episode(
                    epHref,
                    epName,
                    date = date
                )
            }

        return if (episodes.isEmpty()) {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.rating = rating?.toInt()
            }

        } else {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.reversed()
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.rating = rating?.toInt()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        var found = false

        val seen = Collections.synchronizedSet(
            hashSetOf<String>()
        )

        coroutineScope {

            val players = document.select(
                "iframe, .mirror-option option, .nav-tabs li a"
            )

            players.map { element ->

                async {

                    val src = when {

                        element.tagName() == "iframe" -> {
                            element.attr("src")
                        }

                        element.tagName() == "option" -> {
                            element.attr("value")
                        }

                        else -> {
                            element.attr("data-embed")
                        }
                    }

                    if (
                        src.isNotBlank() &&
                        src.startsWith("http") &&
                        seen.add(src)
                    ) {

                        runCatching {

                            loadExtractor(
                                src,
                                data,
                                subtitleCallback
                            ) { link ->

                                found = true
                                callback.invoke(link)
                            }
                        }
                    }
                }

            }.awaitAll()
        }

        return found
    }
}