package com.layarasia

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
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

open class LayarAsiaHtmlExtractor : ExtractorApi() {
    override var name = "LayarAsia Server"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = normalizeUrl(url, mainUrl.ifBlank { url })
        val host = runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault("")
        val origin = if (host.isNotBlank()) "https://$host" else mainUrl.ifBlank { pageUrl }

        val response = runCatching {
            app.get(
                pageUrl,
                referer = referer ?: origin,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val candidates = linkedSetOf<String>()
        val html = response.text.cleanEscaped()

        response.document.select(
            "source[src], video[src], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "embed[src], a[href], [data-src], [data-url], [data-file], [data-video]"
        ).forEach { element ->
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) candidates.add(normalizeUrl(raw, pageUrl))
        }

        extractPlayableUrls(html).forEach { candidates.add(normalizeUrl(it, pageUrl)) }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { candidates.add(normalizeUrl(it, pageUrl)) }
        }

        candidates
            .map { it.replace(".txt", ".m3u8") }
            .filterNot { isNoiseUrl(it) }
            .distinct()
            .forEach { link ->
                when {
                    link.contains(".m3u8", true) -> {
                        generateM3u8(
                            source = name,
                            streamUrl = link,
                            referer = pageUrl,
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to pageUrl)
                        ).forEach(callback)
                    }

                    link.contains(".mp4", true) || link.contains(".webm", true) -> {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = link,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                                this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value }
                                    ?: qualityFromUrl(link)
                            }
                        )
                    }
                }
            }
    }
}

class Smoothpre : LayarAsiaHtmlExtractor() {
    override var name = "Smoothpre"
    override var mainUrl = "https://smoothpre.com"
}

class EmturbovidExtractor : LayarAsiaHtmlExtractor() {
    override var name = "Turbovid"
    override var mainUrl = "https://emturbovid.com"
}

class BuzzServer : LayarAsiaHtmlExtractor() {
    override var name = "BuzzServer"
    override var mainUrl = "https://buzzserver.net"
}

class Nunaupns : VidStack() {
    override var name = "Nunaupns"
    override var mainUrl = "https://nuna.upns.xyz"
    override var requiresReferer = true
}

class Nunap2p : VidStack() {
    override var name = "Nunap2p"
    override var mainUrl = "https://nuna.strp2p.site"
    override var requiresReferer = true
}

class Minochinos : Dingtezuni() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
}

open class Dingtezuni : LayarAsiaHtmlExtractor() {
    override var name = "Dingtezuni"
    override var mainUrl = "https://dingtezuni.com"
}

class Dintezuvio : LayarAsiaHtmlExtractor() {
    override var name = "Dintezuvio"
    override var mainUrl = "https://dintezuvio.com"
}

class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

private fun extractPlayableUrls(text: String): List<String> {
    val results = linkedSetOf<String>()
    val clean = text.cleanEscaped()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
        .forEach { results.add(it) }

    Regex(
        """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
        .forEach { results.add(it) }

    Regex(
        """(?:file|src|source|url|videoUrl|video_url|hls|hlsUrl|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.contains(".m3u8", true) ||
                it.contains(".mp4", true) ||
                it.contains(".webm", true)
        }
        .forEach { results.add(it) }

    return results.toList()
}

private fun normalizeUrl(url: String, baseUrl: String): String {
    val clean = url.cleanEscaped().trim()
    return when {
        clean.isBlank() -> ""
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: ""
            "$origin$clean"
        }
        else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
    }
}

private fun isNoiseUrl(url: String): Boolean {
    val value = url.lowercase()
    return value.contains("doubleclick") ||
        value.contains("googlesyndication") ||
        value.contains("google-analytics") ||
        value.contains("facebook.com") ||
        value.contains("twitter.com") ||
        value.contains("telegram") ||
        value.contains("whatsapp") ||
        value.contains("/ads/") ||
        value.contains("banner") ||
        value.contains("tracking")
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
