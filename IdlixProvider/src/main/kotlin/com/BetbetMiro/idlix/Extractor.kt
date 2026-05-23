package com.BetbetMiro.idlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder

class IdlixHtmlExtractor : HtmlMediaExtractor() {
    override var name = "Idlix HTML"
    override var mainUrl = "https://ryangoslingfrance.com"
}

class Pm21P2p : HtmlMediaExtractor() {
    override var name = "PM21"
    override var mainUrl = "https://pm21.p2pplay.pro"
}

class Dm21Embed : HtmlMediaExtractor() {
    override var name = "DM21"
    override var mainUrl = "https://dm21.embed4me.vip"
}

class Dm21Upns : HtmlMediaExtractor() {
    override var name = "DM21"
    override var mainUrl = "https://dm21.upns.live"
}

class MePlayer : HtmlMediaExtractor() {
    override var name = "4MePlayer"
    override var mainUrl = "https://video.4meplayer.com"
}

class SerhMePlayer : HtmlMediaExtractor() {
    override var name = "4MePlayer"
    override var mainUrl = "https://serh.4meplayer.online"
}

class FourMePlayer : HtmlMediaExtractor() {
    override var name = "4MePlayer"
    override var mainUrl = "https://4meplayer.com"
}

class MinocHinos : HtmlMediaExtractor() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
}

class DingTezuni : HtmlMediaExtractor() {
    override var name = "Dingtezuni"
    override var mainUrl = "https://dingtezuni.com"
}

class DinTezuvio : HtmlMediaExtractor() {
    override var name = "Dintezuvio"
    override var mainUrl = "https://dintezuvio.com"
}

class Mivalyo : HtmlMediaExtractor() {
    override var name = "Mivalyo"
    override var mainUrl = "https://mivalyo.com"
}

class MovEarnPre : HtmlMediaExtractor() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class VeevTo : HtmlMediaExtractor() {
    override var name = "Veev"
    override var mainUrl = "https://veev.to"
}

class HgCloud : HtmlMediaExtractor() {
    override var name = "HGCloud"
    override var mainUrl = "https://hgcloud.to"
}

class HgLink : HtmlMediaExtractor() {
    override var name = "HGLink"
    override var mainUrl = "https://hglink.to"
}

class LuluVdoo : HtmlMediaExtractor() {
    override var name = "LuluVdoo"
    override var mainUrl = "https://luluvdoo.com"
}

class Majorplay : HtmlMediaExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.net"
}

class E2eMajorplay : HtmlMediaExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
}

open class HtmlMediaExtractor : ExtractorApi() {
    override var name = "HTML Media"
    override var mainUrl = ""
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.replace(" ", "%20")
        val domain = runCatching {
            "https://${URI(fixedUrl).host}"
        }.getOrDefault(mainUrl.ifBlank { fixedUrl })

