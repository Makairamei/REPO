package com.sad25kag.dramacool

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dramacool : MainAPI() {
    override var mainUrl = "https://asianctv.net"
    override var name = "Dramacool"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "recently-added?page=%d" to "Recently Added",
        "recently-added-movie?page=%d" to "Recently Added Movie",
        "most-popular-drama?page=%d" to "Popular Drama",
        "recently-added-kshow?page=%d" to "Recently Added KShow",

        "genre/action?page=%d" to "Action",
        "genre/adventure?page=%d" to "Adventure",
        "genre/animation?page=%d" to "Animation",
        "genre/comedy?page=%d" to "Comedy",
        "genre/crime?page=%d" to "Crime",
        "genre/drama?page=%d" to "Drama",
        "genre/family?page=%d" to "Family",
        "genre/fantasy?page=%d" to "Fantasy",
        "genre/historical?page=%d" to "Historical",
        "genre/horror?page=%d" to "Horror",
        "genre/law?page=%d" to "Law",
        "genre/legal?page=%d" to "Legal",
        "genre/medical?page=%d" to "Medical",
        "genre/mystery?page=%d" to "Mystery",
        "genre/psychological?page=%d" to "Psychological",
        "genre/reality-show?page=%d" to "Reality Show",
        "genre/romance?page=%d" to "Romance",
        "genre/school?page=%d" to "School",
        "genre/sci-fi?page=%d" to "Sci-Fi",
        "genre/thriller?page=%d" to "Thriller",
        "genre/variety?page=%d" to "Variety",

        "genre/accident?page=%d" to "Accident",
        "genre/alien?page=%d" to "Alien",
        "genre/amnesia?page=%d" to "Amnesia",
        "genre/ancient-legend?page=%d" to "Ancient Legend",
        "genre/animals?page=%d" to "Animals",
        "genre/artificial-intelligence?page=%d" to "Artificial Intelligence",
        "genre/award-winning?page=%d" to "Award Winning",
        "genre/based-on-a-comic?page=%d" to "Based on a Comic",
        "genre/based-on-true-story?page=%d" to "Based on True Story",
        "genre/betrayal?page=%d" to "Betrayal",
        "genre/biography?page=%d" to "Biography",
        "genre/bl?page=%d" to "BL",
        "genre/bodyguard?page=%d" to "Bodyguard",
        "genre/bromance?page=%d" to "Bromance",
        "genre/business?page=%d" to "Business",
        "genre/cohabitation?page=%d" to "Cohabitation",
        "genre/conspiracy?page=%d" to "Conspiracy",
        "genre/contract-relationship?page=%d" to "Contract Relationship",
        "genre/corruption?page=%d" to "Corruption",
        "genre/detective?page=%d" to "Detective",
        "genre/disaster?page=%d" to "Disaster",
        "genre/documentary?page=%d" to "Documentary",
        "genre/entertainment?page=%d" to "Entertainment",
        "genre/espionage?page=%d" to "Espionage",
        "genre/friendship?page=%d" to "Friendship",
        "genre/gangster?page=%d" to "Gangster",
        "genre/goryeo-dynasty?page=%d" to "Goryeo Dynasty",
        "genre/gumiho?page=%d" to "Gumiho",
        "genre/hidden-identity?page=%d" to "Hidden Identity",
        "genre/hostage?page=%d" to "Hostage",
        "genre/idol-drama?page=%d" to "Idol Drama",
        "genre/investigation?page=%d" to "Investigation",
        "genre/jidai-geki?page=%d" to "Jidai Geki",
        "genre/kidnapping?page=%d" to "Kidnapping",
        "genre/kung-fu?page=%d" to "Kung Fu",
        "genre/love-triangle?page=%d" to "Love Triangle",
        "genre/mafia?page=%d" to "Mafia",
        "genre/magic?page=%d" to "Magic",
        "genre/manga?page=%d" to "Manga",
        "genre/martial-arts?page=%d" to "Martial Arts",
        "genre/melodrama?page=%d" to "Melodrama",
        "genre/military?page=%d" to "Military",
        "genre/miniseries?page=%d" to "Miniseries",
        "genre/misunderstanding?page=%d" to "Misunderstanding",
        "genre/monster?page=%d" to "Monster",
        "genre/murder?page=%d" to "Murder",
        "genre/music?page=%d" to "Music",
        "genre/musical?page=%d" to "Musical",
        "genre/mythology?page=%d" to "Mythology",
        "genre/noir?page=%d" to "Noir",
        "genre/novel?page=%d" to "Novel",
        "genre/omnibus?page=%d" to "Omnibus",
        "genre/police?page=%d" to "Police",
        "genre/political?page=%d" to "Political",
        "genre/prison?page=%d" to "Prison",
        "genre/professional?page=%d" to "Professional",
        "genre/rebellion?page=%d" to "Rebellion",
        "genre/remake?page=%d" to "Remake",
        "genre/revenge?page=%d" to "Revenge",
        "genre/rich-man?page=%d" to "Rich Man",
        "genre/robot?page=%d" to "Robot",
        "genre/samurai?page=%d" to "Samurai"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val safePage = if (page < 1) 1 else page
        val url = "$mainUrl/${request.data.format(safePage)}"

        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val forceMovie = request.name.contains("Movie", true) || request.data.contains("movie", true)
        val home = document.select(
            "ul.list-episode-item-2 li, div.left-tab-1 ul li, ul.switch-block li, " +
                "div.content-left ul li, div.content-left li, ul.list-film li"
        )
            .mapNotNull { it.toSearchResult(forceMovie) }
            .distinctBy { it.url }

        val hasNext = document.select(
            "ul.pagination a:contains(Next), div.pagination a:contains(Next), a[href*='page=${safePage + 1}']"
        ).isNotEmpty() || home.isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(forceMovie: Boolean = false): SearchResponse? {
        val detail = selectFirst("a[href*=/drama-detail/]")
        val episode = selectFirst("a[href*=episode-][href$=.html]")
        val anchor = detail ?: episode ?: selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.contains("/drama-detail/", true) && !href.contains("episode-", true)) return null

        val title = anchor.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("h3.title")?.text()?.trim()
            ?: selectFirst(".title")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: anchor.text().trim()

        if (title.isBlank()) return null

        val poster = getPosterUrl()
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val type = when {
            forceMovie -> TvType.Movie
            href.contains("episode-", true) -> TvType.AsianDrama
            else -> getTypeFromUrl(href)
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title.cleanTitle(), href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title.cleanTitle(), href, TvType.AsianDrama) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        if (encodedQuery.isBlank()) return emptyList()

        val htmlResults = app.get(
            "$mainUrl/search?type=movies&keyword=$encodedQuery",
            headers = defaultHeaders,
            referer = mainUrl
        ).document
            .select("ul.list-episode-item-2 li, div.content-left ul li, div.content-left li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (htmlResults.isNotEmpty()) return htmlResults

        val apiText = app.get(
            "$mainUrl/api?a=search&keyword=$encodedQuery&type=drama",
            headers = defaultHeaders,
            referer = mainUrl
        ).text

        val apiResults = runCatching {
            AppUtils.parseJson<List<SearchItem>>(apiText)
        }.getOrNull() ?: return emptyList()

        return apiResults.mapNotNull { item ->
            val title = item.name ?: item.value ?: return@mapNotNull null
            val url = fixUrlNull(item.url ?: return@mapNotNull null) ?: return@mapNotNull null
            val type = getTypeFromUrl(url)

            if (type == TvType.Movie) {
                newMovieSearchResponse(title.cleanTitle(), url, TvType.Movie) {
                    this.posterUrl = fixUrlNull(item.cover)
                    this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            } else {
                newTvSeriesSearchResponse(title.cleanTitle(), url, TvType.AsianDrama) {
                    this.posterUrl = fixUrlNull(item.cover)
                    this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val detailUrl = if (url.contains("/drama-detail/", true)) url else getDetailUrl(url) ?: url

        val document = app.get(
            detailUrl,
            headers = defaultHeaders,
            referer = mainUrl
        ).document

        val title = document.selectFirst("div.info h1, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.details div.img img, div.img img, meta[property=og:image]")
                ?.let { element ->
                    if (element.tagName() == "meta") element.attr("content") else element.attr("src")
                }
        )

        val description = document.select("div.info p")
            .firstOrNull { it.selectFirst("span") == null }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val tags = document.select("div.info p:contains(Genre:) a, div.info p:contains(Country:) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val status = getStatus(document.selectFirst("div.info p:contains(Status:)")?.ownText())
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = document.select("a[href*=episode-][href$=.html]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = document.select(
            "div.content-right a[href*=/drama-detail/], ul.switch-block a[href*=/drama-detail/]"
        )
            .mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
            .distinctBy { it.url }

        val isMovie = title.contains("movie", true) ||
            detailUrl.contains("movie", true) ||
            tags.any { it.contains("Movie", true) } ||
            episodes.size <= 1 && document.select("div.info p:contains(Type:)").text().contains("Movie", true)

        return if (isMovie) {
            newMovieLoadResponse(title.cleanTitle(), detailUrl, TvType.Movie, episodes.firstOrNull()?.data ?: detailUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title.cleanTitle(), detailUrl, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = fixUrlNull(attr("href")) ?: return null
        val name = selectFirst("h3.title")?.text()?.trim()
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: text().trim()

        val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val dateText = selectFirst("span.time")?.text()
            ?: parent()?.selectFirst("span.time")?.text()

        return newEpisode(href) {
            this.name = name.cleanTitle()
            this.episode = epNum
            addDate(dateText)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(
            data,
            headers = defaultHeaders,
            referer = mainUrl
        ).document

        val links = document.select("div.muti_link li[data-video], ul.list-server li[data-video], li.linkserver[data-video]")
            .mapNotNull { li ->
                fixUrlNull(li.attr("data-video").trim()).takeIf { !it.isNullOrBlank() }
            }
            .distinct()

        val extractorLinks = links
            .filterNot { it.contains("vidbasic", true) }
            .takeIf { it.isNotEmpty() }
            ?: links

        var delivered = false

        extractorLinks.amap { link ->
            runCatching {
                val success = loadExtractor(resolveExtractorUrl(link), data, subtitleCallback) { extractorLink ->
                    delivered = true
                    callback(extractorLink)
                }
                if (success) delivered = true
            }
        }

        if (!delivered) {
            document.select("iframe[src], source[src], video[src]").forEach { element ->
                val direct = element.attr("src").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
                if (!direct.isNullOrBlank()) {
                    runCatching {
                        val success = loadExtractor(resolveExtractorUrl(direct), data, subtitleCallback) { extractorLink ->
                            delivered = true
                            callback(extractorLink)
                        }
                        if (success) delivered = true
                    }
                }
            }
        }

        return delivered
    }

    private suspend fun resolveExtractorUrl(url: String): String {
        return runCatching {
            when {
                url.contains("hglink.to", true) -> url
                    .replace("https://hglink.to/e/", "https://hanerix.com/e/")
                    .replace("http://hglink.to/e/", "https://hanerix.com/e/")
                else -> url
            }
        }.getOrDefault(url)
    }

    private suspend fun getDetailUrl(url: String): String? {
        return app.get(
            url,
            headers = defaultHeaders,
            referer = mainUrl
        ).document
            .selectFirst("div.category a[href*=/drama-detail/], a[href*=/drama-detail/]")
            ?.attr("href")
            ?.let { fixUrl(it) }
    }

    private fun Element.getPosterUrl(): String? {
        val img = selectFirst("img")
        return fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ")
            .replace("SUB ", "", ignoreCase = true)
            .trim()
    }

    private fun getTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/country/") && url.contains("movie", true) -> TvType.Movie
            url.contains("movie", true) -> TvType.Movie
            else -> TvType.AsianDrama
        }
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when {
            status?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            status?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    data class SearchItem(
        @JsonProperty("value") val value: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("status") val status: String?,
    )
}
