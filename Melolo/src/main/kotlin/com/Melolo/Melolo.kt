package com.Melolo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import com.Melolo.MeloloHTMLConstants as HTML
import com.Melolo.MeloloAPIConstants as API
import com.Melolo.MeloloAPI
import com.Melolo.MeloloScraper
import com.Melolo.MeloloConstants as Constants

/**
 * 🚀 UNIVERSAL MEDIA ENGINE - VERSION 3.0.0 (MODULAR)
 * 
 * Arsitektur modular yang memisahkan logika orkestrasi (Melolo)
 * dengan mesin eksekusi spesifik:
 * - MeloloScraper: Engine untuk 7 provider bertipe HTML.
 * - MeloloAPI: Engine untuk 3 provider bertipe API.
 * 
 * LOGIC ALIGNMENT: Commit 5826152 (Ground Truth).
 */

open class Melolo : MainAPI() {
    
    // Identity Detection (Lazy-Safe)
    val isApiProvider: Boolean by lazy { 
        listOf("Dramabox", "Melolo", "Idlix").contains(providerId)
    }

    // Cache untuk mempercepat akses konfigurasi (O(1))
    private val configCache = mutableMapOf<Int, String>()
    private val configListCache = mutableMapOf<Int, List<String>>()

    val providerId: String by lazy { 
        this::class.java.simpleName.replace("Provider", "")
    }

    override var name = "Base Provider"
    override var mainUrl = "https://example.com"
    open var seriesUrl = ""
    open var searchUrl = ""

    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val usesWebView = true
    
    override var supportedTypes = setOf<TvType>()

    // Engine Configurations
    open var searchPathPattern = ""
    open var mainPagePathPattern = ""
    open var moviePathSegment = ""
    open var tvPathSegment = ""
    open var searchPageLimit = 2
    open var reverseEpisodes = true
    
    // Performance & Stability
    open var useDocumentLarge = false
    open var loadRecursiveLimit = 2
    open var loadLinksSemaphoreLimit = 6

    open var globalHeaders: Map<String, String> = emptyMap()

