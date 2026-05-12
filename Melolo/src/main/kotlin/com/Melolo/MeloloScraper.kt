package com.Melolo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.Melolo.MeloloConstants as Constants
import com.Melolo.MeloloMapper.toSearchResponse
import com.Melolo.MeloloMapper.toEpisode
import com.Melolo.MeloloMapper.toLoadResponse
import com.Melolo.MeloloMapper.toActor

/**
 * ⚙️ ARCHITECTURE LAYER: SCRAPER ENGINE (HTML)
 * 
 * Lapisan ini bertanggung jawab menangani seluruh alur kerja provider bertipe HTML.
 * Tanggung jawab: Request HTML, Parsing Document, Selector Handling, Fallback, 
 * Homepage, Search, Load, Episode, dan Extractor Handling.
 * 
 * LOGIC ALIGNMENT: Commit 5826152 (Ground Truth).
 */

object MeloloScraper {

    // ============================================
    // REGION: MAIN WORKFLOW (ORCHESTRATION)
    // ============================================

    suspend fun getMainPage(provider: Melolo, page: Int, request: MainPageRequest): HomePageResponse {
        val seriesKeyword = provider.getCached(Constants.STR_SERIES, "Series")
        val baseUrl = if (request.name.contains(seriesKeyword, true) && provider.seriesUrl.isNotBlank()) provider.seriesUrl else provider.mainUrl
        
        // Ground Truth: Literal URL Construction (V2.2.0 Pattern)
        val url = if (request.data.startsWith("http")) {
            val d = request.data.replace("{page}", page.toString()).replace("%d", page.toString())
            val pagePattern = Regex("""(/page/|page=)$page(\b|/|$)""")
            if (!pagePattern.containsMatchIn(d)) {
                if (d.endsWith("/page/")) "${d}$page"
                else { val conn = if (d.contains("?")) "&" else "?"; "${d}${conn}page=$page" }
            } else d
        } else {
            val data = request.data.replace("{page}", page.toString()).replace("%d", page.toString())
            provider.mainPagePathPattern.replace("{baseUrl}", baseUrl).replace("{data}", data).replace("{page}", page.toString())
        }
        
        val document = provider.getHtmlParsed(url)
        val isHorizontal = provider.getCached(Constants.CONFIG_HOOK_IS_HORIZONTAL, "false").toBoolean() && request.name.contains("Episode Terbaru", true)
        
        val rawItems = document.selectSafeList(provider, Constants.SEARCH_ITEMS)
        val home = rawItems.mapNotNull { el -> 
            el.toScrapedSearch(provider, url)
        }.map { it.toSearchResponse(provider) }
        
        return provider.pubNewHomePageResponse(list = HomePageList(name = request.name, list = home, isHorizontalImages = isHorizontal), hasNext = home.isNotEmpty())
    }

