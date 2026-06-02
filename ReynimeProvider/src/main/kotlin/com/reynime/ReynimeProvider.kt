package com.reynime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.reynime.ReynimeUtils.cleanTitle
import com.reynime.ReynimeUtils.encode
import com.reynime.ReynimeUtils.extractEpisodeNumber
import com.reynime.ReynimeUtils.extractSeriesId
import com.reynime.ReynimeUtils.extractSeriesSlug
import com.reynime.ReynimeUtils.buildEpisodeData
import com.reynime.ReynimeUtils.slugify

class ReynimeProvider : MainAPI() {
    override var mainUrl = "https://reynime.my.id"
    override var name = "Reynime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "featured" to "Featured",
        "updated" to "Update Terbaru",
        "all" to "Daftar Donghua",
        "ongoing" to "Ongoing Series",
        "completed" to "Completed Series",
        "donghua" to "Donghua",
        "anime" to "Anime",
        "genre:action" to "Action",
        "genre:adventure" to "Adventure",
        "genre:fantasy" to "Fantasy",
        "genre:martial-arts" to "Martial Arts",
        "genre:xuanhuan" to "Xuanhuan",
        "genre:xianxia" to "Xianxia",
        "genre:wuxia" to "Wuxia",
        "genre:sci-fi" to "Sci-Fi",
        "genre:romance" to "Romance",
        "genre:comedy" to "Comedy"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl
    )

    private fun apiHeaders(): Map<String, String> = headers + mapOf(
        "Accept" to "application/json,text/plain,text/html,*/*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private fun seriesUrl(item: ReynimeSeries): String = "$mainUrl/series/${item.id}/${item.slug}"
    private fun bareSeriesUrl(item: ReynimeSeries): String = "$mainUrl/series/${item.id}"
    private fun episodeListApi(seriesId: Int): String = "$mainUrl/backend/api/episodes.php?series_id=$seriesId&limit=1000&_t=${System.currentTimeMillis()}"
    private fun episodeDetailApi(episodeId: String): String = "$mainUrl/backend/api/episodes.php?id=$episodeId&_t=${System.currentTimeMillis()}"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val safePage = page.coerceAtLeast(1)
        val webItems = fetchOfficialRows(request.data, safePage)
        val seedItems = filterSeeds(request.data)

        val items = (webItems.ifEmpty { seedItems })
            .distinctBy { it.url }
            .take(40)

        return newHomePageResponse(
            HomePageList(request.name, items, false),
            hasNext = webItems.isNotEmpty() && safePage < 3
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val official = fetchSearchRows(keyword)
        val seed = ReynimeSeeds.series
            .filter { item ->
                item.title.contains(keyword, ignoreCase = true) ||
                    item.slug.contains(keyword.slugify(), ignoreCase = true) ||
                    item.genres.any { it.contains(keyword.lowercase(), ignoreCase = true) }
            }
            .map { it.toSearchResponse() }

        return (official + seed).distinctBy { it.url }.take(50)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val requestedId = extractSeriesId(url)
        val requestedSlug = extractSeriesSlug(url)
        val seed = ReynimeSeeds.byId(requestedId) ?: ReynimeSeeds.bySlug(requestedSlug)
        val officialDetail = loadSeriesDetail(seed, url)
        val series = mergeSeries(seed, officialDetail)
            ?: officialDetail
            ?: seed
            ?: throw ErrorLoadingException("Series Reynime tidak ditemukan")

        val canonicalUrl = seriesUrl(series)
        val episodes = fetchEpisodes(series, canonicalUrl).ifEmpty { buildSeedEpisodes(series) }

        return newAnimeLoadResponse(series.title, canonicalUrl, series.type) {
            posterUrl = series.poster
            year = series.year
            plot = series.description
            tags = series.genres.map { it.replace('-', ' ').replaceFirstChar { char -> char.titlecase() } }
            addEpisodes(DubStatus.Subbed, episodes)
            recommendations = ReynimeSeeds.series
                .filter { it.id != series.id && it.genres.intersect(series.genres).isNotEmpty() }
                .take(12)
                .map { it.toSearchResponse() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return ReynimeExtractor.loadLinks(
            data = data,
            mainUrl = mainUrl,
            headers = headers,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun fetchOfficialRows(data: String, page: Int): List<SearchResponse> {
        val sort = when (data) {
            "featured" -> "popular"
            "updated" -> "updated"
            "all" -> "title"
            "ongoing" -> "updated"
            "completed" -> "updated"
            "donghua" -> "updated"
            "anime" -> "updated"
            else -> "updated"
        }
        val candidates = linkedSetOf(
            "$mainUrl/backend/api/series.php?sort=$sort&page=$page&limit=40&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/anime.php?sort=$sort&page=$page&limit=40&_t=${System.currentTimeMillis()}",
            "$mainUrl/api/series?sort=$sort&page=$page&limit=40",
            "$mainUrl/api/anime?sort=$sort&page=$page&limit=40",
            "$mainUrl/browse?sort=$sort&page=$page"
        )

        val parsed = candidates.flatMap { candidate ->
            runCatching {
                val response = app.get(candidate, headers = apiHeaders(), referer = mainUrl, timeout = 12L)
                ReynimeParser.parseSeries(response.text, candidate, mainUrl)
            }.getOrDefault(emptyList())
        }
            .distinctBy { it.id }
            .filter { matchesRequest(it, data) }

        return parsed.map { mergeSeries(ReynimeSeeds.byId(it.id), it) ?: it }.map { it.toSearchResponse() }
    }

    private suspend fun fetchSearchRows(keyword: String): List<SearchResponse> {
        val encoded = encode(keyword)
        val candidates = linkedSetOf(
            "$mainUrl/backend/api/series.php?search=$encoded&limit=50&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/series.php?q=$encoded&limit=50&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/anime.php?search=$encoded&limit=50&_t=${System.currentTimeMillis()}",
            "$mainUrl/api/search?q=$encoded",
            "$mainUrl/api/series?search=$encoded",
            "$mainUrl/browse?search=$encoded"
        )

        return candidates.flatMap { candidate ->
            runCatching {
                val response = app.get(candidate, headers = apiHeaders(), referer = mainUrl, timeout = 12L)
                ReynimeParser.parseSeries(response.text, candidate, mainUrl)
            }.getOrDefault(emptyList())
        }
            .distinctBy { it.id }
            .map { mergeSeries(ReynimeSeeds.byId(it.id), it) ?: it }
            .map { it.toSearchResponse() }
    }

    private suspend fun loadSeriesDetail(seed: ReynimeSeries?, requestedUrl: String): ReynimeSeries? {
        val id = seed?.id ?: extractSeriesId(requestedUrl)
        val slug = seed?.slug ?: extractSeriesSlug(requestedUrl)
        val candidates = linkedSetOf<String>()
        seed?.let {
            candidates.add(seriesUrl(it))
            candidates.add(bareSeriesUrl(it))
            candidates.add("$mainUrl/backend/api/series.php?id=${it.id}&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/backend/api/series.php?series_id=${it.id}&_t=${System.currentTimeMillis()}")
        }
        if (id != null) {
            if (!slug.isNullOrBlank()) candidates.add("$mainUrl/series/$id/$slug")
            candidates.add("$mainUrl/series/$id")
            candidates.add("$mainUrl/backend/api/series.php?id=$id&_t=${System.currentTimeMillis()}")
            candidates.add("$mainUrl/backend/api/series.php?series_id=$id&_t=${System.currentTimeMillis()}")
        }
        candidates.add(requestedUrl)

        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                val response = app.get(candidate, headers = apiHeaders(), referer = mainUrl, timeout = 12L)
                ReynimeParser.parseSeriesDetail(response.text, candidate, mainUrl)
            }.getOrNull()
        }
    }

    private suspend fun fetchEpisodes(series: ReynimeSeries, referer: String): List<Episode> {
        val candidates = linkedSetOf(
            episodeListApi(series.id),
            "$mainUrl/backend/api/episodes.php?series_id=${series.id}&_t=${System.currentTimeMillis()}",
            "$mainUrl/api/episodes?series_id=${series.id}",
            "$mainUrl/api/series/${series.id}/episodes",
            seriesUrl(series),
            bareSeriesUrl(series)
        )

        val apiEpisodes = candidates.flatMap { candidate ->
            runCatching {
                val response = app.get(candidate, headers = apiHeaders(), referer = referer, timeout = 14L)
                val fromApi = ReynimeParser.parseApiEpisodeList(response.text)
                if (fromApi.isNotEmpty()) fromApi else ReynimeParser.parseBackendEpisodeRecords(response.text).mapNotNull { record ->
                    val id = record.id?.toIntOrNull() ?: return@mapNotNull null
                    val ep = record.episodeNumber?.let { extractEpisodeNumber(it) } ?: extractEpisodeNumber(record.title, id.toString()) ?: id
                    ReynimeApiEpisode(id, ep, record.title ?: "Episode $ep", record.poster, record.description, record.urls)
                }
            }.getOrDefault(emptyList())
        }
            .distinctBy { it.id }
            .filter { it.episode >= series.firstEpisode || series.firstEpisode <= 1 }
            .sortedBy { it.episode }

        return apiEpisodes.map { item ->
            val pageUrl = "$mainUrl/watch/${item.id}"
            newEpisode(
                buildEpisodeData(
                    pageUrl = pageUrl,
                    seriesId = series.id,
                    episode = item.episode,
                    episodeId = item.id,
                    title = item.title,
                    seedSlug = series.slug
                )
            ) {
                name = item.title.cleanTitle().ifBlank { "Episode ${item.episode}" }
                episode = item.episode
                posterUrl = item.poster ?: series.poster
                description = item.description
            }
        }
    }

    private fun buildSeedEpisodes(series: ReynimeSeries): List<Episode> {
        val start = series.firstEpisode.coerceAtLeast(1)
        val end = series.latestEpisode.coerceAtLeast(start)
        return (start..end).map { ep ->
            val pageUrl = "$mainUrl/watch/${series.id}/$ep"
            newEpisode(
                buildEpisodeData(
                    pageUrl = pageUrl,
                    seriesId = series.id,
                    episode = ep,
                    title = "Episode $ep",
                    seedSlug = series.slug
                )
            ) {
                name = "Episode $ep"
                episode = ep
                posterUrl = series.poster
            }
        }
    }

    private fun filterSeeds(data: String): List<SearchResponse> {
        return ReynimeSeeds.series
            .filter { matchesRequest(it, data) }
            .sortedWith(
                compareByDescending<ReynimeSeries> { it.updated || data == "updated" }
                    .thenByDescending { it.featured || data == "featured" }
                    .thenByDescending { it.latestEpisode }
            )
            .map { it.toSearchResponse() }
    }

    private fun matchesRequest(item: ReynimeSeries, data: String): Boolean {
        return when {
            data == "featured" -> item.featured
            data == "updated" -> item.updated || item.status.equals("Ongoing", true)
            data == "ongoing" -> item.status.contains("ongoing", true)
            data == "completed" -> item.status.contains("complete", true) || item.status.contains("finished", true)
            data == "donghua" -> item.kind.equals("Donghua", true) || item.genres.contains("donghua")
            data == "anime" -> item.kind.equals("Anime", true) || item.genres.contains("anime")
            data.startsWith("genre:") -> item.genres.contains(data.removePrefix("genre:").lowercase())
            else -> true
        }
    }

    private fun mergeSeries(seed: ReynimeSeries?, official: ReynimeSeries?): ReynimeSeries? {
        if (seed == null) return official
        if (official == null) return seed
        return seed.copy(
            title = official.title.takeIf { it.isNotBlank() } ?: seed.title,
            slug = official.slug.takeIf { it.isNotBlank() } ?: seed.slug,
            poster = official.poster ?: seed.poster,
            status = official.status.takeIf { it.isNotBlank() } ?: seed.status,
            type = official.type,
            latestEpisode = maxOf(seed.latestEpisode, official.latestEpisode),
            firstEpisode = minOf(seed.firstEpisode, official.firstEpisode.coerceAtLeast(1)),
            year = official.year ?: seed.year,
            score = official.score ?: seed.score,
            genres = (seed.genres + official.genres).filter { it.isNotBlank() }.toSet(),
            description = official.description.takeIf { it.isNotBlank() && !it.equals("Streaming Donghua subtitle Indonesia di Reynime.", true) } ?: seed.description,
            featured = seed.featured || official.featured,
            updated = seed.updated || official.updated
        )
    }

    private fun ReynimeSeries.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, seriesUrl(this), type) {
            posterUrl = poster
        }
    }
}
