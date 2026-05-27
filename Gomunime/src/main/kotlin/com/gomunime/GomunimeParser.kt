package com.gomunime

import com.gomunime.GomunimeUtils.absoluteUrl
import com.gomunime.GomunimeUtils.cleanText
import com.gomunime.GomunimeUtils.episodeNumber
import com.gomunime.GomunimeUtils.isAnimeDetailUrl
import com.gomunime.GomunimeUtils.statusFromText
import com.gomunime.GomunimeUtils.typeFromText
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TrackerType
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

object GomunimeParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val cards = document.select(
            "article.bs, article, .anime-card, .anime-item, .series-card, .post, .card, .grid > div, .swiper-slide, .listupd .bs"
        ).mapNotNull { parseCard(api, it) }

        val fallback = document.select("a[href]")
            .mapNotNull { parseAnchorCard(api, it) }

        return (cards + fallback)
            .distinctBy { it.url }
            .filter { it.name.length > 2 }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): AnimeSearchResponse? {
        val link = element.selectFirst("a[href*='${api.mainUrl}'], a[href^='/'], a[href]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isAnimeDetailUrl(href)) return null

        val image = element.selectFirst("img")
        val title = cleanText(
            element.selectFirst(".tt h3, .tt h2, h3, h2, .title, .name, .anime-title")?.text()
                ?: link.attr("title")
                ?: image?.attr("alt")
                ?: link.text()
        ).removePrefix("Tonton ").trim()

        if (title.isBlank() || title.equals("GOMU NIME", true)) return null
        if (title.length < 3 || title.contains("episode baru", true)) return null

        val poster = image?.let {
            absoluteUrl(
                api.mainUrl,
                it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank {
                        it.attr("src")
                    }
                }
            )
        }

        val typeText = cleanText(
            element.selectFirst(".type, .typez, .type-label, .meta")?.text()
                ?: element.text()
        )

        val epNum = cleanText(
            element.selectFirst(".epx, .episode, .ep, .eps, .badge")?.text()
                ?: element.text()
        ).let { episodeNumber(it) }

        val type = typeFromText(typeText)

        return api.newAnimeSearchResponse(title, href, if (type == TvType.AnimeMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun parseAnchorCard(api: MainAPI, anchor: Element): AnimeSearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isAnimeDetailUrl(href)) return null

        val raw = cleanText(anchor.text()).removePrefix("Tonton ").trim()
        if (raw.isBlank()) return null
        if (raw.length < 3) return null
        if (raw.equals("Home", true) || raw.equals("Info", true) || raw.equals("Play", true)) return null
        if (raw.contains("Episode", true) && !raw.contains("Tonton", true)) return null

        val title = raw
            .replace(Regex("""^★\s*[\d.]+\s*"""), "")
            .replace(Regex("""\s+TV\s*•.*$"""), "")
            .replace(Regex("""\s+Movie\s*•.*$"""), "")
            .replace(Regex("""\s+OVA\s*•.*$"""), "")
            .replace(Regex("""\s+ONA\s*•.*$"""), "")
            .trim()

        if (title.isBlank() || title.length < 3) return null

        return api.newAnimeSearchResponse(title, href, typeFromText(raw)) {
            // Episode counters are intentionally not set here because the target Cloudstream API
            // used by this repo no longer exposes addSub() in AnimeSearchResponse builders.
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        if (isEpisodePage(document, url)) {
            return parseEpisodeAsSingleLoad(api, url, document)
        }

        val title = cleanText(
            document.selectFirst("h1.entry-title, h1, .anime-title, .title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).removePrefix("Anime ").removeSuffix(" Sub Indo HD").trim().ifBlank { return null }

        val bodyText = cleanText(document.body()?.text())
        val poster = absoluteUrl(
            api.mainUrl,
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".single-info .thumb img, .poster img, .cover img, .thumb img, img[alt*='$title']")?.let {
                    it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } }
                }
        )

        val statusText = Regex("""(?i)\b(Status|TV)\s+([A-Za-z]+)""").find(bodyText)?.groupValues?.getOrNull(2)
            ?: document.selectFirst(".status, .info:contains(Status)")?.text()

        val typeText = Regex("""(?i)\b(TV|Movie|OVA|ONA|Special)\b""").find(bodyText)?.groupValues?.getOrNull(1)
            ?: bodyText

        val year = document.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(bodyText)?.value?.toIntOrNull()

        val description = cleanText(
            document.selectFirst(".desc, .mindes, .sinopsis, .synopsis, .entry-content p, meta[name=description]")?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            }
        )

        val tags = document.select("a[href*='/genre/'], a[href*='/genres/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 }
            .distinct()
            .take(16)

        val episodes = parseEpisodes(api, document, url)
        val type = typeFromText(typeText)
        val tracker = runCatching {
            APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)
        }.getOrNull()

        return api.newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            this.tags = tags
            this.plot = description
            showStatus = statusFromText(statusText)
            addEpisodes(DubStatus.Subbed, episodes)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
            recommendations = parseRecommendations(api, document, url)
        }
    }

    private suspend fun parseEpisodeAsSingleLoad(api: MainAPI, url: String, document: Document): LoadResponse? {
        val pageTitle = cleanText(
            document.selectFirst("h1, .entry-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { return null }

        val baseTitle = pageTitle.substringBefore(" Episode").trim()
        val animeTitle = document.select("a[href]")
            .map { cleanText(it.text()) }
            .firstOrNull { it.isNotBlank() && baseTitle.contains(it, ignoreCase = true) }
            ?: baseTitle.ifBlank { pageTitle }

        val episode = episodeNumber(pageTitle)
        val poster = absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val typeText = cleanText(document.body()?.text())
        val type = typeFromText(typeText)

        return api.newAnimeLoadResponse(animeTitle, url, type) {
            engName = animeTitle
            posterUrl = poster
            plot = cleanText(document.selectFirst("meta[name=description]")?.attr("content"))
            showStatus = statusFromText(typeText)
            addEpisodes(
                DubStatus.Subbed,
                listOf(
                    api.newEpisode(url) {
                        this.name = "Episode ${episode ?: ""}".trim().ifBlank { "Episode" }
                        this.episode = episode
                    }
                )
            )
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val episodes = document.select(
            "a[href*='episode-'], .eplister a[href], .episodes a[href], .episode-list a[href], a[href]:contains(Episode)"
        ).mapNotNull { a ->
            val href = absoluteUrl(api.mainUrl, a.attr("href")) ?: return@mapNotNull null
            if (!isAnimeDetailUrl(href)) return@mapNotNull null
            if (!seen.add(href.substringBefore("#"))) return@mapNotNull null

            val text = cleanText(a.text())
            val epNum = episodeNumber(text) ?: episodeNumber(href)

            api.newEpisode(href) {
                this.name = "Episode ${epNum ?: text}".trim()
                this.episode = epNum
            }
        }.sortedByDescending { it.episode ?: 0 }

        return episodes.ifEmpty {
            val epNum = episodeNumber(fallbackUrl)
            listOf(
                api.newEpisode(fallbackUrl) {
                    this.name = "Episode ${epNum ?: ""}".trim().ifBlank { "Episode" }
                    this.episode = epNum
                }
            )
        }
    }

    private fun parseRecommendations(api: MainAPI, document: Document, currentUrl: String): List<SearchResponse> {
        return document.select(".related, .releases, .anime-mirip, .bixbox, section")
            .flatMap { section -> section.select("article, .anime-card, .bs, .card, a[href]").mapNotNull { parseCard(api, it) ?: parseAnchorCard(api, it) } }
            .distinctBy { it.url }
            .filterNot { it.url == currentUrl }
            .take(12)
    }

    private fun isEpisodePage(document: Document, url: String): Boolean {
        val title = cleanText(document.selectFirst("h1, .entry-title")?.text())
        return url.contains("-episode-", true) || title.contains(" Episode ", true) || document.select("select.mirror option, .mirror option").isNotEmpty()
    }
}
