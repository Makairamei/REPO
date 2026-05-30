package com.filmlokal

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object FilmLokalUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "${FilmLokalSeeds.MAIN_URL}/"
    )

    fun videoHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to referer,
        "Origin" to (originOf(referer) ?: FilmLokalSeeds.MAIN_URL)
    )

    private val catalogSegments = setOf(
        "", "page", "dmca", "faq", "kontak", "contact", "best-rating",
        "21-mph", "sub-indo", "sub-english", "censored", "film-semi", "uncensored",
        "live-streaming-igo", "jeruk-barat", "asian-amateur", "jav",
        "film-series", "action", "adventure", "animation", "comedy", "crime",
        "drama", "fantasy", "horror", "mystery", "romance", "sci-fi", "thriller",
        "year", "country", "genre", "quality", "tag", "cast", "director", "author",
        "wp-admin", "wp-content", "wp-includes"
    )

    fun cleanText(value: String?): String = value.orEmpty()
        .replace("\u00a0", " ")
        .replace("&amp;", "&")
        .replace("&#8211;", "-")
        .replace("&#8217;", "'")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    fun originOf(url: String?): String? {
        val raw = url.orEmpty().trim()
        if (raw.isBlank()) return null
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull()
    }

    fun absoluteUrl(baseUrl: String, value: String?): String? {
        val raw = value.orEmpty().trim()
            .removePrefix("url(")
            .removeSuffix(")")
            .trim('"', '\'', ' ', ',', ';')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "about:blank" || low == "null" || low == "undefined") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val origin = originOf(baseUrl) ?: FilmLokalSeeds.MAIN_URL
        if (raw.startsWith("/")) return origin.trimEnd('/') + raw
        val base = baseUrl.substringBeforeLast('/', "${origin}/")
        return base.trimEnd('/') + "/" + raw
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val raw = if (data.startsWith("http")) data else mainUrl.trimEnd('/') + "/" + data.trimStart('/')
        if (raw.contains("%d")) {
            if (page <= 1) {
                return raw
                    .replace("/page/%d/", "/")
                    .replace("page/%d/", "")
                    .replace("/%d/", "/")
                    .replace("%d", "")
                    .replace(Regex("(?<!:)//+"), "/")
                    .replace("https:/", "https://")
                    .trimEnd('/') + "/"
            }
            return raw.replace("%d", page.toString())
        }
        if (page <= 1) return raw
        return raw.trimEnd('/') + "/page/$page/"
    }

    fun searchUrl(mainUrl: String, query: String): String {
        return "${mainUrl.trimEnd('/')}/?s=${query.urlEncoded()}"
    }

    fun isValidPoster(url: String?): Boolean {
        val raw = url.orEmpty().trim()
        val low = raw.lowercase()
        val path = runCatching { URI(raw).path.orEmpty().lowercase() }.getOrDefault(low)
        val fileName = path.substringAfterLast('/')
        return low.startsWith("http") &&
            !low.startsWith("data:") &&
            !low.contains("/logo") &&
            !low.contains("logo-") &&
            !low.contains("favicon") &&
            !low.contains("cropped-") &&
            !low.contains("placeholder") &&
            !low.contains("no-image") &&
            !low.endsWith(".svg") &&
            !path.contains("/wp-content/themes/") &&
            !path.contains("/wp-content/plugins/") &&
            !fileName.contains("filmlokal") &&
            !fileName.contains("logo") &&
            !fileName.contains("favicon")
    }

    fun isSameHost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "tv1.filmlokal.me" || host.endsWith(".filmlokal.me")
    }

    fun isCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        if (path.isBlank()) return true
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        if (parts.first() in setOf("year", "country", "genre", "tag", "quality", "cast", "director", "page")) return true
        if (parts.size > 1 && parts.contains("page")) return true
        return parts.size == 1 && parts.first().lowercase() in catalogSegments
    }

    fun isVideoUrl(url: String): Boolean {
        if (!isSameHost(url)) return false
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        if (path.isBlank()) return false
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size != 1) return false
        val slug = parts.first().lowercase()
        if (slug in catalogSegments) return false
        if (slug.contains("wp-") || slug.endsWith(".jpg") || slug.endsWith(".png") || slug.endsWith(".webp")) return false
        return true
    }

    fun typeFromUrlOrTitle(url: String, title: String): com.lagradost.cloudstream3.TvType {
        val low = "$url $title".lowercase()
        return when {
            low.contains("jav") || low.contains("21-mph") || low.contains("uncensored") || low.contains("censored") || low.contains("film-semi") -> com.lagradost.cloudstream3.TvType.NSFW
            low.contains("series") || low.contains("episode") || low.contains("season") -> com.lagradost.cloudstream3.TvType.TvSeries
            else -> com.lagradost.cloudstream3.TvType.Movie
        }
    }

    fun durationMinutes(value: String?): Int? {
        val text = value.orEmpty().lowercase().trim()
        Regex("""(\d+)\s*(?:min|menit)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val parts = text.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1] + if (parts[2] > 0) 1 else 0
            2 -> parts[0] + if (parts[1] > 0) 1 else 0
            else -> null
        }
    }

    fun decodeMaybe(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
    }
}
