package com.kazefuri

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
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
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

object KazefuriExtractorHelper {
    private val playableHostRegex = Regex(
        """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|dailymotion|geo\.dailymotion|ok\.ru|rumble|streamruby|turbovid|streamtape|filemoon|streamwish|wishfast|dood|vidhide|vidguard|voe|mixdrop|mp4upload)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    )

    fun decodeMirror(value: String): List<String> {
        val clean = value.trim()
        if (clean.isBlank()) return emptyList()

        val candidates = linkedSetOf<String>()
        candidates.add(clean)

        runCatching { URLDecoder.decode(clean, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(it) }

        runCatching { base64Decode(clean) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(it) }

        return candidates.flatMap { candidate ->
            val decoded = candidate.trim().replace("\\/", "/").replace("&amp;", "&")

            if (decoded.contains("<iframe", true) || decoded.contains("<source", true) || decoded.contains("<video", true)) {
                Jsoup.parse(decoded)
                    .select("iframe[src], iframe[data-src], source[src], video[src], a[href]")
                    .mapNotNull { element ->
                        element.attr("data-src")
                            .ifBlank { element.attr("src") }
                            .ifBlank { element.attr("href") }
                            .trim()
                            .takeIf { it.isNotBlank() }
                    }
            } else {
                listOf(decoded)
            }
        }
            .map { it.replace("\\/", "/").replace("&amp;", "&").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun normalizeUrl(url: String, baseUrl: String): String? {
        val clean = url.trim().replace("\\/", "/").replace("&amp;", "&")
        if (clean.isBlank()) return null

        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: return null
                origin.trimEnd('/') + clean
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    fun isNoiseFrame(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("ads") ||
            value.contains("banner") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking")
    }

    suspend fun resolveLink(
        url: String,
        label: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = normalizeUrl(url, referer)?.replace(".txt", ".m3u8") ?: return
        if (isNoiseFrame(fixed) || !emitted.add(fixed)) return

        when {
            fixed.contains(".m3u8", true) -> {
                generateM3u8(
                    source = label.ifBlank { "Kazefuri" },
                    streamUrl = fixed,
                    referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                ).forEach(callback)
            }
            fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                callback(
                    newExtractorLink(
                        source = label.ifBlank { "Kazefuri" },
                        name = label.ifBlank { "Kazefuri" },
                        url = fixed,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = getQualityFromName(fixed).takeIf { it != Qualities.Unknown.value }
                            ?: qualityFromUrl(fixed)
                    }
                )
            }
            else -> {
                val success = runCatching {
                    loadExtractor(fixed, referer, subtitleCallback, callback)
                }.getOrDefault(false)

                if (!success) {
                    resolveNested(
                        url = fixed,
                        referer = referer,
                        emitted = emitted,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
        }
    }

    private suspend fun resolveNested(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer
                ),
                timeout = 15L
            )
        }.getOrNull() ?: return

        val texts = mutableListOf(response.text.cleanEscaped())

        val unpacked = runCatching {
            if (!getPacked(response.text).isNullOrEmpty()) getAndUnpack(response.text) else null
        }.getOrNull()
        if (!unpacked.isNullOrBlank()) texts.add(unpacked.cleanEscaped())

        response.document.select("iframe[src], iframe[data-src], source[src], video[src], a[href]")
            .mapNotNull { element ->
                element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .forEach { nested ->
                resolveLink(
                    url = normalizeUrl(nested, url) ?: nested,
                    label = "Kazefuri",
                    referer = url,
                    emitted = emitted,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

        texts.flatMap { extractPlayableUrls(it) }
            .distinct()
            .forEach { nested ->
                resolveLink(
                    url = normalizeUrl(nested, url) ?: nested,
                    label = "Kazefuri",
                    referer = url,
                    emitted = emitted,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        playableHostRegex.findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isNoiseFrame(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoUrl|video_url|hls|hlsUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains(".webm", true) ||
                    it.contains("dailymotion", true) ||
                    it.contains("ok.ru", true) ||
                    it.contains("rumble", true) ||
                    it.contains("streamruby", true) ||
                    it.contains("turbovid", true)
            }
            .filterNot { isNoiseFrame(it) }
            .forEach { urls.add(it) }

        return urls.toList()
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

open class KazefuriGenericExtractor : ExtractorApi() {
    override var name = "Kazefuri"
    override var mainUrl = "https://sv4.kazefuri.cloud"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        KazefuriExtractorHelper.resolveLink(
            url = url,
            label = name,
            referer = referer ?: mainUrl,
            emitted = linkedSetOf(),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}

class KazefuriDailymotion : KazefuriGenericExtractor() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
}

class KazefuriGeoDailymotion : KazefuriGenericExtractor() {
    override var name = "Dailymotion Geo"
    override var mainUrl = "https://geo.dailymotion.com"
}

class KazefuriOkRuSSL : KazefuriGenericExtractor() {
    override var name = "OK.ru"
    override var mainUrl = "https://ok.ru"
}

class KazefuriOkRuHTTP : KazefuriGenericExtractor() {
    override var name = "OK.ru HTTP"
    override var mainUrl = "http://ok.ru"
}

class KazefuriRumble : KazefuriGenericExtractor() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
}

class KazefuriStreamRuby : KazefuriGenericExtractor() {
    override var name = "StreamRuby"
    override var mainUrl = "https://streamruby.com"
}

class KazefuriTurbovid : KazefuriGenericExtractor() {
    override var name = "Turbovid"
    override var mainUrl = "https://turbovid.eu"
}
