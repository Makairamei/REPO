package com.hentaicop

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

object HentaiCopUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "${HentaiCopSeeds.MAIN_URL}/"
    )

    // Kept as an alias because Provider/Parser/Extractor references this name.
    val headers: Map<String, String> = siteHeaders

    fun videoHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Origin" to originOf(referer).orEmpty(),
        "Referer" to referer
    ).filterValues { it.isNotBlank() }

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    fun decodeUrl(value: String): String {
        val cleaned = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
        return runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    }

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = decodeUrl(value.orEmpty())
            .trim()
            .trim('"', '\'', ',', ';')

        if (isPseudoUrl(raw)) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw

        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
            ?: originOf(baseUrl)?.trimEnd('/')?.plus("/")?.plus(raw.trimStart('/'))
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val path = data.ifBlank { "/" }
        val normalized = if (path.startsWith("http", true)) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized
        return when {
            normalized.endsWith("/") -> normalized + "page/$page/"
            normalized.endsWith(".html") -> normalized.removeSuffix(".html") + "/page/$page/"
            normalized.contains("?") -> normalized + "&paged=$page"
            else -> normalized.trimEnd('/') + "/page/$page/"
        }
    }

    fun searchUrl(mainUrl: String, query: String): String = "$mainUrl/?s=${query.urlEncoded()}"

    fun isHentaiCopUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault(url.lowercase(Locale.ROOT))
        return host == "hentaicop.com" || host.endsWith(".hentaicop.com") || host.contains("hepidrive")
    }

    fun isSeriesUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore('?')
        return Regex("/series/[^/?#]+/?$").containsMatchIn(lower)
    }

    fun isEpisodeUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore('?').trimEnd('/')
        if (!lower.contains("hentaicop.com")) return false
        return Regex("/(?:[^/?#]+-)?episode-\\d{1,4}(?:-[^/?#]+)?$").containsMatchIn(lower) ||
            Regex("/[^/?#]+-episode-\\d{1,4}(?:-[^/?#]+)?$").containsMatchIn(lower)
    }

    fun isPlayablePageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore('#')
        if (!lower.contains("hentaicop.com")) return false
        if (isBlockedCatalogUrl(lower)) return false
        return isSeriesUrl(lower) || isEpisodeUrl(lower)
    }

    fun isBlockedCatalogUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return listOf(
            "/genre/", "/studio/", "/producer/", "/tag/", "/category/", "/wp-",
            "/series/list-mode", "/jadwal-hentai-baru", "/privacy", "/dmca", "/contact",
            "/hentai/", "/jav/", "/2d/", "/uncensored/"
        ).any { lower.contains(it) }
    }

    fun episodeToSeriesUrl(mainUrl: String, episodeUrl: String): String {
        val clean = episodeUrl.substringBefore('?').trimEnd('/')
        val slug = clean.substringAfterLast('/')
        val seriesSlug = slug.replace(Regex("-episode-\\d{1,4}(?:-[^/?#]+)?$", RegexOption.IGNORE_CASE), "")
        return "$mainUrl/series/$seriesSlug/"
    }

    fun episodeNumber(text: String?): Int? {
        val source = text.orEmpty()
        Regex("(?i)(?:episode|eps|ep)\\s*[-:#]?\\s*(\\d{1,4})").find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("(?i)-episode-(\\d{1,4})(?:-|/|$)").find(source)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun cleanTitle(title: String?): String {
        return cleanText(title)
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
            .replace(Regex("(?i)\\s+episode\\s+\\d+.*$"), "")
            .replace(Regex("(?i)\\s+-\\s+hentaicop.*$"), "")
            .trim()
    }

    fun titleFromSlug(url: String): String {
        return url.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace(Regex("-episode-\\d{1,4}(?:-[^/?#]+)?$", RegexOption.IGNORE_CASE), "")
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .trim()
    }

    fun statusFromText(text: String?): com.lagradost.cloudstream3.ShowStatus? {
        val lower = text.orEmpty().lowercase(Locale.ROOT)
        return when {
            lower.contains("ongoing") -> com.lagradost.cloudstream3.ShowStatus.Ongoing
            lower.contains("completed") || lower.contains("complete") || lower.contains("tamat") -> com.lagradost.cloudstream3.ShowStatus.Completed
            else -> null
        }
    }

    fun decodePossibleBase64(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        val unescaped = decodeUrl(raw)
        when {
            unescaped.startsWith("http", true) || unescaped.startsWith("//") -> return unescaped
            unescaped.startsWith("<iframe", true) -> return unescaped
            unescaped.contains("iframe", true) && unescaped.contains("src", true) -> return unescaped
        }
        return runCatching {
            val bytes = Base64.getDecoder().decode(unescaped)
            String(bytes)
        }.getOrNull()
    }

    fun qualityFromText(value: String?): Int {
        val raw = value.orEmpty()
        Regex("(2160|1440|1080|720|480|360|240)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }

    fun isPseudoUrl(value: String?): Boolean {
        val raw = value.orEmpty().trim().lowercase(Locale.ROOT)
        return raw.isBlank() || raw == "#" || raw == "null" || raw == "undefined" ||
            raw.startsWith("javascript:") || raw.startsWith("about:") || raw.startsWith("data:") ||
            raw.startsWith("blob:") || raw.startsWith("intent:") || raw == "about:blank"
    }

    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
}
