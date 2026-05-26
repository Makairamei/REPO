package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.JWPlayer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class OtakudesuProvider : MainAPI() {
    override var mainUrl = "https://otakudesu.best"
    override var name = "Otakudesu🧶"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList = arrayOf("Mega", "MegaUp", "Otakufiles")
        
        fun getType(t: String): TvType = when {
            t.contains("OVA", true) || t.contains("Special") -> TvType.OVA
            t.contains("Movie", true) -> TvType.AnimeMovie
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = if (t.contains("Ongoing", true)) ShowStatus.Ongoing else ShowStatus.Completed
    }

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
        "$mainUrl/complete-anime/page/" to "Anime Completed",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/adventure/page/" to "Adventure",
        "$mainUrl/genre/comedy/page/" to "Comedy",
        "$mainUrl/genre/drama/page/" to "Drama",
        "$mainUrl/genre/fantasy/page/" to "Fantasy",
        "$mainUrl/genre/harem/page/" to "Harem",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/genre/isekai/page/" to "Isekai",
        "$mainUrl/genre/magic/page/" to "Magic",
        "$mainUrl/genre/martial-arts/page/" to "Martial Arts",
        "$mainUrl/genre/mystery/page/" to "Mystery",
        "$mainUrl/genre/romance/page/" to "Romance",
        "$mainUrl/genre/school/page/" to "School",
        "$mainUrl/genre/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/genre/shounen/page/" to "Shounen",
        "$mainUrl/genre/slice-of-life/page/" to "Slice of Life",
        "$mainUrl/genre/supernatural/page/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val document = app.get(url).document
        val home = document.select("div.venz > ul > li, ul.chivsrc > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm, h2 > a")?.text()?.trim() ?: return null
        val href = this.selectFirst("a[href]")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")?.toString()
        val epNum = this.selectFirst("div.epz")?.text()?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=anime"
        return app.get(url).document.select("ul.chivsrc > li").mapNotNull { li ->
            val a = li.selectFirst("h2 > a") ?: return@mapNotNull null
            newAnimeSearchResponse(a.ownText().trim(), fixUrl(a.attr("href")), TvType.Anime) {
                this.posterUrl = li.selectFirst("img")?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")?.ownText()?.replace(":", "")?.trim().toString()
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type = getType(document.selectFirst("div.infozingle > p:nth-child(5) > span")?.ownText()?.replace(":", "")?.trim() ?: "tv")
        val status = getStatus(document.selectFirst("div.infozingle > p:nth-child(6) > span")?.ownText()?.replace(":", "")?.trim() ?: "")
        val description = document.select("div.sinopc > p").text()

        val episodes = document.select("div.episodelist")[1].select("ul > li").mapNotNull {
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            newEpisode(link) { this.episode = Regex("\\d+").find(name)?.value?.toIntOrNull() }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("div.download li").forEach { ele ->
            val quality = getQuality(ele.select("strong").text())
            ele.select("a").filter { !it.attr("href").contains("Mega") }.forEach {
                loadCustomExtractor(fixUrl(it.attr("href")), data, subtitleCallback, callback, quality)
            }
        }
        return true
    }

    private suspend fun loadCustomExtractor(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, quality: Int) {
        loadExtractor(url, referer, subtitleCallback) { callback.invoke(it.copy(quality = quality)) }
    }

    private fun getQuality(str: String?): Int = Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Qualities.Unknown.value
}