    suspend fun search(provider: Melolo, query: String): List<SearchResponse> {
        val encodedQuery = runCatching { java.net.URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        val baseUrl = if (provider.searchUrl.isNotBlank()) provider.searchUrl else provider.mainUrl
        val refer = app.get(provider.mainUrl).url
        
        // Ground Truth: JSON Search (V2.2.0 Pattern)
        if (provider.getCached(Constants.CONFIG_SEARCH_IS_JSON, "false").toBoolean()) {
            return runCatching {
                val searchUrlJson = provider.searchPathPattern.replace("{baseUrl}", baseUrl).replace("{query}", encodedQuery).replace("{page}", "1")
                val response = app.get(searchUrlJson, referer = refer, headers = provider.globalHeaders).text
                val root = JSONObject(response)
                
                val searchRootKey = provider.getCached(Constants.CONFIG_SEARCH_JSON_ROOTS, "data")
                val titleKey = provider.getCached(Constants.CONFIG_SEARCH_JSON_TITLES, "title")
                val hrefKey = provider.getCached(Constants.CONFIG_SEARCH_JSON_HREFS, "slug")
                val posterKey = provider.getCached(Constants.CONFIG_SEARCH_JSON_POSTERS, "poster")
                val posterPrefix = provider.getCached(Constants.CONFIG_SEARCH_JSON_POSTER_PREFIXES, "")
                val typeKey = provider.getCached(Constants.CONFIG_SEARCH_JSON_TYPES, "type")
                
                val items = if (searchRootKey.isBlank()) root.getJSONArray("results") else root.getJSONArray(searchRootKey)
                val results = mutableListOf<SearchResponse>()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val rawTitle = item.optString(titleKey)
                    val title = rawTitle.safeCleanBloat(rawTitle, Constants.BLOAT_REGEX)
                    val slug = item.optString(hrefKey)
                    var pUrl = item.optString(posterKey)
                    if (!pUrl.startsWith("http") && posterPrefix.isNotBlank()) pUrl = posterPrefix + pUrl
                    
                    val isTv = item.optString(typeKey).contains("series", true) || item.optString(typeKey).contains("tv", true)
                    val finalUrl = if (isTv) "${provider.seriesUrl}/$slug" else "${provider.mainUrl}/$slug"
                    results.add(provider.pubNewAnimeSearchResponse(title, finalUrl, if (isTv) TvType.TvSeries else TvType.Movie) { 
                        this.posterUrl = pUrl
                        this.posterHeaders = provider.globalHeaders.toMutableMap().apply { put(Constants.VAL_REFERER, provider.mainUrl) }
                    })
                }
                results
            }.getOrElse { emptyList<SearchResponse>() }
        }

        return coroutineScope { 
            (1..provider.searchPageLimit).map { page -> 
                async { 
                    runCatching { 
                        val url = provider.searchPathPattern
                            .replace("{baseUrl}", baseUrl)
                            .replace("{page}", page.toString())
                            .replace("%d", page.toString())
                            .replace("{query}", encodedQuery)
                        val document = provider.getHtmlParsed(url, refer)
                        val rawItems = document.selectSafeList(provider, Constants.SEARCH_ITEMS)
                        rawItems.mapNotNull { el -> 
                            el.toScrapedSearch(provider, url)
                        }.map { it.toSearchResponse(provider) }
                    }.getOrElse { emptyList<SearchResponse>() } 
                } 
            }.awaitAll().flatten().distinctBy { it.url } 
        }
    }

    suspend fun load(provider: Melolo, url: String, depth: Int = 0): LoadResponse {
        val document = provider.getHtmlParsed(url)
        val currentUrl = url
        
        if (depth < provider.loadRecursiveLimit) { 
            val follow = provider.getCachedList(Constants.FOLLOW_LINK_SELECTOR)
            if (follow.isNotEmpty()) { 
                val nextAnchor = document.selectSafe(provider, follow)
                val nextUrl = fixUrlSmart(nextAnchor?.attr("href"), currentUrl)
                if (nextUrl.isNotBlank() && nextUrl != currentUrl && nextUrl != url) return load(provider, nextUrl, depth + 1) 
            } 
        }

        val scrapedDetail = extractDetail(provider, document)
        val (recommendations, actors) = coroutineScope {
            val recs = async { 
                document.selectSafeList(provider, Constants.LOAD_RECOMMEND).mapNotNull { el ->
                    el.toScrapedSearch(provider, currentUrl)
                }.map { it.toSearchResponse(provider) }
            }
            val acts = async { extractActors(provider, document).map { it.toActor() } }
            recs.await() to acts.await()
        }
        
        val scrapedEpisodes = extractEpisodes(provider, document, currentUrl, scrapedDetail.poster)
        
        // Ground Truth: Robust isMovie detection (V2.2.0 Pattern)
        val seasonDataScript = document.selectFirst("script#season-data")
        val isMovie = (seasonDataScript == null && document.selectFirst(".tvseason") == null) && 
                      ((provider.moviePathSegment.isNotBlank() && currentUrl.contains(provider.moviePathSegment)) || scrapedEpisodes.isEmpty())
        
        val type = if (isMovie) TvType.Movie else if (provider.supportedTypes.contains(TvType.Anime)) TvType.Anime else TvType.TvSeries

        val watchUrl = if (isMovie) {
            val anchor = document.selectSafe(provider, listOf(".play-button", ".watch-now", ".btn-watch"))
            fixUrlSmart(anchor?.attr("href"), currentUrl).ifBlank { currentUrl }
        } else currentUrl
        
        val dataUrl = provider.getCached(Constants.CONFIG_EPISODE_DATA_URL_PATTERNS, "{url}").replace("{url}", watchUrl)

        return scrapedDetail.toLoadResponse(
            provider = provider,
            url = url,
            type = type,
            dataUrl = dataUrl,
            episodes = scrapedEpisodes.map { it.toEpisode(provider) },
            recommendations = recommendations,
            actors = actors
        )
    }

