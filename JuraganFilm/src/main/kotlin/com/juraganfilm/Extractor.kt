package com.juraganfilm

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
            app.get(cleanUrl, referer = referer ?: mainUrl, headers = headers)
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val found = linkedSetOf<String>()

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
                .trim()

            val endpoints = listOf(
                "$mainUrl/player/ajax.php?data=$hash&do=getVideo",
                "$mainUrl/player/index.php?data=$hash&do=getVideo"
            )

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
            val fixedStream = stream.replace(".txt", ".m3u8")

            if (fixedStream.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = fixedStream,
                    referer = cleanUrl
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixedStream,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = cleanUrl
                        this.quality = getQualityFromName(fixedStream)
                        this.headers = headers
                    }
                )
            }
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
        val domain = runCatching {
            "https://${URI(url).host}"
        }.getOrDefault(mainUrl)

        val response = runCatching {
            app.get(
                url,
                referer = referer ?: domain,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: domain),
                    "Accept" to "*/*"
                )
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val document = response.document
        val streams = linkedSetOf<String>()

        document.select("video source[src], source[src], video[src]").forEach { source ->
            val src = source.attr("src")
                .ifBlank { source.attr("abs:src") }
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
            val fixedStream = stream.replace(".txt", ".m3u8")

            if (fixedStream.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = fixedStream,
                    referer = domain
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixedStream,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = domain
                        this.quality = getQualityFromName(fixedStream).takeIf {
                            it != Qualities.Unknown.value
                        } ?: qualityFromUrl(fixedStream)
                    }
                )
            }
        }

        val scripts = document.selectFirst("script:containsData(subtitles)")?.data().orEmpty()
        val subRegex = Regex("""\\"label\\":\\"([^\\"]*?)\\"[^}]*?\\"path\\":\\"([^\\"]*?)\\\"""")

        subRegex.findAll(scripts).forEach { match ->
            val label = match.groupValues[1]
            var vttUrl = match.groupValues[2].cleanEscaped()

            if (!vttUrl.startsWith("http")) {
                vttUrl = domain.trimEnd('/') + "/" + vttUrl.trimStart('/')
            }

            subtitleCallback.invoke(SubtitleFile(label, vttUrl))
        }
    }
}

class E2eMajorplay : Majorplay() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
}

class M3u8Majorplay : Majorplay() {
    override var name = "Majorplay"
    override var mainUrl = "https://m3u8.majorplay.net"
}

private fun extractStreamUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(text)
        .map { it.value.cleanEscaped() }
        .forEach { urls.add(it.replace(".txt", ".m3u8")) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(text)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|source|src|url|videoSource)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.contains(".m3u8", true) ||
                it.contains(".mp4", true)
        }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun normalizeUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscaped()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""")
                .find(baseUrl)
                ?.value
                ?: "https://"
            "$origin$clean"
        }
        else -> {
            runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrElse {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: ""
                if (origin.isNotBlank()) "$origin/$clean" else clean
            }
        }
    }
}

private fun String.cleanEscaped(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
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