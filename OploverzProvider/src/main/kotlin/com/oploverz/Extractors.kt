package com.oploverz

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

class Qiwi : OploverzDirectExtractor() {
    override val name = "Qiwi"
    override val mainUrl = "https://qiwi.gg"
}

class Filedon : OploverzDirectExtractor() {
    override val name = "Filedon"
    override val mainUrl = "https://filedon.co"
}

class Buzzheavier : OploverzDirectExtractor() {
    override val name = "Buzzheavier"
    override val mainUrl = "https://buzzheavier.com"
}

open class OploverzDirectExtractor : ExtractorApi() {
    override val name = "OploverzDirect"
    override val mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return

        val origin = runCatching {
            URI(cleanUrl).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl.ifBlank { cleanUrl })

        val response = runCatching {
            app.get(
                cleanUrl,
                referer = referer ?: origin,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val found = linkedSetOf<String>()

        if (html.trimStart().startsWith("#EXTM3U")) {
            found.add(cleanUrl)
        }

        response.document.select(
            "video[src], source[src], a[href$=.mp4], a[href$=.mkv], a[href$=.m3u8], a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }.trim()
            if (raw.isNotBlank()) found.add(normalizeUrl(raw, cleanUrl))
        }

        extractMediaUrls(html).forEach { found.add(normalizeUrl(it, cleanUrl)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractMediaUrls(unpacked.cleanEscaped()).forEach { found.add(normalizeUrl(it, cleanUrl)) }
        }

        found
            .map { it.cleanEscaped() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { stream ->
                emitStream(stream, cleanUrl, callback)
            }
    }

    private suspend fun emitStream(
        streamUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = streamUrl.cleanEscaped()
        if (fixed.isBlank()) return

        if (fixed.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = fixed,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl.ifBlank { getOrigin(referer) }
                )
            ).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixed,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value }
                        ?: qualityFromUrl(fixed)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl.ifBlank { getOrigin(referer) }
                    )
                }
            )
        }
    }

    private fun extractMediaUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).map { it.value }.forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).map { "https:${it.value}" }.forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoUrl|video_url|downloadUrl|download_url)\s*[:=]\s*["']([^"']+)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    it.contains(".mkv", true)
            }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> getOrigin(baseUrl).trimEnd('/') + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun getOrigin(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
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

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}
