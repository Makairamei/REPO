package com.BetbetMiro.idlix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiResponse(
    @JsonProperty("data") val data: List<ApiItem> = emptyList(),
    @JsonProperty("pagination") val pagination: Pagination? = null,
    @JsonProperty("meta") val meta: Meta? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,

    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("poster_path") val posterPathAlt: String? = null,

    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPathAlt: String? = null,

    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("release_date") val releaseDateAlt: String? = null,

    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDateAlt: String? = null,

    @JsonProperty("voteAverage") val voteAverage: String? = null,
    @JsonProperty("vote_average") val voteAverageAlt: String? = null,

    @JsonProperty("viewCount") val viewCount: Any? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,

    @JsonProperty("createdAt") val createdAt: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null,
    @JsonProperty("numberOfEpisodes") val numberOfEpisodes: Int? = null,

    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("content_type") val contentTypeAlt: String? = null,

    @JsonProperty("commentCount") val commentCount: Int? = null,
    @JsonProperty("originalLanguage") val originalLanguage: String? = null,
    @JsonProperty("popularity") val popularity: Any? = null,
    @JsonProperty("genres") val genres: List<APIGenre>? = null,
    @JsonProperty("hasVideo") val hasVideo: Boolean? = null,
    @JsonProperty("isPublished") val isPublished: Boolean? = null
) {
    val posterPathFinal: String? get() = posterPath ?: posterPathAlt
    val backdropPathFinal: String? get() = backdropPath ?: backdropPathAlt
    val releaseDateFinal: String? get() = releaseDate ?: releaseDateAlt
    val firstAirDateFinal: String? get() = firstAirDate ?: firstAirDateAlt
    val voteAverageFinal: String? get() = voteAverage ?: voteAverageAlt
    val contentTypeFinal: String? get() = contentType ?: contentTypeAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class APIGenre(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Pagination(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("limit") val limit: Int? = null,
    @JsonProperty("total") val total: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Meta(
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("network") val network: String? = null,
    @JsonProperty("sort") val sort: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailResponse(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,

    @JsonProperty("imdbId") val imdbId: String? = null,
    @JsonProperty("imdb_id") val imdbIdAlt: String? = null,

    @JsonProperty("tmdbId") val tmdbId: String? = null,
    @JsonProperty("tmdb_id") val tmdbIdAlt: String? = null,

    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("tagline") val tagline: String? = null,

    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("poster_path") val posterPathAlt: String? = null,

    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPathAlt: String? = null,

    @JsonProperty("logoPath") val logoPath: String? = null,
    @JsonProperty("logo_path") val logoPathAlt: String? = null,

    @JsonProperty("backdrops") val backdrops: List<String>? = null,

    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("release_date") val releaseDateAlt: String? = null,

    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDateAlt: String? = null,

    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("voteAverage") val voteAverage: Any? = null,
    @JsonProperty("vote_average") val voteAverageAlt: Any? = null,
    @JsonProperty("popularity") val popularity: Any? = null,

    @JsonProperty("originalLanguage") val originalLanguage: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("status") val status: String? = null,

    @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    @JsonProperty("trailer_url") val trailerUrlAlt: String? = null,

    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("director") val director: String? = null,

    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("cast") val cast: List<Cast>? = null,

    @JsonProperty("seasons") val seasons: List<Season>? = null,
    @JsonProperty("firstSeason") val firstSeason: Season? = null,
    @JsonProperty("first_season") val firstSeasonAlt: Season? = null,

    @JsonProperty("viewCount") val viewCount: Any? = null,
    @JsonProperty("isPublished") val isPublished: Boolean? = null
) {
    val posterPathFinal: String? get() = posterPath ?: posterPathAlt
    val backdropPathFinal: String? get() = backdropPath ?: backdropPathAlt
    val logoPathFinal: String? get() = logoPath ?: logoPathAlt
    val releaseDateFinal: String? get() = releaseDate ?: releaseDateAlt
    val firstAirDateFinal: String? get() = firstAirDate ?: firstAirDateAlt
    val voteAverageFinal: Any? get() = voteAverage ?: voteAverageAlt
    val tmdbIdFinal: String? get() = tmdbId ?: tmdbIdAlt
    val imdbIdFinal: String? get() = imdbId ?: imdbIdAlt
    val trailerUrlFinal: String? get() = trailerUrl ?: trailerUrlAlt
    val firstSeasonFinal: Season? get() = firstSeason ?: firstSeasonAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Genre(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Cast(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profilePath") val profilePath: String? = null,
    @JsonProperty("profile_path") val profilePathAlt: String? = null
) {
    val profilePathFinal: String? get() = profilePath ?: profilePathAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumberAlt: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("poster_path") val posterPathAlt: String? = null,
    @JsonProperty("episodes") val episodes: List<Episode>? = null
) {
    val seasonNumberFinal: Int? get() = seasonNumber ?: seasonNumberAlt
    val posterPathFinal: String? get() = posterPath ?: posterPathAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @JsonProperty("episode_number") val episodeNumberAlt: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("stillPath") val stillPath: String? = null,
    @JsonProperty("still_path") val stillPathAlt: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("air_date") val airDateAlt: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("voteAverage") val voteAverage: Any? = null,
    @JsonProperty("vote_average") val voteAverageAlt: Any? = null
) {
    val episodeNumberFinal: Int? get() = episodeNumber ?: episodeNumberAlt
    val stillPathFinal: String? get() = stillPath ?: stillPathAlt
    val voteAverageFinal: Any? get() = voteAverage ?: voteAverageAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonWrapper(
    @JsonProperty("season") val season: Season? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchApiResponse(
    @JsonProperty("results") val results: List<SearchApiResult> = emptyList(),
    @JsonProperty("total") val total: Long = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchApiResult(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("content_type") val contentTypeAlt: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("originalTitle") val originalTitle: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("originalLanguage") val originalLanguage: String? = null,
    @JsonProperty("voteAverage") val voteAverage: Double? = null,
    @JsonProperty("viewCount") val viewCount: Long? = null,
    @JsonProperty("popularity") val popularity: Double? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("poster_path") val posterPathAlt: String? = null,
    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPathAlt: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDateAlt: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Long? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("release_date") val releaseDateAlt: String? = null,
    @JsonProperty("quality") val quality: String? = null
) {
    val contentTypeFinal: String? get() = contentType ?: contentTypeAlt
    val posterPathFinal: String? get() = posterPath ?: posterPathAlt
    val releaseDateFinal: String? get() = releaseDate ?: releaseDateAlt
    val firstAirDateFinal: String? get() = firstAirDate ?: firstAirDateAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LoadData(
    @JsonProperty("id") val id: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Res(
    @JsonProperty("claim") val claim: String? = null,
    @JsonProperty("redeemUrl") val redeemUrl: String? = null,
    @JsonProperty("redeem_url") val redeemUrlAlt: String? = null,
    @JsonProperty("url") val url: String? = null
) {
    val redeemUrlFinal: String? get() = redeemUrl ?: redeemUrlAlt ?: url
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Iframe(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("embedUrl") val embedUrl: String? = null,
    @JsonProperty("embed_url") val embedUrlAlt: String? = null,
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("videoSource") val videoSource: String? = null,
    @JsonProperty("video_source") val videoSourceAlt: String? = null,
    @JsonProperty("videoUrl") val videoUrl: String? = null,
    @JsonProperty("video_url") val videoUrlAlt: String? = null,
    @JsonProperty("expiresAt") val expiresAt: Long? = null,
    @JsonProperty("expires_at") val expiresAtAlt: Long? = null,
    @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList(),
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("video_id") val videoIdAlt: String? = null
) {
    val streamUrlFinal: String?
        get() = url
            ?: embedUrl
            ?: embedUrlAlt
            ?: src
            ?: file
            ?: source
            ?: videoSource
            ?: videoSourceAlt
            ?: videoUrl
            ?: videoUrlAlt
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("path") val path: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null
) {
    val labelFinal: String get() = label ?: lang ?: "Subtitle"
    val pathFinal: String? get() = path ?: url ?: file
}