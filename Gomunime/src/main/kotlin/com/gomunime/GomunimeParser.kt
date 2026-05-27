package com.gomunime

import com.gomunime.GomunimeUtils.absoluteUrl
import com.gomunime.GomunimeUtils.cleanAnimeTitle
import com.gomunime.GomunimeUtils.cleanText
import com.gomunime.GomunimeUtils.episodeNumber
import com.gomunime.GomunimeUtils.isAnimeDetailUrl
import com.gomunime.GomunimeUtils.statusFromText
import com.gomunime.GomunimeUtils.titleFromSlug
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

object GomunimeParser {
    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val cards = linkedSetOf<AnimeSearchResponse>()

        document.select(
            "article.bs, .listupd article, .listupd .bs, .episode-card, .anime-card, .series-card, .grid a[href], .swiper-slide a[href], a[href*='-episode-']"
        ).forEach { element ->
            parseCard(api, element)?.let { cards.add(it) }
        }

        if (cards.size < 8) {
            document.select("a[href]").forEach { anchor ->
                parseAnchorCard(api, anchor)?.let { cards.add(it) }
            }
        }

        return cards
            .distinctBy { it.url }
            .filter { it.name.length > 2 }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): AnimeSearchResponse? {
        val link = if (element.`is`("a[href]")) element else element.selectFirst("a[href*='-episode-'], a[href]")
        link ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isAnimeDetailUrl(href)) return null

        val container = nearestUsefulContainer(element)
        val image = container.selectFirst("img") ?: link.selectFirst("img")
        val rawTitle = container.selectFirst(".tt h3, .tt h2, h3, h2, .title, .name, .anime-title")?.text()
            ?: link.attr("title")
            ?: image?.attr("alt")
            ?: link.text()
            ?: titleFromSlug(href)

        val title = cleanAnimeTitle(rawTitle, href)
        if (!isValidTitle(title)) return null

        val poster = findPoster(api.mainUrl, container, image)
        val typeText = cleanText(container.selectFirst(".type, .typez, .type-label, .meta")?.text() ?: container.text())
        val type = typeFromText(typeText)

        return api.newAnimeSearchResponse(title, href, if (type == TvType.AnimeMovie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = poster
        }
    }

    private fun parseAnchorCard(api: MainAPI, anchor: Element): AnimeSearchResponse? {
        val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return null
        if (!isAnimeDetailUrl(href)) return null

        val text = cleanText(anchor.text())
        val image = anchor.selectFirst("img") ?: anchor.parent()?.selectFirst("img")
        val hasItemSignal = href.contains("-episode-", true) || text.contains("Tonton", true) || text.contains("Episode", true) || text.contains("★") || image != null
        if (!hasItemSignal) return null

        val title = cleanAnimeTitle(text.ifBlank { image?.attr("alt") }, href)
        if (!isValidTitle(title)) return null

        val poster = findPoster(api.mainUrl, anchor.parent() ?: anchor, image)
        return api.newAnimeSearchResponse(title, href, typeFromText(text)) {
            posterUrl = poster
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        if (isEpisodePage(document, url)) {
            return parseEpisodeAsSingleLoad(api, url, document)
        }

        val rawTitle = document.selectFirst("h1.entry-title, h1, .anime-title, .title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        val title = cleanAnimeTitle(rawTitle, url).ifBlank { return null }

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

        val year = document.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(bodyText)?.value?.toIntOrNull()

        val description = cleanText(
            document.selectFirst(".desc, .mindes, .sinopsis, .synopsis, .entry-content p, meta[name=description]")?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            }
        )

        val tags = document.select("a[href*='/genre/'], a[href*='/genres/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
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
            statusFromText(statusText)?.let { showStatus = it }
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

        val episode = episodeNumber(pageTitle) ?: episodeNumber(url)
        val rawAnimeTitle = pageTitle.substringBefore(" Episode").substringBefore(" episode")
        val animeTitle = cleanAnimeTitle(rawAnimeTitle, url).ifBlank { titleFromSlug(url) }
        val poster = absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
        val type = typeFromText(pageTitle)

        return api.newAnimeLoadResponse(animeTitle, url, type) {
            engName = animeTitle
            posterUrl = poster
            plot = cleanText(document.selectFirst("meta[name=description]")?.attr("content"))
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
        }

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
        val hasEpisodeSlug = Regex("""(?i)-episode-\d+""").containsMatchIn(url)
        val hasEpisodeTitle = Regex("""(?i)\bepisode\s+\d+\b""").containsMatchIn(title)
        val hasPlayerSignal = document.select(
            "select.mirror option, .mirror option, #player, #player-div, .player-embed, .anime_video_body iframe, iframe[src*='drive'], iframe[src*='gdrive'], iframe[src*='btube']"
        ).isNotEmpty()
        return hasEpisodeSlug || hasEpisodeTitle || (hasPlayerSignal && hasEpisodeTitle)
    }

    private fun nearestUsefulContainer(element: Element): Element {
        return element.closest("article, .bs, .anime-card, .episode-card, .series-card, .card, .swiper-slide, li") ?: element
    }

    private fun findPoster(mainUrl: String, container: Element, image: Element?): String? {
        val img = image ?: container.selectFirst("img")
        val fromImg = img?.let {
            absoluteUrl(
                mainUrl,
                it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank {
                        it.attr("data-lazy-src").ifBlank { it.attr("src") }
                    }
                }
            )
        }
        if (!fromImg.isNullOrBlank()) return fromImg

        val style = container.attr("style") + " " + container.select("[style]").joinToString(" ") { it.attr("style") }
        return Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { absoluteUrl(mainUrl, it) }
    }

    private fun isValidTitle(title: String): Boolean {
        if (title.isBlank() || title.length < 3) return false
        val lower = title.lowercase()
        if (lower in setOf("home", "info", "play", "genre", "download app", "lihat semua", "semua")) return false
        if (lower.contains("gomu nime")) return false
        if (lower.contains("update tiap hari")) return false
        if (lower.matches(Regex("^[0-9]+$"))) return false
        return true
    }
}
