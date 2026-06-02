package com.istarvin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Element

class Sulasok : MainAPI() {
    override var mainUrl = "https://sulasok.uno"
    override var name = "Sulasok"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"

    private val videoCount = 20
    private val bgUrlRegex = Regex("""url\("(.+)"""")

    override val mainPage = mainPageOf(
        "load_more.php?limit=$videoCount&filter=best" to "Trending",
        "load_more.php?limit=$videoCount" to "Latest",
        "load_more.php?limit=$videoCount&filter=longest" to "Longest",
        "load_more_random.php?limit=$videoCount" to "Random",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = "$mainUrl/${request.data}&start=${(page - 1) * videoCount}"
        val document = app.get(url).document
        val home = document.select("div.col").mapNotNull { it.toSearchResult() }
        val newRequest = request.copy(horizontalImages = true)
        return newHomePageResponse(newRequest, home, home.size == videoCount)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".video_title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href")
            ?.replace("watch.php", "video.php")
            ?: return null

        val posterUrl = selectFirst("div.itemsContainer")?.attr("style")
            ?.let { bgUrlRegex.find(it)?.groupValues?.get(1) }
            ?.let { "$mainUrl/$it" }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val url =
            "$mainUrl/load_more_search.php?start=${(page - 1) * videoCount}&limit=$videoCount&search=$query"
        val document = app.get(url).document
        val list = document.select("div.col").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(list, list.size == videoCount)
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val request = app.get(url)
        val document = request.document
        val title = document.title()
        val posterUrl = document.getElementsByAttributeValue("property", "og:image")
            .attr("content")
            .let { "$mainUrl/$it" }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
        }
    }

    private val sources = listOf("vidara", "streamruby")
    private val iframeSrcRegex = Regex("""iframe.src = "(.*)";""")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val executionList: List<suspend () -> Unit> = sources.map { source ->
            suspend {
                val text = app.get("$data&s=$source").text
                val src = iframeSrcRegex.find(text)?.groupValues?.get(1) ?: return@map

                // Force use my own streamruby extractor
                // Remove if StreamPlay rubyvidhub extractor is updated
                if (source == "streamruby") {
                    getExtractorApiFromName("RubyVidHub").getUrl(
                        src,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    return@map
                }

                loadExtractor(src, subtitleCallback, callback)
            }
        }

        runLimitedAsync(tasks = executionList.toTypedArray())
        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }

    private suspend fun runLimitedAsync(
        concurrency: Int = 5,
        vararg tasks: suspend () -> Unit
    ) = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope

        val semaphore = Semaphore(concurrency)

        tasks.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        task()
                    } catch (e: Exception) {
                        Log.e("SulasokConcurrency", "Task failed: ${e.message}")
                    }
                }
            }
        }.awaitAll()
    }
}
