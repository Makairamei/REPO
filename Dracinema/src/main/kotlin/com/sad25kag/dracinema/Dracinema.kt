package com.sad25kag.dracinema

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class Dracinema : MainAPI() {
    override var mainUrl = "https://www.dracinema.com"
    override var name = "Dracinema"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "collections" to "Koleksi",
        "genre/romantis" to "Romantis",
        "genre/balas-dendam" to "Balas Dendam",
        "genre/identitas-tersembunyi" to "Identitas Tersembunyi",
        "genre/dari-miskin-ke-kaya" to "Dari Miskin ke Kaya",
        "genre/pengkhianatan" to "Pengkhianatan",
        "genre/serangan-balik" to "Serangan Balik",
        "genre/terlahir-kembali" to "Terlahir Kembali",
        "genre/miliarder" to "Miliarder",
        "genre/romansa" to "Romansa",
        "genre/kontemporer" to "Kontemporer",
        "genre/modern" to "Modern",
        "genre/pahlawan-wanita-kuat" to "Pahlawan Wanita Kuat",
        "genre/keluarga" to "Keluarga",
        "genre/ceo--bos" to "CEO / Bos",
        "genre/fantasi" to "Fantasi",
        "genre/komedi" to "Komedi",
        "genre/perjalanan-waktu" to "Perjalanan Waktu",
        "genre/aksi" to "Aksi",
        "genre/misteri" to "Misteri",
        "genre/pernikahan-kontrak" to "Pernikahan Kontrak"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = commonHeaders, referer = mainUrl).document

        if (request.data.isBlank() && page <= 1) {
            val rows = parseHomeRows(document)
            if (rows.isNotEmpty()) return newHomePageResponse(rows)
        }

        val list = parseCards(document).distinctBy { it.url }
        return newHomePageResponse(
            request.name,
            list,
            hasNext = document.selectFirst(
                "a[rel=next], a:contains(Muat Lebih Banyak), button:contains(Muat Lebih Banyak), " +
                    "a[href*='page=${page + 1}'], a[href*='/page/${page + 1}']"
            ) != null || list.isNotEmpty()
        )
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim('/')
        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl?page=$page"
            page <= 1 -> "$mainUrl/$cleanPath"
            cleanPath == "collections" -> "$mainUrl/collections?page=$page"
            cleanPath.startsWith("genre/") -> "$mainUrl/$cleanPath?page=$page"
            else -> "$mainUrl/$cleanPath?page=$page"
        }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()
        document.select("section, main > div, div[class*=section]").forEach { section ->
            val title = section.selectFirst("h1, h2, h3")
                ?.text()?.trim()?.cleanTitle()?.takeIf { it.isNotBlank() }
                ?: return@forEach

            val items = section.select("a[href*='/movie/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (items.isNotEmpty()) rows.add(HomePageList(title, items))
        }

        if (rows.isEmpty()) {
            parseCards(document).takeIf { it.isNotEmpty() }?.let {
                rows.add(HomePageList("Beranda", it))
            }
        }
        return rows.distinctBy { it.name }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(
            "a[href*='/movie/'], article a[href*='/movie/'], .card a[href*='/movie/'], " +
                ".grid a[href*='/movie/'], .swiper-slide a[href*='/movie/'], [class*=movie] a[href*='/movie/']"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || !href.contains("/movie/", true)) return null

        val rawTitle = listOf(
            selectFirst("h1")?.text()?.trim(),
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst("[class*=title]")?.text()?.trim(),
            anchor.attr("title").trim(),
            anchor.attr("aria-label").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Putar Sekarang", true) &&
                !it.equals("Tonton Sekarang", true) &&
                !it.equals("Detail Info", true) &&
                !it.equals("Muat Lebih Banyak", true)
        } ?: return null

        val title = rawTitle.cleanTitle().takeIf { it.length >= 2 } ?: return null
        val poster = fixUrlNull(selectFirst("img")?.getImageAttr())

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), hasNext = false)

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val pageNumber = page.coerceAtLeast(1)
        val urls = listOf(
            "$mainUrl/search?q=$encoded&page=$pageNumber",
            "$mainUrl/search?keyword=$encoded&page=$pageNumber",
            "$mainUrl/search/$encoded?page=$pageNumber",
            "$mainUrl/collections?search=$encoded&page=$pageNumber",
            "$mainUrl/collections?q=$encoded&page=$pageNumber",
            "$mainUrl/collections?page=$pageNumber",
            "$mainUrl?page=$pageNumber"
        )

        val exactResults = linkedMapOf<String, SearchResponse>()
        val fallbackResults = linkedMapOf<String, SearchResponse>()
        val tokens = keyword.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }

        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = commonHeaders, referer = mainUrl).document
            }.getOrNull() ?: return@forEach

            parseCards(document).forEach { item ->
                fallbackResults[item.url] = item
                val haystack = item.name.lowercase()
                if (haystack.contains(keyword.lowercase()) || tokens.any { haystack.contains(it) }) {
                    exactResults[item.url] = item
                }
            }

            if (exactResults.isNotEmpty()) return@forEach
        }

        val output = exactResults.values.ifEmpty { fallbackResults.values.take(12) }
        return newSearchResponseList(output.toList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = commonHeaders, referer = mainUrl).document
        val title = document.selectFirst("h1, h1[class*=title], meta[property=og:title], meta[name=title]")
            ?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.cleanTitle()?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").substringBefore("?").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image], meta[name=twitter:image], img[class*=poster], img[class*=cover], picture img, img")
                ?.let { if (it.hasAttr("content")) it.attr("content") else it.getImageAttr() }
        )

        val description = document.selectFirst(
            "meta[property=og:description], meta[name=description], .description, [class*=description], .synopsis, [class*=synopsis], p"
        )?.let { if (it.hasAttr("content")) it.attr("content") else it.text() }
            ?.trim()?.takeIf { it.isNotBlank() }

        val tags = document.select("a[href*='/genre/'], [class*=genre] a, [class*=tag] a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document, url)
        val recommendations = parseCards(document).filter { it.url != url }.distinctBy { it.url }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun parseEpisodes(document: Document, fallbackUrl: String): List<Episode> {
        val episodes = linkedMapOf<Int, Episode>()
        val candidates = document.select(
            "a[href*='episode'], a[href*='eps'], a[href*='play'], a[href*='watch'], " +
                "button[data-url], button[data-src], button[data-href], button[data-episode], " +
                "[class*=episode] a[href], [class*=eps] a[href], [class*=episode][data-url], " +
                "[data-episode], [data-episode-id], [data-ep], [data-id]"
        )

        candidates.forEachIndexed { index, element ->
            val text = element.text().trim()
            val epNum = extractEpisodeNumber(text, element.outerHtml()) ?: (index + 1)
            val raw = listOf(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-src"),
                element.attr("data-href"),
                element.attr("data-video"),
                element.attr("data-file")
            ).firstOrNull { it.isNotBlank() }.orEmpty()

            val fixed = when {
                raw.isBlank() || raw == "#" || raw.startsWith("javascript", true) -> "$fallbackUrl?episode=$epNum"
                else -> raw.absoluteUrl(fallbackUrl) ?: "$fallbackUrl?episode=$epNum"
            }

            val nameText = text.cleanEpisodeName(epNum)
            episodes[epNum] = newEpisode(fixed) {
                name = nameText
                episode = epNum
            }
        }

        if (episodes.isEmpty()) {
            val count = extractEpisodeCount(document.text()) ?: 1
            for (ep in 1..count) {
                episodes[ep] = newEpisode("$fallbackUrl?episode=$ep") {
                    name = "Episode $ep"
                    episode = ep
                }
            }
        }

        return episodes.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetEpisode = data.substringAfter("?episode=", "").substringBefore("&").toIntOrNull()
        val playUrl = data.toPlayUrl(targetEpisode)
        val emitted = linkedSetOf<String>()
        var found = false

        val pageTexts = mutableListOf<String>()
        runCatching {
            app.get(playUrl, headers = commonHeaders, referer = mainUrl).text
        }.getOrNull()?.let(pageTexts::add)

        if (pageTexts.none { it.contains("videoUrls", true) || it.contains("awscdn.netshort.com", true) }) {
            runCatching {
                app.get(
                    "$playUrl?_rsc=1",
                    headers = commonHeaders + mapOf("RSC" to "1", "Accept" to "*/*"),
                    referer = playUrl
                ).text
            }.getOrNull()?.let(pageTexts::add)
        }

        pageTexts.flatMap { extractDracinemaVideoSources(it) }
            .distinctBy { it.url.substringBefore("&Expires=").substringBefore("?Expires=") }
            .forEach { source ->
                val key = source.url.substringBefore("&Expires=").substringBefore("?Expires=")
                if (!emitted.add(key)) return@forEach

                if (source.url.contains(".m3u8", true)) {
                    generateM3u8(name, source.url, playUrl).forEach {
                        callback(it)
                        found = true
                    }
                } else {
                    callback(
                        newExtractorLink(name, "Dracinema ${source.quality ?: Qualities.Unknown.value}p", source.url, ExtractorLinkType.VIDEO) {
                            referer = mainUrl
                            quality = source.quality ?: Qualities.P720.value
                            headers = mapOf(
                                "Accept" to "*/*",
                                "Range" to "bytes=0-"
                            )
                        }
                    )
                    found = true
                }
            }

        if (found) return true

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        fun addCandidate(raw: String?, base: String = playUrl) {
            val fixed = raw?.cleanEscaped()?.absoluteUrl(base) ?: return
            if (fixed.isNoiseUrl()) return
            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) || fixed.contains(".webm", true) || fixed.contains("awscdn.netshort.com", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        pageTexts.forEach { text -> extractMediaUrls(text).forEach { addCandidate(it) } }

        val document = runCatching {
            Jsoup.parse(pageTexts.firstOrNull().orEmpty(), playUrl)
        }.getOrNull()

        document?.select(
            "video source[src], source[src], video[src], iframe[src], embed[src], " +
                "a[href*='.m3u8'], a[href*='.mp4'], a[href*='embed'], a[href*='player'], " +
                "[data-url], [data-src], [data-video], [data-file], [data-href]"
        )?.distinct()?.forEach { element ->
            listOf("src", "href", "data-url", "data-src", "data-video", "data-file", "data-href")
                .map { element.attr(it) }
                .firstOrNull { it.isNotBlank() }
                ?.let { addCandidate(it) }
        }

        directLinks.forEach { link ->
            val key = link.substringBefore("?token=").substringBefore("&token=")
            if (!emitted.add(key)) return@forEach
            if (link.contains(".m3u8", true)) {
                generateM3u8(name, link, playUrl).forEach {
                    callback(it)
                    found = true
                }
            } else {
                callback(
                    newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                        headers = mapOf("Accept" to "*/*", "Range" to "bytes=0-")
                    }
                )
                found = true
            }
        }

        embedLinks.forEach { embed ->
            val key = embed.substringBefore("?")
            if (!emitted.add(key)) return@forEach
            runCatching {
                if (loadExtractor(embed, playUrl, subtitleCallback, callback)) found = true
            }
        }

        return found
    }

    private data class DracinemaVideoSource(val url: String, val quality: Int?)

    private fun String.toPlayUrl(targetEpisode: Int?): String {
        val clean = substringBefore("?episode=").trimEnd('/')
        if (clean.contains("/play/", true)) return clean
        val movieKey = clean.substringAfter("/movie/", "").substringBefore("?").trim('/').takeIf { it.isNotBlank() }
            ?: return clean
        val base = "$mainUrl/play/$movieKey"
        return if (targetEpisode != null && targetEpisode > 1) "$base/$targetEpisode" else base
    }

    private fun extractDracinemaVideoSources(text: String): List<DracinemaVideoSource> {
        val clean = text.cleanEscaped()
        val sources = linkedMapOf<String, DracinemaVideoSource>()

        Regex("""["\\]?quality["\\]?\s*:\s*(\d+)[\s\S]{0,80}?["\\]?url["\\]?\s*:\s*["\\](https?://[^"\\]+)""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val quality = match.groupValues.getOrNull(1)?.toIntOrNull()
                val url = match.groupValues.getOrNull(2)?.cleanEscaped()?.takeIf { it.isDracinemaVideoUrl() } ?: return@forEach
                sources[url] = DracinemaVideoSource(url, quality)
            }

        Regex("""https?://awscdn\.netshort\.com/[^"'\\\s<>\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped() }
            .filter { it.isDracinemaVideoUrl() }
            .forEach { url -> sources.putIfAbsent(url, DracinemaVideoSource(url, Qualities.P720.value)) }

        Regex("""https?://[^"'\\\s<>\]}]+?(?:\.mp4|\.m3u8|\.webm)(?:\?[^"'\\\s<>\]}]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { it.isNoiseUrl() }
            .forEach { url -> sources.putIfAbsent(url, DracinemaVideoSource(url, getQualityFromName(url).takeIf { q -> q != Qualities.Unknown.value })) }

        return sources.values.toList()
    }


    private fun extractMediaUrls(text: String): List<String> {
        val clean = text.cleanEscaped()
        val urls = linkedSetOf<String>()

        Regex("""https?://[^\"'\s<>)\]}]+""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.value.cleanEscaped().trimEnd('.', ',', ';') }
            .filterNot { it.isNoiseUrl() }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun String.isDracinemaVideoUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("awscdn.netshort.com") || lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm")
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(text: String, fallback: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)[-/]?(\d+)""", RegexOption.IGNORE_CASE).find(fallback)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""data-(?:episode|ep|id)=["']?(\d+)""", RegexOption.IGNORE_CASE).find(fallback)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractEpisodeCount(text: String): Int? {
        return Regex("""(\d+)\s*Episode""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.cleanEpisodeName(epNum: Int): String {
        val cleaned = replace(Regex("""^\s*\d+\s*[.:-]?\s*"""), "")
            .replace(Regex("""^\s*(episode|eps?|ep)\s*\d+\s*[.:-]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return when {
            cleaned.isBlank() -> "Episode $epNum"
            cleaned.equals(epNum.toString(), true) -> "Episode $epNum"
            cleaned.equals("Tonton Sekarang", true) -> "Episode $epNum"
            cleaned.length <= 2 && cleaned.all { it.isDigit() } -> "Episode $epNum"
            else -> "Episode $epNum - $cleaned"
        }
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("\\u003f", "?")
            .replace("\\u003F", "?")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .trim()
    }

    private fun String.absoluteUrl(baseUrl: String): String? {
        val clean = trim()
        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return null
        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull() ?: fixUrlNull(clean)
        }
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("google") || lower.contains("facebook") || lower.contains("doubleclick") || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".css") || lower.endsWith(".js")
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+-\s+Dracinema.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Full Episode Subtitle Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
