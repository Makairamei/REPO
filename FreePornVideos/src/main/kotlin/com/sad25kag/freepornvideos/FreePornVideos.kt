package com.sad25kag.freepornvideos

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FreePornVideos : MainAPI() {
    override var mainUrl = "https://www.freepornvideos.xxx"
    override var name = "Free Porn Videos"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "videos/page/%d/" to "Video Terbaru",
        "most-popular/week/%d/" to "Most Popular",

        "networks/brazzers-com/%d/" to "Brazzers",
        "networks/mylf-com/%d/" to "MYLF",
        "networks/bangbros/%d/" to "BangBros",
        "networks/adult-time/%d/" to "Adult Time",
        "networks/rk-com/%d/" to "Reality Kings",
        "networks/mom-lover/%d/" to "Mom Lover",
        "networks/teamskeet/%d/" to "TeamSkeet",

        "categories/jav-uncensored/%d/" to "JAV Uncensored",
        "categories/milf/%d/" to "MILF",
        "categories/teen/%d/" to "Teen",
        "categories/anal/%d/" to "Anal",
        "categories/big-tits/%d/" to "Big Tits",
        "categories/blowjob/%d/" to "Blowjob",
        "categories/brunette/%d/" to "Brunette",
        "categories/blonde/%d/" to "Blonde",
        "categories/lesbian/%d/" to "Lesbian",
        "categories/interracial/%d/" to "Interracial",
        "categories/rough-sex/%d/" to "Rough Sex",
        "categories/creampie/%d/" to "Creampie",
        "categories/babe/%d/" to "Babe",
        "categories/amateur/%d/" to "Amateur",
        "categories/pov/%d/" to "POV",
        "categories/group-sex/%d/" to "Group Sex",
        "categories/asian/%d/" to "Asian",
        "categories/latina/%d/" to "Latina",
        "categories/ebony/%d/" to "Ebony"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(fixUrl(request.data.format(page.coerceAtLeast(1)))).document
        val home = document.select(
            "#list_videos_common_videos_list_items > div.item, " +
                "#custom_list_videos_videos_list_search_result_items > div.item, " +
                "div.item"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a.next, " +
                    "li.next a, " +
                    ".pagination a:contains(Next), " +
                    "a[href*='/${page + 1}/']"
            ) != null || home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val searchResponse = linkedMapOf<String, SearchResponse>()
        val searchQuery = query.createSlug().orEmpty()
            .ifBlank { URLEncoder.encode(query.trim(), "UTF-8") }

        for (page in 1..5) {
            val document = runCatching {
                app.get("$mainUrl/search/$searchQuery/$page/").document
            }.getOrNull() ?: continue

            val results = document.select(
                "#custom_list_videos_videos_list_search_result_items > div.item, " +
                    "#list_videos_common_videos_list_items > div.item, " +
                    "div.item"
            ).mapNotNull { it.toSearchResult() }

            results.forEach { searchResponse[it.url] = it }

            if (results.isEmpty()) break
        }

        return searchResponse.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document

        val fullTitle = document.selectFirst(
            "div.headline > h1, " +
                "h1, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim().orEmpty()

        val title = fullTitle
            .substringBeforeLast(" - ", fullTitle)
            .removePrefix("- ")
            .removeSuffix("-")
            .cleanTitle()
            .ifBlank { url.substringAfterLast("/").replace("-", " ") }

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "img.thumb, " +
                    "video[poster], " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("poster") -> element.attr("poster")
                    element.hasAttr("data-src") -> element.attr("data-src")
                    element.hasAttr("data-lazy-src") -> element.attr("data-lazy-src")
                    else -> element.attr("src")
                }
            }
        )

        val tags = document.selectXpath("//div[contains(text(), 'Categories:')]/a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .ifEmpty {
                document.select("a[href*='/categories/']")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
            }
            .distinct()

        val description = document.selectXpath("//div[contains(text(), 'Description:')]/em")
            .text()
            .trim()
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val actors = document.selectXpath("//div[contains(text(), 'Models:')]/a")
            .map { Actor(it.text().trim()) }
            .filter { it.name.isNotBlank() }
            .ifEmpty {
                document.select("a[href*='/models/']")
                    .map { Actor(it.text().trim()) }
                    .filter { it.name.isNotBlank() }
            }
            .distinctBy { it.name }

        val recommendations = document.select(
            "div#list_videos_related_videos_items div.item, " +
                "#list_videos_common_videos_list_items > div.item, " +
                "div.item"
        ).mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(fullTitle.ifBlank { document.text() })
            ?.value
            ?.toIntOrNull()

        val rating = document.selectFirst("div.rating span, .rating span, .rating")
            ?.text()
            ?.substringBefore("%")
            ?.trim()
            ?.toFloatOrNull()
            ?.div(10f)
            ?.toString()

        val duration = parseDurationToMinutes(
            document.selectXpath("//span[contains(text(), 'Duration')]/em").text().trim()
                .ifBlank {
                    document.selectFirst("meta[property=video:duration]")
                        ?.attr("content")
                        ?.trim()
                        .orEmpty()
                }
        )

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.score = Score.from10(rating)
            this.duration = duration
            addActors(actors)
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
        val links = linkedSetOf<Pair<String, String>>()

        document.select(
            "video source[src], " +
                "video[src], " +
                "source[src], " +
                "a[href$=.mp4], " +
                "a[href*=.mp4]"
        ).forEach { element ->
            val srcUrl = element.attr("src").ifBlank { element.attr("href") }.trim()
            if (srcUrl.isNotBlank()) {
                val label = element.attr("label").ifBlank { element.attr("res") }.ifBlank { "FPV" }
                links.add(fixUrl(srcUrl) to label)
            }
        }

        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
            .forEach { meta ->
                val srcUrl = meta.attr("content").trim()
                if (srcUrl.isNotBlank()) {
                    links.add(fixUrl(srcUrl) to "FPV")
                }
            }

        links.forEach { (srcUrl, label) ->
            val finalUrl = runCatching {
                val response = app.get(srcUrl, allowRedirects = false)
                response.headers["location"] ?: srcUrl
            }.getOrDefault(srcUrl)

            callback(
                newExtractorLink(
                    source = "FPV",
                    name = "FPV $label",
                    url = finalUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(label)
                }
            )
        }

        return links.isNotEmpty()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null

        val title = listOf(
            selectFirst("strong.title")?.text()?.trim(),
            selectFirst(".title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Watch now", true) &&
                !it.equals("Play", true)
        }?.cleanTitle() ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("a img, img")?.getImageAttr()
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun String?.createSlug(): String? {
        return this
            ?.filter { it.isWhitespace() || it.isLetterOrDigit() }
            ?.trim()
            ?.replace("\\s+".toRegex(), "-")
            ?.lowercase()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+"""), " ")
            .removeSuffix(" - Free Porn Videos")
            .trim()
    }

    private fun parseDurationToMinutes(rawDuration: String): Int {
        if (rawDuration.isBlank()) return 0

        val parts = rawDuration.split(":").mapNotNull { it.trim().toIntOrNull() }

        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            2 -> parts[0]
            1 -> parts[0]
            else -> 0
        }
    }
}

private fun Element.getImageAttr(): String {
    return when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        hasAttr("data-original") -> attr("abs:data-original")
        else -> attr("abs:src")
    }
}