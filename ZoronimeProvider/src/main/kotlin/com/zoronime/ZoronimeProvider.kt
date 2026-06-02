package com.zoronime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ZoronimeProvider : MainAPI() {
    override var mainUrl = "https://zoronime.online"
    override var name = "Zoronime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "ongoing?page=%d" to "Ongoing",
        "completed?page=%d" to "Completed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("a[href^=/anime/], a[href^=$mainUrl/anime/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        val hasNext = document.select("a[href*=\"?page=${page + 1}\"], a[href$=\"/ongoing?page=${page + 1}\"], a[href$=\"/completed?page=${page + 1}\"], a[href$=\"/anime?page=${page + 1}\"]").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded", referer = "$mainUrl/").document
        return document.select("a[href^=/anime/], a[href^=$mainUrl/anime/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val response = app.get(fixUrl(url), referer = "$mainUrl/")
        val document = response.document
        val html = response.text
        val fixedUrl = response.url

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" - ")
                ?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("img")?.imageUrl()
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.substringBefore(" subtitle Indonesia")
            ?.trim()
        val genres = document.select("a[href^=/genres/], a[href^=$mainUrl/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val recommendations = document.select("a[href^=/anime/], a[href^=$mainUrl/anime/]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        val metaDescription = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val type = when {
            metaDescription.contains("Movie", true) -> TvType.AnimeMovie
            metaDescription.contains("OVA", true) -> TvType.OVA
            else -> TvType.Anime
        }
        val status = when {
            metaDescription.contains("Ongoing", true) -> ShowStatus.Ongoing
            metaDescription.contains("Completed", true) -> ShowStatus.Completed
            else -> null
        }
        val score = Regex("""Skor:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(metaDescription)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
        val year: Int? = null

        val episodes = document.select("a[href^=/episode/], a[href^=$mainUrl/episode/]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                plot = description
                this.tags = genres
                this.recommendations = recommendations
                this.showStatus = status
                this.year = year
                score?.let { this.score = Score.from10(it) }
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val playUrl = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, playUrl, type, playUrl) {
                posterUrl = poster
                plot = description
                this.tags = genres
                this.recommendations = recommendations
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val response = app.get(data, referer = "$mainUrl/")
        val html = response.text
        val emitted = linkedSetOf<String>()
        val episodeReferer = data
        val serverApi = "https://wg-anime-api-v2-production.up.railway.app/Otakudesu/server"

        suspend fun emitDirect(mediaUrl: String, referer: String, label: String = name) {
            val cleanUrl = decodeEscaped(mediaUrl).substringBefore('#').trim()
            if (cleanUrl.isBlank() || !emitted.add(cleanUrl)) return

            val type = when {
                cleanUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = cleanUrl,
                    type = type
                ) {
                    this.referer = referer
                    this.quality = qualityFromName(label)
                    headers = mapOf("Referer" to referer)
                }
            )
        }

        suspend fun emitBloggerLinks(bloggerUrl: String, referer: String, label: String): Boolean {
            val resolvedVideos = extractBloggerDirectVideos(bloggerUrl, referer)
            if (resolvedVideos.isEmpty()) return false

            resolvedVideos.forEach { video ->
                val directReferer = if (video.url.contains("googlevideo.com/", true)) {
                    "https://youtube.googleapis.com/"
                } else {
                    bloggerUrl
                }
                val cleanUrl = decodeEscaped(video.url).substringBefore('#').trim()
                if (cleanUrl.isBlank() || !emitted.add(cleanUrl)) return@forEach

                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = cleanUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = directReferer
                        this.quality = if (video.quality != Qualities.Unknown.value) {
                            video.quality
                        } else {
                            qualityFromName(label)
                        }
                        headers = mapOf(
                            "Referer" to directReferer,
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*"
                        )
                    }
                )
            }

            LicenseClient.trackActivity(name, "PLAY")
        return true
        }

        suspend fun inspectIframe(iframeUrl: String, label: String = "$name Direct") {
            val cleanUrl = decodeEscaped(iframeUrl)
            if (cleanUrl.isBlank()) return

            if (cleanUrl.contains("desustream.info/dstream", true)) {
                runCatching {
                    val jsonUrl = if (cleanUrl.contains("?")) {
                        "$cleanUrl&mode=json"
                    } else {
                        "$cleanUrl?mode=json"
                    }
                    val refreshed = app.get(
                        jsonUrl,
                        referer = episodeReferer,
                        headers = mapOf(
                            "Accept" to "application/json",
                            "X-Requested-With" to "XMLHttpRequest",
                        )
                    ).parsedSafe<ZoronimePlayerRefresh>()?.video

                    if (!refreshed.isNullOrBlank()) {
                        if (refreshed.contains("blogger.com/video.g", true) ||
                            refreshed.contains("blogger.googleusercontent.com", true)
                        ) {
                            emitBloggerLinks(refreshed, cleanUrl, label)
                        } else {
                            emitDirect(refreshed, cleanUrl, label)
                        }
                    }
                }
            }

            runCatching {
                loadExtractor(cleanUrl, episodeReferer, subtitleCallback, callback)
            }

            val iframePage = app.get(cleanUrl, referer = episodeReferer).text
            Regex("""https?://[^"'\\\s<>]+""", setOf(RegexOption.IGNORE_CASE))
                .findAll(iframePage)
                .map { decodeEscaped(it.value) }
                .filter { candidate ->
                    candidate.contains(".m3u8", true) ||
                        candidate.contains(".mp4", true) ||
                        candidate.contains("googlevideo", true) ||
                        candidate.contains("blogger.com/video.g", true) ||
                        candidate.contains("bloggerusercontent", true)
                }
                .distinct()
                .forEach { mediaUrl ->
                    if (mediaUrl.contains("blogger.com/video.g", true) ||
                        mediaUrl.contains("blogger.googleusercontent.com", true)
                    ) {
                        emitBloggerLinks(mediaUrl, cleanUrl, label)
                    } else {
                        emitDirect(mediaUrl, cleanUrl, label)
                    }
                }
        }

        val serverLinks = extractServerLinks(html)
        serverLinks.forEach { server ->
            runCatching {
                val resolved = app.get(
                    "$serverApi/${server.serverId}",
                    referer = episodeReferer,
                    headers = mapOf("Accept" to "application/json")
                ).parsedSafe<ZoronimeServerResponse>()?.data?.url
                    ?: return@runCatching

                val serverLabel = buildString {
                    append(name)
                    append(" [")
                    append(server.quality.ifBlank { "Source" })
                    append(" - ")
                    append(server.title)
                    append("]")
                }

                if (resolved.contains(".m3u8", true) ||
                    resolved.contains(".mp4", true) ||
                    resolved.contains("googlevideo", true) ||
                    resolved.contains("blogger", true)
                ) {
                    if (resolved.contains("blogger.com/video.g", true) ||
                        resolved.contains("blogger.googleusercontent.com", true)
                    ) {
                        emitBloggerLinks(resolved, episodeReferer, serverLabel)
                    } else {
                        emitDirect(resolved, episodeReferer, serverLabel)
                    }
                } else {
                    inspectIframe(resolved, serverLabel)
                }
            }
        }

        val iframeCandidates = linkedSetOf<String>()
        Regex("""defaultStreamingUrl\\?":\\?"([^"]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(iframeCandidates::add)
        Regex("""<iframe[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach(iframeCandidates::add)

        if (emitted.isEmpty()) {
            iframeCandidates.forEach { inspectIframe(it) }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
        return normalized.replace("%d", page.toString())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!href.contains("/anime/")) return null

        val title = selectFirst("h3")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.substringBefore(" poster")?.trim()
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst("img")?.imageUrl()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = attr("href").trim().takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!href.contains("/episode/")) return null

        val label = selectFirst("span")?.text()?.trim()
            ?: text().trim()
            ?: return null
        val number = Regex("""(\d+)""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newEpisode(href) {
            name = if (label.startsWith("Episode", true)) label else "Episode ${number ?: "?"}"
            episode = number
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("src"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("abs:src"),
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun decodeEscaped(value: String): String {
        var result = value.trim()
        repeat(3) {
            result = result
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("&amp;", "&")
        }
        return result
    }

    private fun decodeUnicodeEscapes(input: String): String {
        val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
        var output = input
        repeat(2) {
            output = unicodeRegex.replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        output = output.replace("\\/", "/")
        output = output.replace("\\=", "=")
        output = output.replace("\\&", "&")
        output = output.replace("\\\\", "\\")
        output = output.replace("\\\"", "\"")
        return output
    }

    private fun normalizeVideoUrl(input: String): String {
        return decodeUnicodeEscapes(input)
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\", "")
    }

    private suspend fun extractBloggerDirectVideos(url: String, referer: String?): List<ResolvedVideo> {
        val fixedUrl = if (url.startsWith("//")) "https:$url" else decodeEscaped(url)

        if (fixedUrl.contains("blogger.googleusercontent.com", true)) {
            return listOf(
                ResolvedVideo(
                    url = fixedUrl,
                    quality = itagToQuality(
                        Regex("[?&]itag=(\\d+)")
                            .find(fixedUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    )
                )
            )
        }

        val token = Regex("[?&]token=([^&]+)")
            .find(fixedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val page = runCatching {
            app.get(
                fixedUrl,
                referer = referer ?: "$mainUrl/",
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "User-Agent" to USER_AGENT
                )
            )
        }.getOrNull() ?: return emptyList()
        val html = page.text
        val cookies = page.cookies

        val fSid = Regex("FdrFJe\":\"(-?\\d+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: ""
        val bl = Regex("cfb2h\":\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()
        val hl = Regex("lang=\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.ifBlank { null }
            ?: "en-US"
        val reqId = (10000..99999).random()
        val rpcId = "WcwnYd"
        val payload = """[[["$rpcId","[\"$token\",\"\",0]",null,"generic"]]]"""
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=$rpcId&source-path=%2Fvideo.g&f.sid=$fSid&bl=$bl&hl=$hl&_reqid=$reqId&rt=c"

        val response = runCatching {
            app.post(
                apiUrl,
                data = mapOf("f.req" to payload),
                referer = fixedUrl,
                cookies = cookies,
                headers = mapOf(
                    "Origin" to "https://www.blogger.com",
                    "Accept" to "*/*",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "X-Same-Domain" to "1",
                    "User-Agent" to USER_AGENT
                )
            ).text
        }.getOrNull() ?: return emptyList()

        return Regex("""https://[^\s"']+""")
            .findAll(decodeUnicodeEscapes(response))
            .map { it.value }
            .plus(
                Regex("""https://[^\s"']+""")
                    .findAll(response)
                    .map { it.value }
            )
            .map { normalizeVideoUrl(it) }
            .filter {
                it.contains("googlevideo.com/videoplayback", true) ||
                    it.contains("blogger.googleusercontent.com", true)
            }
            .distinct()
            .map { videoUrl ->
                ResolvedVideo(
                    url = videoUrl,
                    quality = itagToQuality(
                        Regex("[?&]itag=(\\d+)")
                            .find(videoUrl)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    )
                )
            }
            .toList()
    }

    private fun qualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun extractServerLinks(html: String): List<ZoronimeServerLink> {
        val normalized = decodeEscaped(html)
        val matches = Regex(
            """"title":"\s*([^"]+?)\s*","serverList":\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(normalized)

        return matches.flatMap { qualityMatch ->
            val quality = qualityMatch.groupValues.getOrNull(1).orEmpty().trim()
            val block = qualityMatch.groupValues.getOrNull(2).orEmpty()
            Regex(
                """"title":"([^"]+)","serverId":"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(block).mapNotNull { serverMatch ->
                val title = serverMatch.groupValues.getOrNull(1)?.trim().orEmpty()
                val serverId = serverMatch.groupValues.getOrNull(2)?.trim().orEmpty()
                if (title.isBlank() || serverId.isBlank()) null
                else ZoronimeServerLink(title, quality, serverId)
            }
        }.distinctBy { "${it.quality}:${it.title}:${it.serverId}" }.toList()
    }

    private fun itagToQuality(itag: Int?): Int {
        return when (itag) {
            18 -> Qualities.P360.value
            22 -> Qualities.P720.value
            37 -> Qualities.P1080.value
            59 -> Qualities.P480.value
            43 -> Qualities.P360.value
            36 -> Qualities.P240.value
            17 -> Qualities.P144.value
            137 -> Qualities.P1080.value
            136 -> Qualities.P720.value
            135 -> Qualities.P480.value
            134 -> Qualities.P360.value
            133 -> Qualities.P240.value
            160 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private data class ZoronimeServerLink(
        val title: String,
        val quality: String,
        val serverId: String,
    )

    private data class ZoronimeServerResponse(
        @JsonProperty("data") val data: ZoronimeServerData? = null,
    )

    private data class ZoronimeServerData(
        @JsonProperty("url") val url: String? = null,
    )

    private data class ZoronimePlayerRefresh(
        @JsonProperty("video") val video: String? = null,
    )

    private data class ResolvedVideo(
        val url: String,
        val quality: Int,
    )
}
