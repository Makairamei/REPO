package com.gomunime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

suspend fun loadGomunimeLinks(
    data: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(data)
    val document = response.document
    val links = linkedSetOf<String>()
    val foundM3u8 = linkedSetOf<String>()

    extractM3u8Urls(response.text).forEach { foundM3u8.add(it) }

    document.select("select.mirror option[value]").forEach { option ->
        val value = option.attr("value").trim()
        if (value.isBlank()) return@forEach

        decodeIframeUrl(value)?.let { links.add(normalizeUrl(it, data)) }
    }

    document.select(
        "iframe[src], " +
            "embed[src], " +
            "source[src], " +
            "video[src], " +
            "a[href], " +
            "[data-url], " +
            "[data-src], " +
            "[data-link]"
    ).forEach { element ->
        val raw = element.attr("src")
            .ifBlank { element.attr("href") }
            .ifBlank { element.attr("data-url") }
            .ifBlank { element.attr("data-src") }
            .ifBlank { element.attr("data-link") }
            .trim()

        if (raw.isBlank()) return@forEach

        val normalized = normalizeUrl(raw, data)

        when {
            normalized.contains(".m3u8", true) -> foundM3u8.add(normalized)
            isLikelyPlayerUrl(normalized) -> links.add(normalized)
        }
    }

    extractPossibleUrls(response.text).forEach { raw ->
        val normalized = normalizeUrl(raw, data)

        when {
            normalized.contains(".m3u8", true) -> foundM3u8.add(normalized)
            isLikelyPlayerUrl(normalized) -> links.add(normalized)
        }
    }

    links.toList().forEach { link ->
        crawlPlayerPage(
            url = link,
            referer = data,
            foundM3u8 = foundM3u8,
            callback = callback,
            subtitleCallback = subtitleCallback
        )
    }

    foundM3u8.forEach { m3u8 ->
        generateM3u8(
            source = "Gomunime",
            streamUrl = m3u8,
            referer = data
        ).forEach(callback)
    }

    if (foundM3u8.isNotEmpty()) return true

    links.forEach { link ->
        loadExtractor(link, data, subtitleCallback, callback)
    }

    return links.isNotEmpty()
}

private suspend fun crawlPlayerPage(
    url: String,
    referer: String,
    foundM3u8: MutableSet<String>,
    callback: (ExtractorLink) -> Unit,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    val response = runCatching {
        app.get(url, referer = referer)
    }.getOrNull() ?: return

    val document = response.document
    val html = response.text

    extractM3u8Urls(html).forEach { foundM3u8.add(it) }

    val direct = document.findDirectVideoSource()
    if (!direct.isNullOrBlank()) {
        if (direct.contains(".m3u8", true)) {
            foundM3u8.add(normalizeUrl(direct, url))
        } else {
            callback(
                newExtractorLink(
                    source = "Gomunime",
                    name = "Gomunime",
                    url = normalizeUrl(direct, url),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = qualityFromUrl(direct)
                }
            )
        }
        return
    }

    val cepat = document.findCepatPlaylistSource(url)
    if (!cepat.isNullOrBlank()) {
        foundM3u8.add(cepat)
        return
    }

    val unpacked = runCatching {
        if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
    }.getOrNull()

    if (!unpacked.isNullOrBlank()) {
        extractM3u8Urls(unpacked).forEach { foundM3u8.add(it) }

        extractPossibleUrls(unpacked).forEach { raw ->
            val normalized = normalizeUrl(raw, url)
            if (normalized.contains(".m3u8", true)) {
                foundM3u8.add(normalized)
            }
        }
    }
}

private fun decodeIframeUrl(encoded: String): String? {
    val decoded = runCatching {
        base64Decode(encoded).trim()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

    return when {
        decoded.startsWith("http", true) -> decoded
        decoded.startsWith("//") -> "https:$decoded"
        decoded.contains("<iframe", true) || decoded.contains("src=", true) -> {
            Regex("""src=["']([^"']+)["']""")
                .find(decoded)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
        }
        else -> null
    }
}

private fun Document.findCepatPlaylistSource(pageUrl: String): String? {
    val raw = Regex(
        """["']?file["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).find(html())
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    return runCatching {
        URI(pageUrl).resolve(raw).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun Document.findDirectVideoSource(): String? {
    val candidates = sequenceOf(
        selectFirst("video source[src]")?.attr("src"),
        selectFirst("source[src*='googlevideo']")?.attr("src"),
        selectFirst("source[src*='.mp4']")?.attr("src"),
        selectFirst("source[src*='.m3u8']")?.attr("src"),
        Regex("""https?://[^"' ]+googlevideo\.com/videoplayback[^"' ]*""")
            .find(html())?.value,
        Regex("""https?://[^"' ]+\.mp4[^"' ]*""")
            .find(html())?.value,
        Regex("""https?://[^"' ]+\.m3u8[^"' ]*""")
            .find(html())?.value
    )

    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}

private fun extractM3u8Urls(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""")
        .findAll(text)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""https?%3A%2F%2F[^"'\\\s<>]+?\.m3u8[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    return urls.toList()
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

    Regex("""(?:file|src|url|source|hls|video)\s*[:=]\s*['"]([^'"]+)['"]""")
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun isLikelyPlayerUrl(url: String): Boolean {
    return url.contains("googlevideo", true) ||
        url.contains("blogger", true) ||
        url.contains("blogspot", true) ||
        url.contains("mp4upload", true) ||
        url.contains("stream", true) ||
        url.contains("desustream", true) ||
        url.contains("kotakajaib", true) ||
        url.contains("turbosplayer", true) ||
        url.contains("/embed/", true) ||
        url.contains("/player/", true) ||
        url.contains("/file/", true) ||
        url.contains(".mp4", true) ||
        url.contains(".m3u8", true)
}

private fun normalizeUrl(url: String, baseUrl: String): String {
    val clean = url.cleanEscapedUrl()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: "https://gomunime.top"
            "$origin$clean"
        }
        else -> {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: "https://gomunime.top"
            "$origin/$clean"
        }
    }
}

private fun String.cleanEscapedUrl(): String {
    return this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()
}

private fun qualityFromUrl(url: String): Int {
    return when (Regex("""itag=(\d+)""").find(url)?.groupValues?.getOrNull(1)) {
        "37", "96", "137" -> Qualities.P1080.value
        "22", "59" -> Qualities.P720.value
        "18" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}