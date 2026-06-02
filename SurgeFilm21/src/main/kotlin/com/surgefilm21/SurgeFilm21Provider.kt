package com.surgefilm21

import com.lagradost.cloudstream3.*

class SurgeFilm21Provider : MainAPI() {
    companion object {
        const val DEFAULT_MAIN_URL = "https://surgafilm21.homes"
    }

    override var mainUrl = DEFAULT_MAIN_URL
    override var name = "SurgeFilm21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.AnimeMovie, TvType.Cartoon, TvType.NSFW)

    private val sections = listOf(
        SurgeFilm21Section("section:Populer Hari Ini", "Populer Hari Ini", "popular"),
        SurgeFilm21Section("section:Update Terbaru", "Update Terbaru", "latest"),
        SurgeFilm21Section("section:Update Eps", "Update Eps", "series"),
        SurgeFilm21Section("section:Marathon Series Terhangat", "Marathon Series", "series"),
        SurgeFilm21Section("section:Bikin Baper", "Romance", "romance"),
        SurgeFilm21Section("section:Obat Stres", "Comedy", "comedy"),
        SurgeFilm21Section("section:Drama Pilihan", "Drama", "drama"),
        SurgeFilm21Section("section:Adrenalin", "Action", "action"),
        SurgeFilm21Section("section:Dunia Imajinasi", "Animation", "animation"),
        SurgeFilm21Section("section:Sci-Fi", "Sci-Fi", "sci-fi"),
        SurgeFilm21Section("section:Uji Nyali", "Horror", "horror"),
        SurgeFilm21Section("section:Teka-teki", "Mystery", "mystery"),
        SurgeFilm21Section("section:Epik Sejarah", "War & History", "war"),
        SurgeFilm21Section("section:Thailand", "Thailand", "thailand")
    )

    override val mainPage = mainPageOf(*sections.map { it.data to it.name }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val section = sections.firstOrNull { it.data == request.data }
        val defaultType = typeFromSection(section?.name ?: request.name)

        if (page > 1) {
            val fallback = section?.fallbackQuery ?: request.name
            val list = searchPage(fallback, page, defaultType)
            return newHomePageResponse(HomePageList(request.name, list, isHorizontalImages = true), hasNext = list.isNotEmpty())
        }

        val document = SurgeFilm21Sepeda.getDocument(mainUrl)
        val list = section?.let {
            SurgeFilm21Parser.parseSectionItems(this, document, it.data.removePrefix("section:"), mainUrl, defaultType)
                .ifEmpty { searchPage(it.fallbackQuery, page, defaultType) }
        } ?: SurgeFilm21Parser.parseHomeItems(this, document, mainUrl, defaultType)

        return newHomePageResponse(HomePageList(request.name, list.distinctBy { it.url }, isHorizontalImages = true), hasNext = list.isNotEmpty())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val pageItems = searchPage(query, page, TvType.Movie).filterNot { item -> results.any { it.url == item.url } }
            if (pageItems.isEmpty()) break
            results.addAll(pageItems)
        }
        return results
    }

    private suspend fun searchPage(query: String, page: Int, defaultType: TvType): List<SearchResponse> {
        val encoded = query.urlEncodeSf21()
        val candidates = listOf(
            "$mainUrl/search?q=$encoded&page=$page",
            "$mainUrl/?s=$encoded&page=$page",
            "$mainUrl/page/$page/?s=$encoded",
            "$mainUrl/search/$encoded?page=$page"
        )

        for (url in candidates) {
            val items = runCatching {
                val document = SurgeFilm21Sepeda.getDocument(url, mainUrl)
                SurgeFilm21Parser.parseHomeItems(this, document, url, defaultType)
                    .filter { it.name.contains(query, true) || query.length <= 4 }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = SurgeFilm21Sepeda.getDocument(url, mainUrl)
        val title = SurgeFilm21Parser.parseTitle(document).ifBlank { return null }
        val poster = SurgeFilm21Parser.parsePoster(document, url)
        val plot = SurgeFilm21Parser.parsePlot(document)
        val tags = SurgeFilm21Parser.parseTags(document)
        val year = SurgeFilm21Parser.parseYear(document, title)
        val inferredType = SurgeFilm21Parser.inferType(title, url, TvType.Movie)
        val episodes = SurgeFilm21Parser.parseEpisodes(this, document, url)

        return if (episodes.isNotEmpty() && inferredType != TvType.Movie && inferredType != TvType.AnimeMovie) {
            newTvSeriesLoadResponse(title, url, inferredType, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, inferredType, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return SurgeFilm21Extractor.load(data, subtitleCallback, callback)
    }

    private fun typeFromSection(name: String): TvType {
        val lower = name.lowercase()
        return when {
            lower.contains("series") || lower.contains("eps") -> TvType.TvSeries
            lower.contains("animation") -> TvType.Cartoon
            lower.contains("thailand") || lower.contains("drama") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }
}
