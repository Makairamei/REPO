package com.nonton01

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nonton01.Nonton01Utils.absoluteUrl
import com.nonton01.Nonton01Utils.decodeMaybe
import com.nonton01.Nonton01Utils.videoHeaders
import org.jsoup.nodes.Document

object Nonton01Extractor {
    private const val TAG = "Nonton01"
    private const val MAX_HOPS = 2
    private const val MAX_DIRECT_CANDIDATES = 20
    private const val MAX_EMBED_CANDIDATES = 10

    private val keyValueRegex = Regex(
        """(?i)(?:file|src|url|source|hls|hlsUrl|video|videoUrl|stream|streamUrl|playlist|embed|iframe|link)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val quotedUrlRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]"""
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['\"]([^'\"]+)['\"]""")
    private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
    private val atobRegex = Regex("""(?i)atob\s*\(\s*['\"]([A-Za-z0-9+/=_-]{16,})['\"]\s*\)""")
    private val base64StringRegex = Regex("""['\"]([A-Za-z0-9+/=]{28,})['\"]""")

    private val serverAttributes = listOf(
        "src", "href", "value", "data-src", "data-url", "data-link", "data-href",
        "data-file", "data-video", "data-video-url", "data-stream", "data-stream-url",
        "data-embed", "data-iframe", "data-player", "data-play", "data-server"
    )

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks start: $data")
        val emitted = linkedSetOf<String>()
        val found = resolvePage(providerName, data, data, 0, emitted, subtitleCallback, callback)
        if (!found) Log.e(TAG, "loadLinks no playable links for: $data")
        return found
    }

    private suspend fun resolvePage(
        providerName: String,
        pageUrl: String,
        referer: String,
        depth: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > MAX_HOPS) {
            Log.e(TAG, "max hop reached: $pageUrl")
            return false
        }

        val document = runCatching {
            app.get(pageUrl, headers = Nonton01Utils.siteHeaders, referer = referer).document
        }.onFailure { Log.e(TAG, "GET failed $pageUrl: ${it.message}") }.getOrNull() ?: return false

        collectSubtitles(pageUrl, document, subtitleCallback)
        var found = false

        val direct = prioritizeCandidates(extractDirectMedia(pageUrl, document))
        val embeds = prioritizeCandidates(extractEmbeds(pageUrl, document))
        Log.e(TAG, "captured page=$pageUrl depth=$depth direct=${direct.size} embeds=${embeds.size}")

        for (url in direct.take(MAX_DIRECT_CANDIDATES)) {
            Log.e(TAG, "direct candidate: $url")
            val emittedNow = emitDirect(providerName, url, pageUrl, emitted, callback)
            found = found || emittedNow
            if (!emittedNow) {
                val extractorFound = runExtractor(url, pageUrl, emitted, subtitleCallback, callback)
                found = found || extractorFound
            }
        }

