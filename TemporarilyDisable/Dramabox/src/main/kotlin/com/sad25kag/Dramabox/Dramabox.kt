package com.sad25kag.Dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

class Dramabox : MainAPI() {
    override var mainUrl = "https://www.dramabox.com/in"
    private val apiUrl = "https://db.hafizhibnusyam.my.id"
    override var name = "DramaBox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        // API sections with playback-compatible IDs.
        "/api/dramas/indo" to "Drama Dub Indo",
        "/api/dramas/trending" to "Trending",
        "/api/dramas/must-sees" to "Must Sees",
        "/api/dramas/hidden-gems" to "Hidden Gems",

        // Official DramaBox browse/category pages.
        "web:/browse" to "Semua Drama",
        "web:/browse/447" to "Romansa",
        "web:/browse/449" to "Cinta Pahit",
        "web:/browse/467" to "Realitas",
        "web:/browse/456" to "Nikah Dulu Cinta Belakangan",
        "web:/browse/454" to "Kawin Kontrak",
        "web:/browse/442" to "Naga",
        "web:/browse/470" to "Orang Kuat",
        "web:/browse/466" to "Salah Paham",
        "web:/browse/464" to "CEO Wanita",
        "web:/browse/450" to "Kelahiran Kembali",
        "web:/browse/459" to "Reuni",
        "web:/browse/448" to "Manis",
        "web:/browse/462" to "Melawan Balik",
        "web:/browse/469" to "Cinta Sejati",
        "web:/browse/430" to "Dokter Dewa",
        "web:/browse/427" to "Urban",
        "web:/browse/444" to "Menantu Matrilineal",
        "web:/browse/433" to "Kekuatan Super",
        "web:/browse/429" to "Kebangkitan",
        "web:/browse/441" to "Identitas Rahasia",
        "web:/browse/258" to "Billionaire",
        "web:/browse/460" to "Bayi",
        "web:/browse/435" to "Orang Kecil",
        "web:/browse/434" to "Misteri",
        "web:/browse/161" to "Romance",
        "web:/browse/283" to "Toxic Love",
        "web:/browse/440" to "Miliarder",
        "web:/browse/437" to "Ahli Turun Gunung",
        "web:/browse/457" to "Pernikahan Kilat",
        "web:/browse/463" to "Wanita Tangguh",
        "web:/browse/445" to "Pengkhianatan",
        "web:/browse/436" to "Kebangkitan Warisan",
        "web:/browse/461" to "Cinta Segitiga",
        "web:/browse/439" to "Perjalanan Waktu",
        "web:/browse/455" to "Kekasih Kontrak",
        "web:/browse/453" to "Identitas Tersembunyi",
        "web:/browse/689" to "Keluarga",
        "web:/browse/438" to "Kembali Orang Kuat",
        "web:/browse/452" to "Identitas Tertukar",
        "web:/browse/458" to "Balas Dendam",

        // Extra search buckets for terms that are useful in Indonesia/API search.
        "search:Cinta" to "Romance Indonesia",
        "search:Love" to "Love Story",
        "search:Pernikahan" to "Pernikahan",
        "search:Marriage" to "Marriage",
        "search:Kontrak" to "Marriage Contract",
        "search:Revenge" to "Revenge",
        "search:CEO" to "CEO",
        "search:Boss" to "Boss",
        "search:Mafia" to "Mafia",
        "search:Family" to "Family",
        "search:Istri" to "Istri",
        "search:Suami" to "Suami",
        "search:Rahasia" to "Secret",
        "search:Fantasy" to "Fantasy",
        "search:Werewolf" to "Werewolf"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = if (page < 1) 1 else page

        if (request.data.startsWith("web:")) {
            val webItems = fetchOfficialBrowseList(request.data.removePrefix("web:"), safePage)
                .distinctBy { it.url }

            val items = if (webItems.isNotEmpty()) {
                webItems
            } else {
                // Fallback: kalau halaman resmi sedang 403/dinamis, kategori tetap terisi
                // memakai API search dengan nama kategori.
                fetchSearchList(request.name, safePage)
                    ?.data
                    .orEmpty()
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }

            return newHomePageResponse(
                HomePageList(request.name, items, false),
                hasNext = items.isNotEmpty()
            )
        }

        val response = if (request.data.startsWith("search:")) {
            fetchSearchList(request.data.removePrefix("search:").trim(), safePage)
        } else {
            fetchDramaList(request.data, safePage)
        }

