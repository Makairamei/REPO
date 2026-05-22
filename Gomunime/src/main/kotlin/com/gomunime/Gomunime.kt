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
        "__home__" to "Beranda",
        "status/ongoing?page=%d" to "Ongoing",
        "status/completed?page=%d" to "Tamat",
        "type/movie?page=%d" to "Movie",
        "type/ova?page=%d" to "OVA",
        "type/ona?page=%d" to "ONA",
        "type/special?page=%d" to "Special",
        "koleksi/anime-skor-mal-tertinggi?page=%d" to "Top Rated",

        "genre/fantasy?page=%d" to "Fantasy",
        "genre/action?page=%d" to "Action",
        "genre/comedy?page=%d" to "Comedy",
        "genre/shounen?page=%d" to "Shounen",
        "genre/romance?page=%d" to "Romance",
        "genre/adventure?page=%d" to "Adventure",
        "genre/school?page=%d" to "School",
        "genre/seinen?page=%d" to "Seinen",
        "genre/isekai?page=%d" to "Isekai",
        "genre/drama?page=%d" to "Drama",
        "genre/ecchi?page=%d" to "Ecchi",
        "genre/horror?page=%d" to "Horror",
        "genre/mystery?page=%d" to "Mystery",
        "genre/music?page=%d" to "Music",
        "genre/slice-of-life?page=%d" to "Slice of Life",
        "genre/sports?page=%d" to "Sports",
        "genre/supernatural?page=%d" to "Supernatural"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url).document

        val home = parseAnimeCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a:contains(Next), " +
                    "a:contains(Next »), " +
                    "a[href*='page=${page + 1}'], " +
                    ".pagination a[href*='page=${page + 1}']"
            ) != null
        )
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)

        if (data == "__home__") {
            return if (safePage <= 1) mainUrl else "$mainUrl?page=$safePage"
        }

        val formatted = data.format(safePage)

        return when {
            formatted.startsWith("http", true) -> formatted
            formatted.startsWith("/") -> "$mainUrl$formatted"
            else -> "$mainUrl/$formatted"
        }
    }

    private fun parseAnimeCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "a[href], " +
                "article a[href], " +
                ".grid a[href], " +
                ".card a[href], " +
                ".anime-card a[href], " +
                ".relative a[href]"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!isLikelyAnimeUrl(href)) return null

        val rawTitle = listOf(
            anchor.selectFirst("h2")?.text()?.trim(),
            anchor.selectFirst("h3")?.text()?.trim(),
            anchor.selectFirst(".title")?.text()?.trim(),
            anchor.selectFirst(".font-bold")?.text()?.trim(),
            anchor.attr("title").trim(),
            anchor.selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Ongoing", true) &&
                !it.equals("Tamat", true) &&
                !it.equals("Movies", true) &&
                !it.equals("Top Rated", true) &&
                !it.equals("Download App", true) &&
                !it.equals("Genre", true) &&
                !it.equals("Lihat Semua →", true) &&
                !it.equals("Next", true) &&
                !it.equals("Next »", true) &&
                !it.equals("« Previous", true)
        } ?: return null

        val title = rawTitle.extractAnimeTitle().cleanTitle()
            .takeIf { it.isNotBlank() }
            ?: return null

        if (title.length < 2) return null
        if (title.equals("Anime", true)) return null
        if (title.equals("Tonton", true)) return null

        val poster = fixUrlNull(anchor.selectFirst("img")?.getImageAttr())

        return newAnimeSearchResponse(
            title,
            href,
            getTypeFromUrlOrTitle(href, title)
        ) {
            this.posterUrl = poster
        }
    }

    private fun isLikelyAnimeUrl(url: String): Boolean {
        val path = runCatching {
            URI(url).path.trim('/')
        }.getOrNull().orEmpty()

        if (path.isBlank()) return false

        val blockedPrefixes = listOf(
            "status/",
            "type/",
            "genre/",
            "genres/",
            "koleksi/",
            "download",
            "about",
            "faq",
            "privacy",
            "dmca",
            "schedule",
            "bookmark",
            "search",
            "page/",
            "tag/",
            "studio/",
            "year/"
        )

        if (blockedPrefixes.any { path.startsWith(it, true) }) return false
        if (path.equals("anime", true)) return false
        if (path.equals("home", true)) return false
        if (path.contains("episode-", true)) return false

        return url.startsWith(mainUrl)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")

        val endpoints = listOf(
            "$mainUrl/search?q=$q",
            "$mainUrl/?s=$q",
            "$mainUrl/search/$q"
        )

        val results = linkedMapOf<String, SearchResponse>()

        for (url in endpoints) {
            val document = runCatching {
                app.get(url).document
            }.getOrNull() ?: continue

            parseAnimeCards(document).forEach { item ->
                results[item.url] = item
            }

            if (results.isNotEmpty()) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, " +
                "h1.entry-title, " +
                ".entry-title, " +
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
                "img[alt='$title'], " +
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

        val smallInfoText = document.select(
            ".badge-brand, " +
                ".badge-glass, " +
                ".badge-warning, " +
                ".spe span, " +
                ".info-content span, " +
                "a[href*='/type/'], " +
                "a[href*='/status/']"
        ).joinToString(" ") { it.text() }

        val tags = document.select(
            "a[href*='/genre/'], " +
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

        val episodes = document.getEpisodes(url)
        val type = getType(smallInfoText, url, title, episodes)

        val shouldBeMovie = type == TvType.AnimeMovie && episodes.size <= 1 && isRealMoviePage(
            url = url,
            title = title,
            smallInfo = smallInfoText
        )

        return if (shouldBeMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.AnimeMovie,
                episodes.firstOrNull()?.data ?: url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                type.takeIf { it != TvType.AnimeMovie } ?: TvType.Anime,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.showStatus = getStatus(smallInfoText)
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
        val episodes = linkedMapOf<String, Episode>()

        select(
            "section:contains(Pilih Episode) a[href], " +
                "a[href*='episode-'], " +
                "a:contains(Episode), " +
                "a:contains(Nonton Episode), " +
                "div.eplister ul li a[href], " +
                "ul.episodios li a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href]"
        ).forEachIndexed { index, a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEachIndexed

            val epNum = extractEpisodeNumber(a.text(), href) ?: index + 1

            val isValidEpisode = href.contains("episode-", true) ||
                a.text().contains("episode", true) ||
                a.text().contains("nonton episode", true) ||
                a.text().trim().matches(Regex("""\d+"""))

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
        smallInfo: String?,
        url: String,
        title: String,
        episodes: List<Episode>
    ): TvType {
        val value = "${smallInfo.orEmpty()} $url $title"

        if (episodes.size > 1) return TvType.Anime

        return when {
            value.contains("ova", true) -> TvType.OVA
            value.contains("special", true) -> TvType.OVA
            isRealMoviePage(url, title, smallInfo.orEmpty()) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun getTypeFromUrlOrTitle(
        url: String,
        title: String
    ): TvType {
        val value = "$url $title"

        return when {
            value.contains("ova", true) -> TvType.OVA
            value.contains("special", true) -> TvType.OVA
            value.contains("/type/movie", true) -> TvType.AnimeMovie
            title.contains(" movie ", true) -> TvType.AnimeMovie
            title.endsWith(" movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun isRealMoviePage(
        url: String,
        title: String,
        smallInfo: String
    ): Boolean {
        val path = runCatching {
            URI(url).path.trim('/')
        }.getOrNull().orEmpty()

        val value = "$smallInfo $title"

        return path.contains("movie", true) ||
            value.contains("Full Movie", true) ||
            Regex("""(^|\s)Movie(\s|$)""", RegexOption.IGNORE_CASE).containsMatchIn(smallInfo)
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
        return Regex("""(?:episode|eps?|ep|nonton episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
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

    private fun String.extractAnimeTitle(): String {
        var text = this
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (text.contains("Tonton ", true)) {
            text = text.substringAfter("Tonton ", text)
        }

        val markers = listOf(
            " TV •",
            " Movie •",
            " OVA •",
            " ONA •",
            " Special •",
            " TV ",
            " Movie ",
            " OVA ",
            " ONA ",
            " Special "
        )

        for (marker in markers) {
            if (text.contains(marker, true)) {
                text = text.substringBefore(marker)
                break
            }
        }

        text = text.replace(Regex("""^★\s*[\d.]+\s*"""), "")
        text = text.replace(Regex("""^(Ongoing|Completed)\s+""", RegexOption.IGNORE_CASE), "")

        return text.trim()
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