package com.Melolo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.*

/**
 * 🔄 ARCHITECTURE LAYER: MAPPER / ADAPTER
 */

object MeloloMapper {

    suspend fun ScrapedSearch.toSearchResponse(provider: Melolo): SearchResponse {
        val type = if (isMovie) TvType.Movie else if (provider.supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries
        
        return provider.pubNewAnimeSearchResponse(title, url, type) {
            this.posterUrl = poster
            this.posterHeaders = provider.globalHeaders.toMutableMap().apply { 
                put("Referer", provider.mainUrl) 
            }
            this.score = Score.from10(this@toSearchResponse.rating?.toDoubleOrNull())
            addDubStatus(
                dubExist = isDub, 
                subExist = true, 
                subEpisodes = episodeText?.safeExtractEpNum()
            )
        }
    }

    fun ScrapedEpisode.toEpisode(provider: Melolo): Episode {
        return provider.pubNewEpisode(url) {
            this.name = this@toEpisode.name
            this.episode = this@toEpisode.episodeNum
            this.season = this@toEpisode.season
            this.posterUrl = this@toEpisode.poster
            this.description = this@toEpisode.description
            this.runTime = this@toEpisode.runtime
        }
    }

    fun ScrapedActor.toActor(): Actor {
        return Actor(name, image ?: "")
    }

    suspend fun ScrapedDetail.toLoadResponse(
        provider: Melolo,
        url: String,
        type: TvType,
        dataUrl: String,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>,
        actors: List<Actor>
    ): LoadResponse {
        val pHeaders = provider.globalHeaders.toMutableMap().apply { 
            put("Referer", provider.mainUrl) 
        }

        return when (type) {
            TvType.Movie -> {
                provider.pubNewMovieLoadResponse(title, url, type, dataUrl) {
                    this.posterUrl = this@toLoadResponse.poster
                    this.backgroundPosterUrl = this@toLoadResponse.banner
                    this.posterHeaders = pHeaders
                    this.plot = this@toLoadResponse.description
                    this.tags = this@toLoadResponse.tags.ifEmpty { emptyList<String>() }
                    this.year = this@toLoadResponse.year
                    this.score = Score.from10(this@toLoadResponse.rating?.toDoubleOrNull())
                    this.recommendations = recommendations
                    this.comingSoon = this@toLoadResponse.isComingSoon
                    addTrailer(this@toLoadResponse.trailer)
                    addActors(actors)
                    addImdbId(this@toLoadResponse.imdbId)
                    addTMDbId(this@toLoadResponse.tmdbId?.toString())
                }
            }
            else -> {
                provider.pubNewAnimeLoadResponse(title, url, type) {
                    this.posterUrl = this@toLoadResponse.poster
                    this.backgroundPosterUrl = this@toLoadResponse.banner
                    this.posterHeaders = pHeaders
                    this.plot = this@toLoadResponse.description
                    this.tags = this@toLoadResponse.tags.ifEmpty { emptyList<String>() }
                    this.year = this@toLoadResponse.year
                    this.score = Score.from10(this@toLoadResponse.rating?.toDoubleOrNull())
                    this.recommendations = recommendations
                    val ongoingKeyword = provider.getCached(com.Melolo.MeloloConstants.STR_ONGOING, "Ongoing")
                    this.showStatus = if (this@toLoadResponse.statusText?.contains(ongoingKeyword, true) == true) ShowStatus.Ongoing else ShowStatus.Completed
                    addEpisodes(DubStatus.Subbed, episodes)
                    addTrailer(this@toLoadResponse.trailer)
                }
            }
        }
    }
}
