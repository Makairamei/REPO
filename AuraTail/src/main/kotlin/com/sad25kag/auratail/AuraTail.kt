package com.sad25kag.auratail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class AuraTail : MainAPI() {
    override var mainUrl = "https://auratail.vip"
    override var name = "AuraTail"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    companion object {
        fun getType(typeText: String?): TvType {
            if (typeText == null) return TvType.Anime
            return when {
                typeText.contains("tv", true) -> TvType.Anime
                typeText.contains("movie", true) -> TvType.AnimeMovie
                typeText.contains("ova", true) || typeText.contains("special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(statusText: String?): ShowStatus {
            if (statusText == null) return ShowStatus.Completed
            return when {
                statusText.contains("ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private fun translateGenre(raw: String): String {
        return when (raw.trim().lowercase(Locale.ROOT)) {
            "action" -> "Aksi"
            "adventure" -> "Petualangan"
            "comedy" -> "Komedi"
            "drama" -> "Drama"
            "fantasy" -> "Fantasi"
            "gag humor" -> "Humor Gag"
            "gore" -> "Gore"
            "historical" -> "Sejarah"
            "horror" -> "Horor"
            "isekai" -> "Isekai"
            "martial arts" -> "Martial Arts"
            "military" -> "Militer"
            "mystery" -> "Misteri"
            "mythology" -> "Mitologi"
            "parody" -> "Parodi"
            "psychological" -> "Psikologis"
            "reincarnation" -> "Reinkarnasi"
            "romance" -> "Romantis"
            "school" -> "Sekolah"
            "sci-fi" -> "Sci-Fi"
            "seinen" -> "Seinen"
            "shounen" -> "Shounen"
            "supernatural" -> "Supernatural"
            "suspense" -> "Suspense"
            "time travel" -> "Perjalanan Waktu"
            "urban fantasy" -> "Urban Fantasy"
            "video game" -> "Video Game"
            else -> raw.trim()
        }
    }

    override val mainPage = mainPageOf(
        "" to "Update Terbaru",
        "anime/" to "Daftar Anime",
        "genres/action/" to "Aksi",
        "genres/adventure/" to "Petualangan",
        "genres/comedy/" to "Komedi",
        "genres/drama/" to "Drama",
        "genres/fantasy/" to "Fantasi",
        "genres/gag-humor/" to "Humor Gag",
        "genres/gore/" to "Gore",
        "genres/historical/" to "Sejarah",
        "genres/horror/" to "Horor",
        "genres/isekai/" to "Isekai",
        "genres/martial-arts/" to "Martial Arts",
        "genres/military/" to "Militer",
        "genres/mystery/" to "Misteri",
        "genres/mythology/" to "Mitologi",
        "genres/parody/" to "Parodi",
        "genres/psychological/" to "Psikologis",
        "genres/reincarnation/" to "Reinkarnasi",
        "genres/romance/" to "Romantis",
        "genres/school/" to "Sekolah",
        "genres/sci-fi/" to "Sci-Fi",
        "genres/seinen/" to "Seinen",
        "genres/shounen/" to "Shounen",
        "genres/supernatural/" to "Supernatural",
        "genres/suspense/" to "Suspense",
        "genres/time-travel/" to "Perjalanan Waktu",
        "genres/urban-fantasy/" to "Urban Fantasy",
        "genres/video-game/" to "Video Game",
    )

    private fun buildMainPageUrl(page: Int, data: String): String {
        val path = data.trim().trimStart('/')
        return when {
            path.isBlank() -> {
                if (page <= 1) mainUrl else "$mainUrl/page/$page/"
            }
            page <= 1 -> "$mainUrl/${path.trimEnd('/')}/"
            else -> "$mainUrl/${path.trimEnd('/')}/page/$page/"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildMainPageUrl(page, request.data)).document
        val items = parseSearchResults(document)

        val hasNext = document.selectFirst(
            "a.nextpostslink, a[rel=next], .pagination a:contains(Next), .hpage a:contains(Next), .wp-pagenavi a:contains(Next)"
        ) != null

        return newHomePageResponse(
            listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        return parseSearchResults(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val document = response.document
        val rawHtml = response.text
        val pageTitle =
            document
                .selectFirst("h1.entry-title")
                ?.text()
                ?.replace(
                    Regex(
                        "\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b",
                        RegexOption.IGNORE_CASE,
                    ),
                    "",
                )
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
        val seriesMeta = extractSeriesMeta(rawHtml)
        val isEpisodePage =
            url.contains("-episode-", true) ||
                Regex("\\bEpisode\\s+\\d+\\b", RegexOption.IGNORE_CASE).containsMatchIn(pageTitle)

        val title =
            if (isEpisodePage) {
                seriesMeta?.seriesTitle ?: pageTitle.removeEpisodeSuffix()
            } else {
                pageTitle
            }
        val poster =
            seriesMeta?.posterUrl
                ?: document
                    .selectFirst("div.bigcontent img, div.thumb img, .thumb img, meta[property=og:image]")
                    ?.let { element ->
                        if (element.tagName().equals("meta", true)) {
                            fixUrlNull(element.attr("content"))
                        } else {
                            element.getImageAttr().let { fixUrlNull(it) }
                        }
                    }

        val description =
            document
                .select("div.entry-content p")
                .joinToString("\n") { it.text() }
                .trim()

        val year =
            document
                .selectFirst("span:matchesOwn(Dirilis:)")
                ?.ownText()
                ?.filter { it.isDigit() }
                ?.take(4)
                ?.toIntOrNull()

        val duration =
            document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
                val hours =
                    Regex("(\\d+)\\s*hr", RegexOption.IGNORE_CASE)
                        .find(it)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 0
                val minutes =
                    Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE)
                        .find(it)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 0
                hours * 60 + minutes
            }

        val typeText = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()
        val type = if (isEpisodePage) TvType.Anime else getType(typeText)

        val tags = document.select("div.genxed a").map { translateGenre(it.text()) }
        val actors = document.select("span:has(b:matchesOwn(Artis:)) a").map { it.text().trim() }
        val rating =
            document
                .selectFirst("div.rating strong")
                ?.text()
                ?.replace("Rating", "")
                ?.trim()
                ?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val status = getStatus(document.selectFirst("span:matchesOwn(Status:)")?.ownText()?.trim())

        val recommendations =
            document
                .select("div.listupd article.bs")
                .mapNotNull { it.toRecommendResult() }

        val castList =
            document.select("div.bixbox.charvoice div.cvitem").mapNotNull { item ->
                val charBox = item.selectFirst(".cvsubitem.cvchar") ?: item
                val actorBox = item.selectFirst(".cvsubitem.cvactor") ?: item

                val charName = charBox.selectFirst(".cvcontent .charname")?.text()?.trim()
                val charRole = charBox.selectFirst(".cvcontent .charrole")?.text()?.trim()
                val charImg = charBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

                val actorName = actorBox.selectFirst(".cvcontent .charname")?.text()?.trim()
                val actorImg = actorBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

                if (charName.isNullOrBlank() && actorName.isNullOrBlank()) return@mapNotNull null

                val actor = Actor(charName ?: actorName ?: "", charImg)
                val voiceActor = actorName?.let { Actor(it, actorImg) }
                ActorData(actor = actor, roleString = charRole, voiceActor = voiceActor)
            }

        val episodes = buildEpisodes(document, isEpisodePage)

        val altTitles =
            listOfNotNull(
                title,
                pageTitle.takeIf { it.isNotBlank() && !it.equals(title, true) }?.removeEpisodeSuffix(),
                seriesMeta?.seriesTitle,
                document.selectFirst("span:matchesOwn(Judul Inggris:)")?.ownText()?.trim(),
                document.selectFirst("span:matchesOwn(Judul Jepang:)")?.ownText()?.trim(),
                document.selectFirst("span:matchesOwn(Judul Asli:)")?.ownText()?.trim(),
            ).distinct()

        val malIdFromPage =
            document
                .selectFirst("a[href*=\"myanimelist.net/anime/\"]")
                ?.attr("href")
                ?.substringAfter("/anime/", "")
                ?.substringBefore("/")
                ?.toIntOrNull()
        val aniIdFromPage =
            document
                .selectFirst("a[href*=\"anilist.co/anime/\"]")
                ?.attr("href")
                ?.substringAfter("/anime/", "")
                ?.substringBefore("/")
                ?.toIntOrNull()

        val tracker = APIHolder.getTracker(altTitles, TrackerType.getTypes(type), year, true)

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addEpisodes(DubStatus.Subbed, episodes)
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val response = app.get(data)
        val document = response.document
        var found = false

        document
            .selectFirst("div.player-embed iframe, .player-embed iframe, .embed-responsive iframe, iframe[src], iframe[data-src], iframe[data-litespeed-src]")
            ?.getIframeAttr()
            ?.let { iframe ->
                found = loadExtractor(httpsify(iframe), data, subtitleCallback, callback) || found
            }

        val mirrorOptions = document.select("select.mirror option[value]:not([disabled]), option[value]:not([disabled])")
        for (option in mirrorOptions) {
            val base64 = option.attr("value").trim()
            if (base64.isBlank()) continue
            runCatching {
                val decodedHtml = base64Decode(base64.replace("\\s".toRegex(), ""))
                val iframeTag = Jsoup.parse(decodedHtml).selectFirst("iframe")
                val mirrorUrl =
                    when {
                        iframeTag?.attr("src")?.isNotBlank() == true -> iframeTag.attr("src")
                        iframeTag?.attr("data-src")?.isNotBlank() == true -> iframeTag.attr("data-src")
                        else -> null
                    }
                if (!mirrorUrl.isNullOrBlank()) {
                    found = loadExtractor(httpsify(mirrorUrl), data, subtitleCallback, callback) || found
                }
            }
        }

        Regex("""<iframe[^>]+(?:src|data-src|data-litespeed-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(response.text)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { iframe ->
                found = loadExtractor(httpsify(iframe), data, subtitleCallback, callback) || found
            }

        val downloadLinks = document.select("div.dlbox li span.e a[href], .dlbox a[href], a[href*='.m3u8'], a[href*='.mp4']")
        for (anchor in downloadLinks) {
            val downloadUrl = anchor.attr("abs:href").ifBlank { anchor.attr("href") }.trim()
            if (downloadUrl.isNotBlank()) {
                found = loadExtractor(httpsify(downloadUrl), data, subtitleCallback, callback) || found
            }
        }

        return found
    }

    private fun parseSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        val containerSelector = listOf(
            "div.listupd article.bs",
            "article.bs",
            ".listupd .bs",
            ".listupd .bsx",
            ".listupd .utao",
            ".latest .bs",
            ".latest .bsx",
            ".bixbox .listupd article",
            ".items article",
            ".items .bsx",
            ".postbody article",
            ".releases article",
            "article:has(a[href])",
        ).joinToString(", ")

        document.select(containerSelector).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.isEmpty()) {
            document.select("a[href]").forEach { anchor ->
                anchor.toSearchResult()?.let { results[it.url] = it }
            }
        }

        return results.values
            .filter { it.name.isNotBlank() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor =
            if (tagName().equals("a", true)) {
                this
            } else {
                selectFirst("a[href]:has(img)")
                    ?: selectFirst("h2 a[href], h3 a[href], h4 a[href], .tt a[href], .entry-title a[href]")
                    ?: selectFirst("a[href]")
            } ?: return null

        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!isValidItemUrl(href)) return null

        val fixedUrl = fixUrl(href)
        val image = selectFirst("img") ?: anchor.selectFirst("img")

        val rawTitle = listOfNotNull(
            selectFirst(".tt")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst("h4")?.text(),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            anchor.text(),
            fixedUrl.substringBefore("?").trim('/').substringAfterLast("/").replace("-", " "),
        ).firstOrNull { it.isNotBlank() && !isBadCardTitle(it) } ?: return null

        val cleanTitle = cleanCardTitle(rawTitle)
        if (cleanTitle.isBlank() || isBadCardTitle(cleanTitle)) return null

        val poster = image?.getImageAttr()?.let { fixUrlNull(it) }
        val type = getType(selectFirst(".typez, .limit .type, span.type, .type, .bt span")?.text()?.trim())

        return when (type) {
            TvType.AnimeMovie -> newMovieSearchResponse(cleanTitle, fixedUrl, type) {
                posterUrl = poster
            }
            else -> newAnimeSearchResponse(cleanTitle, fixedUrl, type) {
                posterUrl = poster
            }
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        return toSearchResult()
    }

    private fun isValidItemUrl(url: String): Boolean {
        val fixed = fixUrlNull(url)?.substringBefore("#") ?: return false
        if (!fixed.startsWith(mainUrl, true)) return false

        val path = runCatching {
            URI(fixed).path.trim('/')
        }.getOrDefault(fixed.substringAfter(mainUrl).trim('/'))

        if (path.isBlank()) return false

        val blockedPrefixes = listOf(
            "genres/",
            "genre/",
            "anime/",
            "az-list",
            "schedule",
            "bookmark",
            "history",
            "page/",
            "wp-",
            "tag/",
            "studio/",
            "season/",
            "status/",
            "type/",
            "order/",
            "author/",
            "privacy-policy",
            "contact",
            "login",
            "register",
        )

        return blockedPrefixes.none { path.equals(it.trim('/'), true) || path.startsWith(it, true) }
    }

    private fun isBadCardTitle(title: String): Boolean {
        val value = title.trim().lowercase(Locale.ROOT)
        return value.isBlank() ||
            value == "home" ||
            value == "anime lists" ||
            value == "az lists" ||
            value == "schedule" ||
            value == "bookmark" ||
            value == "history" ||
            value == "view all" ||
            value == "next" ||
            value == "prev" ||
            value == "all episodes" ||
            value == "image" ||
            value == "expand" ||
            value == "turn off light" ||
            value == "select video server" ||
            value.startsWith("genre all") ||
            value.startsWith("season all") ||
            value.startsWith("studio all") ||
            value.startsWith("status all") ||
            value.startsWith("type all") ||
            value.startsWith("order by")
    }

    private fun cleanCardTitle(title: String): String {
        var clean = title
            .replace(Regex("\\b(Completed|Ongoing|Upcoming|Hiatus|ONA|OVA|TV|Movie|Special|BD|Sub)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bEps?\\s*\\d+\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bReleased\\s+on\\b.*$", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val parts = clean.split(" ")
        if (parts.size >= 2 && parts.size % 2 == 0) {
            val half = parts.size / 2
            val first = parts.take(half).joinToString(" ")
            val second = parts.drop(half).joinToString(" ")
            if (first.equals(second, true)) clean = first
        }

        return clean
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun buildEpisodes(
        document: org.jsoup.nodes.Document,
        isEpisodePage: Boolean,
    ): List<Episode> {
        val selectors =
            if (isEpisodePage) {
                "#singlepisode .episodelist ul li a[href], div.episodelist ul li a[href], div.eplister ul li a[href]"
            } else {
                "div.eplister ul li a[href], #singlepisode .episodelist ul li a[href]"
            }

        val episodeAnchors = document.select(selectors)
        return episodeAnchors
            .mapNotNull { anchor ->
                val href = fixUrl(anchor.attr("href"))
                if (href.isBlank()) return@mapNotNull null

                val rawTitle =
                    anchor.selectFirst("h3, h4")?.text()?.trim()
                        ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                        ?: anchor.text().trim()
                val episodeNumber =
                    Regex("\\b(?:Episode|Eps?|Ep)\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
                        .find(rawTitle)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE)
                            .find(href)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                val name =
                    rawTitle.ifBlank {
                        episodeNumber?.let { "Episode $it" } ?: "Episode"
                    }

                newEpisode(href) {
                    this.name = name
                    this.episode = episodeNumber
                }
            }.distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun extractSeriesMeta(html: String): SeriesMeta? {
        val match =
            Regex(
                """item":\{"mid":\d+,"cid":\d+,"c":"([^"]+)","s":"([^"]+)","t":"([^"]+)"""",
                RegexOption.IGNORE_CASE,
            ).find(html)
                ?: return null
        return SeriesMeta(
            currentEpisode = match.groupValues[1],
            seriesTitle = match.groupValues[2].unescapeJsString().removeEpisodeSuffix(),
            posterUrl = fixUrlNull(match.groupValues[3].unescapeJsString()),
        )
    }

    private fun String.unescapeJsString(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\\"", "\"")
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(
            Regex("\\s+Episode\\s+\\d+\\b.*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
    }

    private data class SeriesMeta(
        val currentEpisode: String?,
        val seriesTitle: String,
        val posterUrl: String?,
    )

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("data-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }
}
