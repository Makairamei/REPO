package com.anixcafe

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random

class Playmogo : AnixCafeGenericExtractor() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
}

class AnixCafeVideoplayer : AnixCafeGenericExtractor() {
    override val name = "AnixCafe Videoplayer"
    override val mainUrl = "https://videoplayer.vip"
}

open class AnixCafeGenericExtractor : ExtractorApi() {
    override val name = "AnixCafe"
    override val mainUrl = "https://anixcafe.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val visited = linkedSetOf<String>()
        AnixCafeExtractorHelper.resolveLink(
            url = url,
            label = name,
            referer = referer ?: mainUrl,
            visited = visited,
            subtitleCallback = subtitleCallback,
            callback = callback,
            useGenericExtractor = false,
        )
    }
}

object AnixCafeExtractorHelper {
    private const val MAX_TEXT_BODY_BYTES = 5_000_000L

    fun decodeMirror(value: String): List<String> {
        if (value.isBlank()) return emptyList()

        val decoded = runCatching {
            String(Base64.getDecoder().decode(value.trim()))
        }.getOrElse { value }

        val document = Jsoup.parse(decoded)
        val links = linkedSetOf<String>()

        document.select("iframe[src], iframe[data-src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let(links::add)
        }

        Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(decoded)
            .map { it.value.cleanEscaped() }
            .forEach(links::add)

        return links.toList()
    }

    suspend fun resolveLink(
        url: String,
        label: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        useGenericExtractor: Boolean = true,
        depth: Int = 0,
    ) {
        val fixedUrl = normalizeUrl(url, referer) ?: return
        if (!visited.add(fixedUrl)) return
        if (isNoiseFrame(fixedUrl)) return

        if (isDirectMedia(fixedUrl)) {
            emitDirectLink(fixedUrl, label, referer, callback)
            return
        }

        if (resolveDailymotion(fixedUrl, label, referer, callback)) return
        if (resolvePlaymogo(fixedUrl, label, referer, callback)) return

        if (useGenericExtractor) {
            runCatching { loadExtractor(fixedUrl, referer, subtitleCallback, callback) }
        }

        if (depth >= 2) return

        val response = runCatching {
            app.get(
                fixedUrl,
                referer = referer,
                headers = mapOf("Referer" to referer),
                timeout = 15000L
            )
        }.getOrNull() ?: return

        val contentType = response.headers["Content-Type"].orEmpty().lowercase()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull()

        if (isBinaryResponse(contentType, contentLength)) {
            return
        }

        val body = runCatching { response.text.cleanEscaped() }.getOrNull() ?: return
        val nested = linkedSetOf<String>()

        nested.addAll(extractMediaCandidates(body, fixedUrl))
        extractSubtitles(body, fixedUrl, subtitleCallback)

        Jsoup.parse(body, fixedUrl).select("source[src], video[src], iframe[src], iframe[data-src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("abs:href") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeUrl(it, fixedUrl) }
                ?.let(nested::add)
        }

        Jsoup.parse(body, fixedUrl).select("script").forEach { script ->
            val data = script.data()
            nested.addAll(extractMediaCandidates(data, fixedUrl))
            if (data.contains("eval(function(p,a,c,k,e,d)", true)) {
                runCatching { getAndUnpack(data) }
                    .getOrNull()
                    ?.let { unpacked ->
                        nested.addAll(extractMediaCandidates(unpacked.cleanEscaped(), fixedUrl))
                        extractSubtitles(unpacked, fixedUrl, subtitleCallback)
                    }
            }
        }

        nested.forEach { nestedUrl ->
            resolveLink(
                url = nestedUrl,
                label = label,
                referer = fixedUrl,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
                useGenericExtractor = useGenericExtractor,
                depth = depth + 1,
            )
        }
    }

    fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = raw.cleanEscaped()
            .takeIf {
                it.isNotBlank() &&
                    !it.startsWith("javascript:", true) &&
                    !it.startsWith("data:", true) &&
                    !it.equals("about:blank", true)
            } ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    fun isNoiseFrame(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com/plugins") ||
            lower.contains("histats.com") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("/ads/") ||
            lower.contains("banner")
    }

    fun isUnsupportedPlayerFrame(url: String): Boolean {
        return false
    }


