package com.sad25kag

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLDecoder
import java.net.URLEncoder

class Chatrubate : MainAPI() {
    override var mainUrl = "https://chaturbate.com"
    override var name = "Chaturbate"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "/api/ts/roomlist/room-list/?limit=90" to "Unggulan",
        "/api/ts/roomlist/room-list/?limit=90&hd=true" to "HD",
        "/api/ts/roomlist/room-list/?genders=f&limit=90" to "Perempuan",
        "/api/ts/roomlist/room-list/?genders=m&limit=90" to "Pria",
        "/api/ts/roomlist/room-list/?genders=c&limit=90" to "Pasangan",
        "/api/ts/roomlist/room-list/?genders=t&limit=90" to "Transgender",
        "/api/ts/roomlist/room-list/?regions=NA&limit=90" to "Amerika Utara",
        "/api/ts/roomlist/room-list/?regions=SA&limit=90" to "Amerika Selatan",
        "/api/ts/roomlist/room-list/?regions=AS&limit=90" to "Asia",
        "/api/ts/roomlist/room-list/?regions=ER&limit=90" to "Eropa / Rusia",
        "/api/ts/roomlist/room-list/?hashtags=asian&limit=90" to "Asian",
        "/api/ts/roomlist/room-list/?hashtags=latina&limit=90" to "Latina",
        "/api/ts/roomlist/room-list/?hashtags=milf&limit=90" to "MILF",
        "/api/ts/roomlist/room-list/?hashtags=ebony&limit=90" to "Ebony",
        "/api/ts/roomlist/room-list/?hashtags=cosplay&limit=90" to "Cosplay"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val offset = if (page <= 1) 0 else 90 * (page - 1)
        val url = appendOffset(request.data, offset)

        val response = app.get(
            "$mainUrl$url",
            headers = apiHeaders,
            referer = "$mainUrl/"
        ).parsedSafe<Response>()

