package com.youtube

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.youtube.YouTubeUtils.bestPoster
import com.youtube.YouTubeUtils.durationMinutes
import com.youtube.YouTubeUtils.formatCompact
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo

class YouTubeProvider : MainAPI() {
    override var mainUrl = YouTubeSeeds.MAIN_URL
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Others)

    private val service = ServiceList.YouTube
    private val videoFilter = listOf("videos")

    override val mainPage = YouTubeSeeds.mainPage.map { category ->
        MainPageData(category.name, category.data)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        if (page > 1) return newHomePageResponse(emptyList(), false)

        val category = YouTubeSeeds.findCategory(request.name, request.data)
            ?: YouTubeCategory(request.name, request.data, YouTubeCategoryMode.Search, request.name)

        val items = when (category.mode) {
            YouTubeCategoryMode.Channel -> getChannelVideos(category.data).ifEmpty {
                getSearchVideos(category.fallbackQuery)
            }
            YouTubeCategoryMode.Search -> getSearchVideos(category.data)
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    private suspend fun getSearchVideos(query: String): List<SearchResponse> {
        return runCatching {
            val searchInfo = SearchInfo.getInfo(
                service,
                service.searchQHFactory.fromQuery(query, videoFilter, "")
            )
            YouTubeParser.parseInfoItems(this, searchInfo.relatedItems)
        }.getOrElse { emptyList() }
    }

    private suspend fun getChannelVideos(url: String): List<SearchResponse> {
        return runCatching {
            val channelInfo = ChannelInfo.getInfo(url)
            val tabs = channelInfo.tabs.mapNotNull { tab ->
                runCatching { ChannelTabInfo.getInfo(service, tab) }.getOrNull()
            }

            val videoTab = tabs.firstOrNull { tab ->
                tab.name.contains("video", ignoreCase = true) ||
                    tab.name.contains("upload", ignoreCase = true)
            } ?: tabs.firstOrNull { tab ->
                tab.relatedItems.any { it is org.schabi.newpipe.extractor.stream.StreamInfoItem }
            }

            YouTubeParser.parseInfoItems(this, videoTab?.relatedItems.orEmpty())
        }.getOrElse { emptyList() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        return getSearchVideos(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        val videoInfo = runCatching { StreamInfo.getInfo(service, url) }.getOrNull() ?: return null
        val title = videoInfo.name.takeIf { it.isNotBlank() } ?: return null
        val runtime = durationMinutes(videoInfo.duration)

        return newMovieLoadResponse(title, url, TvType.Others, url) {
            posterUrl = bestPoster(videoInfo)
            backgroundPosterUrl = bestPoster(videoInfo)
            plot = videoInfo.description?.content.orEmpty()
            if (runtime > 0) duration = runtime
            tags = listOfNotNull(
                videoInfo.uploaderName.takeIf { it.isNotBlank() },
                formatCompact(videoInfo.viewCount)?.let { "👀 $it" },
                formatCompact(videoInfo.likeCount)?.let { "👍 $it" }
            )
            actors = listOf(
                ActorData(
                    Actor(
                        videoInfo.uploaderName,
                        bestAvatarFromStream(videoInfo)
                    )
                )
            )
        }
    }

    private fun bestAvatarFromStream(videoInfo: StreamInfo): String? {
        return videoInfo.uploaderAvatars.lastOrNull()?.url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        return YouTubeExtractor.loadLinks(data, subtitleCallback, callback)
    }
}
