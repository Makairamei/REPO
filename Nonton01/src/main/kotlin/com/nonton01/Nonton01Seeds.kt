package com.nonton01

object Nonton01Seeds {
    const val MAIN_URL = "https://91.208.197.221"

    /**
     * Data memakai prefix paths: agar satu row bisa mencoba beberapa struktur permalink.
     * Situs berbasis IP seperti ini sering mengganti slug kategori, jadi provider mencoba
     * variasi umum tanpa mengubah label kategori di UI.
     */
    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        paths("/page/%d/", "/movie/page/%d/", "/movies/page/%d/") to "Upload Terbaru",
        paths("/movie/page/%d/", "/movies/page/%d/", "/film/page/%d/") to "Movies",
        paths("/tv-series/page/%d/", "/series/page/%d/", "/tvshows/page/%d/", "/tv-shows/page/%d/") to "TV Series",
        paths("/film-dewasa/page/%d/", "/adult/page/%d/", "/semi/page/%d/", "/category/film-dewasa/page/%d/") to "Film Dewasa",
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
        paths("/genre/thriller/page/%d/", "/thriller/page/%d/", "/category/thriller/page/%d/") to "Thriller",
        paths("/genre/k-drama/page/%d/", "/k-drama/page/%d/", "/category/k-drama/page/%d/") to "K-Drama",
        paths("/genre/anime/page/%d/", "/anime/page/%d/", "/category/anime/page/%d/") to "Anime",
        paths("/country/indonesia/page/%d/", "/indonesia/page/%d/", "/category/indonesia/page/%d/") to "Indonesia",
        paths("/country/usa/page/%d/", "/usa/page/%d/", "/country/united-states/page/%d/") to "USA",
        paths("/country/korea/page/%d/", "/korea/page/%d/", "/country/south-korea/page/%d/") to "Korea",
        paths("/country/japan/page/%d/", "/japan/page/%d/", "/country/japan/page/%d/") to "Japan",
        paths("/year/2026/page/%d/", "/2026/page/%d/") to "Tahun 2026",
        paths("/year/2025/page/%d/", "/2025/page/%d/") to "Tahun 2025"
    )

    private fun paths(vararg values: String): String = "paths:" + values.joinToString("|")
}
