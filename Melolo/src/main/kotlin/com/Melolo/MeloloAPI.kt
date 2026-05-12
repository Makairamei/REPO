package com.Melolo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONObject
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * ⚙️ ARCHITECTURE LAYER: API ENGINE (JSON)
 * 
 * Lapisan ini bertanggung jawab menangani provider yang menggunakan API/JSON.
 * LOGIC ALIGNMENT: Commit 5826152 (Ground Truth).
 */

object MeloloAPI {

    suspend fun search(provider: Melolo, query: String): List<SearchResponse> {
        return when (provider.providerId) {
            "Dramabox" -> searchDramabox(provider, query)
            "Melolo" -> searchMelolo(provider, query)
            "Idlix" -> searchIdlix(provider, query)
            else -> emptyList()
        }
    }

    suspend fun getMainPage(provider: Melolo, page: Int, request: MainPageRequest): HomePageResponse {
        return when (provider.providerId) {
            "Dramabox" -> getMainPageDramabox(provider, page, request)
            "Melolo" -> getMainPageMelolo(provider, page, request)
            "Idlix" -> getMainPageIdlix(provider, page, request)
            else -> provider.pubNewHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
    }

    suspend fun load(provider: Melolo, url: String): LoadResponse {
        return when (provider.providerId) {
            "Dramabox" -> loadDramabox(provider, url)
            "Melolo" -> loadMelolo(provider, url)
            "Idlix" -> loadIdlix(provider, url)
            else -> throw Exception("API Provider not supported")
        }
    }

    suspend fun loadLinks(
        provider: Melolo,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when (provider.providerId) {
            "Dramabox" -> loadLinksDramabox(provider, data, callback)
            "Melolo" -> loadLinksMelolo(provider, data, callback)
            "Idlix" -> loadLinksIdlix(provider, data, subtitleCallback, callback)
            else -> false
        }
    }

    // ============================================
    // REGION: DRAMABOX STRATEGY (V2.2.0 ALIGNED)
    // ============================================

    private suspend fun getMainPageDramabox(provider: Melolo, page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = "https://db.hafizhibnusyam.my.id"
        val p = if (page < 1) 1 else page
        val url = "${if (request.data.startsWith("http")) request.data else "$apiUrl${request.data}"}${if (request.data.contains("?")) "&" else "?"}page=$p"
        val res = app.get(url).text
        val response = tryParseJson<DramaListResponse>(res)
        val items = response?.data.orEmpty().mapNotNull { item ->
            val dramaId = item.id?.trim() ?: return@mapNotNull null
            provider.pubNewTvSeriesSearchResponse(item.title?.replace(Regex("\\((Sulih Suara|Dub Indo|Indonesian Sub|Sub Indo)\\)", RegexOption.IGNORE_CASE), "")?.trim() ?: "", "${provider.mainUrl}/drama/_$dramaId", TvType.AsianDrama) {
                this.posterUrl = item.coverImage
            }
        }.distinctBy { it.url }
        return provider.pubNewHomePageResponse(HomePageList(request.name, items, false), response?.meta?.pagination?.hasMore ?: items.isNotEmpty())
    }

    private suspend fun searchDramabox(provider: Melolo, query: String): List<SearchResponse> {
        val apiUrl = "https://db.hafizhibnusyam.my.id"
        val url = "$apiUrl/api/search?keyword=${URLEncoder.encode(query, "UTF-8")}&page=1&size=50"
        val res = app.get(url).text
        val response = tryParseJson<DramaListResponse>(res)
        return response?.data.orEmpty().mapNotNull { item ->
            val dramaId = item.id?.trim() ?: return@mapNotNull null
            provider.pubNewTvSeriesSearchResponse(item.title?.replace(Regex("\\((Sulih Suara|Dub Indo|Indonesian Sub|Sub Indo)\\)", RegexOption.IGNORE_CASE), "")?.trim() ?: "", "${provider.mainUrl}/drama/_$dramaId", TvType.AsianDrama) {
                this.posterUrl = item.coverImage
            }
        }.distinctBy { it.url }
    }

    private suspend fun loadDramabox(provider: Melolo, url: String): LoadResponse {
        val dramaId = url.substringAfterLast("_").substringBefore("?").trim()
        val apiUrl = "https://db.hafizhibnusyam.my.id"
        val res = app.get("$apiUrl/api/dramas/$dramaId").text
        val drama = tryParseJson<DramaDetailResponse>(res)?.data ?: throw Exception("Drama tidak ditemukan")
        
        var episodeCount = drama.episodeCount ?: 0
        if (episodeCount <= 0) {
            val checkUrl = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=1"
            val checkRes = tryParseJson<DramaChapterResponse>(app.post(checkUrl).text)
            episodeCount = (checkRes?.data.orEmpty() + checkRes?.extras.orEmpty()).mapNotNull { it.chapterIndex?.toIntOrNull() }.maxOrNull() ?: 0
        }

        val episodes = (1..episodeCount).map { ep ->
            provider.pubNewEpisode(DramaboxLoadData(dramaId, ep).toJson()) {
                this.name = "Episode $ep"
                this.episode = ep
                this.posterUrl = drama.coverImage
            }
        }
        return provider.pubNewTvSeriesLoadResponse(drama.title?.replace(Regex("\\((Sulih Suara|Dub Indo|Indonesian Sub|Sub Indo)\\)", RegexOption.IGNORE_CASE), "")?.trim() ?: "DramaBox", url, TvType.AsianDrama, episodes) {
            this.posterUrl = drama.coverImage
            this.plot = drama.introduction
            this.tags = drama.tags ?: emptyList<String>()
        }
    }

    private suspend fun loadLinksDramabox(provider: Melolo, data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val parsed = parseJson<DramaboxLoadData>(data)
        val apiUrl = "https://db.hafizhibnusyam.my.id"
        val url = "$apiUrl/api/chapters/video?book_id=${parsed.bookId}&episode=${parsed.episodeNo}"
        val res = app.post(url).text
        val response = tryParseJson<DramaChapterResponse>(res)
        val chapter = (response?.data.orEmpty() + response?.extras.orEmpty()).firstOrNull { it.chapterIndex?.toIntOrNull() == parsed.episodeNo }
        val streams = chapter?.streamUrl.orEmpty().filter { it.url?.isNotBlank() == true }.distinctBy { it.url }.sortedByDescending { it.quality ?: 0 }
        
        streams.forEach { s ->
            val sUrl = s.url!!
            val isM3u8 = sUrl.contains(".m3u8") || sUrl.contains("master")
            callback(newExtractorLink(provider.name, "DramaBox ${s.quality?.let { "${it}p" } ?: "Auto"}", sUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = s.quality ?: Qualities.Unknown.value
                this.referer = "${provider.mainUrl}/"
            })
        }
        return streams.isNotEmpty()
    }

    // ============================================
    // REGION: MELOLO STRATEGY (V2.2.0 ALIGNED)
    // ============================================

    private suspend fun getMainPageMelolo(provider: Melolo, page: Int, request: MainPageRequest): HomePageResponse {
        val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"
        val isSearchCategory = request.data.startsWith("q:", true)
        if (page > 1 && !isSearchCategory) return provider.pubNewHomePageResponse(HomePageList(request.name, emptyList()), false)

        val books: List<MeloloBook>
        val hasNext: Boolean

        if (isSearchCategory) {
            val query = request.data.removePrefix("q:").trim()
            val offset = (page.coerceAtLeast(1) - 1) * 20
            val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=20&offset=$offset"
            val resp = tryParseJson<MeloloSearchResponse>(app.get(url).text)
            books = resp?.data?.search_data?.flatMap { it.books }.orEmpty().filter { it.language.equals("id", true) }
            hasNext = resp?.data?.has_more == true
        } else {
            val endpoint = if (request.data == "trending") "trending" else "latest"
            val res = app.get("$catalogBase/$endpoint").text
            books = if (request.data == "trending") {
                tryParseJson<MeloloTrendingResponse>(res)?.books.orEmpty().filter { it.language.equals("id", true) }
            } else {
                tryParseJson<MeloloLatestResponse>(res)?.books.orEmpty().filter { it.language.equals("id", true) }
            }
            hasNext = false
        }

        val items = books.mapNotNull { b ->
            provider.pubNewTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "${provider.mainUrl}/series/${b.book_id ?: return@mapNotNull null}", TvType.TvSeries) {
                this.posterUrl = b.thumb_url
            }
        }
        return provider.pubNewHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    private suspend fun searchMelolo(provider: Melolo, query: String): List<SearchResponse> {
        val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"
        val url = "$catalogBase/search?query=${URLEncoder.encode(query, "UTF-8")}&limit=20&offset=0"
        val res = app.get(url).text
        return tryParseJson<MeloloSearchResponse>(res)?.data?.search_data?.flatMap { it.books }.orEmpty().filter { it.language.equals("id", true) }.mapNotNull { b ->
            provider.pubNewTvSeriesSearchResponse(b.book_name ?: return@mapNotNull null, "${provider.mainUrl}/series/${b.book_id ?: return@mapNotNull null}", TvType.TvSeries) {
                this.posterUrl = b.thumb_url
            }
        }
    }

    private suspend fun loadMelolo(provider: Melolo, url: String): LoadResponse {
        val bookId = url.substringAfterLast("/").substringBefore("?").trim()
        val catalogBase = "https://melolo-api-azure.vercel.app/api/melolo"
        val res = app.get("$catalogBase/detail/$bookId").text
        val detail = tryParseJson<MeloloDetailResponse>(res)?.data?.video_data ?: throw Exception("Melolo detail empty")
        
        val episodes = detail.video_list.filter { it.disable_play != true }.mapNotNull { ep ->
            provider.pubNewEpisode(MeloloEpisodeData(bookId, detail.series_id_str ?: bookId, ep.vid ?: return@mapNotNull null, ep.vid_index ?: return@mapNotNull null, detail.video_platform ?: 3).toJson()) {
                this.name = "Episode ${ep.vid_index}"
                this.posterUrl = ep.cover
                this.episode = ep.vid_index
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return provider.pubNewTvSeriesLoadResponse(detail.series_title ?: "Melolo", "${provider.mainUrl}/series/$bookId", TvType.TvSeries, episodes) {
            this.plot = detail.series_intro
            this.posterUrl = detail.series_cover
        }
    }

    private suspend fun loadLinksMelolo(provider: Melolo, data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val ep = tryParseJson<MeloloEpisodeData>(data) ?: return false
        val aid = "645713"
        val body = """{"video_id":"${ep.vid}","biz_param":{"video_id_type":0,"device_level":1,"video_platform":${ep.videoPlatform}},"NovelCommonParam":{"app_language":"id","sys_language":"id","user_language":"id","ui_language":"id","language":"id","region":"ID","current_region":"ID","app_region":"ID","sys_region":"ID","carrier_region":"ID","carrier_region_v2":"ID","fake_priority_region":"ID","time_zone":"Asia/Jakarta","mcc_mnc":"51011"}}"""
        val res = app.post("${provider.mainUrl}/novel/player/video_model/v1/?aid=$aid", 
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf("Content-Type" to "application/json", "X-Xs-From-Web" to "false", "User-Agent" to "okhttp/4.9.3", "Referer" to "${provider.mainUrl}/")
        ).text
        val resp = tryParseJson<MeloloPlayerResponse>(res)
        listOfNotNull(resp?.data?.main_url, resp?.data?.backup_url).distinct().forEach { url ->
            val isM3u8 = url.contains(".m3u8")
            callback(newExtractorLink(provider.name, "Melolo", url, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = "https://www.melolo.com/"
                this.headers = mapOf("User-Agent" to "okhttp/4.9.3")
            })
        }
        return resp?.data?.main_url != null || resp?.data?.backup_url != null
    }

    // ============================================
    // REGION: IDLIX STRATEGY (V2.2.0 ALIGNED)
    // ============================================

    private suspend fun getMainPageIdlix(provider: Melolo, page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 10000L).parsedSafe<IdlixApiResponse>() ?: return provider.pubNewHomePageResponse(request.name, emptyList<SearchResponse>())
        val home = res.data.mapNotNull { item ->
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            val scoreVal = Score.from10(item.voteAverage?.toString()?.toDoubleOrNull())
            if (item.contentType == "movie") {
                provider.pubNewMovieSearchResponse(item.title ?: return@mapNotNull null, "${provider.mainUrl}/api/movies/${item.slug}", TvType.Movie) { this.posterUrl = poster; this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull(); this.quality = getQualityFromString(item.quality); this.score = scoreVal }
            } else {
                provider.pubNewTvSeriesSearchResponse(item.title ?: return@mapNotNull null, "${provider.mainUrl}/api/series/${item.slug}", TvType.TvSeries) { this.posterUrl = poster; this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull(); this.score = scoreVal; this.quality = getQualityFromString(item.quality) }
            }
        }
        return provider.pubNewHomePageResponse(request.name, home)
    }

    private suspend fun searchIdlix(provider: Melolo, query: String): List<SearchResponse> {
        val encodedQuery = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val res = app.get("${provider.mainUrl}/api/search?q=$encodedQuery&page=1&limit=20").parsedSafe<IdlixSearchResponse>() ?: return emptyList()
        return res.results.mapNotNull { item ->
            val poster = "https://image.tmdb.org/t/p/w342${item.posterPath}"
            val link = if (item.contentType == "movie") "${provider.mainUrl}/api/movies/${item.slug}" else "${provider.mainUrl}/api/series/${item.slug}"
            val scoreVal = Score.from10(item.voteAverage)
            if (item.contentType == "movie") {
                provider.pubNewMovieSearchResponse(item.title, link, TvType.Movie) { this.posterUrl = poster; this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull(); this.quality = getQualityFromString(item.quality); this.score = scoreVal }
            } else {
                provider.pubNewTvSeriesSearchResponse(item.title, link, TvType.TvSeries) { this.posterUrl = poster; this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull(); this.score = scoreVal }
            }
        }
    }

    private suspend fun loadIdlix(provider: Melolo, url: String): LoadResponse {
        val data = app.get(url, timeout = 10000L).parsedSafe<IdlixDetailResponse>() ?: throw Exception("Invalid JSON response")
        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val actors = data.cast?.mapNotNull { it.name?.let { name -> Actor(name, it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" }) } } ?: emptyList()

        if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()
            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(provider.pubNewEpisode(IdlixLoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) { this.name = ep.name; this.season = data.firstSeason.seasonNumber; this.episode = ep.episodeNumber; this.description = ep.overview; this.runTime = ep.runtime; this.score = Score.from10(ep.voteAverage?.toString()?.toDoubleOrNull()); this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" } })
            }
            data.seasons.forEach { season ->
                val sn = season.seasonNumber ?: return@forEach
                if (sn == data.firstSeason?.seasonNumber) return@forEach
                app.get("${provider.mainUrl}/api/series/${data.slug}/season/$sn").parsedSafe<IdlixSeasonWrapper>()?.season?.episodes?.forEach { ep ->
                    episodes.add(provider.pubNewEpisode(IdlixLoadData(id = ep.id ?: return@forEach, type = "episode").toJson()) { this.name = ep.name; this.season = sn; this.episode = ep.episodeNumber; this.description = ep.overview; this.runTime = ep.runtime; this.score = Score.from10(ep.voteAverage?.toString()?.toDoubleOrNull()); this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" } })
                }
            }
            return provider.pubNewTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { 
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString()?.toDoubleOrNull())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        } else {
            return provider.pubNewMovieLoadResponse(title, url, TvType.Movie, IdlixLoadData(id = data.id ?: "", type = "movie").toJson()) { 
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = (data.releaseDate ?: data.firstAirDate)?.substringBefore("-")?.toIntOrNull()
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString()?.toDoubleOrNull())
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

    private suspend fun loadLinksIdlix(provider: Melolo, data: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parsed = parseJson<IdlixLoadData>(data)
        val aclr = app.get("${provider.mainUrl}/pagead/ad_frame.js?_=${System.currentTimeMillis()}").text.let { Regex("""__aclr\s*=\s*"([a-f0-9]+)""").find(it)?.groupValues?.getOrNull(1) }
        val headers = mapOf("accept" to "*/*", "content-type" to "application/json", "origin" to provider.mainUrl, "referer" to provider.mainUrl, "user-agent" to USER_AGENT)
        val challenge = app.post("${provider.mainUrl}/api/watch/challenge", data = mapOf("contentType" to parsed.type, "contentId" to parsed.id, "clearance" to (aclr ?: "")), headers = headers).parsedSafe<IdlixChallengeResponse>() ?: return false
        val solve = app.post("${provider.mainUrl}/api/watch/solve", data = mapOf("challenge" to challenge.challenge, "signature" to challenge.signature, "nonce" to solvePow(challenge.challenge, challenge.difficulty).toString()), headers = headers).parsedSafe<IdlixSolveResponse>() ?: return false
        val embedUrl = solve.embedUrl ?: return false
        // Ground Truth: Add referer for embed page extraction
        val finalUrl = app.get("${provider.mainUrl}$embedUrl", referer = provider.mainUrl).document.selectFirst("iframe")?.attr("src") ?: return false
        return loadExtractorWithFallbackCustom(url = finalUrl, referer = provider.mainUrl, subtitleCallback = subtitleCallback, callback = callback)
    }

    private fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty); var nonce = 0
        while (true) { if (sha256(challenge + nonce).startsWith(target)) return nonce; nonce++ }
    }

    private fun sha256(input: String): String = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }

    // --- DATA CLASSES ---

    data class DramaListResponse(
        @JsonProperty("data") val data: List<DramaItem>? = null, 
        @JsonProperty("meta") val meta: ResponseMeta? = null
    )
    data class DramaDetailResponse(
        @JsonProperty("data") val data: DramaItem? = null
    )
    data class DramaItem(
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("title") val title: String? = null, 
        @JsonProperty("cover_image") val coverImage: String? = null, 
        @JsonProperty("introduction") val introduction: String? = null, 
        @JsonProperty("tags") val tags: List<String>? = null, 
        @JsonProperty("episode_count") val episodeCount: Int? = null
    )
    data class ResponseMeta(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class Pagination(@JsonProperty("has_more") val hasMore: Boolean? = null)
    data class DramaChapterResponse(
        @JsonProperty("data") val data: List<ChapterContent>? = null, 
        @JsonProperty("extras") val extras: List<ChapterContent>? = null
    )
    data class ChapterContent(
        @JsonProperty("chapter_index") val chapterIndex: String? = null, 
        @JsonProperty("stream_url") val streamUrl: List<StreamItem>? = null
    )
    data class StreamItem(
        @JsonProperty("quality") val quality: Int? = null, 
        @JsonProperty("url") val url: String? = null
    )
    data class DramaboxLoadData(
        @JsonProperty("bookId") val bookId: String? = null, 
        @JsonProperty("episodeNo") val episodeNo: Int? = null
    )

    data class MeloloLatestResponse(@JsonProperty("books") val books: List<MeloloBook> = emptyList())
    data class MeloloTrendingResponse(@JsonProperty("books") val books: List<MeloloBook> = emptyList())
    data class MeloloSearchResponse(@JsonProperty("data") val data: MeloloSearchData? = null)
    data class MeloloSearchData(
        @JsonProperty("has_more") val has_more: Boolean? = null, 
        @JsonProperty("search_data") val search_data: List<MeloloSearchBlock> = emptyList()
    )
    data class MeloloSearchBlock(@JsonProperty("books") val books: List<MeloloBook> = emptyList())
    data class MeloloBook(
        @JsonProperty("book_id") val book_id: String? = null, 
        @JsonProperty("book_name") val book_name: String? = null, 
        @JsonProperty("thumb_url") val thumb_url: String? = null, 
        @JsonProperty("language") val language: String? = null
    )
    data class MeloloDetailResponse(@JsonProperty("data") val data: MeloloDetailData? = null)
    data class MeloloDetailData(@JsonProperty("video_data") val video_data: MeloloDetailDataInner? = null)
    data class MeloloDetailDataInner(
        @JsonProperty("series_id_str") val series_id_str: String? = null, 
        @JsonProperty("series_title") val series_title: String? = null, 
        @JsonProperty("series_intro") val series_intro: String? = null, 
        @JsonProperty("series_cover") val series_cover: String? = null, 
        @JsonProperty("video_list") val video_list: List<MeloloEpisode> = emptyList(), 
        @JsonProperty("video_platform") val video_platform: Int? = null
    )
    data class MeloloEpisode(
        @JsonProperty("vid") val vid: String? = null, 
        @JsonProperty("vid_index") val vid_index: Int? = null, 
        @JsonProperty("cover") val cover: String? = null, 
        @JsonProperty("disable_play") val disable_play: Boolean? = null
    )
    data class MeloloPlayerResponse(@JsonProperty("data") val data: MeloloPlayerData? = null)
    data class MeloloPlayerData(
        @JsonProperty("main_url") val main_url: String? = null, 
        @JsonProperty("backup_url") val backup_url: String? = null
    )
    data class MeloloEpisodeData(
        @JsonProperty("bookId") val bookId: String, 
        @JsonProperty("seriesId") val seriesId: String, 
        @JsonProperty("vid") val vid: String, 
        @JsonProperty("episode") val episode: Int, 
        @JsonProperty("videoPlatform") val videoPlatform: Int = 3
    )

    data class IdlixApiResponse(val data: List<IdlixApiItem> = emptyList())
    data class IdlixApiItem(
        val id: String? = null, 
        val title: String? = null, 
        val slug: String? = null, 
        val posterPath: String? = null, 
        val releaseDate: String? = null, 
        val voteAverage: String? = null, 
        val quality: String? = null, 
        val contentType: String? = null
    )
    data class IdlixDetailResponse(
        val id: String? = null, 
        val title: String? = null, 
        val slug: String? = null, 
        val imdbId: String? = null, 
        val tmdbId: String? = null, 
        val overview: String? = null, 
        val posterPath: String? = null, 
        val backdropPath: String? = null, 
        val logoPath: String? = null, 
        val releaseDate: String? = null, 
        val firstAirDate: String? = null, 
        val voteAverage: Any? = null, 
        val quality: String? = null, 
        val trailerUrl: String? = null, 
        val genres: List<IdlixGenre>? = null, 
        val cast: List<IdlixCast>? = null, 
        val seasons: List<IdlixSeason>? = null, 
        val firstSeason: IdlixSeason? = null
    )
    data class IdlixGenre(val id: String? = null, val name: String? = null)
    data class IdlixCast(val id: String? = null, val name: String? = null, val profilePath: String? = null)
    data class IdlixSeason(
        val id: String? = null, 
        val seasonNumber: Int? = null, 
        val name: String? = null, 
        val episodes: List<IdlixEpisode>? = null
    )
    data class IdlixEpisode(
        val id: String? = null, 
        val episodeNumber: Int? = null, 
        val name: String? = null, 
        val overview: String? = null, 
        val stillPath: String? = null, 
        val airDate: String? = null, 
        val runtime: Int? = null, 
        val voteAverage: Any? = null
    )
    data class IdlixSearchResponse(val results: List<IdlixSearchResult> = emptyList())
    data class IdlixSearchResult(
        val id: String = "", 
        val contentType: String = "", 
        val title: String = "", 
        val posterPath: String = "", 
        val slug: String = "", 
        val releaseDate: String? = null, 
        val firstAirDate: String? = null, 
        val voteAverage: Double = 0.0, 
        val quality: String? = null
    )
    data class IdlixChallengeResponse(val challenge: String = "", val signature: String = "", val difficulty: Int = 0)
    data class IdlixSolveResponse(val embedUrl: String? = null)
    data class IdlixLoadData(val id: String, val type: String)
    data class IdlixSeasonWrapper(val season: IdlixSeason? = null)
}
