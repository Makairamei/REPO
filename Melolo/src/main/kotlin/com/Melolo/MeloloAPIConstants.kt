package com.Melolo

import com.lagradost.cloudstream3.TvType

/**
 * 📘 MASTER CONFIGURATION: API / JSON PROVIDERS
 * 
 * Khusus menyimpan endpoint API dan Key JSON untuk provider tipe API.
 * Seluruh data diselaraskan dengan rilis stabil V2.2.0 untuk behavior-nya.
 */
object MeloloAPIConstants {

    // --- [1] METADATA (API ONLY) ---
    val CONFIG_NAMES = listOf(
        "Dramabox:::DramaBox👌", 
        "Melolo:::Melolo😶", 
        "Idlix:::Idlix",
        "GLOBAL:::Base API Provider"
    )

    val CONFIG_MAIN_URLS = listOf(
        "Dramabox:::https://www.dramabox.com/in", 
        "Melolo:::https://api.tmthreader.com", 
        "Idlix:::https://z1.idlixku.com", 
        "GLOBAL:::https://example.api"
    )

    val CONFIG_LANGS = listOf("GLOBAL:::id")
    
    val CONFIG_SUPPORTED_TYPES = listOf(
        "Dramabox:::TvSeries,AsianDrama", 
        "Melolo:::TvSeries,AsianDrama", 
        "Idlix:::Movie,TvSeries,Anime,AsianDrama",
        "GLOBAL:::Movie,TvSeries,AsianDrama"
    )

    // --- [2] API ENDPOINTS & JSON KEYS ---
    
    // Search Config (For JSON Search Pattern in Provider.kt)
    val CONFIG_SEARCH_IS_JSON = listOf("Idlix:::true", "GLOBAL:::false")
    val CONFIG_SEARCH_JSON_ROOTS = listOf("Idlix:::data", "GLOBAL:::data")
    val CONFIG_SEARCH_JSON_TITLES = listOf("Idlix:::title", "GLOBAL:::title")
    val CONFIG_SEARCH_JSON_HREFS = listOf("Idlix:::slug", "GLOBAL:::slug")
    val CONFIG_SEARCH_JSON_POSTERS = listOf("Idlix:::poster_path", "GLOBAL:::poster")
    val CONFIG_SEARCH_JSON_POSTER_PREFIXES = listOf("Idlix:::https://image.tmdb.org/t/p/w342", "GLOBAL:::")
    val CONFIG_SEARCH_JSON_TYPES = listOf("Idlix:::contentType", "GLOBAL:::type")

    val CONFIG_SERIES_URLS = listOf("GLOBAL:::")
    val CONFIG_SEARCH_URLS = listOf("GLOBAL:::")

    val CONFIG_SEARCH_PAGE_LIMITS = listOf("GLOBAL:::1")
    val CONFIG_HREF_CLEAN_REGEXPS = listOf("GLOBAL:::")
    val CONFIG_HREF_CLEAN_REPLACES = listOf("GLOBAL:::")
    
