package com.kazefuri

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KazefuriProvider : MainAPI() {
    override var mainUrl = "https://sv4.kazefuri.cloud"
    override var name = "Kazefuri"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Update Terbaru",
        "$mainUrl/anime/page/%d/?status=&type=&order=update" to "Donghua",
        "$mainUrl/anime/page/%d/?status=&type=&order=popular" to "Populer",
        "$mainUrl/anime/page/%d/?status=Ongoing&type=&order=update" to "Ongoing",
        "$mainUrl/anime/page/%d/?status=Upcoming&type=&order=update" to "Upcoming",
        "$mainUrl/anime/page/%d/?status=Completed&type=&order=update" to "Completed",
        "$mainUrl/anime/page/%d/?status=&type=Movie&order=update" to "Movie",

        "$mainUrl/anime/page/%d/?genre=action&status=&type=&order=update" to "Action",
        "$mainUrl/anime/page/%d/?genre=adventure&status=&type=&order=update" to "Adventure",
        "$mainUrl/anime/page/%d/?genre=comedy&status=&type=&order=update" to "Comedy",
        "$mainUrl/anime/page/%d/?genre=crossdressing&status=&type=&order=update" to "Crossdressing",
        "$mainUrl/anime/page/%d/?genre=cultivation&status=&type=&order=update" to "Cultivation",
        "$mainUrl/anime/page/%d/?genre=demons&status=&type=&order=update" to "Demons",
        "$mainUrl/anime/page/%d/?genre=drama&status=&type=&order=update" to "Drama",
        "$mainUrl/anime/page/%d/?genre=ecchi&status=&type=&order=update" to "Ecchi",
        "$mainUrl/anime/page/%d/?genre=fantasy&status=&type=&order=update" to "Fantasy",
        "$mainUrl/anime/page/%d/?genre=friendship&status=&type=&order=update" to "Friendship",
        "$mainUrl/anime/page/%d/?genre=game&status=&type=&order=update" to "Game",
        "$mainUrl/anime/page/%d/?genre=gore&status=&type=&order=update" to "Gore",
        "$mainUrl/anime/page/%d/?genre=gourmet&status=&type=&order=update" to "Gourmet",
        "$mainUrl/anime/page/%d/?genre=guoman&status=&type=&order=update" to "Guoman",
        "$mainUrl/anime/page/%d/?genre=harem&status=&type=&order=update" to "Harem",
        "$mainUrl/anime/page/%d/?genre=historical&status=&type=&order=update" to "Historical",
        "$mainUrl/anime/page/%d/?genre=horror&status=&type=&order=update" to "Horror",
        "$mainUrl/anime/page/%d/?genre=isekai&status=&type=&order=update" to "Isekai",
        "$mainUrl/anime/page/%d/?genre=magic&status=&type=&order=update" to "Magic",
        "$mainUrl/anime/page/%d/?genre=martial-arts&status=&type=&order=update" to "Martial Arts",
        "$mainUrl/anime/page/%d/?genre=mecha&status=&type=&order=update" to "Mecha",
        "$mainUrl/anime/page/%d/?genre=military&status=&type=&order=update" to "Military",
        "$mainUrl/anime/page/%d/?genre=music&status=&type=&order=update" to "Music",
        "$mainUrl/anime/page/%d/?genre=mystery&status=&type=&order=update" to "Mystery",
        "$mainUrl/anime/page/%d/?genre=psychological&status=&type=&order=update" to "Psychological",
        "$mainUrl/anime/page/%d/?genre=reincarnation&status=&type=&order=update" to "Reincarnation",
        "$mainUrl/anime/page/%d/?genre=romance&status=&type=&order=update" to "Romance",
        "$mainUrl/anime/page/%d/?genre=school&status=&type=&order=update" to "School",
        "$mainUrl/anime/page/%d/?genre=sci-fi&status=&type=&order=update" to "Sci-Fi",
        "$mainUrl/anime/page/%d/?genre=shoujo&status=&type=&order=update" to "Shoujo",
        "$mainUrl/anime/page/%d/?genre=shounen&status=&type=&order=update" to "Shounen",
        "$mainUrl/anime/page/%d/?genre=slice-of-life&status=&type=&order=update" to "Slice of Life",
        "$mainUrl/anime/page/%d/?genre=space&status=&type=&order=update" to "Space",
        "$mainUrl/anime/page/%d/?genre=sports&status=&type=&order=update" to "Sports",
        "$mainUrl/anime/page/%d/?genre=super-power&status=&type=&order=update" to "Super Power",
        "$mainUrl/anime/page/%d/?genre=supernatural&status=&type=&order=update" to "Supernatural",
        "$mainUrl/anime/page/%d/?genre=thriller&status=&type=&order=update" to "Thriller",
        "$mainUrl/anime/page/%d/?genre=vampire&status=&type=&order=update" to "Vampire",
        "$mainUrl/anime/page/%d/?genre=youth&status=&type=&order=update" to "Youth",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = if (page == 1) request.data.replace("/page/%d/", "/").replace("page/%d/", "")
            .replace("%d", page.toString()) else request.data.format(page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a.next.page-numbers, a.nextpostslink, .pagination a.next").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.entry-title, .bigcontent h1, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".thumb img, .bigcontent .thumb img, .ime img, meta[property=og:image]")
            ?.let { it.attr("content").ifBlank { it.imageUrl() } }

        val typeText = detailValue(document, "Type")
            ?: detailValue(document, "Tipe")
            ?: document.selectFirst(".typez")?.text()
            ?: fixedUrl
        val type = getType(typeText, fixedUrl)
        val status = getStatus(detailValue(document, "Status") ?: document.selectFirst(".epx")?.text())
        val year = listOfNotNull(
            detailValue(document, "Released"),
            detailValue(document, "Rilis"),
            detailValue(document, "Date aired"),
            document.select(".year, .spe").text()
        ).firstNotNullOfOrNull { extractYear(it) }
        val rating = Regex("""(\d+(?:\.\d+)?)""")
            .find(document.selectFirst(".rating, .rt")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val tags = document.select(".genxed a[href], .infox a[href*='/genres/'], a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val plot = document.extractSynopsis()
        val episodes = document.select(".eplister a[href], .episodelist a[href], ul.episodios a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        val recommendations = document.select(".serieslist a[href], .listupd .bsx a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                showStatus = status
                rating?.let { score = Score.from10(it) }
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val data = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, fixedUrl, type, data) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(data, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<Pair<String, String>>()

        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src]").forEach { iframe ->
            iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { candidates.add(it to "Default") }
        }

        document.select("select.mirror option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { "Mirror" }
            KazefuriExtractorHelper.decodeMirror(option.attr("value")).forEach { mirror ->
                candidates.add(mirror to label)
            }
        }

        candidates
            .filterNot { (url, _) -> KazefuriExtractorHelper.isNoiseFrame(url) }
            .amap { (url, label) ->
                KazefuriExtractorHelper.resolveLink(
                    url = KazefuriExtractorHelper.normalizeUrl(url, data) ?: return@amap,
                    label = label,
                    referer = data,
                    emitted = emitted,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            }

        document.select(".soraddlx a[href], .dlbox a[href], .download a[href], a[href*='mirrored.to']")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { runCatching { loadExtractor(it, data, subtitleCallback, callback) } }

        return candidates.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: if (tagName() == "a") this else return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (href.contains("/genres/") || href.contains("/season/") || href.contains("/author/")) return null

        val title = listOf(
            link.attr("title"),
            selectFirst(".tt h2, .tt, h2, h3")?.text(),
            selectFirst("img")?.attr("title"),
            selectFirst("img")?.attr("alt")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("img")?.imageUrl()
        val type = getType(selectFirst(".typez")?.text(), href)
        val episode = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(link.attr("title").ifBlank { text() })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = attr("abs:href").ifBlank { attr("href") }.takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val rawTitle = selectFirst(".epl-title")?.text()?.trim().orEmpty()
            .ifBlank { attr("title").trim() }
            .ifBlank { text().trim() }
        val epNum = selectFirst(".epl-num")?.text()?.trim()?.toDoubleOrNull()
            ?: Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(rawTitle.ifBlank { href })
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        return newEpisode(href) {
            name = rawTitle.cleanTitle().ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src")
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select(".spe span, .infox .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()?.replace(":", "")?.trim()?.equals(label, true) == true
            }
            ?.ownText()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Document.extractSynopsis(): String? {
        val synopsisElement = selectFirst(
            ".entry-content.entry-content-single p, " +
                ".entry-content p, " +
                ".bigcontent .info-content .desc, " +
                ".bigcontent .desc, " +
                ".synp .entry-content, " +
                ".desc"
        ) ?: return null

        synopsisElement.select("script, style, .keyword").remove()
        return synopsisElement.text()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { null }
    }

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value.isNullOrBlank() -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("completed", true) || value.contains("finish", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+(?:\.\d+)?.*$"""), "")
            .trim()
    }
}
