package com.sad25kag.anizone

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.lagradost.cloudstream3.newSubtitleFile

class AnizoneProvider : MainAPI() {

    override var mainUrl = "https://anizone.to"
    override var name = "AniZone"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        // Kategori / genre dari tag AniZone
        "tag/hmi0gccz" to "Manga",
        "tag/bio3ygrp" to "Komedi",
        "tag/s1ssghb1" to "Fantasi",
        "tag/fxndqllf" to "Shounen",
        "tag/xottt75h" to "Aksi",
        "tag/tzgxn5ic" to "Seinen",
        "tag/2ch5lzak" to "Novel",
        "tag/fqe1dvxj" to "Romantis",
        "tag/n6ta6ma6" to "Kehidupan Sekolah",
        "tag/7kov4siq" to "Kekerasan"
    )

    private fun translateGenre(name: String): String {
        return when (name.trim().lowercase(Locale.ROOT)) {
            "action" -> "Aksi"
            "adventure" -> "Petualangan"
            "comedy" -> "Komedi"
            "daily life", "slice of life" -> "Kehidupan Sehari-hari"
            "fantasy" -> "Fantasi"
            "high school" -> "SMA"
            "historical" -> "Sejarah"
            "manga" -> "Manga"
            "novel" -> "Novel"
            "romance" -> "Romantis"
            "school life" -> "Kehidupan Sekolah"
            "seinen" -> "Seinen"
            "shounen" -> "Shounen"
            "sports" -> "Olahraga"
            "thriller" -> "Thriller"
            "tragedy" -> "Tragedi"
            "violence" -> "Kekerasan"
            else -> name.trim()
        }
    }

    private var cookies = mutableMapOf<String, String>()
    private var wireData = mutableMapOf(
        "wireSnapshot" to "",
        "token" to ""
    )

    private suspend fun initializeLiveWire(): Boolean {
        if (!wireData["wireSnapshot"].isNullOrBlank()) LicenseClient.trackActivity(name, "PLAY", data)
        return true

        try {
            val initReq = app.get("$mainUrl/anime")
            val doc = initReq.document

            val csrfToken = doc.select("script[data-csrf]").attr("data-csrf")
            val snapshot = getSnapshot(doc)

            if (csrfToken.isBlank() || snapshot.isBlank()) {
                Log.e("AniZone Init", "Inisialisasi gagal: token atau snapshot kosong.")
                return false
            }

            this.cookies = initReq.cookies.toMutableMap()
            wireData["token"] = csrfToken
            wireData["wireSnapshot"] = snapshot

            sortAnimeLatest()
            LicenseClient.trackActivity(name, "PLAY", data)
        return true
        } catch (e: Exception) {
            Log.e("AniZone Init", "Error initializeLiveWire: ${e.message}")
            return false
        }
    }

    private suspend fun sortAnimeLatest() {
        try {
            liveWireBuilder(
                mapOf("sort" to "release-desc"),
                mutableListOf(),
                this.cookies,
                this.wireData,
                true
            )
        } catch (e: Exception) {
            Log.e("AniZone Init", "Error sortAnimeLatest: ${e.message}")
        }
    }

    private fun getSnapshot(doc: Document): String {
        return doc.select("main div[wire:snapshot]")
            .attr("wire:snapshot")
            .replace("&quot;", "\"")
    }

    private fun getSnapshot(json: JSONObject): String {
        return json.getJSONArray("components")
            .getJSONObject(0)
            .getString("snapshot")
    }

    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(
            json.getJSONArray("components")
                .getJSONObject(0)
                .getJSONObject("effects")
                .getString("html")
        )
    }

    private suspend fun liveWireBuilder(
        updates: Map<String, String>,
        calls: List<Map<String, Any>>,
        biscuit: MutableMap<String, String>,
        wireCreds: MutableMap<String, String>,
        remember: Boolean
    ): JSONObject {
        val payload = mapOf(
            "_token" to wireCreds["token"],
            "components" to listOf(
                mapOf(
                    "snapshot" to wireCreds["wireSnapshot"],
                    "updates" to updates,
                    "calls" to calls
                )
            )
        )

        val jsonString = payload.toJson()
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonString.toRequestBody(mediaType)

        val req = app.post(
            url = "$mainUrl/livewire/update",
            requestBody = requestBody,
            headers = mapOf(
                "X-CSRF-TOKEN" to wireCreds["token"]!!
            ),
            cookies = biscuit,
            referer = "$mainUrl/anime"
        )

        val bodyString = req.text

        if (bodyString.isBlank()) {
            throw Exception("Respons Livewire kosong. HTTP ${req.code}.")
        }

        if (
            bodyString.trim().startsWith("<!DOCTYPE", ignoreCase = true) ||
            bodyString.trim().startsWith("<html", ignoreCase = true)
        ) {
            Log.e("AniZone", "Livewire mengembalikan HTML, bukan JSON.")
            throw Exception("Livewire tidak mengembalikan JSON. HTTP ${req.code}. URL: ${req.url}")
        }

        val responseJson = JSONObject(bodyString)

        if (remember) {
            wireCreds["wireSnapshot"] = getSnapshot(responseJson)
            biscuit.putAll(req.cookies)
        }

        return responseJson
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        if (request.data.startsWith("tag/")) {
            return getTagMainPage(page, request)
        }

        val initialized = initializeLiveWire()
        if (!initialized) {
            Log.w("AniZone", "Inisialisasi LiveWire gagal.")
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }

        return try {
            var responseJson = liveWireBuilder(
                mapOf("type" to request.data),
                mutableListOf(),
                this.cookies,
                this.wireData,
                true
            )

            var doc = getHtmlFromWire(responseJson)

            for (i in 1 until page) {
                if (doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") == null) break

                responseJson = liveWireBuilder(
                    mutableMapOf(),
                    mutableListOf(
                        mapOf(
                            "path" to "",
                            "method" to "loadMore",
                            "params" to listOf<String>()
                        )
                    ),
                    this.cookies,
                    this.wireData,
                    true
                )

                doc = getHtmlFromWire(responseJson)
            }

            val home = doc.select("div[wire:key]")

            newHomePageResponse(
                HomePageList(request.name, home.map { toResult(it) }, isHorizontalImages = false),
                hasNext = doc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") != null
            )
        } catch (e: Exception) {
            Log.e("AniZone", "Gagal memproses getMainPage: ${e.message}")
            newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
    }

    private suspend fun getTagMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val url = if (page <= 1) {
                "$mainUrl/${request.data}"
            } else {
                "$mainUrl/${request.data}?page=$page"
            }

            val doc = app.get(url).document

            val items = doc.select("div[wire:key]")
                .ifEmpty { doc.select("article") }
                .ifEmpty { doc.select("a[href*='/anime/']").mapNotNull { it.parent() } }
                .distinctBy {
                    it.selectFirst("a[href*='/anime/']")?.attr("href") ?: it.text()
                }
                .map { toResult(it) }
                .filter { it.name.isNotBlank() }

            val hasNext = doc.selectFirst(
                "a[rel=next], a[href*='page=${page + 1}'], .h-12[x-intersect=\"\$wire.loadMore()\"]"
            ) != null

            newHomePageResponse(
                HomePageList(request.name, items, isHorizontalImages = false),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            Log.e("AniZone", "Gagal memuat kategori/tag ${request.data}: ${e.message}")
            newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
    }

    private fun toResult(post: Element): SearchResponse {
        val title = post.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: post.selectFirst("h2, h3, a[href*='/anime/']")?.text()?.trim()
            ?: ""

        val url = post.selectFirst("a[href*='/anime/']")?.attr("href")
            ?: post.selectFirst("a")?.attr("href")
            ?: ""

        return newMovieSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = post.selectFirst("img")?.attr("src")
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        initializeLiveWire()

        val doc = getHtmlFromWire(
            liveWireBuilder(
                mapOf("search" to query),
                mutableListOf(),
                this.cookies,
                this.wireData,
                false
            )
        )

        return doc.select("div[wire:key]").map { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val r = Jsoup.connect(url)
            .method(Connection.Method.GET)
            .execute()

        val doc = Jsoup.parse(r.body())
        val cookie = r.cookies()

        val wireData = mutableMapOf(
            "wireSnapshot" to getSnapshot(doc = r.parse()),
            "token" to doc.select("script[data-csrf]").attr("data-csrf")
        )

        val title = doc.selectFirst("h1")?.text()
            ?: throw NotImplementedError("Unable to find title")

        val bgImage = doc.selectFirst("main img")?.attr("src")
        val synopsis = doc.selectFirst(".sr-only + div")?.text() ?: ""
        val rowLines = doc.select("span.inline-block").map { it.text() }
        val releasedYear = rowLines.getOrNull(3)

        val status = when (rowLines.getOrNull(1)) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> null
        }

        val genres = doc.select("a[wire:navigate][wire:key]").map {
            translateGenre(it.text())
        }

        val imdbLink = doc.selectFirst("a[href*='imdb.com']")

        val imdbId = imdbLink?.attr("href")
            ?.substringAfter("title/")
            ?.trimEnd('/', ' ', '?')
            ?.let {
                if (it.startsWith("tt") && it.length > 2) it else "tt0000000"
            }
            ?: "tt0000000"

        Log.d("AniZoneIMDB", "IMDB ID ditemukan di LOAD: $imdbId")

        var currentDoc = doc
        var attempts = 0
        val maxAttempts = 100

        while (
            currentDoc.selectFirst(".h-12[x-intersect=\"\$wire.loadMore()\"]") != null &&
            attempts < maxAttempts
        ) {
            attempts++
            try {
                val responseJson = liveWireBuilder(
                    mutableMapOf(),
                    mutableListOf(
                        mapOf(
                            "path" to "",
                            "method" to "loadMore",
                            "params" to listOf<String>()
                        )
                    ),
                    cookie,
                    wireData,
                    true
                )

                currentDoc = getHtmlFromWire(responseJson)
            } catch (e: Exception) {
                Log.e("AniZone Load", "Error loadMore episode percobaan $attempts: ${e.message}")
                break
            }
        }

        val epiElms = currentDoc.select("li[x-data]")

        val episodes = epiElms.map { elt ->
            newEpisode(
                data = elt.selectFirst("a")?.attr("href") ?: ""
            ) {
                this.name = elt.selectFirst("h3")?.text()
                    ?.substringAfter(":")
                    ?.trim()

                this.season = 0
                this.posterUrl = elt.selectFirst("img")?.attr("src")
                this.data = "${elt.selectFirst("a")?.attr("href")}|||$imdbId"

                this.date = elt.selectFirst("span[title]")
                    ?.selectFirst("span.line-clamp-1")
                    ?.text()
                    ?.trim()
                    ?.replace(Regex("\\s+"), "")
                    ?.ifEmpty { null }
                    ?.let { dateText ->
                        Log.d("AniZone", "Tanggal ditemukan untuk ${this.name}: $dateText")

                        try {
                            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                                .parse(dateText)
                                ?.time
                        } catch (e: Exception) {
                            Log.e("AniZone", "Gagal parse tanggal '$dateText': ${e.message}")
                            null
                        }
                    } ?: 0L
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = bgImage
            this.plot = synopsis
            this.tags = genres
            this.year = releasedYear?.toIntOrNull()
            this.showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val parts = data.split("|||")
        val episodeUrl = parts[0]

        Log.d("AniZoneSub", "Mulai loadLinks: $episodeUrl")

        val webReq = app.get(episodeUrl)
        val web = webReq.document
        val cookie = webReq.cookies

        val sourceName = web.selectFirst("span.truncate")?.text() ?: name
        val mediaPlayer = web.selectFirst("media-player")
        val masterUrl = mediaPlayer?.attr("src") ?: ""

        Log.d("AniZoneSub", "Source: $sourceName, M3U8: $masterUrl")

        if (masterUrl.isBlank()) return false

        mediaPlayer?.select("track")?.forEach {
            Log.d("AniZoneSub", "Subtitle ditemukan: ${it.attr("label")}")
            subtitleCallback.invoke(
                newSubtitleFile(
                    it.attr("label"),
                    it.attr("src")
                )
            )
        }

        val baseHeaders = mapOf(
            "Origin" to mainUrl,
            "Accept" to "*/*",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Cookie" to cookie.map { "${it.key}=${it.value}" }.joinToString("; ")
        )

        callback.invoke(
            newExtractorLink(
                sourceName,
                name,
                masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = episodeUrl
                this.quality = 0
                this.headers = baseHeaders
            }
        )

        LicenseClient.trackActivity(name, "PLAY", data)
        return true
    }
}