package com.anixcafe

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnixCafeProvider : MainAPI() {
    override var mainUrl = "https://anixcafe.com"
    override var name = "AnixCafe"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?order=update&status=&type=&page=%d" to "Pembaruan Terbaru",
        "$mainUrl/anime/?order=added&status=&type=&page=%d" to "Terbaru Ditambahkan",
        "$mainUrl/anime/?order=popular&status=&type=&page=%d" to "Populer",
        "$mainUrl/anime/?order=rating&status=&type=&page=%d" to "Rating",
        "$mainUrl/anime/?order=update&status=ongoing&type=&page=%d" to "Ongoing",
        "$mainUrl/anime/?order=update&status=completed&type=&page=%d" to "Completed",
        "$mainUrl/anime/?order=update&status=upcoming&type=&page=%d" to "Upcoming",
        "$mainUrl/anime/?order=update&status=hiatus&type=&page=%d" to "Hiatus",
        "$mainUrl/anime/?order=update&status=&type=tv&page=%d" to "TV Series",
        "$mainUrl/anime/?order=update&status=&type=movie&page=%d" to "Movie",
        "$mainUrl/anime/?order=update&status=&type=ova&page=%d" to "OVA",
        "$mainUrl/anime/?order=update&status=&type=live-action&page=%d" to "Live Action",
        "$mainUrl/anime/?order=update&status=&type=special&page=%d" to "Special",
        "$mainUrl/anime/?order=update&status=&type=bd&page=%d" to "BD",
        "$mainUrl/anime/?order=update&status=&type=ona&page=%d" to "ONA",
        "$mainUrl/anime/?order=update&status=&type=music&page=%d" to "Music",
        "$mainUrl/genres/action/page/%d/" to "Action",
        "$mainUrl/genres/adult-cast/page/%d/" to "Adult Cast",
        "$mainUrl/genres/adventure/page/%d/" to "Adventure",
        "$mainUrl/genres/apocalypse/page/%d/" to "Apocalypse",
        "$mainUrl/genres/boys-love/page/%d/" to "Boys Love",
        "$mainUrl/genres/childcare/page/%d/" to "Childcare",
        "$mainUrl/genres/combat-sports/page/%d/" to "Combat Sports",
        "$mainUrl/genres/comedy/page/%d/" to "Comedy",
        "$mainUrl/genres/demons/page/%d/" to "Demons",
        "$mainUrl/genres/detective/page/%d/" to "Detective",
        "$mainUrl/genres/drama/page/%d/" to "Drama",
        "$mainUrl/genres/ecchi/page/%d/" to "Ecchi",
        "$mainUrl/genres/family/page/%d/" to "Family",
        "$mainUrl/genres/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genres/food/page/%d/" to "Food",
        "$mainUrl/genres/friendship/page/%d/" to "Friendship",
        "$mainUrl/genres/game/page/%d/" to "Game",
        "$mainUrl/genres/gender-bender/page/%d/" to "Gender Bender",
        "$mainUrl/genres/gore/page/%d/" to "Gore",
        "$mainUrl/genres/gourmet/page/%d/" to "Gourmet",
        "$mainUrl/genres/harem/page/%d/" to "Harem",
        "$mainUrl/genres/hentai/page/%d/" to "Hentai",
        "$mainUrl/genres/historical/page/%d/" to "Historical",
        "$mainUrl/genres/horror/page/%d/" to "Horror",
        "$mainUrl/genres/isekai/page/%d/" to "Isekai",
        "$mainUrl/genres/iyashikei/page/%d/" to "Iyashikei",
        "$mainUrl/genres/kids/page/%d/" to "Kids",
        "$mainUrl/genres/magic/page/%d/" to "Magic",
        "$mainUrl/genres/mahou-shoujo/page/%d/" to "Mahou Shoujo",
        "$mainUrl/genres/martial-arts/page/%d/" to "Martial Arts",
        "$mainUrl/genres/mecha/page/%d/" to "Mecha",
        "$mainUrl/genres/military/page/%d/" to "Military",
        "$mainUrl/genres/music/page/%d/" to "Music",
        "$mainUrl/genres/mystery/page/%d/" to "Mystery",
        "$mainUrl/genres/mythology/page/%d/" to "Mythology",
        "$mainUrl/genres/organized-crime/page/%d/" to "Organized Crime",
        "$mainUrl/genres/parody/page/%d/" to "Parody",
        "$mainUrl/genres/political/page/%d/" to "Political",
        "$mainUrl/genres/psychological/page/%d/" to "Psychological",
        "$mainUrl/genres/regression/page/%d/" to "Regression",
        "$mainUrl/genres/reincarnation/page/%d/" to "Reincarnation",
        "$mainUrl/genres/romance/page/%d/" to "Romance",
        "$mainUrl/genres/samurai/page/%d/" to "Samurai",
        "$mainUrl/genres/school/page/%d/" to "School",
        "$mainUrl/genres/sci-fi/page/%d/" to "Sci-Fi",
        "$mainUrl/genres/seinen/page/%d/" to "Seinen",
        "$mainUrl/genres/shoujo/page/%d/" to "Shoujo",
        "$mainUrl/genres/shounen/page/%d/" to "Shounen",
        "$mainUrl/genres/shounen-ai/page/%d/" to "Shounen Ai",
        "$mainUrl/genres/slice-of-life/page/%d/" to "Slice of Life",
        "$mainUrl/genres/space/page/%d/" to "Space",
        "$mainUrl/genres/sports/page/%d/" to "Sports",
        "$mainUrl/genres/strategy-game/page/%d/" to "Strategy Game",
        "$mainUrl/genres/super-power/page/%d/" to "Super Power",
        "$mainUrl/genres/supernatural/page/%d/" to "Supernatural",
        "$mainUrl/genres/survival/page/%d/" to "Survival",
        "$mainUrl/genres/suspense/page/%d/" to "Suspense",
        "$mainUrl/genres/team-sports/page/%d/" to "Team Sports",
        "$mainUrl/genres/thriller/page/%d/" to "Thriller",
        "$mainUrl/genres/time-travel/page/%d/" to "Time Travel",
        "$mainUrl/genres/tokusatsu/page/%d/" to "Tokusatsu",
        "$mainUrl/genres/urban-fantasy/page/%d/" to "Urban Fantasy",
        "$mainUrl/genres/video-game/page/%d/" to "Video Game",
        "$mainUrl/genres/workplace/page/%d/" to "Workplace",
        "$mainUrl/genres/wuxia/page/%d/" to "Wuxia",
        "$mainUrl/genres/yuri/page/%d/" to "Yuri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.format(page), referer = "$mainUrl/").document
        val items = document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select("div.hpage a.r, a.next, .pagination .next, a.next.page-numbers, .pagination a:contains(Next), a:contains(Next »)").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.select("div.listupd article.bs, div.listupd div.bs, article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.entry-title, .bigcontent h1, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".bigcontent .thumb img, .thumbook img, meta[property=og:image]")
            ?.let { it.attr("content").ifBlank { it.imageUrl() } }

        val type = getType(detailValue(document, "Tipe"), fixedUrl)
        val year = detailValue(document, "Rilis")?.let(::extractYear)
            ?: detailValue(document, "Dirilis pada")?.let(::extractYear)
        val status = getStatus(detailValue(document, "Status"))
        val tags = document.select(".genxed a[href], .infox a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val plot = document.extractSynopsis()

        val episodes = document.select(".eplister a[href], .episodelist a[href], ul.episodios a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedByDescending { it.episode ?: -1 }

        val recommendations = document.select(".serieslist a.series[href], .listupd .bsx a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, type, fixedUrl) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<Pair<String, String>>()

        document.select("#pembed iframe[src], .player-embed iframe[src], .megavid iframe[src]").forEach { iframe ->
            iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                .takeIf { it.isNotBlank() }
                ?.let { candidates.add(it to "Default") }
        }

        document.select("select.mirror option[value]").forEach { option ->
            val label = option.text().trim().ifBlank { "Mirror" }
            AnixCafeExtractorHelper.decodeMirror(option.attr("value")).forEach { mirror ->
                candidates.add(mirror to label)
            }
        }

        candidates
            .filterNot { (url, _) ->
                AnixCafeExtractorHelper.isNoiseFrame(url) ||
                    AnixCafeExtractorHelper.isUnsupportedPlayerFrame(url)
            }
            .amap { (url, label) ->
                AnixCafeExtractorHelper.resolveLink(
                    url = AnixCafeExtractorHelper.normalizeUrl(url, data) ?: return@amap,
                    label = label,
                    referer = data,
                    emitted = emitted,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

        document.select(".soraddlx a[href], .dlbox a[href], .download a[href], a[href*='mirrored.to'], a[href*='terabox']")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .distinct()
            .forEach { runCatching { loadExtractor(it, data, subtitleCallback, callback) } }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href]") ?: return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val fixedHref = getProperAnimeLink(fixUrl(href))

        val title = listOf(
            link.attr("title"),
            selectFirst(".tt h2, .tt, h2, h3")?.text(),
            selectFirst("img")?.attr("alt")
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("img")?.imageUrl()
        val type = getType(selectFirst(".typez")?.text(), fixedHref)
        val episode = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(link.attr("title").ifBlank { text() })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, fixedHref, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = attr("abs:href").ifBlank { attr("href") }.takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        val rawTitle = selectFirst(".epl-title")?.text()?.trim().orEmpty()
            .ifBlank { attr("title").trim() }
            .ifBlank { text().trim() }
        val epNum = selectFirst(".epl-num")?.text()?.trim()?.toDoubleOrNull()
            ?: Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                .find(rawTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        return newEpisode(href) {
            name = rawTitle.cleanTitle().ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }
    }

    private fun getProperAnimeLink(url: String): String {
        if (url.contains("/anime/", true)) return url

        val rel = Regex("""rel=["'](\d+)["']""").find(url)?.groupValues?.getOrNull(1)
        if (!rel.isNullOrBlank()) return url

        var slug = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        slug = slug
            .substringBefore("-episode-")
            .substringBefore("-subtitle-indonesia")
            .replace(Regex("""-season-(\d+)"""), "-$1th-season")
        return "$mainUrl/anime/$slug/"
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
            attr("abs:src")
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select(".spe span, .infox .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()?.replace(":", "")?.trim()?.equals(label, true) == true
            }
            ?.ownText()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Document.extractSynopsis(): String? {
        val synopsisElement = selectFirst(
            ".single-info.bixbox .infox .info-content .desc, " +
                ".single-info .info-content .desc, " +
                ".bigcontent .info-content .desc, " +
                ".bigcontent .desc, " +
                ".entry-content p"
        ) ?: return null

        synopsisElement.select(".colap, script, style").remove()
        return synopsisElement.text()
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { null }
    }

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value.isNullOrBlank() -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("hiatus", true) -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+(?:\.\d+)?.*$"""), "")
            .trim()
    }
}
