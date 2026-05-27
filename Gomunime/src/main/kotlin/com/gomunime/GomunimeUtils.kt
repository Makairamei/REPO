package com.gomunime

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

object GomunimeUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "${GomunimeSeeds.MAIN_URL}/"
    )

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = decodeUrl(
            value.orEmpty()
                .trim()
                .trim('"', '\'', ',', ';')
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
        )

        if (raw.isBlank() || raw == "#" || raw.equals("null", true) || raw.startsWith("javascript:", true)) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) return baseUrl.trimEnd('/') + raw
        return runCatching { URI(baseUrl).resolve(raw).toString() }.getOrNull()
            ?: baseUrl.trimEnd('/') + "/" + raw
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val path = data.ifBlank { "/" }
        val normalized = if (path.startsWith("http")) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized

        return when {
            normalized.endsWith("/") -> normalized + "page/$page/"
            normalized.contains("?") -> normalized + "&page=$page"
            else -> normalized.trimEnd('/') + "/page/$page/"
        }
    }

    fun searchUrl(mainUrl: String, query: String): String = "$mainUrl/?s=${query.urlEncoded()}"

    fun isAnimeDetailUrl(url: String): Boolean {
        val normalized = url.lowercase(Locale.ROOT).substringBefore("#")
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        val host = uri.host.orEmpty()
        if (!host.contains("gomunime", ignoreCase = true)) return false
        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank()) return false
        if (path in setOf("home", "ongoing", "tamat", "movies", "top", "download-app")) return false
        if (path.startsWith("genre/") || path.startsWith("genres/")) return false
        if (path.startsWith("status/") || path.startsWith("type/")) return false
        if (path.startsWith("koleksi/") || path.startsWith("download")) return false
        if (path.startsWith("tag/") || path.startsWith("year/") || path.startsWith("page/")) return false
        if (normalized.contains("?s=")) return false
        if (normalized.contains("/wp-") || normalized.contains("/feed")) return false
        return true
    }

    fun slugFromUrl(url: String): String {
        return runCatching { URI(url).path.trim('/').substringAfterLast('/') }
            .getOrDefault(url.substringAfterLast('/'))
            .substringBefore('?')
    }

    fun titleFromSlug(url: String): String {
        val slug = slugFromUrl(url)
            .replace(Regex("(?i)-episode-\\d+.*$"), "")
            .replace(Regex("(?i)-sub(?:title)?-indo.*$"), "")
        return slug.split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .trim()
    }

    fun cleanAnimeTitle(value: String?, url: String? = null): String {
        var text = cleanText(value)
            .replace(Regex("(?i)^new\\s+"), "")
            .replace(Regex("(?i)^tonton\\s+"), "")
            .replace(Regex("(?i)^nonton\\s+anime\\s+"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+sub\\s*indo(?:\\s+hd)?$"), "")
            .replace(Regex("(?i)\\s+subtitle\\s+indonesia$"), "")
            .replace(Regex("^★\\s*[\\d.]+\\s*"), "")
            .replace(Regex("(?i)^episode\\s+\\d+\\s+"), "")
            .trim()

        text = text
            .replace(Regex("(?i)\\s+(TV|Movie|OVA|ONA|Special)\\s*•.*$"), "")
            .replace(Regex("(?i)\\s+—\\s+Full\\s+Movie$"), "")
            .trim()

        val ep = episodeNumber(text)
        if (ep != null) {
            text = text.replace(Regex("(?i)\\s+Episode\\s+$ep\\b.*$"), "").trim()
        }

        if (text.length < 3 && url != null) text = titleFromSlug(url)
        return text.ifBlank { url?.let { titleFromSlug(it) }.orEmpty() }
    }

    fun decodeBase64Html(value: String): String? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null

        val candidates = listOf(
            normalized,
            decodeUrl(normalized),
            normalized.replace('-', '+').replace('_', '/')
        ).distinct()

        return candidates.firstNotNullOfOrNull { candidate ->
            val padded = candidate + "=".repeat((4 - candidate.length % 4) % 4)
            runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
                ?: runCatching { String(Base64.getUrlDecoder().decode(padded)) }.getOrNull()
        }
    }

    fun decodeUrl(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    fun episodeNumber(value: String?): Int? {
        val text = value.orEmpty()
        return Regex("""(?i)(?:episode|eps|ep)\s*[-:_]?\s*(\d{1,4})""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)-episode-(\d{1,4})""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)/(?:[^/?#]*-)?episode-(\d{1,4})(?:[/?#-]|$)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun typeFromText(value: String?): com.lagradost.cloudstream3.TvType {
        val text = value.orEmpty().lowercase(Locale.ROOT)
        return when {
            text.contains("movie") -> com.lagradost.cloudstream3.TvType.AnimeMovie
            text.contains("ova") || text.contains("ona") || text.contains("special") -> com.lagradost.cloudstream3.TvType.OVA
            else -> com.lagradost.cloudstream3.TvType.Anime
        }
    }

    fun statusFromText(value: String?): com.lagradost.cloudstream3.ShowStatus? {
        val text = value.orEmpty().lowercase(Locale.ROOT)
        return when {
            text.contains("ongoing") -> com.lagradost.cloudstream3.ShowStatus.Ongoing
            text.contains("tamat") || text.contains("completed") || text.contains("complete") -> com.lagradost.cloudstream3.ShowStatus.Completed
            else -> null
        }
    }
}
