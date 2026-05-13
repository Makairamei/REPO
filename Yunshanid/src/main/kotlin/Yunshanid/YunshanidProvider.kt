package Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class YunshanidProvider : MainAPI() {

    override var name = "Yunshanid"
    override var mainUrl = "https://yunshanid.site"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10)",
        "Referer" to mainUrl
    )

    // -----------------------------
    // TRANSCENDENT MEMORY GRAPH
    // -----------------------------
    private val successWeight = ConcurrentHashMap<String, Int>()
    private val failureWeight = ConcurrentHashMap<String, Int>()

    // -----------------------------
    // CONTENT RESOLVER (NO DOM RELIANCE)
    // -----------------------------
    private fun extractUrls(text: String): List<String> {
        val regex = Regex("https?://[^\"]+")
        return regex.findAll(text).map { it.value }.toList()
    }

    private fun confidence(url: String): Int {
        val base = when {
            url.contains("filemoon") -> 10
            url.contains("streamwish") -> 10
            url.contains("voe") -> 8
            url.contains("gofile") -> 8
            url.contains("mp4upload") -> 8
            else -> 3
        }

        val success = successWeight[url] ?: 0
        val fail = failureWeight[url] ?: 0

        return base + success - (fail * 2)
    }

    // -----------------------------
    // SAFE FETCH ENGINE
    // -----------------------------
    private fun fetch(url: String): String? {
        return try {
            app.get(url, headers = headers).text
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------------
    // MAIN PAGE (HYBRID RESOLVER)
    // -----------------------------
    override val mainPage = mainPageOf(
        "" to "Update",
        "category/movie/page/%d/" to "Movie",
        "category/tv-series/page/%d/" to "TV",
        "category/anime/page/%d/" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val path = if (page == 1)
            request.data.replace("/page/%d/", "/")
        else request.data.format(page)

        val doc = app.get("$mainUrl/$path", headers = headers).document

        val items = doc.select("article, .post, .bs, .item, *")
            .mapNotNull { el ->
                val text = el.text()
                val urls = extractUrls(text)

                val url = urls.firstOrNull()
                    ?: el.selectFirst("a")?.attr("href")

                val title = el.selectFirst("h1,h2,h3,.title,.tt")?.text()

                if (url == null || title == null) return@mapNotNull null

                newMovieSearchResponse(title, fixUrl(url), TvType.Movie)
            }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    // -----------------------------
    // LOAD (GRAPH-BASED RECOVERY)
    // -----------------------------
    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url, headers = headers).document

        val title =
            doc.selectFirst("h1,.entry-title,.post-title")?.text()
                ?: "No Title"

        val poster =
            doc.selectFirst("img")?.attr("src")

        val plot =
            doc.text().takeIf { it.length < 4000 }?.substring(0, minOf(250, doc.text().length))

        val episodes = doc.select("a[href]")
            .mapNotNull {
                val u = it.attr("href")
                if (!u.contains("episode")) null else fixUrl(u)
            }
            .distinct()
            .mapIndexed { i, ep ->
                newEpisode(ep) {
                    this.name = "Episode ${i + 1}"
                    this.episode = i + 1
                }
            }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
        }
    }

    // -----------------------------
    // TRANSCENDENT LOAD LINKS ENGINE
    // -----------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data, headers = headers).document

        val raw = doc.html()
        val urls = extractUrls(raw).toMutableList()

        val seen = hashSetOf<String>()
        var found = false

        fun markSuccess(url: String) {
            successWeight[url] = (successWeight[url] ?: 0) + 1
        }

        fun markFail(url: String) {
            failureWeight[url] = (failureWeight[url] ?: 0) + 1
        }

        fun extract(url: String) {
            if (!url.startsWith("http")) return
            if (!seen.add(url)) return

            runCatching {
                loadExtractor(url, data, subtitleCallback) {
                    found = true
                    markSuccess(url)
                    callback(it)
                }
            }.onFailure {
                markFail(url)
            }
        }

        // SORT BY DYNAMIC CONFIDENCE
        urls.sortedByDescending { confidence(it) }
            .forEach { extract(it) }

        // fallback DOM scan
        doc.select("iframe[src], source[src], a[href]")
            .forEach {
                extract(it.attr("src").ifBlank { it.attr("href") })
            }

        return found
    }
}