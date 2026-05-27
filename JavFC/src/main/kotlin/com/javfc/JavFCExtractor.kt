package com.javfc

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getExtractorApiFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.javfc.JavFCUtils.absoluteUrl
import com.javfc.JavFCUtils.cleanText
import org.jsoup.nodes.Document

object JavFCExtractor {
    private val srcRegex = Regex("""(?i)(?:src|file|url)\s*[:=]\s*['\"](https?://[^'\"]+?)['\"]""")
    private val hlsRegex = Regex("""https?://[^\s'\"<>]+\.m3u8[^\s'\"<>]*""", RegexOption.IGNORE_CASE)
    private val mp4Regex = Regex("""https?://[^\s'\"<>]+\.mp4[^\s'\"<>]*""", RegexOption.IGNORE_CASE)

    suspend fun loadLinks(
        providerName: String,
        mainUrl: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = JavFCUtils.headers).document
        var found = false

        collectSubtitles(mainUrl, document, subtitleCallback)

        val directUrls = extractDirectUrls(mainUrl, document)
        val embedUrls = extractEmbedUrls(mainUrl, document)
        val code = data.substringAfterLast('/').substringBeforeLast('.').substringBefore('?')

        val tasks = mutableListOf<suspend () -> Unit>()

        directUrls.forEach { url ->
            tasks.add {
                if (url.contains(".m3u8", ignoreCase = true)) {
                    generateM3u8(
                        source = providerName,
                        streamUrl = url,
                        referer = data
                    ).forEach { link ->
                        found = true
                        callback(link)
                    }
                } else {
                    found = true
                    callback(
                        newExtractorLink(providerName, "$providerName MP4", url) {
                            referer = data
                            quality = Qualities.Unknown.value
                            headers = JavFCUtils.headers
                        }
                    )
                }
            }
        }

        embedUrls.forEach { embed ->
            tasks.add {
                loadExtractor(embed, data, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }

        if (code.isNotBlank()) {
            tasks.add {
                getExtractorApiFromName("SubtitleCat").takeIf { it.name == "SubtitleCat" }?.getUrl(
                    url = code,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        tasks.forEach { task ->
            try {
                task()
            } catch (e: Throwable) {
                Log.e("JavFC", "Extractor task failed: ${e.message}")
            }
        }

        return found
    }

    private suspend fun collectSubtitles(
        mainUrl: String,
        document: Document,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        document.select("track[kind=subtitles], track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val subUrl = absoluteUrl(mainUrl, element.attr("src").ifBlank { element.attr("href") }) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.text().ifBlank { "Subtitle" } })
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun extractDirectUrls(mainUrl: String, document: Document): List<String> {
        val raw = buildString {
            appendLine(document.select("#player-div script, script").joinToString("\n") { it.html() })
            appendLine(document.select("video source[src], source[src]").joinToString("\n") { it.attr("src") })
        }

        val direct = linkedSetOf<String>()
        srcRegex.findAll(raw).map { it.groupValues[1] }.forEach { url ->
            if (url.contains(".m3u8", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)) {
                direct.add(url)
            }
        }
        hlsRegex.findAll(raw).map { it.value }.forEach { direct.add(it) }
        mp4Regex.findAll(raw).map { it.value }.forEach { direct.add(it) }

        document.select("video[src], source[src]").forEach { source ->
            absoluteUrl(mainUrl, source.attr("src"))?.let { direct.add(it) }
        }

        return direct.filter { it.startsWith("http") }.distinct()
    }

    private fun extractEmbedUrls(mainUrl: String, document: Document): List<String> {
        val embeds = linkedSetOf<String>()

        document.select("iframe[src], embed[src]").forEach { iframe ->
            absoluteUrl(mainUrl, iframe.attr("src"))?.let { embeds.add(it) }
        }

        val scripts = document.select("#player-div script, script").joinToString("\n") { it.html() }
        srcRegex.findAll(scripts)
            .map { it.groupValues[1] }
            .filterNot { it.contains(".m3u8", ignoreCase = true) || it.contains(".mp4", ignoreCase = true) }
            .forEach { embeds.add(it) }

        return embeds.filter { it.startsWith("http") }.distinct()
    }

}
