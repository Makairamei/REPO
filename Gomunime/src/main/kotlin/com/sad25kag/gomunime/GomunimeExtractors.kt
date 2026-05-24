package com.sad25kag.gomunime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

private const val MAX_PLAYER_CANDIDATES = 8
private const val MAX_CHILD_CANDIDATES = 3
private const val MAX_CRAWL_DEPTH = 3
private const val EXTRACTION_TIMEOUT_MS = 28_000L

private data class HlsSource(
    val url: String,
    val referer: String,
    val quality: Int = Qualities.Unknown.value
)

private data class DirectSource(
    val url: String,
    val referer: String,
    val quality: Int = Qualities.Unknown.value
)

suspend fun loadGomunimeLinks(
    data: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
        runCatching {
            loadGomunimeLinksInternal(data, subtitleCallback, callback)
        }.getOrDefault(false)
    } ?: false
}

private suspend fun loadGomunimeLinksInternal(
    data: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(
        data,
        referer = HOME_URL,
        headers = defaultHeaders(HOME_URL),
        allowRedirects = true,
        timeout = 14L
    )

    val visited = linkedSetOf<String>()
    val players = linkedSetOf<String>()
    val hls = linkedMapOf<String, HlsSource>()
    val direct = linkedMapOf<String, DirectSource>()

    collectFromPage(
        document = response.document,
        html = response.text,
        baseUrl = response.url,
        referer = data,
        outPlayers = players,
        outHls = hls,
        outDirect = direct
    )

    if (emitCollected(hls, direct, callback)) return true

    val prioritizedPlayers = players
        .map { normalizeUrl(it, data) }
        .filterNot { isBadUrl(it) }
        .distinct()
        .sortedWith(compareBy<String> { playerPriority(it) }.thenBy { it.length })
        .take(MAX_PLAYER_CANDIDATES)

    for (player in prioritizedPlayers) {
        crawlPlayer(
            url = player,
            referer = data,
            visited = visited,
            outHls = hls,
            outDirect = direct,
            depth = 0
        )

        if (emitCollected(hls, direct, callback)) return true
    }

    var deliveredByExtractor = false
    prioritizedPlayers
        .filter { isExtractorFriendlyHost(it) }
        .take(4)
        .forEach { player ->
            val delivered = runCatching {
                loadExtractor(player, data, subtitleCallback, callback)
            }.getOrDefault(false)
            deliveredByExtractor = deliveredByExtractor || delivered
        }

    return deliveredByExtractor
}

private suspend fun crawlPlayer(
    url: String,
    referer: String,
    visited: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>,
    depth: Int
) {
    if (depth > MAX_CRAWL_DEPTH) return

    val fixedUrl = normalizeUrl(url, referer)
    if (fixedUrl in visited || isBadUrl(fixedUrl)) return
    visited.add(fixedUrl)

    when {
        fixedUrl.isHlsLike() -> {
            outHls[fixedUrl] = HlsSource(fixedUrl, referer, qualityFromUrl(fixedUrl))
            return
        }
        fixedUrl.isDirectVideo() -> {
            outDirect[fixedUrl] = DirectSource(fixedUrl, referer, qualityFromUrl(fixedUrl))
            return
        }
    }

    val response = runCatching {
        app.get(
            fixedUrl,
            referer = referer,
            headers = defaultHeaders(referer),
            allowRedirects = true,
            timeout = 11L
        )
    }.getOrNull() ?: return

    val currentUrl = response.url
    val html = response.text
    val trimmed = html.trimStart()

    if (trimmed.startsWith("#EXTM3U")) {
        outHls[currentUrl] = HlsSource(currentUrl, referer, qualityFromUrl(currentUrl))
        return
    }

    val nextPlayers = linkedSetOf<String>()

    collectFromPage(
        document = response.document,
        html = html,
        baseUrl = currentUrl,
        referer = fixedUrl,
        outPlayers = nextPlayers,
        outHls = outHls,
        outDirect = outDirect
    )

    if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return

    val unpacked = runCatching {
        if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
    }.getOrNull()

    if (!unpacked.isNullOrBlank()) {
        collectFromText(
            text = unpacked,
            baseUrl = currentUrl,
            referer = fixedUrl,
            outPlayers = nextPlayers,
            outHls = outHls,
            outDirect = outDirect
        )
    }

    if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return

    extractEncodedPayloads(html).forEach { decoded ->
        collectFromText(
            text = decoded,
            baseUrl = currentUrl,
            referer = fixedUrl,
            outPlayers = nextPlayers,
            outHls = outHls,
            outDirect = outDirect
        )
    }

    if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return

    for (next in nextPlayers
        .map { normalizeUrl(it, currentUrl) }
        .filterNot { it in visited }
        .filterNot { isBadUrl(it) }
        .sortedWith(compareBy<String> { playerPriority(it) }.thenBy { it.length })
        .take(MAX_CHILD_CANDIDATES)
    ) {
        crawlPlayer(
            url = next,
            referer = currentUrl,
            visited = visited,
            outHls = outHls,
            outDirect = outDirect,
            depth = depth + 1
        )

        if (outHls.isNotEmpty() || outDirect.isNotEmpty()) return
    }
}

