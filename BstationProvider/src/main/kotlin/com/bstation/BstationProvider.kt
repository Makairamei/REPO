package com.bstation

import com.excloud.BuildConfig
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URLEncoder

class BstationProvider : MainAPI() {
    override var mainUrl = "https://www.bilibili.tv"
    override var name = "Bstation"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val locale = "id_ID"
    private val regionBase = "$mainUrl/id"
    private val apiBase = "https://api.bilibili.tv/intl/gateway/web/v2"
    private val playApiBase = "https://api.bilibili.tv/intl/gateway/web"
    private val legacyApiBase = "https://api.bilibili.tv/intl/gateway/v2"
    private val cookieHeader = BuildConfig.BSTATION_COOKIE.trim().takeIf { it.isNotBlank() }
    private val cookieMap: Map<String, String> by lazy {
        cookieHeader?.split(";")
            ?.mapNotNull { token ->
                val idx = token.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = token.substring(0, idx).trim()
                val value = token.substring(idx + 1).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            ?.toMap()
            .orEmpty()
    }
    private val baseHeaders = mapOf(
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    )

    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "timeline" to "Update Terbaru",
        "anime" to "Anime Indonesia",
        "search:anime" to "Anime",
        "search:movie" to "Film Anime",
        "search:drama" to "Drama",
        "search:series" to "Serial",
        "search:action" to "Aksi",
        "search:adventure" to "Petualangan",
        "search:comedy" to "Komedi",
        "search:romance" to "Romantis",
        "search:fantasy" to "Fantasi",
        "search:isekai" to "Isekai",
        "search:sci-fi" to "Fiksi Ilmiah",
        "search:thriller" to "Thriller",
        "search:horror" to "Horor",
        "search:mystery" to "Misteri",
        "search:slice of life" to "Slice of Life",
        "search:school" to "Sekolah",
        "search:sports" to "Olahraga",
        "search:music" to "Musik",
        "search:supernatural" to "Supernatural",
        "search:donghua" to "Donghua",
        "search:indonesia" to "Subtitle Indonesia",
        "search:dub indonesia" to "Dub Indonesia",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }

        val items = when {
            request.data == "timeline" -> fetchTimelineApi(40).ifEmpty { fetchAnimePageFallback(40) }
            request.data == "anime" -> fetchTimelineApi(40).ifEmpty { fetchAnimePageFallback(40) }
            request.data.startsWith("search:") -> {
                val keyword = request.data.removePrefix("search:").trim()
                fetchSearchApi(keyword, 40).ifEmpty {
                    fetchTimelineApi(120).filter { it.name.contains(keyword, ignoreCase = true) }.take(40)
                }
            }
            else -> fetchSearchApi(request.data, 40).ifEmpty { fetchAnimePageFallback(40) }
        }.distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = false
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$regionBase/search-result?q=${query.urlEncoded()}"
        val document = app.get(url, headers = requestHeaders()).document

        val htmlResults = document.select(".all-sheet__ogv_subject .ogv--season").mapNotNull { card ->
            val href = card.selectFirst("a[href*=/play/]")?.attr("href")?.toAbsoluteBstationUrl() ?: return@mapNotNull null
            val title = card.selectFirst(".ogv__content-title p")?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src")
            val label = card.selectFirst(".cover-mask-text")?.text().orEmpty()
            newAnimeSearchResponse(title, href, inferType(label, singleEpisode = false)) {
                this.posterUrl = poster?.normalizeUrl()
            }
        }.distinctBy { it.url }

        if (htmlResults.isNotEmpty()) return htmlResults

        val timelineMatch = fetchTimelineApi(150).filter {
            it.name.contains(query, ignoreCase = true)
        }
        if (timelineMatch.isNotEmpty()) return timelineMatch

