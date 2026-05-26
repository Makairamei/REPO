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
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList = arrayOf(
            "Mega",
            "MegaUp",
            "Otakufiles",
        )

        var context: android.content.Context? = null

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        // Kategori Utama
        "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
        "$mainUrl/complete-anime/page/" to "Anime Completed",
        
        // Kategori Genre (Perbaikan: Menggunakan 'genre' bukan 'genres')
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/adventure/page/" to "Adventure",
        "$mainUrl/genre/comedy/page/" to "Comedy",
        "$mainUrl/genre/demons/page/" to "Demons",
        "$mainUrl/genre/drama/page/" to "Drama",
        "$mainUrl/genre/ecchi/page/" to "Ecchi",
        "$mainUrl/genre/fantasy/page/" to "Fantasy",
        "$mainUrl/genre/game/page/" to "Game",
        "$mainUrl/genre/harem/page/" to "Harem",
        "$mainUrl/genre/historical/page/" to "Historical",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/genre/isekai/page/" to "Isekai",
        "$mainUrl/genre/josei/page/" to "Josei",
        "$mainUrl/genre/magic/page/" to "Magic",
        "$mainUrl/genre/martial-arts/page/" to "Martial Arts",
        "$mainUrl/genre/mecha/page/" to "Mecha",
        "$mainUrl/genre/military/page/" to "Military",
        "$mainUrl/genre/music/page/" to "Music",
        "$mainUrl/genre/mystery/page/" to "Mystery",
        "$mainUrl/genre/psychological/page/" to "Psychological",
        "$mainUrl/genre/romance/page/" to "Romance",
        "$mainUrl/genre/samurai/page/" to "Samurai",
        "$mainUrl/genre/school/page/" to "School",
        "$mainUrl/genre/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/genre/seinen/page/" to "Seinen",
        "$mainUrl/genre/shoujo/page/" to "Shoujo",
        "$mainUrl/genre/shoujo-ai/page/" to "Shoujo Ai",
        "$mainUrl/genre/shounen/page/" to "Shounen",
        "$mainUrl/genre/slice-of-life/page/" to "Slice of Life",
        "$mainUrl/genre/space/page/" to "Space",
        "$mainUrl/genre/sports/page/" to "Sports",
        "$mainUrl/genre/super-power/page/" to "Super Power",
        "$mainUrl/genre/supernatural/page/" to "Supernatural",
        "$mainUrl/genre/thriller/page/" to "Thriller",
        "$mainUrl/genre/vampire/page/" to "Vampire"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Menambahkan '/' di akhir agar menghindari error redirect pada web Otakudesu
        val url = "${request.data}$page/" 
        val document = app.get(url).document
        
        // Membaca dari kedua layout web Otakudesu (.venz untuk utama, .chivsrc untuk genre)
        val home = document.select("div.venz > ul > li, ul.chivsrc > li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val titleElement = this.selectFirst("h2.jdlflm, h2 > a") ?: return null
        val title = titleElement.text().trim()
        val href = this.selectFirst("a[href]")?.attr("href") ?: return null
        
        // Perbaikan pembacaan poster agar muncul
        val posterUrl = this.selectFirst("img")?.attr("src")?.toString()
        val epNum = this.selectFirst("div.epz")?.text()?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type=anime"
        val document = app.get(url).document

        return document.select("ul.chivsrc > li").mapNotNull { li ->
            val titleElement = li.selectFirst("h2 > a") ?: return@mapNotNull null
            val title = titleElement.ownText().trim()
            val href = fixUrl(titleElement.attr("href"))
            val posterUrl = li.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")?.ownText()
            ?.replace(":", "")?.trim().toString()
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
        val tags = document.select("div.infozingle > p:nth-child(11) > span > a").map { it.text() }
        val type = getType(
            document.selectFirst("div.infozingle > p:nth-child(5) > span")?.ownText()
                ?.replace(":", "")?.trim() ?: "tv"
        )

        val year = Regex("\\d, (\\d*)").find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst("div.infozingle > p:nth-child(6) > span")!!.ownText()
                .replace(":", "")
                .trim()
        )
        val description = document.select("div.sinopc > p").text()

        val episodes = document.select("div.episodelist")[1].select("ul > li").mapNotNull {
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val episode = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(0)
                ?: it.selectFirst("a")?.text()
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            newEpisode(link) { this.episode = episode?.toIntOrNull() }
        }.reversed()

        val recommendations =
            document.select("div.isi-recommend-anime-series > div.isi-konten").map {
                val recName = it.selectFirst("span.judul-anime > a")!!.text()
                val recHref = it.selectFirst("a")!!.attr("href")
                val recPosterUrl = it.selectFirst("a > img")?.attr("src").toString()
                newAnimeSearchResponse(recName, recHref, TvType.Anime) {
                    this.posterUrl = recPosterUrl
                }
            }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }


    data class ResponseSources(
        @JsonProperty("id") val id: String,
        @JsonProperty("i") val i: String,
        @JsonProperty("q") val q: String,
    )

    data class ResponseData(
        @JsonProperty("data") val data: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        // ====== BLOK 1 ======
        try {
            val scriptData = document.select("script:containsData(action:)").lastOrNull()?.data()
            val token = scriptData?.substringAfter("{action:\"")?.substringBefore("\"}").toString()

            val nonce = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf("action" to token)
            ).parsed<ResponseData>().data

            val action = scriptData?.substringAfter(",action:\"")?.substringBefore("\"}").toString()

            val mirrorData = document.select("div.mirrorstream > ul > li").mapNotNull {
                base64Decode(it.select("a").attr("data-content"))
            }.toString()

            tryParseJson<List<ResponseSources>>(mirrorData)?.forEach { res ->
                val id = res.id
                val i = res.i
                val q = res.q

                val sources = Jsoup.parse(
                    base64Decode(
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "id" to id,
                                "i" to i,
                                "q" to q,
                                "nonce" to nonce,
                                "action" to action
                            )
                        ).parsed<ResponseData>().data
                    )
                ).select("iframe").attr("src")

                loadCustomExtractor(sources, data, subtitleCallback, callback, getQuality(q))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ====== BLOK 2 ======
        document.select("div.download li").forEach { ele ->
            val quality = getQuality(ele.select("strong").text())
            ele.select("a").map {
                it.attr("href") to it.text()
            }.filter {
                !inBlacklist(it.first) && quality != Qualities.P360.value
            }.forEach {
                val link = app.get(it.first, referer = "$mainUrl/").url
                loadCustomExtractor(
                    fixedIframe(link),
                    data,
                    subtitleCallback,
                    callback,
                    quality
                )
            }
        }

        return true
    }

    private suspend fun loadCustomExtractor(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int = Qualities.Unknown.value,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun fixedIframe(url: String): String {
        return when {
            url.startsWith(acefile) -> {
                val id = Regex("""(?:/f/|/file/)(\w+)""").find(url)?.groupValues?.getOrNull(1)
                "${acefile}/player/$id"
            }

            else -> fixUrl(url)
        }
    }

    private fun inBlacklist(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

class Moedesu : JWPlayer() {
    override val name = "Moedesu"
    override val mainUrl = "https://desustream.me/moedesu/"
}

class DesuBeta : JWPlayer() {
    override val name = "DesuBeta"
    override val mainUrl = "https://desustream.me/beta/"
}

class Desudesuhd : JWPlayer() {
    override val name = "Desudesuhd"
    override val mainUrl = "https://desustream.me/desudesuhd/"
}

class Odvidhide : Filesim() {
    override val name = "Odvidhide"
    override var mainUrl = "https://odvidhide.com"
}
