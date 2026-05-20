package com.hexated

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

    override var mainUrl = "https://v0.animasu.app"
    override var name = "Animasu😸"
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

    // === FIX: PARAMETER GENRE MENGGUNAKAN HURUF KECIL (SLUG) ===
    override val mainPage = mainPageOf(
        "urutan=update" to "Baru diupdate",
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=&urutan=populer" to "Terpopuler",
        "status=&tipe=&urutan=rating" to "Rating Tertinggi",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler",
        "genre[]=action" to "Action",
        "genre[]=comedy" to "Comedy",
        "genre[]=romance" to "Romance",
        "genre[]=fantasy" to "Fantasy",
        "genre[]=drama" to "Drama",
        "genre[]=isekai" to "Isekai",
        "genre[]=school" to "School",
        "genre[]=slice-of-life" to "Slice of Life",
        "genre[]=mystery" to "Mystery",
        "genre[]=sci-fi" to "Sci-Fi",
        "genre[]=supernatural" to "Supernatural",
        "genre[]=harem" to "Harem"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

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

        return app.get("$mainUrl/?s=$query")
            .document
            .select("div.listupd div.bs")
            .map {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {

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

        val rawTags = table?.select("span:contains(Genre:) a, span:contains(Tipe Karakter:) a")
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

        val document = app.get(data).document

        document.select(".mobius > .mirror > option")
            .mapNotNull {

                val value = it.attr("value")

                if (value.isBlank()) {
                    return@mapNotNull null
                }

                val decoded =
                    base64Decode(value)
                        ?: return@mapNotNull null

                val iframeSrc =
                    Jsoup.parse(decoded)
                        .select("iframe")
                        .attr("src")

                if (iframeSrc.isBlank()) {
                    return@mapNotNull null
                }

                fixUrl(iframeSrc) to it.text()
            }
            .amap { (iframe, quality) ->

                loadFixedExtractor(
                    iframe,
                    quality,
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        loadExtractor(
            url,
            referer,
            subtitleCallback
        ) { link ->

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
