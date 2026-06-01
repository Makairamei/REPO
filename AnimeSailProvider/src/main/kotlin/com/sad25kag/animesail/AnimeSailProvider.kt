package com.sad25kag.animesail

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    private val turnstileInterceptor = TurnstileInterceptor()

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = turnstileInterceptor,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
            ),
            referer = ref
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/page/" to "Movie Terbaru",
        "$mainUrl/daftar-anime/page/" to "Daftar Anime",

        "$mainUrl/genres/action/page/" to "Action",
        "$mainUrl/genres/adventure/page/" to "Adventure",
        "$mainUrl/genres/comedy/page/" to "Comedy",
        "$mainUrl/genres/drama/page/" to "Drama",
        "$mainUrl/genres/fantasy/page/" to "Fantasy",
        "$mainUrl/genres/romance/page/" to "Romance",
        "$mainUrl/genres/school/page/" to "School",
        "$mainUrl/genres/slice-of-life/page/" to "Slice of Life",
        "$mainUrl/genres/shounen/page/" to "Shounen",
        "$mainUrl/genres/shoujo/page/" to "Shoujo",
        "$mainUrl/genres/seinen/page/" to "Seinen",
        "$mainUrl/genres/josei/page/" to "Josei",
        "$mainUrl/genres/isekai/page/" to "Isekai",
        "$mainUrl/genres/reincarnation/page/" to "Reincarnation",
        "$mainUrl/genres/supernatural/page/" to "Supernatural",
        "$mainUrl/genres/super-power/page/" to "Super Power",
        "$mainUrl/genres/magic/page/" to "Magic",
        "$mainUrl/genres/mystery/page/" to "Mystery",
        "$mainUrl/genres/detective/page/" to "Detective",
        "$mainUrl/genres/police/page/" to "Police",
        "$mainUrl/genres/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/genres/mecha/page/" to "Mecha",
        "$mainUrl/genres/sports/page/" to "Sports",
        "$mainUrl/genres/music/page/" to "Music",
        "$mainUrl/genres/historical/page/" to "Historical",
        "$mainUrl/genres/samurai/page/" to "Samurai",
        "$mainUrl/genres/martial-arts/page/" to "Martial Arts",
        "$mainUrl/genres/mythology/page/" to "Mythology",
        "$mainUrl/genres/urban-fantasy/page/" to "Urban Fantasy",
        "$mainUrl/genres/harem/page/" to "Harem",
        "$mainUrl/genres/ecchi/page/" to "Ecchi",
        "$mainUrl/genres/gore/page/" to "Gore",
        "$mainUrl/genres/horror/page/" to "Horror",
        "$mainUrl/genres/thriller/page/" to "Thriller",
        "$mainUrl/genres/psychological/page/" to "Psychological",
        "$mainUrl/genres/suspense/page/" to "Suspense",
        "$mainUrl/genres/parody/page/" to "Parody"
    )

    override suspend fun getMainPage(page: Int, pageRequest: MainPageRequest): HomePageResponse {
        val document = request(pageRequest.data + page).document
        val home = document.select("div.listupd article, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            pageRequest.name,
            home,
            hasNext = home.isNotEmpty()
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore(
                    "-episode"
                )

                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkElement = selectFirst(
            ".tt > h2 > a, h2.entry-title > a, h2 > a, h3 > a, a[rel=bookmark], a[href]"
        ) ?: return null

        val rawHref = fixUrlNull(
            linkElement.attr("href").ifBlank {
                selectFirst("a[href]")?.attr("href")
            }
        ) ?: return null
        val href = getProperAnimeLink(rawHref)

        val rawTitle = listOfNotNull(
            selectFirst(".tt > h2")?.text(),
            selectFirst("h2.entry-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            linkElement.attr("title").takeIf { it.isNotBlank() },
            select("a[href]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.equals("Next", true) && !it.equals("Previous", true) }
                .maxByOrNull { it.length }
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("div.limit img, img.wp-post-image, img.attachment-post-thumbnail, img")?.let { img ->
                img.attr("abs:data-src").ifBlank {
                    img.attr("abs:data-lazy-src").ifBlank {
                        img.attr("abs:src").ifBlank {
                            img.attr("data-src").ifBlank {
                                img.attr("data-lazy-src").ifBlank {
                                    img.attr("src")
                                }
                            }
                        }
                    }
                }
            }
        )
        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val typeText = listOfNotNull(
            this.selectFirst(".tt > span")?.text(),
            this.selectFirst(".typez")?.text(),
            this.text().takeIf { it.contains("·") }
        ).joinToString(" ")
        val type = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val document = request("$mainUrl/?s=$encoded").document

        return document.select("div.listupd article, article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = request(url).document

        val title = document.selectFirst("h1.entry-title")?.text().toString()
            .replace("Subtitle Indonesia", "").trim()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = getType(document.select("tbody th:contains(Tipe)").next().text().lowercase())
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val episodes = document.select("ul.daftar > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()
            val episode =
                Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) { this.episode = episode }
        }.reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                getStatus(document.select("tbody th:contains(Status)").next().text().trim())
            plot = document.selectFirst("div.entry-content > p")?.text()
            this.tags =
                document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = request(data).document
        val playerPath = "$mainUrl/utils/player/"
        val visitedUrls = linkedSetOf<String>()

        document.select(".mobius > .mirror > option, .mobius option, select.mirror option").amap { element ->
            safeApiCall {
                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)

                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                val candidates = element.extractMirrorCandidates()
                if (candidates.isEmpty()) return@safeApiCall
                candidates.forEach { candidate ->
                    resolveMirrorLink(
                        rawUrl = candidate,
                        referer = data,
                        playerPath = playerPath,
                        serverName = serverName,
                        quality = quality,
                        visitedUrls = visitedUrls,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
        }

        return true
    }

    private suspend fun resolveMirrorLink(
        rawUrl: String,
        referer: String,
        playerPath: String,
        serverName: String,
        quality: Int?,
        visitedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalized = normalizeMirrorUrl(rawUrl) ?: return
        if (normalized.contains("statistic", true)) return
        if (!visitedUrls.add(normalized)) return

        when {
            isDirectMediaUrl(normalized) -> {
                emitDirectMediaLink(normalized, serverName, quality, referer, callback)
            }

            normalized.contains("${playerPath}popup", true) -> {
                val encodedUrl = normalized.substringAfter("url=", "").substringBefore("&")
                if (encodedUrl.isBlank()) return
                val realUrl = runCatching { URLDecoder.decode(encodedUrl, "UTF-8") }.getOrNull() ?: return
                resolveMirrorLink(
                    rawUrl = realUrl,
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains("aghanim.xyz/tools/redirect/", true) -> {
                val id = normalized.substringAfter("id=").substringBefore("&token")
                if (id.isBlank()) return
                resolveMirrorLink(
                    rawUrl = "https://rasa-cintaku-semakin-berantai.xyz/v/$id",
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains("player-kodir.aghanim.xyz", true) -> {
                if (tryPlayerKodirDirect(normalized, serverName, quality, subtitleCallback, callback)) return
                val response = request(normalized, ref = referer)
                val text = response.text
                val playerDoc = response.document

                val nestedLinks = linkedSetOf<String>()
                playerDoc.select("script[src]").forEach { script ->
                    normalizeUrlFromBase(script.attr("src"), normalized)?.let(nestedLinks::add)
                }
                nestedLinks.addAll(extractCandidatesFromText(text, normalized))

                if (nestedLinks.isEmpty()) {
                    loadFixedExtractor(
                        url = normalized,
                        serverName = serverName,
                        quality = quality,
                        referer = referer,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    return
                }

                nestedLinks.forEach { nested ->
                    resolveMirrorLink(
                        rawUrl = nested,
                        referer = normalized,
                        playerPath = playerPath,
                        serverName = serverName,
                        quality = quality,
                        visitedUrls = visitedUrls,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }

            normalized.contains("${playerPath}kodir2", true) ||
                normalized.contains("${playerPath}framezilla", true) ||
                normalized.contains("uservideo.xyz", true) ||
                normalized.contains(playerPath, true) -> {
                val response = request(normalized, ref = referer)
                val text = response.text
                val playerDoc = response.document

                if (isCustomManagedServer(serverName) && tryPassMd5PatternDirect(
                        normalized,
                        serverName,
                        quality,
                        referer,
                        callback,
                        prefetchedPageText = text
                    )
                ) {
                    return
                }

                val nestedLinks = linkedSetOf<String>()

                val packedHtml = text.substringAfter("= `", "").substringBefore("`;", "")
                if (packedHtml.isNotBlank()) {
                    nestedLinks.addAll(
                        Jsoup.parse(packedHtml)
                            .select("source[src], video[src], iframe[src], a[href]")
                            .mapNotNull { it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank) }
                    )
                }

                nestedLinks.addAll(
                    playerDoc.select("source[src], video[src], iframe[src], a[href]")
                        .mapNotNull { it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank) }
                )

                Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .findAll(text)
                    .map { it.value.trim() }
                    .filter { shouldFollowNestedLink(it, playerPath) }
                    .forEach { nestedLinks.add(it) }

                if (nestedLinks.isEmpty()) {
                    loadFixedExtractor(
                        url = normalized,
                        serverName = serverName,
                        quality = quality,
                        referer = referer,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    return
                }

                nestedLinks.forEach { nested ->
                    resolveMirrorLink(
                        rawUrl = nested,
                        referer = normalized,
                        playerPath = playerPath,
                        serverName = serverName,
                        quality = quality,
                        visitedUrls = visitedUrls,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }

            else -> {
                loadFixedExtractor(
                    url = normalized,
                    serverName = serverName,
                    quality = quality,
                    referer = referer,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }
    }

    private fun Element.extractMirrorCandidates(): List<String> {
        val rawCandidates = listOf(
            attr("data-em"),
            attr("value"),
            attr("data-iframe"),
            attr("data-url"),
            attr("data-src")
        ).filter { it.isNotBlank() }

        val results = linkedSetOf<String>()
        rawCandidates.forEach { encoded ->
            results.addAll(decodeMirrorCandidates(encoded))
        }
        return results.toList()
    }

    private fun decodeMirrorCandidates(encodedData: String): List<String> {
        if (encodedData.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        val clean = encodedData.trim().replace("\\u0026", "&")

        fun addUrl(raw: String?) {
            normalizeMirrorUrl(raw)?.let { candidates.add(it) }
        }

        fun parseBlob(blob: String) {
            if (blob.isBlank()) return
            addUrl(blob)
            val doc = Jsoup.parse(blob)
            doc.select("iframe[src], source[src], video[src], a[href]").forEach { el ->
                addUrl(el.attr("src").ifBlank { el.attr("href") })
            }
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                .findAll(blob)
                .forEach { addUrl(it.value) }
        }

        parseBlob(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let(::parseBlob)
        runCatching { base64Decode(clean.replace("\\s".toRegex(), "")) }.getOrNull()?.let(::parseBlob)
        return candidates.toList()
    }

    private fun normalizeMirrorUrl(raw: String?): String? {
        return normalizeUrlFromBase(raw, mainUrl)
    }

    private fun normalizeUrlFromBase(raw: String?, baseUrl: String?): String? {
        val clean = raw?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.removePrefix("'")
            ?.removeSuffix("'")
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.replace("\\u0026", "&")
            ?.trim()
            ?: return null
        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) {
            return null
        }

        fun resolveWithBase(path: String): String? {
            if (baseUrl.isNullOrBlank()) return null
            return runCatching { URI(baseUrl).resolve(path).toString() }.getOrNull()
        }

        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> resolveWithBase(clean) ?: runCatching { fixUrl(clean) }.getOrNull()
            else -> resolveWithBase(clean)
        }
    }

    private fun shouldFollowNestedLink(url: String, playerPath: String): Boolean {
        val lower = url.lowercase()
        if (isDirectMediaUrl(lower)) return true
        if (lower.contains(playerPath.lowercase())) return true
        val hostHints = listOf(
            "yourupload",
            "pixeldrain",
            "pompom",
            "pancal",
            "myvidplay",
            "mixdrop",
            "mp4upload",
            "uservideo",
            "aghanim"
        )
        return hostHints.any { lower.contains(it) }
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url)
    }

    private suspend fun emitDirectMediaLink(
        mediaUrl: String,
        serverName: String,
        quality: Int?,
        refererHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val isMp4UploadDirect = mediaUrl.contains("mp4upload.com", ignoreCase = true)
        val directReferer = if (isMp4UploadDirect) "https://www.mp4upload.com/" else (refererHint ?: mainUrl)
        val directHeaders = if (isMp4UploadDirect) {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer,
                "Origin" to "https://www.mp4upload.com"
            )
        } else {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer
            )
        }

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = mediaUrl,
                type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = directReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = directHeaders
            }
        )
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = normalizeYourUploadUrl(url)
        if (isCustomManagedHost(normalizedUrl) || isCustomManagedServer(serverName)) {
            tryCustomLocalExtractor(normalizedUrl, serverName, quality, referer, callback)
            return
        }
        if (tryCustomLocalExtractor(normalizedUrl, serverName, quality, referer, callback)) return
        if (tryLoadMp4UploadDirect(normalizedUrl, serverName, quality, callback)) return

        loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
            val finalName =
                if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"

            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = finalName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.takeIf { it.isNotBlank() } ?: referer ?: mainUrl
                        this.quality =
                            if (link.type == ExtractorLinkType.M3U8) link.quality else quality
                                ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun normalizeYourUploadUrl(url: String): String {
        if (!url.contains("yourupload.com", true)) return url
        return if (url.contains("/watch/", true)) {
            url.replace("/watch/", "/embed/", true)
        } else {
            url
        }
    }

    private fun isCustomManagedHost(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("pixeldrain.com") ||
            lower.contains("pompom") ||
            lower.contains("pancal") ||
            lower.contains("myvidplay.com")
    }

    private fun isCustomManagedServer(serverName: String): Boolean {
        val lower = serverName.lowercase()
        return lower.contains("pompom") || lower.contains("pancal")
    }

    private suspend fun tryCustomLocalExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lower = url.lowercase()
        return when {
            lower.contains("myvidplay.com") -> {
                tryPassMd5PatternDirect(url, serverName, quality, referer, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            lower.contains("pixeldrain.com") -> {
                tryPixeldrainDirect(url, serverName, quality, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            lower.contains("yourupload.com") -> {
                tryMirrorCrawlerExtractor(normalizeYourUploadUrl(url), serverName, quality, referer, callback)
            }
            lower.contains("pompom") || lower.contains("pancal") -> {
                tryPassMd5PatternDirect(url, serverName, quality, referer, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            isCustomManagedServer(serverName) -> {
                tryPassMd5PatternDirect(url, serverName, quality, referer, callback) ||
                    tryMirrorCrawlerExtractor(url, serverName, quality, referer, callback)
            }
            else -> false
        }
    }

    private fun getQueryParam(url: String, name: String): String? {
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        return rawQuery.split("&")
            .mapNotNull { item ->
                val key = item.substringBefore("=", "")
                val value = item.substringAfter("=", "")
                if (key == name && value.isNotBlank()) {
                    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrElse { value }
                } else null
            }
            .firstOrNull()
    }

    private suspend fun tryPlayerKodirDirect(
        url: String,
        serverName: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = getQueryParam(url, "id") ?: return false
        val token = getQueryParam(url, "token") ?: return false
        val playerOrigin = "https://player-kodir.aghanim.xyz"
        val apiUrl = "$playerOrigin?api=init&id=$id&token=$token"
        val apiResponse = runCatching {
            app.get(
                apiUrl,
                referer = url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to url,
                    "Origin" to playerOrigin,
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text
        }.getOrNull() ?: return false

        val player = runCatching {
            JSONObject(apiResponse)
                .optJSONObject("data")
                ?.optJSONObject("player")
        }.getOrNull() ?: return false

        var emitted = false
        val sources = player.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val sourceUrl = source.optString("url").takeIf { it.isNotBlank() } ?: continue
                val sourceLabel = source.optString("name").takeIf { it.isNotBlank() } ?: "Kodir"
                val metadata = source.optJSONObject("metadata")
                val accessToken = metadata?.optString("access_token")?.takeIf { it.isNotBlank() }
                val isDriveApi = sourceUrl.contains("googleapis.com/drive", ignoreCase = true)
                val linkHeaders = mutableMapOf("User-Agent" to USER_AGENT)

                if (isDriveApi) {
                    linkHeaders["Origin"] = playerOrigin
                    if (!accessToken.isNullOrBlank()) {
                        linkHeaders["Authorization"] = "Bearer $accessToken"
                    }
                }

                callback.invoke(
                    newExtractorLink(
                        source = "AnimeSail Kodir",
                        name = "$serverName - $sourceLabel",
                        url = sourceUrl,
                        type = if (sourceUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = if (isDriveApi) playerOrigin else ""
                        this.quality = quality ?: Qualities.Unknown.value
                        this.headers = linkHeaders
                    }
                )
                emitted = true
            }
        }

        val mirrors = player.optJSONArray("mirrors")
        if (mirrors != null) {
            for (i in 0 until mirrors.length()) {
                val mirror = mirrors.optJSONObject(i) ?: continue
                if (!mirror.optBoolean("alive", true)) continue
                val embedUrl = mirror.optString("embedUrl").takeIf { it.isNotBlank() } ?: continue
                val mirrorName = mirror.optString("service").takeIf { it.isNotBlank() } ?: serverName
                loadFixedExtractor(
                    url = embedUrl,
                    serverName = "$serverName - $mirrorName",
                    quality = quality,
                    referer = playerOrigin,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        return emitted
    }

    private suspend fun tryPixeldrainDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = listOf(
            Regex("""pixeldrain\.com/(?:u|l)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            Regex("""pixeldrain\.com/api/file/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { rgx ->
            rgx.find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } ?: return false

        val directUrl = "https://pixeldrain.com/api/file/$id?download"
        val pixeldrainReferer = "https://pixeldrain.com/"
        val probe = runCatching {
            app.get(
                directUrl,
                referer = pixeldrainReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pixeldrainReferer,
                    "Origin" to "https://pixeldrain.com",
                    "Range" to "bytes=0-1023"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (contentType.contains("text/html")) return false

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = pixeldrainReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pixeldrainReferer,
                    "Origin" to "https://pixeldrain.com"
                )
            }
        )
        return true
    }

    private suspend fun tryPassMd5PatternDirect(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        prefetchedPageText: String? = null
    ): Boolean {
        val pageUrl = normalizeUrlFromBase(url, referer ?: mainUrl) ?: return false
        val pageText = prefetchedPageText ?: runCatching {
            request(pageUrl, ref = referer ?: mainUrl).text
        }.getOrNull() ?: return false

        val passPath = Regex("""/pass_md5/[^"'\\s<]+""", RegexOption.IGNORE_CASE)
            .find(pageText)
            ?.value
            ?.trim()
            ?: return false

        val passUrl = normalizeUrlFromBase(passPath, pageUrl) ?: return false
        val token = passPath.substringAfterLast("/").takeIf { it.isNotBlank() }
            ?: Regex("""token=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
            ?: return false

        val passResponse = runCatching { request(passUrl, ref = pageUrl) }.getOrNull() ?: return false

        val baseStream = passResponse.text
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: return false

        val resolvedBaseStream = normalizeUrlFromBase(baseStream, pageUrl) ?: return false
        val randomPad = randomAlphaNum(10)
        val expiry = System.currentTimeMillis()
        val separator = if (resolvedBaseStream.contains("?")) "&" else "?"
        val finalUrl = "${resolvedBaseStream}${randomPad}${separator}token=$token&expiry=$expiry"

        val probe = runCatching {
            app.get(
                finalUrl,
                referer = pageUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl,
                    "Range" to "bytes=0-4095"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (!(contentType.contains("video") || contentType.contains("octet-stream"))) return false

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                this.referer = pageUrl
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to pageUrl
                )
            }
        )
        return true
    }

    private suspend fun tryMirrorCrawlerExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val queue = ArrayDeque<Pair<String, String?>>()
        val visited = linkedSetOf<String>()
        queue.add(url to referer)
        var safety = 0

        while (queue.isNotEmpty() && safety++ < 12) {
            val (currentUrl, currentReferer) = queue.removeFirst()
            val current = normalizeUrlFromBase(currentUrl, currentReferer ?: mainUrl) ?: continue
            if (!visited.add(current)) continue

            if (isDirectMediaUrl(current)) {
                emitDirectMediaLink(current, serverName, quality, currentReferer ?: referer, callback)
                return true
            }

            val response = runCatching {
                app.get(
                    current,
                    referer = currentReferer ?: mainUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to (currentReferer ?: mainUrl)
                    )
                )
            }.getOrNull() ?: continue

            val discovered = linkedSetOf<String>()
            response.document.select("source[src], video[src], iframe[src], a[href], script[src]").forEach { el ->
                val raw = el.attr("src").ifBlank { el.attr("href") }
                normalizeUrlFromBase(raw, current)?.let(discovered::add)
            }
            discovered.addAll(extractCandidatesFromText(response.text, current))

            response.document.select("script").forEach { script ->
                val data = script.data().trim()
                if (data.isBlank()) return@forEach
                discovered.addAll(extractCandidatesFromText(data, current))
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    runCatching { getAndUnpack(data) }
                        .getOrNull()
                        ?.let { unpacked -> discovered.addAll(extractCandidatesFromText(unpacked, current)) }
                }
            }

            val direct = discovered.firstOrNull { isDirectMediaUrl(it) }
            if (!direct.isNullOrBlank()) {
                emitDirectMediaLink(direct, serverName, quality, current, callback)
                return true
            }

            discovered
                .filter { shouldQueueCustomCandidate(it, current) }
                .forEach { next -> queue.add(next to current) }
        }

        return false
    }

    private fun extractCandidatesFromText(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { rgx ->
            rgx.findAll(text).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrlFromBase(raw, baseUrl)?.let(out::add)
            }
        }
        return out
    }

    private fun shouldQueueCustomCandidate(candidate: String, currentUrl: String): Boolean {
        val lower = candidate.lowercase()
        if (isDirectMediaUrl(lower)) return false
        if (lower.startsWith("javascript:") || lower.startsWith("data:")) return false
        if (lower.contains("/utils/player/")) return true

        val hints = listOf("yourupload", "pixeldrain", "pompom", "pancal", "myvidplay", "aghanim", "uservideo", "mp4upload")
        if (hints.any { lower.contains(it) }) return true

        val currentHost = runCatching { URI(currentUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        val nextHost = runCatching { URI(candidate).host?.lowercase().orEmpty() }.getOrDefault("")
        return currentHost.isNotBlank() && currentHost == nextHost
    }

    private fun randomAlphaNum(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString(length) {
            repeat(length) {
                append(chars.random())
            }
        }
    }

    private suspend fun tryLoadMp4UploadDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)(?:\.html)?""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val downloadUrl = "https://www.mp4upload.com/dl?op=download2&id=$id"
        val watchReferer = "https://www.mp4upload.com/"
        val redirect = runCatching {
            app.get(
                downloadUrl,
                referer = watchReferer,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            )
        }.getOrNull() ?: return false

        val location = redirect.headers["Location"] ?: redirect.headers["location"]
        val finalUrl = when {
            location.isNullOrBlank() -> return false
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("//") -> "https:$location"
            location.startsWith("/") -> "https://www.mp4upload.com$location"
            else -> return false
        }

        val probe = runCatching {
            app.get(
                finalUrl,
                referer = watchReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com",
                    "Range" to "bytes=0-4095"
                )
            )
        }.getOrNull() ?: return false

        val contentType = (probe.headers["Content-Type"] ?: probe.headers["content-type"]).orEmpty().lowercase()
        if (!(contentType.contains("octet-stream") || contentType.contains("video"))) return false

        callback.invoke(
            newExtractorLink(
                source = "Mp4Upload",
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                this.referer = watchReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            }
        )
        return true
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

}

class TurnstileInterceptor(
    private val targetCookies: List<String> = listOf("cf_clearance", "_as_turnstile")
) : Interceptor {
    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_ATTEMPTS = 30
        private const val PAGE_WAIT_SECONDS = 45L
    }

    private fun getCookieHeader(url: String, domainUrl: String): String {
        val manager = CookieManager.getInstance()
        return manager.getCookie(url) ?: manager.getCookie(domainUrl) ?: ""
    }

    private fun getCookieValue(url: String, domainUrl: String): String? {
        val raw = getCookieHeader(url, domainUrl)
        if (raw.isBlank()) return null
        return raw.split(";")
            .map { it.trim() }
            .firstNotNullOfOrNull { cookie ->
                targetCookies.firstOrNull { target -> cookie.startsWith("$target=") }
                    ?.let { cookie.substringAfter("=") }
                    ?.takeIf { it.isNotBlank() }
            }
    }

    private fun invalidateCookie(domainUrl: String) {
        CookieManager.getInstance().apply {
            targetCookies.forEach { cookie ->
                setCookie(domainUrl, "$cookie=; Max-Age=0")
            }
            flush()
        }
    }

    private fun hasChallenge(response: Response): Boolean {
        if (response.code == 403 || response.code == 429 || response.code == 503) return true

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return false

        val preview = runCatching { response.peekBody(128 * 1024).string() }.getOrDefault("")
        if (preview.isBlank()) return false

        val challengeHints = listOf(
            "cf-challenge",
            "cf-browser-verification",
            "cf_clearance",
            "challenge-platform",
            "Just a moment",
            "Attention Required",
            "turnstile",
            "/cdn-cgi/challenge-platform/"
        )
        return challengeHints.any { preview.contains(it, ignoreCase = true) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()
        if (getCookieValue(url, domainUrl) != null) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", getCookieHeader(url, domainUrl))
                    .build()
            )
            if (!hasChallenge(response)) return response
            response.close()
            invalidateCookie(domainUrl)
        }

        val context = AcraApplication.context
            ?: return chain.proceed(originalRequest)

        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var resolvedUserAgent = originalRequest.header("User-Agent") ?: ""
        val challengeLatch = CountDownLatch(1)

        handler.post {
            try {
                val wv = WebView(context).also { webView = it }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadsImagesAutomatically = true
                    if (resolvedUserAgent.isNotBlank()) userAgentString = resolvedUserAgent
                    resolvedUserAgent = userAgentString
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        cookieManager.flush()
                        if (getCookieValue(finishedUrl, domainUrl) != null) {
                            challengeLatch.countDown()
                        }
                    }
                }
                wv.loadUrl(url)
            } catch (e: Exception) {
                challengeLatch.countDown()
                e.printStackTrace()
            }
        }

        challengeLatch.await(PAGE_WAIT_SECONDS, TimeUnit.SECONDS)

        var attempts = 0
        while (attempts < MAX_ATTEMPTS && getCookieValue(url, domainUrl) == null) {
            Thread.sleep(POLL_INTERVAL_MS)
            cookieManager.flush()
            attempts++
        }

        handler.post {
            try {
                webView?.apply {
                    stopLoading()
                    clearCache(false)
                    destroy()
                }
                webView = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalCookies = getCookieHeader(url, domainUrl)
        val finalResponse = chain.proceed(
            originalRequest.newBuilder()
                .header("Cookie", finalCookies)
                .apply { if (resolvedUserAgent.isNotBlank()) header("User-Agent", resolvedUserAgent) }
                .build()
        )

        if (!hasChallenge(finalResponse)) return finalResponse

        return finalResponse
    }
}
