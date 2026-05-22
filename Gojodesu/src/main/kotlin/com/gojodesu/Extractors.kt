package com.gojodesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to (referer ?: "https://gojodesu.com/")
        )

        val response = app.get(
            url,
            referer = referer ?: "https://gojodesu.com/",
            headers = headers
        )

        val document = response.document
        val html = response.text
        val foundM3u8 = linkedSetOf<String>()
        val frameLinks = linkedSetOf<String>()

        extractM3u8Urls(html).forEach { foundM3u8.add(normalizeUrl(it)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractM3u8Urls(unpacked).forEach { foundM3u8.add(normalizeUrl(it)) }
            extractPossibleUrls(unpacked).forEach { frameLinks.add(normalizeUrl(it)) }
        }

        document.select(
            "ul#dropdown-server li a[data-frame], " +
                "a[data-frame], " +
                "[data-frame]"
        ).forEach { element ->
            val encoded = element.attr("data-frame").trim()
            if (encoded.isBlank()) return@forEach

            val decoded = runCatching {
                base64Decode(encoded).trim()
            }.getOrNull()

            if (!decoded.isNullOrBlank()) {
                if (decoded.contains(".m3u8", true)) {
                    foundM3u8.add(normalizeUrl(decoded))
                } else {
                    frameLinks.add(normalizeUrl(decoded))
                }
            }
        }

        document.select(
            "iframe[src], " +
                "embed[src], " +
                "source[src], " +
                "video[src], " +
                "a[href]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach

            when {
                raw.contains(".m3u8", true) -> foundM3u8.add(normalizeUrl(raw))
                raw.contains("turbosplayer", true) -> frameLinks.add(normalizeUrl(raw))
                raw.contains("kotakajaib", true) -> frameLinks.add(normalizeUrl(raw))
                raw.contains("/file/", true) -> frameLinks.add(normalizeUrl(raw))
                raw.contains("/embed/", true) -> frameLinks.add(normalizeUrl(raw))
            }
        }

        extractPossibleUrls(html).forEach { raw ->
            when {
                raw.contains(".m3u8", true) -> foundM3u8.add(normalizeUrl(raw))
                raw.contains("turbosplayer", true) -> frameLinks.add(normalizeUrl(raw))
                raw.contains("kotakajaib", true) -> frameLinks.add(normalizeUrl(raw))
            }
        }

        foundM3u8.forEach { m3u8 ->
            generateM3u8(
                source = name,
                streamUrl = m3u8,
                referer = url
            ).forEach(callback)
        }

        frameLinks.forEach { frame ->
            val frameResponse = runCatching {
                app.get(
                    frame,
                    referer = url,
                    headers = headers
                )
            }.getOrNull()

            val frameHtml = frameResponse?.text.orEmpty()
            val frameM3u8 = linkedSetOf<String>()

            extractM3u8Urls(frameHtml).forEach { frameM3u8.add(normalizeUrl(it)) }

            val frameUnpacked = runCatching {
                if (!getPacked(frameHtml).isNullOrEmpty()) getAndUnpack(frameHtml) else null
            }.getOrNull()

            if (!frameUnpacked.isNullOrBlank()) {
                extractM3u8Urls(frameUnpacked).forEach { frameM3u8.add(normalizeUrl(it)) }
            }

            if (frameM3u8.isNotEmpty()) {
                frameM3u8.forEach { m3u8 ->
                    generateM3u8(
                        source = name,
                        streamUrl = m3u8,
                        referer = frame
                    ).forEach(callback)
                }
            } else {
                loadExtractor(
                    frame,
                    url,
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    private fun extractM3u8Urls(text: String): List<String> {
        return Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""")
            .findAll(text)
            .map { it.value.cleanEscapedUrl() }
            .distinct()
            .toList()
    }

    private fun extractPossibleUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""https?:\\?/\\?/[^"'\\\s<>]+""")
            .findAll(text)
            .map { it.value.cleanEscapedUrl() }
            .forEach { urls.add(it) }

        Regex("""['"]((?:https?:)?//[^'"]+)['"]""")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscapedUrl() }
            .forEach { urls.add(it) }

        Regex("""(?:file|src|url)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscapedUrl() }
            .forEach { urls.add(it) }

        Regex("""atob\(['"]([^'"]+)['"]\)""")
            .findAll(text)
            .mapNotNull { match ->
                runCatching {
                    base64Decode(match.groupValues[1]).trim()
                }.getOrNull()
            }
            .filter { it.startsWith("http") || it.startsWith("//") }
            .forEach { urls.add(it.cleanEscapedUrl()) }

        return urls.toList()
    }

    private fun String.cleanEscapedUrl(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun normalizeUrl(url: String): String {
        val cleanUrl = url.cleanEscapedUrl()

        return when {
            cleanUrl.startsWith("http", ignoreCase = true) -> cleanUrl
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }
}