package com.pasarbokep

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

object PasarBokepUtils {
    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    fun cleanText(input: String?): String {
        return input
            ?.replace("\u00a0", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    fun encodeQuery(query: String): String {
        return URLEncoder.encode(query.trim(), "UTF-8")
    }

    fun absoluteUrl(rawUrl: String?, mainUrl: String): String? {
        val value = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("data:", ignoreCase = true) || value.startsWith("javascript:", ignoreCase = true)) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            else -> mainUrl.trimEnd('/') + "/" + value
        }
    }

    fun updateHost(url: String, mainUrl: String): String {
        return try {
            val original = URI(url)
            val target = URI(mainUrl)
            URI(target.scheme, original.userInfo, target.host, target.port, original.path, original.query, original.fragment).toString()
        } catch (_: Throwable) {
            url
        }
    }

    fun pagedUrl(baseUrl: String, page: Int, mainUrl: String): String {
        val fixedBase = absoluteUrl(baseUrl, mainUrl)?.trimEnd('/') ?: mainUrl
        if (page <= 1) return fixedBase
        if (fixedBase.contains("?")) {
            val separator = if (fixedBase.endsWith("?") || fixedBase.endsWith("&")) "" else "&"
            return "$fixedBase${separator}paged=$page"
        }
        return "$fixedBase/page/$page/"
    }

    fun Element.bestImage(mainUrl: String): String? {
        val img = selectFirst("img") ?: return null
        val raw = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-img"),
            img.attr("src"),
            img.attr("data-srcset").substringBefore(" "),
            img.attr("srcset").substringBefore(" "),
        ).firstOrNull { it.isNotBlank() }
        return absoluteUrl(raw, mainUrl)
    }

    fun Document.bestPoster(mainUrl: String): String? {
        val fromMeta = listOf(
            selectFirst("meta[property=og:image]")?.attr("content"),
            selectFirst("meta[name=twitter:image]")?.attr("content"),
            selectFirst("link[rel=image_src]")?.attr("href"),
        ).firstOrNull { !it.isNullOrBlank() }
        if (!fromMeta.isNullOrBlank()) return absoluteUrl(fromMeta, mainUrl)

        return selectFirst("article img, .entry-content img, .post-content img, .single img, main img, img")
            ?.let { absoluteUrl(it.attr("data-src").ifBlank { it.attr("src") }, mainUrl) }
    }

    fun Document.hasNextPage(): Boolean {
        return select(
            "a.next, a[rel=next], .page-numbers.next, .pagination a:matchesOwn((?i)next|selanjutnya|berikut|›|»), nav a:matchesOwn((?i)next|selanjutnya|berikut|›|»)"
        ).any { it.attr("href").isNotBlank() || it.text().isNotBlank() }
    }

    fun isLikelyVideoPage(url: String, title: String, mainUrl: String): Boolean {
        val cleanTitle = cleanText(title).lowercase()
        if (cleanTitle.length < 3) return false
        if (PasarBokepSeeds.blockedTitleHints.any { cleanTitle == it || cleanTitle.contains(it) }) return false

        val fixed = absoluteUrl(url, mainUrl) ?: return false
        val lower = fixed.lowercase()
        if (!lower.startsWith(mainUrl.lowercase())) return false
        if (PasarBokepSeeds.blockedPathHints.any { hint -> lower.contains(hint) }) return false
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) return false
        return lower.trimEnd('/') != mainUrl.trimEnd('/').lowercase()
    }

    fun titleFromUrl(url: String): String {
        return url
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
            .ifBlank { "PasarBokep" }
    }

    fun directVideoQuality(url: String): Int {
        val lower = url.lowercase()
        return Regex("(2160|1440|1080|720|480|360|240|144)p?").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: when {
                "4k" in lower -> Qualities.P2160.value
                "fhd" in lower || "fullhd" in lower -> Qualities.P1080.value
                "hd" in lower -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
    }

    fun isDirectVideo(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".mov")
    }

    fun isPotentialExtractor(url: String, mainUrl: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith(mainUrl.lowercase())) return false
        if (lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("#")) return false
        if (lower.contains("google.com") || lower.contains("gstatic.com") || lower.contains("facebook.com") || lower.contains("twitter.com")) return false
        if (lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp")) return false
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")
    }
}


fun Element.bestImage(mainUrl: String): String? = with(PasarBokepUtils) {
    this@bestImage.bestImage(mainUrl)
}

fun Document.bestPoster(mainUrl: String): String? = with(PasarBokepUtils) {
    this@bestPoster.bestPoster(mainUrl)
}

fun Document.hasNextPage(): Boolean = with(PasarBokepUtils) {
    this@hasNextPage.hasNextPage()
}
