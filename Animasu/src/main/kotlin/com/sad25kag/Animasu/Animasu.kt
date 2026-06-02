package com.sad25kag.Animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Animasu : MainAPI() {

    override var mainUrl = "https://v1.animasu.work"
    override var name = "Animasu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {

        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime

            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed

            return when {
                t.contains("Sedang Tayang", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    // === PERBAIKAN: MENGHAPUS GENRE YANG RUSAK/KOSONG, MENGEMBALIKAN KE STRUKTUR ASLI ===
    override val mainPage = mainPageOf(
        "urutan=update" to "Baru diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")

        val document =
            app.get("$mainUrl/pencarian/?${request.data}&halaman=$page").document

        val home = document.select("div.listupd div.bs").map {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {

            var title = uri.substringAfter("$mainUrl/")

            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> {
                    title.substringBefore("-episode")
                }

                (title.contains("-movie")) -> {
                    title.substringBefore("-movie")
                }

                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {

        val href =
            getProperAnimeLink(
                fixUrlNull(
                    this.selectFirst("a")?.attr("href")
                ).toString()
            )

        val title = this.select("div.tt").text().trim()

        val posterUrl =
            fixUrlNull(this.selectFirst("img")?.getImageAttr())

        val epNum =
            this.selectFirst("span.epx")
                ?.text()
                ?.filter { it.isDigit() }
                ?.toIntOrNull()

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)

        return app.get("$mainUrl/?s=$query")
            .document
            .select("div.listupd div.bs")
            .map {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)

        val document = app.get(url).document

        val title =
            document.selectFirst("div.infox h1")
                ?.text()
                .toString()
                .replace("Sub Indo", "")
                .trim()

        val poster =
            document.selectFirst("div.bigcontent img")
                ?.getImageAttr()

        val table =
            document.selectFirst("div.infox div.spe")

        val type =
            getType(
                table?.selectFirst("span:contains(Jenis:)")
                    ?.ownText()
            )

        val year =
            table?.selectFirst("span:contains(Rilis:)")
                ?.ownText()
                ?.substringAfterLast(",")
                ?.trim()
                ?.toIntOrNull()

        val status =
            table?.selectFirst("span:contains(Status:) font")
                ?.text()

        val trailer =
            document.selectFirst("div.trailer iframe")
                ?.attr("src")

        val episodes =
            document.select("ul#daftarepisode > li").mapNotNull {

                val aTag = it.selectFirst("a")
                    ?: return@mapNotNull null

                val link = fixUrl(aTag.attr("href"))

                val name = aTag.text()

                val episode =
                    Regex("Episode\\s?(\\d+)")
                        .find(name)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                newEpisode(link) {
                    this.episode = episode
                }
            }.reversed()

        val tracker = try {
            APIHolder.getTracker(
                listOf(title),
                TrackerType.getTypes(type),
                year,
                true
            )
        } catch (_: Exception) {
            null
        }

        val rawTags = table?.select("span:contains(Genre:) a")
            ?.map { it.text().trim() } ?: emptyList()

        return newAnimeLoadResponse(
            title,
            url,
            type
        ) {

            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover

            this.year = year

            addEpisodes(
                DubStatus.Subbed,
                episodes
            )

            showStatus = getStatus(status)

            plot =
                document.select("div.sinopsis p")
                    .text()

            this.tags = rawTags.map { tag ->
                AnimasuTagCategory.getCategoryByTag(tag)
            }.distinct()

            addTrailer(trailer)

            addMalId(tracker?.malId)

            addAniListId(
                tracker?.aniId?.toIntOrNull()
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)

        val document = app.get(data).document
        val candidates = linkedSetOf<Pair<String, String?>>()

        document.select("#pembed iframe[src], .player-embed iframe[src]").forEach { iframe ->
            normalizeIframeUrl(iframe.attr("src"))?.let { candidates.add(it to "Default") }
        }

        document.select(".mobius > .mirror > option, .mobius select.mirror option").forEach { option ->
            val value = option.attr("value")
            if (value.isBlank()) return@forEach

            val decoded = runCatching { base64Decode(value) }.getOrNull() ?: return@forEach
            val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe[src]")?.attr("src") ?: return@forEach
            normalizeIframeUrl(iframeSrc)?.let { candidates.add(it to option.text()) }
        }

        var hasLinks = false

        candidates.forEach { (iframe, quality) ->
            val emitted = if (iframe.contains("blogger.com/video.g", ignoreCase = true)) {
                emitBloggerVideo(iframe, quality, data, callback)
            } else {
                loadFixedExtractor(
                    iframe,
                    quality,
                    data,
                    subtitleCallback,
                    callback
                )
            }

            if (emitted) hasLinks = true
        }

        return hasLinks
    }

    private fun normalizeIframeUrl(url: String?): String? {
        val raw = url
            ?.replace("&amp;", "&")
            ?.trim()
            ?.trim('"', '\'', ' ')
            ?: return null

        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", true)) return null

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> fixUrl(raw)
        }
    }

    private suspend fun emitBloggerVideo(
        url: String,
        quality: String?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val videos = extractBloggerDirectVideos(url, referer)
        if (videos.isEmpty()) return false

        videos.forEach { videoUrl ->
            callback.invoke(
                newExtractorLink(
                    "Blogger",
                    "Blogger ${quality.orEmpty()}".trim(),
                    videoUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.blogger.com/"
                    this.quality = qualityFromBloggerUrl(videoUrl).takeIf { it != Qualities.Unknown.value }
                        ?: getIndexQuality(quality)
                    this.headers = mapOf("Referer" to "https://www.blogger.com/")
                }
            )
        }

        LicenseClient.trackActivity(name, "PLAY")
        return true
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String): List<String> {
        val token = Regex("""[?&]token=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val page = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
        }.getOrNull() ?: return emptyList()

        val html = page.text
        val cookies = page.cookies
        val fSid = Regex("""FdrFJe":"(-?\d+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val bl = Regex("""cfb2h":"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val hl = Regex("""lang="([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "id"

        val rpcId = "WcwnYd"
        val reqId = (System.currentTimeMillis() % 90000L + 10000L).toString()
        val payload = """[[["$rpcId","[\"$token\",null,0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = runCatching {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = url,
                cookies = cookies,
                headers = mapOf(
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1"
                )
            ).text
        }.getOrNull() ?: return emptyList()

        val decoded = decodeBloggerEscapes(response)

        return Regex("""https://[^\s"'\\]+""")
            .findAll(decoded)
            .map { it.value }
            .filter { it.contains("googlevideo.com/videoplayback", ignoreCase = true) }
            .map { decodeBloggerEscapes(it) }
            .distinct()
            .toList()
    }

    private fun decodeBloggerEscapes(input: String): String {
        var output = input
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }

        return output
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\=", "=")
            .replace("\\&", "&")
            .replace("\\\"", "\"")
    }

    private fun qualityFromBloggerUrl(url: String): Int {
        val itag = Regex("""[?&]itag=(\d+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return when (itag) {
            37, 96, 137, 248, 299 -> Qualities.P1080.value
            22, 59, 136, 247, 298 -> Qualities.P720.value
            18, 134, 244 -> Qualities.P360.value
            135 -> Qualities.P480.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var emitted = false

        loadExtractor(
            url,
            referer,
            subtitleCallback
        ) { link ->

            emitted = true

            runBlocking {

                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {

                        this.referer = link.referer

                        this.quality =
                            if (
                                link.type == ExtractorLinkType.M3U8 ||
                                link.name == "Uservideo"
                            ) {
                                link.quality
                            } else {
                                getIndexQuality(quality)
                            }

                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }

        return emitted
    }

    private fun getIndexQuality(str: String?): Int {

        return Regex("(\\d{3,4})[pP]")
            .find(str ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun Element.getImageAttr(): String? {

        return when {

            this.hasAttr("data-src") -> {
                this.attr("abs:data-src")
            }

            this.hasAttr("data-lazy-src") -> {
                this.attr("abs:data-lazy-src")
            }

            this.hasAttr("srcset") -> {
                this.attr("abs:srcset")
                    .substringBefore(" ")
            }

            else -> {
                this.attr("abs:src")
            }
        }
    }
}

enum class AnimasuTagCategory(val title: String, val tagsList: List<String>) {
    ACTION_ADVENTURE("Action & Adventure", listOf("Action", "Adventure", "Martial Arts", "Samurai", "Super Power", "Survival", "Military")),
    COMEDY("Comedy", listOf("Comedy", "Gag Humor", "Parody")),
    DRAMA_ROMANCE("Drama & Romance", listOf("Drama", "Romance", "Boys Love", "Girls Love", "School")),
    FANTASY_SCIFI("Fantasy & Sci-Fi", listOf("Fantasy", "Sci-Fi", "Supernatural", "Isekai", "Magic", "Demons", "Vampire", "Mecha", "Space", "Time Travel", "Mythology")),
    MYSTERY_HORROR("Mystery & Horror", listOf("Mystery", "Thriller", "Suspense", "Detective", "Police", "Psychological", "Horror", "Gore")),
    SLICE_OF_LIFE("Slice of Life", listOf("Slice of Life", "Iyashikei", "Kids", "Workplace")),
    SPORTS_GAMES("Sports & Games", listOf("Sports", "Racing", "Strategy Game", "Game")),
    ARTS_CULTURE("Arts & Music", listOf("Music", "Idol", "Historical", "Performing Arts")),
    MATURE("Mature & Ecchi", listOf("Ecchi", "Harem", "Reverse Harem")),
    DEMOGRAPHICS("Demographics", listOf("Shounen", "Shoujo", "Seinen", "Josei")),
    
    MC_PERSONALITY_GOOD("MC: Kepribadian Baik", listOf("Ambisi", "Berjuang", "Beruntung", "Blakblakan", "Ceria", "Jenius", "Optimis", "Pemimpin", "Polos", "Semangat", "Setia", "Sopan", "Totalitas")),
    MC_PERSONALITY_QUIRKY("MC: Sifat Negatif/Eksentrik", listOf("Anti-Sosial", "Berisik", "Cerewet", "Ceroboh", "Kejam", "Licik", "Mencolok", "Menyebalkan", "Mesum", "Narsis", "Pemalas", "Pemalu", "Penakut", "Pendiam", "Pesimis", "Slengekan", "Suram")),
    MC_IDENTITY("MC: Identitas & Profesi", listOf("Anak-Anak", "Berbisnis", "Bounty Hunter", "Cewek", "Cowok", "Dewa", "Iblis", "Loli", "Monster", "Vampir")),
    MC_TROPE("MC: Trope Anime", listOf("Badass", "Couple", "Dikagumi", "Disepelekan", "Ditakuti", "Legenda", "Overpower", "Terkutuk", "Tsundere", "Yandere", "Zero To Hero"));

    companion object {
        fun getCategoryByTag(tag: String): String {
            return entries.find { category ->
                category.tagsList.any { it.equals(tag, ignoreCase = true) }
            }?.title ?: tag
        }
    }
}
