package com.hentaicop

import com.hentaicop.HentaiCopUtils.absoluteUrl
import com.hentaicop.HentaiCopUtils.cleanText
import com.hentaicop.HentaiCopUtils.decodePossibleBase64
import com.hentaicop.HentaiCopUtils.decodeUrl
import com.hentaicop.HentaiCopUtils.isPseudoUrl
import com.hentaicop.HentaiCopUtils.qualityFromText
import com.hentaicop.HentaiCopUtils.videoHeaders
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

object HentaiCopExtractor {
    private val keyValueMediaRegex = Regex(
        """(?i)(?:file|src|source|url|hls|playlist|video|videoUrl|hlsUrl)\s*[:=]\s*['\"]([^'\"]+)['\"]"""
    )
    private val iframeRegex = Regex("""(?i)<iframe[^>]+src=['\"]([^'\"]+)['\"]""")
    private val quotedMediaRegex = Regex(
        """(?i)['\"]((?:https?:)?//[^'\"<>\s\\]+?(?:\.m3u8|\.mp4|googlevideo\.com/[^'\"<>\s\\]+|videoplayback[^'\"<>\s\\]*)(?:\?[^'\"<>\s\\]*)?)['\"]"""
    )
    private val bareMediaRegex = Regex(
        """(?i)(?:https?:)?//[^\s'\"<>\\]+?(?:\.m3u8|\.mp4|googlevideo\.com/[^\s'\"<>\\]+|videoplayback[^\s'\"<>\\]*)(?:\?[^\s'\"<>\\]*)?"""
    )
    private val encodedHttpRegex = Regex("""https?%3A%2F%2F[^\s'\"<>]+""", RegexOption.IGNORE_CASE)

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isPseudoUrl(data)) return false

        val seenLinks = linkedSetOf<String>()
        var found = false
        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) callback(link)
        }

        val document = app.get(data, headers = HentaiCopUtils.siteHeaders, referer = data).document
        collectSubtitles(data, document, subtitleCallback)

        extractMedia(data, data, document).forEach { media ->
            val emitted = emitMedia(providerName, media.name, media.url, media.referer, seenLinks, emitLink)
            if (emitted) found = true
        }

        val pageServers = extractServers(data, document)
        for (server in pageServers) {
            val serverFound = resolveServer(providerName, mainUrl, server, seenLinks, subtitleCallback, emitLink)
            if (serverFound) found = true
        }

        if (!found) {
            val fallback = runCatching {
                loadExtractor(data, data, subtitleCallback) { link ->
                    if (seenLinks.add(link.url)) {
                        found = true
                        callback(link)
                    }
                }
            }.getOrDefault(false)
            found = found || fallback
        }

        return found
    }

    private suspend fun resolveServer(
        providerName: String,
        mainUrl: String,
        server: HentaiCopServer,
        seenLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var serverFound = false
        val normalizedServer = absoluteUrl(server.referer, server.url) ?: return false
        if (isPseudoUrl(normalizedServer)) return false

        if (isDirectMedia(normalizedServer) || isLikelyHlsCandidate(normalizedServer)) {
            val emitted = emitMedia(providerName, server.name, normalizedServer, server.referer, seenLinks, callback)
            if (emitted) return true
        }

        runCatching {
            loadExtractor(normalizedServer, server.referer, subtitleCallback) { link ->
                if (seenLinks.add(link.url)) {
                    serverFound = true
                    callback(link)
                }
            }
        }

        val embedDocument = runCatching {
            app.get(normalizedServer, headers = HentaiCopUtils.siteHeaders, referer = server.referer).document
        }.getOrNull()

        if (embedDocument != null) {
            collectSubtitles(normalizedServer, embedDocument, subtitleCallback)

            val media = extractMedia(normalizedServer, normalizedServer, embedDocument)
            for (item in media) {
                val emitted = emitMedia(providerName, item.name.ifBlank { server.name }, item.url, item.referer, seenLinks, callback)
                if (emitted) serverFound = true
            }

            val nestedServers = extractServers(normalizedServer, embedDocument)
                .filterNot { it.url == normalizedServer || isPseudoUrl(it.url) }
                .distinctBy { it.url }
            for (nested in nestedServers) {
                val nestedFound = runCatching {
                    loadExtractor(nested.url, nested.referer, subtitleCallback) { link ->
                        if (seenLinks.add(link.url)) {
                            serverFound = true
                            callback(link)
                        }
                    }
                }.getOrDefault(false)
                if (nestedFound) serverFound = true
            }
        }

        return serverFound
    }

    private suspend fun emitMedia(
        providerName: String,
        name: String,
        url: String,
        referer: String,
        seenLinks: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isPseudoUrl(url)) return false
        var emitted = false
        val headers = videoHeaders(referer)

        if (url.contains(".m3u8", true) || isLikelyHlsCandidate(url)) {
            val links = runCatching {
                generateM3u8(
                    source = providerName,
                    streamUrl = url,
                    referer = referer,
                    headers = headers
                )
            }.getOrDefault(emptyList())
            links.forEach { link ->
                if (seenLinks.add(link.url)) {
                    emitted = true
                    callback(link)
                }
            }
            if (emitted) return true
        }

        if (url.contains(".mp4", true) || url.contains("googlevideo", true) || url.contains("videoplayback", true)) {
            if (seenLinks.add(url)) {
                callback(
                    newExtractorLink(
                        providerName,
                        name.ifBlank { "$providerName MP4" },
                        url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = qualityFromText(url).let { if (it == Qualities.Unknown.value) Qualities.Unknown.value else it }
                        this.headers = headers
                    }
                )
                return true
            }
        }

        return false
    }

    private suspend fun collectSubtitles(
        pageUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = absoluteUrl(pageUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun extractServers(pageUrl: String, document: Document): List<HentaiCopServer> {
        val servers = linkedSetOf<HentaiCopServer>()
        val raw = normalizedHtml(document)

        document.select("iframe[src], embed[src]").forEachIndexed { index, iframe ->
            val url = absoluteUrl(pageUrl, iframe.attr("src")) ?: return@forEachIndexed
            if (isPseudoUrl(url)) return@forEachIndexed
            val name = cleanText(iframe.attr("title")).ifBlank { "Server ${index + 1}" }
            servers.add(HentaiCopServer(name, url, pageUrl))
        }

        document.select("select.mirror option, select option, .mirror option").forEachIndexed { index, option ->
            val value = option.attr("value").ifBlank { option.attr("data-src") }.ifBlank { option.attr("data-embed") }
            val decoded = decodePossibleBase64(value) ?: return@forEachIndexed
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val candidate = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (isPseudoUrl(candidate)) return@forEachIndexed
            val name = cleanText(option.text()).ifBlank { "Mirror ${index + 1}" }
            servers.add(HentaiCopServer(name, candidate, pageUrl))
        }

        document.select("[data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-video]").forEachIndexed { index, element ->
            val value = element.attr("data-src")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-video") }
            val decoded = decodePossibleBase64(value) ?: value
            val iframeUrl = iframeRegex.find(decoded)?.groupValues?.getOrNull(1)
            val url = absoluteUrl(pageUrl, iframeUrl ?: decoded) ?: return@forEachIndexed
            if (isPseudoUrl(url)) return@forEachIndexed
            val name = cleanText(element.text()).ifBlank { cleanText(element.attr("data-name")).ifBlank { "Server ${index + 1}" } }
            servers.add(HentaiCopServer(name, url, pageUrl))
        }

        keyValueMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && url.startsWith("http", true) && !isDirectMedia(url)) {
                servers.add(HentaiCopServer("Script ${index + 1}", url, pageUrl))
            }
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url) && !isDirectMedia(url)) servers.add(HentaiCopServer("Encoded ${index + 1}", url, pageUrl))
        }

        return servers.distinctBy { it.url }
    }

    private fun extractMedia(pageUrl: String, referer: String, document: Document): List<HentaiCopMedia> {
        val media = linkedSetOf<HentaiCopMedia>()
        val raw = normalizedHtml(document)

        document.select("video[src], video source[src], source[src]").forEachIndexed { index, source ->
            val url = absoluteUrl(pageUrl, source.attr("src")) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Source ${index + 1}", url, referer))
        }

        keyValueMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("File ${index + 1}", url, referer))
        }

        quotedMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Media ${index + 1}", url, referer))
        }

        bareMediaRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, match.value) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Direct ${index + 1}", url, referer))
        }

        encodedHttpRegex.findAll(raw).forEachIndexed { index, match ->
            val url = absoluteUrl(pageUrl, decodeUrl(match.value)) ?: return@forEachIndexed
            if (!isPseudoUrl(url)) media.add(HentaiCopMedia("Encoded ${index + 1}", url, referer))
        }

        return media
            .filter { isDirectMedia(it.url) || isLikelyHlsCandidate(it.url) }
            .distinctBy { it.url }
    }

    private fun normalizedHtml(document: Document): String {
        return document.html()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains("googlevideo.com") || lower.contains("videoplayback")
    }

    private fun isLikelyHlsCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (isPseudoUrl(lower)) return false
        if (lower.contains(".mp4") || lower.contains("googlevideo") || lower.contains("videoplayback")) return false
        return lower.contains("m3u8") || lower.contains("playlist") || lower.contains("hls") || lower.contains("master")
    }
}
