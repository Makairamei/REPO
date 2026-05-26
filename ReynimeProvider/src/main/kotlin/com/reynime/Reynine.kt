package com.reynime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class ReynimeProvider : MainAPI() {
    override var mainUrl = "https://reynime.my.id"
    override var name = "Reynime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "featured" to "Featured",
        "updated" to "Update Terbaru",
        "all" to "Daftar Donghua",
        "ongoing" to "Ongoing Series",
        "completed" to "Completed Series",
        "donghua" to "Donghua",
        "anime" to "Anime",
        "genre:action" to "Action",
        "genre:adventure" to "Adventure",
        "genre:fantasy" to "Fantasy",
        "genre:martial-arts" to "Martial Arts",
        "genre:xuanhuan" to "Xuanhuan",
        "genre:xianxia" to "Xianxia",
        "genre:wuxia" to "Wuxia",
        "genre:sci-fi" to "Sci-Fi",
        "genre:romance" to "Romance",
        "genre:comedy" to "Comedy"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl
    )

    private fun apiHeaders(): Map<String, String> {
        return headers + mapOf(
            "Accept" to "application/json,text/plain,text/html,*/*",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }

    private data class SourceSeries(
        val id: Int,
        val title: String,
        val slug: String,
        val poster: String,
        val kind: String = "Donghua",
        val status: String = "Ongoing",
        val type: TvType = TvType.Anime,
        val latestEpisode: Int = 1,
        val year: Int? = null,
        val score: Double? = null,
        val genres: Set<String> = emptySet(),
        val description: String = "Streaming Donghua subtitle Indonesia di Reynime.",
        val featured: Boolean = false,
        val updated: Boolean = false,
        val firstEpisode: Int = 1
    )

    private val episodeLabelRegex = Regex("""(?:episode|eps?|ep|bab)\s*[-:]?\s*\d{1,4}|^\s*\d{1,4}\s*$""", RegexOption.IGNORE_CASE)
    private val apiRootRegex = Regex("""^https?://[^/]+""")
    private val duplicateSlashRegex = Regex("""/{2,}""")

    private fun JSONObject.hasAnyKey(vararg keys: String): Boolean {
        return keys.any { has(it) && !isNull(it) }
    }

    private fun JSONObject.hasAnyTextKey(vararg keys: String): Boolean {
        return keys.any { key ->
            if (!has(key) || isNull(key)) return@any false
            opt(key).toString().cleanEscaped().trim().let { it.isNotBlank() && !it.equals("null", true) }
        }
    }

    private fun JSONObject.looksLikeEpisodeObject(): Boolean {
        val hasPlayable = backendVideoKeys.any { key -> pickString(key) != null }
        val hasEpisodeIdentity = hasAnyTextKey(
            "episode_id", "episodeId", "watch_id", "pid", "episode_number", "episodeNumber",
            "episode", "ep", "eps", "number"
        )
        val label = pickString("episode_title", "label", "judul", "title", "name")
        val hasEpisodeLabel = label?.let { episodeLabelRegex.containsMatchIn(it) } == true

        val hasStrongSeriesSignal = hasAnyTextKey(
            "series_id", "seriesId", "anime_id", "animeId", "series_title", "anime_title",
            "title_japanese", "latest_episode", "latestEpisode", "total_episode", "totalEpisode",
            "chapter_count", "chapterCount", "status", "genres", "genre", "slug"
        ) && !hasEpisodeIdentity && !hasPlayable && !hasEpisodeLabel

        return (hasPlayable || hasEpisodeIdentity || hasEpisodeLabel) && !hasStrongSeriesSignal
    }

    private fun JSONObject.looksLikeSeriesObject(): Boolean {
        if (looksLikeEpisodeObject()) return false
        return hasAnyTextKey(
            "series_id", "seriesId", "series_title", "anime_id", "animeId", "title", "name", "slug",
            "poster", "cover", "banner", "thumbnail", "latest_episode", "latestEpisode", "status", "genre", "genres"
        )
    }

    private val sourceSeries = listOf(
        SourceSeries(1, "Battle Through The Heavens Season 5", "battle-through-the-heaven-s5", "https://cdn.myanimelist.net/images/anime/1457/152289l.jpg", "Donghua", "Ongoing", TvType.Anime, 200, 2022, 9.20, setOf("action", "adventure", "fantasy", "martial-arts", "xuanhuan", "donghua"), "Season kelima dari Doupo Cangqiong.", true, true, firstEpisode = 105),
        SourceSeries(11, "Renegade Immortal", "renegade-immortal", "https://cdn.myanimelist.net/images/anime/1289/149708l.jpg", "Donghua", "Ongoing", TvType.Anime, 142, 2023, 7.80, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua"), "Wang Lin menembus kurangnya bakat dan berjalan menuju jalan abadi sejati.", true, true),
        SourceSeries(29, "Tales of Herding Gods", "tales-of-herding-gods", "https://cdn.myanimelist.net/images/anime/1324/150012l.jpg", "Donghua", "Ongoing", TvType.Anime, 84, 2024, 8.80, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua"), "Qin Mu tumbuh di desa lansia penyandang cacat dan menghadapi bahaya Daxu.", true, true),
        SourceSeries(4, "Perfect World", "perfect-world", "https://cdn.myanimelist.net/images/anime/1809/153679l.jpg", "Donghua", "Ongoing", TvType.Anime, 200, 2021, 8.00, setOf("action", "adventure", "fantasy", "martial-arts", "xuanhuan", "donghua"), "Shi Hao, jenius yang diberkati langit, menempuh perjalanan untuk mengguncang dunia.", true, false),
        SourceSeries(3, "Soul Land 2: The Peerless Tang Clan", "soul-land-2-the-peerless-tang-clan", "https://cdn.myanimelist.net/images/anime/1985/150594l.jpg", "Donghua", "Ongoing", TvType.Anime, 100, 2023, 8.40, setOf("action", "adventure", "fantasy", "martial-arts", "donghua"), "Generasi baru Shrek berusaha membangun kembali Tang Clan.", true, false, firstEpisode = 55),

        SourceSeries(26, "Martial Master", "martial-master", "https://cdn.myanimelist.net/images/anime/1738/107609l.jpg", "Donghua", "Ongoing", TvType.Anime, 660, 2020, 7.80, setOf("action", "fantasy", "martial-arts", "donghua"), updated = true),
        SourceSeries(84, "Sword and Fairy 3", "sword-and-fairy-3", "https://cdn.myanimelist.net/images/anime/1567/154040l.jpg", "Donghua", "Ongoing", TvType.Anime, 24, 2025, null, setOf("action", "adventure", "fantasy", "donghua"), updated = true),
        SourceSeries(24, "Peerless Battle Spirit", "peerless-battle-spirit", "https://cdn.myanimelist.net/images/anime/1290/150120l.jpg", "Donghua", "Ongoing", TvType.Anime, 179, 2024, 7.80, setOf("action", "fantasy", "martial-arts", "donghua"), updated = true),
        SourceSeries(12, "Swallowed Star 4th Season", "swallowed-star-4th-season", "https://cdn.myanimelist.net/images/anime/1335/138907l.jpg", "Donghua", "Ongoing", TvType.Anime, 225, 2023, 7.80, setOf("action", "adventure", "sci-fi", "donghua"), updated = true),
        SourceSeries(136, "Walking the Way All Alone", "walking-the-way-all-alone", "https://cdn.myanimelist.net/images/anime/1648/156622l.jpg", "Donghua", "Ongoing", TvType.Anime, 9, 2026, null, setOf("action", "adventure", "fantasy", "donghua"), updated = true),
        SourceSeries(83, "Beyond Time's Gaze", "beyond-times-gaze", "https://cdn.myanimelist.net/images/anime/1628/153418l.jpg", "Donghua", "Ongoing", TvType.Anime, 23, 2025, null, setOf("fantasy", "romance", "mystery", "supernatural", "donghua"), updated = true),

        SourceSeries(138, "Coiling Dragon", "coiling-dragon", "https://cdn.myanimelist.net/images/anime/1578/156842.jpg", "Donghua", "Ongoing", TvType.Anime, 6, 2026, null, setOf("action", "adventure", "fantasy", "donghua")),
        SourceSeries(137, "Ever Night", "ever-night", "https://cdn.myanimelist.net/images/anime/1021/156293l.jpg", "Donghua", "Ongoing", TvType.Anime, 6, 2026, null, setOf("action", "adventure", "fantasy", "donghua")),
        SourceSeries(120, "Against the Gods 2nd Season", "against-the-gods-2nd-season", "https://cdn.myanimelist.net/images/anime/1125/156004l.jpg", "Donghua", "Ongoing", TvType.Anime, 38, 2026, null, setOf("action", "fantasy", "martial-arts", "donghua")),
        SourceSeries(118, "In Search of Gods", "in-search-of-gods", "https://cdn.myanimelist.net/images/anime/1433/156264l.jpg", "Donghua", "Ongoing", TvType.Anime, 11, 2026, null, setOf("action", "fantasy", "donghua")),
        SourceSeries(116, "Azure Legacy 3", "azure-legacy-3", "https://cdn.myanimelist.net/images/anime/1742/153462l.jpg", "Donghua", "Ongoing", TvType.Anime, 78, 2026, null, setOf("action", "fantasy", "donghua")),
        SourceSeries(115, "Maou no Musume wa Yasashisugiru!!", "maou-no-musume-wa-yasashisugiru", "https://cdn.myanimelist.net/images/anime/1160/154083l.jpg", "Anime", "Ongoing", TvType.Anime, 10, 2026, null, setOf("comedy", "fantasy", "anime")),
        SourceSeries(114, "Yuusha Party ni Kawaii Ko ga Ita node, Kokuhaku shitemita.", "yuusha-party-ni-kawaii-ko-ga-ita-node-kokuhaku-shitemita", "https://cdn.myanimelist.net/images/anime/1573/152828l.jpg", "Anime", "Ongoing", TvType.Anime, 9, 2026, null, setOf("comedy", "fantasy", "romance", "anime")),
        SourceSeries(113, "Mayonaka Heart Tune", "mayonaka-heart-tune", "https://cdn.myanimelist.net/images/anime/1769/152823l.jpg", "Anime", "Ongoing", TvType.Anime, 9, 2026, null, setOf("comedy", "romance", "anime")),
        SourceSeries(112, "Isekai no Sata wa Shachiku Shidai", "isekai-no-sata-wa-shachiku-shidai", "https://cdn.myanimelist.net/images/anime/1510/153806l.jpg", "Anime", "Ongoing", TvType.Anime, 9, 2026, null, setOf("fantasy", "isekai", "anime")),

        SourceSeries(139, "Stellar Transformation 7th Season", "stellar-transformation-7th-season", "https://cdn.myanimelist.net/images/anime/1147/156957l.jpg", "Donghua", "Completed", TvType.Anime, 12, 2026, null, setOf("action", "adventure", "fantasy", "donghua"), updated = true),
        SourceSeries(119, "Shrouding the Heavens: The Imperial Path", "shrouding-the-heavens-the-imperial-path", "https://cdn.myanimelist.net/images/anime/1017/156548l.jpg", "Donghua", "Completed", TvType.AnimeMovie, 1, 2026, null, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua")),
        SourceSeries(74, "Long Hun", "long-hun", "https://cdn.myanimelist.net/images/anime/1878/153807l.jpg", "Donghua", "Completed", TvType.Anime, 8, 2025, 8.50, setOf("action", "fantasy", "donghua")),
        SourceSeries(71, "Wu Dong Qian Kun 6th Season", "wu-dong-qian-kun-6th-season", "https://cdn.myanimelist.net/images/anime/1937/151456l.jpg", "Donghua", "Completed", TvType.Anime, 12, 2025, 9.00, setOf("action", "fantasy", "martial-arts", "donghua")),
        SourceSeries(70, "Back as Immortal Lord", "back-as-immortal-lord", "https://cdn.myanimelist.net/images/anime/1109/153446l.jpg", "Donghua", "Completed", TvType.Anime, 16, 2025, null, setOf("action", "fantasy", "donghua")),
        SourceSeries(68, "Eternal Sword Emperor", "eternal-sword-emperor", "https://cdn.myanimelist.net/images/anime/1794/151549l.jpg", "Donghua", "Completed", TvType.Anime, 40, 2025, 7.80, setOf("action", "fantasy", "martial-arts", "donghua")),
        SourceSeries(65, "Legend of the Misty Sword Immortal", "legend-of-the-misty-sword-immortal", "https://cdn.myanimelist.net/images/anime/1600/151290l.jpg", "Donghua", "Completed", TvType.Anime, 40, 2025, 7.50, setOf("action", "fantasy", "martial-arts", "donghua")),
        SourceSeries(64, "Throne of Ten Thousand Swords", "throne-of-ten-thousand-swords", "https://cdn.myanimelist.net/images/anime/1098/151287l.webp", "Donghua", "Completed", TvType.Anime, 40, 2025, 7.80, setOf("action", "fantasy", "martial-arts", "wuxia", "donghua")),
        SourceSeries(63, "Long March to the Stars", "long-march-to-the-stars", "https://cdn.myanimelist.net/images/anime/1273/151289l.jpg", "Donghua", "Completed", TvType.Anime, 4, 2025, 8.20, setOf("action", "adventure", "sci-fi", "donghua")),
        SourceSeries(62, "Zi Chuan 2nd Season", "zi-chuan-2nd-season", "https://cdn.myanimelist.net/images/anime/1186/151163l.jpg", "Donghua", "Completed", TvType.Anime, 43, 2025, 8.20, setOf("action", "fantasy", "donghua"))
    )

    private fun seriesUrl(item: SourceSeries): String = "$mainUrl/series/${item.id}/${item.slug}"
    private fun bareSeriesUrl(item: SourceSeries): String = "$mainUrl/series/${item.id}"
    private fun legacySeriesUrl(item: SourceSeries): String = "$mainUrl/series/${item.id}/${item.slug}"

    private fun SourceSeries.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, seriesUrl(this), type) {
            posterUrl = poster
            score = this@toSearchResponse.score?.let { com.lagradost.cloudstream3.Score.from10(it) }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The current source is a JavaScript-heavy SPA. HTML/API catalog responses can expose
        // placeholder objects such as the global site title, so the homepage intentionally uses
        // validated seed metadata and canonical /series/{id}/{slug} URLs.
        val items = sourceItemsFor(request.data)
            .filter { it.name.isNotBlank() && !isBadTitle(it.name) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = false)
    }

    private fun sourceItemsFor(data: String): List<SearchResponse> {
        val key = data.lowercase()
        val items = when {
            key == "featured" -> sourceSeries.filter { it.featured }
            key == "updated" -> sourceSeries.filter { it.updated }
            key == "ongoing" -> sourceSeries.filter { it.status.equals("Ongoing", true) }
            key == "completed" -> sourceSeries.filter { it.status.equals("Completed", true) }
            key == "anime" -> sourceSeries.filter { it.kind.equals("Anime", true) }
            key == "donghua" || key == "all" -> sourceSeries.filter { it.kind.equals("Donghua", true) }
            key.startsWith("genre:") -> {
                val genre = key.substringAfter("genre:")
                sourceSeries.filter { item -> item.genres.any { it.equals(genre, true) } }
            }
            else -> sourceSeries
        }
        return items.ifEmpty { sourceSeries }.map { it.toSearchResponse() }
    }

    private fun parseMarkdownLikeCards(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()
        Regex(
            """\[!\[([^\]]+)\]\(([^)]+)\)[\s\S]{0,500}?\]\((https?://reynime\.my\.id/series/\d+[^)]*|/series/\d+[^)]*)\)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val title = match.groupValues[1].cleanTitle()
            val poster = normalizePoster(match.groupValues[2])
            val href = normalizeUrl(match.groupValues[3], mainUrl).substringBefore("?")
            if (!isBadTitle(title) && href.startsWith(mainUrl) && !isBlockedCatalogUrl(href)) {
                results[href] = newAnimeSearchResponse(title, href, findTypeFromSeedOrText(href, title)) {
                    posterUrl = poster
                }
            }
        }
        return results.values.toList()
    }

    private fun parseHtmlCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select("a[href*='/series/']:has(img), article:has(a[href*='/series/']):has(img), .card:has(a[href*='/series/']):has(img)")
            .forEach { element ->
                element.toSearchResult()?.let { result -> results[result.url] = result }
            }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("a[href*='/series/']") ?: return null
        val href = normalizeUrl(anchor.attr("href"), mainUrl).substringBefore("?")
        if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val title = listOf(
            image?.attr("alt"),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            selectFirst("h1,h2,h3,.title,.name,.font-semibold,.text-lg,.text-xl")?.text(),
            anchor.text()
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }?.cleanTitle() ?: return null

        if (title.equals("Beranda", true) || title.equals("Donghua Terbaru", true)) return null

        val poster = image?.getImageAttr()?.let { normalizePoster(normalizeUrl(it, href)) }
            ?: findSeedFromUrl(href)?.poster

        return newAnimeSearchResponse(title, href, findTypeFromSeedOrText(href, title)) {
            posterUrl = poster
        }
    }

    private fun parseRawSeriesLinks(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()
        Regex("""(?:href|url|to)=['\"]([^'\"]*/series/\d+[^'\"]*)['\"]|['\"]([^'\"]*/series/\d+[^'\"]*)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
                val href = normalizeUrl(raw, mainUrl).substringBefore("?")
                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEach
                val nearby = clean.substring((match.range.first - 900).coerceAtLeast(0), (match.range.last + 1300).coerceAtMost(clean.length))
                val seed = findSeedFromUrl(href)
                val title = listOfNotNull(
                    seed?.title,
                    Regex("""!\[([^\]]+)\]\(""", RegexOption.IGNORE_CASE).find(nearby)?.groupValues?.getOrNull(1),
                    Regex("""(?:alt|title)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE).find(nearby)?.groupValues?.getOrNull(1),
                    extractJsonString(nearby, "title"),
                    extractJsonString(nearby, "name")
                ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }?.cleanTitle() ?: return@forEach
                if (title.equals("Beranda", true) || title.equals("Donghua Terbaru", true)) return@forEach
                val poster = findImageNear(nearby)?.let { normalizePoster(normalizeUrl(it, href)) } ?: seed?.poster
                results[href] = newAnimeSearchResponse(title, href, seed?.type ?: findTypeFromSeedOrText(href, title)) { posterUrl = poster }
            }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val results = linkedMapOf<String, SearchResponse>()
        listOf(
            "$mainUrl/browse?search=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/api/series?search=$encoded",
            "$mainUrl/api/search?q=$encoded"
        ).forEach { url ->
            val response = runCatching { app.get(url, headers = headers, referer = mainUrl, timeout = 8L) }.getOrNull() ?: return@forEach
            parseApiSeriesCards(response.text).forEach { results[it.url] = it }
            parseMarkdownLikeCards(response.text).forEach { results[it.url] = it }
            parseHtmlCards(response.document).forEach { results[it.url] = it }
            parseRawSeriesLinks(response.text).forEach { results[it.url] = it }
        }
        if (results.isNotEmpty()) return results.values.toList()
        return sourceSeries.filter {
            it.title.contains(keyword, true) || it.slug.contains(keyword.slugify(), true)
        }.ifEmpty { sourceSeries.take(8) }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = normalizeUrl(url, mainUrl).substringBefore("#").substringBefore("?")
        val seed = findSeedFromUrl(pageUrl)
        val response = runCatching { app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 10L) }.getOrNull()
        val html = response?.text?.cleanEscaped().orEmpty()
        val document = response?.document ?: Jsoup.parse("")

        val title = listOfNotNull(
            seed?.title,
            extractJsonString(html, "series_title"),
            extractJsonString(html, "anime_title"),
            extractJsonString(html, "title"),
            extractJsonString(html, "name"),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1, .text-2xl, .text-3xl, .font-bold, .font-semibold")?.text()
        ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: pageUrl.substringAfterLast("/").replace("-", " ").cleanTitle().ifBlank { name }

        val poster = listOfNotNull(
            seed?.poster,
            extractJsonString(html, "poster"),
            extractJsonString(html, "cover"),
            extractJsonString(html, "image"),
            extractJsonString(html, "thumbnail"),
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[alt*='${title.take(24)}'], img.h-full, img.w-full, .poster img, .cover img, img")?.getImageAttr()
        ).firstOrNull { it.isNotBlank() }?.let { normalizePoster(normalizeUrl(it, pageUrl)) }

        val description = listOfNotNull(
            seed?.description,
            extractJsonString(html, "description"),
            extractJsonString(html, "synopsis"),
            extractJsonString(html, "overview"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".synopsis, .description, .desc, article p, main p")?.text()
        ).firstOrNull { it.isNotBlank() && it.length > 20 && !isBadTitle(it) }?.cleanTitle()

        val tags = seed?.genres?.map { it.replace('-', ' ').cleanTitle() }
            ?.takeIf { it.isNotEmpty() }
            ?: document.select("a[href*='genre'], a[href*='tag'], .genre a, .genres a, .tags a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !isBadTitle(it) }
                .distinct()
                .ifEmpty { listOf(seed?.kind ?: "Donghua") }

        val episodes = if (seed != null) {
            // Runtime note: the public app shell can leak cached/global episode payloads.
            // Keep detail episode rows deterministic from validated seed metadata, then resolve
            // the real playable link inside loadLinks with exact series/episode probes.
            fallbackEpisodes(pageUrl, seed, poster, description)
        } else {
            parseEpisodes(document, html, pageUrl, poster)
                .ifEmpty { fallbackEpisodes(pageUrl, seed, poster, description) }
        }

        return newAnimeLoadResponse(title, seed?.let { seriesUrl(it) } ?: pageUrl, seed?.type ?: findTypeFromSeedOrText(pageUrl, title)) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description ?: "Streaming Donghua subtitle Indonesia di Reynime."
            this.tags = tags
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
            recommendations = sourceSeries.filter { it.id != seed?.id }.take(12).map { it.toSearchResponse() }
        }
    }

    private fun parseEpisodes(document: Document, html: String, pageUrl: String, poster: String?): List<Episode> {
        val results = linkedMapOf<String, Episode>()
        document.select("a[href*='/watch/'], a[href*='/episode/'], a[href*='/stream/'], button[data-url], [data-url*='/watch/']")
            .forEachIndexed { index, element ->
                val raw = element.attr("href").ifBlank { element.attr("data-url") }.ifBlank { element.attr("data-href") }
                val href = normalizeUrl(raw, pageUrl).substringBefore("?")
                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEachIndexed
                val label = element.text().ifBlank { element.attr("title") }.cleanTitle()
                val ep = extractEpisodeNumber(label, href) ?: (index + 1)
                results[href] = newEpisode(href) {
                    name = label.ifBlank { "Episode $ep" }
                    episode = ep
                    posterUrl = poster
                }
            }

        Regex("""\bEP\s*(\d{1,4})\b|\bEpisode\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
            .findAll("$html ${document.text()}")
            .mapNotNull { it.groupValues.drop(1).firstOrNull { number -> number.isNotBlank() }?.toIntOrNull() }
            .distinct()
            .sorted()
            .forEach { ep ->
                val seed = findSeedFromUrl(pageUrl)
                val data = seed?.let { watchUrl(it, ep) } ?: "$pageUrl#episode-$ep"
                results[data] = newEpisode(data) {
                    name = "Episode $ep"
                    episode = ep
                    posterUrl = poster
                }
            }
        return results.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun fallbackEpisodes(pageUrl: String, seed: SourceSeries?, poster: String?, description: String?): List<Episode> {
        val latest = seed?.latestEpisode?.coerceAtLeast(1) ?: 1
        val first = seed?.firstEpisode?.coerceAtLeast(1) ?: 1
        val last = latest.coerceAtLeast(first)
        return (first..last).map { ep ->
            val data = seed?.let { watchUrl(it, ep) } ?: "$pageUrl#episode-$ep"
            newEpisode(data) {
                name = if (latest == 1 && seed?.type == TvType.AnimeMovie) "Movie" else "Episode $ep"
                episode = ep
                posterUrl = poster
                this.description = description
            }
        }
    }

    private fun watchUrl(seed: SourceSeries, episode: Int): String {
        return "$mainUrl/watch/${seed.id}/$episode#series=${seed.id}&episode=$episode&slug=${seed.slug}"
    }

    private fun watchUrl(episodeId: Int, seriesId: Int? = null, episode: Int? = null): String {
        val meta = listOfNotNull(
            seriesId?.let { "series=$it" },
            episode?.let { "episode=$it" },
            "episode_id=$episodeId"
        ).joinToString("&")
        return "$mainUrl/watch/$episodeId#$meta"
    }

    private data class PlaybackContext(
        val requestedUrl: String,
        val pageUrl: String,
        val seriesId: String?,
        val episode: String?,
        val directEpisodeId: String?,
        val seed: SourceSeries?
    )

    private data class PlaybackBuckets(
        val directLinks: LinkedHashSet<String> = linkedSetOf(),
        val embedLinks: LinkedHashSet<String> = linkedSetOf()
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val context = parsePlaybackContext(data)

        // Keep the resolver order explicit. This makes runtime failures easier to isolate:
        // backend API first, page/player scraping second, and external fallback last.
        if (resolveFromBackend(context, subtitleCallback, callback)) return true
        if (resolveFromPage(context, subtitleCallback, callback)) return true
        return resolveFromEmergencyFallback(context, subtitleCallback, callback)
    }

    private fun parsePlaybackContext(data: String): PlaybackContext {
        val requestedUrl = normalizeUrl(data, mainUrl)
        val syntheticWatch = Regex("""/watch/(\d+)/(\d+)""").find(requestedUrl)
        val directEpisodeId = Regex("""/watch/(\d+)(?:[/?#]|$)""").find(requestedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { syntheticWatch == null }
            ?: Regex("""(?:episode_id|api_id|id)=(\d+)""").find(requestedUrl)?.groupValues?.getOrNull(1)
        val seriesId = syntheticWatch?.groupValues?.getOrNull(1)
            ?: Regex("""/series/(\d+)""").find(requestedUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""series(?:_id)?=(\d+)""").find(requestedUrl)?.groupValues?.getOrNull(1)
        val episode = syntheticWatch?.groupValues?.getOrNull(2)
            ?: Regex("""episode(?:_number)?=(\d+)""").find(requestedUrl)?.groupValues?.getOrNull(1)
            ?: Regex("""#episode-(\d+)""").find(requestedUrl)?.groupValues?.getOrNull(1)
        val seed = seriesId?.toIntOrNull()?.let { value -> sourceSeries.firstOrNull { it.id == value } }
        val pageUrl = seed?.let { seriesUrl(it) } ?: requestedUrl.substringBefore("#").substringBefore("?")

        return PlaybackContext(
            requestedUrl = requestedUrl,
            pageUrl = pageUrl,
            seriesId = seriesId,
            episode = episode,
            directEpisodeId = directEpisodeId,
            seed = seed
        )
    }

    private suspend fun resolveFromBackend(
        context: PlaybackContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val buckets = PlaybackBuckets()

        collectBackendEpisodeUrls(
            seriesId = context.seriesId,
            episodeNumber = context.episode,
            episodeId = context.directEpisodeId,
            referer = context.pageUrl
        ).forEach { url ->
            addCandidate(url, mainUrl, buckets.directLinks, buckets.embedLinks)
        }

        return emitCollectedLinks(
            buckets = buckets,
            referer = context.pageUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun resolveFromPage(
        context: PlaybackContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val buckets = PlaybackBuckets()

        playbackProbeUrls(context).forEach { url ->
            val response = runCatching {
                app.get(url, headers = headers, referer = context.pageUrl, timeout = 10L)
            }.getOrNull() ?: return@forEach

            val raw = response.text.cleanEscaped()
            collectCandidatesFromDocument(response.document, url, buckets.directLinks, buckets.embedLinks)
            extractPlayableUrls(raw).forEach { addCandidate(it, url, buckets.directLinks, buckets.embedLinks) }
            extractSubtitles(raw, url, subtitleCallback)
            decodeBase64Payloads(raw).forEach { decoded ->
                extractPlayableUrls(decoded).forEach { addCandidate(it, url, buckets.directLinks, buckets.embedLinks) }
            }
            runCatching { if (!getPacked(raw).isNullOrEmpty()) getAndUnpack(raw) else null }
                .getOrNull()
                ?.cleanEscaped()
                ?.let { unpacked -> extractPlayableUrls(unpacked).forEach { addCandidate(it, url, buckets.directLinks, buckets.embedLinks) } }
        }

        return emitCollectedLinks(
            buckets = buckets,
            referer = context.pageUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun resolveFromEmergencyFallback(
        context: PlaybackContext,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        resolveDailymotionFallback(context.seed, context.episode, context.pageUrl).forEach { dmUrl ->
            if (runCatching { loadExtractor(dmUrl, context.pageUrl, subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
            }
        }
        return found
    }

    private suspend fun emitCollectedLinks(
        buckets: PlaybackBuckets,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        prioritizeEmbeds(buckets.embedLinks).take(8).forEach { embed ->
            if (runCatching { loadExtractor(embed, referer, subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
            }
            resolveNestedLinks(embed, referer).forEach { nested ->
                if (emitDirectLink(nested, embed, callback)) found = true
            }
        }

        buckets.directLinks.forEach { link ->
            if (emitDirectLink(link, referer, callback)) found = true
        }

        return found
    }

    private fun playbackProbeUrls(context: PlaybackContext): List<String> {
        val candidates = linkedSetOf<String>()
        candidates.add(context.pageUrl)
        context.seed?.let { item ->
            candidates.add(seriesUrl(item))
            candidates.add(bareSeriesUrl(item))
            candidates.add(legacySeriesUrl(item))
            context.episode?.let { ep ->
                candidates.add("$mainUrl/watch/${item.id}/$ep")
                candidates.add("$mainUrl/watch/${item.id}?episode=$ep")
                candidates.add("$mainUrl/watch?series=${item.id}&episode=$ep")
            }
        }
        context.directEpisodeId?.let { episodeId ->
            candidates.add("$mainUrl/watch/$episodeId")
        }
        return candidates.toList()
    }
    private suspend fun resolveDailymotionFallback(seed: SourceSeries?, episode: String?, referer: String): List<String> {
        if (seed == null || episode.isNullOrBlank()) return emptyList()
        val ep = episode.toIntOrNull() ?: return emptyList()
        val queries = listOf(
            "${seed.title} Episode $ep Sub Indo",
            "${seed.title} Ep $ep",
            "${seed.title} $ep Reynime"
        )

        val results = linkedSetOf<String>()
        queries.forEach { query ->
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            listOf(
                "https://www.dailymotion.com/search/$encoded/videos",
                "https://www.dailymotion.com/reynime/videos"
            ).forEach { url ->
                val raw = runCatching {
                    app.get(url, headers = headers + mapOf("Accept" to "text/html,*/*"), referer = referer, timeout = 10L).text
                }.getOrNull()?.cleanEscaped().orEmpty()
                if (raw.isBlank()) return@forEach

                Regex("""(?:/video/|%2Fvideo%2F)([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                    .findAll(raw)
                    .forEach { match ->
                        val id = match.groupValues.getOrNull(1).orEmpty()
                        if (id.isBlank()) return@forEach
                        val nearby = raw.substring(
                            (match.range.first - 900).coerceAtLeast(0),
                            (match.range.last + 1400).coerceAtMost(raw.length)
                        )
                        if (looksLikeDailymotionEpisode(seed, ep, nearby)) {
                            results.add("https://www.dailymotion.com/video/$id")
                        }
                    }
            }
        }
        return results.take(6)
    }

    private fun looksLikeDailymotionEpisode(seed: SourceSeries, episode: Int, text: String): Boolean {
        val haystack = text.cleanEscaped().lowercase()
        val slug = haystack.slugify()
        val titleTokens = seed.title
            .slugify()
            .split('-')
            .filter { it.length >= 4 && it !in setOf("season", "episode", "donghua", "anime", "the", "and") }
        val matchedTitleTokens = titleTokens.count { slug.contains(it) }
        val episodePattern = Regex("""(?i)(episode|eps?|ep|bab|e)\s*[-:_ ]*0*${episode}\b|\b0*${episode}\s*(sub\s*indo|subtitle|reynime)""")
        val hasEpisode = episodePattern.containsMatchIn(haystack) || slug.contains("episode-$episode") || slug.contains("ep-$episode")
        val hasReynimeOrSub = haystack.contains("reynime") || haystack.contains("sub indo") || haystack.contains("subtitle indonesia")
        return hasEpisode && hasReynimeOrSub && matchedTitleTokens >= 2
    }

    private data class BackendEpisodeRecord(
        val id: String?,
        val seriesId: String?,
        val episodeNumber: String?,
        val urls: List<String>
    )

    private suspend fun collectBackendEpisodeUrls(
        seriesId: String?,
        episodeNumber: String?,
        episodeId: String?,
        referer: String
    ): List<String> {
        val collected = linkedSetOf<String>()
        val episodeIdsToProbe = linkedSetOf<String>()

        episodeId?.takeIf { it.isNotBlank() }?.let { id ->
            probeEpisodeById(id, seriesId, referer).forEach { record ->
                record.urls.forEach(collected::add)
                record.id?.takeIf { it.isNotBlank() }?.let(episodeIdsToProbe::add)
            }
        }

        if (!seriesId.isNullOrBlank()) {
            probeEpisodesBySeries(seriesId, referer)
                .filter { record -> episodeNumber.isNullOrBlank() || record.matchesEpisode(episodeNumber) }
                .forEach { record ->
                    record.urls.forEach(collected::add)
                    record.id?.takeIf { it.isNotBlank() }?.let(episodeIdsToProbe::add)
                }

            if (collected.isEmpty() && !episodeNumber.isNullOrBlank()) {
                probeEpisodesBySeriesEpisode(seriesId, episodeNumber, referer).forEach { record ->
                    record.urls.forEach(collected::add)
                    record.id?.takeIf { it.isNotBlank() }?.let(episodeIdsToProbe::add)
                }
            }
        }

        episodeId?.let { episodeIdsToProbe.remove(it) }

        if (collected.isEmpty()) {
            episodeIdsToProbe.take(6).forEach { id ->
                probeEpisodeStreamsById(id, referer).forEach(collected::add)
            }
        }

        return collected
            .map { it.cleanEscaped().trim() }
            .filter { it.startsWith("http", true) || it.startsWith("//") }
            .distinct()
    }

    private suspend fun probeEpisodeById(
        episodeId: String,
        expectedSeriesId: String?,
        referer: String
    ): List<BackendEpisodeRecord> {
        val url = "$mainUrl/backend/api/episodes.php?id=$episodeId&_t=${System.currentTimeMillis()}"
        return probeBackendRecords(url, referer).filterBySeries(expectedSeriesId)
    }

    private suspend fun probeEpisodesBySeries(
        seriesId: String,
        referer: String
    ): List<BackendEpisodeRecord> {
        val url = "$mainUrl/backend/api/episodes.php?series_id=$seriesId&limit=1000&_t=${System.currentTimeMillis()}"
        return probeBackendRecords(url, referer).filterBySeries(seriesId)
    }

    private suspend fun probeEpisodesBySeriesEpisode(
        seriesId: String,
        episodeNumber: String,
        referer: String
    ): List<BackendEpisodeRecord> {
        val queries = listOf(
            "$mainUrl/backend/api/episodes.php?series_id=$seriesId&episode=$episodeNumber&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/episodes.php?series_id=$seriesId&episode_number=$episodeNumber&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/episodes.php?series_id=$seriesId&ep=$episodeNumber&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/episodes.php?series_id=$seriesId&number=$episodeNumber&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/watch.php?series_id=$seriesId&episode=$episodeNumber&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/stream.php?series_id=$seriesId&episode=$episodeNumber&_t=${System.currentTimeMillis()}"
        )

        val results = linkedSetOf<BackendEpisodeRecord>()
        queries.forEach { url ->
            probeBackendRecords(url, referer)
                .filterBySeries(seriesId)
                .filter { record -> record.episodeNumber.isNullOrBlank() || record.matchesEpisode(episodeNumber) || record.urls.isNotEmpty() }
                .forEach(results::add)
        }
        return results.toList()
    }

    private suspend fun probeEpisodeStreamsById(
        episodeId: String,
        referer: String
    ): List<String> {
        val queries = listOf(
            "$mainUrl/backend/api/episodes.php?id=$episodeId&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/watch.php?id=$episodeId&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/stream.php?id=$episodeId&_t=${System.currentTimeMillis()}",
            "$mainUrl/backend/api/player.php?id=$episodeId&_t=${System.currentTimeMillis()}"
        )

        val urls = linkedSetOf<String>()
        queries.forEach { url ->
            val response = runCatching {
                app.get(url, headers = apiHeaders(), referer = referer, timeout = 10L)
            }.getOrNull() ?: return@forEach

            val raw = response.text.cleanEscaped()
            parseBackendEpisodeRecords(raw).forEach { record ->
                record.urls.forEach(urls::add)
            }
            extractPlayableUrls(raw).forEach(urls::add)
        }
        return urls.toList()
    }

    private suspend fun probeBackendRecords(
        url: String,
        referer: String
    ): List<BackendEpisodeRecord> {
        val response = runCatching {
            app.get(url, headers = apiHeaders(), referer = referer, timeout = 10L)
        }.getOrNull() ?: return emptyList()

        return parseBackendEpisodeRecords(response.text)
    }

    private fun List<BackendEpisodeRecord>.filterBySeries(seriesId: String?): List<BackendEpisodeRecord> {
        if (seriesId.isNullOrBlank()) return this
        val exact = filter { it.seriesId == seriesId }
        return if (exact.isNotEmpty()) exact else filter { it.seriesId.isNullOrBlank() }
    }

    private fun BackendEpisodeRecord.matchesEpisode(expectedEpisode: String?): Boolean {
        if (expectedEpisode.isNullOrBlank()) return true
        val actual = episodeNumber ?: return false
        return normalizeEpisodeNumber(actual) == normalizeEpisodeNumber(expectedEpisode)
    }

    private fun normalizeEpisodeNumber(value: String): String {
        return Regex("""\d{1,4}""")
            .find(value)
            ?.value
            ?.trimStart('0')
            ?.ifBlank { "0" }
            ?: value.trim()
    }

    private fun parseBackendEpisodeRecords(text: String): List<BackendEpisodeRecord> {
        val clean = text.cleanEscaped().trim()
        val results = linkedMapOf<String, BackendEpisodeRecord>()

        fun addRecord(id: String?, seriesId: String?, episodeNumber: String?, urls: List<String>, fallbackKey: String) {
            val cleanedUrls = urls
                .map { it.cleanEscaped().trim() }
                .filter { it.contains("http", true) || it.startsWith("//") }
                .distinct()

            if (id == null && episodeNumber == null && cleanedUrls.isEmpty()) return
            val key = listOfNotNull(id, seriesId, episodeNumber).joinToString(":").ifBlank { fallbackKey }
            val existing = results[key]
            results[key] = BackendEpisodeRecord(
                id = id ?: existing?.id,
                seriesId = seriesId ?: existing?.seriesId,
                episodeNumber = episodeNumber ?: existing?.episodeNumber,
                urls = ((existing?.urls).orEmpty() + cleanedUrls).distinct()
            )
        }

        fun parseObject(obj: JSONObject) {
            val keyedUrls = backendVideoKeys.mapNotNull { key -> obj.pickString(key) }
            val scannedUrls = extractPlayableUrls(obj.toString())
            val title = obj.pickString("episode_title", "label", "judul", "title", "name")
            val episodeNumber = obj.pickString("episode_number", "episodeNumber", "episode", "ep", "eps", "number")
                ?: extractEpisodeNumber(title.orEmpty(), obj.toString())?.toString()
            val hasEpisodeSignal = obj.looksLikeEpisodeObject() || episodeNumber != null || keyedUrls.isNotEmpty() || scannedUrls.isNotEmpty()
            if (!hasEpisodeSignal) return

            val id = obj.pickString("episode_id", "episodeId", "watch_id", "pid", "post_id")
                ?: obj.pickString("id")?.takeIf { obj.looksLikeEpisodeObject() || episodeNumber != null || keyedUrls.isNotEmpty() || scannedUrls.isNotEmpty() }
            val seriesId = obj.pickString("series_id", "seriesId", "anime_id", "animeId")
            addRecord(id, seriesId, episodeNumber, keyedUrls + scannedUrls, obj.toString().hashCode().toString())
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                is JSONObject -> {
                    parseObject(value)
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
            }
        }

        runCatching {
            when {
                clean.startsWith("[") -> walk(JSONArray(clean))
                clean.startsWith("{") -> walk(JSONObject(clean))
            }
        }

        if (results.isNotEmpty()) return results.values.toList()

        return Regex("""\{[^{}]*}""")
            .findAll(clean)
            .map { it.value }
            .mapNotNull { obj ->
                val id = extractJsonValue(obj, "id")
                    ?: extractJsonValue(obj, "episode_id")
                    ?: extractJsonValue(obj, "episodeId")
                    ?: extractJsonValue(obj, "watch_id")
                val seriesId = extractJsonValue(obj, "series_id") ?: extractJsonValue(obj, "seriesId")
                val episodeNumber = extractJsonValue(obj, "episode_number")
                    ?: extractJsonValue(obj, "episodeNumber")
                    ?: extractJsonValue(obj, "episode")
                    ?: extractJsonValue(obj, "ep")
                val urls = backendVideoKeys.mapNotNull { key -> extractJsonValue(obj, key) } + extractPlayableUrls(obj)
                val cleanedUrls = urls
                    .map { it.cleanEscaped().trim() }
                    .filter { it.contains("http", true) || it.startsWith("//") }
                    .distinct()
                if (id == null && episodeNumber == null && cleanedUrls.isEmpty()) null else BackendEpisodeRecord(id, seriesId, episodeNumber, cleanedUrls)
            }
            .toList()
    }

    private fun parseApiSeriesCards(text: String): List<SearchResponse> {
        val clean = text.cleanEscaped().trim()
        if (clean.isBlank() || !(clean.startsWith("[") || clean.startsWith("{"))) return emptyList()
        val results = linkedMapOf<String, SearchResponse>()

        fun parseObject(obj: JSONObject) {
            if (!obj.looksLikeSeriesObject()) return
            val id = obj.pickInt("id", "series_id", "seriesId", "post_id") ?: return
            val rawTitle = obj.pickString("title", "name", "series_title", "judul") ?: return
            val title = rawTitle.cleanTitle()
            if (isBadTitle(title)) return
            val slug = obj.pickString("slug", "permalink") ?: title.slugify()
            val poster = obj.pickString("poster", "image", "thumbnail", "cover", "banner")
                ?.let { normalizePoster(normalizeUrl(it, mainUrl)) }
                ?: sourceSeries.firstOrNull { it.id == id }?.poster
            val type = obj.pickString("type", "kind", "category")?.let { getTypeFromText(it) }
                ?: sourceSeries.firstOrNull { it.id == id }?.type
                ?: TvType.Anime
            val url = "$mainUrl/series/$id${if (slug.isNotBlank()) "/$slug" else ""}"
            results[url] = newAnimeSearchResponse(title, url, type) {
                posterUrl = poster
            }
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                is JSONObject -> {
                    parseObject(value)
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
            }
        }

        runCatching {
            when {
                clean.startsWith("[") -> walk(JSONArray(clean))
                clean.startsWith("{") -> walk(JSONObject(clean))
            }
        }
        return results.values.toList()
    }

    private fun JSONObject.pickString(vararg keys: String): String? {
        keys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            val string = opt(key).toString().cleanEscaped().trim()
            if (string.isNotBlank() && !string.equals("null", true)) return string
        }
        return null
    }

    private fun JSONObject.pickInt(vararg keys: String): Int? {
        keys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            when (val value = opt(key)) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
                else -> value.toString().trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private val backendVideoKeys = listOf(
        "download_url",
        "video_url",
        "reynime_video_url",
        "vip_video_url",
        "regular_video_url",
        "regular_video_url_1",
        "regular_video_url_2",
        "regular_video_url_3",
        "regular_video_url_4",
        "regular_video_url_5",
        "regular_video_url_6",
        "regular_video_url_7",
        "regular_video_url_8",
        "embed_url",
        "player_url",
        "stream_url",
        "hls",
        "hls_url",
        "hlsUrl",
        "m3u8",
        "source",
        "src",
        "file",
        "url",
        "link",
        "video",
        "videoUrl",
        "video_url",
        "direct_url"
    )

    private fun extractJsonValue(text: String, key: String): String? {
        val pattern = """["']${Regex.escape(key)}["']\s*:\s*(?:["']((?:\\.|[^"'\\])*)["']|([^,}\]]+))"""
        val match = Regex(pattern, RegexOption.IGNORE_CASE).find(text) ?: return null
        return (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.groupValues.getOrNull(2))
            ?.trim()
            ?.trim('"', '\'')
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
    }


    private fun collectCandidatesFromDocument(document: Document, baseUrl: String, directLinks: MutableSet<String>, embedLinks: MutableSet<String>) {
        document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player], " +
                "iframe[src], iframe[data-src], video[src], video source[src], source[src], embed[src], object[data], a[href], " +
                "[data-url], [data-src], [data-video], [data-file], [data-embed], [data-iframe], [data-player]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-player") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(url: String, referer: String): List<String> {
        if (shouldSkipUrl(url)) return emptyList()
        val response = runCatching { app.get(url, headers = apiHeaders(), referer = referer, timeout = 10L) }.getOrNull() ?: return emptyList()
        val results = linkedSetOf<String>()
        val text = response.text.cleanEscaped()
        val direct = linkedSetOf<String>()
        val embed = linkedSetOf<String>()
        collectCandidatesFromDocument(response.document, url, direct, embed)
        results.addAll(direct)
        results.addAll(extractPlayableUrls(text))
        runCatching { if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null }
            .getOrNull()
            ?.cleanEscaped()
            ?.let { results.addAll(extractPlayableUrls(it)) }
        return results.map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filter { isDirectVideo(it) && !isBadMediaUrl(it) && !shouldSkipUrl(it) }
            .distinct()
    }

    private fun addCandidate(raw: String, baseUrl: String, directLinks: MutableSet<String>, embedLinks: MutableSet<String>) {
        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl).replace(".txt", ".m3u8").trim()
        if (fixed.isBlank() || isBadMediaUrl(fixed) || shouldSkipUrl(fixed)) return
        expandRumbleTarCandidates(fixed).forEach { candidate ->
            when {
                isDirectVideo(candidate) -> directLinks.add(candidate)
                candidate.startsWith("http", true) && isLikelyEmbed(candidate) -> embedLinks.add(candidate)
            }
        }
    }

    private fun expandRumbleTarCandidates(url: String): List<String> {
        if (!isRumbleTarHls(url)) return listOf(url)
        val noQuery = url.substringBefore("?")
        val match = Regex("""\.([a-z]aa)\.tar$""", RegexOption.IGNORE_CASE).find(noQuery)
        val base = match?.let { noQuery.removeSuffix(".${it.groupValues[1]}.tar") } ?: return listOf(url)
        val suffix = match.groupValues[1].lowercase()
        val variants = linkedSetOf<String>()
        listOf("aaa", suffix, "haa", "gaa", "caa", "baa").distinct().forEach { variants.add("$base.$it.mp4") }
        variants.add(url)
        return variants.toList()
    }

    private suspend fun emitDirectLink(link: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (isBadMediaUrl(link) || shouldSkipUrl(link)) return false
        if (isHlsLike(link)) {
            val generated = runCatching {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl
                    )
                ).forEach(callback)
                true
            }.getOrDefault(false)
            if (generated) return true
        }
        callback(
            newExtractorLink(
                name,
                name,
                link,
                if (isHlsLike(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = qualityFromUrl(link).takeIf { it != Qualities.Unknown.value } ?: Qualities.P720.value
                this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Origin" to mainUrl)
            }
        )
        return true
    }

    private suspend fun extractSubtitles(text: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val clean = text.cleanEscaped()
        Regex(
            """[\"'](?:label|lang|language)[\"']\s*:\s*[\"']([^\"']+)[\"'][^{}]{0,300}[\"'](?:file|url|src|path)[\"']\s*:\s*[\"']([^\"']+\.(?:vtt|srt|ass)[^\"']*)[\"']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val url = normalizeUrl(match.groupValues[2], baseUrl)
            if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile(normalizeSubtitleLabel(match.groupValues[1]), url))
        }
        Regex("""https?://[^\"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).forEach { match ->
                val url = match.value.cleanEscaped()
                if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile("Subtitle", url))
            }
    }

    private fun cleanPlayableUrl(raw: String): String {
        return raw.cleanEscaped()
            .trim()
            .trimEnd(',', ';', ')', ']', '}', '.')
            .replace(".txt", ".m3u8")
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        listOf(
            Regex("""https?://[^\"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt|tar)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""//[^\"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt|tar)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(clean).map { it.value }.forEach { raw ->
                val fixed = cleanPlayableUrl(if (raw.startsWith("//")) "https:$raw" else raw)
                if (!isBadMediaUrl(fixed) && !shouldSkipUrl(fixed)) urls.add(fixed)
            }
        }
        Regex("""https?%3A%2F%2F[^\"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.mkv|\.txt|\.tar|embed|player|stream)[^\"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { cleanPlayableUrl(it) }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }
        Regex("""(?:file|src|source|url|video|videoUrl|video_url|stream|streamUrl|stream_url|hls|hlsUrl|hls_url|embed|embedUrl|embed_url)\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { cleanPlayableUrl(it) }
            .filter { isDirectVideo(it) || isLikelyEmbed(it) }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }
        Regex("""https?://[^\"'\\\s<>]+?(?:embed|player|stream|watch|video|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|ok\.ru|dailymotion|rumble)[^\"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { cleanPlayableUrl(it.value) }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }
        return urls.toList()
    }

    private fun decodeBase64Payloads(text: String): List<String> {
        val results = linkedSetOf<String>()
        Regex("""[\"']([A-Za-z0-9+/=]{40,})[\"']""")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(30)
            .forEach { token ->
                runCatching { String(Base64.getDecoder().decode(token), Charsets.UTF_8) }
                    .getOrNull()
                    ?.takeIf { it.contains("http", true) || it.contains("iframe", true) || it.contains("m3u8", true) }
                    ?.let { results.add(it.cleanEscaped()) }
            }
        return results.toList()
    }

    private fun findSeedFromUrl(url: String): SourceSeries? {
        val value = url.lowercase()
        return sourceSeries.firstOrNull { value.contains("/series/${it.id}") || value.contains(it.slug.lowercase()) }
    }

    private fun findTypeFromSeedOrText(url: String, text: String): TvType {
        return findSeedFromUrl(url)?.type ?: getTypeFromText(text)
    }

    private fun findImageNear(text: String): String? {
        val clean = text.cleanEscaped()
        Regex("""(?:src|data-src|poster|image|cover|thumbnail|thumb)=['\"]([^'\"]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^'\"]*)?)['\"]""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""[\"'](?:poster|cover|image|thumbnail|thumb|src)[\"']\s*:\s*[\"']([^\"']+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^\"']*)?)[\"']""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""!\[[^\]]*\]\(([^)]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^)]*)?)\)""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun extractJsonString(text: String, key: String): String? {
        return Regex("""[\"']${Regex.escape(key)}[\"']\s*:\s*[\"']((?:\\.|[^\"'\\])*)[\"']""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
            ?.trim()
    }

    private fun sanitizeResolvedUrl(value: String): String {
        val normalized = value.cleanEscaped().trim().replace("\\", "/")
        val schemeIndex = normalized.indexOf("://")
        return if (schemeIndex >= 0) {
            val prefix = normalized.substring(0, schemeIndex + 3)
            val rest = normalized.substring(schemeIndex + 3).replace(duplicateSlashRegex, "/")
            prefix + rest
        } else {
            normalized.replace(duplicateSlashRegex, "/")
        }
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        val resolved = when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> apiRootRegex.find(baseUrl)?.value.orEmpty().ifBlank { mainUrl } + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
        return sanitizeResolvedUrl(resolved)
    }

    private fun normalizePoster(url: String): String {
        return url.replace("https://myanimelist.net/images/", "https://cdn.myanimelist.net/images/")
            .replace("http://", "https://")
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> = links
        .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
        .distinct()
        .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })

    private fun hostPriority(url: String): Int {
        val value = url.lowercase()
        return when {
            value.contains("reynime.my.id") -> 0
            value.contains("filemoon") -> 1
            value.contains("streamwish") || value.contains("wishfast") -> 2
            value.contains("dood") -> 3
            value.contains("streamtape") -> 4
            value.contains("vidhide") -> 5
            value.contains("vidguard") -> 6
            value.contains("voe") -> 7
            value.contains("mixdrop") -> 8
            value.contains("mp4upload") -> 9
            value.contains("ok.ru") -> 10
            value.contains("dailymotion") -> 11
            value.contains("rumble") -> 12
            else -> 50
        }
    }

    private fun isDirectVideo(url: String): Boolean = isHlsLike(url) || url.contains(".mp4", true) || url.contains(".webm", true) || url.contains(".mkv", true)
    private fun isHlsLike(url: String): Boolean = url.contains(".m3u8", true) ||
        url.contains("application/x-mpegurl", true) ||
        url.contains("application/vnd.apple.mpegurl", true) ||
        isRumbleTarHls(url)

    private fun isRumbleTarHls(url: String): Boolean {
        val value = url.lowercase()
        return value.contains(".tar?") && (
            value.contains("r_file=chunklist.m3u8") ||
                value.contains("r_type=application%2fvnd.apple.mpegurl") ||
                value.contains("r_type=application/vnd.apple.mpegurl")
            )
    }

    private fun isLikelyEmbed(url: String): Boolean {
        val value = url.lowercase()
        return value.startsWith("http") && (
            value.contains("embed") || value.contains("player") || value.contains("stream") || value.contains("watch") || value.contains("video") ||
                value.contains("filemoon") || value.contains("streamwish") || value.contains("wishfast") || value.contains("dood") ||
                value.contains("streamtape") || value.contains("vidhide") || value.contains("vidguard") || value.contains("voe") ||
                value.contains("mixdrop") || value.contains("mp4upload") || value.contains("ok.ru") || value.contains("dailymotion") || value.contains("rumble")
            )
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() || value.startsWith("javascript") || value.startsWith("mailto:") || value.startsWith("#") ||
            value.contains("facebook.com") || value.contains("twitter.com") || value.contains("telegram") || value.contains("whatsapp") ||
            value.contains("youtube.com") || value.contains("youtu.be") || value.contains("trailer") || value.contains("googletagmanager") ||
            value.contains("cloudflareinsights") || value.contains("recaptcha") || value.contains("/login") || value.contains("/register") ||
            value.contains("/privacy") || value.contains("/contact")
    }

    private fun isBadMediaUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") || value.contains("googlesyndication") || value.contains("adservice") ||
            value.contains("adsterra") || value.contains("popads") || value.contains("/ads/") || value.contains("vast") ||
            value.contains("preroll") || value.contains("banner") || value.contains("tracking") || value.contains("analytics")
    }

    private fun isBlockedCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.trim('/').lowercase() }.getOrDefault(url.lowercase())
        return path.isBlank() || path == "series" || path == "browse" || path == "anime" || path == "donghua" ||
            path.startsWith("genre") || path.startsWith("tag") || path.startsWith("search") || path.startsWith("login") ||
            path.startsWith("register") || path.startsWith("privacy") || path.startsWith("contact") || path.startsWith("api")
    }

    private fun getTypeFromText(text: String): TvType = when {
        text.contains("movie", true) || text.contains("film", true) -> TvType.AnimeMovie
        text.contains("ova", true) || text.contains("special", true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun qualityFromUrl(url: String): Int = when {
        url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
        url.contains("1080", true) || url.contains(".haa.", true) -> Qualities.P1080.value
        url.contains("720", true) || url.contains(".gaa.", true) || url.contains(".aaa.", true) -> Qualities.P720.value
        url.contains("480", true) || url.contains(".caa.", true) -> Qualities.P480.value
        url.contains("360", true) || url.contains(".baa.", true) -> Qualities.P360.value
        else -> getQualityFromName(url)
    }

    private fun normalizeSubtitleLabel(label: String): String = when {
        label.contains("indonesia", true) || label.equals("id", true) || label.contains("bahasa", true) -> "Indonesian"
        label.isBlank() -> "Subtitle"
        else -> label
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? = value?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim().substringBefore(" ") }?.lastOrNull { it.isNotBlank() }
        return fromSrcSet(attr("data-srcset")) ?: fromSrcSet(attr("srcset")) ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() } ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() } ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() } ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun isBadTitle(title: String): Boolean {
        val value = title.lowercase().trim()
        return value.isBlank() || value == "home" || value == "beranda" || value == "login" || value == "register" ||
            value == "search" || value == "genre" || value == "watch" || value == "episode" || value == "episodes" ||
            value == "reynime" || value == "reynime - nonton donghua sub indo" ||
            value == "donghua terbaru" || value.contains("join telegram") || value.contains("tentang reynime") ||
            value.contains("aktifkan javascript") || value.contains("nonton donghua sub indo gratis")
    }

    private fun String.cleanEscaped(): String = this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim()

    private fun String.cleanTitle(): String = this
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace(Regex("""\s+[-|]\s+Reynime\s*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.slugify(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

@CloudstreamPlugin
class ReynimeProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ReynimeProvider())
    }
}