private fun collectFromPage(
    document: Document,
    html: String,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    val selector = listOf(
        "iframe[src]",
        "iframe[data-src]",
        "iframe[data-litespeed-src]",
        "embed[src]",
        "video[src]",
        "video source[src]",
        "source[src]",
        "button[onclick]",
        "a[onclick]",
        "[data-frame]",
        "[data-src]",
        "[data-embed]",
        "[data-embed-url]",
        "[data-url]",
        "[data-link]",
        "[data-file]",
        "[data-video]",
        "[data-player]",
        "[data-server]",
        "[data-id]",
        "select.mirror option[value]",
        "option[value]",
        "a[href*='gdriveplayer']",
        "a[href*='anime-indo']",
        "a[href*='yup.php']",
        "a[href*='kotakajaib']",
        "a[href*='turbosplayer']",
        "a[href*='desustream']",
        "a[href*='yourupload']",
        "a[href*='mp4upload']",
        "a[href*='drive.google']",
        "a[href*='blogger']",
        "a[href*='blogspot']",
        "a[href*='googlevideo']",
        "a[href*='play.php']",
        "a[href*='.m3u8']",
        "a[href*='.mp4']",
        "a[href*='/embed/']",
        "a[href*='/player/']",
        "a[href*='/file/']"
    ).joinToString(", ")

    document.select(selector).forEach { element ->
        collectElementCandidates(element, baseUrl, referer, outPlayers, outHls, outDirect)
    }

    collectFromText(html, baseUrl, referer, outPlayers, outHls, outDirect)

    document.select("script").forEach { script ->
        val text = script.data().ifBlank { script.html() }
        collectFromText(text, baseUrl, referer, outPlayers, outHls, outDirect)
    }
}

private fun collectElementCandidates(
    element: Element,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    val rawValues = linkedSetOf<String>()

    listOf(
        "data-litespeed-src",
        "data-src",
        "data-frame",
        "data-embed",
        "data-embed-url",
        "data-url",
        "data-link",
        "data-file",
        "data-video",
        "data-player",
        "data-server",
        "data-id",
        "onclick",
        "src",
        "href",
        "value"
    ).forEach { attr ->
        element.attr(attr).trim().takeIf { it.isNotBlank() }?.let { rawValues.add(it) }
    }

    element.attributes().asList().forEach { attribute ->
        val value = attribute.value.trim()
        if (value.isNotBlank() && looksLikeUsefulRaw(value)) rawValues.add(value)
    }

    rawValues.forEach { raw ->
        decodeCandidateValues(raw, baseUrl).forEach { decoded ->
            addCandidate(decoded, baseUrl, referer, outPlayers, outHls, outDirect)
        }
    }
}

