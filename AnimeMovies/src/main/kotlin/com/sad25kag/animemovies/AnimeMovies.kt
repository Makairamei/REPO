package com.sad25kag.animemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AnimeMovies : MainAPI() {
    override var mainUrl = "https://animemovies.org"
    override var name = "AnimeMovies"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/anime" to "Daftar Anime",
        "$mainUrl/genre" to "Genre"
    )

    private fun normalizeTitleFromUrl(url: String): String {
        return url.substringAfterLast('/')
            .substringBefore('?')
            .replace('-', ' ')
            .replace("subtitle indonesia", "")
            .trim()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href*=/anime/]") ?: return null
        val href = anchor.attr("abs:href").ifBlank { anchor.attr("href") }
        if (!href.contains("/anime/")) return null

        val title = anchor.selectFirst("h1, h2, h3, h4, .line-clamp-2, .font-semibold")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: normalizeTitleFromUrl(href)

        val poster = anchor.selectFirst("img")?.let { img ->
            img.attr("abs:src").ifBlank {
                img.attr("abs:data-src").ifBlank { img.attr("src") }
            }
        }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val results = doc.select("a[href*=/anime/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/anime").document
        return doc.select("a[href*=/anime/]")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore("|").trim()

        val poster = doc.selectFirst("img[src*=_next/image], img[alt], img")?.let { img ->
            img.attr("abs:src").ifBlank {
                img.attr("abs:data-src").ifBlank { img.attr("src") }
            }
        }

        val tags = doc.select("a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.select("p").map { it.text().trim() }.firstOrNull { it.isNotBlank() }

        val episodes = doc.select("a[href*=/watch/]")
            .distinctBy { it.attr("abs:href") }
            .mapIndexed { index, a ->
                val epUrl = a.attr("abs:href")
                val epName = a.text().trim().ifBlank { "Episode ${index + 1}" }
                val epNum = Regex("episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                Episode(
                    data = epUrl,
                    name = epName,
                    episode = epNum
                )
            }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        var found = false

        val directRegex = Regex("""https?:\\/\\/[^\"'\\s<>]+(?:m3u8|mp4)[^\"'\\s<>]*""", RegexOption.IGNORE_CASE)
        directRegex.findAll(html)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .forEach { link ->
                found = true
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = link,
                        referer = data,
                        quality = getQualityFromName(link),
                        isM3u8 = link.contains(".m3u8", ignoreCase = true)
                    )
                )
            }

        val embedRegex = Regex("""\"embed_url\":\"(https?:\\/\\/[^\"]+)\"""", RegexOption.IGNORE_CASE)
        embedRegex.findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .forEach { embedUrl ->
                found = true
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }

        return found
    }
}
