package com.putarflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PutarFlixProvider : MainAPI() {
    override var mainUrl = PutarFlixSeeds.MAIN_URL
    override var name = PutarFlixConstants.PROVIDER_NAME
    override val hasMainPage = true
    override var lang = PutarFlixConstants.LANG
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Terbaru",
        "$mainUrl/movie/" to "Movie",
        "$mainUrl/tv/" to "Series"
    )

    private fun Element.toSearchResponse(base: String): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = PutarFlixUtils.absoluteUrl(base, anchor.attr("href")) ?: return null
        if (!PutarFlixUtils.isContentUrl(href)) return null

        val title = PutarFlixParser.titleFrom(this).ifBlank { return null }
        val poster = PutarFlixUtils.pickImage(base, selectFirst("img"), this)
            ?: PutarFlixUtils.extractMetaImage(base, ownerDocument())
        val type = PutarFlixUtils.typeFrom(href, title)

        return newSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${PutarFlixUtils.encode(query)}"
        val doc = app.get(url).document
        return PutarFlixParser.itemNodes(doc).mapNotNull { it.toSearchResponse(mainUrl) }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = PutarFlixUtils.pageUrl(request.data, page)
        val doc = app.get(url).document
        val home = PutarFlixParser.itemNodes(doc).mapNotNull { it.toSearchResponse(mainUrl) }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = PutarFlixUtils.absoluteUrl(mainUrl, url) ?: url
        val doc = app.get(fixedUrl).document

        val title = PutarFlixUtils.cleanTitle(
            doc.selectFirst("h1, .entry-title, .data h1, .sheader .data h1")?.text() ?: doc.title()
        )
        val poster = PutarFlixUtils.extractMetaImage(fixedUrl, doc)
            ?: PutarFlixUtils.pickImage(fixedUrl, doc.selectFirst(".poster img, .thumb img, img"))
        val description = PutarFlixUtils.cleanText(
            doc.selectFirst(".entry-content p, .desc p, .wp-content p, .overview, .synopsis, .plot")?.text()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
        ).ifBlank { null }

        val year = PutarFlixUtils.extractYear(doc.text())
        val duration = PutarFlixUtils.extractDuration(doc.text())
        val rating = PutarFlixUtils.extractRating(doc.text())?.toRatingInt()
        val type = PutarFlixUtils.typeFrom(fixedUrl, title, doc.text())

        val episodes = if (type != TvType.Movie) {
            doc.select("a[href]").mapNotNull { a ->
                val href = PutarFlixUtils.absoluteUrl(fixedUrl, a.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/eps/")) return@mapNotNull null
                val name = PutarFlixUtils.cleanText(a.text()).ifBlank { PutarFlixUtils.extractLabelNear(a) }
                newEpisode(href) {
                    this.name = name
                    this.episode = PutarFlixUtils.episodeNumber(name)
                    this.season = PutarFlixUtils.seasonNumber(name)
                }
            }.distinctBy { it.data }
        } else emptyList()

        return if (type == TvType.Movie) {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                plot = description
                this.year = year
                this.duration = duration
                this.rating = rating
            }
        } else {
            newTvSeriesLoadResponse(title, fixedUrl, type, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.duration = duration
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = PutarFlixUtils.absoluteUrl(mainUrl, data) ?: data
        val doc = app.get(url).document
        val candidates = linkedSetOf<String>()

        doc.select("iframe[src], a[href], source[src], video[src]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("href") }
            val abs = PutarFlixUtils.absoluteUrl(url, raw) ?: return@forEach
            candidates += PutarFlixUtils.decodeKnownRedirect(abs)
        }

        doc.select("script").forEach { script ->
            PutarFlixUtils.extractUrlsFromText(url, script.data()).forEach { found ->
                candidates += PutarFlixUtils.decodeKnownRedirect(found)
            }
        }

        val filtered = candidates
            .filterNot { PutarFlixUtils.isRejectedVideoCandidate(it) }
            .filter { PutarFlixUtils.looksDirectVideo(it) || PutarFlixUtils.isKnownPlayableHost(it) }
            .distinct()

        return PutarFlixExtractor.extract(filtered, url, subtitleCallback, callback, name)
    }

    private fun String.toRatingInt(): Int? {
        val score = this.toDoubleOrNull() ?: return null
        return (score * 1000).toInt()
    }
}