private fun collectFromText(
    text: String,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    val cleaned = text.cleanEscapedUrl()

    extractHlsUrls(cleaned, baseUrl).forEach { hls ->
        outHls[hls] = HlsSource(hls, referer, qualityFromUrl(hls))
    }

    extractGoogleVideoPlaybackUrls(cleaned).forEach { video ->
        outDirect[video] = DirectSource(video, referer, qualityFromUrl(video))
    }

    extractFileVariables(cleaned).forEach { raw ->
        addCandidate(raw, baseUrl, referer, outPlayers, outHls, outDirect)
    }

    extractPriorityUrls(cleaned).forEach { raw ->
        addCandidate(raw, baseUrl, referer, outPlayers, outHls, outDirect)
    }

    extractGenericJsArguments(cleaned).forEach { raw ->
        decodeCandidateValues(raw, baseUrl).forEach { decoded ->
            addCandidate(decoded, baseUrl, referer, outPlayers, outHls, outDirect)
        }
    }
}

private fun addCandidate(
    raw: String?,
    baseUrl: String,
    referer: String,
    outPlayers: MutableSet<String>,
    outHls: MutableMap<String, HlsSource>,
    outDirect: MutableMap<String, DirectSource>
) {
    if (raw.isNullOrBlank()) return

    val fixed = normalizeUrl(raw, baseUrl)
    if (isBadUrl(fixed)) return

    when {
        fixed.isHlsLike() -> outHls[fixed] = HlsSource(fixed, referer, qualityFromUrl(fixed))
        fixed.isDirectVideo() -> outDirect[fixed] = DirectSource(fixed, referer, qualityFromUrl(fixed))
        isLikelyPlayerUrl(fixed) -> outPlayers.add(fixed)
    }
}

private suspend fun emitCollected(
    hlsLinks: MutableMap<String, HlsSource>,
    directVideos: MutableMap<String, DirectSource>,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var delivered = false

    hlsLinks.values
        .distinctBy { it.url }
        .sortedByDescending { it.quality }
        .forEach { hls ->
            callback(
                newExtractorLink(
                    source = "Gomunime",
                    name = "Gomunime ${qualityName(hls.quality)}",
                    url = hls.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = hls.referer
                    this.quality = hls.quality
                    this.headers = defaultHeaders(hls.referer, includeOrigin = false)
                }
            )
            delivered = true
        }

    directVideos.values
        .distinctBy { it.url }
        .sortedByDescending { it.quality }
        .forEach { video ->
            val isHls = video.url.isHlsLike()
            callback(
                newExtractorLink(
                    source = "Gomunime",
                    name = "Gomunime ${qualityName(video.quality)}",
                    url = video.url,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = video.referer
                    this.quality = video.quality
                    this.headers = defaultHeaders(video.referer, includeOrigin = false)
                }
            )
            delivered = true
        }

    if (delivered) {
        hlsLinks.clear()
        directVideos.clear()
    }

    return delivered
}

private fun decodeCandidateValues(raw: String, baseUrl: String): List<String> {
    val results = linkedSetOf<String>()
    val clean = raw.cleanEscapedUrl().trim()
    if (clean.isBlank()) return emptyList()

    results.add(clean)

    val urlDecoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()
    if (!urlDecoded.isNullOrBlank() && urlDecoded != clean) results.add(urlDecoded.cleanEscapedUrl())

    extractIframeSrc(clean)?.let { results.add(it.cleanEscapedUrl()) }
    extractGenericJsArguments(clean).forEach { results.add(it.cleanEscapedUrl()) }

    listOf(clean, urlDecoded.orEmpty()).forEach { value ->
        val decoded = decodeBase64Flexible(value)
        if (!decoded.isNullOrBlank()) {
            results.add(decoded.cleanEscapedUrl())
            extractIframeSrc(decoded)?.let { results.add(it.cleanEscapedUrl()) }
            extractPriorityUrls(decoded).forEach { results.add(it.cleanEscapedUrl()) }
            extractHlsUrls(decoded, baseUrl).forEach { results.add(it.cleanEscapedUrl()) }
            extractGoogleVideoPlaybackUrls(decoded).forEach { results.add(it.cleanEscapedUrl()) }
        }
    }

    return results
        .map { normalizeUrl(it, baseUrl) }
        .filterNot { isBadUrl(it) }
        .toList()
}

