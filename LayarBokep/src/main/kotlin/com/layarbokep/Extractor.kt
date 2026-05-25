package com.layarbokep

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(" ", "%20")
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to (referer ?: mainUrl)
        )

        val response = runCatching {
            app.get(cleanUrl, referer = referer ?: mainUrl, headers = headers, timeout = 20L)
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val found = linkedSetOf<String>()

        extractStreamUrls(html).forEach { found.add(normalizeUrl(it, cleanUrl)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractStreamUrls(unpacked.cleanEscaped()).forEach { found.add(normalizeUrl(it, cleanUrl)) }
            extractSubtitles(unpacked.cleanEscaped(), cleanUrl, subtitleCallback)
        }

        extractSubtitles(html, cleanUrl, subtitleCallback)

        if (found.isEmpty()) {
            val hash = cleanUrl.substringAfter("data=", cleanUrl.substringAfterLast("/"))
                .substringBefore("&")
                .trim()

            val endpoints = listOf(
                "$mainUrl/player/ajax.php?data=$hash&do=getVideo",
                "$mainUrl/player/index.php?data=$hash&do=getVideo"
            )

            endpoints.forEach { endpoint ->
                val postText = runCatching {
                    app.post(
                        url = endpoint,
                        data = mapOf("hash" to hash, "r" to (referer ?: "")),
                        referer = cleanUrl,
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                        timeout = 20L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                extractStreamUrls(postText).forEach { found.add(normalizeUrl(it, cleanUrl)) }
                extractSubtitles(postText, cleanUrl, subtitleCallback)
            }
        }

        found.distinct().forEach { stream ->
            emitStream(
                sourceName = name,
                streamUrl = stream,
                referer = cleanUrl,
                headers = headers,
                callback = callback
            )
        }
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}

open class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(" ", "%20")
        val domain = runCatching { "https://${URI(cleanUrl).host}" }.getOrDefault(mainUrl)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: domain),
            "Accept" to "*/*"
        )

        val response = runCatching {
            app.get(cleanUrl, referer = referer ?: domain, headers = headers, timeout = 20L)
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val document = response.document
        val streams = linkedSetOf<String>()

        document.select("video source[src], source[src], video[src], a[href*='.m3u8'], a[href*='.mp4']").forEach { source ->
            val src = source.attr("src")
                .ifBlank { source.attr("abs:src") }
                .ifBlank { source.attr("href") }
                .ifBlank { source.attr("abs:href") }
                .trim()

            if (src.isNotBlank()) streams.add(normalizeUrl(src, cleanUrl))
        }

        extractStreamUrls(html).forEach { streams.add(normalizeUrl(it, cleanUrl)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractStreamUrls(unpacked.cleanEscaped()).forEach { streams.add(normalizeUrl(it, cleanUrl)) }
            extractSubtitles(unpacked.cleanEscaped(), cleanUrl, subtitleCallback)
        }

        extractSubtitles(html, cleanUrl, subtitleCallback)

        streams.distinct().forEach { stream ->
            emitStream(
                sourceName = name,
                streamUrl = stream,
                referer = domain,
                headers = headers,
                callback = callback
            )
        }
    }
}

private suspend fun emitStream(
    sourceName: String,
    streamUrl: String,
    referer: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
) {
    val fixedStream = streamUrl.cleanEscaped().replace(".txt", ".m3u8")
    if (fixedStream.isBlank() || isBadPlayableUrl(fixedStream)) return

    if (fixedStream.contains(".m3u8", true)) {
        generateM3u8(
            source = sourceName,
            streamUrl = fixedStream,
            referer = referer,
            headers = headers
        ).forEach(callback)
    } else {
        callback(
            newExtractorLink(
                source = sourceName,
                name = sourceName,
                url = fixedStream,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(fixedStream).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(fixedStream)
                this.headers = headers
            }
        )
    }
}

private fun extractStreamUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()
    val clean = text.cleanEscaped()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isBadPlayableUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
        .filterNot { isBadPlayableUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isBadPlayableUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|source|src|url|videoSource|videoUrl|video_url|hls|hlsUrl|hls_url|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) }
        .filterNot { isBadPlayableUrl(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractSubtitles(
    text: String,
    baseUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    val clean = text.cleanEscaped()

    Regex(
        """(?:file|src|url|path)\s*[:=]\s*["']([^"']+\.(?:vtt|srt|ass)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean).forEach { match ->
        val subUrl = normalizeUrl(match.groupValues[1], baseUrl)
        val label = when {
            subUrl.contains("ind", true) || subUrl.contains("indo", true) -> "Indonesian"
            subUrl.contains("eng", true) || subUrl.contains("english", true) -> "English"
            else -> "Subtitle"
        }
        subtitleCallback(newSubtitleFile(label, subUrl))
    }
}

private fun normalizeUrl(url: String, baseUrl: String): String {
    val clean = url.cleanEscaped()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: ""
            "$origin$clean"
        }
        else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
}

private fun isBadPlayableUrl(url: String): Boolean {
    val value = url.lowercase()
    return value.contains("vast") ||
        value.contains("preroll") ||
        value.contains("doubleclick") ||
        value.contains("googlesyndication") ||
        value.contains("analytics") ||
        value.contains("tracking") ||
        value.contains("banner") ||
        value.contains("/ads/")
}

private fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
