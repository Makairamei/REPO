package com.alqanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Alqanime : MainAPI() {
    override var mainUrl = "https://alqanime.net"
    override var name = "Alqanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType = when {
            t.contains("Movie", true) -> TvType.AnimeMovie
            t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when {
            t.contains("Completed", true) || t.contains("Tamat", true) -> ShowStatus.Completed
            t.contains("Ongoing", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to mainUrl,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Rilisan Terbaru",
        "$mainUrl/advanced-search/page/%d/?status=ongoing&order=update" to "Sedang Tayang",
        "$mainUrl/advanced-search/page/%d/?status=completed&order=update" to "Selesai Tayang",
        "$mainUrl/advanced-search/page/%d/?type[]=movie&order=update" to "Film Layar Lebar",
        "$mainUrl/popular/page/%d/" to "Popular",
        "$mainUrl/tag/action/page/%d/" to "Action / Adventure",
        "$mainUrl/tag/adventure/page/%d/" to "Action / Adventure",
        "$mainUrl/tag/romance/page/%d/" to "Romance / Drama",
        "$mainUrl/tag/drama/page/%d/" to "Romance / Drama",
        "$mainUrl/tag/comedy/page/%d/" to "Comedy / Slice of Life",
        "$mainUrl/tag/slice-of-life/page/%d/" to "Comedy / Slice of Life",
        "$mainUrl/tag/fantasy/page/%d/" to "Fantasy / Magic / Supernatural",
        "$mainUrl/tag/magic/page/%d/" to "Fantasy / Magic / Supernatural",
        "$mainUrl/tag/supernatural/page/%d/" to "Fantasy / Magic / Supernatural",
        "$mainUrl/tag/scifi/page/%d/" to "Sci-Fi / Mecha",
        "$mainUrl/tag/mecha/page/%d/" to "Sci-Fi / Mecha",
        "$mainUrl/tag/horror/page/%d/" to "Horror / Mystery / Thriller",
        "$mainUrl/tag/mystery/page/%d/" to "Horror / Mystery / Thriller",
        "$mainUrl/tag/thriller/page/%d/" to "Horror / Mystery / Thriller",
        "$mainUrl/tag/ecchi/page/%d/" to "Ecchi / Harem / Adult",
        "$mainUrl/tag/harem/page/%d/" to "Ecchi / Harem / Adult",
        "$mainUrl/tag/adult/page/%d/" to "Ecchi / Harem / Adult",
        "$mainUrl/tag/sports/page/%d/" to "Sports / Game / Music",
        "$mainUrl/tag/game/page/%d/" to "Sports / Game / Music",
        "$mainUrl/tag/music/page/%d/" to "Sports / Game / Music",
        "$mainUrl/tag/school/page/%d/" to "School",
        "$mainUrl/tag/isekai/page/%d/" to "Isekai",
        "$mainUrl/tag/shounen/page/%d/" to "Shounen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), headers = commonHeaders).document
        val selector = "div.listupd:not(.popularslider) article.bs"
        val home = document.select(selector).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = this.selectFirst(".ntitle")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val typeText = this.selectFirst(".typez")?.text()?.trim() ?: ""
        val epNum = this.selectFirst("a")?.attr("title")
            ?.let { Regex("Episode\\s*\(\\d+)\", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val rating = this.selectFirst("div.numscore")?.text()?.trim()
        return newAnimeSearchResponse(title, href, getType(typeText)) {
            this.posterUrl = posterUrl
            addDubStatus("Sub Indo", epNum)
            this.score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        return document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders).document

        val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val title = rawTitle
            .replace(Regex("\\s*\Episode[^)]*\", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub Indo\\b.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\BD\.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*BD Batch.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("div.thumb img")?.attr("src")
        val coverBg = document.selectFirst("div.ime img")?.attr("src")
        val trailerRaw = document.selectFirst("a.trailerbutton")?.attr("href")
        val trailer = trailerRaw?.let { url ->
            val videoId = Regex("[?&]v=([^&]+)").find(url)?.groupValues?.getOrNull(1)
            if (videoId != null) "https://www.youtube.com/embed/$videoId" else url
        }
        val description = document.select("div.entry-content > p")
            .filter { it.text().length > 10 }
            .joinToString("\n\n") { it.text().trim() }
            .ifBlank { null }
        val genres = document.select("div.genxed a").map { it.text() }

        val speMap = document.select("div.spe > span").associate { span ->
            val label = span.selectFirst("b")?.text()?.trim() ?: ""
            val value = span.text().replace(label, "").trim()
            label to value
        }

        val status = getStatus(speMap.entries.find { it.key.contains("Status", true) }?.value ?: "")
        val typeText = speMap.entries.find { it.key.contains("Tipe", true) }?.value ?: ""
        val type = getType(typeText)
        val year = Regex("(\\d{4})").find(
            speMap.entries.find { it.key.contains("Dirilis", true) }?.value ?: ""
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        val japName = document.selectFirst("span.alter")?.text()?.trim()
            ?.split(",")?.firstOrNull()?.trim()?.trimStart('-')?.trimEnd('-')?.trim()
        val studio = document.selectFirst("div.spe > span:contains(Studio) a")?.text()?.trim()
        val season = document.selectFirst("div.spe > span:contains(Musim) a")?.text()?.trim()
        val duration = Regex("(\\d+)\\s*min").find(
            speMap.entries.find { it.key.contains("Durasi", true) }?.value ?: ""