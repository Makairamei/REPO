package com.reelshort

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class Reelshort : MainAPI() {
    override var mainUrl = "https://streamapi.web.id/p/reelshort"
    override var name = "ReelShort"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        Route.FOR_YOU.key to "Untuk Kamu",
        Route.NEW.key to "Rilis Baru",
        Route.COMPLETED.key to "Tamat",
        Route.ROMANCE.key to "Romance",
        Route.DRAMA.key to "Drama",
    )

    private enum class Route(val key: String, val streamPath: String, val legacyPath: String?) {
        FOR_YOU("foryou", "/api/v1/foryou?lang=in", "/api/v1/reelshort/recommend"),
        NEW("new", "/api/v1/new?lang=in", "/api/v1/reelshort/newrelease"),
        COMPLETED("completed", "/api/v1/completed?lang=in", null),
        ROMANCE("romance", "/api/v1/romance?lang=in", null),
        DRAMA("drama", "/api/v1/drama?lang=in", "/api/v1/reelshort/dramadub"),
    }

    private val legacyBase = "https://reelshort.dramaview.web.id"

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
    )

    private val epNumberRegex = Regex("""(?:episode|eps?|ep|chapter|bab)\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val episodeLabelRegex = Regex("""(?i)\b(episode|eps?|ep|chapter|bab)\s*\d+|\bs\d+\s*e\d+\b""")
    private val numericLabelRegex = Regex("""^\d{1,4}$""")
    private val subtitleCleanRegex = Regex("""(?i)\s+(subtitle\s+indonesia|sub\s*indo|streaming|download).*$""")
    private val spaceRegex = Regex("""\s+""")
    private val urlRegexNormal = Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
    private val urlRegexEncoded = Regex("""https?%3A%2F%2F[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
    private val apiRootRegex = Regex("""https?://[^/]+""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>())

        val route = Route.values().firstOrNull { it.key == request.data } ?: Route.FOR_YOU
        val items = fetchRoute(route)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(36)

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = keyword.encodeUrl()
        val candidates = listOf(
            "$mainUrl/api/v1/search?q=$encoded&page=1&lang=in",
            "$legacyBase/api/v1/reelshort/search?keywords=$encoded",
        )

        return fetchFirstJson(candidates)
            ?.let { parseDramaItems(it) }
            .orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseInternalUrl(url)
        val bookId = data.bookId?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("ID ReelShort tidak ditemukan")

        val detail = fetchBookDetail(bookId) ?: DramaItem(
            id = bookId,
            title = data.title?.takeIf { it.isNotBlank() } ?: "ReelShort",
            filteredTitle = data.filteredTitle,
            poster = null,
            description = null,
            episodeCount = null,
            raw = null,
        )

        val episodes = fetchEpisodes(bookId, detail.filteredTitle ?: data.filteredTitle, detail.raw)
            .ifEmpty { buildFallbackEpisodes(detail) }
            .distinctBy { it.chapterId ?: it.episode }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .mapIndexed { index, episode ->
                val number = episode.episode ?: index + 1
                newEpisode(
                    LoadData(
                        bookId = bookId,
                        filteredTitle = detail.filteredTitle ?: data.filteredTitle,
                        episode = number,
                        chapterId = episode.chapterId,
                    ).toJson()
                ) {
                    this.name = episode.name?.takeIf { it.isNotBlank() } ?: "Episode $number"
                    this.episode = number
                }
            }

        return newTvSeriesLoadResponse(detail.title, buildBookUrl(detail), TvType.AsianDrama, episodes) {
            posterUrl = detail.poster
            plot = detail.description
            this.tags = listOf("Short Drama", "ReelShort")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        val bookId = parsed.bookId?.takeIf { it.isNotBlank() } ?: return false
        val episodeNumber = parsed.episode ?: 1

        val chapterId = parsed.chapterId?.takeIf { it.isNotBlank() }
            ?: fetchEpisodes(bookId, parsed.filteredTitle, null)
                .firstOrNull { it.episode == parsed.episode }
                ?.chapterId
                ?.takeIf { it.isNotBlank() }

        val videoUrls = linkedSetOf<String>()

        if (!chapterId.isNullOrBlank()) {
            fetchFirstJson(
                listOf(
                    "$mainUrl/api/v1/book/${bookId.encodePath()}/chapter/${chapterId.encodePath()}/video",
                    "$mainUrl/api/v1/book/${bookId.encodePath()}/chapter/${chapterId.encodePath()}/video?lang=in",
                )
            )?.let { extractMediaUrls(it).forEach(videoUrls::add) }
        }

        val filteredTitle = parsed.filteredTitle?.takeIf { it.isNotBlank() }
        if (videoUrls.isEmpty() && filteredTitle != null) {
            val legacyUrls = if (!chapterId.isNullOrBlank()) {
                listOf(
                    "$legacyBase/api/v1/reelshort/video/${bookId.encodePath()}/$episodeNumber?filtered_title=${filteredTitle.encodeUrl()}&chapter_id=${chapterId.encodeUrl()}"
                )
            } else {
                listOf(
                    "$legacyBase/api/v1/reelshort/video/${bookId.encodePath()}/$episodeNumber?filtered_title=${filteredTitle.encodeUrl()}"
                )
            }

            fetchFirstJson(legacyUrls)?.let { extractMediaUrls(it).forEach(videoUrls::add) }
        }

        var found = false
        videoUrls
            .filter { it.startsWith("http", true) && !isImageOrAsset(it) }
            .distinct()
            .forEach { url ->
                emitDirectLink(url) { link ->
                    found = true
                    callback(link)
                }
            }

        return found
    }

    private suspend fun fetchRoute(route: Route): List<DramaItem> {
        val urls = mutableListOf("$mainUrl${route.streamPath}")
        route.legacyPath?.let { urls.add("$legacyBase$it") }
        return fetchFirstJson(urls)?.let { parseDramaItems(it) }.orEmpty()
    }

    private suspend fun fetchBookDetail(bookId: String): DramaItem? {
        fetchFirstJson(
            listOf(
                "$mainUrl/api/v1/book/${bookId.encodePath()}?lang=in",
                "$mainUrl/api/v1/book/${bookId.encodePath()}",
            )
        )?.let { response ->
            parseDramaItems(response).firstOrNull { it.id == bookId }?.let { return it }
            firstDramaObject(response)?.toDramaItem()?.let { return it.copy(id = it.id.ifBlank { bookId }) }
        }

        Route.values().forEach { route ->
            fetchRoute(route).firstOrNull { it.id == bookId }?.let { return it }
        }

        return null
    }

    private suspend fun fetchEpisodes(bookId: String, filteredTitle: String?, rawDetail: JSONObject?): List<EpisodeItem> {
        val results = linkedMapOf<String, EpisodeItem>()

        rawDetail?.let { detail ->
            parseEpisodeItems(detail, allowGenericId = false).forEach { episode ->
                results.putIfAbsent(episode.key(), episode)
            }
        }

        fetchFirstJson(
            listOf(
                "$mainUrl/api/v1/book/${bookId.encodePath()}/chapters?lang=in",
                "$mainUrl/api/v1/book/${bookId.encodePath()}/chapters",
            )
        )?.let { response ->
            parseEpisodeItems(response, allowGenericId = true).forEach { episode ->
                results.putIfAbsent(episode.key(), episode)
            }
        }

        filteredTitle?.takeIf { it.isNotBlank() }?.let { filtered ->
            fetchFirstJson(
                listOf("$legacyBase/api/v1/reelshort/episodes/${bookId.encodePath()}?filtered_title=${filtered.encodeUrl()}")
            )?.let { response ->
                parseEpisodeItems(response, allowGenericId = true).forEach { episode ->
                    results.putIfAbsent(episode.key(), episode)
                }
            }
        }

        return results.values.toList()
    }

    private fun buildFallbackEpisodes(detail: DramaItem): List<EpisodeItem> {
        val count = detail.episodeCount?.takeIf { it > 0 } ?: 0
        if (count <= 0) return emptyList()
        return (1..count).map { number ->
            EpisodeItem(
                chapterId = null,
                episode = number,
                name = "Episode $number",
                raw = null,
            )
        }
    }

    private suspend fun fetchFirstJson(urls: List<String>): JSONObject? {
        urls.distinct().forEach { url ->
            runCatching {
                val text = app.get(url, headers = headers).text.trim()
                if (text.isBlank()) return@runCatching
                return parseJsonObject(text)
            }
        }
        return null
    }

    private fun parseJsonObject(text: String): JSONObject? {
        val clean = text.trim()
        return runCatching {
            if (clean.startsWith("[")) {
                JSONObject().put("data", JSONArray(clean))
            } else {
                JSONObject(clean)
            }
        }.getOrNull()
    }

    private fun parseDramaItems(root: JSONObject): List<DramaItem> {
        val items = linkedMapOf<String, DramaItem>()
        walkObjects(root).forEach { obj ->
            obj.toDramaItem()?.let { item ->
                if (item.id.isNotBlank() && item.title.isNotBlank()) {
                    items.putIfAbsent(item.id, item)
                }
            }
        }
        return items.values.toList()
    }

    private fun firstDramaObject(root: JSONObject): JSONObject? {
        return walkObjects(root).firstOrNull { it.toDramaItem() != null }
    }

    private fun JSONObject.looksLikeEpisodeObject(): Boolean {
        val hasExplicitEpisodeSignal = optText(
            "chapter_id", "chapterId", "chapterID",
            "episode_id", "episodeId", "video_id", "videoId",
            "chapter_name", "chapterName",
            "episode", "episode_no", "episodeNo",
            "ep", "chapter", "chapter_no", "chapterNo",
            "seq", "video_url", "videoUrl", "play_url", "playUrl"
        ) != null

        val label = optText("chapter_name", "chapterName", "title", "name")
        val hasEpisodeLabel = label?.let {
            episodeLabelRegex.containsMatchIn(it) || numericLabelRegex.matches(it.trim())
        } == true

        val hasDramaSignal = optText(
            "book_id", "bookId", "bookid",
            "drama_id", "dramaId", "dramaID",
            "series_id", "seriesId",
            "book_title", "bookTitle",
            "drama_name", "dramaName",
            "series_name", "seriesName",
            "book_pic", "bookPic",
            "cover", "cover_url", "coverUrl",
            "poster", "poster_url", "posterUrl",
            "chapter_count", "chapterCount",
            "episode_count", "episodeCount"
        ) != null

        return (hasExplicitEpisodeSignal || hasEpisodeLabel) && !hasDramaSignal
    }

    private fun JSONObject.toDramaItem(): DramaItem? {
        if (looksLikeEpisodeObject()) return null

        val id = optText(
            "book_id", "bookId", "bookid", "id", "drama_id", "dramaId", "dramaID", "series_id", "seriesId"
        ) ?: return null

        val title = optText(
            "book_title", "bookTitle", "title", "name", "drama_name", "dramaName", "series_name", "seriesName"
        )?.cleanTitle().orEmpty()
        if (title.isBlank()) return null

        val poster = optText(
            "book_pic", "bookPic", "cover", "cover_url", "coverUrl", "poster", "poster_url", "posterUrl", "image", "thumb", "thumbnail"
        )

        val desc = optText(
            "special_desc", "specialDesc", "description", "desc", "intro", "synopsis", "summary", "book_desc", "bookDesc"
        )

        val episodeCount = optIntAny(
            "chapter_count", "chapterCount", "episode_count", "episodeCount", "total", "total_chapters", "totalChapters"
        )

        val filtered = optText("filtered_title", "filteredTitle", "slug", "alias", "keyword")

        return DramaItem(
            id = id,
            title = title,
            filteredTitle = filtered,
            poster = poster?.fixUrl(),
            description = desc?.cleanText(),
            episodeCount = episodeCount,
            raw = this,
        )
    }

    private fun parseEpisodeItems(root: JSONObject, allowGenericId: Boolean = false): List<EpisodeItem> {
        val episodes = mutableListOf<EpisodeItem>()
        walkArrays(root).forEach { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                obj.toEpisodeItem(index = i + 1, allowGenericId = allowGenericId)?.let { episodes.add(it) }
            }
        }
        walkObjects(root).forEach { obj ->
            obj.toEpisodeItem(index = episodes.size + 1, allowGenericId = allowGenericId)?.let { episode ->
                if (episodes.none { it.key() == episode.key() }) episodes.add(episode)
            }
        }
        return episodes
            .filter { !it.chapterId.isNullOrBlank() || (it.episode ?: 0) > 0 }
            .distinctBy { it.key() }
    }

    private fun JSONObject.toEpisodeItem(index: Int, allowGenericId: Boolean = false): EpisodeItem? {
        val label = optText("chapter_name", "chapterName", "title", "name")
        val hasExplicitEpisodeSignal = optText(
            "chapter_id", "chapterId", "chapterID", "episode_id", "episodeId", "video_id", "videoId",
            "chapter_name", "chapterName", "episode", "episode_no", "episodeNo", "ep", "chapter",
            "chapter_no", "chapterNo", "seq", "video_url", "videoUrl", "play_url", "playUrl"
        ) != null

        val hasEpisodeLabel = label?.let {
            episodeLabelRegex.containsMatchIn(it) ||
                (allowGenericId && numericLabelRegex.matches(it.trim()))
        } == true

        val hasEpisodeSignal = hasExplicitEpisodeSignal || hasEpisodeLabel
        if (!hasEpisodeSignal) return null

        val id = optText(
            "chapter_id", "chapterId", "chapterID", "episode_id", "episodeId", "video_id", "videoId"
        ) ?: if (allowGenericId && hasEpisodeSignal) optText("id") else null

        val number = optIntAny(
            "episode", "episode_no", "episodeNo", "ep", "chapter", "chapter_no", "chapterNo", "index", "seq"
        ) ?: label?.let { text ->
            epNumberRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: numericLabelRegex.find(text.trim())?.value?.toIntOrNull()
        } ?: index

        val name = label?.cleanText() ?: "Episode $number"

        if (id.isNullOrBlank() && number <= 0) return null
        if (name.equals("ReelShort", true) && id.isNullOrBlank()) return null

        return EpisodeItem(
            chapterId = id,
            episode = number,
            name = name,
            raw = this,
        )
    }

    private fun extractMediaUrls(root: JSONObject): List<String> {
        val urls = linkedSetOf<String>()
        fun scan(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (key.contains("url", true) || key.contains("video", true) || key.contains("play", true) || key.contains("m3u8", true) || key == "src" || key == "file") {
                            asString(child)?.let { urls.addAll(extractUrls(it)) }
                        }
                        scan(child)
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) scan(value.opt(i))
                is String -> urls.addAll(extractUrls(value))
            }
        }
        scan(root)
        return urls.filter { it.startsWith("http", true) && !isImageOrAsset(it) }.distinct()
    }

    private fun extractUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()
        if (clean.startsWith("http", true)) urls.add(clean)

        urlRegexNormal.findAll(clean)
            .map { it.value.trimEnd(',', ';', ')', ']', '}') }
            .forEach(urls::add)

        urlRegexEncoded.findAll(clean)
            .mapNotNull { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrNull() }
            .forEach(urls::add)

        return urls.toList()
    }

    private suspend fun emitDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val lower = url.lowercase()
        val isM3u8 = lower.contains("m3u8")

        callback(
            newExtractorLink(
                source = name,
                name = if (isM3u8) "ReelShort HLS" else "ReelShort Video",
                url = url,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                quality = getQualityFromName(url).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                referer = "$mainUrl/"
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to "https://${java.net.URI(mainUrl).host}"
                )
            }
        )
    }

    private fun DramaItem.toSearchResult(): SearchResponse {
        return newTvSeriesSearchResponse(title, buildBookUrl(this), TvType.AsianDrama) {
            posterUrl = poster
        }
    }

    private fun buildBookUrl(item: DramaItem): String {
        val params = mutableListOf<String>()
        item.filteredTitle?.takeIf { it.isNotBlank() }?.let { params.add("filtered_title=${it.encodeUrl()}") }
        item.title.takeIf { it.isNotBlank() }?.let { params.add("title=${it.encodeUrl()}") }
        val suffix = params.takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "?").orEmpty()
        return "reelshort://book/${item.id.encodePath()}$suffix"
    }

    private fun parseInternalUrl(url: String): LoadData {
        val bookId = url.substringAfter("/book/", "")
            .substringBefore("?")
            .ifBlank { url.substringAfter("reelshort://book/", "").substringBefore("?") }
            .ifBlank { url.substringAfterLast("/").substringBefore("?") }
            .decodeUrl()

        return LoadData(
            bookId = bookId,
            filteredTitle = getQueryParam(url, "filtered_title")?.decodeUrl(),
            title = getQueryParam(url, "title")?.decodeUrl(),
        )
    }

    private fun getQueryParam(url: String, key: String): String? {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return null
        return query.split("&")
            .firstOrNull { it.substringBefore("=") == key }
            ?.substringAfter("=", "")
    }

    private fun walkObjects(root: Any?): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    result.add(value)
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
            }
        }
        visit(root)
        return result
    }

    private fun walkArrays(root: Any?): List<JSONArray> {
        val result = mutableListOf<JSONArray>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> {
                    result.add(value)
                    for (i in 0 until value.length()) visit(value.opt(i))
                }
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
            }
        }
        visit(root)
        return result
    }

    private fun JSONObject.optText(vararg names: String): String? {
        names.forEach { name ->
            val value = opt(name)
            asString(value)?.cleanText()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        return null
    }

    private fun JSONObject.optIntAny(vararg names: String): Int? {
        names.forEach { name ->
            val value = opt(name)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.filter { it.isDigit() }.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun asString(value: Any?): String? {
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            is Number -> value.toString()
            else -> null
        }
    }

    private fun EpisodeItem.key(): String {
        return chapterId?.takeIf { it.isNotBlank() } ?: "episode-${episode ?: 0}"
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.encodePath(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    private fun String.decodeUrl(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun String.cleanText(): String {
        return cleanEscaped()
            .replace(spaceRegex, " ")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(subtitleCleanRegex, "")
            .trim(' ', '-', '|')
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun apiRoot(): String {
        return apiRootRegex.find(mainUrl)?.value ?: mainUrl
    }

    private fun String.fixUrl(): String {
        val value = cleanEscaped().trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> apiRoot().trimEnd('/') + value
            else -> value
        }
    }

    private fun isImageOrAsset(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".css") ||
            clean.endsWith(".js") || clean.endsWith(".svg") || clean.endsWith(".ico")
    }

    private data class DramaItem(
        val id: String,
        val title: String,
        val filteredTitle: String?,
        val poster: String?,
        val description: String?,
        val episodeCount: Int?,
        val raw: JSONObject?,
    )

    private data class EpisodeItem(
        val chapterId: String?,
        val episode: Int?,
        val name: String?,
        val raw: JSONObject?,
    )

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("filteredTitle") val filteredTitle: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("title") val title: String? = null,
    )
}
