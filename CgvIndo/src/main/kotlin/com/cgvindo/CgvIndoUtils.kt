package com.cgvindo

import com.lagradost.cloudstream3.TvType
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object CgvIndoUtils {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"

    val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "${CgvIndoSeeds.MAIN_URL}/"
    )

    fun videoHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Referer" to referer
    )

    fun ajaxHeaders(pageUrl: String): Map<String, String> {
        val origin = originOf(pageUrl) ?: CgvIndoSeeds.MAIN_URL
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to origin,
            "Referer" to pageUrl
        )
    }

    private val catalogSegments = setOf(
        "", "page", "genre", "genres", "tag", "category", "author", "cast", "director",
        "country", "year", "quality", "studio", "network", "tv", "series", "movies", "movie",
        "dmca", "privacy-policy", "contact", "about", "jadwal", "ongoing", "completed"
    )

    fun cleanText(value: String?): String = value.orEmpty()
        .replace("\u00a0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun cleanTitle(value: String?): String {
        return cleanText(value)
            .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^trailer\\s*[:|-]?\\s*"), "")
            .replace(Regex("(?i)\\s*[-|]\\s*(CGVIndo|Nonton|Streaming).*$"), "")
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s+sub\\s+indo$"), " Sub")
            .trim(' ', '-', '|')
    }

    fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    fun decodeMaybe(value: String): String {
        var out = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
        out = Regex("\\\\x([0-9A-Fa-f]{2})").replace(out) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        out = Regex("\\\\u([0-9A-Fa-f]{4})").replace(out) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        if (out.contains("%3A%2F%2F", ignoreCase = true) || out.contains("%2F", ignoreCase = true)) {
            out = runCatching { URLDecoder.decode(out, "UTF-8") }.getOrDefault(out)
        }
        return out
    }

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
        val raw = decodeMaybe(value.orEmpty())
            .trim()
            .removePrefix("url(")
            .removeSuffix(")")
            .trim('"', '\'', ' ', ',', ';')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "about:blank" || low == "null" || low == "undefined") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val origin = originOf(baseUrl) ?: CgvIndoSeeds.MAIN_URL
        if (raw.startsWith("/")) return origin.trimEnd('/') + raw
        val base = baseUrl.substringBeforeLast('/', "${origin}/")
        return base.trimEnd('/') + "/" + raw
    }

    fun pageUrls(mainUrl: String, data: String, page: Int): List<String> {
        val paths = if (data.startsWith("paths:")) data.removePrefix("paths:").split(',') else listOf(data)
        return paths.map { pageUrl(mainUrl, it.trim(), page) }.distinct()
    }

    fun pageUrl(mainUrl: String, data: String, page: Int): String {
        val raw = if (data.startsWith("http")) data else mainUrl.trimEnd('/') + "/" + data.trimStart('/')
        if (page <= 1) return raw.trimEnd('/') + "/"
        return raw.trimEnd('/') + "/page/$page/"
    }

    fun searchUrl(mainUrl: String, query: String): String = "${mainUrl.trimEnd('/')}/?s=${query.urlEncoded()}"

    fun isSameHost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "cgvindo2.baby" || host.endsWith(".cgvindo2.baby") || host.contains("cgvindo")
    }

    fun isCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty().trim('/').lowercase() }.getOrDefault(url.lowercase())
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        if (parts.any { it in catalogSegments }) return true
        if (path.contains("/page/")) return true
        return false
    }

    fun isTitleUrl(url: String): Boolean {
        val low = url.lowercase()
        if (!isSameHost(low)) return false
        if (isCatalogUrl(low)) return false
        if (low.contains("/wp-content/") || low.contains("/wp-json/") || low.contains("/feed")) return false
        val last = runCatching { URI(url).path.orEmpty().trim('/').split('/').lastOrNull().orEmpty() }.getOrDefault("")
        return last.length > 2 && last.contains('-')
    }

    fun typeFromUrlOrTitle(url: String, title: String? = null): TvType {
        val text = "${url.lowercase()} ${title.orEmpty().lowercase()}"
        return when {
            listOf("series", "episode", "eps", "season", "drama korea", "drama jepang", "thailand series", "series indonesia", "series malaysia").any { it in text } -> TvType.TvSeries
            listOf("drama", "korea", "jepang", "thailand", "malaysia", "china", "japan").any { it in text } -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    fun pickImage(base: String, image: Element?, container: Element? = null): String? {
        val candidates = linkedSetOf<String>()
        fun add(value: String?) { if (!value.isNullOrBlank()) candidates.add(value) }
        if (image != null) {
            listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "data-srcset", "srcset", "src").forEach { add(image.attr(it)) }
        }
        container?.select("img, source")?.forEach { img ->
            listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "data-srcset", "srcset", "src").forEach { add(img.attr(it)) }
        }
        container?.attr("style")?.let { style ->
            Regex("(?i)url\\(([^)]+)\\)").findAll(style).forEach { add(it.groupValues[1]) }
        }
        return candidates.asSequence()
            .flatMap { srcsetValues(it).asSequence() }
            .mapNotNull { absoluteUrl(base, it) }
            .map { upscalePosterUrl(it) }
            .firstOrNull { isValidPoster(it) }
    }

    private fun srcsetValues(value: String): List<String> {
        val raw = value.trim()
        if (raw.isBlank()) return emptyList()
        if (!raw.contains(',')) return listOf(raw.substringBefore(' ').trim())
        return raw.split(',').map { it.trim().substringBefore(' ').trim() }.filter { it.isNotBlank() }
    }

    fun extractDetailPoster(base: String, doc: Document, title: String): String? {
        val titleTokens = cleanTitle(title).lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()
        val candidates = linkedSetOf<String>()
        listOf(
            "meta[property=og:image]", "meta[name=twitter:image]", "meta[property=twitter:image]"
        ).forEach { sel -> doc.selectFirst(sel)?.attr("content")?.let { candidates.add(it) } }
        doc.select("article img, .entry-content img, .poster img, .thumb img, .cover img, .single img, img").forEach { img ->
            listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "srcset", "src").forEach { attr ->
                img.attr(attr).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            }
        }
        return candidates.asSequence()
            .flatMap { srcsetValues(it).asSequence() }
            .mapNotNull { absoluteUrl(base, it) }
            .map { upscalePosterUrl(it) }
            .filter { isValidPoster(it) }
            .sortedByDescending { posterScore(it, titleTokens) }
            .firstOrNull()
    }

    fun extractMetaImage(base: String, doc: Document): String? = extractDetailPoster(base, doc, doc.title())

    private fun posterScore(url: String, tokens: Set<String>): Int {
        val low = url.lowercase()
        var score = 0
        tokens.forEach { if (it in low) score += 6 }
        if ("wp-content/uploads" in low) score += 5
        if ("poster" in low || "cover" in low || "thumb" in low) score += 3
        if ("logo" in low || "favicon" in low || "placeholder" in low) score -= 20
        return score
    }

    fun upscalePosterUrl(url: String): String {
        return url.replace(Regex("-(\\d{2,4})x(\\d{2,4})(?=\\.(jpg|jpeg|png|webp))", RegexOption.IGNORE_CASE), "")
    }

    fun isValidPoster(url: String?): Boolean {
        val raw = url.orEmpty().trim()
        val low = raw.lowercase()
        val path = runCatching { URI(raw).path.orEmpty().lowercase() }.getOrDefault(low)
        val fileName = path.substringAfterLast('/')
        return low.startsWith("http") &&
            !low.startsWith("data:") &&
            !low.contains("logo") &&
            !low.contains("favicon") &&
            !low.contains("placeholder") &&
            !low.contains("no-image") &&
            !low.contains("/themes/") &&
            !low.contains("/plugins/") &&
            fileName.contains(Regex("\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE))
    }
}
