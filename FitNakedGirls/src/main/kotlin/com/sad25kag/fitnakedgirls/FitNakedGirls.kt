package com.sad25kag.fitnakedgirls

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
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
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FitNakedGirls : MainAPI() {
    override var mainUrl = "https://fitnakedgirls.com"
    override var name = "FitNakedGirls"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "videos/page/%d/" to "Video Terbaru",

        "videos/category/nude-instagram-girls/page/%d/" to "Nude Instagram Girls",
        "videos/category/nude-models/page/%d/" to "Nude Models",
        "videos/category/naked-yoga/page/%d/" to "Naked Yoga",
        "videos/category/nude-fitness-models/page/%d/" to "Nude Fitness Models",
        "videos/category/naked-girls/page/%d/" to "Amateur Fit Girls",
        "videos/category/nude-influencers/page/%d/" to "Nude Influencers",
        "videos/category/gym-sex/page/%d/" to "Gym Sex",
        "videos/category/naked-workout/page/%d/" to "Naked Workout",
        "videos/category/nude-internet-girls/page/%d/" to "Nude Internet Girls",
        "videos/category/camgirls/page/%d/" to "Fit Camgirls",
        "videos/category/muscle-porn/page/%d/" to "Muscle Porn",
        "videos/category/fitness-lesbians-porn/page/%d/" to "Fitness Lesbians",
        "videos/category/nude-ballet/page/%d/" to "Nude Ballet",
        "videos/category/wwe-porn/page/%d/" to "WWE Porn",
        "videos/category/massage-porn/page/%d/" to "Massage Porn",
        "videos/category/nude-sports/page/%d/" to "Nude Sports",
        "videos/category/gymnast-porn/page/%d/" to "Gymnast Porn"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) =
        app.get(fixUrl(request.data.format(page))).document.let {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME") document ->
            val home = document.select(
                "li.g1-collection-item, " +
                    "article, " +
                    ".g1-collection-items article"
            ).mapNotNull { it.toMainPageResult() }
                .distinctBy { it.url }

            newHomePageResponse(
                request.name,
                home,
                hasNext = document.selectFirst(
                    "a.next, " +
                        ".next.page-numbers, " +
                        "a.page-numbers:contains(Next), " +
                        "a:contains(Next)"
                ) != null || home.isNotEmpty()
            )
        }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = if (page <= 1) {
            "$mainUrl/videos/?s=$q"
        } else {
            "$mainUrl/videos/page/$page/?s=$q"
        }

        val document = app.get(searchUrl).document

        val results = document.select(
            "li.g1-collection-item, " +
                "article, " +
                ".g1-collection-items article"
        ).mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a.next, " +
                    ".next.page-numbers, " +
                    "a.page-numbers:contains(Next), " +
                    "a:contains(Next)"
            ) != null
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1, " +
                "h1.entry-title, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "img.wp-post-image, " +
                    "figure img, " +
                    ".entry-featured-media img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    element.hasAttr("data-src") -> element.attr("data-src")
                    element.hasAttr("data-lazy-src") -> element.attr("data-lazy-src")
                    else -> element.attr("src")
                }
            }
        )

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "div.entry-content p, " +
                ".g1-content-narrow p"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val tags = document.select(
            "a[rel=tag], " +
                ".tags a, " +
                ".entry-tags a, " +
                ".g1-meta a[href*=/tag/], " +
                "a[href*=/videos/category/]"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val scoreText = document.selectFirst(
            "span.dt_rating_vgs, " +
                ".rating, " +
                ".g1-meta-rating"
        )?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.getOrNull(1) }

        val duration = document.selectFirst(
            "span.runtime, " +
                ".duration, " +
                "meta[property=video:duration]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.let { Regex("""\d+""").find(it)?.value }
            ?.toIntOrNull()

        val recommendations = document.select(
            "li.g1-collection-item, " +
                ".related li, " +
                ".g1-related-entries li"
        ).mapNotNull { it.toMainPageResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        val actors = document.select(
            "span.valor a, " +
                "a[rel=author], " +
                ".author a"
        ).map { Actor(it.text().trim()) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }

        val trailer = Regex("""(?:youtube\.com/embed/|youtu\.be/)([A-Za-z0-9_-]+)""")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { "https://www.youtube.com/embed/$it" }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.score = Score.from10(scoreText?.toDoubleOrNull())
            this.duration = duration ?: 0
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
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

        val directVideos = linkedSetOf<String>()

        document.select(
            "figure.wp-block-video video[src], " +
                "div.wp-video video[src], " +
                "video[src], " +
                "source[src], " +
                "a[href$=.mp4], " +
                "a[href*=.mp4]"
        ).forEach { element ->
            val href = element.attr("src").ifBlank { element.attr("href") }.trim()
            if (href.isNotBlank()) {
                directVideos.add(fixUrl(href))
            }
        }

        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")
            .forEach { meta ->
                val href = meta.attr("content").trim()
                if (href.isNotBlank()) {
                    directVideos.add(fixUrl(href))
                }
            }

        directVideos.forEach { video ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = video,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                }
            )
        }

        return directVideos.isNotEmpty()
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val anchor = selectFirst(
            "h3 a[href], " +
                ".entry-title a[href], " +
                "a[href]"
        ) ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val title = listOf(
            selectFirst("h3")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Read more", true) &&
                !it.equals("More", true)
        }?.cleanTitle() ?: return null

        val posterUrl = fixUrlNull(
            selectFirst("img")?.let { image ->
                when {
                    image.hasAttr("data-src") -> image.attr("data-src")
                    image.hasAttr("data-lazy-src") -> image.attr("data-lazy-src")
                    image.hasAttr("srcset") -> image.attr("srcset").substringBefore(" ")
                    else -> image.attr("src")
                }
            }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""(?i)\s*-\s*FitNakedGirls\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}