    private suspend fun resolveDailymotion(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractDailymotionId(url) ?: return false
        val encodedReferer = URLEncoder.encode(referer, "UTF-8")
        val metaUrl = "https://geo.dailymotion.com/video/$videoId.json?legacy=true&embedder=$encodedReferer&geo=1&player-id=xir9o&publisher-id=x2fn6ma&locale=id"

        val jsonText = runCatching {
            app.get(
                metaUrl,
                referer = referer,
                headers = mapOf("Referer" to referer),
                timeout = 15000L
            ).text
        }.getOrNull()

        val qualities = runCatching {
            JSONObject(jsonText ?: "").optJSONObject("qualities")
        }.getOrNull()

        var emitted = false

        if (qualities != null) {
            val keys = qualities.keys()
            while (keys.hasNext()) {
                val qualityKey = keys.next()
                val entries = qualities.optJSONArray(qualityKey) ?: continue
                for (index in 0 until entries.length()) {
                    val source = entries.optJSONObject(index) ?: continue
                    val sourceUrl = source.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val type = source.optString("type")
                    if (!sourceUrl.contains(".m3u8", ignoreCase = true) && !type.contains("mpegURL", ignoreCase = true)) continue

                    callback(
                        newExtractorLink(
                            source = "Dailymotion",
                            name = if (qualityKey.equals("auto", ignoreCase = true)) "Dailymotion" else "Dailymotion [$qualityKey]",
                            url = sourceUrl.cleanEscaped(),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.headers = mapOf("Referer" to referer)
                            this.quality = getQualityFromName(qualityKey)
                        }
                    )
                    emitted = true
                }
            }
        }

        if (emitted) return true

        val page = runCatching {
            app.get(url, referer = referer, headers = mapOf("Referer" to referer), timeout = 15000L).text.cleanEscaped()
        }.getOrNull() ?: return false

        Regex("""https?://[^"'\\\s<>]+?\.m3u8[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(page)
            .map { it.value.cleanEscaped() }
            .distinct()
            .forEach { sourceUrl ->
                callback(
                    newExtractorLink(
                        source = "Dailymotion",
                        name = "Dailymotion",
                        url = sourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.headers = mapOf("Referer" to referer)
                    }
                )
                emitted = true
            }

        return emitted
    }

    private suspend fun resolvePlaymogo(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.contains("playmogo.com/e/", ignoreCase = true)) return false

        val page = runCatching {
            app.get(
                url,
                referer = referer,
                headers = mapOf("Referer" to referer),
                timeout = 15000L
            ).text.cleanEscaped()
        }.getOrNull() ?: return false

        val passPath = Regex("""\$\.\get\(['"]([^'"]*?/pass_md5/[^'"]+)['"]""")
            .find(page)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false

        val passUrl = normalizeUrl(passPath, url) ?: return false
        val token = passPath.substringBefore("?").trimEnd('/').substringAfterLast("/")
            .takeIf { it.isNotBlank() }
            ?: return false

        val mediaPrefix = runCatching {
            app.get(
                passUrl,
                referer = url,
                headers = mapOf("Referer" to url),
                timeout = 15000L
            ).text.trim()
        }.getOrNull()
            ?.takeIf { it.startsWith("http", ignoreCase = true) && !it.equals("RELOAD", ignoreCase = true) }
            ?: return false

        val directUrl = mediaPrefix + randomAlphaNum(10) + "?token=$token&expiry=${System.currentTimeMillis()}"
        callback(
            newExtractorLink(
                source = "Playmogo",
                name = if (label.isBlank()) "Playmogo" else "Playmogo [$label]",
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.headers = mapOf("Referer" to url)
                this.quality = getQualityFromName(label)
            }
        )
        return true
    }

    private fun extractDailymotionId(url: String): String? {
        return Regex("""(?:video=|/video/)([A-Za-z0-9]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""dailymotion\.com/embed/video/([A-Za-z0-9]+)""")
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun randomAlphaNum(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(length) {
                append(chars[Random.nextInt(chars.length)])
            }
        }
    }

    private suspend fun emitDirectLink(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = url.cleanEscaped().replace(".txt", ".m3u8")
        callback(
            newExtractorLink(
                source = label.substringBefore(" ").ifBlank { "AnixCafe" },
                name = label,
                url = fixed,
                type = if (fixed.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(label).takeIf { it != Qualities.Unknown.value }
                    ?: qualityFromUrl(fixed)
                this.headers = mapOf("Referer" to referer)
            }
        )
    }

    private fun extractMediaCandidates(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()

        val results = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'<>\\\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s"'<>\\]+?(?:playmogo|videoplayer|dood|streamwish|wishfast|filemoon|vidhide|vidguard|streamtape|mp4upload|mixdrop|voe|dailymotion)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|videoUrl|play_url|playUrl|hls|url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4|webm|txt)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { pattern ->
            pattern.findAll(clean).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrl(raw.replace(".txt", ".m3u8"), baseUrl)
                    ?.takeIf { shouldKeepCandidate(it) }
                    ?.let(results::add)
            }
        }

        return results
    }

    private suspend fun extractSubtitles(
        text: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        Regex("""https?://[^\s"'<>\\]+?\.(?:vtt|srt|ass)(?:\?[^"'<>\\\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text.cleanEscaped())
            .map { it.value }
            .distinct()
            .forEach { sub ->
                subtitleCallback(
                    newSubtitleFile(
                        "Subtitle",
                        normalizeUrl(sub, baseUrl) ?: sub
                    )
                )
            }
    }

    private fun shouldKeepCandidate(url: String): Boolean {
        val lower = url.lowercase()
        return !isNoiseFrame(url) &&
            !lower.contains("youtube.com") &&
            !lower.contains("youtu.be") &&
            !lower.contains("trailer") &&
            (
                isDirectMedia(url) ||
                    lower.contains("playmogo") ||
                    lower.contains("videoplayer") ||
                    lower.contains("embed") ||
                    lower.contains("player") ||
                    lower.contains("dood") ||
                    lower.contains("streamwish") ||
                    lower.contains("wishfast") ||
                    lower.contains("filemoon") ||
                    lower.contains("vidhide") ||
                    lower.contains("vidguard") ||
                    lower.contains("streamtape") ||
                    lower.contains("mp4upload") ||
                    lower.contains("mixdrop") ||
                    lower.contains("voe") ||
                    lower.contains("dailymotion")
                )
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains(".txt")
    }

    private fun isBinaryResponse(contentType: String, contentLength: Long?): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("octet-stream") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/x-mpegurl") ||
            contentType.contains("mpegurl") ||
            (contentLength != null && contentLength > MAX_TEXT_BODY_BYTES)
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
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
