package com.dubbindo

import com.dubbindo.BuildConfig
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class DubbindoProvider : MainAPI() {
    override var mainUrl = "https://www.dubbindo.site"
    override var name = "Dubbindo (Source Limited)"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon,
        TvType.Anime,
    )

    private val username = BuildConfig.DUBBINDO_USERNAME
    private val password = BuildConfig.DUBBINDO_PASSWORD

    private var sessionCookie = ""

    private val baseHeaders get() = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private val authedHeaders get() = if (sessionCookie.isNotBlank()) {
        baseHeaders + mapOf("Cookie" to sessionCookie)
    } else {
        baseHeaders
    }

    override val mainPage = mainPageOf(
        "$mainUrl/videos/latest" to "Latest Update",
        "$mainUrl/videos/trending" to "Trending",
        "$mainUrl/videos/top" to "Most Viewed",

        "$mainUrl/videos/category/1" to "Film Movie",
        "$mainUrl/videos/category/3" to "TV Series",
        "$mainUrl/videos/category/4" to "Anime Movie",
        "$mainUrl/videos/category/5" to "Anime Series",
        "$mainUrl/videos/category/790" to "Shorts",
        "$mainUrl/videos/category/791" to "Uncategory",
        "$mainUrl/videos/category/other" to "Other",

        // Curated search sections, because UVideo/Dubbindo exposes only a small fixed category menu.
        "search:Dubbing Indonesia" to "Dubbing Indonesia",
        "search:Dub Indo" to "Dub Indo",
        "search:Bahasa Indonesia" to "Bahasa Indonesia",
        "search:Disney" to "Disney",
        "search:Pixar" to "Pixar",
        "search:Nickelodeon" to "Nickelodeon",
        "search:Cartoon Network" to "Cartoon Network",
        "search:RTV" to "RTV",
        "search:Indosiar" to "Indosiar",
        "search:Spacetoon" to "Spacetoon",
        "search:Anime" to "Anime",
        "search:Doraemon" to "Doraemon",
        "search:Digimon" to "Digimon",
        "search:Naruto" to "Naruto",
        "search:Bleach" to "Bleach",
        "search:Tom and Jerry" to "Tom and Jerry",
        "search:Tayo" to "Tayo",
        "search:Cars" to "Cars",
        "search:Ultraman" to "Ultraman",
        "search:Kamen Rider" to "Kamen Rider"
    )

    private fun parseCookiePair(header: String): Pair<String, String>? {
        val part = header.split(";").firstOrNull()?.trim() ?: return null
        val eq = part.indexOf('=')
        if (eq < 0) return null
        return part.substring(0, eq).trim() to part.substring(eq + 1).trim()
    }

    private suspend fun doLogin(): Boolean {
        if (username.isBlank() || password.isBlank()) return false

        val getResp = app.get("$mainUrl/login", headers = baseHeaders)
        val initCookies = getResp.headers
            .filter { it.first.equals("set-cookie", ignoreCase = true) }
            .mapNotNull { parseCookiePair(it.second) }
            .toMap()
            .toMutableMap()

        val phpSessId = initCookies["PHPSESSID"].orEmpty()

        val postResp = app.post(
            "$mainUrl/login",
            data = mapOf(
                "username" to username,
                "password" to password,
                "remember_device" to "on"
            ),
            headers = baseHeaders + mapOf(
                "Cookie" to if (phpSessId.isNotBlank()) "PHPSESSID=$phpSessId" else "",
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/login"
            ),
            allowRedirects = false
        )

        val allCookies = initCookies + postResp.headers
            .filter { it.first.equals("set-cookie", ignoreCase = true) }
            .mapNotNull { parseCookiePair(it.second) }
            .toMap()

        return if (!allCookies["user_id"].isNullOrBlank()) {
            sessionCookie = allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            true
        } else {
            false
        }
    }

    private suspend fun ensureSession() {
        if (sessionCookie.isNotBlank()) return
        doLogin()
    }

    private suspend fun subscribeChannel(document: Document, pageUrl: String): Boolean {
        if (sessionCookie.isBlank()) return false

        val channelId = document
            .selectFirst("button.btn-subscribe[data-id]")?.attr("data-id")?.trim()
            ?: document.selectFirst(".subscribe-btn-container button[data-id]")?.attr("data-id")?.trim()
            ?: document.selectFirst("button[onclick*=PT_Subscribe]")
                ?.attr("onclick")
                ?.let { Regex("""PT_Subscribe\((\d+)""").find(it)?.groupValues?.get(1) }
            ?: document.selectFirst("input#profile-id")?.attr("value")?.trim()
            ?: return false

        if (channelId.isBlank()) return false

        val mainSession = document
            .selectFirst("input.main_session")
            ?.attr("value")
            ?.trim()
            .orEmpty()

        val subscribeUrl = if (mainSession.isNotBlank()) {
            "$mainUrl/aj/subscribe?hash=$mainSession"
        } else {
            "$mainUrl/aj/subscribe"
        }

        val resp = app.post(
            subscribeUrl,
            data = mapOf("user_id" to channelId),
            headers = authedHeaders + mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to pageUrl,
                "Origin" to mainUrl
            )
        )

        if (!resp.isSuccessful) return false

        val muteUrl = if (mainSession.isNotBlank()) {
            "$mainUrl/aj/user/notify?hash=$mainSession"
        } else {
            "$mainUrl/aj/user/notify"
        }

        runCatching {
            app.post(
                muteUrl,
                data = mapOf("user_id" to channelId),
                headers = authedHeaders + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pageUrl,
                    "Origin" to mainUrl
                )
            )
        }

        LicenseClient.trackActivity(name, "PLAY")
        return true
    }

    private fun isSubscribeWall(document: Document): Boolean {
        val playerArea = document.selectFirst("div.video-processing, div.video-player, div.pt_video_player")
            ?.text()
            .orEmpty()

        return playerArea.contains("subscribe to watch", ignoreCase = true) ||
            playerArea.contains("subscribe", ignoreCase = true) &&
            parseVideoSources(document).isEmpty()
    }

    private fun isVideoInQueue(document: Document): Boolean =
        document.selectFirst("div.pt_video_player div.video-processing, div.video-processing") != null

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        ensureSession()

        if (request.data.startsWith("search:")) {
            val query = request.data.removePrefix("search:").trim()
            val items = searchPage(query, page)
            return newHomePageResponse(
                HomePageList(request.name, items, isHorizontalImages = true),
                hasNext = items.isNotEmpty()
            )
        }

        val document = app.get(
            buildPagedUrl(request.data, page),
            headers = authedHeaders
        ).document

        val defaultType = typeFromSection(request.name, request.data)
        val home = document
            .select(
                "div.video-wrapper, div.video-list, div.video-list-wrapper, " +
                    "div.related-video-wrapper, div.video-item, div.col-md-3, div.col-sm-4, div.col-xs-6"
            )
            .mapNotNull { it.toSearchResult(defaultType) }
            .distinctBy { it.url }
            .filter { it.name.isNotBlank() }

        val hasNext = document.select(
            "a[href*='page_id=${page + 1}'], ul.pagination a:contains(${page + 1}), .pagination a:contains(Next)"
        ).isNotEmpty() || home.isNotEmpty()

        return newHomePageResponse(
            HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private fun buildPagedUrl(rawUrl: String, page: Int): String {
        val safePage = if (page < 1) 1 else page
        val cleanUrl = rawUrl.trimEnd()
        return when {
            cleanUrl.contains("page_id=", true) -> cleanUrl.replace(Regex("""page_id=\d+"""), "page_id=$safePage")
            cleanUrl.contains("?") -> "$cleanUrl&page_id=$safePage"
            else -> "$cleanUrl?page_id=$safePage"
        }
    }

    private fun typeFromSection(name: String, url: String): TvType {
        val text = "$name $url".lowercase()
        return when {
            text.contains("anime series") -> TvType.Anime
            text.contains("anime movie") -> TvType.AnimeMovie
            text.contains("anime") -> TvType.Anime
            text.contains("tv series") || text.contains("series") -> TvType.TvSeries
            text.contains("short") || text.contains("other") || text.contains("uncategory") -> TvType.Cartoon
            else -> TvType.Movie
        }
    }

    private fun Element.toSearchResult(defaultType: TvType = TvType.Movie): SearchResponse? {
        val anchor = selectFirst(
            "div.video-title h4 a[href], div.video-title a[href], div.video-thumb a[href], " +
                "div.video-list-title h4 a[href], div.video-list-image a[href], a[href*='/watch/'], a[href*='/videos/watch/'], a[href]"
        ) ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains(mainUrl, true) && !href.startsWith("http", true)) return null
        if (href.contains("/user/", true) || href.contains("/channel/", true) || href.contains("/login", true)) return null

        val title = selectFirst("div.video-title h4, div.video-list-title h4, h4, h3, .title")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.text().trim().takeIf { it.isNotBlank() }
            ?: return null

        val cleanTitle = title.cleanTitle()
        if (cleanTitle.isBlank()) return null

        val poster = getPosterUrl()
        val inferredType = inferType(cleanTitle, href, defaultType)

        return when (inferredType) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(cleanTitle, href, inferredType) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to mainUrl)
            }
            else -> newTvSeriesSearchResponse(cleanTitle, href, inferredType) {
                posterUrl = poster
                posterHeaders = mapOf("Referer" to mainUrl)
            }
        }
    }

    private fun inferType(title: String, url: String, defaultType: TvType): TvType {
        val text = "$title $url".lowercase()
        return when {
            text.contains("anime") && (text.contains("movie") || text.contains("film")) -> TvType.AnimeMovie
            text.contains("anime") -> TvType.Anime
            text.contains("episode") || text.contains("eps") || text.contains("s0") || text.contains("season") -> TvType.TvSeries
            text.contains("movie") || text.contains("film") -> TvType.Movie
            else -> defaultType
        }
    }

    private fun Element.getPosterUrl(): String? {
        val img = selectFirst("div.video-thumb img, div.video-list-image img, img")
        return fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )
    }

    private fun Element.toRelatedResult(): SearchResponse? {
        return toSearchResult(TvType.Movie)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        ensureSession()
        if (query.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val page = searchPage(query, i)
                .filterNot { item -> results.any { it.url == item.url } }

            if (page.isEmpty()) break
            results.addAll(page)
        }

        return results
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get(
            "$mainUrl/search?keyword=$encoded&page_id=$page",
            headers = authedHeaders
        ).document

        return document
            .select("div.video-list, div.video-wrapper, div.video-list-wrapper, div.video-item")
            .mapNotNull { it.toSearchResult(TvType.Movie) }
            .distinctBy { it.url }
    }

    private fun parseVideoSources(doc: Document): List<Video> {
        val fromSources = doc.select("video#my-video source, video source, source[src], video[src]").mapNotNull { el ->
            val src = el.attr("src").trim()
                .ifBlank { el.attr("data-src").trim() }
                .ifBlank { return@mapNotNull null }

            Video(
                src = fixUrlNull(src) ?: src,
                res = el.attr("res")
                    .ifBlank { el.attr("data-quality") }
                    .ifBlank { el.attr("label") }
                    .replace(Regex("[^0-9]"), ""),
                type = el.attr("type").ifBlank { if (src.contains(".m3u8", true)) "application/x-mpegURL" else "video/mp4" }
            )
        }

        val fromHtml = Regex("""https?://[^"'\\<>\s]+?\.(?:m3u8|mp4|mpd)(?:\?[^"'\\<>\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(doc.html().replace("\\/", "/").replace("&amp;", "&"))
            .map { it.value }
            .distinct()
            .map { link ->
                Video(
                    src = link,
                    res = link.detectQuality()?.toString(),
                    type = when {
                        link.contains(".m3u8", true) -> "application/x-mpegURL"
                        link.contains(".mpd", true) -> "application/dash+xml"
                        else -> "video/mp4"
                    }
                )
            }
            .toList()

        return (fromSources + fromHtml)
            .filter { !it.src.isNullOrBlank() }
            .distinctBy { it.src }
    }

    private suspend fun fetchVideoSources(url: String): Pair<Document, List<Video>> {
        var doc = app.get(url, headers = authedHeaders).document
        var videos = parseVideoSources(doc)

        if (videos.isNotEmpty()) return doc to videos

        if (isSubscribeWall(doc)) {
            subscribeChannel(doc, url)
            doc = app.get(url, headers = authedHeaders).document
            videos = parseVideoSources(doc)
        }

        if (videos.isNotEmpty()) return doc to videos

        sessionCookie = ""
        if (doLogin()) {
            doc = app.get(url, headers = authedHeaders).document
            videos = parseVideoSources(doc)

            if (videos.isEmpty() && isSubscribeWall(doc)) {
                subscribeChannel(doc, url)
                doc = app.get(url, headers = authedHeaders).document
                videos = parseVideoSources(doc)
            }
        }

        return doc to videos
    }

    override suspend fun load(url: String): LoadResponse? {
        LicenseClient.checkLicense(name, "LOAD", url)
        ensureSession()

        val document = app.get(url, headers = authedHeaders).document

        val title = (
            document.selectFirst("meta[name=title]")?.attr("content")
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1, h2")?.text()
                ?: document.title()
            )
            .replace(" | UVideo", "")
            .trim()
            .cleanTitle()

        if (title.isEmpty()) return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("video[poster]")?.attr("poster")

        val tags = document.select("div.pt_categories li a, a[href*='/videos/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return if (url.contains("/articles/read/", true)) {
            val description = document.selectFirst("div.read-article-description article")?.text()
            val videoLinks = document.select("div.read-article-text a[href]")
                .map { it.attr("href") }
                .mapNotNull { fixUrlNull(it) }
                .filter { it.isNotBlank() }

            val recommendations = document.select("div.related-video-wrapper")
                .mapNotNull { it.toRelatedResult() }
                .distinctBy { it.url }

            newMovieLoadResponse(title, url, TvType.Movie, videoLinks.toJson()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val description = document.select("div.watch-video-description p, div.video-description p, div.description p")
                .text()
                .replace("\u2063", "")
                .trim()
                .takeIf { it.isNotBlank() }

            val recommendations = document.select("div.related-video-wrapper, div.video-wrapper")
                .mapNotNull { it.toRelatedResult() }
                .distinctBy { it.url }
                .filterNot { it.url == url }

            if (isVideoInQueue(document)) {
                return newMovieLoadResponse(title, url, TvType.Movie, "[]") {
                    posterUrl = poster
                    plot = "⏳ Video ini sedang dalam antrian pemrosesan.\nHarap buka kembali dalam beberapa menit atau jam."
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            val (_, videos) = fetchVideoSources(url)
            val type = inferType(title, url, TvType.Movie)

            newMovieLoadResponse(title, url, type, videos.toJson()) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private fun isPresignedS3(url: String): Boolean {
        return url.contains("X-Amz-Signature", ignoreCase = true) ||
            url.contains("X-Amz-Credential", ignoreCase = true) ||
            url.contains("wasabisys.com", ignoreCase = true) ||
            url.contains("amazonaws.com", ignoreCase = true)
    }

    private suspend fun resolveVideoUrl(src: String): String {
        if (isPresignedS3(src)) return src
        if (!src.contains("s3.dubbindo.my.id", true)) return src

        return try {
            val resp = app.get(
                src,
                headers = authedHeaders,
                allowRedirects = false
            )
            val location = resp.headers
                .firstOrNull { it.first.equals("location", ignoreCase = true) }
                ?.second
            if (!location.isNullOrBlank()) location else src
        } catch (_: Exception) {
            src
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val streamHeaders = authedHeaders + mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )

        val videos = parseVideoList(data)
        if (videos.isNotEmpty()) {
            var delivered = false
            val emitted = linkedSetOf<String>()

            videos.forEach { video ->
                val rawSrc = video.src?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val src = resolveVideoUrl(rawSrc)
                if (!emitted.add(src.substringBefore("?X-Amz-Signature"))) return@forEach

                val quality = qualityFromString(video.res ?: src)

                if (isDirectVideo(src, video.type)) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Dubbindo ${video.res?.takeIf { it.isNotBlank() } ?: "Auto"}",
                            src,
                            INFER_TYPE
                        ) {
                            this.quality = quality
                            this.referer = "$mainUrl/"
                            this.headers = if (isPresignedS3(src)) emptyMap() else streamHeaders
                        }
                    )
                    delivered = true
                } else {
                    val success = loadExtractor(src, mainUrl, subtitleCallback, callback)
                    if (success) delivered = true
                }
            }

            return delivered
        }

        val urls = parseStringList(data)
        if (urls.isNotEmpty()) {
            var delivered = false
            urls.filter { it.isNotBlank() }.forEach { url ->
                val success = loadExtractor(url, mainUrl, subtitleCallback, callback)
                if (success) delivered = true
            }
            return delivered
        }

        return false
    }


    private fun parseVideoList(data: String): List<Video> {
        parseJsonVideoList(data).takeIf { it.isNotEmpty() }?.let { return it }

        return Regex("""["'](?:src|file|url)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(data.replace("""\/""", "/").replace("&amp;", "&"))
            .mapNotNull { match ->
                val src = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Video(
                    src = fixUrlNull(src) ?: src,
                    res = src.detectQuality()?.toString(),
                    type = when {
                        src.contains(".m3u8", true) -> "application/x-mpegURL"
                        src.contains(".mpd", true) -> "application/dash+xml"
                        else -> "video/mp4"
                    }
                )
            }
            .distinctBy { it.src }
            .toList()
    }

    private fun parseStringList(data: String): List<String> {
        return try {
            val array = JSONArray(data)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseJsonVideoList(data: String): List<Video> {
        return try {
            val trimmed = data.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.toVideo()
                }.distinctBy { it.src }
            } else {
                JSONObject(trimmed).toVideo()?.let { listOf(it) } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JSONObject.toVideo(): Video? {
        val src = optString("src").trim().takeIf { it.isNotBlank() }
            ?: optString("file").trim().takeIf { it.isNotBlank() }
            ?: optString("url").trim().takeIf { it.isNotBlank() }
            ?: return null

        return Video(
            src = fixUrlNull(src) ?: src,
            res = optString("res").trim().takeIf { it.isNotBlank() }
                ?: optString("label").trim().takeIf { it.isNotBlank() }
                ?: optString("quality").trim().takeIf { it.isNotBlank() },
            type = optString("type").trim().takeIf { it.isNotBlank() }
        )
    }

    private fun isDirectVideo(url: String, type: String?): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mpd", true) ||
            url.contains(".mp4", true) ||
            type.orEmpty().startsWith("video/", true) ||
            type.equals("application/x-mpegURL", true) ||
            type.equals("application/vnd.apple.mpegurl", true) ||
            type.equals("application/dash+xml", true)
    }

    private fun qualityFromString(value: String?): Int {
        val quality = value?.filter { it.isDigit() }?.toIntOrNull()
        return when (quality ?: value?.detectQuality()) {
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

    private fun String.cleanTitle(): String {
        return replace(Regex("""""\s+"""""), " ")
            .replace("&amp;", "&")
            .trim()
    }

    data class Video(
        val src: String? = null,
        val res: String? = null,
        val type: String? = null,
    )
}
