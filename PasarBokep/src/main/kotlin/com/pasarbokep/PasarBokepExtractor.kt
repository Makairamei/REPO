package com.pasarbokep

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

object PasarBokepExtractor {
    suspend fun resolve(
        pageUrl: String,
        mainUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(pageUrl, headers = PasarBokepUtils.headers, referer = mainUrl).document
        val html = document.outerHtml()
        val candidates = extractCandidates(document, html, pageUrl, mainUrl)

        var found = false
        for (url in candidates) {
            try {
                if (PasarBokepUtils.isDirectVideo(url)) {
                    callback(
                        newExtractorLink(
                            source = "PasarBokep",
                            name = "PasarBokep",
                            url = url,
                        ) {
                            this.referer = pageUrl
                            this.quality = PasarBokepUtils.directVideoQuality(url)
                            this.headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to pageUrl,
                            )
                        }
                    )
                    found = true
                } else {
                    val extracted = loadExtractor(url, pageUrl, subtitleCallback, callback)
                    if (extracted) found = true
                }
            } catch (_: Throwable) {
                // Continue trying other mirrors. One bad host must not kill playback.
            }
        }

        return found
    }

    private fun extractCandidates(document: Document, html: String, pageUrl: String, mainUrl: String): List<String> {
        val results = linkedSetOf<String>()

        document.select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            PasarBokepUtils.absoluteUrl(raw, mainUrl)?.let { fixed ->
                if (fixed != pageUrl && (PasarBokepUtils.isDirectVideo(fixed) || PasarBokepUtils.isPotentialExtractor(fixed, mainUrl))) {
                    results += fixed
                }
            }
        }

        val decodedHtml = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

        val regexes = listOf(
            Regex("""(?i)(?:file|src|source|video|embed|url)\s*[:=]\s*['\"]([^'\"]+(?:\.m3u8|\.mp4|\.webm|\.mkv|/embed/|/e/)[^'\"]*)['\"]"""),
            Regex("""(?i)<iframe[^>]+src=['\"]([^'\"]+)['\"]"""),
            Regex("""(?i)<source[^>]+src=['\"]([^'\"]+)['\"]"""),
            Regex("""https?:\\?/\\?/[^'\"\s<>]+(?:\.m3u8|\.mp4|\.webm|/embed/|/e/)[^'\"\s<>]*"""),
            Regex("""//[^'\"\s<>]+(?:\.m3u8|\.mp4|\.webm|/embed/|/e/)[^'\"\s<>]*"""),
        )

        regexes.forEach { regex ->
            regex.findAll(decodedHtml).forEach { match ->
                val raw = (match.groups[1]?.value ?: match.value)
                    .replace("\\/", "/")
                    .trim()
                PasarBokepUtils.absoluteUrl(raw, mainUrl)?.let { fixed ->
                    if (fixed != pageUrl && (PasarBokepUtils.isDirectVideo(fixed) || PasarBokepUtils.isPotentialExtractor(fixed, mainUrl))) {
                        results += fixed
                    }
                }
            }
        }

        return results
            .map { it.replace(" ", "%20") }
            .filterNot { it.contains("/wp-content/", ignoreCase = true) && !PasarBokepUtils.isDirectVideo(it) }
            .distinct()
    }
}
