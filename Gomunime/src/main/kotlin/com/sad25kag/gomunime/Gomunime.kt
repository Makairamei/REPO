package com.sad25kag.gomunime

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

const val HOME_URL = "https://gomunime.top"

class Gomunime : MainAPI() {
    override var mainUrl = HOME_URL
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
        "status/ongoing" to "Ongoing",
        "status/completed" to "Tamat",
        "type/movie" to "Movie",
        "type/ova" to "OVA",
        "type/ona" to "ONA",
        "type/special" to "Special",
        "koleksi/anime-skor-mal-tertinggi" to "Top Rated",
        "genre/fantasy" to "Fantasy",
        "genre/action" to "Action",
        "genre/comedy" to "Comedy",
        "genre/shounen" to "Shounen",
        "genre/romance" to "Romance",
        "genre/adventure" to "Adventure",
        "genre/school" to "School",
        "genre/seinen" to "Seinen",
        "genre/isekai" to "Isekai",
        "genre/drama" to "Drama",
        "genre/adult-cast" to "Adult Cast",
        "genre/supernatural" to "Supernatural",
        "genre/reincarnation" to "Reincarnation",
        "genre/sci-fi" to "Sci-Fi",
        "genre/suspense" to "Suspense",
        "genre/historical" to "Historical",
        "genre/military" to "Military",
        "genre/shoujo" to "Shoujo",
        "genre/slice-of-life" to "Slice of Life",
        "genre/mystery" to "Mystery",
        "genre/ecchi" to "Ecchi",
        "genre/horror" to "Horror",
        "genre/music" to "Music",
        "genre/sports" to "Sports",
        "genre/martial-arts" to "Martial Arts",
        "genre/cultivation" to "Cultivation"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = headers, referer = mainUrl, timeout = 18L).document
        val items = document.parseCards().distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = document.hasNextPage(page)
        )
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val safePage = page.coerceAtLeast(1)
        if (data == "__home__") return if (safePage == 1) mainUrl else "$mainUrl?page=$safePage"
        if (data.startsWith("http", ignoreCase = true)) return data

        val path = data.trim('/').substringBefore("?")
        val query = data.substringAfter("?", "")
        val base = "$mainUrl/$path"

        return when {
            safePage <= 1 && query.isBlank() -> base
            safePage <= 1 -> "$base?$query"
            query.isBlank() -> "$base?page=$safePage"
            else -> "$base?$query&page=$safePage"
        }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst(
            "a[rel=next], " +
                "a.next, " +
                ".pagination a:contains(Next), " +
                "a[href*='page=${page + 1}'], " +
                "a[href*='/page/${page + 1}']"
        ) != null
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            "article:has(a[href])",
            ".grid a[href]:has(img)",
            ".card:has(a[href])",
            ".anime-card:has(a[href])",
            ".swiper-slide:has(a[href])",
            "a[href]:has(img)",
            "main a[href]"
        )

        selectors.forEach { selector ->
            select(selector).forEach { element ->
                element.toSearchResult()?.let { item -> results[item.url] = item }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("a[href]:has(img), h2 a[href], h3 a[href], a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrlOrNull() ?: return null
        if (!href.startsWith(mainUrl, ignoreCase = true)) return null
        if (isNavigationUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val rawTitle = listOfNotNull(
            selectFirst("h1, h2, h3, .title, .entry-title, .name, .anime-title")?.ownText(),
            selectFirst("h1, h2, h3, .title, .entry-title, .name, .anime-title")?.text(),
            anchor.attr("title"),
            image?.imageAttr("alt"),
            anchor.text(),
            href.titleFromSlug()
        ).firstOrNull { it.isNotBlank() } ?: return null

        val title = rawTitle.cleanCardTitle(href)
            .takeIf { it.length >= 2 && !it.isUiNoise() }
            ?: href.titleFromSlug().cleanCardTitle(href).takeIf { it.length >= 2 && !it.isUiNoise() }
            ?: return null

        return newAnimeSearchResponse(title, href, guessTypeFromUrlOrTitle(href, title)) {
            posterUrl = image?.imageAttr()?.absoluteUrlOrNull()
        }
    }

    private fun isNavigationUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl, "").substringBefore("?").trim('/').lowercase()
        if (path.isBlank()) return true

        val blockedExact = setOf(
            "download",
            "manifest.json",
            "favicon.ico",
            "firebase-messaging-sw.js",
            "sw.js"
        )
        if (path in blockedExact) return true

        val blockedPrefixes = listOf(
            "genre/", "genres/", "status/", "type/", "koleksi/", "tag/", "tags/", "studio/", "search",
            "page/", "api/", "storage/", "images/", "icons/", "build/", "login", "register", "privacy", "dmca", "contact"
        )
        return blockedPrefixes.any { path == it.trimEnd('/') || path.startsWith(it) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/anime?keyword=$encoded",
            "$mainUrl/anime?search=$encoded"
        )

        for (url in attempts) {
            val document = runCatching {
                app.get(url, headers = headers, referer = mainUrl, timeout = 15L).document
            }.getOrNull() ?: continue

            val results = document.parseCards()
                .filter { it.name.contains(keyword, ignoreCase = true) || it.url.contains(keyword.slugify(), ignoreCase = true) }
                .distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, referer = mainUrl, timeout = 18L).document
        val isEpisodePage = url.isEpisodeUrl()
        val animeUrl = document.select("nav a[href], .breadcrumb a[href], [aria-label*=breadcrumb] a[href]")
            .mapNotNull { it.attr("href").absoluteUrlOrNull() }
            .firstOrNull { candidate ->
                candidate.startsWith(mainUrl, ignoreCase = true) &&
                    !candidate.isEpisodeUrl() &&
                    !isNavigationUrl(candidate)
            }
            ?: url.substringBeforeLast("-episode-", missingDelimiterValue = url)

        val title = document.readTitle(url)
            .removeSuffixEpisodeIfNeeded(isEpisodePage)
            .ifBlank { animeUrl.titleFromSlug().removeSuffixEpisodeIfNeeded(isEpisodePage) }

        val poster = document.selectFirst(
            "meta[property=og:image], meta[name=twitter:image], .poster img, .thumb img, img.wp-post-image, main img"
        )?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.imageAttr()
        }?.absoluteUrlOrNull()

        val infoText = document.select(
            ".badge-brand, .badge-glass, .badge-warning, .spe, .info-content, " +
                "a[href*='/type/'], a[href*='/status/']"
        ).joinToString(" ") { it.text() }

        val tags = document.select("a[href*='/genre/'], a[href*='/genres/'], .genres a, .genre a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.isUiNoise() }
            .distinct()

        val plot = document.selectFirst(
            "meta[property=og:description], meta[name=description], .entry-content p, .entry-content, .desc, .sinopsis, main p"
        )?.let { element ->
            if (element.hasAttr("content")) element.attr("content") else element.text()
        }?.cleanPlot()

        val episodes = document.getEpisodes(url)
        val type = getType(infoText, url, title, episodes)
        val isMovie = type == TvType.AnimeMovie && episodes.size <= 1 && !isEpisodePage

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, animeUrl, type.takeIf { it != TvType.AnimeMovie } ?: TvType.Anime, episodes) {
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
        return loadGomunimeLinks(data, subtitleCallback, callback)
    }

    private fun Document.getEpisodes(currentUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        select(
            "section:contains(Pilih Episode) a[href], div:contains(Pilih Episode) a[href], " +
                "#episode-list a[href], .episode-list a[href], .episodelist a[href], .eplister a[href], " +
                "ul.episodios li a[href], a[href*='-episode-'], a:contains(Episode), a:contains(Nonton Episode)"
        ).forEachIndexed { index, anchor ->
            val href = anchor.attr("href").absoluteUrlOrNull() ?: return@forEachIndexed
            if (!href.startsWith(mainUrl, ignoreCase = true) || !href.isEpisodeUrl()) return@forEachIndexed

            val text = anchor.text().trim()
            val epNum = extractEpisodeNumber(text, href) ?: index + 1
            val epTitle = text.cleanEpisodeName(epNum).ifBlank { "Episode $epNum" }

            episodes[href] = newEpisode(href) {
                episode = epNum
                name = epTitle
            }
        }

        if (episodes.isEmpty() && currentUrl.isEpisodeUrl()) {
            val epNum = extractEpisodeNumber("", currentUrl) ?: 1
            episodes[currentUrl] = newEpisode(currentUrl) {
                episode = epNum
                name = "Episode $epNum"
            }
        }

        return episodes.values
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(newEpisode(currentUrl) {
                    episode = 1
                    name = "Episode 1"
                })
            }
    }

    private fun extractEpisodeNumber(text: String, url: String): Int? {
        val source = "$text $url"
        return Regex("""(?i)episode[-\s]*(\d+)""")
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?i)-episode-(\d+)""")
                .find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: text.trim().toIntOrNull()
    }

    private fun getType(info: String?, url: String, title: String, episodes: List<Episode>): TvType {
        val value = "${info.orEmpty()} $url $title"
        if (episodes.size > 1) return TvType.Anime
        return when {
            value.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) -> TvType.OVA
            value.contains("ona", true) -> TvType.OVA
            value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun guessTypeFromUrlOrTitle(url: String, title: String): TvType {
        val value = "$url $title"
        return when {
            value.contains("/type/movie", true) || value.contains(" movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("ona", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(text: String?): ShowStatus? {
        val value = text.orEmpty()
        return when {
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("completed", true) || value.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun Document.readTitle(url: String): String {
        return selectFirst("h1, h1.entry-title, .entry-title, meta[property=og:title], meta[name=twitter:title]")
            ?.let { element -> if (element.hasAttr("content")) element.attr("content") else element.text() }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.titleFromSlug().cleanTitle()
    }

    private fun Element.imageAttr(attrName: String? = null): String? {
        if (!attrName.isNullOrBlank()) return attr(attrName).trim().takeIf { it.isNotBlank() }
        return attr("data-src")
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("src") }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun String.absoluteUrlOrNull(): String? {
        val clean = trim()
        if (clean.isBlank() || clean == "#") return null
        return when {
            clean.startsWith("http", ignoreCase = true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> runCatching { URI(mainUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun String.titleFromSlug(): String {
        val slug = substringBefore("?")
            .substringAfterLast('/')
            .replace(Regex("""(?i)-sub-indo.*$"""), "")
        return slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> token.replaceFirstChar { char -> char.uppercase() } }
            .cleanTitle()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)\s*\|\s*Gomunime.*$"""), "")
            .replace(Regex("""(?i)\bNonton\b"""), "")
            .replace(Regex("""(?i)\bSub(?:title)?\s*Indo(?:nesia)?\b"""), "")
            .replace(Regex("""(?i)\bHD\b"""), "")
            .replace(Regex("""(?i)\bdi\s+Gomunime\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '—', '|')
    }

    private fun String.cleanCardTitle(url: String): String {
        val slugTitle = url.titleFromSlug()
        val cleaned = cleanTitle()
            .replace(Regex("""(?i)^new\s+episode\s+\d+\s*"""), "")
            .replace(Regex("""^[★⭐]?\s*\d+(?:\.\d+)?\s*"""), "")
            .replace(Regex("""(?i)\b(?:ongoing|completed|tv|movie|ova|ona)\b\s*"""), "")
            .replace(Regex("""(?i)\b\d{4}\b\s*"""), "")
            .replace(Regex("""(?i)\b\d+\s*eps?\b\s*"""), "")
            .replace(Regex("""(?i)^tonton\s+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return when {
            cleaned.length > 90 -> slugTitle
            cleaned.count { it == ' ' } > 12 && slugTitle.isNotBlank() -> slugTitle
            else -> cleaned
        }
    }

    private fun String.removeSuffixEpisodeIfNeeded(isEpisodePage: Boolean): String {
        return if (isEpisodePage) {
            replace(Regex("""(?i)\s+episode\s+\d+\s*$"""), "").trim()
        } else {
            this
        }
    }

    private fun String.cleanEpisodeName(epNum: Int): String {
        return replace(Regex("""(?i)^episode\s+"""), "Episode ")
            .replace(Regex("""\s+\d{1,2}\s+[A-Za-z]+\s+\d{4}\s*$"""), "")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: "Episode $epNum"
    }

    private fun String.cleanPlot(): String? {
        return cleanTitle()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.length > 20 && !it.isUiNoise() }
    }

    private fun String.slugify(): String {
        return lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
    }

    private fun String.isEpisodeUrl(): Boolean = Regex("""(?i)-episode-\d+""").containsMatchIn(this)

    private fun String.isUiNoise(): Boolean {
        val clean = trim().lowercase()
        return clean.isBlank() || clean in setOf(
            "home", "ongoing", "tamat", "movies", "movie", "genre", "genre populer", "top rated",
            "download app", "play", "info", "lihat semua", "semua", "pilih episode", "gdrive", "gdrive hd", "mp4", "mp4 hd", "cepat", "b-tube"
        )
    }
}
