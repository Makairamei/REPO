package com.sontolfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class SontolFilmProvider : MainAPI() {
    override var mainUrl = "https://sontolfilm.com"
    override var name = "SontolFilm"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "/page/%d/" to "Update Terbaru",
        "/tv/page/%d/" to "Series",
        "/category/dubbing/page/%d/" to "Dubbing",
        "/country/indonesia/page/%d/" to "Film Indonesia",
        "/country/korea/page/%d/" to "Korea",
        "/category/animation/page/%d/" to "Animation",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(pageUrl(request.data, page), referer = "$mainUrl/").document
        val items = document.toSearchResults()
        val hasNext = document.select("link[rel=next], a.next.page-numbers, a.page-numbers[href*='/page/${page + 1}/']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: url
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.entry-title[itemprop=name], h1.entry-title, meta[property=og:title], title")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("figure.pull-left img, meta[property=og:image], img.wp-post-image")
            ?.imageUrl()
            ?.fixImageQuality()
        val plot = document.plot()
        val tags = document.infoLinks("Genre")
        val actors = document.infoLinks("Cast")
        val year = document.infoText("Year")?.extractYear()
        val duration = document.infoText("Duration")?.extractInt()
        val rating = document.selectFirst("span[itemprop=ratingValue]")?.text()?.toDoubleOrNull()
        val recommendations = document.select("div.idmuvi-core article.item, div.gmr-grid.idmuvi-core article")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val episodes = document.select("div.gmr-listseries a[href*='/eps/']")
            .mapNotNull { it.toEpisode() }
            .filterNot { it.data.contains("download", true) || it.name?.contains("download", true) == true }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: Int.MAX_VALUE })

        val type = if (fixedUrl.contains("/tv/", true) || episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, fixedUrl, type, episodes.ifEmpty { listOf(newEpisode(fixedUrl)) }) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                addActors(actors)
                duration?.let { this.duration = it }
                rating?.let { score = Score.from10(it) }
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                addActors(actors)
                duration?.let { this.duration = it }
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
        val fixedUrl = normalizeUrl(data, mainUrl) ?: data
        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        var handled = false

        document.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
            val iframeUrl = iframe.iframeUrl(fixedUrl) ?: return@forEach
            handled = true
            runCatching { loadExtractor(iframeUrl, fixedUrl, subtitleCallback, callback) }
        }

        val postId = document.selectFirst("div#muvipro_player_content_id[data-id]")?.attr("data-id")
        val tabs = document.select("div.tab-content-ajax[id]").map { it.id() }.ifEmpty {
            document.select("ul.muvipro-player-tabs a[href^='#']").map { it.attr("href").removePrefix("#") }
        }.filter { it.isNotBlank() }.distinct()

        if (!postId.isNullOrBlank()) {
            for (tab in tabs) {
                val ajaxDocument = runCatching {
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        referer = fixedUrl,
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tab,
                            "post_id" to postId,
                        )
                    ).document
                }.getOrNull() ?: continue

                ajaxDocument.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { iframe ->
                    val iframeUrl = iframe.iframeUrl(fixedUrl) ?: return@forEach
                    handled = true
                    runCatching { loadExtractor(iframeUrl, fixedUrl, subtitleCallback, callback) }
                }
            }
        }

        document.select("ul.gmr-download-list a[href]").forEach { link ->
            val href = normalizeUrl(link.attr("href"), fixedUrl) ?: return@forEach
            handled = true
            runCatching { loadExtractor(href, fixedUrl, subtitleCallback, callback) }
        }

        return handled
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("div#gmr-main-load article.item, div#gmr-main-load article.item-infinite, article.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title a[href], .content-thumbnail a[href], a[href][itemprop=url]") ?: return null
        val href = normalizeUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.contains(mainUrl)) return null

        val title = listOf(
            selectFirst("h2.entry-title a[href]")?.text(),
            anchor.attr("title").substringAfter("Permalink to:", anchor.attr("title")),
            selectFirst("img[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst(".content-thumbnail img, img")?.imageUrl()?.fixImageQuality()
        val rating = selectFirst(".gmr-rating-item")?.text()?.toScore()
        val quality = select(".gmr-quality-item a, .gmr-qual").text().trim()
        val isSeries = href.contains("/tv/", true) ||
            selectFirst(".gmr-numbeps span, .gmr-posttype-item") != null ||
            text().contains("TV Show", true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                rating?.let { score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (quality.isNotBlank()) addQuality(quality)
                rating?.let { score = Score.from10(it) }
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        val label = text().replace(Regex("""\s+"""), " ").trim()
        val title = attr("title").substringAfter("Permalink to", "").ifBlank { label }.trim()
        val episodeNumber = Regex("""(?:eps?|episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(label.ifBlank { title })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        val seasonNumber = Regex("""\bS(?:eason)?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""-season-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = label.ifBlank { episodeNumber?.let { "Episode $it" } ?: title }
            episode = episodeNumber
            season = seasonNumber
        }
    }

    private fun Document.plot(): String? {
        val content = selectFirst("div.entry-content[itemprop=description], div.entry-content-single")?.clone()
            ?: return selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        content.select(".content-moviedata, .tags-links-content, #download, script, style").remove()
        return content.select("p").joinToString("\n") { it.text().trim() }
            .ifBlank { content.text().trim() }
            .ifBlank { null }
    }

    private fun Document.infoText(label: String): String? {
        return select("div.gmr-moviedata")
            .firstOrNull { it.selectFirst("strong")?.text()?.cleanLabel()?.equals(label, true) == true }
            ?.clone()
            ?.also { it.select("strong").remove() }
            ?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Document.infoLinks(label: String): List<String> {
        return select("div.gmr-moviedata")
            .firstOrNull { it.selectFirst("strong")?.text()?.cleanLabel()?.equals(label, true) == true }
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
    }

    private fun Element.iframeUrl(baseUrl: String): String? {
        return listOf(
            attr("data-litespeed-src"),
            attr("data-src"),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(httpsify(it), baseUrl) }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        val path = if (page <= 1) pattern.replace("/page/%d/", "/") else pattern.format(page)
        return normalizeUrl(path, mainUrl) ?: "$mainUrl/"
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = Jsoup.parse(raw).text()
            .trim()
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)^Permalink to:\s*"""), "")
            .replace(Regex("""(?i)\s+-\s+Sontol Film$"""), "")
            .replace(Regex("""(?i)\s+Subtitle Indonesia$"""), " Subtitle Indonesia")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanLabel(): String {
        return replace(":", "").replace(Regex("""\s+"""), " ").trim()
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("""-\d+x\d+(?=\.[a-zA-Z]{3,4}(?:$|[?#]))"""), "")
    }

    private fun String.extractYear(): Int? {
        return Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()
    }

    private fun String.extractInt(): Int? {
        return Regex("""\d+""").find(this)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }
}
