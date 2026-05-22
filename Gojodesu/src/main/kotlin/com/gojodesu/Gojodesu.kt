package com.gojodesu

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Gojodesu : MainAPI() {
    override var mainUrl = "https://gojodesu.com"
    override var name = "Gojodesu🤖"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "page/%d/" to "Latest Release",
        "anime/page/%d/?order=latest&status=&sub=&type=" to "Latest Anime",
        "anime/page/%d/?order=popular&status=&sub=&type=" to "Popular",
        "anime/page/%d/?order=rating&status=&sub=&type=" to "Rating",

        "genres/action/page/%d/" to "Action",
        "genres/adventure/page/%d/" to "Adventure",
        "genres/comedy/page/%d/" to "Comedy",
        "genres/drama/page/%d/" to "Drama",
        "genres/ecchi/page/%d/" to "Ecchi",
        "genres/fantasy/page/%d/" to "Fantasy",
        "genres/horror/page/%d/" to "Horror",
        "genres/mystery/page/%d/" to "Mystery",
        "genres/romance/page/%d/" to "Romance",
        "genres/sci-fi/page/%d/" to "Sci-Fi",
        "genres/slice-of-life/page/%d/" to "Slice of Life",
        "genres/sports/page/%d/" to "Sports",
        "genres/supernatural/page/%d/" to "Supernatural",
        "genres/suspense/page/%d/" to "Suspense",

        "season/spring-2026/page/%d/" to "Spring 2026",
        "season/winter-2026/page/%d/" to "Winter 2026",
        "season/fall-2025/page/%d/" to "Fall 2025",
        "season/summer-2025/page/%d/" to "Summer 2025",
        "season/spring-2025/page/%d/" to "Spring 2025",

        "anime/page/%d/?order=latest&status=ongoing&sub=&type=" to "Ongoing",
        "anime/page/%d/?order=latest&status=completed&sub=&type=" to "Completed",
        "anime/page/%d/?order=latest&status=&sub=&type=movie" to "Movie",
        "anime/page/%d/?order=latest&status=&sub=&type=ova" to "OVA",
        "anime/page/%d/?order=latest&status=&sub=&type=ona" to "ONA",
        "anime/page/%d/?order=latest&status=&sub=&type=special" to "Special"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = fixUrl(request.data.format(page.coerceAtLeast(1)))
        val document = app.get(url).document

        val items = document.select(
            "article, " +
                ".listupd article, " +
                ".bs article, " +
                ".postbody article, " +
                ".items article"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = document.selectFirst(
                "a.next, " +
                    ".pagination a:contains(Berikutnya), " +
                    ".pagination a:contains(Next), " +
                    "a.page-numbers:contains(»), " +
                    "a[href*='/page/${page + 1}/']"
            ) != null || items.isNotEmpty()
        )
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
                "article, " +
                    ".listupd article, " +
                    ".bs article, " +
                    ".postbody article, " +
                    ".items article"
            ).mapNotNull { it.toSearchResult() }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst(
            "h1.entry-title, " +
                "h1, " +
                ".entry-title"
        )?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: url.substringAfterLast("/").replace("-", " ")

        val title = rawTitle.cleanSeriesTitle()

        val poster = fixUrlNull(
            document.selectFirst(
                "img.wp-post-image, " +
                    ".thumb img, " +
                    ".poster img, " +
                    "meta[property=og:image]"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )

        val description = document.selectFirst(
            "div.entry-content p, " +
                "div.entry-content, " +
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

        val tags = document.select(
            "a[href*='/genres/'], " +
                ".genre a, " +
                ".genres a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document, title, url)

        return newTvSeriesLoadResponse(title, url, guessType(title, url, tags), episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, referer = mainUrl)
        val document = response.document
        val links = linkedSetOf<String>()
        var found = false

        extractM3u8Urls(response.text).forEach { m3u8 ->
            generateM3u8(
                source = name,
                streamUrl = m3u8,
                referer = data
            ).forEach(callback)

            found = true
        }

        document.select(
            "a[href*='kotakajaib.me'], " +
                "a[href*='kotakajaib'], " +
                "a:contains(Download)"
        ).forEach { a ->
            val href = a.attr("href").trim()
            if (href.isNotBlank()) {
                links.add(fixUrl(href))
            }
        }

        document.select("select.mirror option[value]:not([disabled])")
            .map { it.attr("value").trim() }
            .filter {
                it.isNotBlank() &&
                    !it.contains("Select Video Server", true)
            }
            .forEach {
                links.add(fixUrl(it))
            }

        document.select(
            "iframe[src], " +
                "embed[src], " +
                "source[src], " +
                "video[src]"
        ).forEach { element ->
            val src = element.attr("src").trim()
            if (src.isNotBlank()) {
                links.add(httpsify(src))
            }
        }

        links.forEach { link ->
            if (link.contains("kotakajaib", true)) {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
                found = true
                return@forEach
            }

            val linkResponse = runCatching {
                app.get(link, referer = data)
            }.getOrNull()

            val m3u8s = linkResponse?.text
                ?.let { extractM3u8Urls(it) }
                .orEmpty()

            if (m3u8s.isNotEmpty()) {
                m3u8s.forEach { m3u8 ->
                    generateM3u8(
                        source = name,
                        streamUrl = m3u8,
                        referer = link
                    ).forEach(callback)

                    found = true
                }
            } else {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
                found = true
            }
        }

        return found
    }

    private fun parseEpisodes(
        document: Document,
        seriesTitle: String,
        fallbackUrl: String
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select("a[href]")
            .forEach { element ->
                val href = element.attr("href").trim()
                if (href.isBlank()) return@forEach

                val absoluteUrl = fixUrl(href)
                val path = absoluteUrl.substringAfter(mainUrl).trim('/')

                val episodeNumber = extractEpisodeNumber(element.text(), absoluteUrl)
                    ?: return@forEach

                val isEpisodeUrl = Regex(
                    """(^|/)?.*-episode-\d+/?$""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(path)

                if (!isEpisodeUrl) return@forEach

                episodes[absoluteUrl] = newEpisode(absoluteUrl) {
                    this.name = "Episode $episodeNumber"
                    this.episode = episodeNumber
                }
            }

        return episodes.values
            .sortedBy { it.episode ?: 9999 }
            .ifEmpty {
                listOf(
                    newEpisode(fallbackUrl) {
                        this.name = seriesTitle
                        this.episode = 1
                    }
                )
            }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(
            "h2 a[href], " +
                "h3 a[href], " +
                ".entry-title a[href], " +
                "a[href]"
        ) ?: return null

        val href = fixUrl(anchor.attr("href").trim())

        val title = listOf(
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("View All", true) &&
                !it.equals("Next", true) &&
                !it.equals("Berikutnya", true)
        }?.cleanSearchTitle() ?: return null

        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())
        val type = guessType(title, href, emptyList())

        return if (type == TvType.AnimeMovie) {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                this.posterUrl = poster
            }
        }
    }

    private fun guessType(
        title: String,
        url: String,
        tags: List<String>
    ): TvType {
        return when {
            url.contains("movie", true) -> TvType.AnimeMovie
            title.contains("movie", true) -> TvType.AnimeMovie
            tags.any { it.equals("Movie", true) } -> TvType.AnimeMovie

            url.contains("ova", true) -> TvType.OVA
            title.contains("ova", true) -> TvType.OVA
            tags.any { it.equals("OVA", true) } -> TvType.OVA

            else -> TvType.Anime
        }
    }

    private fun extractM3u8Urls(text: String): List<String> {
        return Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""")
            .findAll(text)
            .map { it.value.cleanEscapedUrl() }
            .distinct()
            .toList()
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun extractEpisodeNumber(
        title: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)-?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.cleanEscapedUrl(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanSeriesTitle(): String {
        return this
            .replace(Regex("""(?i)^nonton\s+anime\s+"""), "")
            .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanSearchTitle(): String {
        return this
            .replace(Regex("""(?i)\s+episode\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+subtitle\s+indonesia.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}