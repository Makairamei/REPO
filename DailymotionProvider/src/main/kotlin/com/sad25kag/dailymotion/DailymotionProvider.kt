package com.sad25kag.dailymotion

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import java.util.Locale

class DailymotionProvider : MainAPI() {

    override var mainUrl = "https://www.dailymotion.com"
    override var name = "Dailymotion"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Others
    )

    private val apiBase = "https://api.dailymotion.com"
    private val videoFields = listOf(
        "id",
        "title",
        "description",
        "thumbnail_360_url",
        "thumbnail_720_url",
        "duration",
        "channel",
        "created_time",
        "url",
        "embed_url",
        "language",
        "country",
        "mode",
        "views_total"
    ).joinToString(",")

    override val mainPage = mainPageOf(
        row("sort=trending", "Trending Hari Ini", TvType.Others),
        row("sort=recent", "Upload Terbaru", TvType.Others),
        row("sort=visited-week", "Populer Minggu Ini", TvType.Others),
        row("channel=tv&sort=recent", "TV Publik Terbaru", TvType.Others),

        row(searchQuery("anime sub indo", "relevance", longerThan = 8), "Anime Sub Indo", TvType.Anime),
        row(searchQuery("anime episode subtitle indonesia", "relevance", longerThan = 8), "Anime Episode Indonesia", TvType.Anime),
        row(searchQuery("anime movie full movie", "relevance", longerThan = 20), "Anime Movie", TvType.AnimeMovie),
        row(searchQuery("donghua sub indo", "relevance", longerThan = 8), "Donghua Sub Indo", TvType.Anime),
        row(searchQuery("cartoon full episode", "relevance", longerThan = 8), "Cartoon Full Episode", TvType.Cartoon),

        row(searchQuery("drama korea sub indo", "relevance", longerThan = 20), "Drama Korea", TvType.AsianDrama),
        row(searchQuery("drama china sub indo", "relevance", longerThan = 20), "Drama China", TvType.AsianDrama),
        row(searchQuery("drama thailand sub indo", "relevance", longerThan = 20), "Drama Thailand", TvType.AsianDrama),
        row(searchQuery("drama jepang sub indo", "relevance", longerThan = 20), "Drama Jepang", TvType.AsianDrama),
        row(searchQuery("drama filipina full episode", "relevance", longerThan = 20), "Drama Filipina", TvType.AsianDrama),
        row(searchQuery("drama malaysia full episode", "relevance", longerThan = 20), "Drama Malaysia", TvType.AsianDrama),
        row(searchQuery("sinetron indonesia full episode", "relevance", longerThan = 20), "Drama Indonesia", TvType.TvSeries),

        row(searchQuery("full movie indonesia", "relevance", longerThan = 45), "Movie Indonesia", TvType.Movie),
        row(searchQuery("full movie korea", "relevance", longerThan = 45), "Movie Korea", TvType.Movie),
        row(searchQuery("full movie china", "relevance", longerThan = 45), "Movie China", TvType.Movie),
        row(searchQuery("full movie japan", "relevance", longerThan = 45), "Movie Jepang", TvType.Movie),
        row(searchQuery("full movie thailand", "relevance", longerThan = 45), "Movie Thailand", TvType.Movie),
        row(searchQuery("full movie india hindi", "relevance", longerThan = 45), "Movie India", TvType.Movie),
        row(searchQuery("full movie philippines", "relevance", longerThan = 45), "Movie Filipina", TvType.Movie),
        row(searchQuery("full movie malaysia", "relevance", longerThan = 45), "Movie Malaysia", TvType.Movie),
        row(searchQuery("full movie english", "relevance", longerThan = 45), "Movie Barat", TvType.Movie),

        row(searchQuery("WWE full show", "relevance", longerThan = 20), "WWE Full Show", TvType.Others),
        row(searchQuery("WWE Raw full show", "relevance", longerThan = 20), "WWE RAW", TvType.Others),
        row(searchQuery("WWE SmackDown full show", "relevance", longerThan = 20), "WWE SmackDown", TvType.Others),
        row(searchQuery("WWE NXT full show", "relevance", longerThan = 20), "WWE NXT", TvType.Others),
        row(searchQuery("WWE highlights", "relevance", longerThan = 3), "WWE Highlights", TvType.Others),
        row(searchQuery("AEW Dynamite full show", "relevance", longerThan = 20), "AEW Dynamite", TvType.Others),
        row(searchQuery("UFC full fight", "relevance", longerThan = 5), "UFC Full Fight", TvType.Others),

        row(searchQuery("documentary indonesia", "relevance", longerThan = 15), "Dokumenter Indonesia", TvType.Others),
        row(searchQuery("komedi indonesia", "relevance", longerThan = 8), "Komedi Indonesia", TvType.Others),
        row(searchQuery("travel indonesia", "relevance", longerThan = 5), "Travel Indonesia", TvType.Others),
        row("channel=news&sort=recent", "Berita", TvType.Others),
        row("channel=sport&sort=recent", "Olahraga", TvType.Others),
        row("channel=music&sort=recent", "Musik", TvType.Others),
        row("channel=fun&sort=recent", "Hiburan & Lucu", TvType.Others),
        row("channel=videogames&sort=recent", "Gaming", TvType.Others),
        row("channel=tech&sort=recent", "Teknologi", TvType.Others)
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = parseCategoryData(request.data)
        val videos = fetchVideoPage(category.apiQuery, page, 36)
        val rawList = videos?.list.orEmpty()
            .takeIf { it.isNotEmpty() }
            ?: fetchFallbackRows(category.apiQuery, page)

        val home = rawList
            .mapNotNull { it.toSearchResponse(category.type) }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = videos?.hasMore == true && home.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        val apiUrl = buildVideoListUrl(
            query = searchQuery(cleanQuery, sort = "relevance"),
            page = 1,
            limit = 40
        )

        return app.get(apiUrl, headers = apiHeaders)
            .text
            .let { parseDailymotionJson<VideoSearchResponse>(it) }
            ?.list
            .orEmpty()
            .mapNotNull { it.toSearchResponse(inferTypeFromTitle(it.title.orEmpty())) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = url.extractDailymotionId()
            ?: throw ErrorLoadingException("ID video Dailymotion tidak ditemukan")

        val forcedType = extractForcedType(url)
        val apiUrl = "$apiBase/video/$videoId?fields=$videoFields"
        val detail = app.get(apiUrl, headers = apiHeaders)
            .text
            .let { parseDailymotionJson<VideoDetailResponse>(it) }
            ?: throw ErrorLoadingException("Detail video Dailymotion tidak ditemukan")

        return detail.toLoadResponse(forcedType)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val urls = data.split(LINK_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        var found = false
        for (url in urls) {
            found = runCatching {
                loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
            }.getOrDefault(false) || found
        }

        return found
    }

    private fun row(query: String, title: String, type: TvType): Pair<String, String> {
        return "dmtype=${type.name}&$query" to title
    }

    private fun parseCategoryData(data: String): CategoryData {
        val params = data.split("&")
            .mapNotNull { item ->
                val key = item.substringBefore("=", "").trim()
                val value = item.substringAfter("=", "").trim()
                if (key.isBlank()) null else key to value
            }
            .toMap()

        val type = params["dmtype"]?.toTvType() ?: TvType.Others
        val apiQuery = data.split("&")
            .filterNot { it.startsWith("dmtype=", ignoreCase = true) }
            .joinToString("&")
            .trim('&')

        return CategoryData(
            apiQuery = apiQuery.ifBlank { "sort=recent" },
            type = type
        )
    }

    private fun searchQuery(
        query: String,
        sort: String = "relevance",
        longerThan: Int? = null
    ): String {
        val parts = mutableListOf(
            "search=${query.urlEncoded()}",
            "sort=$sort"
        )

        // Jangan pakai filter availability/no_live di main row.
        // Di beberapa query Dailymotion, filter itu membuat respons kosong sehingga Cloudstream
        // cuma menampilkan judul kategori sebagai tulisan tanpa kartu video.
        if (longerThan != null && longerThan > 0) {
            parts += "longer_than=$longerThan"
        }

        return parts.joinToString("&")
    }

    private fun buildVideoListUrl(query: String, page: Int, limit: Int): String {
        val safeQuery = query.trim().trimStart('&')
        val joiner = if (safeQuery.isBlank()) "" else "&$safeQuery"
        return "$apiBase/videos?fields=$videoFields&limit=$limit&page=$page$joiner"
    }

    private suspend fun fetchVideoPage(query: String, page: Int, limit: Int): VideoSearchResponse? {
        val apiUrl = buildVideoListUrl(
            query = query,
            page = page,
            limit = limit
        )

        return app.get(apiUrl, headers = apiHeaders)
            .text
            .let { parseDailymotionJson<VideoSearchResponse>(it) }
    }

    private suspend fun fetchFallbackRows(query: String, page: Int): List<VideoItem> {
        val relaxed = buildRelaxedQueries(query)
        for (candidate in relaxed) {
            val result = fetchVideoPage(candidate, page, 36)
                ?.list
                .orEmpty()
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun buildRelaxedQueries(query: String): List<String> {
        val params = query.split("&")
            .mapNotNull { item ->
                val key = item.substringBefore("=", "").trim()
                val value = item.substringAfter("=", "").trim()
                if (key.isBlank()) null else key to value
            }
            .toMap()

        val search = params["search"]?.takeIf { it.isNotBlank() } ?: return emptyList()
        val decoded = runCatching {
            java.net.URLDecoder.decode(search, "UTF-8")
        }.getOrDefault(search.replace("+", " "))
            .replace("+", " ")
            .trim()

        val words = decoded
            .replace("sub indo", "", ignoreCase = true)
            .replace("subtitle indonesia", "", ignoreCase = true)
            .replace("full movie", "movie", ignoreCase = true)
            .replace("full episode", "episode", ignoreCase = true)
            .replace("full show", "show", ignoreCase = true)
            .replace("highlights", "highlight", ignoreCase = true)
            .replace("\\s+".toRegex(), " ")
            .trim()

        return listOf(
            "search=${decoded.urlEncoded()}&sort=recent",
            "search=${decoded.urlEncoded()}&sort=visited-week",
            "search=${words.urlEncoded()}&sort=relevance",
            "search=${words.urlEncoded()}&sort=recent"
        ).distinct()
    }


    private fun VideoItem.toSearchResponse(type: TvType): SearchResponse? {
        val videoId = id?.takeIf { it.isNotBlank() } ?: return null
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: "Dailymotion Video"
        val resolvedType = if (type == TvType.Others) inferTypeFromTitle(cleanTitle) else type
        val watchUrl = "$mainUrl/video/$videoId?dmtype=${resolvedType.name}"
        val poster = thumbnail720Url ?: thumbnail360Url
        val year = extractYear(cleanTitle)

        return when (resolvedType) {
            TvType.TvSeries, TvType.AsianDrama -> newTvSeriesSearchResponse(cleanTitle, watchUrl, resolvedType) {
                posterUrl = poster
                this.year = year
            }
            TvType.Anime -> newAnimeSearchResponse(cleanTitle, watchUrl, TvType.Anime) {
                posterUrl = poster
                this.year = year
            }
            TvType.AnimeMovie -> newAnimeSearchResponse(cleanTitle, watchUrl, TvType.AnimeMovie) {
                posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(cleanTitle, watchUrl, resolvedType) {
                posterUrl = poster
                this.year = year
            }
        }
    }

    private suspend fun VideoDetailResponse.toLoadResponse(forcedType: TvType?): LoadResponse {
        val videoId = id?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("ID video Dailymotion kosong")
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: "Dailymotion Video"
        val resolvedType = forcedType ?: inferTypeFromTitle(cleanTitle)
        val watchUrl = url?.takeIf { it.isNotBlank() } ?: "$mainUrl/video/$videoId"
        val embedUrl = embedUrl?.takeIf { it.isNotBlank() }
        val data = listOfNotNull(watchUrl, embedUrl).distinct().joinToString(LINK_SEPARATOR)
        val tagList = listOfNotNull(
            channel?.trim()?.takeIf { it.isNotBlank() },
            country?.trim()?.takeIf { it.isNotBlank() },
            language?.trim()?.takeIf { it.isNotBlank() },
            mode?.trim()?.takeIf { it.isNotBlank() }
        )

        return newMovieLoadResponse(cleanTitle, watchUrl, resolvedType, data) {
            posterUrl = thumbnail720Url ?: thumbnail360Url
            plot = description?.trim()?.takeIf { it.isNotBlank() }
            duration = duration?.let { seconds -> seconds / 60 }
            tags = tagList
        }
    }

    private fun extractForcedType(url: String): TvType? {
        return url.substringAfter("dmtype=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() }
            ?.toTvType()
    }

    private fun String.toTvType(): TvType? {
        return when (trim().lowercase(Locale.ROOT)) {
            "movie" -> TvType.Movie
            "tvseries" -> TvType.TvSeries
            "asiandrama" -> TvType.AsianDrama
            "anime" -> TvType.Anime
            "animemovie" -> TvType.AnimeMovie
            "cartoon" -> TvType.Cartoon
            "others" -> TvType.Others
            else -> null
        }
    }

    private fun inferTypeFromTitle(title: String): TvType {
        val lower = title.lowercase(Locale.ROOT)
        return when {
            lower.contains("anime") || lower.contains("sub indo") && lower.contains("episode") -> TvType.Anime
            lower.contains("donghua") -> TvType.Anime
            lower.contains("cartoon") || lower.contains("animation") -> TvType.Cartoon
            lower.contains("drama korea") || lower.contains("drakor") -> TvType.AsianDrama
            lower.contains("drama china") || lower.contains("cdrama") -> TvType.AsianDrama
            lower.contains("drama thailand") || lower.contains("thai drama") -> TvType.AsianDrama
            lower.contains("full movie") || lower.contains("film ") || lower.contains("movie") -> TvType.Movie
            lower.contains("raw") || lower.contains("smackdown") || lower.contains("wwe") || lower.contains("aew") -> TvType.Others
            else -> TvType.Others
        }
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.extractDailymotionId(): String? {
        val clean = substringBefore("?").trim()
        return Regex("""(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)([A-Za-z0-9]+)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?: clean.substringAfter("/video/", "")
                .substringBefore("?")
                .substringBefore("_")
                .takeIf { it.isNotBlank() && !it.contains("/") }
            ?: clean.takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }
    }

    private fun String.urlEncoded(): String = try {
        URLEncoder.encode(this, "UTF-8")
    } catch (_: Throwable) {
        this
    }

    private val apiHeaders = mapOf(
        "Accept" to "application/json,text/plain,*/*",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )


    private inline fun <reified T> parseDailymotionJson(text: String): T? {
        return runCatching { dailymotionJsonMapper.readValue<T>(text) }.getOrNull()
    }

    private data class CategoryData(
        val apiQuery: String,
        val type: TvType
    )

    data class VideoSearchResponse(
        @param:JsonProperty("list") val list: List<VideoItem>? = emptyList(),
        @param:JsonProperty("has_more") val hasMore: Boolean? = null,
        @param:JsonProperty("page") val page: Int? = null,
        @param:JsonProperty("limit") val limit: Int? = null,
        @param:JsonProperty("total") val total: Int? = null
    )

    data class VideoItem(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null,
        @param:JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null,
        @param:JsonProperty("duration") val duration: Int? = null,
        @param:JsonProperty("channel") val channel: String? = null,
        @param:JsonProperty("created_time") val createdTime: Long? = null,
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("embed_url") val embedUrl: String? = null,
        @param:JsonProperty("language") val language: String? = null,
        @param:JsonProperty("country") val country: String? = null,
        @param:JsonProperty("mode") val mode: String? = null,
        @param:JsonProperty("views_total") val viewsTotal: Long? = null
    )

    data class VideoDetailResponse(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null,
        @param:JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null,
        @param:JsonProperty("duration") val duration: Int? = null,
        @param:JsonProperty("channel") val channel: String? = null,
        @param:JsonProperty("created_time") val createdTime: Long? = null,
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("embed_url") val embedUrl: String? = null,
        @param:JsonProperty("language") val language: String? = null,
        @param:JsonProperty("country") val country: String? = null,
        @param:JsonProperty("mode") val mode: String? = null,
        @param:JsonProperty("views_total") val viewsTotal: Long? = null
    )

    companion object {
        private const val LINK_SEPARATOR = "|||DM|||"
        private val dailymotionJsonMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
