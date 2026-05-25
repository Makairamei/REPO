package com.nomat

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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder

class Hydrax : ExtractorApi() {
    override val name = "Hydrax"
    override val mainUrl = "https://nontonhemat.link"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeUrl(url, referer ?: mainUrl)
        if (pageUrl.isBlank()) return

        val origin = getOrigin(pageUrl)
        val pageReferer = referer ?: origin
        val requestHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to pageReferer,
            "Origin" to origin
        )

        val response = runCatching {
            app.get(
                pageUrl,
                referer = pageReferer,
                headers = requestHeaders,
                timeout = 30L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        if (html.trimStart().startsWith("#EXTM3U")) {
            emitVideo(pageUrl, pageReferer, requestHeaders, callback)
            return
        }

        response.document.select(
            "video[src], video[data-src], video[data-video], " +
                "source[src], source[data-src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "embed[src], object[data], a[href], " +
                "[data-url], [data-file], [data-video], [data-src]"
        ).forEach { element ->
            val raw = element.attr("data-video")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        extractPlayableUrls(html).forEach { raw ->
            addCandidate(raw, pageUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        val decoded = runCatching {
            URLDecoder.decode(html, "UTF-8")
        }.getOrDefault(html)

        if (decoded != html) {
            extractPlayableUrls(decoded.cleanEscaped()).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }

        directLinks.distinct().forEach { link ->
            emitVideo(link, pageUrl, requestHeaders, callback)
        }

        if (directLinks.isNotEmpty()) return

        embedLinks
            .filter { it != pageUrl }
            .distinct()
            .take(8)
            .forEach { embed ->
                val success = runCatching {
                    loadExtractor(embed, pageUrl, subtitleCallback, callback)
                }.getOrDefault(false)

                if (success) return

                val nestedText = runCatching {
                    app.get(
                        embed,
                        referer = pageUrl,
                        headers = requestHeaders,
                        timeout = 20L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                if (nestedText.isNotBlank()) {
                    extractPlayableUrls(nestedText).forEach { raw ->
                        val fixed = normalizeUrl(raw, embed).replace(".txt", ".m3u8")
                        if (isDirectVideo(fixed)) {
                            emitVideo(fixed, embed, requestHeaders, callback)
                            return
                        }
                    }
                }
            }
    }

    private suspend fun emitVideo(
        streamUrl: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = streamUrl.cleanEscaped().replace(".txt", ".m3u8")
        if (fixed.isBlank()) return

        if (fixed.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = fixed,
                referer = referer,
                headers = headers
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
                    this.quality = getQualityFromName(fixed).takeIf {
                        it != Qualities.Unknown.value
                    } ?: qualityFromUrl(fixed)
                }
            )
        }
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isBlockedUrl(fixed)) return

        when {
            isDirectVideo(fixed) -> directLinks.add(fixed)
            fixed.startsWith("http", true) -> embedLinks.add(fixed)
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val results = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBlockedUrl(it) }
            .forEach { results.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .filterNot { isBlockedUrl(it) }
            .forEach { results.add(it) }

        Regex(
            """(?:file|src|source|url|videoUrl|video_url|hls|hlsUrl|hls_url|stream|streamUrl|stream_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { isDirectVideo(it) || it.startsWith("http", true) || it.startsWith("//") }
            .filterNot { isBlockedUrl(it) }
            .forEach { results.add(it) }

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
            .filterNot { isBlockedUrl(it) }
            .forEach { results.add(it) }

        return results.toList()
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

    private fun isDirectVideo(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true)
    }

    private fun isBlockedUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("ads") ||
            value.contains("banner") ||
            value.contains("mailto:")
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
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}
