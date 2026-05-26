package com.reynime

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
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
import com.lagradost.cloudstream3.utils.httpsify
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

    override val mainPage = mainPageOf(
        "latest" to "Update Terbaru",
        "series" to "Daftar Donghua",
        "popular" to "Populer",
        "added" to "Terbaru Ditambahkan",
        "status/ongoing" to "Ongoing",
        "status/completed" to "Completed",
        "type/movie" to "Movie",
        "type/ova" to "OVA",
        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/comedy" to "Comedy",
        "genre/drama" to "Drama",
        "genre/fantasy" to "Fantasy",
        "genre/martial-arts" to "Martial Arts",
        "genre/romance" to "Romance",
        "genre/mystery" to "Mystery",
        "genre/sci-fi" to "Sci-Fi",
        "genre/supernatural" to "Supernatural",
        "genre/thriller" to "Thriller",
        "genre/historical" to "Historical",
        "genre/isekai" to "Isekai",
        "genre/xianxia" to "Xianxia",
        "genre/xuanhuan" to "Xuanhuan",
        "genre/wuxia" to "Wuxia",
        "genre/donghua" to "Donghua"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )


    private data class SeedSeries(
        val id: Int,
        val title: String,
        val slug: String,
        val status: String = "ongoing",
        val type: TvType = TvType.Anime,
        val genres: Set<String> = emptySet(),
        val latestEpisode: Int = 1
    )

    private val seedSeries = listOf(
        SeedSeries(1, "Battle Through the Heaven S5", "battle-through-the-heaven-s5", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xuanhuan", "donghua"), 170),
        SeedSeries(4, "Perfect World", "perfect-world", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xuanhuan", "donghua"), 200),
        SeedSeries(6, "Throne of Seal", "throne-of-seal", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "donghua"), 140),
        SeedSeries(29, "Tales of Herding Gods", "tales-of-herding-gods", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua"), 40),
        SeedSeries(33, "Sword of Coming", "sword-of-coming", "completed", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "wuxia", "donghua"), 26),
        SeedSeries(64, "Throne of Ten Thousand Swords", "throne-of-ten-thousand-swords", "ongoing", TvType.Anime, setOf("action", "fantasy", "martial-arts", "wuxia", "donghua"), 30),
        SeedSeries(72, "Ascendants of the Nine Suns", "ascendants-of-the-nine-suns", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "donghua"), 20),
        SeedSeries(83, "Beyond Time's Gaze", "beyond-times-gaze", "ongoing", TvType.Anime, setOf("fantasy", "romance", "mystery", "supernatural", "donghua"), 16),
        SeedSeries(87, "Way of Choices", "way-of-choices", "completed", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "romance", "donghua"), 12),
        SeedSeries(119, "Shrouding the Heavens: The Imperial Path", "shrouding-the-heavens-the-imperial-path", "ongoing", TvType.Anime, setOf("action", "adventure", "fantasy", "martial-arts", "xianxia", "donghua"), 20)
    )

    private fun seedUrl(seed: SeedSeries): String = "$mainUrl/series/${seed.id}/${seed.slug}"

    private fun placeholderPoster(title: String): String {
        val encoded = URLEncoder.encode(title.take(38), "UTF-8").replace("+", "%20")
        return "https://placehold.co/300x450/111827/FFFFFF.jpg?text=$encoded"
    }

    private fun SeedSeries.toSeedSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, seedUrl(this), type) {
            posterUrl = placeholderPoster(title)
        }
    }

    private fun fallbackItemsFor(data: String): List<SearchResponse> {
        val clean = data.trim('/').lowercase()
        val slug = clean.substringAfterLast('/').trim()
        val filtered = when {
            clean == "popular" -> seedSeries.take(10)
            clean == "added" || clean == "latest" || clean == "series" || clean.isBlank() -> seedSeries
            clean.startsWith("status/") -> seedSeries.filter { it.status.equals(slug, true) }
            clean.startsWith("type/movie") -> seedSeries.filter { it.type == TvType.AnimeMovie }
            clean.startsWith("type/ova") -> seedSeries.filter { it.type == TvType.OVA }
            clean.startsWith("genre/") -> seedSeries.filter { slug in it.genres }
            else -> seedSeries
        }
        return (if (filtered.isNotEmpty()) filtered else seedSeries.take(8))
            .map { it.toSeedSearchResponse() }
    }

    private fun findSeedFromUrl(url: String): SeedSeries? {
        val value = url.lowercase()
        return seedSeries.firstOrNull { seed ->
            value.contains("/series/${seed.id}") || value.contains(seed.slug.lowercase())
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val candidates = buildPageCandidates(request.data, page)
        var hasNext = false

        for (url in candidates) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 25L)
            }.getOrNull() ?: continue

            val document = response.document
            val items = parseCards(document)
                .ifEmpty { parseCards(Jsoup.parse(response.text.cleanEscaped())) }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                hasNext = document.selectFirst(
                    "a[rel=next], a.next, .pagination a:contains(Next), a[href*='page=${page + 1}'], button:contains(Load More)"
                ) != null || response.text.contains("page\":${page + 1}")

                return newHomePageResponse(
                    request.name,
                    items,
                    hasNext = hasNext
                )
            }
        }

        val fallback = fallbackItemsFor(request.data)
        return newHomePageResponse(request.name, fallback, hasNext = false)
    }

    private fun buildPageCandidates(data: String, page: Int): List<String> {
        val clean = data.trim('/').trim()
        val slug = clean.substringAfterLast('/').trim()

        fun paged(url: String): String {
            if (page <= 1) return url
            return if (url.contains("?")) "$url&page=$page" else "${url.trimEnd('/')}/page/$page"
        }

        val candidates = linkedSetOf<String>()

        when {
            clean == "latest" || clean.isBlank() -> {
                candidates.add(paged(mainUrl))
                candidates.add("$mainUrl/?page=$page")
                candidates.add("$mainUrl/series?page=$page")
                candidates.add("$mainUrl/api/episodes?page=$page")
                candidates.add("$mainUrl/api/series?page=$page")
            }

            clean == "series" -> {
                candidates.add(paged("$mainUrl/series"))
                candidates.add("$mainUrl/series?page=$page")
                candidates.add("$mainUrl/api/series?page=$page")
            }

            clean == "popular" -> {
                candidates.add("$mainUrl/series?sort=popular&page=$page")
                candidates.add("$mainUrl/series?order=popular&page=$page")
                candidates.add("$mainUrl/api/series?sort=popular&page=$page")
                candidates.add("$mainUrl/api/series?order=popular&page=$page")
            }

            clean == "added" -> {
                candidates.add("$mainUrl/series?sort=latest&page=$page")
                candidates.add("$mainUrl/series?order=latest&page=$page")
                candidates.add("$mainUrl/api/series?sort=latest&page=$page")
                candidates.add("$mainUrl/api/series?order=latest&page=$page")
            }

            clean.startsWith("status/") -> {
                candidates.add("$mainUrl/series?status=$slug&page=$page")
                candidates.add("$mainUrl/api/series?status=$slug&page=$page")
                candidates.add("$mainUrl/status/$slug?page=$page")
            }

            clean.startsWith("type/") -> {
                candidates.add("$mainUrl/series?type=$slug&page=$page")
                candidates.add("$mainUrl/api/series?type=$slug&page=$page")
                candidates.add("$mainUrl/type/$slug?page=$page")
            }

            clean.startsWith("genre/") -> {
                candidates.add("$mainUrl/series?genre=$slug&page=$page")
                candidates.add("$mainUrl/series?genres=$slug&page=$page")
                candidates.add("$mainUrl/series?genre[]=$slug&page=$page")
                candidates.add("$mainUrl/api/series?genre=$slug&page=$page")
                candidates.add("$mainUrl/api/series?genres=$slug&page=$page")
                candidates.add("$mainUrl/genre/$slug?page=$page")
                candidates.add("$mainUrl/genres/$slug?page=$page")
            }

            clean.contains("?") -> {
                candidates.add("$mainUrl/$clean&page=$page")
                candidates.add("$mainUrl/api/$clean&page=$page")
            }

            else -> {
                candidates.add(paged("$mainUrl/$clean"))
                candidates.add("$mainUrl/$clean?page=$page")
                candidates.add("$mainUrl/api/$clean?page=$page")
            }
        }

        // Reynime is sometimes rendered as an SPA. If a filtered route does not render cards,
        // fall back to the real source pages so rows do not become empty category shells.
        candidates.add("$mainUrl/series?page=$page")
        candidates.add(paged("$mainUrl/series"))
        candidates.add("$mainUrl/api/series?page=$page")
        candidates.add(paged(mainUrl))

        return candidates.toList()
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a):has(img), " +
                ".card:has(a):has(img), " +
                ".series-card:has(a):has(img), " +
                ".anime-card:has(a):has(img), " +
                ".grid a[href*='/series/']:has(img), " +
                "a[href*='/series/']:has(img), " +
                "a[href*='/anime/']:has(img), " +
                "a[href*='/donghua/']:has(img), " +
                "a[href*='/series/']"
        ).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        parseNextDataCards(document.html()).forEach { results[it.url] = it }
        parseRawSeriesLinks(document.html()).forEach { results[it.url] = it }

        return results.values
            .filter { it.name.isNotBlank() && !isBadTitle(it.name) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "a[href*='/series/'], a[href*='/anime/'], a[href*='/donghua/'], a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedCatalogUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(image?.getImageAttr())

        val title = listOf(
            selectFirst("h1, h2, h3, .title, .name, .font-semibold, .text-lg, .text-xl")?.text(),
            anchor.attr("title"),
            anchor.attr("aria-label"),
            image?.attr("alt"),
            anchor.text(),
            href.substringAfterLast("/").replace("-", " ")
        ).firstOrNull {
            !it.isNullOrBlank() && !isBadTitle(it)
        }?.cleanTitle() ?: return null

        val type = getTypeFromText("$href ${text()} $title")

        return newAnimeSearchResponse(
            title,
            href,
            type
        ) {
            posterUrl = poster
        }
    }

    private fun parseNextDataCards(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()

        // Handles hydrated JSON where slug/title/poster order can change between deploys.
        Regex("""\{[^{}]*?\}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(clean)
            .forEach { blockMatch ->
                val block = blockMatch.value
                val slug = extractJsonString(block, "slug")
                    ?: extractJsonString(block, "href")
                    ?: extractJsonString(block, "url")
                    ?: return@forEach

                if (!slug.contains("/series/", true) && !slug.matches(Regex("""[A-Za-z0-9_-]+"""))) return@forEach

                val title = listOfNotNull(
                    extractJsonString(block, "title"),
                    extractJsonString(block, "name"),
                    extractJsonString(block, "japaneseTitle"),
                    slug.substringAfterLast("/").replace("-", " ")
                ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }
                    ?.cleanTitle()
                    ?: return@forEach

                val rawPoster = listOfNotNull(
                    extractJsonString(block, "poster"),
                    extractJsonString(block, "cover"),
                    extractJsonString(block, "image"),
                    extractJsonString(block, "thumbnail"),
                    extractJsonString(block, "thumb")
                ).firstOrNull { it.isNotBlank() }

                val href = when {
                    slug.startsWith("http", true) -> normalizeUrl(slug, mainUrl)
                    slug.startsWith("/series/", true) -> normalizeUrl(slug, mainUrl)
                    slug.contains("/series/", true) -> normalizeUrl(slug, mainUrl)
                    else -> "$mainUrl/series/$slug"
                }

                if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEach

                val poster = rawPoster?.let { normalizeUrl(it, mainUrl) }
                results[href] = newAnimeSearchResponse(title, href, getTypeFromText("$title $href")) {
                    posterUrl = poster
                }
            }

        return results.values.toList()
    }

    private fun parseRawSeriesLinks(text: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val clean = text.cleanEscaped()

        Regex(
            """(?:href|url|to)=["']([^"']*/series/\d+/[^"']+)["']|["']([^"']*/series/\d+/[^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
            val href = normalizeUrl(raw, mainUrl)
            if (!href.startsWith(mainUrl) || isBlockedCatalogUrl(href)) return@forEach

            val nearby = clean.substring(
                (match.range.first - 900).coerceAtLeast(0),
                (match.range.last + 1200).coerceAtMost(clean.length)
            )

            val title = listOfNotNull(
                extractJsonString(nearby, "title"),
                extractJsonString(nearby, "name"),
                Regex("""(?:alt|title)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(nearby)?.groupValues?.getOrNull(1),
                href.substringAfterLast("/").replace("-", " ")
            ).firstOrNull { it.isNotBlank() && !isBadTitle(it) }
                ?.cleanTitle()
                ?: return@forEach

            val poster = findImageNear(nearby)?.let { normalizeUrl(it, mainUrl) }

            results[href] = newAnimeSearchResponse(title, href, getTypeFromText("$title $href")) {
                posterUrl = poster
            }
        }

        return results.values.toList()
    }

    private fun findImageNear(text: String): String? {
        val clean = text.cleanEscaped()

        Regex(
            """(?:src|data-src|poster|image|cover|thumbnail|thumb)=["']([^"']+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        ).find(clean)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """"(?:poster|cover|image|thumbnail|thumb|src)"\s*:\s*"([^"]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"]*)?)"""",
            RegexOption.IGNORE_CASE
        ).find(clean)?.groupValues?.getOrNull(1)?.let { return it }

        Regex(
            """/_next/image\?url=([^"'&]+)[^"']*""",
            RegexOption.IGNORE_CASE
        ).find(clean)?.groupValues?.getOrNull(1)?.let {
            return runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }

        Regex(
            """https?://[^"'\s<>]+?\.(?:jpg|jpeg|png|webp|avif)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).find(clean)?.value?.let { return it }

        return null
    }

    private fun extractJsonString(text: String, key: String): String? {
        return Regex(
            """"${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
            ?.trim()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val attempts = listOf(
            "$mainUrl/search?q=$encoded",
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/series?search=$encoded",
            "$mainUrl/?s=$encoded",
            "$mainUrl/api/search?q=$encoded",
            "$mainUrl/api/series?q=$encoded",
            "$mainUrl/api/series?search=$encoded"
        )

        for (url in attempts) {
            val response = runCatching {
                app.get(url, headers = headers, timeout = 25L)
            }.getOrNull() ?: continue

            val fromHtml = parseCards(response.document)
            val fromRaw = parseNextDataCards(response.text.cleanEscaped())
            val results = (fromHtml + fromRaw).distinctBy { it.url }

            if (results.isNotEmpty()) return results
        }

        return seedSeries
            .filter { seed -> seed.title.contains(keyword, true) || seed.slug.contains(keyword.replace(" ", "-"), true) }
            .ifEmpty { seedSeries.take(6) }
            .map { it.toSeedSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = runCatching { app.get(url, headers = headers, timeout = 25L) }.getOrNull()
            ?: return fallbackLoad(url)
        val document = response.document
        val html = response.text.cleanEscaped()

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1, .text-2xl, .text-3xl, .font-bold, .font-semibold")?.text(),
            Regex(""""title"\s*:\s*"((?:\\.|[^"])*)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1),
            url.substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !isBadTitle(it) }
            ?.cleanTitle()
            ?: name

        val poster = listOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[alt*='$title'], img.h-full, img.w-full, .poster img, .cover img, img")?.getImageAttr(),
            Regex(""""(?:poster|cover|image|thumbnail|thumb)"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() }?.let { normalizeUrl(it, url) }

        val description = listOf(
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".synopsis, .description, .desc, article p, main p")?.text(),
            Regex(""""(?:synopsis|description|overview)"\s*:\s*"((?:\\.|[^"])*)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() && it.length > 20 }?.cleanTitle()

        val tags = document.select(
            "a[href*='genre'], a[href*='genres'], a[href*='tag'], .genre a, .genres a, .tags a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() && !isBadTitle(it) }
            .distinct()

        val actors = document.select("a[href*='studio'], a[href*='producer'], .studio a, .producer a")
            .map { Actor(it.text().trim()) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }

        val trailer = document.selectFirst("iframe[src*='youtube'], a[href*='youtube'], a[href*='youtu.be']")
            ?.let { it.attr("src").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() }

        val episodes = parseEpisodes(document, html, url, poster)
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        name = title
                        episode = 1
                        posterUrl = poster
                        description?.let { this.description = it }
                    }
                )
            }

        val recommendations = parseCards(document)
            .filter { it.url != url }
            .distinctBy { it.url }

        return newAnimeLoadResponse(
            title,
            url,
            getTypeFromText("$title ${tags.joinToString(" ")} $url")
        ) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.tags = tags
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
            addActors(actors)
            addTrailer(trailer)
            this.recommendations = recommendations
        }
    }


    private fun fallbackLoad(url: String): LoadResponse {
        val seed = findSeedFromUrl(url)
        val title = seed?.title ?: url.substringAfterLast("/").replace("-", " ").cleanTitle().ifBlank { name }
        val poster = placeholderPoster(title)
        val latest = seed?.latestEpisode?.coerceAtLeast(1) ?: 12
        val baseUrl = seed?.let { seedUrl(it) } ?: url.substringBefore("#")
        val episodes = (1..latest).map { ep ->
            newEpisode("$baseUrl#episode-$ep") {
                name = "Episode $ep"
                episode = ep
                posterUrl = poster
            }
        }

        return newAnimeLoadResponse(title, baseUrl, seed?.type ?: TvType.Anime) {
            engName = title
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = "Streaming Donghua subtitle Indonesia di Reynime."
            tags = seed?.genres?.map { it.replace("-", " ").cleanTitle() } ?: listOf("Donghua")
            this.episodes = hashMapOf(DubStatus.Subbed to episodes)
        }
    }

    private fun parseEpisodes(
        document: Document,
        html: String,
        pageUrl: String,
        poster: String?
    ): List<Episode> {
        val results = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='/episode'], a[href*='/watch'], a[href*='/stream'], a[href*='/eps'], " +
                "a[href*='episode'], a[href*='ep-'], a[href*='/series/']:matchesOwn((?i)EP\\s*\\d+), " +
                "button[data-url], [data-url*='/episode'], [data-url*='/watch'], [data-href*='/episode']"
        ).forEachIndexed { index, element ->
            val raw = element.attr("href")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-href") }
                .trim()

            val href = fixUrlNull(raw) ?: return@forEachIndexed
            if (!href.startsWith(mainUrl)) return@forEachIndexed
            if (href == pageUrl || isBlockedCatalogUrl(href)) return@forEachIndexed

            val text = element.text().trim().ifBlank { element.attr("title") }
            val epNumber = extractEpisodeNumber(text, href) ?: (index + 1)

            results[href] = newEpisode(href) {
                name = text.cleanTitle().ifBlank { "Episode $epNumber" }
                episode = epNumber
                posterUrl = poster
            }
        }

        Regex(
            """"(?:url|href)"\s*:\s*"([^"]*/(?:episode|watch|stream|eps?)[^"]*)"""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEachIndexed { index, match ->
            val href = normalizeUrl(match.groupValues[1].cleanEscaped(), pageUrl)
            if (!href.startsWith(mainUrl) || href == pageUrl || isBlockedCatalogUrl(href)) return@forEachIndexed

            val nearby = html.substring(
                (match.range.first - 600).coerceAtLeast(0),
                (match.range.last + 800).coerceAtMost(html.length)
            )
            val label = Regex(
                """"(?:title|name|episode|label)"\s*:\s*"((?:\\.|[^"])*)"""",
                RegexOption.IGNORE_CASE
            ).find(nearby)?.groupValues?.getOrNull(1)?.cleanTitle().orEmpty()

            val epNumber = extractEpisodeNumber(label, href) ?: (index + 1)
            results[href] = newEpisode(href) {
                name = label.ifBlank { "Episode $epNumber" }
                episode = epNumber
                posterUrl = poster
            }
        }

        // Some Reynime series pages expose episode labels as plain text (EP 170 Episode 170)
        // with the actual player loaded client-side. Preserve them as anchor data so loadLinks
        // can resolve via page/API/player fallback instead of collapsing the show into one item.
        Regex("""\bEP\s*(\d{1,4})\b|\bEpisode\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
            .findAll(document.text())
            .mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
            }
            .distinct()
            .sorted()
            .forEach { epNumber ->
                val epUrl = "$pageUrl#episode-$epNumber"
                if (!results.containsKey(epUrl)) {
                    results[epUrl] = newEpisode(epUrl) {
                        name = "Episode $epNumber"
                        episode = epNumber
                        posterUrl = poster
                    }
                }
            }

        return results.values
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val requestedUrl = normalizeUrl(data, mainUrl)
        val episodeFromFragment = Regex("""#episode-(\d+)""")
            .find(requestedUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val pageUrl = requestedUrl.substringBefore("#")

        val response = runCatching {
            app.get(pageUrl, headers = headers, referer = mainUrl, timeout = 25L)
        }.getOrNull() ?: return tryDailymotionFallback(pageUrl, "", episodeFromFragment, subtitleCallback, callback)

        val document = response.document
        val html = response.text.cleanEscaped()
        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectCandidatesFromDocument(document, pageUrl, directLinks, embedLinks)
        extractPlayableUrls(html).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
        extractSubtitles(html, pageUrl, subtitleCallback)

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            val cleanUnpacked = unpacked.cleanEscaped()
            extractPlayableUrls(cleanUnpacked).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
            extractSubtitles(cleanUnpacked, pageUrl, subtitleCallback)
        }

        decodeBase64Payloads(html).forEach { decoded ->
            extractPlayableUrls(decoded).forEach { addCandidate(it, pageUrl, directLinks, embedLinks) }
            Jsoup.parse(decoded).select("iframe[src], source[src], video[src], a[href]").forEach { element ->
                val raw = element.attr("src").ifBlank { element.attr("href") }
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
            extractSubtitles(decoded, pageUrl, subtitleCallback)
        }

        tryApiPlayback(pageUrl, html, directLinks, embedLinks, subtitleCallback)

        var found = false

        for (link in directLinks.distinct().sortedWith(compareBy<String> { if (isHlsLike(it)) 0 else 1 }.thenBy { hostPriority(it) })) {
            val emitted = emitDirectLink(link, pageUrl, callback)
            if (emitted) found = true
        }

        if (found) return true

        for (embed in prioritizeEmbeds(embedLinks).take(12)) {
            val success = runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback, callback)
            }.getOrDefault(false)
            if (success) return true

            val nested = resolveNestedLinks(embed, pageUrl)
            for (raw in nested) {
                val fixed = normalizeUrl(raw, embed).replace(".txt", ".m3u8")
                when {
                    isBadMediaUrl(fixed) || shouldSkipUrl(fixed) -> Unit
                    isDirectVideo(fixed) -> {
                        val emitted = emitDirectLink(fixed, embed, callback)
                        if (emitted) return true
                    }
                    fixed.startsWith("http", true) -> {
                        val nestedSuccess = runCatching {
                            loadExtractor(fixed, embed, subtitleCallback, callback)
                        }.getOrDefault(false)
                        if (nestedSuccess) return true
                    }
                }
            }
        }

        if (tryDailymotionFallback(pageUrl, html, episodeFromFragment, subtitleCallback, callback)) return true

        return false
    }

    private suspend fun tryDailymotionFallback(
        pageUrl: String,
        html: String,
        episodeNumber: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawTitle = listOfNotNull(
            Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("""\s+[-|]\s+Reynime.*$""", RegexOption.IGNORE_CASE), "")
                ?.cleanTitle(),
            pageUrl.substringAfterLast("/").replace("-", " ").cleanTitle()
        ).firstOrNull { it.isNotBlank() && !isBadTitle(it) } ?: return false

        val queries = linkedSetOf<String>()
        if (episodeNumber != null) {
            queries.add("$rawTitle Episode $episodeNumber Reynime")
            queries.add("$rawTitle Ep $episodeNumber Sub Indo")
            queries.add("$rawTitle $episodeNumber Reynime")
        }
        queries.add("$rawTitle Reynime")
        queries.add(rawTitle)

        for (query in queries.take(5)) {
            val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            val searchUrl = "https://www.dailymotion.com/search/$encoded/videos"
            val text = runCatching {
                app.get(searchUrl, headers = headers, referer = mainUrl, timeout = 15L).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (text.isBlank()) continue

            val ids = Regex("""/video/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1] }
                .distinct()
                .take(4)
                .toList()

            for (id in ids) {
                val videoUrl = "https://www.dailymotion.com/video/$id"
                val success = runCatching {
                    loadExtractor(videoUrl, searchUrl, subtitleCallback, callback)
                }.getOrDefault(false)
                if (success) return true
            }
        }

        return false
    }

    private suspend fun tryApiPlayback(
        pageUrl: String,
        html: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val pathParts = runCatching { URI(pageUrl).path.trim('/').split('/').filter { it.isNotBlank() } }
            .getOrDefault(emptyList())

        val ids = linkedSetOf<String>()
        pathParts.forEach { part ->
            if (part.length in 1..80 && !part.equals("series", true) && !part.equals("episode", true) && !part.equals("watch", true)) {
                ids.add(part)
            }
        }

        Regex(""""(?:id|episodeId|videoId|postId|slug)"\s*:\s*"?([A-Za-z0-9_-]{1,80})"?""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .forEach { ids.add(it) }

        val endpointTemplates = listOf(
            "$mainUrl/api/episode/%s",
            "$mainUrl/api/episodes/%s",
            "$mainUrl/api/watch/%s",
            "$mainUrl/api/stream/%s",
            "$mainUrl/api/video/%s",
            "$mainUrl/api/source/%s",
            "$mainUrl/api/player/%s",
            "$mainUrl/api/episode/%s/stream",
            "$mainUrl/api/watch/%s/stream"
        )

        ids.take(8).forEach { id ->
            endpointTemplates.forEach { template ->
                val endpoint = template.format(URLEncoder.encode(id, "UTF-8"))
                val text = runCatching {
                    app.get(
                        endpoint,
                        headers = headers + mapOf("Accept" to "application/json,text/plain,*/*", "X-Requested-With" to "XMLHttpRequest"),
                        referer = pageUrl,
                        timeout = 12L
                    ).text.cleanEscaped()
                }.getOrNull().orEmpty()

                if (text.isBlank()) return@forEach

                extractPlayableUrls(text).forEach { addCandidate(it, endpoint, directLinks, embedLinks) }
                extractSubtitles(text, endpoint, subtitleCallback)

                val decoded = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
                if (decoded != text) {
                    extractPlayableUrls(decoded).forEach { addCandidate(it, endpoint, directLinks, embedLinks) }
                    extractSubtitles(decoded, endpoint, subtitleCallback)
                }
            }
        }
    }

    private fun collectCandidatesFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], iframe[src], iframe[data-src], iframe[data-litespeed-src], " +
                "video[src], video[data-src], video source[src], source[src], embed[src], object[data], " +
                "a[href], [data-url], [data-src], [data-video], [data-file], [data-embed], [data-iframe]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-litespeed-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach

            val label = element.text().lowercase()
            if (label.contains("trailer") || label.contains("report")) return@forEach

            addCandidate(raw, baseUrl, directLinks, embedLinks)
        }
    }

    private suspend fun resolveNestedLinks(url: String, referer: String): List<String> {
        if (shouldSkipUrl(url)) return emptyList()

        val response = runCatching {
            app.get(url, headers = headers, referer = referer, timeout = 15L)
        }.getOrNull() ?: return emptyList()

        val text = response.text.cleanEscaped()
        val results = linkedSetOf<String>()

        response.document.select(
            "iframe[src], iframe[data-src], source[src], video[src], embed[src], object[data], a[href], " +
                "[data-url], [data-src], [data-file], [data-video], [data-embed]"
        ).forEach { element ->
            val raw = element.attr("data-video")
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
            if (raw.isNotBlank()) results.add(normalizeUrl(raw, url))
        }

        results.addAll(extractPlayableUrls(text))

        val unpacked = runCatching {
            if (!getPacked(text).isNullOrEmpty()) getAndUnpack(text) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            results.addAll(extractPlayableUrls(unpacked.cleanEscaped()))
        }

        return results
            .map { normalizeUrl(it, url).replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .distinct()
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isBadMediaUrl(fixed) || shouldSkipUrl(fixed)) return

        when {
            isDirectVideo(fixed) -> directLinks.add(fixed)
            fixed.startsWith("http", true) && isLikelyEmbed(fixed) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isBadMediaUrl(link) || shouldSkipUrl(link)) return false

        return if (isHlsLike(link)) {
            val generated = runCatching {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                )
            }.getOrNull().orEmpty()

            if (generated.isNotEmpty()) {
                generated.forEach(callback)
                true
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = qualityFromUrl(link)
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer,
                            "Origin" to mainUrl,
                            "Accept" to "*/*"
                        )
                    }
                )
                true
            }
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(link).takeIf { it != Qualities.Unknown.value } ?: qualityFromUrl(link)
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
            true
        }
    }

    private suspend fun extractSubtitles(
        text: String,
        baseUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val clean = text.cleanEscaped()

        Regex(
            """"(?:label|lang|language)"\s*:\s*"([^"]+)"[^{}]{0,300}"(?:file|url|src|path)"\s*:\s*"([^"]+\.(?:vtt|srt|ass)[^"]*)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = normalizeSubtitleLabel(match.groupValues[1])
            val url = normalizeUrl(match.groupValues[2].cleanEscaped(), baseUrl)
            if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile(label, url))
        }

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:vtt|srt|ass)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val url = match.value.cleanEscaped()
            if (!shouldSkipUrl(url)) subtitleCallback(newSubtitleFile("Subtitle", url))
        }
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|mkv|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8" )}" }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.mkv|\.txt|embed|player|stream)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|video|videoUrl|video_url|stream|streamUrl|stream_url|hls|hlsUrl|hls_url|embed|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { isDirectVideo(it) || isLikelyEmbed(it) }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:embed|player|stream|watch|video|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|mediafire|mega|drive\.google|ok\.ru|dailymotion|rumble)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun decodeBase64Payloads(text: String): List<String> {
        val results = linkedSetOf<String>()

        Regex("""["']([A-Za-z0-9+/=]{40,})["']""")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(30)
            .forEach { token ->
                runCatching {
                    String(Base64.getDecoder().decode(token), Charsets.UTF_8)
                }.getOrNull()?.takeIf {
                    it.contains("http", true) || it.contains("iframe", true) || it.contains("m3u8", true)
                }?.let { results.add(it.cleanEscaped()) }
            }

        return results.toList()
    }

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: mainUrl
                "$origin$clean"
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun prioritizeEmbeds(links: Collection<String>): List<String> {
        return links
            .filterNot { isBadMediaUrl(it) || shouldSkipUrl(it) }
            .distinct()
            .sortedWith(compareBy<String> { hostPriority(it) }.thenBy { it.length })
    }

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
            value.contains("embed") -> 30
            value.contains("player") -> 31
            value.contains("stream") -> 32
            else -> 50
        }
    }

    private fun isDirectVideo(url: String): Boolean {
        return isHlsLike(url) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true) ||
            url.contains(".mkv", true)
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) || url.contains("application/x-mpegurl", true)
    }

    private fun isLikelyEmbed(url: String): Boolean {
        val value = url.lowercase()
        return value.startsWith("http") && (
            value.contains("embed") ||
                value.contains("player") ||
                value.contains("stream") ||
                value.contains("watch") ||
                value.contains("video") ||
                value.contains("filemoon") ||
                value.contains("streamwish") ||
                value.contains("wishfast") ||
                value.contains("dood") ||
                value.contains("streamtape") ||
                value.contains("vidhide") ||
                value.contains("vidguard") ||
                value.contains("voe") ||
                value.contains("mixdrop") ||
                value.contains("mp4upload") ||
                value.contains("ok.ru") ||
                value.contains("dailymotion") ||
                value.contains("rumble")
            )
    }

    private fun shouldSkipUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("javascript") ||
            value.startsWith("mailto:") ||
            value.startsWith("#") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("googletagmanager") ||
            value.contains("cloudflareinsights") ||
            value.contains("recaptcha") ||
            value.contains("/login") ||
            value.contains("/register") ||
            value.contains("/privacy") ||
            value.contains("/contact")
    }

    private fun isBadMediaUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adservice") ||
            value.contains("adsterra") ||
            value.contains("popads") ||
            value.contains("/ads/") ||
            value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("banner") ||
            value.contains("tracking") ||
            value.contains("analytics")
    }

    private fun isBlockedCatalogUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.trim('/').lowercase() }
            .getOrDefault(url.lowercase())
        return path.isBlank() ||
            path.startsWith("genre") ||
            path.startsWith("tag") ||
            path.startsWith("search") ||
            path.startsWith("login") ||
            path.startsWith("register") ||
            path.startsWith("privacy") ||
            path.startsWith("contact") ||
            path.startsWith("api")
    }

    private fun getTypeFromText(text: String): TvType {
        return when {
            text.contains("movie", true) || text.contains("film", true) -> TvType.AnimeMovie
            text.contains("ova", true) || text.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun normalizeSubtitleLabel(label: String): String {
        return when {
            label.contains("indonesia", true) || label.equals("id", true) || label.contains("bahasa", true) -> "Indonesian"
            label.isBlank() -> "Subtitle"
            else -> label
        }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull { it.isNotBlank() }
        }

        return fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun isBadTitle(title: String): Boolean {
        val value = title.lowercase().trim()
        return value.isBlank() ||
            value == "home" ||
            value == "login" ||
            value == "register" ||
            value == "search" ||
            value == "genre" ||
            value == "watch" ||
            value == "episode" ||
            value == "episodes" ||
            value.contains("tentang reynime")
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace(Regex("""\s+[-|]\s+Reynime\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
