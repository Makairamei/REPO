package com.nekokun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class NekokunProvider : MainAPI() {
    override var mainUrl = "https://nekokun.my.id"
    override var name = "Nekokun"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Rilisan Terbaru",
        "$mainUrl/anime/?page=%d" to "Anime Lists",
        "$mainUrl/anime/?page=%d&type=movie&sub=&order=update" to "Movie",
        "$mainUrl/anime/?page=%d&type=live+action&sub=&order=update" to "Live Action",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("div.listupd article.bs, article.bs, div.bsx, .serieslist li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("a.next, .hpage a.r, .pagination a.next, a[rel=next]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun buildPageUrl(pattern: String, page: Int): String {
        return when {
            page == 1 && pattern.contains("/page/%d/") -> pattern.replace("/page/%d/", "/")
            else -> pattern.format(page)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.select("div.listupd article.bs, article.bs, div.bsx, .serieslist li")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        val seriesUrl = fixedUrl.takeIf { it.contains("/anime/", true) }
            ?: document.selectFirst(".ts-breadcrumb a[href*='/anime/'], .naveps a[href*='/anime/'], #singlepisode .headlist a[href*='/anime/']")
                ?.attr("abs:href")
                ?.ifBlank { null }
                ?.let(::fixUrl)
            ?: fixedUrl

        val detailDocument = if (seriesUrl != fixedUrl) {
            app.get(seriesUrl, referer = fixedUrl).document
        } else {
            document
        }

        val title = detailDocument.selectFirst(".infox h1.entry-title, h1.entry-title, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = detailDocument.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: detailDocument.selectFirst(".bigcontent .thumb img, .thumbook img, .headlist .thumb img, img.wp-post-image")?.imageUrl()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".tb img, img.wp-post-image")?.imageUrl()

        val type = getType(detailValue(detailDocument, "Tipe"), seriesUrl)
        val year = detailValue(detailDocument, "Dirilis")?.let(::extractYear)
            ?: detailValue(detailDocument, "Diperbarui pada")?.let(::extractYear)
        val status = getStatus(detailValue(detailDocument, "Status"))
        val score = detailDocument.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()
            ?: Regex("""Rating\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(detailDocument.text())
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
        val tags = detailDocument.select(".genxed a[href], .infox a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val plot = detailDocument.extractSynopsis()

        val episodes = detailDocument.select(".episodelist li a[href], ul#daftarepisode li a[href], .eplister a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = detailDocument.select(".serieslist a.series[href], .listupd .bsx a[href], article.bs a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == seriesUrl }
            .distinctBy { it.url }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, seriesUrl, type) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                showStatus = status
                score?.let { this.score = Score.from10(it) }
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val playUrl = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, seriesUrl, type, playUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                score?.let { this.score = Score.from10(it) }
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
        val document = app.get(data, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<Pair<String, String>>()

        document.select("#pembed iframe[src], .player-embed iframe[src], .megavid iframe[src], .video-content iframe[src]")
            .forEach { iframe ->
                iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    .takeIf { it.isNotBlank() }
                    ?.let { candidates.add(it to "Default") }
            }

        document.select("select.mirror option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { "Mirror" }
            NekokunExtractorHelper.decodeMirror(option.attr("value")).forEach { mirror ->
                candidates.add(mirror to label)
            }
        }

        candidates
            .filterNot { (url, _) -> NekokunExtractorHelper.isNoiseFrame(url) }
            .forEach { (url, label) ->
                NekokunExtractorHelper.resolveLink(
                    url = url,
                    label = "$name $label",
                    referer = data,
                    emitted = emitted,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

        document.select(".soraddlx a[href], .soraurlx a[href], .download a[href], a[href*='mediafire.com'], a[href*='gofile.io']")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { runCatching { loadExtractor(it, data, subtitleCallback, callback) } }

        return candidates.isNotEmpty() || emitted.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href]")
        } ?: return null

        val rawHref = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val href = getProperAnimeLink(fixUrl(rawHref))
        if (!href.contains(mainUrl) || href.contains("/genres/", true) || href.contains("/season/", true)) return null

        val title = listOf(
            link.attr("title"),
            selectFirst(".tt h2, h2[itemprop=headline], h2, h3")?.text(),
            selectFirst(".tt")?.ownText(),
            selectFirst("img")?.attr("alt"),
            link.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("img")?.imageUrl()
        val type = getType(selectFirst(".typez, .type")?.text(), href)
        val episode = Regex("""(?:Ep|Episode)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
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
        val rawTitle = selectFirst(".playinfo h3, h3, .epl-title")?.text()?.trim().orEmpty()
            .ifBlank { attr("title").trim() }
            .ifBlank { text().trim() }
        val epNum = selectFirst(".playinfo span, .epl-num, .epx")?.text()?.let { extractEpisodeNumber(it) }
            ?: extractEpisodeNumber(rawTitle)

        return newEpisode(href) {
            name = rawTitle.ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }
    }

    private fun getProperAnimeLink(url: String): String {
        if (url.contains("/anime/", true)) return url
        val clean = url.substringBefore("?").trimEnd('/')
        var slug = clean.substringAfter(mainUrl).trim('/').substringAfterLast("/")
        slug = slug
            .substringBefore("-episode-")
            .substringBefore("-subtitle-indonesia")
            .substringBefore("-sub-indo")
        return "$mainUrl/anime/$slug/"
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
        return document.select(".spe span, .info-content .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()
                    ?.replace(":", "")
                    ?.trim()
                    ?.equals(label, true) == true
            }
            ?.ownText()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Document.extractSynopsis(): String? {
        val synopsisElement = listOf(
            ".bixbox.synp .entry-content",
            ".entry-content[itemprop=description]",
            ".single-info .info-content .desc",
            ".single-info .desc",
            ".info-content .desc",
            ".bigcontent .desc",
        ).firstNotNullOfOrNull { selector ->
            selectFirst(selector)?.takeIf { element ->
                element.text().isUsefulSynopsis()
            }
        } ?: return null
        synopsisElement.select("script, style").remove()
        return synopsisElement.text()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { null }
    }

    private fun String.isUsefulSynopsis(): Boolean {
        val clean = replace(Regex("""\s+"""), " ").trim()
        if (clean.length < 30) return false
        return !clean.startsWith("Tonton streaming", true) &&
            !clean.startsWith("nonton ", true)
    }

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("type=movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value.isNullOrBlank() -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("complete", true) || value.contains("completed", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun extractEpisodeNumber(value: String): Double? {
        return Regex("""(?:Eps?|Episode)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+(?:\.\d+)?.*$"""), "")
            .trim()
    }
}
