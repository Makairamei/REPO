package com.putarflix

import com.lagradost.cloudstream3.TvType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

internal object PutarFlixUtils {
    private val badContentPaths = listOf(
        "/category/", "/tag/", "/genre/", "/country/", "/quality/", "/year/",
        "/author/", "/page/", "/wp-content/", "/sample-page/", "#respond"
    )

    private val badExternalHosts = listOf(
        "themoviedb.org", "facebook.com", "twitter.com", "instagram.com", "whatsapp.com"
    )

    fun cleanText(value: String?): String {
        return Jsoup.parse(value.orEmpty()).text()
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':', '–')
            .trim()
    }

    fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)\\s*[-|]\\s*PUTARFLIX.*$"), "")
            .replace(Regex("(?i)^Nonton\\s+(?:Film|Movie|Series)\\s+(.+)$"), "\$1")
            .replace(Regex("(?i)\\s+Sub\\s+Indo.*$"), "")
            .trim()
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun absoluteUrl(base: String, value: String?): String? {
        val raw = value?.trim().orEmpty()
            .replace("&amp;", "&")
            .replace("\\/", "/")
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", true)) return null
        return runCatching {
            val normalized = if (raw.startsWith("//")) "https:$raw" else raw
            URI(base).resolve(normalized).toString()
        }.getOrNull()
    }

    fun pageUrl(path: String, page: Int): String {
        val fixed = absoluteUrl(PutarFlixSeeds.MAIN_URL, path) ?: PutarFlixSeeds.MAIN_URL
        if (page <= 1) return fixed
        return fixed.trimEnd('/') + "/page/$page/"
    }

    fun hostOf(url: String): String? {
        return runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()
    }

    fun isPutarFlixUrl(url: String): Boolean {
        return hostOf(url) == hostOf(PutarFlixSeeds.MAIN_URL)
    }

    fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith(PutarFlixSeeds.MAIN_URL)) return false
        if (badContentPaths.any { it in lower }) return false
        return lower.contains("/eps/") || lower.contains("/tv/") || Regex("https?://[^/]+/[^/?#]+/?$").containsMatchIn(lower)
    }

    fun isInternalNavigation(url: String): Boolean {
        if (!isPutarFlixUrl(url)) return false
        if (looksDirectVideo(url)) return false
        val lower = url.lowercase()
        if (lower.contains("?player=")) return true
        return isContentUrl(url) || badContentPaths.any { it in lower } || lower.trimEnd('/') == PutarFlixSeeds.MAIN_URL
    }

    fun isRejectedVideoCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.isBlank()) return true
        if (badExternalHosts.any { it in lower }) return true
        if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/")) return true
        if (lower.contains("/trailer") || lower.contains("/embed/trailer")) return true
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp")) return true
        if (lower.contains("wp-content") && !looksDirectVideo(lower)) return true
        return false
    }

    fun typeFrom(url: String, title: String? = null, hint: String? = null): TvType {
        val value = listOf(url, title.orEmpty(), hint.orEmpty()).joinToString(" ").lowercase()
        return when {
            "/eps/" in value -> TvType.TvSeries
            "/tv/" in value -> TvType.TvSeries
            "episode" in value || Regex("\\bs\\d+\\s*e\\d+").containsMatchIn(value) -> TvType.TvSeries
            "season" in value || "tv show" in value || "series" in value -> TvType.TvSeries
            "korea" in value || "dramaqu" in value || "drakorkita" in value -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    fun pickImage(base: String, image: Element?, container: Element? = null): String? {
        val candidates = buildList {
            if (image != null) {
                add(image.attr("data-src"))
                add(image.attr("data-lazy-src"))
                add(image.attr("data-original"))
                add(image.attr("src"))
                add(image.attr("srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ").orEmpty())
            }
            container?.select("img")?.forEach {
                add(it.attr("data-src"))
                add(it.attr("data-lazy-src"))
                add(it.attr("data-original"))
                add(it.attr("src"))
                add(it.attr("srcset").split(",").lastOrNull()?.trim()?.substringBefore(" ").orEmpty())
            }
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(base, it) }
            .firstOrNull { it.startsWith("http") }
    }

    fun extractMetaImage(base: String, doc: Document): String? {
        val raw = listOfNotNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            doc.selectFirst(".poster img, .cover img, article img, img")?.attr("src")
        ).firstOrNull { it.isNotBlank() }
        return absoluteUrl(base, raw)
    }

    fun extractYear(text: String?): Int? {
        val value = text.orEmpty()
        return Regex("\\((19|20)\\d{2}\\)|\\b((19|20)\\d{2})\\b")
            .find(value)
            ?.value
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()
    }

    fun extractDuration(text: String?): Int? {
        return Regex("(?i)(\\d{2,3})\\s*(min|minute|minutes)")
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    fun extractRating(text: String?): String? {
        return Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:votes|/10|out of 10)?")
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
    }

    fun episodeNumber(text: String?): Int? {
        val clean = cleanText(text).lowercase()
        return listOf(
            Regex("episode\\s*(\\d+)"),
            Regex("eps?\\s*(\\d+)"),
            Regex("e(\\d+)"),
            Regex("\\b(\\d+)\\b")
        ).firstNotNullOfOrNull { it.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun seasonNumber(text: String?): Int? {
        val clean = cleanText(text).lowercase()
        return listOf(
            Regex("season\\s*(\\d+)"),
            Regex("s(\\d+)")
        ).firstNotNullOfOrNull { it.find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun extractLabelNear(element: Element): String {
        return cleanText(
            element.attr("title").ifBlank { element.attr("aria-label") }
                .ifBlank { element.text() }
                .ifBlank { element.parent()?.text().orEmpty() }
        ).ifBlank { "PutarFlix" }
    }

    fun looksDirectVideo(url: String): Boolean {
        val lower = url.lowercase().substringBefore("?")
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mpd")
    }

    fun decodeKnownRedirect(url: String): String {
        val lower = url.lowercase()
        if (!lower.contains("semawur.com") && !lower.contains("linkduit.net") && !lower.contains("safelinku")) return url
        val rawQuery = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val encoded = rawQuery.split("&")
            .firstOrNull { it.substringBefore("=") in listOf("url", "u", "go", "target") }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
            ?: return url
        val decodedParam = decodeUrlRepeated(encoded)
        val padded = decodedParam + "=".repeat((4 - decodedParam.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(padded)) }
            .getOrDefault(decodedParam.takeIf { it.startsWith("http", true) } ?: url)
    }

    fun decodeUrlRepeated(value: String): String {
        var current = value
        repeat(2) {
            val decoded = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
            if (decoded == current) return current
            current = decoded
        }
        return current
    }

    fun extractUrlsFromText(base: String, value: String): List<String> {
        val normalized = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
        val pools = listOf(normalized, decodeUrlRepeated(normalized))
        val candidates = linkedSetOf<String>()
        val urlRegex = Regex("""https?:\\?/\\?/[^\"'<>\s]+""", RegexOption.IGNORE_CASE)
        val protocolLessRegex = Regex("""(?<!:)//[^\"'<>\s]+""", RegexOption.IGNORE_CASE)
        pools.forEach { pool ->
            urlRegex.findAll(pool).forEach { candidates += it.value }
            protocolLessRegex.findAll(pool).forEach { candidates += it.value }
        }
        return candidates.mapNotNull { raw ->
            val cleaned = decodeUrlRepeated(raw)
                .replace("\\/", "/")
                .trim('"', '\'', ' ', '\n', '\r', '\t', ')', ']', '}')
            absoluteUrl(base, cleaned)
        }.distinct()
    }
}
