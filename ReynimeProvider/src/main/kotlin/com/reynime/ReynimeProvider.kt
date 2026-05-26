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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    // Kategori dibuat dari struktur sumber yang terlihat langsung di halaman Reynime:
    // Featured, Update Terbaru, Ongoing Series, Completed Series, dan filter All/Anime/Donghua.
    override val mainPage = mainPageOf(
        "home:featured" to "Featured",
        "browse?sort=updated" to "Update Terbaru",
        "browse" to "Daftar Donghua",
        "browse?status=Ongoing" to "Ongoing Series",
        "browse?status=Completed" to "Completed Series",
        "browse?type=Anime" to "Anime",
        "browse?type=Donghua" to "Donghua"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to mainUrl
    )

    private data class SeedSeries(
        val id: Int,
        val title: String,
        val slug: String,
        val status: String = "Ongoing",
        val mediaKind: String = "Donghua",
        val type: TvType = TvType.Anime,
        val genres: Set<String> = emptySet(),
        val latestEpisode: Int = 1,
        val poster: String,
        val score: String? = null,
        val description: String? = null,
        val featured: Boolean = false
    )

    // Source-backed fallback dari daftar yang terlihat langsung di halaman Reynime.
    // Ini dipakai hanya ketika halaman/API sumber kosong karena SPA/client-side render atau request diblokir.
    private val seedSeries = listOf(
        SeedSeries(1, "Battle Through The Heavens Season 5", "battle-through-the-heavens-season-5", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "martial arts", "xuanhuan", "donghua"), 200, "https://cdn.myanimelist.net/images/anime/1457/152289l.jpg", "9.20", "Season kelima dari Doupo Cangqiong.", true),
        SeedSeries(11, "Renegade Immortal", "renegade-immortal", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "martial arts", "xianxia", "donghua"), 142, "https://cdn.myanimelist.net/images/anime/1289/149708l.jpg", "7.80", "Wang Lin berjalan di jalan menuju menjadi abadi nyata.", true),
        SeedSeries(29, "Tales of Herding Gods", "tales-of-herding-gods", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "martial arts", "xianxia", "donghua"), 84, "https://cdn.myanimelist.net/images/anime/1324/150012l.jpg", "8.80", "Qin Mu tumbuh di desa para lansia penyandang cacat dan menghadapi bahaya Daxu.", true),
        SeedSeries(4, "Perfect World", "perfect-world", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "martial arts", "xuanhuan", "donghua"), 200, "https://cdn.myanimelist.net/images/anime/1809/153679l.jpg", "8.00", "Shi Hao, jenius yang diberkati langit, menempuh perjalanan untuk mengguncang dunia.", true),
        SeedSeries(3, "Soul Land 2: The Peerless Tang Clan", "soul-land-2-the-peerless-tang-clan", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "martial arts", "donghua"), 100, "https://cdn.myanimelist.net/images/anime/1985/150594l.jpg", "8.40", "Generasi baru Shrek berusaha membangun kembali Tang Clan.", true),

        SeedSeries(26, "Martial Master", "martial-master", "Ongoing", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "donghua"), 660, "https://cdn.myanimelist.net/images/anime/1738/107609l.jpg", "7.80"),
        SeedSeries(84, "Sword and Fairy 3", "sword-and-fairy-3", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "donghua"), 24, "https://cdn.myanimelist.net/images/anime/1567/154040l.jpg"),
        SeedSeries(24, "Peerless Battle Spirit", "peerless-battle-spirit", "Ongoing", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "donghua"), 179, "https://cdn.myanimelist.net/images/anime/1290/150120l.jpg", "7.80"),
        SeedSeries(12, "Swallowed Star 4th Season", "swallowed-star-4th-season", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "sci-fi", "donghua"), 225, "https://cdn.myanimelist.net/images/anime/1335/138907l.jpg", "7.80"),
        SeedSeries(136, "Walking the Way All Alone", "walking-the-way-all-alone", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "donghua"), 9, "https://myanimelist.net/images/anime/1648/156622l.jpg"),
        SeedSeries(139, "Stellar Transformation 7th Season", "stellar-transformation-7th-season", "Completed", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "donghua"), 12, "https://myanimelist.net/images/anime/1147/156957l.jpg"),
        SeedSeries(83, "Beyond Time's Gaze", "beyond-times-gaze", "Ongoing", "Donghua", TvType.Anime, setOf("fantasy", "romance", "mystery", "supernatural", "donghua"), 23, "https://cdn.myanimelist.net/images/anime/1628/153418l.jpg"),

        SeedSeries(138, "Coiling Dragon", "coiling-dragon", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "donghua"), 6, "https://myanimelist.net/images/anime/1578/156842.jpg"),
        SeedSeries(137, "Ever Night", "ever-night", "Ongoing", "Donghua", TvType.Anime, setOf("action", "adventure", "fantasy", "donghua"), 6, "https://myanimelist.net/images/anime/1021/156293l.jpg"),
        SeedSeries(120, "Against the Gods 2nd Season", "against-the-gods-2nd-season", "Ongoing", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "donghua"), 38, "https://myanimelist.net/images/anime/1125/156004l.jpg"),
        SeedSeries(118, "In Search of Gods", "in-search-of-gods", "Ongoing", "Donghua", TvType.Anime, setOf("action", "fantasy", "donghua"), 11, "https://myanimelist.net/images/anime/1433/156264l.jpg"),
        SeedSeries(116, "Azure Legacy 3", "azure-legacy-3", "Ongoing", "Donghua", TvType.Anime, setOf("action", "fantasy", "donghua"), 78, "https://myanimelist.net/images/anime/1742/153462l.jpg"),
        SeedSeries(115, "Maou no Musume wa Yasashisugiru!!", "maou-no-musume-wa-yasashisugiru", "Ongoing", "Anime", TvType.Anime, setOf("comedy", "fantasy", "anime"), 10, "https://myanimelist.net/images/anime/1160/154083l.jpg"),
        SeedSeries(114, "Yuusha Party ni Kawaii Ko ga Ita node, Kokuhaku shitemita.", "yuusha-party-ni-kawaii-ko-ga-ita-node-kokuhaku-shitemita", "Ongoing", "Anime", TvType.Anime, setOf("comedy", "fantasy", "romance", "anime"), 9, "https://myanimelist.net/images/anime/1573/152828l.jpg"),
        SeedSeries(113, "Mayonaka Heart Tune", "mayonaka-heart-tune", "Ongoing", "Anime", TvType.Anime, setOf("comedy", "romance", "anime"), 9, "https://myanimelist.net/images/anime/1769/152823l.jpg"),
        SeedSeries(112, "Isekai no Sata wa Shachiku Shidai", "isekai-no-sata-wa-shachiku-shidai", "Ongoing", "Anime", TvType.Anime, setOf("fantasy", "isekai", "anime"), 9, "https://myanimelist.net/images/anime/1510/153806l.jpg"),

        SeedSeries(119, "Shrouding the Heavens: The Imperial Path", "shrouding-the-heavens-the-imperial-path", "Completed", "Donghua", TvType.AnimeMovie, setOf("action", "adventure", "fantasy", "martial arts", "xianxia", "donghua"), 1, "https://myanimelist.net/images/anime/1017/156548l.jpg"),
        SeedSeries(74, "Long Hun", "long-hun", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "donghua"), 8, "https://cdn.myanimelist.net/images/anime/1878/153807l.jpg", "8.50"),
        SeedSeries(71, "Wu Dong Qian Kun 6th Season", "wu-dong-qian-kun-6th-season", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "donghua"), 12, "https://cdn.myanimelist.net/images/anime/1937/151456l.jpg", "9.00"),
        SeedSeries(70, "Back as Immortal Lord", "back-as-immortal-lord", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "donghua"), 16, "https://cdn.myanimelist.net/images/anime/1109/153446l.jpg"),
        SeedSeries(68, "Eternal Sword Emperor", "eternal-sword-emperor", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "donghua"), 40, "https://cdn.myanimelist.net/images/anime/1794/151549l.jpg", "7.80"),
        SeedSeries(65, "Legend of the Misty Sword Immortal", "legend-of-the-misty-sword-immortal", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "donghua"), 40, "https://cdn.myanimelist.net/images/anime/1600/151290l.jpg", "7.50"),
        SeedSeries(64, "Throne of Ten Thousand Swords", "throne-of-ten-thousand-swords", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "martial arts", "wuxia", "donghua"), 40, "https://cdn.myanimelist.net/images/anime/1098/151287l.webp", "7.80"),
        SeedSeries(63, "Long March to the Stars", "long-march-to-the-stars", "Completed", "Donghua", TvType.Anime, setOf("action", "adventure", "sci-fi", "donghua"), 4, "https://cdn.myanimelist.net/images/anime/1273/151289l.jpg", "8.20"),
        SeedSeries(62, "Zi Chuan 2nd Season", "zi-chuan-2nd-season", "Completed", "Donghua", TvType.Anime, setOf("action", "fantasy", "donghua"), 43, "https://cdn.myanimelist.net/images/anime/1186/151163l.jpg", "8.20")
    )

    private fun seedUrl(seed: SeedSeries): String = "$mainUrl/series/${seed.id}"

    private fun SeedSeries.toSeedSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, seedUrl(this), type) {
            posterUrl = this@toSeedSearchResponse.poster
            score = this@toSeedSearchResponse.score?.toDoubleOrNull()?.let { com.lagradost.cloudstream3.Score.from10(it) }
            addSub(this@toSeedSearchResponse.latestEpisode)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val fetched = fetchCatalog(request.data, page)
            .distinctBy { it.url }
            .filter { it.name.isNotBlank() && !isBadTitle(it.name) }
        val items = fetched.ifEmpty { fallbackItemsFor(request.data) }
            .distinctBy { it.url }
            .filter { it.name.isNotBlank() && !isBadTitle(it.name) }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = fetched.isNotEmpty() && page < 10
        )
    }

    private suspend fun fetchCatalog(data: String, page: Int): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        buildSourceCandidates(data, page).forEach { url ->
            val response = runCatching {
                app.get(url, headers = headers, referer = mainUrl, timeout = 12L)
            }.getOrNull() ?: return@forEach

            val raw = response.text.cleanEscaped()
            parseApiCards(raw, url).forEach { results[it.url] = it }
            parseHtmlCards(response.document).forEach { results[it.url] = it }
            parseHtmlCards(Jsoup.parse(raw)).forEach { results[it.url] = it }
            parseRawSeriesLinks(raw).forEach { results[it.url] = it }
        }

        return results.values.toList()
    }

    private fun buildSourceCandidates(data: String, page: Int): List<String> {
        val clean = data.trim('/').trim()
        if (clean.startsWith("home:")) return listOf(mainUrl)
        val query = clean.substringAfter("?", "")
        val apiQuery = when {
            query.isNotBlank() -> query
            clean == "browse" -> ""
            else -> clean
        }

        val apiSort = when {
            clean.contains("sort=updated", true) -> "sort=updated"
            clean.contains("sort=popular", true) -> "sort=popular"
            clean.contains("sort=latest", true) -> "sort=latest"
            clean.contains("status=", true) -> query
            clean.contains("type=", true) -> query
            clean.contains("genre=", true) -> query
            else -> apiQuery
        }

        val candidates = linkedSetOf<String>()
        fun addWithPage(base: String) {
            candidates.add(if (base.contains("?")) "$base&page=$page" else "$base?page=$page")
        }

        if (clean.contains("sort=updated", true)) {
            addWithPage("$mainUrl/api/episodes?sort=latest")
            addWithPage("$mainUrl/api/updates")
            addWithPage("$mainUrl/api/watch")
            addWithPage("$mainUrl/$clean")
        }

        addWithPage("$mainUrl/api/series${if (apiSort.isNotBlank()) "?$apiSort" else ""}")
        addWithPage("$mainUrl/api/browse${if (apiSort.isNotBlank()) "?$apiSort" else ""}")
        addWithPage("$mainUrl/browse${if (query.isNotBlank()) "?$query" else ""}")
        addWithPage("$mainUrl/$clean")
        addWithPage("$mainUrl/browse")
        addWithPage("$mainUrl/api/series")
        return candidates.toList()
    }

    private fun fallbackItemsFor(data: String): List<SearchResponse> {
        val clean = data.lowercase()
        val queryValue = runCatching {
            URLDecoder.decode(clean.substringAfter("=", ""), "UTF-8")
        }.getOrDefault(clean.substringAfter("=", "")).lowercase()

        val filtered = when {
            clean.contains("home:featured") -> seedSeries.filter { it.featured }
            clean.contains("sort=updated") -> seedSeries.filter { it.id in setOf(26, 84, 24, 12, 136, 11, 29, 139, 83, 1) }
            clean.contains("status=ongoing") -> seedSeries.filter { it.status.equals("Ongoing", true) }
            clean.contains("status=completed") -> seedSeries.filter { it.status.equals("Completed", true) }
            clean.contains("type=anime") -> seedSeries.filter { it.mediaKind.equals("Anime", true) }
            clean.contains("type=donghua") || clean == "browse" -> seedSeries.filter { it.mediaKind.equals("Donghua", true) }
            clean.contains("type=movie") -> seedSeries.filter { it.type == TvType.AnimeMovie }
            clean.contains("type=ova") -> seedSeries.filter { it.type == TvType.OVA }
            clean.contains("genre=") -> seedSeries.filter { queryValue in it.genres.map { genre -> genre.lowercase() } }
            clean.contains("sort=popular") -> seedSeries.filter { it.featured }.ifEmpty { seedSeries.take(10) }
            else -> seedSeries
        }
        return (if (filtered.isNotEmpty()) filtered else seedSeries.take(10)).map { it.toSeedSearchResponse() }
    }

    private fun parseApiCards(text: String, baseUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()

        Regex("""\{[^{}]*(?:title|name|slug|poster|cover|image|thumbnail|watch_url|series_id|episode)[^{}]*\}""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val block = match.value
                val title = listOfNotNull(
                    extractJsonString(block, "title"),
                    extractJsonString(block, "name"),
                    extractJsonString(block, "seriesTitle"),
                    extractJsonString(block, "anime_title")
                ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }?.cleanTitle() ?: return@forEach

                val id = extractJsonValue(block, "id")
                    ?: extractJsonValue(block, "seriesId")
                    ?: extractJsonValue(block, "series_id")
                val slug = extractJsonString(block, "slug")
                    ?: title.slugify()
                val rawUrl = listOfNotNull(
                    extractJsonString(block, "url"),
                    extractJsonString(block, "href"),
                    extractJsonString(block, "link"),
                    extractJsonString(block, "watch_url"),
                    extractJsonString(block, "series_url")
                ).firstOrNull { it.isNotBlank() }

                val href = when {
                    rawUrl != null -> normalizeUrl(rawUrl, baseUrl)
                    id != null -> "$mainUrl/series/$id/$slug"
                    slug.contains("/series/", true) -> normalizeUrl(slug, baseUrl)
                    else -> "$mainUrl/series/$slug"
                }

                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEach

                val poster = listOfNotNull(
                    extractJsonString(block, "poster"),
                    extractJsonString(block, "cover"),
                    extractJsonString(block, "image"),
                    extractJsonString(block, "thumbnail"),
                    extractJsonString(block, "thumb")
                ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, baseUrl) }

                results[href] = newAnimeSearchResponse(title, href, getTypeFromText("$title $href")) {
                    posterUrl = poster
                }
            }

        return results.values.toList()
    }

    private fun parseHtmlCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(
            "article:has(a):has(img), .card:has(a):has(img), .series-card:has(a), .anime-card:has(a), " +
                ".grid a[href*='/series/'], a[href*='/series/']:has(img), a[href*='/watch/']:has(img), " +
                "a[href*='/series/']"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }
        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) this else selectFirst("a[href*='/series/'], a[href*='/watch/'], a[href]") ?: return null
        val rawHref = anchor.attr("href").trim()
        val href = fixUrlNull(rawHref)?.let { normalizeUrl(it, mainUrl) } ?: return null
        if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = image?.getImageAttr()?.let { normalizeUrl(it, href) }

        val title = listOf(
            selectFirst("h1, h2, h3, .title, .name, .font-semibold, .text-lg, .text-xl")?.text(),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }?.cleanTitle() ?: return null

        return newAnimeSearchResponse(title, href, getTypeFromText("$title $href")) {
            posterUrl = poster
        }
    }

    private fun parseRawSeriesLinks(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()
        Regex("""(?:href|url|to)=['\"]([^'\"]*/(?:series|watch)/[^'\"]+)['\"]|['\"]([^'\"]*/(?:series|watch)/[^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .forEach { match ->
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
                val href = normalizeUrl(raw, mainUrl)
                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEach
                val nearby = clean.substring((match.range.first - 800).coerceAtLeast(0), (match.range.last + 1000).coerceAtMost(clean.length))
                val title = listOfNotNull(
                    extractJsonString(nearby, "title"),
                    extractJsonString(nearby, "name"),
                    Regex("""(?:alt|title)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE).find(nearby)?.groupValues?.getOrNull(1),
                    href.substringAfterLast("/").replace("-", " ")
                ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }?.cleanTitle() ?: return@forEach
                val poster = findImageNear(nearby)?.let { normalizeUrl(it, href) }
                results[href] = newAnimeSearchResponse(title, href, getTypeFromText("$title $href")) { posterUrl = poster }
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
            "$mainUrl/api/search?q=$encoded",
            "$mainUrl/api/series?search=$encoded",
            "$mainUrl/api/series?q=$encoded",
            "$mainUrl/browse?search=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded"
        ).forEach { url ->
            val response = runCatching { app.get(url, headers = headers, referer = mainUrl, timeout = 12L) }.getOrNull() ?: return@forEach
            parseApiCards(response.text.cleanEscaped(), url).forEach { results[it.url] = it }
            parseHtmlCards(response.document).forEach { results[it.url] = it }
            parseRawSeriesLinks(response.text.cleanEscaped()).forEach { results[it.url] = it }
        }
        if (results.isNotEmpty()) return results.values.toList()
        return seedSeries.filter { it.title.contains(keyword, true) || it.slug.contains(keyword.slugify(), true) }
            .map { it.toSeedSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val pageUrl = normalizeUrl(url, mainUrl).substringBefore("#")
        val response = runCatching { app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 15L) }.getOrNull()
        val seed = findSeedFromUrl(pageUrl)
        if (response == null) return fallbackLoad(pageUrl, seed)

        val document = response.document
        val html = response.text.cleanEscaped()
        val title = listOf(
            seed?.title,
            extractJsonString(html, "title"),
            extractJsonString(html, "name"),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1, .text-2xl, .text-3xl, .font-bold, .font-semibold")?.text(),
            pageUrl.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }?.cleanTitle() ?: name

        val poster = listOf(
            extractJsonString(html, "poster"),
            extractJsonString(html, "cover"),
            extractJsonString(html, "image"),
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[alt*='$title'], img.h-full, img.w-full, .poster img, .cover img, img")?.getImageAttr(),
            seed?.poster
        ).firstOrNull { !it.isNullOrBlank() }?.let { normalizeUrl(it, pageUrl) }
            ?: seed?.poster
            ?: "https://cdn.myanimelist.net/images/anime/1457/152289l.jpg"

        val description = listOf(
            seed?.description,
            extractJsonString(html, "description"),
            extractJsonString(html, "synopsis"),
            extractJsonString(html, "overview"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".synopsis, .description, .desc, article p, main p")?.text()
        ).firstOrNull { !it.isNullOrBlank() && it.length > 20 && !isBadTitle(it) }?.cleanTitle()

        val tags = document.select("a[href*='genre'], a[href*='tag'], .genre a, .genres a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()
            .ifEmpty { seed?.genres?.map { it.cleanTitle() }.orEmpty() }

        val episodes = parseEpisodes(document, html, pageUrl, poster)
            .ifEmpty { fallbackEpisodes(pageUrl, seed, title, poster, description) }

        return newAnimeLoadResponse(title, pageUrl, seed?.type ?: getTypeFromText("$title ${tags.joinToString(" ")} $pageUrl")) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description ?: "Streaming Donghua subtitle Indonesia di Reynime."
            this.tags = tags
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
            recommendations = parseHtmlCards(document).filter { it.url != pageUrl }.distinctBy { it.url }
        }
    }

    private suspend fun fallbackLoad(url: String, seed: SeedSeries? = findSeedFromUrl(url)): LoadResponse {
        val title = seed?.title ?: url.substringAfterLast("/").replace("-", " ").cleanTitle().ifBlank { name }
        val poster = seed?.poster ?: "https://cdn.myanimelist.net/images/anime/1457/152289l.jpg"
        val episodes = fallbackEpisodes(seed?.let { seedUrl(it) } ?: url, seed, title, poster, "Streaming Donghua subtitle Indonesia di Reynime.")
        return newAnimeLoadResponse(title, seed?.let { seedUrl(it) } ?: url, seed?.type ?: TvType.Anime) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = "Streaming Donghua subtitle Indonesia di Reynime."
            tags = seed?.genres?.map { it.cleanTitle() } ?: listOf("Donghua")
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
        }
    }

    private fun fallbackEpisodes(baseUrl: String, seed: SeedSeries?, title: String, poster: String?, description: String?): List<Episode> {
        val latest = seed?.latestEpisode?.coerceAtLeast(1) ?: 1
        return (1..latest).map { ep ->
            newEpisode("${baseUrl.substringBefore("#")}#episode-$ep") {
                name = "Episode $ep"
                episode = ep
                posterUrl = poster
                this.description = description
            }
        }
    }

    private fun parseEpisodes(document: Document, html: String, pageUrl: String, poster: String?): List<Episode> {
        val results = linkedMapOf<String, Episode>()
        document.select("a[href*='/watch/'], a[href*='/episode/'], a[href*='/stream/'], button[data-url], [data-url*='/watch/']")
            .forEachIndexed { index, element ->
                val raw = element.attr("href").ifBlank { element.attr("data-url") }.ifBlank { element.attr("data-href") }.trim()
                val href = normalizeUrl(raw, pageUrl)
                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEachIndexed
                val label = element.text().ifBlank { element.attr("title") }.cleanTitle()
                val ep = extractEpisodeNumber(label, href) ?: index + 1
                results[href] = newEpisode(href) {
                    name = label.ifBlank { "Episode $ep" }
                    episode = ep
                    posterUrl = poster
                }
            }

        Regex("""[\"'](?:url|href|watch_url|episode_url)[\"']\s*:\s*[\"']([^\"']*/(?:watch|episode|stream)/[^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEachIndexed { index, match ->
                val href = normalizeUrl(match.groupValues[1], pageUrl)
                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEachIndexed
                val nearby = html.substring((match.range.first - 600).coerceAtLeast(0), (match.range.last + 800).coerceAtMost(html.length))
                val label = listOfNotNull(extractJsonString(nearby, "title"), extractJsonString(nearby, "name"), extractJsonString(nearby, "episode"))
                    .firstOrNull { it.isNotBlank() }?.cleanTitle().orEmpty()
                val ep = extractEpisodeNumber(label, href) ?: index + 1
                results[href] = newEpisode(href) {
                    name = label.ifBlank { "Episode $ep" }
                    episode = ep
                    posterUrl = poster
                }
            }

        Regex("""\bEP\s*(\d{1,4})\b|\bEpisode\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
            .findAll(document.text())
            .mapNotNull { it.groupValues.drop(1).firstOrNull { number -> number.isNotBlank() }?.toIntOrNull() }
            .distinct()
            .sorted()
            .forEach { ep ->
                val epUrl = "$pageUrl#episode-$ep"
                if (!results.containsKey(epUrl)) {
                    results[epUrl] = newEpisode(epUrl) {
                        name = "Episode $ep"
                        episode = ep
                        posterUrl = poster
                    }
                }
            }
        return results.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val requestedUrl = normalizeUrl(data, mainUrl)
        val pageUrl = requestedUrl.substringBefore("#")
        val episodeNumber = Regex("""#episode-(\d+)""").find(requestedUrl)?.groupValues?.getOrNull(1)

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        buildWatchCandidates(pageUrl, episodeNumber).forEach { url ->
            val response = runCatching { app.get(url, headers = headers, referer = mainUrl, timeout = 12L) }.getOrNull() ?: return@forEach
            val raw = response.text.cleanEscaped()
            collectCandidatesFromDocument(response.document, url, directLinks, embedLinks)
            extractPlayableUrls(raw).forEach { addCandidate(it, url, directLinks, embedLinks) }
            extractSubtitles(raw, url, subtitleCallback)
            decodeBase64Payloads(raw).forEach { decoded ->
                extractPlayableUrls(decoded).forEach { addCandidate(it, url, directLinks, embedLinks) }
            }
            runCatching { if (!getPacked(raw).isNullOrEmpty()) getAndUnpack(raw) else null }
                .getOrNull()
                ?.cleanEscaped()
                ?.let { unpacked -> extractPlayableUrls(unpacked).forEach { addCandidate(it, url, directLinks, embedLinks) } }
        }

        var found = false
        prioritizeEmbeds(embedLinks).take(12).forEach { embed ->
            if (runCatching { loadExtractor(embed, pageUrl, subtitleCallback, callback) }.getOrDefault(false)) {
                found = true
            }
            resolveNestedLinks(embed, pageUrl).forEach { nested ->
                if (emitDirectLink(nested, embed, callback)) found = true
            }
        }

        directLinks.forEach { link ->
            if (emitDirectLink(link, pageUrl, callback)) found = true
        }

        return found
    }

    private fun buildWatchCandidates(pageUrl: String, episodeNumber: String?): List<String> {
        val ids = linkedSetOf<String>()
        runCatching { URI(pageUrl).path.trim('/').split('/').filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
            .forEach { part -> if (part.length in 1..80) ids.add(part) }
        episodeNumber?.let { ids.add(it) }

        val candidates = linkedSetOf(pageUrl)
        ids.take(8).forEach { id ->
            val enc = URLEncoder.encode(id, "UTF-8")
            candidates.add("$mainUrl/api/watch/$enc")
            candidates.add("$mainUrl/api/episode/$enc")
            candidates.add("$mainUrl/api/episodes/$enc")
            candidates.add("$mainUrl/api/stream/$enc")
            candidates.add("$mainUrl/api/video/$enc")
            candidates.add("$mainUrl/watch/$enc")
        }
        return candidates.toList()
    }

    private fun collectCandidatesFromDocument(document: Document, baseUrl: String, directLinks: MutableSet<String>, embedLinks: MutableSet<String>) {
        document.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player], iframe[src], iframe[data-src], video[src], video source[src], source[src], embed[src], object[data], a[href], [data-url], [data-src], [data-video], [data-file], [data-embed], [data-iframe]")
            .forEach { element ->
                val raw = element.attr("content")
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
        val response = runCatching { app.get(url, headers = headers, referer = referer, timeout = 12L) }.getOrNull() ?: return emptyList()
        val results = linkedSetOf<String>()
        val text = response.text.cleanEscaped()
        collectCandidatesFromDocument(response.document, url, results, results)
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
        when {
            isDirectVideo(fixed) -> directLinks.add(fixed)
            fixed.startsWith("http", true) && isLikelyEmbed(fixed) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(link: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (isBadMediaUrl(link) || shouldSkipUrl(link)) return false
        if (isHlsLike(link)) {
            val generated = runCatching {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Origin" to mainUrl)
                ).forEach(callback)
                true
            }.getOrDefault(false)
            if (generated) return true
        }
        callback(
            newExtractorLink(name, name, link, if (isHlsLike(link)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = qualityFromUrl(link).takeIf { it != Qualities.Unknown.value } ?: Qualities.P720.value
            }
        )
        return true
    }

    private suspend fun extractSubtitles(text: String, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val clean = text.cleanEscaped()
        Regex("""[\"'](?:label|lang|language)[\"']\s*:\s*[\"']([^\"']+)[\"'][^{}]{0,300}[\"'](?:file|url|src|path)[\"']\s*:\s*[\"']([^\"']+\.(?:vtt|srt|ass)[^\"']*)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(clean).forEach { match ->
                val url = normalizeUrl(match.groupValues[2], baseUrl)
                if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile(normalizeSubtitleLabel(match.groupValues[1]), url))
            }
        Regex("""https?://[^\"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(clean).forEach { match ->
                val url = match.value.cleanEscaped()
                if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile("Subtitle", url))
            }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()
        listOf(
            Regex("""https?://[^\"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE),
            Regex("""//[^\"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^\"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(clean).map { it.value }.forEach { raw ->
                val fixed = (if (raw.startsWith("//")) "https:$raw" else raw).replace(".txt", ".m3u8").cleanEscaped()
                if (!isBadMediaUrl(fixed) && !shouldSkipUrl(fixed)) urls.add(fixed)
            }
        }
        Regex("""https?%3A%2F%2F[^\"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.mkv|\.txt|embed|player|stream)[^\"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }
        Regex("""(?:file|src|source|url|video|videoUrl|video_url|stream|streamUrl|stream_url|hls|hlsUrl|hls_url|embed|embedUrl|embed_url)\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .findAll(clean).mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { isDirectVideo(it) || isLikelyEmbed(it) }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }
        Regex("""https?://[^\"'\\\s<>]+?(?:embed|player|stream|watch|video|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|ok\.ru|dailymotion|rumble)[^\"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(clean).map { it.value.cleanEscaped() }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }
        return urls.toList()
    }

    private fun decodeBase64Payloads(text: String): List<String> {
        val results = linkedSetOf<String>()
        Regex("""[\"']([A-Za-z0-9+/=]{40,})[\"']""")
            .findAll(text).mapNotNull { it.groupValues.getOrNull(1) }.take(30).forEach { token ->
                runCatching { String(Base64.getDecoder().decode(token), Charsets.UTF_8) }.getOrNull()
                    ?.takeIf { it.contains("http", true) || it.contains("iframe", true) || it.contains("m3u8", true) }
                    ?.let { results.add(it.cleanEscaped()) }
            }
        return results.toList()
    }

    private fun findSeedFromUrl(url: String): SeedSeries? {
        val value = url.lowercase()
        return seedSeries.firstOrNull { value.contains("/series/${it.id}") || value.contains(it.slug.lowercase()) }
    }

    private fun findImageNear(text: String): String? {
        val clean = text.cleanEscaped()
        Regex("""(?:src|data-src|poster|image|cover|thumbnail|thumb)=['\"]([^'\"]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^'\"]*)?)['\"]""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""[\"'](?:poster|cover|image|thumbnail|thumb|src)[\"']\s*:\s*[\"']([^\"']+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^\"']*)?)[\"']""", RegexOption.IGNORE_CASE)
            .find(clean)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun extractJsonString(text: String, key: String): String? {
        return Regex("""[\"']${Regex.escape(key)}[\"']\s*:\s*[\"']((?:\\.|[^\"'\\])*)[\"']""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.replace("\\u0026", "&")?.replace("\\\"", "\"")?.replace("\\n", " ")?.trim()
    }

    private fun extractJsonValue(text: String, key: String): String? {
        return Regex("""[\"']${Regex.escape(key)}[\"']\s*:\s*[\"']?([A-Za-z0-9_-]+)[\"']?""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> Regex("""^https?://[^/]+""").find(baseUrl)?.value.orEmpty().ifBlank { mainUrl } + clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> = links.filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }.distinct().sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })

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
    private fun isHlsLike(url: String): Boolean = url.contains(".m3u8", true) || url.contains("application/x-mpegurl", true)

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
        return path.isBlank() || path == "series" || path == "browse" || path.startsWith("genre") || path.startsWith("tag") ||
            path.startsWith("search") || path.startsWith("login") || path.startsWith("register") || path.startsWith("privacy") ||
            path.startsWith("contact") || path.startsWith("api")
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
        url.contains("1080", true) -> Qualities.P1080.value
        url.contains("720", true) -> Qualities.P720.value
        url.contains("480", true) -> Qualities.P480.value
        url.contains("360", true) -> Qualities.P360.value
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
        return value.isBlank() || value == "home" || value == "login" || value == "register" || value == "search" ||
            value == "genre" || value == "watch" || value == "episode" || value == "episodes" || value.contains("tentang reynime") ||
            value.contains("aktifkan javascript") || value.contains("nonton donghua sub indo gratis")
    }

    private fun String.cleanEscaped(): String = this.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim()
    private fun String.cleanTitle(): String = this.replace("\\\"", "\"").replace("\\/", "/").replace(Regex("""\s+[-|]\s+Reynime\s*$""", RegexOption.IGNORE_CASE), "").replace(Regex("""\s+"""), " ").trim()
    private fun String.slugify(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