        val response = runCatching {
            app.get(
                fixedUrl,
                referer = referer ?: domain,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to (referer ?: domain)
                ),
                timeout = 30L
            )
        }.getOrNull() ?: return

        val body = response.text.cleanEscaped()

        if (body.trimStart().startsWith("#EXTM3U")) {
            emitVideo(
                source = name,
                streamUrl = fixedUrl,
                referer = referer ?: domain,
                callback = callback
            )
            return
        }

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        response.document.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "source[src], " +
                "video[src], " +
                "video source[src], " +
                "a[href]"
        ).forEach { element ->
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            addCandidate(raw, fixedUrl, directLinks, embedLinks)
        }

        extractPlayableUrls(body).forEach { raw ->
            addCandidate(raw, fixedUrl, directLinks, embedLinks)
        }

        val unpacked = runCatching {
            if (!getPacked(body).isNullOrEmpty()) getAndUnpack(body) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { raw ->
                addCandidate(raw, fixedUrl, directLinks, embedLinks)
            }
        }

        extractSubtitles(body, domain, subtitleCallback)

        directLinks.distinct().forEach { link ->
            emitVideo(
                source = name,
                streamUrl = link,
                referer = fixedUrl,
                callback = callback
            )
        }

        embedLinks.distinct()
            .filter { it != fixedUrl }
            .forEach { embed ->
                val success = loadExtractor(
                    embed,
                    fixedUrl,
                    subtitleCallback,
                    callback
                )

                if (!success) {
                    val nested = runCatching {
                        app.get(
                            embed,
                            referer = fixedUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Accept" to "*/*",
                                "Referer" to fixedUrl
                            ),
                            timeout = 30L
                        ).text.cleanEscaped()
                    }.getOrNull().orEmpty()

                    if (nested.isNotBlank()) {
                        extractPlayableUrls(nested).forEach { raw ->
                            val fixed = normalizeUrl(raw, embed).replace(".txt", ".m3u8")

                            if (!isAdUrl(fixed) && (isHlsLike(fixed) || fixed.contains(".mp4", true))) {
                                emitVideo(
                                    source = name,
                                    streamUrl = fixed,
                                    referer = embed,
                                    callback = callback
                                )
                            }
                        }

                        extractSubtitles(nested, domain, subtitleCallback)
                    }
                }
            }
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")

        if (fixed.isBlank() || isAdUrl(fixed)) return

        when {
            isHlsLike(fixed) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
            fixed.startsWith("http", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun extractSubtitles(
        text: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val clean = text.cleanEscaped()

        Regex(
            """"(?:lang|label)"\s*:\s*"([^"]+)"[^}]*?"(?:path|url|file)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = match.groupValues[1].ifBlank { "Subtitle" }
            val rawUrl = match.groupValues[2].cleanEscaped()

            val subUrl = when {
                rawUrl.startsWith("http", true) -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> domain.trimEnd('/') + "/" + rawUrl.trimStart('/')
            }

            subtitleCallback(
                newSubtitleFile(
                    label,
                    subUrl
                )
            )
        }

        Regex(
            """\\"(?:lang|label)\\":\\"([^\\"]+)\\"[^}]*?\\"(?:path|url|file)\\":\\"([^\\"]+)\\"""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = match.groupValues[1].ifBlank { "Subtitle" }
            val rawUrl = match.groupValues[2].cleanEscaped()

            val subUrl = when {
                rawUrl.startsWith("http", true) -> rawUrl
                rawUrl.startsWith("//") -> "https:$rawUrl"
                else -> domain.trimEnd('/') + "/" + rawUrl.trimStart('/')
            }

            subtitleCallback(
                newSubtitleFile(
                    label,
                    subUrl
                )
            )
        }
    }
}

private suspend fun emitVideo(
    source: String,
    streamUrl: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    val fixedStream = streamUrl
        .cleanEscaped()
        .replace(".txt", ".m3u8")

    if (isAdUrl(fixedStream)) return

    callback(
        newExtractorLink(
            source = source,
            name = source,
            url = fixedStream,
            type = if (isHlsLike(fixedStream)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
        ) {
            this.referer = referer
            this.quality = getQualityFromName(fixedStream).takeIf {
                it != Qualities.Unknown.value
            } ?: qualityFromUrl(fixedStream)
        }
    )
}

private fun extractPlayableUrls(text: String): List<String> {
    val urls = linkedSetOf<String>()
    val clean = text.cleanEscaped()

    Regex(
        """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|txt)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?://[^"'\\\s<>]+?(?:majorplay|e2e\.majorplay|pm21|dm21|4meplayer|veev|hglink|hgcloud|luluvdoo|minochinos|dingtezuni|dintezuvio|mivalyo|movearnpre)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map { it.value.cleanEscaped() }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt|majorplay|pm21|dm21|4meplayer|veev|hglink|hgcloud|luluvdoo|minochinos|dingtezuni|dintezuvio|mivalyo|movearnpre)[^"'\\\s<>]*""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .map {
            runCatching {
                URLDecoder.decode(it.value, "UTF-8")
            }.getOrDefault(it.value)
        }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    Regex(
        """(?:file|src|source|url|videoSource|videoUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    ).findAll(clean)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.cleanEscaped().replace(".txt", ".m3u8") }
        .filter {
            it.contains(".m3u8", true) ||
                it.contains(".mp4", true) ||
                it.contains("majorplay", true) ||
                it.contains("pm21", true) ||
                it.contains("dm21", true) ||
                it.contains("4meplayer", true) ||
                it.contains("veev", true) ||
                it.contains("hglink", true) ||
                it.contains("hgcloud", true) ||
                it.contains("luluvdoo", true) ||
                it.contains("minochinos", true) ||
                it.contains("dingtezuni", true) ||
                it.contains("dintezuvio", true) ||
                it.contains("mivalyo", true) ||
                it.contains("movearnpre", true)
        }
        .filterNot { isAdUrl(it) }
        .forEach { urls.add(it) }

    return urls.toList()
}

private fun normalizeUrl(
    url: String,
    baseUrl: String
): String {
    val clean = url.cleanEscaped()

    return when {
        clean.startsWith("http", true) -> clean
        clean.startsWith("//") -> "https:$clean"
        clean.startsWith("/") -> {
            val origin = Regex("""^https?://[^/]+""")
                .find(baseUrl)
                ?.value
                ?: ""
            "$origin$clean"
        }

        else -> runCatching {
            URI(baseUrl).resolve(clean).toString()
        }.getOrDefault(clean)
    }
}

private fun isHlsLike(url: String): Boolean {
    return url.contains(".m3u8", true) ||
        (
            url.contains("majorplay", true) &&
                url.contains("config", true) &&
                url.contains(".json", true)
            )
}

private fun isAdUrl(url: String): Boolean {
    return url.contains("vast", true) ||
        url.contains("preroll", true) ||
        url.contains("qq288", true) ||
        url.contains("sngine", true) ||
        url.contains("/content/uploads/videos/", true) ||
        url.contains("demo.sngine.com", true) ||
        url.contains("doubleclick", true) ||
        url.contains("googlesyndication", true)
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