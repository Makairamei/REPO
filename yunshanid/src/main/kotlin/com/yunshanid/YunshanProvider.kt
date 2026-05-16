package com.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class YunshanProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override var lang = "id"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Home"
    )

    // ================= HOME =================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data).document
        return YunshanParser.parseHome(doc, request.name)
    }

    // ================= SEARCH =================
    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/?s=$query").document
        return YunshanParser.parseSearch(doc)
    }

    // ================= LOAD DETAIL =================
    override suspend fun load(url: String): LoadResponse? {

        val doc = app.get(url).document
        return YunshanParser.parseDetail(doc, url)
    }

    // ================= LOAD LINKS (STREAM ENGINE) =================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        // ambil semua link download / stream
        val links = doc.select("div#downloadb a")

        links.forEach { a ->

            val url = fixUrl(a.attr("href"))

            if (url.isBlank()) return@forEach

            try {
                loadExtractor(url, subtitleCallback) { link ->

                    callback.invoke(
                        ExtractorLink(
                            source = "YunshanID",
                            name = link.name,
                            url = link.url,
                            referer = link.referer ?: mainUrl,
                            quality = Qualities.P1080.value,
                            isM3u8 = link.url.contains("m3u8")
                        )
                    )
                }
            } catch (_: Exception) {
                // silent fail biar tidak crash playback
            }
        }

        return true
    }
}