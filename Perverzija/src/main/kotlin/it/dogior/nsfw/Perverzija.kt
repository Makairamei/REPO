package it.dogior.nsfw

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element

class Perverzija : MainAPI() {
    override var name = "Perverzija"
    override var mainUrl = "https://tube.perverzija.com"
    override val supportedTypes = setOf(TvType.NSFW)

    override val hasDownloadSupport = true
    override val hasMainPage = true

    private val cfInterceptor = CloudflareKiller()

//    override var sequentialMainPage = true
//    override var sequentialMainPageDelay = 100L
//    override var sequentialMainPageScrollDelay = 10L

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
//        "$mainUrl/studio/page/%d/?orderby=like" to "Most Liked",
        "$mainUrl/studio/page/%d/?orderby=view" to "Most Viewed",
        "$mainUrl/featured-scenes/page/%d/?orderby=date" to "Featured",
        "$mainUrl/featured-scenes/page/%d/?orderby=view" to "Featured Most Viewed",
        "$mainUrl/featured-scenes/page/%d/?orderby=like" to "Featured Most Liked",
        "$mainUrl/tag/4k-quality/" to "4K Videos",
        "$mainUrl/studio/onlyfans/page/%d/?orderby=date" to "Recent Onlyfans",
        "$mainUrl/studio/onlyfans/page/%d/?orderby=view" to "Most Viewed Onlyfans",
        "$mainUrl/studio/vxn/page/%d/?orderby=date" to "Recent Vxn",
        "$mainUrl/studio/vxn/page/%d/?orderby=view" to "Most Viewed Vxn",
        "$mainUrl/studio/wicked/page/%d/?orderby=date" to "Wicked",
        "$mainUrl/studio/nubiles/stepsiblingscaught/page/%d/?orderby=date" to "Stepsiblings Caught",
        "$mainUrl/studio/newsensations/page/%d/?orderby=date" to "NewSensations",
        "$mainUrl/studio/pornpros/page/%d/?orderby=date" to "PornPros",
        "$mainUrl/tag/18-year-old/page/%d/?orderby=date" to "18 Year Old",
        "$mainUrl/studio/digitalplayground/page/%d/?orderby=date" to "Digital Playground",
        "$mainUrl/studio/teamskeet/page/%d/?orderby=date" to "Team Skeet",
        "$mainUrl/studio/brazzers/page/%d/?orderby=date" to "Brazzers",
        "$mainUrl/studio/bangbros/page/%d/?orderby=date" to "Bang Bros",
        "$mainUrl/studio/realitykings/page/%d/?orderby=date" to "Reality Kings",
        "$mainUrl/studio/fullpornnetwork/brokensluts/page/%d/?orderby=date" to "Broken Sluts",
        "$mainUrl/studio/letsdoeit/page/%d/?orderby=date" to "LetsDoeIt",
        "$mainUrl/studio/nubiles/page/%d/?orderby=date" to "Nubiles",
        "$mainUrl/studio/adulttime/page/%d/?orderby=date" to "Adult Time",
        "$mainUrl/studio/dorcelclub/page/%d/?orderby=date" to "DorcelClub",
        "$mainUrl/studio/naughtyamerica/page/%d/?orderby=date" to "NaughtyAmerica",
        "$mainUrl/studio/pornworld/ddfnetwork/page/%d/?orderby=date" to "DDFNetwork",
        "$mainUrl/studio/fullpornnetwork/pornforce/page/%d/?orderby=date" to "PornForce",
        "$mainUrl/studio/pornpros/page/%d/?orderby=date" to "PornPros",
        "$mainUrl/studio/evilangel/page/%d/?orderby=date" to "EvilAngel",
        "$mainUrl/studio/private/page/%d/?orderby=date" to "Private",
        "$mainUrl/studio/fakehub/page/%d/?orderby=date" to "FakeHub",
        "$mainUrl/studio/sexyhub/page/%d/?orderby=date" to "SexyHub",
        "$mainUrl/studio/milehighmedia/page/%d/?orderby=date" to "MileHighMedia",
        "$mainUrl/studio/mofos/page/%d/?orderby=date" to "Mofos",
        "$mainUrl/studio/adulttime/21sextury/page/%d/?orderby=date" to "21Sextury",
        "$mainUrl/studio/bang/page/%d/?orderby=date" to "Bang",
        "$mainUrl/studio/twistysnetwork/page/%d/?orderby=date" to "Twistys Network",
        "$mainUrl/studio/cherrypimps/page/%d/?orderby=date" to "Cherry Pimps",
        "$mainUrl/studio/xempire/page/%d/?orderby=date" to "XEmpire",
        "$mainUrl/studio/sexmex/page/%d/?orderby=date" to "SexMex",
        "$mainUrl/studio/pornworld/page/%d/?orderby=date" to "PornWorld",
        "$mainUrl/studio/hustler/page/%d/?orderby=date" to "Hustler",
        "$mainUrl/studio/penthousegold/page/%d/?orderby=date" to "PenthouseGold",
        "$mainUrl/studio/babesnetwork/page/%d/?orderby=date" to "BabesNetwork",
        "$mainUrl/studio/pornfidelity/page/%d/?orderby=date" to "PornFidelity",
        "$mainUrl/studio/mypervyfamily/page/%d/?orderby=date" to "MyPervyFamily",
        "$mainUrl/studio/adulttime/puretaboo/page/%d/?orderby=date" to "PureTaboo",
        "$mainUrl/studio/teamskeet/familystrokes/page/%d/?orderby=date" to "FamilyStrokes",
        "$mainUrl/studio/teamskeet/daughterswap/page/%d/?orderby=date" to "DaughterSwap",
        "$mainUrl/studio/mylf/page/%d/?orderby=date" to "Mylf"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(request.data.format(page), interceptor = cfInterceptor, timeout = 15).document
        val home = document.select("div.row div div.post").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name, list = home, isHorizontalImages = true
            ), hasNext = true
        )
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("dt a img").attr("src"))
        val title = this.select("dd a").text()
        val href = fixUrlNull(this.select("dt a").attr("href")) ?: return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }

    }

    private fun Element.toSearchResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("div.item-thumbnail img").attr("src"))
        val title = this.select("div.item-head a").text()
        val href = fixUrlNull(this.select("div.item-head a").attr("href")) ?: return null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        // They added cloudflare in the search page
        val searchResponse = mutableListOf<SearchResponse>()
        val maxPages = if (query.contains(" ")) 6 else 20
        for (i in 1..maxPages) {
            val url = if (query.contains(" ")) {
                "$mainUrl/page/$i/?s=${query.replace(" ", "+")}&orderby=date"
            } else {
                "$mainUrl/tag/$query/page/$i/"
            }

            val results = app.get(url, interceptor = cfInterceptor).document
                .select("div.row div div.post").mapNotNull {
                    it.toSearchResult()
                }.distinctBy { it.url }
            if (results.isEmpty()) break
            else delay((100L..500L).random())
            searchResponse.addAll(results)
        }
        return searchResponse.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, interceptor = cfInterceptor).document

        val poster = document.select("div#featured-img-id img").attr("src")
        val title = document.select("div.title-info h1.light-title.entry-title").text()
        val pTags = document.select("div.item-content p")
        val description = StringBuilder().apply {
            pTags.forEach {
                append(it.text())
            }
        }.toString()

        val tags = document.select("div.item-tax-list div a").map { it.text() }

        val recommendations =
            document.select("div.related-gallery dl.gallery-item").mapNotNull {
                it.toRecommendationResult()
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val response = app.get(data)
        val document = response.document

        val iframeUrl = document.select("div#player-embed iframe").attr("src")

         Xtremestream().getUrl(iframeUrl, null)?.map(callback)
        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }
}
