package com.nonton01

object Nonton01Seeds {
    const val MAIN_URL = "https://01ntn.cc"
    const val PUBLIC_URL = "https://01nonton.com"
    const val LATEST_LINK_URL = "https://idmax.one/01nonton/"

    /**
     * Mirror candidates are intentionally centralized here so runtime fixes do not
     * require parser/extractor rewrites when the IP or public domain changes.
     */
    val MIRROR_URLS = listOf(
        MAIN_URL,
        "https://www.01ntn.cc",
        "https://91.208.197.221",
        PUBLIC_URL,
        "https://www.01nonton.com",
        "https://nonton01.com"
    )

    val KNOWN_HOSTS = setOf(
        "01ntn.cc",
        "www.01ntn.cc",
        "91.208.197.221",
        "01nonton.com",
        "www.01nonton.com",
        "nonton01.com",
        "www.nonton01.com",
        "01nonton.top",
        "www.01nonton.top",
        "01nonton.site",
        "www.01nonton.site"
    )

    /**
     * Data memakai prefix paths: agar satu row bisa mencoba beberapa struktur permalink.
     * Situs berbasis IP seperti ini sering mengganti slug kategori, jadi provider mencoba
     * variasi umum tanpa mengubah label kategori di UI.
     */
    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        paths("/page/%d/", "/movies/page/%d/", "/movie/page/%d/") to "Upload Terbaru",
        paths("/movies/page/%d/", "/movie/page/%d/", "/film/page/%d/") to "Movies",
        paths("/film-semi/page/%d/", "/film-dewasa/page/%d/", "/semi/page/%d/") to "Film Semi",
        paths("/drakor/page/%d/", "/k-drama/page/%d/", "/country/korea/page/%d/") to "Drakor",
        paths("/dracin/page/%d/", "/drama-china/page/%d/", "/country/china/page/%d/") to "Dracin",
        paths("/genre/action/page/%d/", "/action/page/%d/", "/category/action/page/%d/") to "Action",
        paths("/genre/adventure/page/%d/", "/adventure/page/%d/", "/category/adventure/page/%d/") to "Adventure",
        paths("/genre/animation/page/%d/", "/animation/page/%d/", "/category/animation/page/%d/") to "Animation",
        paths("/genre/comedy/page/%d/", "/comedy/page/%d/", "/category/comedy/page/%d/") to "Comedy",
        paths("/genre/crime/page/%d/", "/crime/page/%d/", "/category/crime/page/%d/") to "Crime",
        paths("/genre/drama/page/%d/", "/drama/page/%d/", "/category/drama/page/%d/") to "Drama",
        paths("/genre/horror/page/%d/", "/horror/page/%d/", "/category/horror/page/%d/") to "Horror",
        paths("/genre/romance/page/%d/", "/romance/page/%d/", "/category/romance/page/%d/") to "Romance",
        paths("/genre/thriller/page/%d/", "/thriller/page/%d/", "/category/thriller/page/%d/") to "Thriller"
    )

    private fun paths(vararg values: String): String = "paths:" + values.joinToString("|")
}
