package com.sad25kag.hahomoe

import android.annotation.SuppressLint
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

class Hahomoe : MainAPI() {
    private val globalTvType = TvType.NSFW

    override var mainUrl = "https://haho.moe"
    override var name = "Haho Moe"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "__home__" to "Beranda",

        "anime?page=%d" to "Anime Index",

        "type/ova?page=%d" to "OVA",
        "type/movie?page=%d" to "Movie",
        "type/tv-series?page=%d" to "TV Series",
        "type/web?page=%d" to "Web",
        "type/tv-special?page=%d" to "TV Special",

        "status/ongoing?page=%d" to "Ongoing",
        "status/completed?page=%d" to "Completed",
        "status/stalled?page=%d" to "Stalled",

        // Route genre Haho memakai ID, bukan slug biasa.
        "genre/zdwxopnb?page=%d" to "Action"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        if (request.data == "__home__") {
            val document = app.get(
                mainUrl,
                headers = commonHeaders
            ).document

            val rows = parseHomeSections(document)

            if (rows.isEmpty()) {
                throw ErrorLoadingException("Homepage kosong")
            }

            return newHomePageResponse(rows)
        }

        val document = app.get(
            buildPageUrl(request.data, page),
            headers = commonHeaders,
            cookies = mapOf("loop-view" to "thumb")
        ).document

