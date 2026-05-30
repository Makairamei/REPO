package com.sad25kag.idlix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Film Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "Serial TV Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest" to "Update Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=popular" to "Populer",
        "$mainUrl/api/browse?page=%d&limit=36&sort=rating" to "Papan Peringkat",

        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=action" to "Action",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=adventure" to "Adventure",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=animation" to "Animation",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=comedy" to "Comedy",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=crime" to "Crime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=documentary" to "Documentary",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=drama" to "Drama",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=family" to "Family",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=fantasy" to "Fantasy",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=history" to "History",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=horror" to "Horror",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=kids" to "Kids",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=music" to "Music",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=mystery" to "Mystery",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=reality" to "Reality",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=romance" to "Romance",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=science-fiction" to "Science Fiction",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=soap" to "Soap",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=talk" to "Talk",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=thriller" to "Thriller",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=tv-movie" to "TV Movie",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=war" to "War",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=western" to "Western",


        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2026" to "2026",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2025" to "2025",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2024" to "2024",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2023" to "2023",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2022" to "2022",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2021" to "2021",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2020" to "2020",

        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Prime Video",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
    )

    private val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) {
            request.data.format(page.coerceAtLeast(1))
        } else {
            request.data
        }

        val parsed = app.get(url, headers = apiHeaders, referer = mainUrl, timeout = 10000L)
            .parsedSafe<ApiResponse>()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val fallbackType = when {
            url.contains("/api/movies", true) -> "movie"
            url.contains("/api/series", true) -> "series"
            else -> null
        }

        val items = parsed.data
            .mapNotNull { it.toSearchResponse(fallbackType) }
            .distinctBy { it.url }

        val totalPages = parsed.pagination?.totalPages ?: 0

        return newHomePageResponse(
            request.name,
            items,
            hasNext = if (totalPages > 0) page < totalPages else items.isNotEmpty(),
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(
        query: String,
        page: Int,
    ): SearchResponseList? {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "$mainUrl/api/search?q=$encoded&page=${page.coerceAtLeast(1)}&limit=12"
        val parsed = app.get(url, headers = apiHeaders, referer = mainUrl, timeout = 10000L)
            .parsedSafe<SearchApiResponse>() ?: return null

        val results = parsed.results
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = app.get(url, headers = apiHeaders, referer = mainUrl, timeout = 10000L)
            .parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid IDLIX API response")

        val title = data.title?.takeIf { it.isNotBlank() } ?: "Unknown"
        val poster = data.posterPath?.tmdbPoster("w500")
        val backdrop = data.backdropPath?.tmdbPoster("w780")
        val logo = data.logoPath?.tmdbPoster("w500")
        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }.orEmpty()
        val actors = data.cast?.mapNotNull { cast ->
            cast.name?.takeIf { it.isNotBlank() }?.let { name ->
                Actor(name, cast.profilePath?.tmdbPoster("w185"))
            }
        }.orEmpty()

        val isSeries = !data.seasons.isNullOrEmpty()
        val webUrl = if (isSeries) {
            "$mainUrl/series/${data.slug.orEmpty()}"
        } else {
            "$mainUrl/movie/${data.slug.orEmpty()}"
        }

        val recommendations = loadRecommendations(data, isSeries)

        return if (isSeries) {
            val episodes = loadEpisodes(data)

            newTvSeriesLoadResponse(title, webUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                this.duration = data.runtime ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        } else {
            newMovieLoadResponse(
                title,
                webUrl,
                TvType.Movie,
                LoadData(
                    id = data.id.orEmpty(),
                    type = "movie",
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                this.duration = data.runtime ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

    private suspend fun loadEpisodes(data: DetailResponse): List<Episode> {
        val slug = data.slug.orEmpty()
        val episodes = mutableListOf<Episode>()

        data.firstSeason?.episodes?.forEach { ep ->
            val id = ep.id ?: return@forEach
            episodes.add(ep.toCloudstreamEpisode(data.firstSeason.seasonNumber ?: 1, id))
        }

        data.seasons.orEmpty().forEach { season ->
            val seasonNumber = season.seasonNumber ?: return@forEach
            if (seasonNumber == data.firstSeason?.seasonNumber) return@forEach

            val seasonData = runCatching {
                app.get(
                    "$mainUrl/api/series/$slug/season/$seasonNumber",
                    headers = apiHeaders,
                    referer = mainUrl,
                    timeout = 10000L,
                ).parsedSafe<SeasonWrapper>()?.season
            }.getOrNull()

            seasonData?.episodes?.forEach { ep ->
                val id = ep.id ?: return@forEach
                episodes.add(ep.toCloudstreamEpisode(seasonNumber, id))
            }
        }

        return episodes.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 }
        )
    }

    private fun com.sad25kag.idlix.Episode.toCloudstreamEpisode(
        seasonNumber: Int,
        id: String,
    ): Episode {
        return newEpisode(
            LoadData(
                id = id,
                type = "episode",
            ).toJson(),
        ) {
            this.name = name
            this.season = seasonNumber
            this.episode = episodeNumber
            this.description = overview
            this.runTime = runtime
            this.score = Score.from10(voteAverage?.toString())
            addDate(airDate)
            this.posterUrl = stillPath?.tmdbPoster("w300")
        }
    }

    private suspend fun loadRecommendations(
        data: DetailResponse,
        isSeries: Boolean,
    ): List<SearchResponse> {
        val slug = data.slug ?: return emptyList()
        val relatedUrl = if (isSeries) {
            "$mainUrl/api/series/$slug/related"
        } else {
            "$mainUrl/api/movies/$slug/related"
        }

        return runCatching {
            app.get(relatedUrl, headers = apiHeaders, referer = mainUrl, timeout = 10000L)
                .parsedSafe<ApiResponse>()
                ?.data
                ?.mapNotNull { it.toSearchResponse(null) }
                ?.distinctBy { it.url }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        if (parsed.id.isBlank() || parsed.type.isBlank()) return false

        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
        )

        val playResponse = app.get(
            "$mainUrl/api/watch/play-info/${parsed.type}/${parsed.id}",
            headers = headers,
            referer = mainUrl,
            timeout = 10000L,
        )

        val cookies = playResponse.cookies
        val playInfo = playResponse.parsedSafe<Res>() ?: return false

        val waitTime = (playInfo.unlockAt - playInfo.serverNow).coerceAtLeast(0)
        val totalWait = (waitTime / 1000).coerceAtLeast(0)
        var elapsed = 0L

        while (elapsed < totalWait) {
            Log.d(name, "Waiting IDLIX gate: ${elapsed}s / ${totalWait}s")
            delay(1000)
            elapsed++
        }

        val claimResponse = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = headers,
            cookies = cookies,
            referer = mainUrl,
            requestBody = """{"gateToken":"${playInfo.gateToken}"}"""
                .toRequestBody("application/json".toMediaType()),
            timeout = 10000L,
        ).parsedSafe<RedeemRes>() ?: return false

        val redeemUrl = claimResponse.redeemUrl.fixAgainstMainUrl() ?: return false
        val iframeResponse = app.post(
            redeemUrl,
            headers = headers,
            cookies = cookies,
            referer = mainUrl,
            requestBody = """{"claim":"${claimResponse.claim}"}"""
                .toRequestBody("application/json".toMediaType()),
            timeout = 10000L,
        ).parsedSafe<Iframe>() ?: return false

        val streamUrl = iframeResponse.url?.takeIf { it.isNotBlank() }?.fixAgainstMainUrl()
        var delivered = false

        if (!streamUrl.isNullOrBlank()) {
            generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = mainUrl,
                headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl),
            ).forEach { link ->
                callback(link)
                delivered = true
            }
        }

        iframeResponse.subtitles.forEach { subtitle ->
            val path = subtitle.path?.takeIf { it.isNotBlank() }?.fixAgainstMainUrl() ?: return@forEach
            subtitleCallback(
                newSubtitleFile(
                    subtitle.label?.takeIf { it.isNotBlank() } ?: subtitle.lang ?: "Subtitle",
                    path,
                ),
            )
        }

        return delivered
    }

    private fun ApiItem.toSearchResponse(defaultType: String?): SearchResponse? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val type = contentType ?: defaultType.orEmpty()
        val poster = posterPath?.tmdbPoster("w342")
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val rating = voteAverage?.toString()
        val qualityText = quality

        return if (type.isSeriesType()) {
            newTvSeriesSearchResponse(title, "$mainUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    private fun SearchApiResult.toSearchResponse(): SearchResponse? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val poster = posterPath?.tmdbPoster("w342")
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val rating = voteAverage?.toString()
        val qualityText = quality

        return if (contentType.isSeriesType()) {
            newTvSeriesSearchResponse(title, "$mainUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    private fun String?.isSeriesType(): Boolean {
        val value = this.orEmpty().lowercase()
        return value == "tv_series" || value == "series" || value == "tv" || value.contains("series")
    }

    private fun String.tmdbPoster(size: String): String {
        return if (startsWith("http", true)) this else "https://image.tmdb.org/t/p/$size$this"
    }

    private fun String.fixAgainstMainUrl(): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(mainUrl).resolve(value).toString() }.getOrNull()
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val value = check ?: return null
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
    )

    for ((regex, quality) in patterns) {
        if (regex.containsMatchIn(normalized)) return quality
    }

    return null
}