        for (embed in embeds.filterNot { direct.contains(it) }.take(MAX_EMBED_CANDIDATES)) {
            Log.e(TAG, "embed candidate: $embed referer=$pageUrl")
            val extractorFound = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback)
            found = found || extractorFound
            if (!extractorFound && depth < MAX_HOPS && canRecurseInto(embed, pageUrl)) {
                val nestedFound = resolvePage(providerName, embed, pageUrl, depth + 1, emitted, subtitleCallback, callback)
                found = found || nestedFound
            }
        }

        if (!found) {
            Log.e(TAG, "fallback extractor on page: $pageUrl")
            found = runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
        }
        return found
    }

    private suspend fun emitDirect(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                looksLikeHls(url) -> {
                    val links = generateM3u8(
                        source = providerName,
                        streamUrl = url,
                        referer = referer,
                        headers = videoHeaders(referer)
                    )
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    if (links.isEmpty()) Log.e(TAG, "generateM3u8 returned empty: $url")
                    links.isNotEmpty()
                }
                looksLikeDirectMp4(url) -> {
                    if (emitted.add(url)) {
                        callback(
                            newExtractorLink(providerName, "$providerName MP4", url) {
                                this.referer = referer
                                this.quality = Qualities.Unknown.value
                                this.headers = videoHeaders(referer)
                            }
                        )
                        true
                    } else {
                        false
                    }
                }
                else -> {
                    // Some Nonton01 player scripts hide HLS behind extensionless URLs,
                    // but do not waste M3u8Helper calls on generic embed/player pages.
                    if (!shouldTryHlsFallback(url)) return false
                    val links = runCatching {
                        generateM3u8(
                            source = providerName,
                            streamUrl = url,
                            referer = referer,
                            headers = videoHeaders(referer)
                        )
                    }.getOrDefault(emptyList())
                    links.forEach { link ->
                        if (emitted.add(link.url)) callback(link)
                    }
                    links.isNotEmpty()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed $url: ${e.message}")
            false
        }
    }

    private suspend fun runExtractor(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        return try {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) {
                    found = true
                    Log.e(TAG, "loadExtractor emitted: ${link.url}")
                    callback(link)
                }
            }
            if (!found) Log.e(TAG, "loadExtractor emitted 0 links: $url")
            found
        } catch (e: Throwable) {
            Log.e(TAG, "loadExtractor failed $url: ${e.message}")
            false
        }
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = Nonton01Utils.cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            runCatching { subtitleCallback(newSubtitleFile(label, url)) }
        }
    }

    private fun extractDirectMedia(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }

        val html = normalizedHtml(document)
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.filter { looksLikeMediaOrPlayer(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        extractAttributeUrls(pageUrl, document).filter { looksLikeEmbed(it) }.forEach { out.add(it) }

        val html = normalizedHtml(document)
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        iframeRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        decodeBase64Candidates(html).mapNotNull { normalizeUrl(pageUrl, it) }.filter { looksLikeEmbed(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractAttributeUrls(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("iframe, embed, video, source, option, a[href], button, div, span").forEach { element ->
            serverAttributes.forEach { attr ->
                normalizeUrl(pageUrl, element.attr(attr))?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    private fun normalizedHtml(document: Document): String = document.html()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\u002f", "/")

    private fun normalizeUrl(pageUrl: String, value: String?): String? {
        val raw = decodeMaybe(value.orEmpty())
            .trim()
            .trim('"', '\'', ',', ';', ' ')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "null" || low == "undefined" || low == "about:blank") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (low.contains(".jpg") || low.contains(".png") || low.contains(".webp") || low.contains(".gif")) return null
        if (low.contains("youtube.com") || low.contains("youtu.be") || low.contains("trailer")) return null
        if (isDeniedUrl(low)) return null
        return absoluteUrl(pageUrl, raw)
    }

    private fun decodeBase64Candidates(html: String): List<String> {
        val out = linkedSetOf<String>()
        val candidates = mutableListOf<String>()
        atobRegex.findAll(html).map { it.groupValues[1] }.forEach { candidates.add(it) }
        base64StringRegex.findAll(html).map { it.groupValues[1] }.take(80).forEach { candidates.add(it) }
        candidates.forEach { encoded ->
            val decoded = decodeBase64(encoded) ?: return@forEach
            if (decoded.contains("http", ignoreCase = true) || decoded.contains("iframe", ignoreCase = true) || decoded.contains("m3u8", ignoreCase = true)) {
                quotedUrlRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                iframeRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                keyValueRegex.findAll(decoded).map { it.groupValues[1] }.forEach { out.add(it) }
                if (decoded.startsWith("http", ignoreCase = true)) out.add(decoded)
            }
        }
        return out.distinct()
    }

    private fun decodeBase64(value: String): String? {
        return runCatching {
            val normalized = value.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()
    }

    private fun looksLikeHls(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains("m3u8") || low.contains("playlist") || low.contains("master.m3u")
    }

    private fun looksLikeDirectMp4(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".mp4") || low.contains("googlevideo") || low.contains("videoplayback")
    }

    private fun looksLikeMediaOrPlayer(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            (looksLikeHls(url) ||
                looksLikeDirectMp4(url) ||
                isKnownExtractorHost(low) ||
                looksLikeEmbed(url) ||
                shouldTryHlsFallback(url))
    }

    private fun looksLikeEmbed(url: String): Boolean {
        val low = url.lowercase()
        return !isDeniedUrl(low) &&
            !looksLikeDirectMp4(url) &&
            !looksLikeHls(url) &&
            (isKnownExtractorHost(low) ||
                low.contains("/embed/") ||
                low.contains("/player/") ||
                low.contains("?embed=") ||
                low.contains("?source="))
    }

    private fun shouldTryHlsFallback(url: String): Boolean {
        val low = url.lowercase()
        if (isDeniedUrl(low) || looksLikeDirectMp4(url) || looksLikeEmbed(url)) return false
        return low.contains("m3u") ||
            low.contains("playlist") ||
            low.contains("hls") ||
            low.contains("master") ||
            low.contains("chunklist") ||
            low.contains("manifest")
    }

    private fun isKnownExtractorHost(low: String): Boolean {
        return listOf(
            "myvidplay", "minochinos", "hglink", "streamtape", "dood", "filemoon",
            "vidhide", "vidguard", "filelions", "streamwish", "streamsb", "sbembed",
            "voe.sx", "uqload", "mixdrop", "fembed", "doodstream", "streamlare"
        ).any { low.contains(it) }
    }

    private fun isDeniedUrl(low: String): Boolean {
        return low.contains("facebook.com") ||
            low.contains("twitter.com") ||
            low.contains("instagram.com") ||
            low.contains("whatsapp") ||
            low.contains("telegram") ||
            low.contains("disqus") ||
            low.contains("googletagmanager") ||
            low.contains("google-analytics") ||
            low.contains("doubleclick") ||
            low.contains("/wp-content/themes/") ||
            low.endsWith(".css") ||
            low.endsWith(".js")
    }

    private fun canRecurseInto(embed: String, pageUrl: String): Boolean {
        val embedOrigin = Nonton01Utils.originOf(embed)
        val pageOrigin = Nonton01Utils.originOf(pageUrl)
        val low = embed.lowercase()
        return embedOrigin != null &&
            embedOrigin != pageOrigin &&
            !Nonton01Utils.isSameHost(embed) &&
            !isDeniedUrl(low) &&
            (isKnownExtractorHost(low) || low.contains("/embed/") || low.contains("/player/"))
    }

    private fun prioritizeCandidates(urls: List<String>): List<String> {
        return urls.distinct().sortedBy { url ->
            when {
                looksLikeHls(url) -> 0
                looksLikeDirectMp4(url) -> 1
                isKnownExtractorHost(url.lowercase()) -> 2
                else -> 3
            }
        }
    }
}