        val items = response?.rooms.orEmpty()
            .mapNotNull { it.toLiveSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val offset = if (page <= 1) 0 else 90 * (page - 1)

        val response = app.get(
            "$mainUrl/api/ts/roomlist/room-list/?keywords=$encodedQuery&limit=90&offset=$offset",
            referer = "$mainUrl/",
            headers = apiHeaders
        ).parsedSafe<Response>()

        val results = response?.rooms.orEmpty()
            .mapNotNull { it.toLiveSearchResult() }
            .distinctBy { it.url }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = normalizeRoomUrl(url)
        val document = app.get(
            cleanUrl,
            headers = htmlHeaders,
            referer = "$mainUrl/"
        ).document

        val username = cleanUrl.trimEnd('/').substringAfterLast("/")
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            ?.replace("| Chaturbate", "", ignoreCase = true)
            ?.ifBlank { null }
            ?: username

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image], [property='og:image']")
                ?.attr("content")
        )

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.ifBlank { null }

        return newLiveStreamLoadResponse(
            name = title,
            url = cleanUrl,
            dataUrl = cleanUrl
        ) {
            posterUrl = poster
            plot = description ?: "Live stream Chaturbate"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val roomUrl = normalizeRoomUrl(data)
        val username = roomUrl.trimEnd('/').substringAfterLast("/")
        if (username.isBlank()) return false

        val streamCandidates = linkedSetOf<String>()

        suspend fun fetchText(url: String, referer: String, json: Boolean = false): String? {
            return runCatching {
                app.get(
                    url,
                    headers = if (json) apiHeaders else htmlHeaders,
                    referer = referer,
                    timeout = 30L
                ).text
            }.getOrNull()
        }

        // 1) Current room HTML often contains the live HLS inside initialRoomDossier.
        val roomHtml = fetchText(roomUrl, "$mainUrl/", json = false)
        if (!roomHtml.isNullOrBlank()) {
            streamCandidates.addAll(extractM3u8Candidates(roomHtml))
            streamCandidates.addAll(extractInitialRoomDossierStreams(roomHtml))
        }

        // 2) Keep the old API path, but do not rely on it as the only source anymore.
        val apiUrls = listOf(
            "$mainUrl/api/chatvideocontext/$username/",
            "$mainUrl/api/chatvideocontext/$username/?room=$username",
            "$mainUrl/api/chatvideocontext/$username/?format=json"
        )

        for (apiUrl in apiUrls) {
            if (streamCandidates.isNotEmpty()) break

            val responseText = fetchText(apiUrl, roomUrl, json = true) ?: continue
            val parsed = runCatching {
                parseJson<ChatResponse>(responseText)
            }.getOrNull()

            listOf(
                parsed?.hlsSource,
                parsed?.hlsSourceCamel,
                parsed?.hlsSourceUrl,
                parsed?.streamUrl
            ).forEach { stream ->
                cleanStreamUrl(stream)?.let { streamCandidates.add(it) }
            }

            streamCandidates.addAll(extractM3u8Candidates(responseText))
        }

        if (streamCandidates.isEmpty()) {
            Log.d("kraptor_$name", "No playable HLS found for $username")
            return false
        }

        var emitted = false
        streamCandidates.distinct().forEach { streamUrl ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name - Live",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = roomUrl
                    this.quality = inferQuality(streamUrl)
                    this.headers = mapOf(
                        "Accept" to "*/*",
                        "User-Agent" to USER_AGENT,
                        "Referer" to roomUrl,
                        "Origin" to mainUrl
                    )
                }
            )
            emitted = true
        }

        return emitted
    }

    private fun Room.toLiveSearchResult(): SearchResponse? {
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank()) return null

        return newLiveSearchResponse(
            name = cleanUsername,
            url = "$mainUrl/$cleanUsername",
            type = TvType.Live,
            fix = false
        ) {
            posterUrl = img.takeIf { it.isNotBlank() }
            lang = null
        }
    }

    private fun appendOffset(path: String, offset: Int): String {
        val joiner = if (path.contains("?")) "&" else "?"
        return "$path${joiner}offset=$offset"
    }

    private fun normalizeRoomUrl(raw: String): String {
        val clean = raw.trim()
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$mainUrl/$clean"
        }.substringBefore("?")
    }

    private fun extractInitialRoomDossierStreams(html: String): List<String> {
        val results = linkedSetOf<String>()

        val dossierPatterns = listOf(
            Regex("""initialRoomDossier\s*=\s*\"((?:\\.|[^\"\\])*)\"""),
            Regex("""initialRoomDossier\s*=\s*'((?:\\.|[^'\\])*)'"""),
            Regex("""initialRoomDossier\s*=\s*(\{.*?\})\s*;""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        )

        dossierPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val raw = match.groupValues.getOrNull(1).orEmpty()
                if (raw.isBlank()) return@forEach

                val decoded = decodePossibleJsonString(raw)
                results.addAll(extractM3u8Candidates(decoded))
            }
        }

        return results.toList()
    }

    private fun extractM3u8Candidates(text: String): List<String> {
        val results = linkedSetOf<String>()
        val variants = linkedSetOf<String>()

        variants.add(text)
        variants.add(decodePossibleJsonString(text))
        runCatching { URLDecoder.decode(text, "UTF-8") }
            .getOrNull()
            ?.let { variants.add(it) }

        val fieldPatterns = listOf(
            Regex("""\"(?:hls_source|hlsSource|hlsSourceUrl|stream_url|streamUrl|source)\"\s*:\s*\"([^\"]+?\.m3u8[^\"]*)\""", RegexOption.IGNORE_CASE),
            Regex("""'(?:hls_source|hlsSource|hlsSourceUrl|stream_url|streamUrl|source)'\s*:\s*'([^']+?\.m3u8[^']*)'""", RegexOption.IGNORE_CASE)
        )

        val urlPatterns = listOf(
            Regex("""https?:\\?/\\?/[^\"'\s<>]+?\.m3u8[^\"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\"'\s<>]+?\.m3u8[^\"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""//[^\"'\s<>]+?\.m3u8[^\"'\s<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?%3A%2F%2F[^\"'\s<>]+?\.m3u8[^\"'\s<>]*""", RegexOption.IGNORE_CASE)
        )

        variants.forEach { variant ->
            fieldPatterns.forEach { pattern ->
                pattern.findAll(variant).forEach { match ->
                    cleanStreamUrl(match.groupValues.getOrNull(1))?.let { results.add(it) }
                }
            }

            urlPatterns.forEach { pattern ->
                pattern.findAll(variant).forEach { match ->
                    cleanStreamUrl(match.value)?.let { results.add(it) }
                }
            }
        }

        return results.toList()
    }

    private fun decodePossibleJsonString(raw: String): String {
        var result = raw
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003d", "=")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")

        if (result.contains("%3A", true) || result.contains("%2F", true)) {
            result = runCatching { URLDecoder.decode(result, "UTF-8") }.getOrDefault(result)
        }

        return result
    }

    private fun cleanStreamUrl(raw: String?): String? {
        var clean = raw?.trim().orEmpty()
        if (clean.isBlank()) return null

        clean = decodePossibleJsonString(clean)
            .trim()
            .trim('"', '\'', ',', ';')
            .replace("\\", "")

        if (clean.startsWith("//")) clean = "https:$clean"

        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return null
        if (!clean.contains(".m3u8", true)) return null

        // Strip HTML/script leftovers without removing valid query parameters.
        clean = clean.substringBefore("</")
            .substringBefore("<")
            .substringBefore(" ")
            .trim()

        return clean
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> 2160
            url.contains("1440", true) -> 1440
            url.contains("1080", true) -> 1080
            url.contains("720", true) -> 720
            url.contains("480", true) -> 480
            url.contains("360", true) -> 360
            url.contains("240", true) -> 240
            else -> Qualities.Unknown.value
        }
    }

    data class ChatResponse(
        @JsonProperty("hls_source") val hlsSource: String? = null,
        @JsonProperty("hlsSource") val hlsSourceCamel: String? = null,
        @JsonProperty("hls_source_url") val hlsSourceUrl: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("broadcaster_username") val username: String? = null
    )

    data class Room(
        @JsonProperty("img") val img: String = "",
        @JsonProperty("username") val username: String = "",
        @JsonProperty("subject") val subject: String = "",
        @JsonProperty("tags") val tags: List<String> = emptyList()
    )

    data class Response(
        @JsonProperty("all_rooms_count") val allRoomsCount: String = "",
        @JsonProperty("room_list_id") val roomListId: String = "",
        @JsonProperty("total_count") val totalCount: String = "",
        @JsonProperty("rooms") val rooms: List<Room> = emptyList()
    )

    private val apiHeaders = mapOf(
        "Accept" to "application/json,text/plain,*/*",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private val htmlHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
