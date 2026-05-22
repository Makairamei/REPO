package com.gomunime

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Gomunime : MainAPI() {
    override var mainUrl = "https://gomunime.top"
    override var name = "Gomunime"
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
        "anime/?status=&type=&order=update&page=%d" to "Terbaru",
        "anime/?status=ongoing&type=&order=update&page=%d" to "Ongoing",
        "anime/?status=completed&type=&order=update&page=%d" to "Completed",
        "anime/?status=&type=&order=popular&page=%d" to "Popular",

        "anime/?status=&type=movie&order=update&page=%d" to "Movie",
        "anime/?status=&type=ova&order=update&page=%d" to "OVA",
        "anime/?status=&type=ona&order=update&page=%d" to "ONA",
        "anime/?status=&type=special&order=update&page=%d" to "Special",

        "genres/action/page/%d/" to "Action",
        "genres/adventure/page/%d/" to "Adventure",
        "genres/comedy/page/%d/" to "Comedy",
        "genres/demons/page/%d/" to "Demons",
        "genres/drama/page/%d/" to "Drama",
        "genres/ecchi/page/%d/" to "Ecchi",
        "genres/fantasy/page/%d/" to "Fantasy",
        "genres/game/page/%d/" to "Game",
        "genres/harem/page/%d/" to "Harem",
        "genres/historical/page/%d/" to "Historical",
        "genres/horror/page/%d/" to "Horror",
        "genres/isekai/page/%d/" to "Isekai",
        "genres/magic/page/%d/" to "Magic",
        "genres/martial-arts/page/%d/" to "Martial Arts",
        "genres/mecha/page/%d/" to "Mecha",
        "genres/military/page/%d/" to "Military",
        "genres/music/page/%d/" to "Music",
        "genres/mystery/page/%d/" to "Mystery",
        "genres/parody/page/%d/" to "Parody",
        "genres/psychological/page/%d/" to "Psychological",
        "genres/romance/page/%d/" to "Romance",
        "genres/school/page/%d/" to "School",
        "genres/sci-fi/page/%d/" to "Sci-Fi",
        "genres/seinen/page/%d/" to "Seinen",
        "genres/shoujo/page/%d/" to "Shoujo",
        "genres/shounen/page/%d/" to "Shounen",
        "genres/slice-of-life/page/%d/" to "Slice of Life",
        "genres/sports/page/%d/" to "Sports",
        "genres/super-power/page/%d/" to "Super Power",
        "genres/supernatural/page/%d/" to "Supernatural",
        "genres/thriller/page/%d/" to "Thriller",
        "genres/vampire/page/%d/" to "Vampire"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url).document

        val home = document.select(
            "div.listupd article, " +
                "div.listupd div.bs, " +
                "div.listupd .bs, " +
                "article.bs, " +
                ".bsx"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    ".pagination a:contains(Next), " +
                    ".pagination a:contains(Berikutnya), " +
                    "a.page-numbers:contains(»), " +
                    "a[href*='page=${page + 1}'], " +
                    "a[href*='/page/${page + 1}/']"
            ) != null
        )
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val formatted = data.format(page.coerceAtLeast(1))
        return when {
            formatted.startsWith("http", true) -> formatted
            formatted.startsWith("/") -> "$mainUrl$formatted"
            else -> "$mainUrl/$formatted"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            ".bsx > a[href], " +
                "a.tip[href], " +
                "a[href*='/anime/'], " +
                "a[href]"
        ) ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.contains("/anime/", true)) return null

        val title = listOf(
            selectFirst("div.tt")?.text()?.trim(),
            selectFirst("div.tt h2")?.text()?.trim(),
            selectFirst("h2")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("View All", true) &&
                !it.equals("Next", true) &&
                !it.equals("Anime", true)
        }?.cleanTitle() ?: return null

        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())

        val type = getTypeFromUrlOrTitle(href, title)

        return newAnimeSearchResponse(
            title,
            href,
            type
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")

        val endpoints = listOf(
            "$mainUrl/?s=$q",
            "$mainUrl/anime/?s=$q"
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in endpoints) {
            val document = runCatching {
                app.get(url).document
            }.getOrNull() ?: continue

            document.select(
                "div.listupd article, " +
                    "div.listupd div.bs, " +
                    "div.listupd .bs, " +
                    "article.bs, " +
                    ".bsx"
            ).mapNotNull { element ->
                element.toSearchResult()
            }.forEach { item ->
                results[item.url] = item
            }

            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1.entry-title, " +
                ".entry-title, " +
                "h1, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(
                "div.thumb img, " +
                    ".thumb img, " +
                    ".poster img, " +
                    "img.wp-post-image, " +
                    "meta[property=og:image]"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )

        val infoText = document.selectFirst(
            ".spe, " +
                ".info-content, " +
                ".entry-content"
        )?.text().orEmpty()

        val tags = document.select(
            "a[href*='/genres/'], " +
                ".genres a, " +
                ".genre a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".entry-content, " +
                ".desc, " +
                ".sinopsis, " +
                "meta[property=og:description]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val type = getType(infoText, url, title)
        val episodes = document.getEpisodes(url)

        return if (type == TvType.AnimeMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.showStatus = getStatus(infoText)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadGomunimeLinks(
            data,
            subtitleCallback,
            callback
        )
    }

    private fun Document.getEpisodes(url: String): List<Episode> {
        val slug = runCatching {
            URI(url).path
                .trim('/')
                .substringAfter("anime/")
                .substringBefore("/")
        }.getOrNull().orEmpty()

        val episodes = linkedMapOf<String, Episode>()

        select(
            "a[href*='$slug-episode-'], " +
                "a[href*='episode-'], " +
                "div.eplister ul li a[href], " +
                "ul.episodios li a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href]"
        ).forEachIndexed { index, a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEachIndexed

            val epNum = extractEpisodeNumber(a.text(), href) ?: index + 1

            val isValidEpisode = href.contains("-episode-", true) ||
                a.text().contains("episode", true) ||
                a.text().matches(Regex("""\d+"""))

            if (!isValidEpisode) return@forEachIndexed

            episodes[href] = newEpisode(href) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }

        return episodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.episode = 1
                        this.name = "Episode 1"
                    }
                )
            }
    }

    private fun getType(
        text: String?,
        url: String,
        title: String
    ): TvType {
        val value = "${text.orEmpty()} $url $title"

        return when {
            value.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) -> TvType.OVA
            value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getTypeFromUrlOrTitle(
        url: String,
        title: String
    ): TvType {
        return when {
            url.contains("movie", true) -> TvType.AnimeMovie
            title.contains("movie", true) -> TvType.AnimeMovie
            url.contains("ova", true) -> TvType.OVA
            title.contains("ova", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(text: String?): ShowStatus {
        val value = text.orEmpty()

        return when {
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""(?i)^nonton\s+"""), "")
            .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+sub\s+indo.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    data class ServerOption(
        val name: String,
        val url: String
    )
}