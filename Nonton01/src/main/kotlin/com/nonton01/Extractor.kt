package com.nonton01

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
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
        val origin = getOrigin(cleanUrl).ifBlank { mainUrl }
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to (referer ?: origin),
            "Origin" to origin
        )

        val response = runCatching {
            app.get(cleanUrl, referer = referer ?: origin, headers = headers)
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val document = response.document
        val found = linkedSetOf<String>()

        document.select("video source[src], source[src], video[src], [data-file], [data-video], [data-url], [data-src]").forEach { source ->
            val raw = source.attr("src")
                .ifBlank { source.attr("data-file") }
                .ifBlank { source.attr("data-video") }
                .ifBlank { source.attr("data-url") }
                .ifBlank { source.attr("data-src") }
                .trim()

            if (raw.isNotBlank()) found.add(normalizeUrl(raw, cleanUrl))
        }

        extractStreamUrls(html).forEach {
            found.add(normalizeUrl(it, cleanUrl))
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractStreamUrls(unpacked.cleanEscaped()).forEach {
                found.add(normalizeUrl(it, cleanUrl))
            }
        }

        if (found.isEmpty()) {
            val hash = cleanUrl.substringAfter("data=", cleanUrl.substringAfterLast("/"))
                .substringBefore("&")
                .substringBefore("?")
                .trim()

            val endpoints = listOf(
                "$mainUrl/player/ajax.php?data=$hash&do=getVideo",
                "$mainUrl/player/index.php?data=$hash&do=getVideo",
                "$origin/player/ajax.php?data=$hash&do=getVideo",
                "$origin/player/index.php?data=$hash&do=getVideo"
            ).distinct()

            endpoints.forEach { endpoint ->
                val postText = runCatching {
                    app.post(
                        url = endpoint,
                        data = mapOf(
                            "hash" to hash,
                            "r" to (referer ?: "")
                        ),
                        referer = cleanUrl,
                        headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                extractStreamUrls(postText).forEach {
                    found.add(normalizeUrl(it, cleanUrl))
                }
            }
        }

        found.forEach { stream ->
            emitExtractorStream(
                source = name,
                streamUrl = stream,
                referer = cleanUrl,
                headers = headers,
                callback = callback
            )
        }

        collectSubtitles(response.text, cleanUrl, subtitleCallback)
    }

    data class ResponseSource(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
}

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = getOrigin(url).ifBlank { mainUrl }
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: origin),
            "Origin" to origin,
            "Accept" to "*/*"
        )

        val response = runCatching {
            app.get(url, referer = referer ?: origin, headers = headers)
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val document = response.document
        val streams = linkedSetOf<String>()

        document.select("video source[src], source[src], video[src], [data-file], [data-video], [data-url], [data-src]").forEach { source ->
            val src = source.attr("src")
                .ifBlank { source.attr("data-file") }
                .ifBlank { source.attr("data-video") }
                .ifBlank { source.attr("data-url") }
                .ifBlank { source.attr("data-src") }
                .trim()

            if (src.isNotBlank()) {
                streams.add(normalizeUrl(src, url))
            }
        }

        extractStreamUrls(html).forEach {
            streams.add(normalizeUrl(it, url))
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractStreamUrls(unpacked.cleanEscaped()).forEach {
                streams.add(normalizeUrl(it, url))
            }
        }

        streams.forEach { stream ->
            emitExtractorStream(
                source = name,
                streamUrl = stream,
                referer = url,
                headers = headers,
                callback = callback
            )
        }

        collectSubtitles(response.text, url, subtitleCallback)
    }
}

private fun emitExtractorStream(
    source: String,
    streamUrl: String,
    referer: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
) {
    val fixedStream = streamUrl.cleanEscaped().replace(".txt", ".m3u8")

    if (fixedStream.contains(".m3u8", true)) {
        generateM3u8(
            source = source,
            streamUrl = fixedStream,
            referer = referer,
            headers = headers
        ).forEach(callback)
    } else {
        callback(
            newExtractorLink(
                source = source,
                name = source,
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

private fun collectSubtitles(
    text: String,
    baseUrl: String,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    Regex(
        """"(?:label|lang|language)"\s*:\s*"([^"]+)"[^{}]*?"(?:file|url|src|path)"\s*:\s*"([^"]+\.(?:vtt|srt)(?:\?[^"]*)?)"""",
        RegexOption.IGNORE_CASE
    ).findAll(text.cleanEscaped()).forEach { match ->
        val label = match.groupValues[1].ifBlank { "Subtitle" }
        val subUrl = normalizeUrl(match.groupValues[2], baseUrl)
        subtitleCallback(SubtitleFile(label, subUrl))
    }

    Jsoup.parse(text).select("track[src], a[href$=.vtt], a[href$=.srt]").forEach { element ->
        val raw = element.attr("src").ifBlank { element.attr("href") }
        if (raw.isNotBlank()) {
            val label = element.attr("label").ifBlank { element.attr("srclang").ifBlank { "Subtitle" } }
            subtitleCallback(SubtitleFile(label, normalizeUrl(raw, baseUrl)))
        }
    }
}

internal fun extractStreamUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()
    val clean = text.cleanEscaped()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped() }
        .forEach { urls.add(it.replace(".txt", ".m3u8")) }

    Regex(
        """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|source|src|url|videoSource|videoUrl|video_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url|contentUrl)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.contains(".m3u8", true) ||
                it.contains(".mp4", true) ||
                it.contains(".webm", true) ||
                it.contains(".txt", true)
        }
        .forEach { urls.add(it) }

    return urls.toList()
}

internal fun normalizeUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscaped().trim()

    return when {
        clean.isBlank() -> ""
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = getOrigin(baseUrl)
            if (origin.isNotBlank()) "$origin$clean" else clean
        }
        else -> {
            runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrElse {
                val origin = getOrigin(baseUrl)
                if (origin.isNotBlank()) "$origin/${clean.trimStart('/')}" else clean
            }
        }
    }
}

internal fun getOrigin(url: String): String {
    return runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(
        Regex("""^https?://[^/]+""").find(url)?.value.orEmpty()
    )
}

internal fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u003A", ":")
        .replace("\\u0026", "&")
        .replace("\\u003D", "=")
        .replace("\\u003F", "?")
        .replace("\\u002D", "-")
        .replace("\\u005C", "\\")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("\\\"", "\"")
        .trim()
}

internal fun qualityFromUrl(url: String): Int {
    return when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1440", true) -> Qualities.P1440.value
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("540", true) -> Qualities.P480.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}