    suspend fun loadLinks(
        provider: Melolo,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val document = provider.getHtmlParsed(data)
            val currentUrl = data
            val attrValueSelectors = provider.getCachedList(Constants.ATTR_VALUE)
            val allPossibleLinks = mutableSetOf<Pair<String, String?>>()

            // AGGRESSIVE GATHERING (STABLE V2.2.0 Pattern + Next.js Resilience)
            document.select("script").forEach { script ->
                val data = script.data()
                if (data.contains("__next_f.push")) {
                    CompiledRegexPatterns.extractAllVideoUrls(data).forEach { allPossibleLinks.add(it to "NextJS") }
                    // Special search for player domains like majorplay
                    Regex("""https?://[^\s"']*(?:majorplay|player)[^\s"']*""").findAll(data).forEach { 
                        allPossibleLinks.add(it.value.replace("\\/", "/") to "MajorPlay") 
                    }
                }
            }

            provider.getCachedList(Constants.LINK_OPTIONS).forEach { selector ->
                document.select(selector).forEach { container ->
                    val anchors = container.select("a")
                    if (anchors.isNotEmpty()) anchors.forEach { a -> allPossibleLinks.add(a.attr("href") to a.text()) }
                    else { 
                        val raw = container.attrSafe(provider, attrValueSelectors) ?: container.attr("href") ?: ""
                        if (raw.isNotBlank()) allPossibleLinks.add(raw to container.text()) 
                    }
                }
            }
            
            provider.getCachedList(Constants.DOWNLOAD_ITEMS).forEach { selector ->
                document.select(selector).forEach { container ->
                    container.select("a").forEach { a ->
                        val href = a.attr("href")
                        if (href.isNotBlank()) allPossibleLinks.add(href to a.text())
                    }
                }
            }

            document.select("iframe").forEach { el ->
                listOf("src", "data-src", "data-link").forEach { attr -> 
                    val s = el.attr(attr); if (s.isNotBlank()) allPossibleLinks.add(s to null) 
                }
            }

            coroutineScope {
                allPossibleLinks.filter { it.first.isNotBlank() }.map { (raw, label) -> async { runCatching {
                    // Ground Truth: Base64 Decoding (V2.2.0 Pattern)
                    val decodedRaw = if (!raw.startsWith("http") && !raw.startsWith("//") && !raw.startsWith("/") && raw.safeIsBase64()) {
                        val dec = raw.safeDecode()
                        if (dec.contains("iframe")) Jsoup.parse(dec).selectFirst("iframe")?.attr("src") ?: raw
                        else if (dec.startsWith("http") || dec.startsWith("//") || dec.startsWith("/")) dec else raw
                    } else raw

                    val fixedUrl = fixUrlSmart(decodedRaw, currentUrl).safeHttpsify().unpackPacked()
                    if (fixedUrl.isBlank()) return@runCatching

                    val okDirect = runCatching { loadExtractorWithFallbackCustom(fixedUrl, currentUrl, subtitleCallback, callback) }.getOrDefault(false)
                    if (!okDirect) {
                        val refererMode = provider.getCached(Constants.CONFIG_HOOK_REFERER_PLAYER, "current_url")
                        val refererForPlayer = if (refererMode == "series_url") "${provider.seriesUrl}/" else currentUrl
                        val playerDoc = app.get(fixedUrl, referer = refererForPlayer, headers = provider.globalHeaders).document
                        val iframeSelectors = provider.getCachedList(Constants.CONFIG_HOOK_IFRAME_SELECTORS)
                        val iframeSrc = iframeSelectors.asSequence().mapNotNull { playerDoc.selectFirst(it)?.attr("src") }.firstOrNull() ?: return@runCatching
                        val finalIframe = fixUrlSmart(iframeSrc, fixedUrl)
                        val refererForExtractor = getBaseUrl(fixedUrl)
                        val okRecursive = runCatching { loadExtractorWithFallbackCustom(finalIframe, refererForExtractor, subtitleCallback, callback) }.getOrDefault(false)
                        if (!okRecursive && (finalIframe.contains(".mp4") || finalIframe.contains(".m3u8") || finalIframe.contains(".mkv") || finalIframe.contains(".mpd"))) {
                            MasterLinkGenerator.createSmartLink(label ?: provider.name, finalIframe, refererForExtractor, callback = callback)
                        }
                    }
                }.getOrElse { e -> logDebug(provider.providerId, "Link Processor Error: ${e.message}") } } }.awaitAll()
            }
            true
        }.getOrElse { e -> logError(provider.providerId, "LoadLinks Critical Failure: ${e.message}"); false }
    }

    // ============================================
    // REGION: EXTRACTION LOGIC (CORE SCRAPING)
    // ============================================

    fun Element.toScrapedSearch(provider: Melolo, baseUrl: String): ScrapedSearch? {
        return runCatching {
            val titleEl = selectSafe(provider, Constants.SEARCH_TITLE) ?: parent()?.selectSafe(provider, Constants.SEARCH_TITLE) ?: selectFirst("h2, h3")
            val rawTitle = titleEl?.text()?.trim() ?: titleEl?.attrSafe(provider, Constants.ATTR_TITLE) ?: return null
            val title = rawTitle.safeCleanBloat(rawTitle, Constants.BLOAT_REGEX)
            
            val hrefEl = selectSafe(provider, Constants.SEARCH_HREF) ?: selectFirst("a") ?: parent()?.selectFirst("a")
            var href = fixUrlSmart(hrefEl?.attr("href"), baseUrl)
            
            // Ground Truth: Href Cleaning (V2.2.0 Stable Pattern)
            val cleanRegex = provider.getCached(Constants.CONFIG_HREF_CLEAN_REGEXPS, "")
            val cleanReplace = provider.getCached(Constants.CONFIG_HREF_CLEAN_REPLACES, "")
            if (cleanRegex.isNotBlank() && cleanReplace.isNotBlank()) {
                href = href.replace(Regex(cleanRegex), cleanReplace)
            }

            val poster = selectSafe(provider, Constants.SEARCH_POSTER)?.safeExtractImage(Constants.ATTR_IMAGE)
            val rating = selectSafe(provider, Constants.SEARCH_RATING)?.text()
            val eps = selectSafe(provider, Constants.SEARCH_EP_TEXT)?.text()?.safeExtractEpNum()

            val moviePath = provider.moviePathSegment
            val isMovie = (moviePath.isNotBlank() && href.contains(moviePath)) || href.contains("movie", true)
            val isDub = text().contains(provider.getCached(Constants.STR_DUB, "dub"), true)

            ScrapedSearch(
                title = title,
                url = href,
                poster = poster,
                rating = rating,
                episodeText = eps?.toString(),
                isMovie = isMovie,
                isDub = isDub
            )
        }.getOrNull()
    }

    fun extractDetail(provider: Melolo, document: Document): ScrapedDetail {
        val rawTitle = document.selectSafe(provider, Constants.LOAD_TITLE)?.text() ?: "Unknown Title"
        val title = rawTitle.safeCleanBloat(rawTitle, Constants.BLOAT_REGEX)
        val poster = document.selectSafe(provider, Constants.LOAD_POSTER)?.safeExtractImage(Constants.ATTR_IMAGE) ?: ""
        val banner = document.selectSafe(provider, Constants.LOAD_BANNER)?.safeExtractImage(Constants.ATTR_IMAGE)
        val description = document.selectSafe(provider, Constants.LOAD_DESC)?.text()?.trim() ?: ""
        
        val infoText = document.selectSafeList(provider, Constants.LOAD_INFO_BOX).text()
        
        // Ground Truth: Year Extraction (V2.2.0 Stable Pattern)
        val year = infoText.safeExtractYear() ?: run {
            val selector = provider.getCached(Constants.CONFIG_HOOK_YEAR_SELECTOR, "")
            val regexStr = provider.getCached(Constants.CONFIG_HOOK_YEAR_EXTRACTOR, "")
            if (selector.isNotBlank() && regexStr.isNotBlank()) {
                Regex(regexStr).find(document.select(selector).text())?.groupValues?.get(1)?.toIntOrNull()
            } else null
        }
        
        val statusText = document.selectSafe(provider, Constants.LOAD_STATUS)?.text()
        
        return ScrapedDetail(
            title = title, poster = poster, banner = banner, description = description,
            year = year, rating = document.selectSafe(provider, Constants.LOAD_RATING)?.text(),
            statusText = statusText,
            tags = document.selectSafeList(provider, Constants.LOAD_TAGS).map { it.text() },
            trailer = document.selectSafe(provider, Constants.LOAD_TRAILER)?.let { if (it.tagName() == "iframe") it.safeExtractImage(Constants.ATTR_IMAGE) else it.attrSafe(provider, Constants.ATTR_HREF) },
            imdbId = document.selectFirst("a[href*='imdb.com/title/']")?.attrSafe(provider, Constants.ATTR_HREF)?.split("/")?.filter { it.startsWith("tt") }?.firstOrNull(),
            tmdbId = document.selectFirst("a[href*='themoviedb.org/']")?.attrSafe(provider, Constants.ATTR_HREF)?.split("/")?.lastOrNull()?.toIntOrNull(),
            isComingSoon = statusText?.contains("Coming Soon", true) ?: false
        )
    }

    fun extractEpisodes(provider: Melolo, document: Document, currentUrl: String, poster: String): List<ScrapedEpisode> {
        val episodes = mutableListOf<ScrapedEpisode>()
        
        // Ground Truth: Support for JSON Season Data (V2.2.0 Pattern)
        val seasonDataScript = document.selectFirst("script#season-data")
        if (seasonDataScript != null) {
            runCatching {
                val root = JSONObject(seasonDataScript.data())
                root.keys().forEach { k ->
                    val arr = root.getJSONArray(k)
                    for (i in 0 until arr.length()) {
                        val ep = arr.getJSONObject(i)
                        val epHref = fixUrlSmart(ep.getString("slug"), currentUrl)
                        val epNum = ep.optInt("episode_no")
                        episodes.add(ScrapedEpisode(
                            name = "${provider.getCached(Constants.STR_EPISODE, "Episode")} $epNum",
                            url = epHref,
                            episodeNum = epNum,
                            season = ep.optInt("s"),
                            poster = poster
                        ))
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            val epItems = document.selectSafeList(provider, Constants.EPISODE_ITEMS)
            epItems.forEach { ep ->
                runCatching {
                    val anchor = ep.selectSafe(provider, Constants.EPISODE_HREF) ?: ep.selectFirst("a") ?: return@runCatching
                    val href = provider.getCached(Constants.CONFIG_EPISODE_DATA_URL_PATTERNS, "{url}").replace("{url}", fixUrlSmart(anchor.attr("href"), currentUrl))
                    val titleEl = ep.selectSafe(provider, Constants.EPISODE_TITLE) ?: ep.selectFirst("a")
                    val rawName = titleEl?.text()?.trim() ?: ""
                    val epNum = titleEl?.text()?.safeExtractEpNum() ?: ep.selectSafe(provider, Constants.EPISODE_NUM)?.text()?.safeExtractEpNum() ?: ep.text().safeExtractEpNum()
                    
                    val isJustNumber = rawName.matches(Regex("""^\d+(\.\d+)?$"""))
                    
                    episodes.add(ScrapedEpisode(
                        name = if (!isJustNumber && rawName.isNotBlank()) rawName else "",
                        url = href,
                        episodeNum = epNum,
                        season = null,
                        poster = ep.selectFirst("img")?.safeExtractImage(Constants.ATTR_IMAGE) ?: poster,
                        description = ep.selectSafe(provider, Constants.EPISODE_DESC)?.text()?.trim(),
                        runtime = ep.selectSafe(provider, Constants.EPISODE_TIME)?.text()?.filter { it.isDigit() }?.toIntOrNull()
                    ))
                }
            }
        }
        
        // Ground Truth: JSON Season data doesn't usually need reversing
        val finalEpisodes = if (provider.reverseEpisodes && seasonDataScript == null) episodes.reversed() else episodes
        return finalEpisodes.distinctBy { it.url }
    }

    fun extractActors(provider: Melolo, document: Document): List<ScrapedActor> {
        return document.selectSafeList(provider, Constants.ACTOR_ITEMS).mapNotNull { 
            val n = it.selectSafe(provider, Constants.ACTOR_NAME)?.text()?.trim() ?: ""
            val p = it.selectFirst("img")?.safeExtractImage(Constants.ATTR_IMAGE) ?: ""
            if (n.isNotBlank() && n.length < 100) ScrapedActor(n, p) else null
        }
    }

    // ============================================
    // REGION: HELPERS (SELECTOR HANDLING)
    // ============================================

    fun Element.selectSafe(provider: Melolo, selectors: List<String>): Element? {
        if (selectors.isEmpty()) return null
        val pid = provider.providerId.trim().lowercase()
        for (s in selectors) { if (!s.contains(":::")) continue
            val owners = s.substringBefore(":::").lowercase()
            if (owners.split(",").map { it.trim().lowercase() }.contains(pid)) {
                val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val el = selectFirst(sel); if (el != null) return el }
            }
        }
        for (s in selectors) {
            val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
            if (sel.isNotBlank()) { val el = selectFirst(sel); if (el != null) return el }
        }
        return null
    }

    fun Document.selectSafeList(provider: Melolo, selectors: List<String>): org.jsoup.select.Elements {
        if (selectors.isEmpty()) return org.jsoup.select.Elements()
        val pid = provider.providerId.trim().lowercase()
        for (s in selectors) { if (!s.contains(":::")) continue
            val owners = s.substringBefore(":::").lowercase()
            if (owners.split(",").map { it.trim().lowercase() }.contains(pid)) {
                val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val els = select(sel); if (els.isNotEmpty()) return els }
            }
        }
        for (s in selectors) {
            val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
            if (sel.isNotBlank()) { val els = select(sel); if (els.isNotEmpty()) return els }
        }
        return org.jsoup.select.Elements()
    }

    fun Element.selectSafeList(provider: Melolo, selectors: List<String>): org.jsoup.select.Elements {
        if (selectors.isEmpty()) return org.jsoup.select.Elements()
        val pid = provider.providerId.trim().lowercase()
        for (s in selectors) { if (!s.contains(":::")) continue
            val owners = s.substringBefore(":::").lowercase()
            if (owners.split(",").map { it.trim().lowercase() }.contains(pid)) {
                val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val els = select(sel); if (els.isNotEmpty()) return els }
            }
        }
        for (s in selectors) {
            val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
            if (sel.isNotBlank()) { val els = select(sel); if (els.isNotEmpty()) return els }
        }
        return org.jsoup.select.Elements()
    }

    fun Element.attrSafe(provider: Melolo, attributes: List<String>): String? {
        val pid = provider.providerId.trim().lowercase()
        for (a in attributes) { if (!a.contains(":::")) continue
            val owners = a.substringBefore(":::").lowercase()
            if (owners.split(",").map { it.trim().lowercase() }.contains(pid)) {
                val attrN = a.substringAfter(":::"); val v = attr(attrN); if (v.isNotBlank()) return v
            }
        }
        for (a in attributes) {
            val attrN = if (a.startsWith("GLOBAL:::")) a.substringAfter(":::") else if (!a.contains(":::")) a else continue
            val v = attr(attrN); if (v.isNotBlank()) return v
        }
        return null
    }
}
