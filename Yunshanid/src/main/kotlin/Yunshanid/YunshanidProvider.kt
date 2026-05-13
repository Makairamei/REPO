package Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class YunshanidProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10)",
        "Referer" to "$mainUrl/"
    )

    // ----------------------------
    // LIGHT CACHE (FINAL BOSS OPT)
    // ----------------------------
    private val cache = ConcurrentHashMap<String, String>()

    private fun cachedGet(url: String): String {
        return cache[url] ?: app.get(url, headers = headers).text.also {
            cache[url] = it
        }
    }

    private fun Element.textAny(vararg sel: String): String? {
        for (s in sel) {
            val t = selectFirst(s)?.text()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun Element.attrAny(attr: String, vararg sel: String): String? {
        for (s in sel) {
            val v = selectFirst(s)?.attr(attr)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    // ----------------------------
    // MAIN PAGE (ROBUST)
    // ----------------------------
    override val mainPage = mainPageOf(
        "" to "Update",
        "category/movie/page/%d/" to "Movie",
        "category/tv-series/page/%d/" to "TV",
        "category/anime/page/%d/" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val path = if (page == 1)
            request.data.replace("/page/%d/", "/")
        else
            request.data.format(page)

        val doc = app.get("$mainUrl/$path", headers = headers).document

        val items = doc.select("article, .bs, .post, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val title = textAny(".tt", "h2", ".title", ".post-title") ?: return null

        val url = fixUrl(selectFirst("a")?.attr("href") ?: return null)

        val poster = attrAny("src", "img", ".thumb img", ".poster img")
            ?: selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // ----------------------------
    // SEARCH (SAFE)
    // ----------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query", headers = headers)
            .document
            .select("article, .bs, .post, .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ----------------------------
    // LOAD (SELF HEALING)
    // ----------------------------
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, headers = headers).document

        val title =
            doc.selectFirst("h1, .entry-title, .post-title")?.text()
                ?: "No Title"

        val poster =
            doc.selectFirst(".poster img, .thumb img, img")?.attr("src")

        val plot =
            doc.selectFirst(".entry-content p, .synopsis, .desc")?.text()

        val episodes = doc.select(".eplister li a, .list-episode li a, .num-ep a")
            .mapIndexedNotNull { i, e ->
                val epUrl = fixUrl(e.attr("href"))
                val epName = e.text().ifBlank { "Episode ${i + 1}" }

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = i + 1
                }
            }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ----------------------------
    // FINAL BOSS LOAD LINKS ENGINE
    // ----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

        val seen = hashSetOf<String>()
        var found = false

        val priority = listOf(
            "filemoon",
            "streamwish",
            "gofile",
            "mp4upload",
            "voe"
        )

        fun safeExtract(url: String) {
            if (!url.startsWith("http")) return
            if (!seen.add(url)) return

            runCatching {
                loadExtractor(url, data, subtitleCallback) {
                    found = true
                    callback(it)
                }
            }
        }

        val sources = doc.select(
            "iframe[src], iframe[data-src], option[value], a[href]"
        )

        // PASS 1: priority routing (FAST PATH)
        for (s in sources) {
            val url = s.attr("src")
                .ifBlank { s.attr("data-src") }
                .ifBlank { s.attr("value") }
                .ifBlank { s.attr("href") }

            if (priority.any { url.contains(it) }) {
                safeExtract(url)
            }
        }

        // PASS 2: fallback routing (FULL SCAN)
        for (s in sources) {
            val url = s.attr("src")
                .ifBlank { s.attr("data-src") }
                .ifBlank { s.attr("value") }
                .ifBlank { s.attr("href") }

            safeExtract(url)
        }

        return found
    }
}