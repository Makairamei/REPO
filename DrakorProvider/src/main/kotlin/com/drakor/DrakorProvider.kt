package com.drakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.drakor.DrakorProviderExtractor.invokeCinemaOS
import com.drakor.DrakorProviderExtractor.invokeDrakor
import com.drakor.DrakorProviderExtractor.invokeGomovies
import com.drakor.DrakorProviderExtractor.invokeIdlix
import com.drakor.DrakorProviderExtractor.invokeKisskh
import com.drakor.DrakorProviderExtractor.invokeMapple
import com.drakor.DrakorProviderExtractor.invokeMoviebox
import com.drakor.DrakorProviderExtractor.invokeMoviebox2
import com.drakor.DrakorProviderExtractor.invokePlayer4U
import com.drakor.DrakorProviderExtractor.invokeRiveStream
import com.drakor.DrakorProviderExtractor.invokeSuperembed
import com.drakor.DrakorProviderExtractor.invokeVidfast
import com.drakor.DrakorProviderExtractor.invokeVidlink
import com.drakor.DrakorProviderExtractor.invokeVidsrc
import com.drakor.DrakorProviderExtractor.invokeVidsrccc
import com.drakor.DrakorProviderExtractor.invokeVixsrc
import com.drakor.DrakorProviderExtractor.invokeWatchsomuch
import com.drakor.DrakorProviderExtractor.invokeWyzie
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.runAllAsync

open class DrakorProvider : TmdbProvider() {
    override var name = "Drakor"
    override val hasMainPage = true
    override var lang = "id"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    val wpRedisInterceptor by lazy { CloudflareKiller() }

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "b030404650f279792a8d3287232358e3"

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$tmdbAPI/discover/tv?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc" to "Popular K-Drama",
        "$tmdbAPI/discover/movie?api_key=$apiKey&with_original_language=ko&sort_by=popularity.desc" to "Popular Korean Movie",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) {
            "https://image.tmdb.org/t/p/w500$link"
        } else {
            link
        }
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        val mediaTitle = title ?: name ?: originalTitle ?: return null
        val mediaTypeFixed = getType(mediaType ?: type)

        return newMovieSearchResponse(
            mediaTitle,
            Data(id = id, type = mediaType ?: type).toJson(),
            mediaTypeFixed,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score = Score.from10(voteAverage?.toString() ?: "0")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = if (request.data.contains("/movie")) "movie" else "tv"

        val home = app.get("${request.data}&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull {
                it.toSearchResponse(type)
            } ?: emptyList()

        return newHomePageResponse(
            request.name,
            home,
            hasNext = true
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get(
            "$tmdbAPI/search/multi?api_key=$apiKey&language=en-US&query=$query&page=1"
        ).parsedSafe<Results>()?.results?.mapNotNull {
            it.toSearchResponse()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = tryParseJson<Data>(url) ?: return null

        val type = getType(data.type)

        val endpoint = if (type == TvType.Movie) {
            "$tmdbAPI/movie/${data.id}?api_key=$apiKey&append_to_response=credits,videos,recommendations"
        } else {
            "$tmdbAPI/tv/${data.id}?api_key=$apiKey&append_to_response=credits,videos,recommendations"
        }

        val res = app.get(endpoint).parsedSafe<MediaDetail>() ?: return null

        val title = res.title ?: res.name ?: return null

        val recommendations =
            res.recommendations?.results?.mapNotNull {
                it.toSearchResponse()
            } ?: emptyList()

        val trailer = res.videos?.results
            ?.mapNotNull { it.key }
            ?.map { "https://www.youtube.com/watch?v=$it" }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null

            ActorData(
                Actor(
                    actorName,
                    getImageUrl(cast.profilePath)
                ),
                roleString = cast.character
            )
        } ?: emptyList()

        return if (type == TvType.Movie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LinkData(
                    id = data.id,
                    type = data.type,
                    title = title,
                ).toJson()
            ) {
                this.posterUrl = getImageUrl(res.posterPath)
                this.backgroundPosterUrl = getImageUrl(res.backdropPath)
                this.plot = res.overview
                this.year = res.releaseDate?.substringBefore("-")?.toIntOrNull()
                this.score = Score.from10(res.voteAverage?.toString() ?: "0")
                this.recommendations = recommendations
                this.actors = actors

                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.imdbId)
            }
        } else {
            val episodes = mutableListOf<Episode>()

            res.seasons?.forEach { season ->
                val seasonData = app.get(
                    "$tmdbAPI/tv/${data.id}/season/${season.seasonNumber}?api_key=$apiKey"
                ).parsedSafe<MediaDetailEpisodes>()

                seasonData?.episodes?.forEach { eps ->
                    episodes.add(
                        newEpisode(
                            LinkData(
                                id = data.id,
                                type = data.type,
                                season = eps.seasonNumber,
                                episode = eps.episodeNumber,
                                title = title,
                            ).toJson()
                        ) {
                            this.name = eps.name
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.description = eps.overview
                            this.score = Score.from10(eps.voteAverage?.toString() ?: "0")
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = getImageUrl(res.posterPath)
                this.backgroundPosterUrl = getImageUrl(res.backdropPath)
                this.plot = res.overview
                this.year = res.firstAirDate?.substringBefore("-")?.toIntOrNull()
                this.score = Score.from10(res.voteAverage?.toString() ?: "0")
                this.recommendations = recommendations
                this.actors = actors

                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = parseJson<LinkData>(data)

        runAllAsync(
            {
                invokeDrakor(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeKisskh(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            },
            {
                invokeMoviebox(
                    res.title,
                    res.year,
                    res.season,
                    res.episode,
                    subtitleCallback,
                    callback
                )
            }
        )

        return true
    }

    data class LinkData(
        val id: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val title: String? = null,
        val year: Int? = null,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
    )

    data class Results(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Cast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Credits(
        @JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class Trailers(
        @JsonProperty("key") val key: String? = null,
    )

    data class ResultsTrailer(
        @JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class Episodes(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("still_path") val stillPath: String? = null,
        @JsonProperty("vote_average") val voteAverage: Double? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Seasons(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetail(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("vote_average") val voteAverage: Any? = null,
        @JsonProperty("credits") val credits: Credits? = null,
        @JsonProperty("videos") val videos: ResultsTrailer? = null,
        @JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
    )
}