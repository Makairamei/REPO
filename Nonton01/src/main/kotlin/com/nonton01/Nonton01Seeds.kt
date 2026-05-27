package com.nonton01

object Nonton01Seeds {
    const val MAIN_URL = "https://91.208.197.221"
    const val PUBLIC_URL = "https://01nonton.com"

    /**
     * Mirror candidates are intentionally centralized here so runtime fixes do not
     * require parser/extractor rewrites when the IP or public domain changes.
     */
    val MIRROR_URLS = listOf(
        MAIN_URL,
        PUBLIC_URL,
        "https://www.01nonton.com",
        "https://nonton01.com"
    )

    val KNOWN_HOSTS = setOf(
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
        paths("/page/%d/", "/movie/page/%d/", "/movies/page/%d/") to "Upload Terbaru",
        paths("/movie/page/%d/", "/movies/page/%d/", "/film/page/%d/") to "Movies",
        paths("/genre/action/page/%d/", "/action/page/%d/", "/category/action/page/%d/") to "Action",
        paths("/genre/adventure/page/%d/", "/adventure/page/%d/", "/category/adventure/page/%d/") to "Adventure",
        paths("/genre/animation/page/%d/", "/animation/page/%d/", "/category/animation/page/%d/") to "Animation",
        paths("/genre/comedy/page/%d/", "/comedy/page/%d/", "/category/comedy/page/%d/") to "Comedy",
        paths("/genre/crime/page/%d/", "/crime/page/%d/", "/category/crime/page/%d/") to "Crime",
        paths("/genre/drama/page/%d/", "/drama/page/%d/", "/category/drama/page/%d/") to "Drama",
        paths("/genre/fantasy/page/%d/", "/fantasy/page/%d/", "/category/fantasy/page/%d/") to "Fantasy",
        paths("/genre/horror/page/%d/", "/horror/page/%d/", "/category/horror/page/%d/") to "Horror",
        paths("/genre/mystery/page/%d/", "/mystery/page/%d/", "/category/mystery/page/%d/") to "Mystery",
        paths("/genre/romance/page/%d/", "/romance/page/%d/", "/category/romance/page/%d/") to "Romance",
        paths("/genre/science-fiction/page/%d/", "/genre/sci-fi/page/%d/", "/sci-fi/page/%d/", "/category/sci-fi/page/%d/") to "Sci-Fi",
        paths("/genre/thriller/page/%d/", "/thriller/page/%d/", "/category/thriller/page/%d/") to "Thriller"
    )

    private fun paths(vararg values: String): String = "paths:" + values.joinToString("|")
}
