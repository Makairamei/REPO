package com.sad25kag.eporner

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

class EPorner : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "EPorner"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        // SISTEM ANTREAN: Memaksa request beranda berjalan bergantian agar tidak diblokir server
        private val mutex = Mutex()
    }

    // Mengadopsi daftar kategori terlengkap dari kode referensi pilihan bosku
    override val mainPage = mainPageOf(
        "" to "Recent Videos",
        "best-videos" to "Best Videos",
        "top-rated" to "Top Rated",
        "most-viewed" to "Most Viewed",
        "cat/milf" to "Milf",
        "cat/japanese" to "Japanese",
        "cat/hd-1080p" to "1080 Porn",
        "cat/4k-porn" to "4K Porn",
        "country-top/id" to "Indonesia"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        // Pembuatan URL dinamis yang rapi untuk menangani halaman pertama vs halaman berikutnya
        val url = if (request.data.isEmpty()) {
            if (page == 1) "$mainUrl/" else "$mainUrl/$page/"
        } else {
            if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/$page/"
        }

        var home: List<SearchResponse> = emptyList()

        // Eksekusi aman via Mutex lock dengan taktik Auto-Retry bawaan
        mutex.withLock {
            for (i in 1..2) {
                try {
                    val document = app.get(url, timeout = 15L).document
                    // Mendukung penyeleksian elemen dari kedua versi layout HTML EPorner
                    val elements = document.select("#div-search-results div.mb, div#vidresults div.mb")
                    home = elements.mapNotNull { it.toSearchResult() }
                    if (home.isNotEmpty()) break
                } catch (e: Exception) {
                    delay(1000L)
                }
            }
            delay(250L) // Jeda nafas agar server tidak mendeteksi aktivitas bot
        }

        // KUNCI UTAMA: Membungkus data ke dalam HomePageList agar menu kategori muncul di HP lu!
        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val titleElement = this.selectFirst("div.mbunder p.mbtit a, p.title a") ?: return null
            val title = titleElement.text().trim()
            
            val hrefElement = this.selectFirst("div.mbcontent a, p.title a") ?: return null
            val href = fixUrl(hrefElement.attr("href"))
            
            val img = this.selectFirst("img") ?: return null
            val posterUrl = img.attr("data-src").ifBlank { img.attr("src") }

            return newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val url = "$mainUrl/search/${query.replace(" ", "-")}/"
        val document = app.get(url).document
        return document.select("#div-search-results div.mb, div#vidresults div.mb").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document
        val title = document.selectFirst("h1, meta[property=og:title]")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        
        val recommendationsList = document.select("div#relateddiv div.mb, #div-search-results div.mb").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.recommendations = recommendationsList
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCaster: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        var videoFound = false
        val url = data
        val resolver = WebViewResolver(
            """www\.eporner\.com/xhr/video/.*""".toRegex(),
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            useOkhttp = true
        )

        try {
            val capturedUrl = app.get(url, interceptor = resolver).url
            if (capturedUrl.contains("/xhr/video/")) {
                val responseText = app.get(capturedUrl).text

                """"(\d{3,4}p)[^"]*"\s*:\s*\{\s*"labelShort"\s*:\s*"[^"]*"\s*,\s*"src"\s*:\s*"([^"]+)"""".toRegex()
                    .findAll(responseText).forEach { match ->
                        val videoUrl = match.groupValues[2]
                        val rawQuality = match.groupValues[1]
                        
                        if (!videoUrl.contains("/dload/")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = videoUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://www.eporner.com/"
                                    this.quality = rawQuality.replace(Regex("\\D"), "").toIntOrNull() ?: 0
                                }
                            )
                            videoFound = true
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("EPorner", "loadLinks failed for url=$url: ${e.message}")
        }

        return videoFound
    }
}