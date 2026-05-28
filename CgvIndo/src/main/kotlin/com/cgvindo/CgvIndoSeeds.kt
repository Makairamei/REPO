package com.cgvindo

object CgvIndoSeeds {
    const val MAIN_URL = "https://cgvindo2.baby"
    const val SITE_NAME = "CgvIndo"
    const val LANG = "id"

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        "paths:/,/movies/,/movie/" to "Beranda",
        "paths:/genre/romance/,/genre/romantis/" to "Romance",
        "paths:/genre/action/,/genre/aksi/" to "Action",
        "paths:/genre/adventure/,/genre/petualangan/" to "Adventure",
        "paths:/genre/comedy/,/genre/komedi/" to "Comedy",
        "paths:/genre/crime/" to "Crime",
        "paths:/genre/drama/" to "Drama",
        "paths:/genre/fantasy/" to "Fantasy",
        "paths:/genre/horror/,/genre/horor/" to "Horror",
        "paths:/genre/mystery/" to "Mystery",
        "paths:/genre/thriller/" to "Thriller",
        "paths:/genre/anime/" to "Anime",
        "paths:/genre/barat/" to "Barat",
        "paths:/genre/jepang/,/genre/japan/" to "Jepang",
        "paths:/genre/korea/,/genre/drama-korea/" to "Korea",
        "paths:/genre/mandarin/,/genre/china/" to "Mandarin",
        "paths:/genre/thailand/" to "Thailand",
        "paths:/genre/indonesia/" to "Indonesia",
        "paths:/genre/malaysia/" to "Malaysia"
    )
}