private fun extractIframeSrc(text: String): String? {
    return Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
}

private fun extractHlsUrls(text: String, baseUrl: String): List<String> {
    val urls = linkedSetOf<String>()
    val clean = text.cleanEscapedUrl()

    Regex("""https?://[^"'\\\s<>]+\.m3u8[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""https?://[^"'\\\s<>]+play\.php\?[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .map { it.value.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|play\.php)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
        .map { it.cleanEscapedUrl() }
        .forEach { urls.add(it) }

    Regex("""["']([^"']*\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { normalizeUrl(it, baseUrl) }
        .forEach { urls.add(it) }

    Regex("""["']([^"']*play\.php\?[^"']*)["']""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { normalizeUrl(it, baseUrl) }
        .forEach { urls.add(it) }

    return urls.filterNot { isBadUrl(it) }.toList()
}

private fun extractGoogleVideoPlaybackUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()
    val clean = text.cleanEscapedUrl()

    Regex(
        """https?://[^"'\\\s<>]+googlevideo\.com/videoplayback[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscapedUrl().trimEnd(',', ';', ')', ']') }
        .filter { it.isValidGoogleVideoPlayback() }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+googlevideo\.com%2Fvideoplayback[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
        .map { it.cleanEscapedUrl().trimEnd(',', ';', ')', ']') }
        .filter { it.isValidGoogleVideoPlayback() }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractPriorityUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex(
        """https?://[^"'\\\s<>]+?(?:kotakajaib|turbosplayer|gdriveplayer|desustream|anime-indo|yourupload|mp4upload|drive\.google|googlevideo\.com/videoplayback|blogger|blogspot|play\.php|/embed/|/player/|/file/)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(text)
        .map { it.value.cleanEscapedUrl().trimEnd(',', ';', ')', ']') }
        .forEach { urls.add(it) }

    Regex("""https?:\\?/\\?/[^"'\\\s<>]+""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.value.cleanEscapedUrl() }
        .filter { isLikelyPlayerUrl(it) || it.isHlsLike() || it.isDirectVideo() }
        .forEach { urls.add(it) }

    Regex("""["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .filter { isLikelyPlayerUrl(it) || it.isHlsLike() || it.isDirectVideo() }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun extractFileVariables(text: String): List<String> {
    val urls = linkedSetOf<String>()

    Regex(
        """(?:file|src|url|source|hls|video|videoUrl|embed|embedUrl|contentUrl|player|link)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscapedUrl() }
        .filter { it.isHlsLike() || it.isDirectVideo() || isLikelyPlayerUrl(it) }
        .forEach { urls.add(it) }

    Regex("""sources\s*[:=]\s*\[([^\]]+)]""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .forEach { match ->
            extractFileVariables(match.groupValues.getOrNull(1).orEmpty()).forEach { urls.add(it) }
        }

    return urls.toList()
}

private fun extractGenericJsArguments(text: String): List<String> {
    val values = linkedSetOf<String>()

    Regex("""(?:load|open|change|switch|server|player|embed)[A-Za-z0-9_]*\s*\(([^)]{8,500})\)""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .flatMap { Regex("""["']([^"']{8,})["']""").findAll(it.groupValues[1]) }
        .mapNotNull { it.groupValues.getOrNull(1) }
        .forEach { values.add(it) }

    Regex("""["']([A-Za-z0-9+/_=-]{32,})["']""")
        .findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .filter { maybeBase64(it) }
        .forEach { values.add(it) }

    return values.toList()
}

private fun extractEncodedPayloads(text: String): List<String> {
    val decoded = linkedSetOf<String>()

    Regex("""atob\(["']([^"']{12,})["']\)""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .mapNotNull { decodeBase64Flexible(it.groupValues[1]) }
        .filter { looksLikeUsefulRaw(it) }
        .forEach { decoded.add(it.cleanEscapedUrl()) }

    Regex("""["']([A-Za-z0-9+/_=-]{40,})["']""")
        .findAll(text)
        .mapNotNull { decodeBase64Flexible(it.groupValues[1]) }
        .filter { looksLikeUsefulRaw(it) }
        .forEach { decoded.add(it.cleanEscapedUrl()) }

    return decoded.toList()
}

private fun decodeBase64Flexible(value: String): String? {
    val cleaned = value.trim()
        .replace("-", "+")
        .replace("_", "/")
        .replace("\\s".toRegex(), "")

    if (!maybeBase64(cleaned)) return null
    val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)

    return runCatching { String(Base64.decode(padded, Base64.DEFAULT)) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

private fun maybeBase64(value: String): Boolean {
    if (value.length < 12) return false
    if (value.contains("http", true)) return false
    return value.matches(Regex("""[A-Za-z0-9+/=_-]+"""))
}

private fun looksLikeUsefulRaw(value: String): Boolean {
    val lower = value.lowercase()
    return lower.contains("http") ||
        lower.contains(".m3u8") ||
        lower.contains("play.php") ||
        lower.contains("src=") ||
        lower.contains("googlevideo") ||
        lower.contains("gdrive") ||
        lower.contains("kotakajaib") ||
        lower.contains("turbosplayer") ||
        lower.contains("mp4")
}

private fun String.isHlsLike(): Boolean {
    val lower = lowercase()
    return lower.contains(".m3u8") || lower.contains("play.php?")
}

private fun String.isDirectVideo(): Boolean {
    val lower = lowercase()
    return lower.contains(".mp4") ||
        lower.isValidGoogleVideoPlayback() ||
        lower.contains("lh3.googleusercontent.com") ||
        lower.contains("blogger.googleusercontent.com")
}

private fun String.isValidGoogleVideoPlayback(): Boolean {
    val lower = lowercase().cleanEscapedUrl()
    if (!lower.contains("googlevideo.com")) return false
    if (!lower.contains("/videoplayback")) return false

    return lower.contains("expire=") ||
        lower.contains("itag=") ||
        lower.contains("mime=") ||
        lower.contains("ratebypass=") ||
        lower.contains("signature=") ||
        lower.contains("sig=")
}

private fun isLikelyPlayerUrl(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("gdriveplayer") ||
        lower.contains("embed2.php") ||
        lower.contains("anime-indo") ||
        lower.contains("yup.php") ||
        lower.contains("yourupload") ||
        lower.isValidGoogleVideoPlayback() ||
        lower.contains("blogger") ||
        lower.contains("blogspot") ||
        lower.contains("mp4upload") ||
        lower.contains("desustream") ||
        lower.contains("kotakajaib") ||
        lower.contains("turbosplayer") ||
        lower.contains("drive.google") ||
        lower.contains("lh3.googleusercontent") ||
        lower.contains("blogger.googleusercontent") ||
        lower.contains("/embed/") ||
        lower.contains("/player/") ||
        lower.contains("/file/") ||
        lower.contains("play.php") ||
        lower.contains(".mp4") ||
        lower.contains(".m3u8")
}

private fun isExtractorFriendlyHost(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("yourupload") ||
        lower.contains("mp4upload") ||
        lower.contains("drive.google") ||
        lower.contains("blogger") ||
        lower.contains("blogspot") ||
        lower.contains("kotakajaib") ||
        lower.contains("turbosplayer") ||
        lower.contains("gdriveplayer") ||
        lower.contains("desustream")
}

private fun playerPriority(url: String): Int {
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") || lower.contains("play.php") -> 0
        lower.contains("kotakajaib") -> 1
        lower.contains("turbosplayer") -> 2
        lower.contains("gdriveplayer") -> 3
        lower.contains("desustream") -> 4
        lower.contains("blogger") || lower.contains("blogspot") -> 5
        lower.contains("drive.google") -> 6
        lower.contains("yourupload") || lower.contains("mp4upload") -> 7
        lower.contains("googlevideo.com/videoplayback") -> 8
        else -> 9
    }
}

private fun isBadUrl(url: String): Boolean {
    val lower = url.lowercase().trim()

    return lower.isBlank() ||
        lower == "#" ||
        lower == HOME_URL ||
        (lower.contains("googlevideo.com") && !lower.contains("/videoplayback")) ||
        lower.startsWith("javascript:") ||
        lower.startsWith("data:image") ||
        lower.startsWith("mailto:") ||
        lower.contains("facebook.com") ||
        lower.contains("twitter.com") ||
        lower.contains("x.com/") ||
        lower.contains("telegram") ||
        lower.contains("whatsapp") ||
        lower.contains("adsbygoogle") ||
        lower.contains("doubleclick") ||
        lower.contains("googlesyndication") ||
        lower.contains("googletagmanager") ||
        lower.contains("google-analytics") ||
        lower.contains("histats") ||
        lower.contains("cloudflareinsights") ||
        lower.endsWith(".css") ||
        lower.endsWith(".js") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".gif") ||
        lower.endsWith(".svg")
}

private fun normalizeUrl(url: String, baseUrl: String): String {
    val clean = url.cleanEscapedUrl().trim()
    if (clean.isBlank()) return clean

    return when {
        clean.startsWith("http", ignoreCase = true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: HOME_URL
            "$origin$clean"
        }
        else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrElse { "${HOME_URL.trimEnd('/')}/$clean" }
    }
}

private fun defaultHeaders(referer: String, includeOrigin: Boolean = true): Map<String, String> {
    val origin = Regex("""^https?://[^/]+""").find(referer)?.value ?: HOME_URL
    val headers = linkedMapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )
    if (includeOrigin) headers["Origin"] = origin
    return headers
}

private fun qualityFromUrl(url: String): Int {
    val lower = url.lowercase()

    Regex("""(?:quality|res|q)[=_-]?(\d{3,4})""", RegexOption.IGNORE_CASE)
        .find(lower)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return qualityFromHeight(it) }

    Regex("""itag=(\d+)""", RegexOption.IGNORE_CASE)
        .find(lower)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { itag ->
            return when (itag) {
                "37", "96", "137", "299" -> Qualities.P1080.value
                "22", "59", "136", "298" -> Qualities.P720.value
                "18", "134" -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

    return when {
        lower.contains("1080") -> Qualities.P1080.value
        lower.contains("720") -> Qualities.P720.value
        lower.contains("480") -> Qualities.P480.value
        lower.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun qualityFromHeight(height: Int): Int {
    return when {
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        height >= 360 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }
}

private fun qualityName(quality: Int): String {
    return when (quality) {
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        else -> "Auto"
    }
}

private fun String.cleanEscapedUrl(): String {
    return trim()
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\u003A", ":")
        .replace("\\u003a", ":")
        .replace("\\u003D", "=")
        .replace("\\u003d", "=")
        .replace("\\u003F", "?")
        .replace("\\u003f", "?")
        .replace("\\u0026", "&")
        .replace("\\x2F", "/")
        .replace("\\x2f", "/")
        .replace("\\x3A", ":")
        .replace("\\x3a", ":")
        .replace("\\x3D", "=")
        .replace("\\x3d", "=")
        .replace("\\x26", "&")
        .replace("&amp;", "&")
}
