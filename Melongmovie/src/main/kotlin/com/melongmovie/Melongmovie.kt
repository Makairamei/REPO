package com.melongmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import org.jsoup.nodes.Element

class Melongmovie : MainAPI() {

    override var mainUrl = "http://139.59.189.160"
    override var name = "Melongmovie"
    override var lang = "id"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "latest-movies/page/%d/" to "Latest",
        "advanced-search/page/%d/?order=latest&type[]=post" to "All Movies"
    )

    // ---------------- MAIN PAGE ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val url = "$mainUrl/${request.data}".format(page)

        val doc = app.get(url).document

        val items = doc.select("article, .box, .post, .item").mapNotNull {
            it.toSearch()
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ---------------- SEARCH ----------------
    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("article, .box, .post, .item").mapNotNull {
            it.toSearch()
        }
    }

    // ---------------- SEARCH PARSER ----------------
    private fun Element.toSearch(): SearchResponse? {

        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.attr("title").ifBlank { a.text() }

        if (title.isBlank()) return null

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = this@toSearch.selectFirst("img")?.attr("src")
        }
    }

    // ---------------- LOAD ----------------
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text().orEmpty()
        val poster = doc.selectFirst("img")?.attr("src")
        val plot = doc.select("p").text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------- LOAD LINKS (FIX UTAMA) ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        var found = false

        // 🔥 1. iframe normal
        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        // 🔥 2. lazy iframe
        doc.select("[data-src], [data-lazy-src]").forEach {
            val src = it.attr("data-src").ifBlank { it.attr("data-lazy-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }

        // 🔥 3. script embed fallback
        doc.select("script").forEach {
            val text = it.html()
            if (text.contains("iframe")) {
                Regex("src\\s*=\\s*\"(.*?)\"")
                    .findAll(text)
                    .forEach { m ->
                        loadExtractor(m.groupValues[1], data, subtitleCallback, callback)
                        found = true
                    }
            }
        }

        return found
    }
}