        return fetchSearchApi(query, 20)
    }

    override suspend fun load(url: String): LoadResponse {
        val seasonId = url.extractSeasonId() ?: throw ErrorLoadingException("Season ID Bstation tidak ditemukan")
        val season = fetchSeasonInfo(seasonId)?.data?.season
        val sections = fetchEpisodes(seasonId)?.data?.sections.orEmpty()
        val episodes = sections.flatMap { section ->
            section.episodes.orEmpty().mapNotNull { episode -> episode.toEpisode(seasonId) }
        }
        val legacySeason = if (season == null || episodes.isEmpty()) {
            fetchLegacySeason(seasonId)?.result
        } else {
            null
        }

        if (episodes.isNotEmpty()) {
            val type = inferType(season?.indexShow, episodes.size <= 1)
            return newAnimeLoadResponse(
                season?.title ?: legacySeason?.title ?: "Bstation",
                "$regionBase/play/$seasonId",
                type
            ) {
                posterUrl = season?.verticalCover?.normalizeUrl() ?: legacySeason?.cover?.normalizeUrl()
                backgroundPosterUrl = season?.horizontalCover?.normalizeUrl()
                plot = season?.description ?: legacySeason?.evaluate
                tags = season?.styles.orEmpty().mapNotNull { style -> style.title?.translateGenre() }.distinct()
                year = season?.playerDate?.extractYear()
                showStatus = when (season?.isFinished) {
                    true -> ShowStatus.Completed
                    false -> ShowStatus.Ongoing
                    null -> null
                }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        val fallbackLegacySeason = legacySeason ?: fetchLegacySeason(seasonId)?.result
            ?: throw ErrorLoadingException("Season Bstation tidak ditemukan")
        val legacyEpisodes = fallbackLegacySeason.allEpisodes(seasonId)
        val legacyType = inferType(null, legacyEpisodes.size <= 1)
        return newAnimeLoadResponse(
            fallbackLegacySeason.title ?: "Bstation",
            "$regionBase/play/$seasonId",
            legacyType
        ) {
            posterUrl = fallbackLegacySeason.cover?.normalizeUrl()
            plot = fallbackLegacySeason.evaluate
            addEpisodes(DubStatus.Subbed, legacyEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeData = parseJson<EpisodeData>(data)
        if (episodeData.watchUrl.isBlank()) return false

        var hasLinks = false
        val emittedLinks = linkedSetOf<String>()

        fetchSubtitleData(episodeData.episodeId).forEach { sub ->
            val subtitleUrl = (sub.srt?.url ?: sub.ass?.url).orEmpty().normalizeUrl()
            if (subtitleUrl.isBlank()) return@forEach
            subtitleCallback(newSubtitleFile(sub.lang ?: sub.langKey ?: "Indonesia", subtitleUrl))
        }

        fetchPlayUrl(episodeData).forEach { stream ->
            val streamUrl = stream.videoResource?.url?.normalizeUrl().orEmpty()
            if (streamUrl.isBlank()) return@forEach
            callback(
                newExtractorLink(
                    source = name,
                    name = listOfNotNull(name, stream.streamInfo?.descWords).joinToString(" "),
                    url = streamUrl,
                    type = inferStreamType(streamUrl)
                ) {
                    this.quality = stream.videoResource?.quality.toBstationQuality()
                    this.referer = "$regionBase/"
                    this.headers = mapOf(
                        "Referer" to "$regionBase/",
                        "Origin" to mainUrl,
                    )
                }
            )
            hasLinks = true
        }

        if (hasLinks) return true

        val response = app.get(
            episodeData.watchUrl,
            headers = requestHeaders(),
            referer = "$regionBase/play/${episodeData.seasonId}"
        )
        val html = response.text
        val document = Jsoup.parse(html)

        extractSubtitles(html).forEach { sub ->
            val subUrl = sub.url?.normalizeUrl().orEmpty()
            if (subUrl.isBlank()) return@forEach
            subtitleCallback(newSubtitleFile(sub.title ?: sub.key ?: "Indonesia", subUrl))
        }

        val mediaCandidates = linkedSetOf<String>()
        document.select("meta[property=og:video], meta[property=og:video:url]")
            .mapNotNullTo(mediaCandidates) { it.attr("content").takeIf { value -> value.contains(".m3u8") || value.contains(".mp4") } }
        Regex("""playUrl:"(https?:\\u002F\\u002F[^"]+)"""").findAll(html)
            .mapTo(mediaCandidates) { it.groupValues[1].normalizeUrl() }
        Regex("""https?:\\u002F\\u002F[^"'\\\s<]+(?:m3u8|mp4)[^"'\\\s<]*""").findAll(html)
            .mapTo(mediaCandidates) { it.value.normalizeUrl() }

        mediaCandidates.forEach { mediaUrl ->
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mediaUrl,
                    type = inferStreamType(mediaUrl)
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$regionBase/"
                    this.headers = mapOf(
                        "Referer" to "$regionBase/",
                        "Origin" to mainUrl,
                    )
                }
            )
        }

        return mediaCandidates.isNotEmpty()
    }

    private suspend fun fetchSeasonInfo(seasonId: String): SeasonInfoRoot? {
        return app.get(
            "$apiBase/ogv/play/season_info?season_id=$seasonId&platform=web&s_locale=$locale",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/play/$seasonId"
        ).parsedSafe<SeasonInfoRoot>()?.takeIf { it.code == 0 }
    }

    private suspend fun fetchEpisodes(seasonId: String): EpisodesRoot? {
        return app.get(
            "$apiBase/ogv/play/episodes?season_id=$seasonId&platform=web&s_locale=$locale",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/play/$seasonId"
        ).parsedSafe<EpisodesRoot>()?.takeIf { it.code == 0 }
    }

    private suspend fun fetchPlayUrl(episodeData: EpisodeData): List<VideoEntry> {
        val preferredQn = listOf(80, 64, 32, 16)
        for (qn in preferredQn) {
            val response = app.get(
                "$playApiBase/playurl?ep_id=${episodeData.episodeId}&platform=web&qn=$qn&type=0&device=wap&tf=0&force_container=2&s_locale=$locale",
                headers = requestHeaders(),
                cookies = requestCookies(),
                referer = episodeData.watchUrl
            ).parsedSafe<PlayUrlRoot>()
            val payload = response?.data ?: response?.directData
            val streams = payload?.playurl?.video.orEmpty()
                .filter { it.videoResource?.url.isNullOrBlank().not() }
                .distinctBy { it.videoResource?.url }
                .sortedByDescending { it.videoResource?.quality ?: 0 }
            if (streams.isNotEmpty()) return streams
        }

        val legacyResponse = app.get(
            "$legacyApiBase/ogv/playurl?ep_id=${episodeData.episodeId}&platform=web&qn=64&type=mp4&tf=0&s_locale=$locale",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = episodeData.watchUrl
        ).parsedSafe<LegacyPlayUrlRoot>()

        return legacyResponse?.data?.videoInfo?.streamList.orEmpty()
            .mapNotNull { stream ->
                val streamUrl = stream.dashVideo?.baseUrl ?: stream.baseUrl
                if (streamUrl.isNullOrBlank()) return@mapNotNull null
                VideoEntry(
                    streamInfo = StreamInfoDto(descWords = stream.streamInfo?.displayDesc),
                    videoResource = VideoResourceDto(
                        url = streamUrl,
                        quality = stream.streamInfo?.quality
                    )
                )
            }
            .distinctBy { it.videoResource?.url }
            .sortedByDescending { it.videoResource?.quality ?: 0 }
    }

    private suspend fun fetchSubtitleData(episodeId: String): List<VideoSubtitleEntry> {
        val response = app.get(
            "$apiBase/subtitle?episode_id=$episodeId&s_locale=$locale",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/play"
        ).parsedSafe<SubtitleRoot>() ?: return emptyList()

        val subtitles = response.data?.videoSubtitle.orEmpty()
        return if (subtitles.isNotEmpty()) {
            subtitles
        } else {
            fetchLegacyEpisode(episodeId)?.data?.subtitles.orEmpty().map {
                VideoSubtitleEntry(
                    lang = it.title ?: it.lang,
                    langKey = it.lang,
                    srt = SubtitleUrlDto(url = it.url),
                    ass = null
                )
            }
        }
    }

    private suspend fun fetchLegacySeason(seasonId: String): LegacySeasonRoot? {
        return app.get(
            "$legacyApiBase/ogv/view/app/season?season_id=$seasonId&platform=web&s_locale=$locale",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/play/$seasonId"
        ).parsedSafe<LegacySeasonRoot>()
    }

    private suspend fun fetchLegacyEpisode(episodeId: String): LegacyEpisodeRoot? {
        return app.get(
            "$legacyApiBase/ogv/view/app/episode?ep_id=$episodeId&platform=web&s_locale=$locale",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/play"
        ).parsedSafe<LegacyEpisodeRoot>()
    }

    private suspend fun fetchSearchApi(query: String, limit: Int): List<SearchResponse> {
        val response = app.get(
            "$apiBase/search_result?keyword=${query.urlEncoded()}&s_locale=$locale&limit=$limit",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/search-result?q=${query.urlEncoded()}"
        ).parsedSafe<SearchApiRoot>() ?: return emptyList()

        return response.data?.modules.orEmpty()
            .flatMap { it.data?.items.orEmpty() }
            .mapNotNull { item ->
                val seasonId = item.seasonId ?: return@mapNotNull null
                val title = item.title?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                newAnimeSearchResponse(title, "$regionBase/play/$seasonId", TvType.Anime) {
                    posterUrl = (item.cover ?: item.poster ?: item.horizontalCover)?.normalizeUrl()
                }
            }
            .distinctBy { it.url }
    }

    private suspend fun fetchTimelineApi(limit: Int): List<SearchResponse> {
        val response = app.get(
            "$apiBase/ogv/timeline?s_locale=$locale&platform=web",
            headers = requestHeaders(),
            cookies = requestCookies(),
            referer = "$regionBase/anime"
        ).parsedSafe<TimelineRoot>() ?: return emptyList()

        return response.data?.items.orEmpty()
            .flatMap { it.cards.orEmpty() }
            .mapNotNull { card ->
                val seasonId = card.seasonId ?: return@mapNotNull null
                val title = card.title?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                newAnimeSearchResponse(title, "$regionBase/play/$seasonId", TvType.Anime) {
                    posterUrl = card.cover?.normalizeUrl()
                }
            }
            .distinctBy { it.url }
            .take(limit)
    }

    private suspend fun fetchAnimePageFallback(limit: Int): List<SearchResponse> {
        val document = app.get("$regionBase/anime", headers = requestHeaders()).document
        val directCards = document.select("a[href*=/id/play/]").mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteBstationUrl()
            val seasonId = href.extractSeasonId() ?: return@mapNotNull null
            val title = anchor.attr("title").trim()
                .ifBlank { anchor.text().trim() }
                .ifBlank { return@mapNotNull null }
            val poster = anchor.selectFirst("img")?.attr("src")
            newAnimeSearchResponse(title, "$regionBase/play/$seasonId", TvType.Anime) {
                this.posterUrl = poster?.normalizeUrl()
            }
        }
        if (directCards.isNotEmpty()) return directCards.distinctBy { it.url }.take(limit)

        val seasonIds = Regex("""/id/play/(\d+)""")
            .findAll(document.html())
            .map { it.groupValues[1] }
            .distinct()
            .take(limit)
            .toList()

        return seasonIds.map { seasonId ->
            newAnimeSearchResponse("Anime $seasonId", "$regionBase/play/$seasonId", TvType.Anime)
        }
    }

    private fun requestHeaders(): Map<String, String> {
        return if (cookieHeader == null) {
            baseHeaders
        } else {
            baseHeaders + ("Cookie" to cookieHeader)
        }
    }

    private fun requestCookies(): Map<String, String> = cookieMap

    private fun SeasonInfoRoot.toSearchResponse(): SearchResponse? {
        val season = data?.season ?: return null
        val seasonId = season.seasonId ?: return null
        val type = inferType(season.indexShow, false)
        return newAnimeSearchResponse(
            season.title ?: return null,
            "$regionBase/play/$seasonId",
            type
        ) {
            this.posterUrl = season.verticalCover?.normalizeUrl()
        }
    }

    private fun inferType(label: String?, singleEpisode: Boolean): TvType {
        return if (singleEpisode || label?.contains("full", true) == true) TvType.AnimeMovie else TvType.Anime
    }

    private fun inferStreamType(url: String): ExtractorLinkType {
        return when {
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun extractSubtitles(html: String): List<SubtitleEntry> {
        val block = Regex("""subtitleList:\[(.*?)]""").find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        return Regex("""\{key:"([^"]+)",title:"([^"]*)",url:"([^"]+)"(?:,assUrl:"([^"]*)")?}""")
            .findAll(block)
            .map {
                SubtitleEntry(
                    key = it.groupValues.getOrNull(1),
                    title = it.groupValues.getOrNull(2),
                    url = it.groupValues.getOrNull(3),
                    assUrl = it.groupValues.getOrNull(4),
                )
            }
            .toList()
    }

    private fun EpisodeDto.toEpisode(seasonId: String): Episode? {
        val currentEpisodeId = episodeId ?: return null
        val watchUrl = "$regionBase/play/$seasonId/$currentEpisodeId"
        return newEpisode(
            EpisodeData(
                seasonId = seasonId,
                episodeId = currentEpisodeId,
                watchUrl = watchUrl,
            ).toJson()
        ) {
            name = titleDisplay ?: longTitleDisplay ?: shortTitleDisplay
            posterUrl = cover?.normalizeUrl()
            episode = shortTitleDisplay?.filter { it.isDigit() }?.toIntOrNull()
        }
    }

    private fun LegacySeasonData.allEpisodes(seasonId: String): List<Episode> {
        val merged = linkedMapOf<String, LegacyEpisodeDto>()
        episodes.orEmpty().forEach { episode ->
            val id = episode.id?.toString() ?: return@forEach
            merged[id] = episode
        }
        modules.orEmpty().flatMap { it.data?.episodes.orEmpty() }.forEach { episode ->
            val id = episode.id?.toString() ?: return@forEach
            merged.putIfAbsent(id, episode)
        }

        return merged.values.mapNotNull { episode ->
            val currentEpisodeId = episode.id?.toString() ?: return@mapNotNull null
            newEpisode(
                EpisodeData(
                    seasonId = seasonId,
                    episodeId = currentEpisodeId,
                    watchUrl = "$regionBase/play/$seasonId/$currentEpisodeId",
                ).toJson()
            ) {
                val episodeNumber = episode.index?.filter { it.isDigit() }?.toIntOrNull()
                    ?: episode.title?.toIntOrNull()
                name = when {
                    episode.title.isNullOrBlank() -> "Episode ${episodeNumber ?: "?"}"
                    episode.title?.toIntOrNull() != null -> "Episode ${episode.title}"
                    else -> episode.title
                }
                this.episode = episodeNumber
                posterUrl = episode.cover?.normalizeUrl()
            }
        }
    }

    private fun String.extractSeasonId(): String? {
        return Regex("""/(?:play|media)/(\d+)""").find(this)?.groupValues?.getOrNull(1)
            ?: trim().takeIf { it.all(Char::isDigit) }
    }

    private fun String.toAbsoluteBstationUrl(): String {
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun String.normalizeUrl(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }

    private fun Int?.toBstationQuality(): Int {
        return when (this) {
            112, 116 -> Qualities.P1080.value
            80 -> Qualities.P720.value
            64, 74 -> Qualities.P480.value
            32 -> Qualities.P360.value
            16, 15, 6 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.translateGenre(): String {
        return when (trim().lowercase()) {
            "action", "aksi" -> "Aksi"
            "adventure", "petualangan" -> "Petualangan"
            "anime" -> "Anime"
            "comedy", "komedi" -> "Komedi"
            "drama" -> "Drama"
            "fantasy", "fantasi" -> "Fantasi"
            "horror", "horor" -> "Horor"
            "isekai" -> "Isekai"
            "movie", "movies", "film" -> "Film"
            "mystery", "misteri" -> "Misteri"
            "romance", "romantis" -> "Romantis"
            "school", "school life", "sekolah" -> "Sekolah"
            "sci-fi", "science fiction", "fiksi ilmiah" -> "Fiksi Ilmiah"
            "slice of life" -> "Slice of Life"
            "sports", "olahraga" -> "Olahraga"
            "music", "musik" -> "Musik"
            "supernatural" -> "Supernatural"
            "thriller" -> "Thriller"
            "donghua" -> "Donghua"
            else -> trim()
        }
    }

    private fun String.extractYear(): Int? = Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    data class EpisodeData(
        val seasonId: String,
        val episodeId: String,
        val watchUrl: String,
    )

    data class SubtitleEntry(
        val key: String? = null,
        val title: String? = null,
        val url: String? = null,
        val assUrl: String? = null,
    )

    data class PlayUrlRoot(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: PlayUrlPayload? = null,
        @JsonProperty("playurl") val playurl: PlayUrlData? = null,
    ) {
        val directData: PlayUrlPayload?
            get() = playurl?.let { PlayUrlPayload(playurl = it) }
    }

    data class PlayUrlPayload(
        @JsonProperty("playurl") val playurl: PlayUrlData? = null,
    )

    data class PlayUrlData(
        @JsonProperty("video") val video: List<VideoEntry>? = null,
    )

    data class VideoEntry(
        @JsonProperty("stream_info") val streamInfo: StreamInfoDto? = null,
        @JsonProperty("video_resource") val videoResource: VideoResourceDto? = null,
    )

    data class StreamInfoDto(
        @JsonProperty("desc_words") val descWords: String? = null,
    )

    data class VideoResourceDto(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: Int? = null,
    )

    data class LegacyPlayUrlRoot(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: LegacyPlayData? = null,
    )

    data class LegacyPlayData(
        @JsonProperty("video_info") val videoInfo: LegacyVideoInfo? = null,
    )

    data class LegacyVideoInfo(
        @JsonProperty("stream_list") val streamList: List<LegacyStreamItem>? = null,
    )

    data class LegacyStreamItem(
        @JsonProperty("stream_info") val streamInfo: LegacyStreamInfo? = null,
        @JsonProperty("dash_video") val dashVideo: LegacyDashVideo? = null,
        @JsonProperty("base_url") val baseUrl: String? = null,
    )

    data class LegacyStreamInfo(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("display_desc") val displayDesc: String? = null,
    )

    data class LegacyDashVideo(
        @JsonProperty("base_url") val baseUrl: String? = null,
    )

    data class SubtitleRoot(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: SubtitlePayload? = null,
    )

    data class SubtitlePayload(
        @JsonProperty("video_subtitle") val videoSubtitle: List<VideoSubtitleEntry>? = null,
    )

    data class VideoSubtitleEntry(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("lang_key") val langKey: String? = null,
        @JsonProperty("srt") val srt: SubtitleUrlDto? = null,
        @JsonProperty("ass") val ass: SubtitleUrlDto? = null,
    )

    data class SubtitleUrlDto(
        @JsonProperty("url") val url: String? = null,
    )

    data class SearchApiRoot(
        @JsonProperty("data") val data: SearchApiData? = null,
    )

    data class SearchApiData(
        @JsonProperty("modules") val modules: List<SearchApiModule>? = null,
    )

    data class SearchApiModule(
        @JsonProperty("data") val data: SearchApiModuleData? = null,
    )

    data class SearchApiModuleData(
        @JsonProperty("items") val items: List<SearchApiItem>? = null,
    )

    data class SearchApiItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("season_id") val seasonId: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("horizontal_cover") val horizontalCover: String? = null,
    )

    data class TimelineRoot(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: TimelineData? = null,
    )

    data class TimelineData(
        @JsonProperty("items") val items: List<TimelineDay>? = null,
    )

    data class TimelineDay(
        @JsonProperty("cards") val cards: List<TimelineCard>? = null,
    )

    data class TimelineCard(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("season_id") val seasonId: String? = null,
    )

    data class SeasonInfoRoot(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: SeasonInfoData? = null,
    )

    data class SeasonInfoData(
        @JsonProperty("season") val season: SeasonDto? = null,
    )

    data class SeasonDto(
        @JsonProperty("season_id") val seasonId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("index_show") val indexShow: String? = null,
        @JsonProperty("player_date") val playerDate: String? = null,
        @JsonProperty("is_finished") val isFinished: Boolean? = null,
        @JsonProperty("vertical_cover") val verticalCover: String? = null,
        @JsonProperty("horizontal_cover") val horizontalCover: String? = null,
        @JsonProperty("styles") val styles: List<StyleDto>? = null,
    )

    data class StyleDto(
        @JsonProperty("title") val title: String? = null,
    )

    data class EpisodesRoot(
        @JsonProperty("code") val code: Int? = null,
        @JsonProperty("data") val data: EpisodesData? = null,
    )

    data class EpisodesData(
        @JsonProperty("sections") val sections: List<SectionDto>? = null,
    )

    data class SectionDto(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeDto>? = null,
    )

    data class EpisodeDto(
        @JsonProperty("episode_id") val episodeId: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("short_title_display") val shortTitleDisplay: String? = null,
        @JsonProperty("long_title_display") val longTitleDisplay: String? = null,
        @JsonProperty("title_display") val titleDisplay: String? = null,
    )

    data class LegacySeasonRoot(
        @JsonProperty("result") val result: LegacySeasonData? = null,
    )

    data class LegacySeasonData(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("evaluate") val evaluate: String? = null,
        @JsonProperty("episodes") val episodes: List<LegacyEpisodeDto>? = null,
        @JsonProperty("modules") val modules: List<LegacySeasonModule>? = null,
    )

    data class LegacySeasonModule(
        @JsonProperty("data") val data: LegacySeasonModuleData? = null,
    )

    data class LegacySeasonModuleData(
        @JsonProperty("episodes") val episodes: List<LegacyEpisodeDto>? = null,
    )

    data class LegacyEpisodeDto(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("index_show") val index: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class LegacyEpisodeRoot(
        @JsonProperty("data") val data: LegacyEpisodeData? = null,
    )

    data class LegacyEpisodeData(
        @JsonProperty("subtitles") val subtitles: List<LegacySubtitleDto>? = null,
    )

    data class LegacySubtitleDto(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("url") val url: String? = null,
    )
}