    init {
        // Late Initialization to avoid property access bugs in constructor
        val api = isApiProvider
        name = getCached(if (api) API.CONFIG_NAMES else HTML.CONFIG_NAMES, "Base Provider")
        mainUrl = getCached(if (api) API.CONFIG_MAIN_URLS else HTML.CONFIG_MAIN_URLS, "https://example.com")
        seriesUrl = getCached(if (api) API.CONFIG_SERIES_URLS else HTML.CONFIG_SERIES_URLS, mainUrl).let { if (it.isBlank()) mainUrl else it }
        searchUrl = getCached(if (api) API.CONFIG_SEARCH_URLS else HTML.CONFIG_SEARCH_URLS, mainUrl).let { if (it.isBlank()) mainUrl else it }
        lang = getCached(if (api) API.CONFIG_LANGS else HTML.CONFIG_LANGS, "id")
        
        supportedTypes = getCached(if (api) API.CONFIG_SUPPORTED_TYPES else HTML.CONFIG_SUPPORTED_TYPES, "Anime,AnimeMovie,TvSeries,Movie,AsianDrama")
            .split(",").mapNotNull { type -> 
                runCatching { TvType.entries.find { it.name.equals(type.trim(), true) } }.getOrNull() 
            }.toSet()

        searchPathPattern = getCached(if (api) API.CONFIG_SEARCH_PATH_PATTERNS else HTML.CONFIG_SEARCH_PATH_PATTERNS, "{baseUrl}/page/{page}/?s={query}")
        mainPagePathPattern = getCached(if (api) API.CONFIG_MAIN_PAGE_PATH_PATTERNS else HTML.CONFIG_MAIN_PAGE_PATH_PATTERNS, "{baseUrl}/{data}{page}")
        moviePathSegment = getCached(if (api) API.CONFIG_MOVIE_PATH_SEGMENTS else HTML.CONFIG_MOVIE_PATH_SEGMENTS, "/movie/")
        tvPathSegment = getCached(if (api) API.CONFIG_TV_PATH_SEGMENTS else HTML.CONFIG_TV_PATH_SEGMENTS, "/anime/")
        searchPageLimit = getCached(if (api) API.CONFIG_SEARCH_PAGE_LIMITS else HTML.CONFIG_SEARCH_PAGE_LIMITS, "2").toIntOrNull() ?: 2
        reverseEpisodes = getCached(if (api) API.CONFIG_REVERSE_EPISODES else HTML.CONFIG_REVERSE_EPISODES, "true").toBoolean()
        useDocumentLarge = getCached(if (api) API.CONFIG_USE_DOCUMENT_LARGE else HTML.CONFIG_USE_DOCUMENT_LARGE, "false").toBoolean()
        loadRecursiveLimit = getCached(if (api) API.CONFIG_LOAD_RECURSIVE_LIMIT else HTML.CONFIG_LOAD_RECURSIVE_LIMIT, "2").toIntOrNull() ?: 2
        loadLinksSemaphoreLimit = getCached(if (api) API.CONFIG_LOAD_LINKS_SEMAPHORE else HTML.CONFIG_LOAD_LINKS_SEMAPHORE, "6").toIntOrNull() ?: 6

        globalHeaders = getCached(if (api) API.CONFIG_GLOBAL_HEADERS else HTML.CONFIG_GLOBAL_HEADERS, "User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .split("|").associate { val parts = it.split("="); if (parts.size == 2) parts[0] to parts[1] else "" to "" }.filter { it.key.isNotBlank() }
    }

    override val mainPage = mainPageOf(*resolveMainPageList().toTypedArray())

    // ============================================
    // REGION: CORE MAIN-API OVERRIDES
    // ============================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (isApiProvider) {
            MeloloAPI.getMainPage(this, page, request)
        } else {
            MeloloScraper.getMainPage(this, page, request)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return if (isApiProvider) {
            MeloloAPI.search(this, query)
        } else {
            MeloloScraper.search(this, query)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse {
        return if (isApiProvider) {
            MeloloAPI.load(this, url)
        } else {
            MeloloScraper.load(this, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return if (isApiProvider) {
            MeloloAPI.loadLinks(this, data, subtitleCallback, callback)
        } else {
            MeloloScraper.loadLinks(this, data, subtitleCallback, callback)
        }
    }

    // ============================================
    // REGION: PUBLIC HELPERS (FOR ENGINES)
    // ============================================

    fun pubNewHomePageResponse(name: String, list: List<SearchResponse>, hasNext: Boolean = false) = newHomePageResponse(name, list, hasNext)
    fun pubNewHomePageResponse(list: HomePageList, hasNext: Boolean = false) = newHomePageResponse(list, hasNext)
    fun pubNewAnimeSearchResponse(name: String, url: String, type: TvType, fix: AnimeSearchResponse.() -> Unit = {}) = newAnimeSearchResponse(name, url, type) { fix() }
    fun pubNewTvSeriesSearchResponse(name: String, url: String, type: TvType, fix: TvSeriesSearchResponse.() -> Unit = {}) = newTvSeriesSearchResponse(name, url, type) { fix() }
    fun pubNewMovieSearchResponse(name: String, url: String, type: TvType, fix: MovieSearchResponse.() -> Unit = {}) = newMovieSearchResponse(name, url, type) { fix() }
    fun pubNewEpisode(data: String, fix: Episode.() -> Unit = {}) = newEpisode(data, fix)
    suspend fun pubNewAnimeLoadResponse(name: String, url: String, type: TvType, fix: suspend AnimeLoadResponse.() -> Unit = {}) = newAnimeLoadResponse(name, url, type) { fix() }
    suspend fun pubNewTvSeriesLoadResponse(name: String, url: String, type: TvType, episodes: List<Episode>, fix: suspend TvSeriesLoadResponse.() -> Unit = {}) = newTvSeriesLoadResponse(name, url, type, episodes) { fix() }
    suspend fun pubNewMovieLoadResponse(name: String, url: String, type: TvType, dataUrl: String, fix: suspend MovieLoadResponse.() -> Unit = {}) = newMovieLoadResponse(name, url, type, dataUrl) { fix() }

    // ============================================
    // REGION: INTERNAL UTILITIES
    // ============================================

    fun getCached(list: List<String>, default: String): String {
        return configCache.getOrPut(list.hashCode()) { resolveConfig(list, default) }
    }

    fun getCachedList(list: List<String>): List<String> {
        return configListCache.getOrPut(list.hashCode()) { resolveConfigList(list) }
    }

    private fun resolveConfig(list: List<String>, default: String): String {
        for (item in list) { 
            if (item.contains(":::")) { 
                val owners = item.substringBefore(":::").split(",").map { it.trim() }
                if (owners.contains(providerId)) { 
                    val v = item.substringAfter(":::")
                    if (v.isBlank()) break
                    return v 
                } 
            } 
        }
        for (item in list) { 
            if (item.startsWith("GLOBAL:::")) return item.substringAfter(":::")
            if (!item.contains(":::")) return item 
        }
        return default
    }

    private fun resolveConfigList(list: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (item in list) { 
            if (item.contains(":::")) { 
                val owners = item.substringBefore(":::").split(",").map { it.trim() }
                if (owners.contains(providerId)) { 
                    val v = item.substringAfter(":::")
                    if (v.isNotBlank()) result.add(v) 
                } 
            } 
        }
        if (result.isNotEmpty()) return result
        for (item in list) { 
            val v = if (item.contains(":::")) { 
                if (item.startsWith("GLOBAL:::")) item.substringAfter(":::") else continue 
            } else item; if (v.isNotBlank()) result.add(v) 
        }
        return result
    }

    private fun resolveMainPageList(): List<Pair<String, String>> {
        val raw = getCached(if (isApiProvider) API.CONFIG_MAIN_PAGE_LISTS else HTML.CONFIG_MAIN_PAGE_LISTS, "trending/page/|Sedang Tren")
        return raw.split(";").mapNotNull { 
            val parts = it.split("|")
            if (parts.size == 2) parts[0] to parts[1] else null 
        }
    }

    suspend fun getHtmlParsed(url: String, referer: String? = null, skipCache: Boolean = false): Document {
        val fixedUrl = fixUrlSmart(url, mainUrl)
        if (!skipCache) { globalHtmlCache.get(fixedUrl)?.let { return it } }
        return executeWithRetry { 
            rateLimitDelay(fixedUrl)
            val res = app.get(fixedUrl, timeout = Constants.DEFAULT_TIMEOUT, headers = globalHeaders, referer = referer ?: mainUrl)
            val doc = if (useDocumentLarge) res.documentLarge else res.document
            if (!skipCache) { globalHtmlCache.put(fixedUrl, doc) }
            doc
        }
    }
}