        val items = response?.data.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, false),
            response?.meta?.pagination?.hasMore ?: items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val response = fetchSearchList(keyword, page = 1, size = 50)
        return response?.data.orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val openData = parseOpenData(url)
        val dramaId = openData?.id?.trim()?.takeIf { it.isNotBlank() }
            ?: extractDramaId(url)
            ?: throw ErrorLoadingException("ID DramaBox tidak ditemukan")

        // Pakai ID tetap sebagai kunci utama API, tapi episode sekarang menyimpan chapterId/webUrl juga.
        val detail = fetchDramaDetail(dramaId)

        val title = cleanTitle(
            detail?.title
                ?: openData?.title
                ?: extractTitleFromUrl(url)
                ?: "DramaBox"
        ).ifBlank { "DramaBox" }

        val poster = detail?.coverImage?.takeIf { it.isNotBlank() }
            ?: openData?.coverImage?.takeIf { it.isNotBlank() }

        val officialDramaUrl = openData?.webUrl?.takeIf { it.isNotBlank() }
            ?: buildDramaWebUrl(title, dramaId)

        val episodeCount = detail?.episodeCount?.takeIf { it > 0 }
            ?: inferEpisodeCount(dramaId)
                .takeIf { it > 0 }
            ?: FALLBACK_EPISODE_COUNT

        val episodesFromApi = fetchEpisodeList(dramaId, title, poster)
        val episodes = if (episodesFromApi.isNotEmpty()) {
            episodesFromApi
        } else {
            (1..episodeCount).map { ep ->
                newEpisode(
                    LoadData(
                        bookId = dramaId,
                        episodeNo = ep,
                        title = title,
                        webUrl = officialDramaUrl,
                        chapterId = null
                    ).toJson()
                ) {
                    this.name = "Episode $ep"
                    this.episode = ep
                    this.posterUrl = poster
                }
            }
        }

        return newTvSeriesLoadResponse(title, officialDramaUrl, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = detail?.introduction
            this.tags = detail?.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        val dramaId = parsed.bookId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val episodeNo = parsed.episodeNo ?: return false
        val chapterId = parsed.chapterId?.trim()?.takeIf { it.isNotBlank() }
        val officialVideoUrl = parsed.webUrl?.trim()?.takeIf { it.isNotBlank() }

        // Stream DramaBox biasanya signed/temporary, jadi refresh saat tombol play ditekan.
        // Cached stream dari episode hanya dipakai sebagai fallback terakhir.
        val freshStreams = fetchChapterForEpisode(dramaId, episodeNo, chapterId)
            ?.streamUrl
            .orEmpty()
            .mapNotNull { it.normalizedOrNull() }
            .filterPlayableStreams()
            .distinctBy { it.url }
            .sortPlaybackStreams()

        val officialPageStreams = if (freshStreams.isEmpty() && !officialVideoUrl.isNullOrBlank()) {
            fetchOfficialVideoStreams(officialVideoUrl)
                .mapNotNull { it.normalizedOrNull() }
                .filterPlayableStreams()
                .distinctBy { it.url }
                .sortPlaybackStreams()
        } else {
            emptyList()
        }

        val cachedStreams = if (freshStreams.isEmpty() && officialPageStreams.isEmpty()) {
            parsed.streams.orEmpty()
                .mapNotNull { it.normalizedOrNull() }
                .filterPlayableStreams()
                .distinctBy { it.url }
                .sortPlaybackStreams()
        } else {
            emptyList()
        }

        val streams = when {
            freshStreams.isNotEmpty() -> freshStreams
            officialPageStreams.isNotEmpty() -> officialPageStreams
            else -> cachedStreams
        }

        if (streams.isEmpty()) {
            if (!officialVideoUrl.isNullOrBlank()) {
                return loadExtractorWithFallback(
                    officialVideoUrl,
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
            return false
        }

        // Kalau ada HLS/MP4, jangan paksa DASH. MPD lebih sering kena IO_BAD_HTTP_STATUS
        // kalau manifest/CDN butuh header/DRM yang tidak lengkap.
        val safeStreams = streams.filter { stream ->
            val url = stream.url.orEmpty()
            url.contains(".m3u8", true) || url.contains(".mp4", true)
        }.ifEmpty { streams }

        safeStreams.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val qualityLabel = stream.quality?.let { "${it}p" } ?: "Auto"
            val linkType = when {
                streamUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    name,
                    "DramaBox $qualityLabel",
                    streamUrl,
                    linkType
                ) {
                    this.quality = qualityFromNumber(stream.quality)
                    this.referer = "$mainUrl/"
                    this.headers = playbackHeaders()
                }
            )
        }

        return true
    }

    private fun List<StreamItem>.filterPlayableStreams(): List<StreamItem> {
        return filter { item ->
            val url = item.url.orEmpty().lowercase()
            url.startsWith("http") &&
                !url.contains("/api/") &&
                !url.contains("/drama/") &&
                !url.contains("/browse") &&
                !url.contains(".jpg") &&
                !url.contains(".jpeg") &&
                !url.contains(".png") &&
                !url.contains(".webp") &&
                !url.contains(".gif") &&
                !url.contains(".svg") &&
                !url.contains(".css") &&
                !url.contains(".js") &&
                !url.contains("google-analytics") &&
                !url.contains("googletagmanager") &&
                !url.contains("doubleclick") &&
                !url.contains("adsbygoogle") &&
                (
                    url.contains(".m3u8") ||
                        url.contains(".mp4") ||
                        url.contains(".mpd") ||
                        url.contains("m3u8") ||
                        url.contains("video")
                )
        }
    }

    private fun List<StreamItem>.sortPlaybackStreams(): List<StreamItem> {
        return sortedWith(
            compareByDescending<StreamItem> { item ->
                val url = item.url.orEmpty().lowercase()
                when {
                    url.contains(".m3u8") -> 3
                    url.contains(".mp4") -> 2
                    url.contains(".mpd") -> 1
                    else -> 0
                }
            }.thenByDescending { it.quality ?: 0 }
        )
    }

    private fun playbackHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "*/*"
        )
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\((Sulih Suara|Dub Indo|Indonesian Sub|Sub Indo)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val dramaId = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanName = cleanTitle(title ?: "DramaBox").ifBlank { "DramaBox" }
        val poster = coverImage?.takeIf { it.isNotBlank() }
        val webUrl = buildDramaWebUrl(cleanName, dramaId)

        // Simpan judul/poster di URL-data supaya halaman detail tetap punya judul
        // walaupun endpoint detail API sedang kosong/gagal.
        return newTvSeriesSearchResponse(
            cleanName,
            DramaOpenData(
                id = dramaId,
                title = cleanName,
                coverImage = poster,
                webUrl = webUrl
            ).toJson(),
            TvType.AsianDrama
        ) {
            this.posterUrl = poster
        }
    }

    private suspend fun fetchOfficialBrowseList(path: String, page: Int): List<SearchResponse> {
        return try {
            val basePath = path.trim().ifBlank { "/browse" }
            val pagePath = if (page <= 1) {
                basePath
            } else {
                "${basePath.trimEnd('/')}/$page"
            }

            val url = if (pagePath.startsWith("http", true)) {
                pagePath
            } else {
                "$mainUrl${if (pagePath.startsWith("/")) pagePath else "/$pagePath"}"
            }

            val document = app.get(
                url,
                headers = webHeaders,
                referer = "$mainUrl/"
            ).document

            document.select("a[href*='/drama/']")
                .mapNotNull { it.toOfficialSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            logError("Dramabox", "fetchOfficialBrowseList failed path=$path page=$page", e)
            emptyList()
        }
    }

    private fun Element.toOfficialSearchResult(): SearchResponse? {
        val href = attr("href")
            .takeIf { it.isNotBlank() }
            ?.toDramaboxUrl()
            ?: return null

        val dramaId = extractDramaId(href) ?: return null

        val rawTitle = attr("title").trim()
            .ifBlank { selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { text().trim() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        val cleanName = cleanTitle(rawTitle)
            .removeEpisodeText()
            .ifBlank { return null }

        if (cleanName.isUiText()) return null

        val poster = findPosterFromElement(this, href)

        return newTvSeriesSearchResponse(
            cleanName,
            DramaOpenData(
                id = dramaId,
                title = cleanName,
                coverImage = poster,
                webUrl = buildDramaWebUrl(cleanName, dramaId)
            ).toJson(),
            TvType.AsianDrama
        ) {
            this.posterUrl = poster
        }
    }

    private fun String.removeEpisodeText(): String {
        return replace(Regex("""(?i)\b\d+\s*episode[s]?\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.isUiText(): Boolean {
        val lower = lowercase()
        if (lower.isBlank()) return true
        if (lower.matches(Regex("""^\d+$"""))) return true
        if (lower.contains("episode") && lower.length < 20) return true

        val blocked = listOf(
            "beranda",
            "kategori",
            "aplikasi",
            "login",
            "lainnya",
            "previous",
            "next",
            "prev",
            "semua",
            "feedback",
            "dramabox"
        )

        return blocked.any { lower == it }
    }

    private fun findPosterFromElement(element: Element, baseUrl: String): String? {
        val boxes = listOfNotNull(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImageFromElement(box, baseUrl)?.let { return it }
            box.select("img, source, picture, div, span").forEach { child ->
                extractImageFromElement(child, baseUrl)?.let { return it }
            }
        }

        return null
    }

    private fun extractImageFromElement(element: Element, baseUrl: String): String? {
        val attrs = listOf(
            "src",
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-image",
            "data-img",
            "poster"
        )

        attrs.forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isImageCandidate()) return value.toDramaboxUrl(baseUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val src = element.attr(attr)
                .split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isImageCandidate() }

            if (!src.isNullOrBlank()) return src.toDramaboxUrl(baseUrl)
        }

        val style = element.attr("style")
        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.toDramaboxUrl(baseUrl) }

        return null
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false
        if (contains("logo", true) || contains("icon", true) || contains("avatar", true)) return false
        return contains(".jpg", true) ||
            contains(".jpeg", true) ||
            contains(".png", true) ||
            contains(".webp", true) ||
            contains("dramaboxdb.com", true) ||
            contains("thwztchapter", true)
    }

    private fun String.toDramaboxUrl(baseUrl: String = mainUrl): String {
        val value = replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "https://www.dramabox.com$value"
            else -> "${baseUrl.trimEnd('/')}/$value"
        }
    }

    private suspend fun fetchDramaList(path: String, page: Int): DramaListResponse? {
        return try {
            val url = "${if (path.startsWith("http")) path else "$apiUrl$path"}${if (path.contains("?")) "&" else "?"}page=$page"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            tryParseJson<DramaListResponse>(body)
        } catch (e: Exception) {
            // FIX #5: Log errors instead of silently swallowing them
            logError("Dramabox", "fetchDramaList failed for path=$path page=$page", e)
            null
        }
    }

    private suspend fun fetchSearchList(keyword: String, page: Int, size: Int = 24): DramaListResponse? {
        return try {
            val encoded = URLEncoder.encode(keyword.trim(), "UTF-8")
            val url = "$apiUrl/api/search?keyword=$encoded&page=$page&size=$size"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            tryParseJson<DramaListResponse>(body)
        } catch (e: Exception) {
            logError("Dramabox", "fetchSearchList failed keyword=$keyword page=$page", e)
            null
        }
    }

    // Returns DramaItem? directly instead of DramaDetailResponse?.
    // FIX: API sometimes returns {"data": {...}} and sometimes just {...} without wrapper.
    // Previously the code only tried the wrapped format — if the API returned the object
    // directly, data would be null and "Drama tidak ditemukan" was always thrown.
    // Now we try both formats: wrapped first, then unwrapped as fallback.
    // Also logs the raw body so you can see exactly what the API returned when debugging.
    private suspend fun fetchDramaDetail(dramaId: String): DramaItem? {
        return try {
            val url = "$apiUrl/api/dramas/$dramaId"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            logDebug("Dramabox", "fetchDramaDetail[$dramaId] raw body: ${body.take(300)}")

            // Try wrapped format {"data": {...}} first
            val wrapped = tryParseJson<DramaDetailResponse>(body)?.data
            if (wrapped?.id != null) return wrapped

            // Fall back to direct object format {...}
            val direct = tryParseJson<DramaItem>(body)
            if (direct?.id != null) return direct

            logError("Dramabox", "fetchDramaDetail[$dramaId]: both parse formats returned null. Body: ${body.take(500)}")
            null
        } catch (e: Exception) {
            logError("Dramabox", "fetchDramaDetail failed for id=$dramaId", e)
            null
        }
    }


    private suspend fun fetchChapterBodies(
        dramaId: String,
        episodeNo: Int,
        chapterId: String? = null
    ): List<String> {
        val bodies = mutableListOf<String>()
        val endpoint = "$apiUrl/api/chapters/video"

        suspend fun tryGet(url: String) {
            runCatching {
                executeWithRetry {
                    rateLimitDelay(moduleName = "Dramabox")
                    app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
                }
            }.onSuccess { body ->
                if (body.isNotBlank()) bodies.add(body)
            }.onFailure { e ->
                logError("Dramabox", "chapter GET failed url=$url", e)
            }
        }

        suspend fun tryPost(payload: Map<String, String>) {
            runCatching {
                executeWithRetry {
                    rateLimitDelay(moduleName = "Dramabox")
                    app.post(
                        endpoint,
                        data = payload,
                        timeout = AutoUsedConstants.DEFAULT_TIMEOUT
                    ).text
                }
            }.onSuccess { body ->
                if (body.isNotBlank()) bodies.add(body)
            }.onFailure { e ->
                logError("Dramabox", "chapter POST failed payload=$payload", e)
            }
        }

        if (!chapterId.isNullOrBlank()) {
            listOf(
                "$endpoint?book_id=$dramaId&chapter_id=$chapterId",
                "$endpoint?book_id=$dramaId&chapterId=$chapterId",
                "$endpoint?book_id=$dramaId&id=$chapterId",
                "$endpoint?chapter_id=$chapterId",
                "$endpoint?chapterId=$chapterId",
                "$endpoint?id=$chapterId",
                "$apiUrl/api/chapters/$chapterId",
                "$apiUrl/api/chapters/video/$chapterId"
            ).distinct().forEach { tryGet(it) }

            listOf(
                mapOf("book_id" to dramaId, "chapter_id" to chapterId),
                mapOf("book_id" to dramaId, "chapterId" to chapterId),
                mapOf("book_id" to dramaId, "id" to chapterId),
                mapOf("bookId" to dramaId, "chapter_id" to chapterId),
                mapOf("bookId" to dramaId, "chapterId" to chapterId),
                mapOf("bookId" to dramaId, "id" to chapterId),
                mapOf("chapter_id" to chapterId),
                mapOf("chapterId" to chapterId),
                mapOf("id" to chapterId)
            ).forEach { tryPost(it) }
        }

        // Endpoint lama dan endpoint POST resmi. Docs Swagger API ini menampilkan /api/chapters/video sebagai POST,
        // jadi kita coba dua-duanya supaya kompatibel dengan versi API lama dan baru.
        listOf(
            "$endpoint?book_id=$dramaId&episode=$episodeNo",
            "$endpoint?bookId=$dramaId&episode=$episodeNo",
            "$endpoint?book_id=$dramaId&episode_no=$episodeNo",
            "$endpoint?book_id=$dramaId&episodeNo=$episodeNo"
        ).distinct().forEach { tryGet(it) }

        listOf(
            mapOf("book_id" to dramaId, "episode" to episodeNo.toString()),
            mapOf("bookId" to dramaId, "episode" to episodeNo.toString()),
            mapOf("book_id" to dramaId, "episode_no" to episodeNo.toString()),
            mapOf("book_id" to dramaId, "episodeNo" to episodeNo.toString())
        ).forEach { tryPost(it) }

        return bodies.distinct()
    }

    // FIX #1: Changed app.post() to app.get().
    // The endpoint uses query string parameters (?book_id=...&episode=...),
    // which is a GET-style API. Using POST with no body was returning
    // 405 Method Not Allowed / empty response, causing all video loading to fail.
    private suspend fun fetchChapterForEpisode(
        dramaId: String,
        episodeNo: Int,
        chapterId: String? = null
    ): ChapterContent? {
        val bodies = fetchChapterBodies(dramaId, episodeNo, chapterId)

        bodies.forEach { body ->
            val typed = tryParseJson<ChapterResponse>(body)
            val typedChapters = typed?.allChapters().orEmpty()
            val parsedChapters = parseChaptersFromJson(body)
            val chapters = (typedChapters + parsedChapters)
                .distinctBy { "${it.chapterIndex}-${it.chapterIdValue()}-${it.streamUrl?.firstOrNull()?.url}" }

            selectBestChapter(chapters, episodeNo, chapterId)?.let { return it }
        }

        return null
    }

    // FIX #1 (same): inferEpisodeCount also used app.post() on the same endpoint.
    private suspend fun inferEpisodeCount(dramaId: String): Int {
        return try {
            val bodies = fetchChapterBodies(dramaId, episodeNo = 1, chapterId = null)
            var max = 0

            bodies.forEach { body ->
                val typed = tryParseJson<ChapterResponse>(body)
                val typedMax = typed?.allChapters()
                    .orEmpty()
                    .mapNotNull { it.chapterIndex?.toIntOrNull() }
                    .maxOrNull() ?: 0

                max = maxOf(max, typedMax, inferEpisodeCountFromRawJson(body))
            }

            max
        } catch (e: Exception) {
            logError("Dramabox", "inferEpisodeCount failed for id=$dramaId", e)
            0
        }
    }



    private suspend fun fetchEpisodeList(
        dramaId: String,
        title: String,
        poster: String?
    ): List<Episode> {
        val bodies = try {
            fetchChapterBodies(dramaId, episodeNo = 1, chapterId = null)
        } catch (e: Exception) {
            logError("Dramabox", "fetchEpisodeList failed id=$dramaId", e)
            return emptyList()
        }

        val chapters = bodies.flatMap { body ->
            val typed = tryParseJson<ChapterResponse>(body)?.allChapters().orEmpty()
            val parsed = parseChaptersFromJson(body)
            typed + parsed
        }
            .distinctBy { "${it.chapterIndex}-${it.chapterIdValue()}-${it.streamUrl?.firstOrNull()?.url}" }
            .sortedBy { it.chapterIndex?.toIntOrNull() ?: Int.MAX_VALUE }

        return chapters.mapNotNull { chapter ->
            val ep = chapter.chapterIndex?.toIntOrNull() ?: return@mapNotNull null
            val chapterId = chapter.chapterIdValue()
            val webUrl = if (!chapterId.isNullOrBlank()) {
                buildVideoWebUrl(title, dramaId, chapterId, ep)
            } else {
                null
            }

            val cleanStreams = chapter.streamUrl.orEmpty()
                .mapNotNull { it.normalizedOrNull() }
                .distinctBy { it.url }

            newEpisode(
                LoadData(
                    bookId = dramaId,
                    episodeNo = ep,
                    chapterId = chapterId,
                    webUrl = webUrl,
                    title = title,
                    streams = cleanStreams
                ).toJson()
            ) {
                this.name = "Episode $ep"
                this.episode = ep
                this.posterUrl = poster
            }
        }
    }

    private fun selectBestChapter(
        chapters: List<ChapterContent>,
        episodeNo: Int,
        chapterId: String?
    ): ChapterContent? {
        val withStreams = chapters.filter { it.streamUrl?.isNotEmpty() == true }
        if (withStreams.isEmpty()) return null

        if (!chapterId.isNullOrBlank()) {
            withStreams.firstOrNull { it.chapterIdValue() == chapterId }?.let { return it }
        }

        withStreams.firstOrNull { it.chapterIndex?.toIntOrNull() == episodeNo }?.let { return it }
        return withStreams.firstOrNull()
    }

    private fun ChapterContent.chapterIdValue(): String? {
        return listOf(id, chapterId, chapterIdCamel, cid, chapterNo, chapterNoCamel)
            .firstOrNull { value ->
                !value.isNullOrBlank() &&
                    value.any { it.isDigit() } &&
                    value != chapterIndex
            }
    }

    private fun StreamItem.normalizedOrNull(): StreamItem? {
        val fixedUrl = url
            ?.cleanStreamText()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return copy(url = fixedUrl)
    }

    private suspend fun fetchOfficialVideoStreams(videoUrl: String): List<StreamItem> {
        return try {
            val html = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(
                    videoUrl,
                    headers = webHeaders,
                    referer = "$mainUrl/"
                ).text
            }

            parseStreamItemsFromHtml(html)
        } catch (e: Exception) {
            logError("Dramabox", "fetchOfficialVideoStreams failed url=$videoUrl", e)
            emptyList()
        }
    }

    private fun parseStreamItemsFromHtml(html: String): List<StreamItem> {
        val decodedHtml = html.cleanStreamText()

        val result = mutableListOf<StreamItem>()

        val directPatterns = listOf(
            Regex("""https?://[^"'\\<>\s]+?\.(?:m3u8|mp4|mpd)(?:\?[^"'\\<>\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""(?:playUrl|videoUrl|video_url|streamUrl|stream_url|file|src)["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )

        directPatterns.forEach { pattern ->
            pattern.findAll(decodedHtml).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                val cleaned = raw.cleanStreamText()
                if (cleaned.startsWith("http", true)) {
                    result.add(StreamItem(cleaned.detectQuality(), cleaned))
                }
            }
        }

        return result.distinctBy { it.url }
    }


    private fun String.cleanStreamText(): String {
        val normalized = replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\u002F", "/")
            .replace("\u003A", ":")
            .replace("\u0026", "&")
            .replace("&amp;", "&")
            .trim()

        return if (normalized.contains("%3A%2F%2F", true) || normalized.contains("%2F", true)) {
            runCatching { java.net.URLDecoder.decode(normalized, "UTF-8") }
                .getOrDefault(normalized)
        } else {
            normalized
        }
    }

    private fun String.detectQuality(): Int? {
        val lower = lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") || lower.contains("2k") -> 1440
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            lower.contains("240") -> 240
            else -> null
        }
    }

    private fun buildVideoWebUrl(title: String?, dramaId: String, chapterId: String, episodeNo: Int): String {
        val dramaSlug = title
            ?.let(::cleanTitle)
            ?.replace(Regex("""['’`]+"""), "")
            ?.replace("&", " and ")
            ?.replace(Regex("""[^A-Za-z0-9]+"""), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }
            ?: "DramaBox"

        return "$mainUrl/video/${dramaId}_${dramaSlug}/${chapterId}_Episode-$episodeNo"
    }

    private fun qualityFromNumber(value: Int?): Int {
        return when (value) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1080.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun parseOpenData(raw: String): DramaOpenData? {
        return runCatching { parseJson<DramaOpenData>(raw) }.getOrNull()
    }

    private fun extractDramaId(raw: String): String? {
        parseOpenData(raw)?.id?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        // Format baru/valid: /in/drama/41000110445/forever-was-a-lie
        Regex("""/drama/(\d{6,})(?:/|$|\?)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Format lama dari file awal: /in/drama/_41000110445
        raw.substringAfterLast("_", "")
            .substringBefore("?")
            .substringBefore("&")
            .trim()
            .takeIf { it.isNotBlank() && it.any { char -> char.isDigit() } }
            ?.let { return it }

        // Fallback jika data cuma mengandung angka ID.
        return Regex("""\d{6,}""")
            .find(raw)
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractTitleFromUrl(raw: String): String? {
        val slug = raw.substringAfter("/drama/", "")
            .substringAfter("/", "")
            .substringBefore("?")
            .substringBefore("&")
            .replace("-", " ")
            .trim()

        return slug.takeIf { it.isNotBlank() && !it.all { char -> char.isDigit() } }
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun buildDramaWebUrl(title: String?, dramaId: String): String {
        val slug = title
            ?.let(::cleanTitle)
            ?.lowercase()
            ?.replace(Regex("""['’`]+"""), "")
            ?.replace("&", " and ")
            ?.replace(Regex("""[^a-z0-9]+"""), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }
            ?: "dramabox"

        return "$mainUrl/drama/$dramaId/$slug"
    }

    private fun ChapterResponse.allChapters(): List<ChapterContent> {
        return data.orEmpty() + extras.orEmpty()
    }

    private fun inferEpisodeCountFromRawJson(body: String): Int {
        return runCatching {
            val root = JSONObject(body)
            val explicitCount = root.findMaxKnownEpisodeCount()
            val parsedChapters = parseChaptersFromJson(body)
                .mapNotNull { it.chapterIndex?.toIntOrNull() }
                .maxOrNull() ?: 0

            maxOf(explicitCount, parsedChapters)
        }.getOrDefault(0)
    }

    private fun JSONObject.findMaxKnownEpisodeCount(): Int {
        var max = 0
        val countKeys = setOf(
            "episode_count",
            "episodeCount",
            "chapter_count",
            "chapterCount",
            "total_episode",
            "totalEpisode",
            "total_episodes",
            "totalEpisodes",
            "total_chapter",
            "totalChapter",
            "total_chapters",
            "totalChapters",
            "max_episode",
            "maxEpisode"
        )

        keys().forEach { key ->
            val value = opt(key)
            if (key in countKeys) {
                max = maxOf(max, value.toString().toIntOrNull() ?: 0)
            }

            when (value) {
                is JSONObject -> max = maxOf(max, value.findMaxKnownEpisodeCount())
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) max = maxOf(max, item.findMaxKnownEpisodeCount())
                    }
                }
            }
        }

        return max
    }

    private fun parseChaptersFromJson(body: String): List<ChapterContent> {
        return runCatching {
            val root = JSONObject(body)
            val result = mutableListOf<ChapterContent>()

            fun parseAny(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        value.toChapterContentOrNull()?.let { result.add(it) }
                        value.keys().forEach { key -> parseAny(value.opt(key)) }
                    }
                    is JSONArray -> {
                        for (i in 0 until value.length()) parseAny(value.opt(i))
                    }
                }
            }

            parseAny(root)
            result.distinctBy { "${it.chapterIndex}-${it.streamUrl?.firstOrNull()?.url}" }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.toChapterContentOrNull(): ChapterContent? {
        val index = optString("chapter_index")
            .ifBlank { optString("chapterIndex") }
            .ifBlank { optString("episode") }
            .ifBlank { optString("episode_no") }
            .ifBlank { optString("episodeNo") }
            .ifBlank { optString("index") }
            .takeIf { it.isNotBlank() }

        val streams = parseStreamItems(
            opt("stream_url")
                ?: opt("streamUrl")
                ?: opt("streams")
                ?: opt("video")
                ?: opt("videos")
                ?: opt("play_url")
                ?: opt("playUrl")
                ?: opt("video_link")
                ?: opt("videoLink")
                ?: opt("download_link")
                ?: opt("downloadLink")
                ?: opt("m3u8")
                ?: opt("mp4")
                ?: opt("source")
                ?: opt("sources")
                ?: this
        )

        val id = optString("id").takeIf { it.isNotBlank() }
        val chapterId = optString("chapter_id").takeIf { it.isNotBlank() }
        val chapterIdCamel = optString("chapterId").takeIf { it.isNotBlank() }
        val cid = optString("cid").takeIf { it.isNotBlank() }
        val chapterNo = optString("chapter_no").takeIf { it.isNotBlank() }
        val chapterNoCamel = optString("chapterNo").takeIf { it.isNotBlank() }

        if (index == null && streams.isEmpty() && id == null && chapterId == null && chapterIdCamel == null && cid == null) return null
        return ChapterContent(
            chapterIndex = index,
            streamUrl = streams,
            id = id,
            chapterId = chapterId,
            chapterIdCamel = chapterIdCamel,
            cid = cid,
            chapterNo = chapterNo,
            chapterNoCamel = chapterNoCamel
        )
    }

    private fun parseStreamItems(raw: Any?): List<StreamItem> {
        val result = mutableListOf<StreamItem>()

        fun parseOne(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val url = value.optString("url")
                        .ifBlank { value.optString("file") }
                        .ifBlank { value.optString("video_url") }
                        .ifBlank { value.optString("videoUrl") }
                        .ifBlank { value.optString("stream_url") }
                        .ifBlank { value.optString("streamUrl") }
                        .ifBlank { value.optString("play_url") }
                        .ifBlank { value.optString("playUrl") }
                        .ifBlank { value.optString("video_link") }
                        .ifBlank { value.optString("videoLink") }
                        .ifBlank { value.optString("download_link") }
                        .ifBlank { value.optString("downloadLink") }
                        .ifBlank { value.optString("m3u8") }
                        .ifBlank { value.optString("mp4") }
                        .ifBlank { value.optString("src") }
                        .ifBlank { value.optString("link") }

                    val quality = value.optInt("quality", -1)
                        .takeIf { it > 0 }
                        ?: value.optString("quality")
                            .filter { it.isDigit() }
                            .toIntOrNull()
                        ?: value.optString("label")
                            .filter { it.isDigit() }
                            .toIntOrNull()

                    if (url.isNotBlank()) result.add(StreamItem(quality, url))

                    value.keys().forEach { key ->
                        val child = value.opt(key)
                        val keyQuality = key.filter { it.isDigit() }.toIntOrNull()

                        if (child is String) {
                            val cleanedChild = child.cleanStreamText()
                            if (cleanedChild.startsWith("http", true)) {
                                result.add(StreamItem(keyQuality ?: cleanedChild.detectQuality(), cleanedChild))
                            }
                        }

                        if (child is JSONObject || child is JSONArray) parseOne(child)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) parseOne(value.opt(i))
                }
                is String -> {
                    val cleaned = value.cleanStreamText()

                    if (cleaned.startsWith("http", true)) {
                        result.add(StreamItem(cleaned.detectQuality(), cleaned))
                    } else {
                        Regex("""https?://[^"'\<>\s]+""", RegexOption.IGNORE_CASE)
                            .findAll(cleaned)
                            .forEach { match ->
                                val url = match.value.cleanStreamText()
                                result.add(StreamItem(url.detectQuality(), url))
                            }
                    }
                }
            }
        }

        parseOne(raw)
        return result.distinctBy { it.url }
    }

    private val webHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    companion object {
        private const val FALLBACK_EPISODE_COUNT = 1
    }

    data class DramaListResponse(@JsonProperty("data") val data: List<DramaItem>? = null, @JsonProperty("meta") val meta: ResponseMeta? = null)
    data class DramaDetailResponse(@JsonProperty("data") val data: DramaItem? = null)
    data class DramaItem(@JsonProperty("id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("cover_image") val coverImage: String? = null, @JsonProperty("introduction") val introduction: String? = null, @JsonProperty("tags") val tags: List<String>? = null, @JsonProperty("episode_count") val episodeCount: Int? = null)
    data class ResponseMeta(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class Pagination(@JsonProperty("has_more") val hasMore: Boolean? = null)
    data class ChapterResponse(@JsonProperty("data") val data: List<ChapterContent>? = null, @JsonProperty("extras") val extras: List<ChapterContent>? = null)
    data class ChapterContent(
        @JsonProperty("chapter_index") val chapterIndex: String? = null,
        @JsonProperty("stream_url") val streamUrl: List<StreamItem>? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("chapter_id") val chapterId: String? = null,
        @JsonProperty("chapterId") val chapterIdCamel: String? = null,
        @JsonProperty("cid") val cid: String? = null,
        @JsonProperty("chapter_no") val chapterNo: String? = null,
        @JsonProperty("chapterNo") val chapterNoCamel: String? = null
    )
    data class StreamItem(@JsonProperty("quality") val quality: Int? = null, @JsonProperty("url") val url: String? = null)
    data class DramaOpenData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("coverImage") val coverImage: String? = null,
        @JsonProperty("webUrl") val webUrl: String? = null
    )
    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("webUrl") val webUrl: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("streams") val streams: List<StreamItem>? = null
    )
}
