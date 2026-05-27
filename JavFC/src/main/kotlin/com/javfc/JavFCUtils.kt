package com.javfc

import java.net.URLEncoder

object JavFCUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "${JavFCSeeds.MAIN_URL}/"
    )

    fun cleanText(value: String?): String {
        return value.orEmpty()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun String.urlEncoded(): String {
        return URLEncoder.encode(this, "UTF-8")
    }

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = value.orEmpty().trim()
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", ignoreCase = true)) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) return baseUrl.trimEnd('/') + raw
        return baseUrl.trimEnd('/') + "/" + raw
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        if (data.startsWith("search:")) {
            val query = data.removePrefix("search:")
            val offset = ((page - 1) * 24).coerceAtLeast(0)
            return "$mainUrl/search?per_page=$offset&q=${query.urlEncoded()}"
        }

        val path = data.ifBlank { "/home/vids.html" }
        val normalized = if (path.startsWith("http")) path else mainUrl.trimEnd('/') + "/" + path.trimStart('/')
        if (page <= 1) return normalized

        return when {
            normalized.endsWith(".html") -> normalized.removeSuffix(".html") + "/$page.html"
            normalized.contains("?") -> normalized + "&page=$page"
            else -> normalized.trimEnd('/') + "/$page"
        }
    }

    fun isLikelyMovieUrl(url: String): Boolean {
        return url.contains(JavFCSeeds.MAIN_URL) &&
            url.endsWith(".html") &&
            !url.contains("/genre/") &&
            !url.contains("/home/") &&
            !url.contains("/star/") &&
            !url.contains("all-movies") &&
            !url.contains("privacy") &&
            !url.contains("dmca")
    }
}