    val CONFIG_SEARCH_PATH_PATTERNS = listOf("GLOBAL:::{baseUrl}/page/{page}/?s={query}")
    val CONFIG_MAIN_PAGE_PATH_PATTERNS = listOf("GLOBAL:::{baseUrl}/{data}{page}")
    val CONFIG_MOVIE_PATH_SEGMENTS = listOf("GLOBAL:::/movie/")
    val CONFIG_TV_PATH_SEGMENTS = listOf("GLOBAL:::/anime/")
    val CONFIG_GLOBAL_HEADERS = listOf("GLOBAL:::User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    
    val CONFIG_MAIN_PAGE_LISTS = listOf(
        "Dramabox:::/api/dramas/indo|Drama Dub Indo;/api/dramas/trending|Trending;/api/dramas/must-sees|Must Sees;/api/dramas/hidden-gems|Hidden Gems",
        "Melolo:::latest|Terbaru;trending|Trending;q:ceo|CEO;q:romansa|Romansa;q:sistem|Sistem;q:keluarga|Keluarga;q:mafia|Mafia;q:aksi|Aksi;q:balas dendam|Balas Dendam;q:pernikahan|Pernikahan;q:drama periode|Drama Periode",
        "Idlix:::https://z1.idlixku.com/api/movies?page=%d&limit=36&sort=createdAt|Movie Terbaru;https://z1.idlixku.com/api/series?page=%d&limit=36&sort=createdAt|TV Series Terbaru;https://z1.idlixku.com/api/browse?page=%d&limit=36&sort=latest&network=prime-video|Amazon Prime;https://z1.idlixku.com/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus|Apple TV+;https://z1.idlixku.com/api/browse?page=%d&limit=36&sort=latest&network=disney-plus|Disney+;https://z1.idlixku.com/api/browse?page=%d&limit=36&sort=latest&network=hbo|HBO;https://z1.idlixku.com/api/browse?page=%d&limit=36&sort=latest&network=netflix|Netflix",
        "GLOBAL:::trending/page/|Sedang Tren;terbaru/page/|Update Terbaru"
    )

    // --- [3] SHARED ENGINE CONFIGURATION ---
    val CONFIG_EPISODE_DATA_URL_PATTERNS = listOf("GLOBAL:::{url}")
    val CONFIG_REVERSE_EPISODES = listOf("GLOBAL:::true")
    val CONFIG_USE_DOCUMENT_LARGE = listOf("GLOBAL:::false")
    val CONFIG_LOAD_RECURSIVE_LIMIT = listOf("GLOBAL:::2")
    val CONFIG_LOAD_LINKS_SEMAPHORE = listOf("GLOBAL:::6")
    val CONFIG_CACHE_TTL_MINUTES = listOf("GLOBAL:::5")
    val DEFAULT_TIMEOUT = 15000L

    // --- [4] UI HOOKS & STRINGS ---
    val CONFIG_HORIZONTAL_KEYWORDS = listOf("GLOBAL:::Episode Terbaru")
    val CONFIG_HOOK_IS_HORIZONTAL = listOf("GLOBAL:::false")
    val CONFIG_HOOK_YEAR_SELECTOR = listOf("GLOBAL:::")
    val CONFIG_HOOK_YEAR_EXTRACTOR = listOf("GLOBAL:::")
    val CONFIG_HOOK_REFERER_PLAYER = listOf("GLOBAL:::current_url")
    val CONFIG_HOOK_IFRAME_SELECTORS = listOf("GLOBAL:::iframe")

    val LOAD_RATING = listOf("GLOBAL:::")
    val LOAD_QUALITY = listOf("GLOBAL:::")
    val LOAD_STATUS = listOf("GLOBAL:::")
    val LOAD_TRAILER = listOf("GLOBAL:::")

    const val VAL_REFERER = "Referer"
    val STR_DUB = listOf("GLOBAL:::dub")
    val STR_SERIES = listOf("GLOBAL:::Series")
    val STR_EPISODE = listOf("GLOBAL:::Episode")

    val BLOAT_REGEX = Regex("(?i)(\\bONA\\b|\\bOngoing\\b|\\bCompleted\\b|\\bSpecial\\b|\\bTAMAT\\b|\\bIndo\\b|\\bFull\\b|\\bSeason\\b|Subtitle\\s*Indonesia|Nonton|Anime|Movie|TV|Series|Lengkap|HD|Free|\\d{3,4}p|Dual\\s*Audio|\\s*–\\s*|\\s*\\|\\s*)", RegexOption.IGNORE_CASE)

    // --- [5] API PROXY & ENGINE SECRETS ---
    val CONFIG_API_PROXY_URLS = listOf(
        "Dramabox:::https://db.hafizhibnusyam.my.id",
        "Melolo:::https://melolo-api-azure.vercel.app/api/melolo",
        "GLOBAL:::"
    )

    val CONFIG_API_ADDITIONAL_KEYS = listOf(
        "Melolo:::645713", 
        "GLOBAL:::"
    )
}