        val list = parseAnimeList(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            list,
            hasNext = document.selectFirst(
                "a.page-link[rel=next], " +
                    "a[rel=next], " +
                    ".pagination a:contains(›), " +
                    ".pagination a[href*='page=${page + 1}'], " +
                    "a[href*='page=${page + 1}']"
            ) != null
        )
    }

    private fun buildPageUrl(
        data: String,
        page: Int
    ): String {
        val safePage = page.coerceAtLeast(1)
        val formatted = data.format(safePage)

        return when {
            formatted.startsWith("http", true) -> formatted
            formatted.startsWith("/") -> "$mainUrl$formatted"
            else -> "$mainUrl/$formatted"
        }
    }

    private fun parseHomeSections(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        document.select("#content > section").forEach { section ->
            val title = when {
                section.attr("id") == "toplist-tabs" -> null
                else -> section.selectFirst("h2")?.text()?.trim()
            }

            if (section.attr("id") == "toplist-tabs") {
                section.select(".tab-content > [role=tabpanel]").forEach { tab ->
                    val tabId = tab.attr("id").trim()
                    val tabName = tabId.substringAfter("-", tabId)
                        .replace("-", " ")
                        .uppercase(Locale.ROOT)
                        .ifBlank { "TOP" }

                    val items = tab.select("li > a[href]")
                        .mapNotNull { it.toSearchResult() }
                        .distinctBy { it.url }

                    if (items.isNotEmpty()) {
                        rows.add(HomePageList("Top - $tabName", items))
                    }
                }
            } else {
                val items = section.select("li > a[href], ul.thumb > li > a[href]")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }

                if (!title.isNullOrBlank() && items.isNotEmpty()) {
                    rows.add(HomePageList(title, items))
                }
            }
        }

        if (rows.isEmpty()) {
            val fallback = parseAnimeList(document)

            if (fallback.isNotEmpty()) {
                rows.add(HomePageList("Beranda", fallback))
            }
        }

        return rows
    }

    private fun parseAnimeList(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "ul.thumb > li > a[href], " +
                "li > a[href*='/anime/'], " +
                "a[href*='/anime/']"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null

        if (!href.contains("/anime/", true)) {
            return null
        }

        val title = listOf(
            attr("title").trim(),
            selectFirst(".thumb-title")?.text()?.trim(),
            selectFirst(".card-title")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Anime", true) &&
                !it.equals("More", true) &&
                !it.equals("Next", true) &&
                !it.equals("›", true) &&
                !it.equals("‹", true)
        }?.cleanSearchTitle() ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        return newAnimeSearchResponse(
            name = title,
            url = href,
            type = globalTvType
        ).apply {
            posterUrl = poster
            dubStatus = EnumSet.of(DubStatus.Subbed)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val results = linkedMapOf<String, SearchResponse>()
        var pageUrl: String? = "$mainUrl/anime"

        while (!pageUrl.isNullOrBlank()) {
            val response = app.get(
                pageUrl,
                headers = commonHeaders,
                params = if (pageUrl == "$mainUrl/anime") {
                    mapOf("q" to query)
                } else {
                    emptyMap()
                },
                cookies = mapOf("loop-view" to "thumb")
            )

            val document = response.document

            parseAnimeList(document).forEach { item ->
                results[item.url] = item
            }

            val next = document.selectFirst("a.page-link[rel=next], a[rel=next]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            pageUrl = next

            if (results.size >= 80) break
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = commonHeaders,
            cookies = mapOf("loop-view" to "thumb")
        ).document

        val canonicalTitle = document.selectFirst("header.entry-header > h1.mb-3, h1")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: url.substringAfterLast("/").replace("-", " ")

        val englishTitle = document.selectFirst("span.value > span[title=English]")
            ?.parent()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val japaneseTitle = document.selectFirst("span.value > span[title=Japanese]")
            ?.parent()
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val episodes = document.select("li[class*=episode] > a[href], a[href*='/episode/']")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }

        val status = when (document.selectFirst("li.status > .value")?.text()?.trim()) {
            "Ongoing" -> ShowStatus.Ongoing
            "Completed" -> ShowStatus.Completed
            else -> null
        }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(document.selectFirst("li.release-date .value")?.text().orEmpty())
            ?.value
            ?.toIntOrNull()

        val poster = fixUrlNull(
            document.selectFirst("img.cover-image, .cover img, img.poster")
                ?.getImageAttr()
        )

        val synopsis = document.selectFirst(".entry-description > .card-body, .entry-description, .description")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val genres = document.select("li.genre.meta-data > span.value, a[href*='/genre/']")
            .map { it.text().trim() }
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val synonyms = document.select("li.synonym.meta-data > div.info-box > span.value, li.synonym .value")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return newAnimeLoadResponse(canonicalTitle, url, globalTvType) {
            engName = englishTitle
            japName = japaneseTitle
            apiName = this@Hahomoe.name
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = synopsis
            tags = ArrayList(genres)
            this.synonyms = ArrayList(synonyms)
            this.episodes = hashMapOf(
                DubStatus.Subbed to episodes.ifEmpty {
                    listOf(
                        newEpisode(url) {
                            name = canonicalTitle
                            episode = 1
                        }
                    )
                }
            )
        }
    }

    private fun Element.toEpisode() = runCatching {
        val href = fixUrlNull(attr("href")) ?: return@runCatching null
        val epText = selectFirst(".episode-slug")?.text()?.trim().orEmpty()
        val title = selectFirst(".episode-title")?.text()?.trim()

        val name = when {
            title.isNullOrBlank() -> epText.ifBlank {
                "Episode ${extractEpisodeNumber(epText, href) ?: 1}"
            }

            title.contains("No Title", ignoreCase = true) -> epText.ifBlank {
                title
            }

            else -> title
        }

        newEpisode(href) {
            data = href
            this.name = name
            episode = extractEpisodeNumber("$epText $title", href)
            posterUrl = fixUrlNull(selectFirst("img")?.getImageAttr())
            description = attr("data-content").trim().takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    @SuppressLint("SimpleDateFormat")
    private fun dateParser(dateString: String): String? {
        return runCatching {
            val format = SimpleDateFormat("dd 'of' MMM',' yyyy", Locale.US)
            val newFormat = SimpleDateFormat("dd-MM-yyyy", Locale.US)

            val parsed = format.parse(
                dateString
                    .replace("th ", " ")
                    .replace("st ", " ")
                    .replace("nd ", " ")
                    .replace("rd ", " ")
            ) ?: return null

            newFormat.format(parsed)
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val document = app.get(
            data,
            headers = commonHeaders,
            referer = mainUrl
        ).document

        val embedUrls = linkedSetOf<String>()
        var found = false

        document.select("[aria-labelledby=mirror-dropdown] > li > a.dropdown-item[href], a.dropdown-item[href*='v=']")
            .forEach { source ->
                val href = source.attr("href").trim()

                val videoId = href.substringAfter("v=", "")
                    .substringBefore("&")
                    .takeIf { it.isNotBlank() }

                if (videoId != null) {
                    embedUrls.add("$mainUrl/embed?v=$videoId")
                }

                val directUrl = fixUrlNull(href)

                if (!directUrl.isNullOrBlank() && directUrl.contains("/embed", true)) {
                    embedUrls.add(directUrl)
                }

                source.attr("data-url")
                    .takeIf { it.isNotBlank() }
                    ?.let { embedUrls.add(fixUrl(it)) }

                source.attr("data-src")
                    .takeIf { it.isNotBlank() }
                    ?.let { embedUrls.add(fixUrl(it)) }

                source.attr("data-link")
                    .takeIf { it.isNotBlank() }
                    ?.let { embedUrls.add(fixUrl(it)) }
            }

        document.select("iframe[src], embed[src], source[src], video[src]")
            .forEach { element ->
                val src = element.attr("src").trim()

                if (src.isNotBlank()) {
                    embedUrls.add(fixUrl(src))
                }
            }

        embedUrls.forEach { embed ->
            val sourceDocument = runCatching {
                app.get(
                    embed,
                    headers = commonHeaders,
                    referer = data
                ).document
            }.getOrNull()

            sourceDocument?.select("video#player > source[src], video source[src], source[src]")
                ?.forEach { source ->
                    val src = source.attr("src").trim()

                    if (src.isBlank()) return@forEach

                    val qualityName = source.attr("title")
                        .ifBlank { source.attr("label") }
                        .ifBlank { source.attr("res") }
                        .ifBlank { "Unknown" }

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} $qualityName",
                            url = fixUrl(src),
                            type = if (src.contains(".m3u8", true)) {
                                ExtractorLinkType.M3U8
                            } else {
                                ExtractorLinkType.VIDEO
                            }
                        ) {
                            quality = getQualityFromName(qualityName).takeIf {
                                it != Qualities.Unknown.value
                            } ?: qualityFromName(qualityName)

                            referer = embed
                            headers = mapOf(
                                "Referer" to embed,
                                "Origin" to mainUrl,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )

                    found = true
                }

            if (!found) {
                loadExtractor(embed, data, subtitleCallback, callback)
            }
        }

        return found || embedUrls.isNotEmpty()
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:ep\.?|episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|ep)[-/]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun qualityFromName(name: String): Int {
        return when {
            name.contains("1080", true) -> Qualities.P1080.value
            name.contains("720", true) -> Qualities.P720.value
            name.contains("480", true) -> Qualities.P480.value
            name.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanSearchTitle(): String {
        return this
            .replace(Regex("""\s+Ep\.\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Episode\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}