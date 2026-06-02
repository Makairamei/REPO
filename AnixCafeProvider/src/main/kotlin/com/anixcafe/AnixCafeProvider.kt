package com.anixcafe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnixCafeProvider : MainAPI() {
    override var mainUrl = "https://anixcafe.com"
    override var name = "AnixCafe"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update&status=&type=&page=%d" to "Pembaruan Terbaru",
        "$mainUrl/anime/?order=added&status=&type=&page=%d" to "Terbaru Ditambahkan",
        "$mainUrl/anime/?order=popular&status=&type=&page=%d" to "Populer",
        "$mainUrl/anime/?order=update&status=ongoing&type=&page=%d" to "Ongoing",
        "$mainUrl/anime/?order=update&status=completed&type=&page=%d" to "Completed",
        "$mainUrl/anime/?order=update&status=&type=movie&page=%d" to "Movie",
        "$mainUrl/anime/?order=update&status=&type=ova&page=%d" to "OVA",
        "$mainUrl/genres/action/page/%d/" to "Action",
        "$mainUrl/genres/adventure/page/%d/" to "Adventure",
        "$mainUrl/genres/comedy/page/%d/" to "Comedy",
        "$mainUrl/genres/drama/page/%d/" to "Drama",
        "$mainUrl/genres/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genres/martial-arts/page/%d/" to "Martial Arts",
        "$mainUrl/genres/romance/page/%d/" to "Romance",
        "$mainUrl/genres/wuxia/page/%d/" to "Wuxia",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(request.data.format(page), referer = "$mainUrl/").document
        val items = document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("div.hpage a.r, a.next, .pagination .next, a.next.page-numbers, .pagination a:contains(Next), a:contains(Next »)").isNotEmpty()
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

        val poster = document.selectFirst(".bigcontent .thumb img, .thumbook img, meta[property=og:image]")
            ?.let { it.attr("content").ifBlank { it.imageUrl() } }

        val type = getType(detailValue(document, "Tipe"), fixedUrl)
        val year = detailValue(document, "Rilis")?.let(::extractYear)
            ?: detailValue(document, "Dirilis pada")?.let(::extractYear)
        val status = getStatus(detailValue(document, "Status"))
        val tags = document.select(".genxed a[href], .infox a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val plot = document.extractSynopsis()

        val episodes = document.select(".eplister a[href], .episodelist a[href], ul.episodios a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedByDescending { it.episode ?: -1 }

        val recommendations = document.select(".serieslist a.series[href], .listupd .bsx a[href]")
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
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
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
        val visited = linkedSetOf<String>()
        val candidates = linkedSetOf<Pair<String, String>>()
        var emitted = false

        val safeCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        document.select("#pembed iframe[src], .player-embed iframe[src], .megavid iframe[src]").forEach { iframe ->
            iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { candidates.add(it to "Default") }
        }

        document.select("select.mirror option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { "Mirror" }
            AnixCafeExtractorHelper.decodeMirror(option.attr("value")).forEach { mirror ->
                candidates.add(mirror to label)
            }
        }

        candidates
            .filterNot { (url, _) ->
                AnixCafeExtractorHelper.isNoiseFrame(url) ||
                    AnixCafeExtractorHelper.isUnsupportedPlayerFrame(url)
            }
            .amap { (url, label) ->
                AnixCafeExtractorHelper.resolveLink(
                    url = AnixCafeExtractorHelper.normalizeUrl(url, data) ?: return@amap,
                    label = label,
                    referer = data,
                    visited = visited,
                    subtitleCallback = subtitleCallback,
                    callback = safeCallback
                )
            }

        document.select(".soraddlx a[href], .dlbox a[href], .download a[href], a[href*='mirrored.to'], a[href*='terabox']")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { url ->
                runCatching {
                    loadExtractor(url, data, subtitleCallback, safeCallback)
                }
            }

        return emitted
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val fixedHref = getProperAnimeLink(fixUrl(href))

        val title = listOf(
            link.attr("title"),
            selectFirst(".tt h2, .tt, h2, h3")?.text(),
            selectFirst("img")?.attr("alt")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("img")?.imageUrl()
        val type = getType(selectFirst(".typez")?.text(), fixedHref)
        val episode = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(link.attr("title").ifBlank { text() })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, fixedHref, type) {
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
                .find(rawTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        return newEpisode(href) {
            name = rawTitle.cleanTitle().ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }
    }

    private fun getProperAnimeLink(url: String): String {
        if (url.contains("/anime/", true)) return url

        val rel = Regex("""rel=["'](\d+)["']""").find(url)?.groupValues?.getOrNull(1)
        if (!rel.isNullOrBlank()) return url

        var slug = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        slug = slug
            .substringBefore("-episode-")
            .substringBefore("-subtitle-indonesia")
            .replace(Regex("""-season-(\d+)"""), "-$1th-season")
        return "$mainUrl/anime/$slug/"
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
            attr("abs:src")
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
            ".single-info.bixbox .infox .info-content .desc, " +
                ".single-info .info-content .desc, " +
                ".bigcontent .info-content .desc, " +
                ".bigcontent .desc, " +
                ".entry-content p"
        ) ?: return null

        synopsisElement.select(".colap, script, style").remove()
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
            value.contains("hiatus", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
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
