package com.sad25kag.alqanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
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
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Rilisan Terbaru",
        "$mainUrl/advanced-search/page/%d/?status=ongoing&order=update" to "Sedang Tayang",
        "$mainUrl/advanced-search/page/%d/?status=completed&order=update" to "Selesai Tayang",
        "$mainUrl/advanced-search/page/%d/?type[]=movie&order=update" to "Film Layar Lebar",
        "$mainUrl/popular/page/%d/" to "Popular",
        "$mainUrl/tag/action/page/%d/" to "Action",
        "$mainUrl/tag/adventure/page/%d/" to "Adventure",
        "$mainUrl/tag/comedy/page/%d/" to "Comedy",
        "$mainUrl/tag/demons/page/%d/" to "Demons",
        "$mainUrl/tag/drama/page/%d/" to "Drama",
        "$mainUrl/tag/ecchi/page/%d/" to "Ecchi",
        "$mainUrl/tag/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/tag/harem/page/%d/" to "Harem",
        "$mainUrl/tag/historical/page/%d/" to "Historical",
        "$mainUrl/tag/horror/page/%d/" to "Horror",
        "$mainUrl/tag/isekai/page/%d/" to "Isekai",
        "$mainUrl/tag/josei/page/%d/" to "Josei",
        "$mainUrl/tag/kids/page/%d/" to "Kids",
        "$mainUrl/tag/magic/page/%d/" to "Magic",
        "$mainUrl/tag/martial-arts/page/%d/" to "Martial Arts",
        "$mainUrl/tag/mecha/page/%d/" to "Mecha",
        "$mainUrl/tag/military/page/%d/" to "Military",
        "$mainUrl/tag/music/page/%d/" to "Music",
        "$mainUrl/tag/mystery/page/%d/" to "Mystery",
        "$mainUrl/tag/parody/page/%d/" to "Parody",
        "$mainUrl/tag/police/page/%d/" to "Police",
        "$mainUrl/tag/psychological/page/%d/" to "Psychological",
        "$mainUrl/tag/romance/page/%d/" to "Romance",
        "$mainUrl/tag/samurai/page/%d/" to "Samurai",
        "$mainUrl/tag/school/page/%d/" to "School",
        "$mainUrl/tag/sci-fi/page/%d/" to "Sci-Fi",
        "$mainUrl/tag/seinen/page/%d/" to "Seinen",
        "$mainUrl/tag/shoujo/page/%d/" to "Shoujo",
        "$mainUrl/tag/shounen/page/%d/" to "Shounen",
        "$mainUrl/tag/slice-of-life/page/%d/" to "Slice of Life",
        "$mainUrl/tag/space/page/%d/" to "Space",
        "$mainUrl/tag/sports/page/%d/" to "Sports",
        "$mainUrl/tag/supernatural/page/%d/" to "Supernatural",
        "$mainUrl/tag/thriller/page/%d/" to "Thriller",
        "$mainUrl/tag/vampire/page/%d/" to "Vampire"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(request.data.format(page), headers = commonHeaders).document
        val selector = "div.listupd:not(.popularslider) article.bs"
        val home = document.select(selector).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val title = selectFirst(".ntitle")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""
        val epNum = selectFirst("a")?.attr("title")
            ?.let {
                Regex("Episode\\s*\\((\\d+)\\)", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        val rating = selectFirst("div.numscore")?.text()?.trim()
        return newAnimeSearchResponse(title, href, getType(typeText)) {
            this.posterUrl = posterUrl
            addDubStatus("Sub Indo", epNum)
            this.score = Score.from10(rating)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val document = app.get("$mainUrl/?s=$query", headers = commonHeaders).document
        return document.select("article.bs").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url, headers = commonHeaders).document

        val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val title = rawTitle
            .replace(Regex("\\s*\\(Episode[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub Indo\\b.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(BD\\).*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*BD Batch.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("div.thumb img")?.attr("src")
        val coverBg = document.selectFirst("div.ime img")?.attr("src")
        val trailerRaw = document.selectFirst("a.trailerbutton")?.attr("href")
        val trailer = trailerRaw?.let { trailerUrl ->
            val videoId = Regex("[?&]v=([^&]+)").find(trailerUrl)?.groupValues?.getOrNull(1)
            if (videoId != null) "https://www.youtube.com/embed/$videoId" else trailerUrl
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
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        val actors = document.select("div.spe span:contains(Casts) a.casts").map { Actor(it.text()) }
        val scoreText = document.selectFirst("strong:contains(Score)")?.text()
            ?.replace("Score", "")?.trim()

        val episodes = mutableListOf<Episode>()

        for (col in document.select("div.sorattl.collapsible")) {
            val epTitle = col.selectFirst("h3")?.text()?.trim() ?: continue
            if (epTitle.equals("Batch", ignoreCase = true)) continue

            val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val contentDiv = col.nextElementSibling()
                ?.takeIf { it.hasClass("content") }
                ?: continue

            val pixeldrainFolderIds = mutableListOf<String>()

            for (tr in contentDiv.select("tr")) {
                for (a in tr.select("div.slink a")) {
                    val resolved = resolveUrl(a.attr("href"))
                    val listId = Regex("pixeldrain\\.com/l/([A-Za-z0-9]+)")
                        .find(resolved)?.groupValues?.getOrNull(1)
                    if (listId != null) pixeldrainFolderIds.add(listId)
                }
            }

            if (pixeldrainFolderIds.isNotEmpty()) {
                val epMap = mutableMapOf<Int, MutableList<EpisodeLink>>()
                val epThumbs = mutableMapOf<Int, String>()

                for (listId in pixeldrainFolderIds) {
                    try {
                        val apiJson = app.get("https://pixeldrain.com/api/list/$listId")
                            .parsedSafe<PixeldrainList>()
                        apiJson?.files
                            ?.filter { it.mimeType.startsWith("video/") }
                            ?.sortedBy { it.name }
                            ?.forEach { file ->
                                val fileEpNum = Regex("(?:_|-)0*(\\d+)(?:_|-)")
                                    .find(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    ?: return@forEach
                                val fileQuality = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                                    .find(file.name)?.groupValues?.getOrNull(1) ?: ""
                                val streamUrl = "https://pixeldrain.com/api/file/${file.id}"
                                epMap.getOrPut(fileEpNum) { mutableListOf() }
                                    .add(EpisodeLink(streamUrl, fileQuality))
                                if (!epThumbs.containsKey(fileEpNum)) {
                                    epThumbs[fileEpNum] =
                                        "https://pixeldrain.com/api/file/${file.id}/thumbnail"
                                }
                            }
                    } catch (_: Exception) {
                    }
                }

                for ((episodeNumber, links) in epMap.toSortedMap()) {
                    episodes.add(newEpisode(links.toEpisodeJson()) {
                        this.name = "Episode $episodeNumber"
                        this.episode = episodeNumber
                        this.posterUrl = epThumbs[episodeNumber]
                    })
                }
            }

            if (pixeldrainFolderIds.isEmpty()) {
                val linkList = mutableListOf<EpisodeLink>()
                for (tr in contentDiv.select("tr")) {
                    val quality = tr.selectFirst("div.res")?.text()?.trim() ?: continue
                    for (a in tr.select("div.slink a")) {
                        linkList.add(EpisodeLink(a.attr("href"), quality))
                    }
                }

                if (linkList.isNotEmpty()) {
                    episodes.add(newEpisode(linkList.toEpisodeJson()) {
                        this.name = epTitle
                        this.episode = epNum
                    })
                }
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            this.japName = japName
            engName = title
            posterUrl = poster
            backgroundPosterUrl = coverBg
            this.year = year
            this.duration = duration
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            addTrailer(trailer, addRaw = true)
            this.tags = listOfNotNull(*genres.toTypedArray(), studio, season)
            addActors(actors)
            this.score = Score.from10(scoreText?.toFloatOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val links = parseEpisodeLinks(data)
        if (links.isEmpty()) return false

        var emitted = false
        val emittedUrls = linkedSetOf<String>()

        fun markEmit(link: ExtractorLink) {
            if (emittedUrls.add(link.url.substringBefore("#"))) {
                callback(link)
                emitted = true
            }
        }

        suspend fun emitDirect(
            source: String,
            url: String,
            quality: Int,
            referer: String,
            headers: Map<String, String> = commonHeaders + mapOf("Referer" to referer)
        ): Boolean {
            val fixedUrl = url.cleanEscaped().replace(".txt", ".m3u8")
            if (!fixedUrl.isPlayableMediaUrl()) return false
            val type = if (fixedUrl.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            markEmit(newExtractorLink(source, source, fixedUrl, type) {
                this.referer = referer
                this.quality = quality
                this.headers = headers
            })
            LicenseClient.trackActivity(name, "PLAY", data)
        return true
        }

        for (linkData in links) {
            val resolvedUrl = resolveUrl(linkData.url)
            if (resolvedUrl.isBlank()) continue

            val qualityInt = linkData.quality.fixQuality()

            if (resolvedUrl.contains("pixeldrain.com/api/file/", true)) {
                markEmit(newExtractorLink("Pixeldrain", "Pixeldrain", resolvedUrl) {
                    this.referer = "https://pixeldrain.com/"
                    this.quality = qualityInt
                })
                continue
            }

            if (resolvedUrl.contains("mediafire.com", true)) {
                if (tryMediafire(resolvedUrl, qualityInt, ::markEmit)) continue
            }

            if (resolvedUrl.contains("acefile.co", true)) {
                if (tryAcefile(resolvedUrl, qualityInt, ::markEmit, subtitleCallback)) continue
            }

            val directFromUrl = emitDirect(
                source = name,
                url = resolvedUrl,
                quality = qualityInt,
                referer = "$mainUrl/"
            )
            if (directFromUrl) continue

            try {
                loadExtractor(resolvedUrl, "$mainUrl/", subtitleCallback) { link ->
                    markEmit(link)
                }
            } catch (_: Exception) {
            }
        }

        return emitted
    }

    private suspend fun tryMediafire(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(
                url,
                headers = commonHeaders + mapOf("Referer" to "$mainUrl/"),
                referer = "$mainUrl/",
                timeout = 20000L
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text.cleanEscaped()
        val candidates = linkedSetOf<String>()

        document.select(
            "a#downloadButton[href], a.input.popsok[href], a[aria-label*=Download][href], " +
                "a[href*=download][href*=mediafire], a[href*=mediafire][href*=download]"
        ).forEach { element ->
            fixUrlNull(element.attr("href"))?.let { candidates.add(it.cleanEscaped()) }
        }

        Regex("""https?://download[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { candidates.add(it) }

        Regex("""(?i)(?:href|url)\s*[:=]\s*["']([^"']*download[^"']*mediafire[^"']*)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.cleanEscaped() }
            .forEach { fixUrlNull(it)?.let(candidates::add) }

        for (candidate in candidates) {
            val fixed = candidate.cleanEscaped()
            if (!fixed.contains("mediafire.com", true) && !fixed.contains("download", true)) continue
            callback(newExtractorLink("MediaFire", "MediaFire", fixed, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = quality
                this.headers = commonHeaders + mapOf("Referer" to url)
            })
            LicenseClient.trackActivity(name, "PLAY", data)
        return true
        }

        return false
    }

    private suspend fun tryAcefile(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val pages = linkedSetOf<String>()
        val pageQueue = mutableListOf<String>()
        fun addPage(page: String) {
            if (pages.add(page)) pageQueue.add(page)
        }

        addPage(url)

        val id = Regex("""acefile\.co/(?:f|player)/(\w+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)

        if (id != null) {
            addPage("https://acefile.co/f/$id")
            addPage("https://acefile.co/player/$id")
        }

        var emitted = false
        var index = 0

        while (index < pageQueue.size) {
            val pageUrl = pageQueue[index++]
            val response = runCatching {
                app.get(
                    pageUrl,
                    headers = commonHeaders + mapOf("Referer" to "$mainUrl/"),
                    referer = "$mainUrl/",
                    timeout = 20000L
                )
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text.cleanEscaped()
            val referer = pageUrl

            document.select("iframe[src], embed[src]").forEach { iframe ->
                normalizeUrl(iframe.attr("src"), pageUrl)?.let { addPage(it) }
            }

            val directLinks = collectPlayableUrls(document, html, pageUrl)
            for (direct in directLinks) {
                val type = if (direct.contains(".m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                callback(newExtractorLink("AceFile", "AceFile", direct, type) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = commonHeaders + mapOf("Referer" to referer)
                })
                emitted = true
            }

            if (emitted) LicenseClient.trackActivity(name, "PLAY", data)
        return true

            runCatching {
                loadExtractor(pageUrl, "$mainUrl/", subtitleCallback) { link ->
                    callback(link)
                    emitted = true
                }
            }
        }

        return emitted
    }

    private fun collectPlayableUrls(
        document: org.jsoup.nodes.Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val results = linkedSetOf<String>()

        document.select("video[src], video source[src], source[src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            normalizeUrl(raw, baseUrl)?.cleanEscaped()?.takeIf { it.isPlayableMediaUrl() }?.let(results::add)
        }

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.cleanEscaped() }
            .forEach { results.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:%2Em3u8|%2Emp4|%2Ewebm)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map {
                runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped() }
            .forEach { results.add(it) }

        Regex("""(?i)(?:file|src|source|url|video)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .mapNotNull { normalizeUrl(it, baseUrl) }
            .map { it.cleanEscaped() }
            .filter { it.isPlayableMediaUrl() }
            .forEach { results.add(it) }

        return results.toList()
    }

    private fun normalizeUrl(url: String?, baseUrl: String = mainUrl): String? {
        val clean = url?.cleanEscaped().orEmpty()
        if (clean.isBlank() || clean == "#" || clean.startsWith("javascript:", true)) return null
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = runCatching {
                    val uri = URI(baseUrl)
                    "${uri.scheme}://${uri.host}"
                }.getOrDefault(mainUrl)
                origin + clean
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }
                .getOrElse { fixUrl(clean) }
        }
    }

    private fun resolveUrl(url: String): String {
        val cleanUrl = url.cleanEscaped()
        if (cleanUrl.isBlank() || cleanUrl == "#") return ""

        if (cleanUrl.contains("ouo.io", true) || cleanUrl.contains("ouo.press", true)) {
            val sParam = Regex("[?&]s=([^&]+)")
                .find(cleanUrl)
                ?.groupValues
                ?.getOrNull(1)
            if (sParam != null) {
                return runCatching { URLDecoder.decode(sParam, "UTF-8") }
                    .getOrDefault(sParam)
                    .cleanEscaped()
            }
        }

        if (cleanUrl.contains("acefile.co/f/", true)) {
            val id = Regex("/f/(\\w+)")
                .find(cleanUrl)
                ?.groupValues
                ?.getOrNull(1)
            if (id != null) return "https://acefile.co/player/$id"
        }

        return fixUrl(cleanUrl)
    }

    private fun parseEpisodeLinks(data: String): List<EpisodeLink> {
        return runCatching {
            val array = JSONArray(data)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val rawUrl = item.optString("url").trim()
                    val quality = item.optString("quality").trim()
                    if (rawUrl.isNotBlank()) add(EpisodeLink(rawUrl, quality))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun List<EpisodeLink>.toEpisodeJson(): String {
        val array = JSONArray()
        forEach { link ->
            array.put(
                JSONObject()
                    .put("url", link.url)
                    .put("quality", link.quality)
            )
        }
        return array.toString()
    }

    private fun String.fixQuality(): Int = when {
        contains("2160", true) -> Qualities.P2160.value
        contains("1080", true) -> Qualities.P1080.value
        contains("720", true) -> Qualities.P720.value
        contains("480", true) -> Qualities.P480.value
        contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun String.cleanEscaped(): String {
        return trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("\\\"", "\"")
            .trim('"', '\'', ',', ';')
            .trim()
    }

    private fun String.isPlayableMediaUrl(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("/api/file/")
    }

    data class EpisodeLink(
        @param:JsonProperty("url") val url: String,
        @param:JsonProperty("quality") val quality: String
    )

    data class PixeldrainList(
        @param:JsonProperty("files") val files: List<PixeldrainFile> = emptyList()
    )

    data class PixeldrainFile(
        @param:JsonProperty("id") val id: String,
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("mime_type") val mimeType: String = ""
    )
}
