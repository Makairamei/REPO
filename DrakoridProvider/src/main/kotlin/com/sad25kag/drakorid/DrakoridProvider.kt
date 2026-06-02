package com.sad25kag.drakorid

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DrakoridProvider : MainAPI() {
    override var mainUrl = "https://drakorid.cam"
    override var name = "Drakor.id"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val baseHeaders = mapOf(
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    )

    override val mainPage = mainPageOf(
        "" to "Latest Release",
        "series/?order=update&status=&type=" to "Series Update",
        "series/?order=popular&status=&type=" to "Popular",
        "genres/romance" to "Romance",
        "genres/comedy" to "Comedy",
        "genres/action" to "Action",
        "genres/fantasy" to "Fantasy",
        "genres/historical" to "Historical",
        "genres/thriller" to "Thriller",
        "genres/mystery" to "Mystery",
        "genres/horror" to "Horror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        LicenseClient.requireLicense(name, "HOME")
        LicenseClient.checkLicense(name, "HOME")
        val document = app.get(
            buildPageUrl(request.data, page),
            headers = baseHeaders,
            referer = mainUrl
        ).document

        val items = document.extractSearchResults()
            .distinctBy { it.url }
            .take(40)

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = page == 1 && items.isNotEmpty() && document.select("a.next, .pagination a, a[href*='/page/']").isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        LicenseClient.checkLicense(name, "SEARCH", query)
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val document = app.get(
            "$mainUrl/?s=$encoded",
            headers = baseHeaders,
            referer = mainUrl
        ).document

        return document.extractSearchResults()
            .filter { it.name.contains(clean, ignoreCase = true) || clean.split(" ").any { part -> it.name.contains(part, ignoreCase = true) } }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        LicenseClient.checkLicense(name, "LOAD", url)
        val document = app.get(
            url,
            headers = baseHeaders,
            referer = mainUrl
        ).document

        val title = document.selectFirst("h1, .entry-title, .post-title, meta[property=og:title]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Judul Drakor.id tidak ditemukan")

        val seriesUrl = document.select("a[href*='/series/']")
            .firstOrNull { it.attr("abs:href").contains("/series/", true) }
            ?.attr("abs:href")
            ?.ifBlank { null }

        val seriesSlug = seriesUrl?.substringAfter("/series/")?.substringBefore("/")?.takeIf { it.isNotBlank() }
        val seriesTitle = document.select("a[href*='/series/']")
            .firstOrNull { it.attr("abs:href") == seriesUrl }
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: title.substringBefore(" Episode ").trim()

        val poster = document.selectFirst("meta[property=og:image], .thumb img, .poster img, article img, img[src*='wp-content']")
            ?.let { it.attr("content").ifBlank { it.attr("abs:src") } }
            ?.takeIf { it.isNotBlank() }

        val tags = document.select("a[href*='/genres/'], .genxed a, .mgen a, .genre a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = Regex("\\((\\d{4})\\)").find(seriesTitle.ifBlank { title })?.groupValues?.getOrNull(1)?.toIntOrNull()
        val plot = document.selectFirst(".entry-content p, .desc p, .synopsis p, [itemprop=description]")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val episodes = document.select("a[href]")
            .mapNotNull { element ->
                val href = element.attr("abs:href").ifBlank { fixUrl(element.attr("href")) }
                if (!href.startsWith(mainUrl) || !href.contains("episode", ignoreCase = true)) return@mapNotNull null
                if (seriesSlug != null && !href.contains(seriesSlug, ignoreCase = true)) return@mapNotNull null
                val epNo = extractEpisodeNumber(href, element.text()) ?: return@mapNotNull null
                Triple(epNo, href, element.text().cleanTitle().ifBlank { "Episode $epNo" })
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .map { (epNo, epUrl, epName) ->
                newEpisode(epUrl) {
                    name = epName
                    episode = epNo
                }
            }

        val isMovie = url.contains("/movie/", true) ||
            document.text().contains("Type: Movie", true) ||
            (episodes.isEmpty() && !title.contains("Episode", true))

        if (isMovie || episodes.isEmpty()) {
            return newMovieLoadResponse(seriesTitle.ifBlank { title }, url, if (isMovie) TvType.Movie else TvType.AsianDrama, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }

        return newTvSeriesLoadResponse(seriesTitle.ifBlank { title }, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        LicenseClient.trackActivity(name, "LOAD", data)
        val pageUrl = resolveEpisodeData(data) ?: return false
        val document = app.get(
            pageUrl,
            headers = baseHeaders,
            referer = mainUrl
        ).document

        val candidates = linkedSetOf<String>()

        candidates += document.select("iframe[src], video source[src], video[src]")
            .mapNotNull { it.attr("abs:src").ifBlank { it.attr("abs:href") }.takeIf { url -> url.startsWith("http") } }

        document.select("option[value], [data-video], [data-src], [data-url], [data-embed], a[href]").forEach { element ->
            listOf("value", "data-video", "data-src", "data-url", "data-embed", "href").forEach attrs@{ attr ->
                val raw = element.attr(attr).trim()
                if (raw.isBlank() || raw == "#" || raw.equals("javascript:;", true)) return@attrs
                val decoded = decodeServerPayload(raw)
                candidates += extractUrls(decoded, pageUrl)
                if (decoded.startsWith("http")) candidates += decoded
            }
        }

        candidates += extractUrls(document.html(), pageUrl)

        var loaded = false
        candidates
            .map { it.trim().replace("\\/", "/") }
            .filter { it.startsWith("http") && it.isPlayableCandidate() }
            .distinct()
            .forEach { url ->
                when {
                    url.contains("seekplayer.vip", true) -> {
                        if (extractSeekPlayer(url, pageUrl, callback)) loaded = true
                        loadExtractor(url, pageUrl, subtitleCallback, callback)
                        loaded = true
                    }
                    url.contains(".m3u8", true) || url.contains(".mp4", true) -> {
                        emitDirect(url, pageUrl, callback)
                        loaded = true
                    }
                    else -> {
                        loadExtractor(url, pageUrl, subtitleCallback, callback)
                        loaded = true
                    }
                }
            }

        return loaded
    }

    private fun Document.extractSearchResults(): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = linkedSetOf<String>()

        select("article, div.bs, div.listupd .bs, div.item, div.card, .swiper-slide, .listupd a[href], .excstf a[href], a[href*='/series/'], a[href*='episode']")
            .forEach { element ->
                val response = element.toSearchResult() ?: return@forEach
                if (seen.add(response.url)) results.add(response)
            }

        return results
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href*='/series/'], a[href*='episode'], a[href]")
        } ?: return null

        val href = fixUrl(linkEl.attr("abs:href").ifBlank { linkEl.attr("href") })
        if (!href.startsWith(mainUrl)) return null
        if (href == mainUrl || href.contains("/genres/", true) || href.contains("/tag/", true) || href.contains("/category/", true)) return null

        val rawTitle = selectFirst("h2, h3, h4, h5, .tt, .name, .entry-title, .post-title")?.text()
            ?: linkEl.attr("title")
            ?: linkEl.text()
        val title = rawTitle.cleanTitle().takeIf { it.isNotBlank() } ?: return null
        if (title.length < 3 || title.equals("Search", true) || title.equals("Home", true)) return null

        val poster = selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
        val isMovie = href.contains("/movie/", true) || text().contains("Movie", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = poster }
        }
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val clean = path.trim().trim('/')
        val base = when {
            clean.isBlank() -> mainUrl
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> "$mainUrl/$clean"
        }
        if (page <= 1) return base
        return if (base.contains("?")) {
            base.replaceBefore("?", base.substringBefore("?").trimEnd('/') + "/page/$page")
        } else {
            "${base.trimEnd('/')}/page/$page/"
        }
    }

    private fun resolveEpisodeData(data: String): String? {
        val clean = data.trim()
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean

        return runCatching {
            val json = JSONObject(clean)
            val url = json.optString("url").takeIf { it.isNotBlank() }
            if (url != null) return@runCatching fixUrl(url)

            val slug = json.optString("slug")
            val episode = json.optInt("episode", 0)
            when {
                slug.startsWith("http", true) -> slug
                slug.isNotBlank() && episode > 0 -> "$mainUrl/${slug.trim('/')}-episode-$episode/"
                slug.isNotBlank() -> "$mainUrl/${slug.trim('/')}/"
                else -> null
            }
        }.getOrNull()
    }

    private suspend fun extractSeekPlayer(
        iframeSrc: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = iframeSrc.substringAfter("#", "").substringBefore("?").trim()
        if (id.isBlank()) return false
        val host = runCatching { URI(iframeSrc).host }.getOrNull() ?: return false
        val endpoints = listOf(
            "https://$host/api/v1/video?id=$id&w=421&h=935&r=drakorid.cam",
            "https://$host/api/v1/video?id=$id&w=1920&h=1080&r=drakorid.cam",
            "https://$host/api/v1/info?id=$id"
        )

        endpoints.forEach { apiUrl ->
            val decrypted = runCatching {
                val hexResponse = app.get(apiUrl, referer = iframeSrc, headers = baseHeaders).text.trim()
                if (!hexResponse.matches(Regex("^[0-9a-fA-F]+$"))) return@runCatching null
                decryptSeekPlayerHex(hexResponse)
            }.getOrNull() ?: return@forEach

            val normalized = decrypted.replace("\\/", "/")
            val mediaUrl = runCatching {
                val json = JSONObject(normalized)
                listOf(
                    json.optString("source"),
                    json.optString("cf")
                ).firstOrNull { it.contains(".m3u8", true) || it.contains(".mp4", true) }
            }.getOrNull()
                ?: Regex("""https?://[^\"'\s<>]+?(?:\.m3u8|\.mp4)[^\"'\s<>]*""")
                    .find(normalized)
                    ?.value

            if (!mediaUrl.isNullOrBlank()) {
                emitDirect(mediaUrl, iframeSrc, callback)
                LicenseClient.trackActivity(name, "PLAY")
        return true
            }
        }

        return false
    }

    private fun decryptSeekPlayerHex(hex: String): String {
        val secretKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        val ivBytes = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKey, "AES"), IvParameterSpec(ivBytes))
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private suspend fun emitDirect(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = mediaUrl.replace("\\/", "/")
        val type = if (fixedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val quality = getQualityFromName(fixedUrl).let {
            if (it == Qualities.Unknown.value) inferQuality(fixedUrl) else it
        }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name ${qualityLabel(quality)}",
                url = fixedUrl,
                type = type
            ) {
                this.quality = quality
                this.referer = referer
                this.headers = mapOf(
                    "Referer" to referer,
                    "Origin" to mainUrl,
                    "User-Agent" to (baseHeaders["User-Agent"] ?: "Mozilla/5.0")
                )
            }
        )
    }

    private fun decodeServerPayload(raw: String): String {
        val decodedUrl = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidates = listOf(raw, decodedUrl, decodedUrl.substringAfter("base64,", decodedUrl))
        candidates.forEach { candidate ->
            val clean = candidate.trim().trim('\'', '"')
            if (clean.length < 8 || !clean.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return@forEach
            listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).forEach { flag ->
                val decoded = runCatching { String(Base64.decode(clean, flag), Charsets.UTF_8) }.getOrNull()
                if (!decoded.isNullOrBlank() && (decoded.contains("http") || decoded.contains("<iframe", true))) {
                    return decoded
                }
            }
        }
        return decodedUrl
    }

    private fun String.isPlayableCandidate(): Boolean {
        return contains("seekplayer.vip", true) ||
            contains("abyssplayer.com", true) ||
            contains("vidmoly", true) ||
            contains("dailymotion.com", true) ||
            contains("streamtape", true) ||
            contains("filemoon", true) ||
            contains("mp4upload", true) ||
            contains(".m3u8", true) ||
            contains(".mp4", true)
    }

    private fun extractUrls(text: String, pageUrl: String): Set<String> {
        val normalized = text.replace("\\/", "/")
        val urls = linkedSetOf<String>()
        Regex("""https?://[^\"'\\s<>]+""")
            .findAll(normalized)
            .forEach { urls.add(it.value.trim().trim('"', '\'', ',', ';', ')', ']')) }

        Regex("""(?:src|href)=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { fixUrl(it, pageUrl) }
            .filter { it.startsWith("http") }
            .forEach { urls.add(it) }

        return urls
    }

    private fun fixUrl(url: String, referer: String = mainUrl): String {
        val clean = url.trim()
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> "$mainUrl$clean"
            clean.isBlank() -> clean
            else -> referer.substringBeforeLast("/") + "/" + clean
        }
    }

    private fun extractEpisodeNumber(href: String, text: String): Int? {
        return Regex("episode[-\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?:Eps|Episode|Ep)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun inferQuality(url: String): Int {
        return when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(quality: Int): String {
        return if (quality == Qualities.Unknown.value) "Auto" else "${quality}p"
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?i)\s+[-–]\s+drakor\.id.*$"""), "")
            .replace(Regex("""(?i)^Nonton\s+"""), "")
            .trim()
    }
}
