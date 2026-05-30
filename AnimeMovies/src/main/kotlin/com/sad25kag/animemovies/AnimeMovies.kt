package com.sad25kag.animemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addEpisodes
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.Goodstream
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
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

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a[href*=/anime/]")?.attr("abs:href") ?: return null
        val title = selectFirst("h3, h2, .line-clamp-2, .font-semibold")?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectFirst("img[alt]")?.attr("alt")?.trim()
            ?: href.substringAfterLast('/').substringBefore('?').replace('-', ' ')
        val poster = selectFirst("img")?.let { img ->
            img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
        }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        val items = doc.select("a[href*=/anime/]")
            .mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/anime?q=${query.urlEncoded()}").document
        var items = doc.select("a[href*=/anime/]")
            .mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
            .distinctBy { it.url }
        if (items.isEmpty()) {
            val fallback = app.get("$mainUrl/anime").document
            items = fallback.select("a[href*=/anime/]")
                .mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
                .filter { it.name.contains(query, true) }
                .distinctBy { it.url }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: doc.title().substringBefore("|").trim()
        val poster = doc.selectFirst("img[src*=_next/image], img[alt]")?.let { img ->
            img.attr("abs:src").ifBlank { img.attr("abs:data-src") }
        }
        val tags = doc.select("a[href*=/genre/], a[href*=/jadwal/], .text-xs a, .text-sm a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.selectFirst("p")?.text()?.trim()
        val episodes = doc.select("a[href*=/watch/]").mapIndexed { index, a ->
            val epName = a.text().trim().ifBlank { "Episode ${index + 1}" }
            Episode(a.attr("abs:href"), name = epName, episode = Regex("episode\s*(\d+)", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.getOrNull(1)?.toFloatOrNull())
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text

        Regex("https?:\/\/[^"'\s<>]+(?:m3u8|mp4)[^"'\s<>]*", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value.replace("\/", "/") }
            .distinct()
            .forEach { link ->
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = link,
                        referer = data,
                        quality = getQualityFromName(link),
                        isM3u8 = link.contains(".m3u8", true)
                    )
                )
            }

        Regex("<iframe[^>]+src=["']([^"']+)["']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .distinct()
            .forEach { iframe ->
                loadExtractor(iframe, data, subtitleCallback, callback)
            }

        return true
    }
}
