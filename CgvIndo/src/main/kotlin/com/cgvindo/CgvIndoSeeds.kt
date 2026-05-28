package com.cgvindo

object CgvIndoSeeds {
    const val MAIN_URL = "https://cgvindo2.baby"
    const val SITE_NAME = "CgvIndo"
    const val LANG = "id"

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        "paths:/,/movies/,/movie/" to "Beranda",
        "paths:/genre/romance/,/genre/romantis/" to "Romance",
        "paths:/genre/thailand-series/,/genre/thailand/,/genre/drama-thailand/" to "Thailand Series",
        "paths:/genre/drama-jepang/,/genre/japan/,/genre/japanese-drama/" to "Drama Jepang",
        "paths:/genre/series-indonesia/,/genre/indonesia/,/genre/drama-indonesia/" to "Series Indonesia",
        "paths:/genre/drama/series-malaysia/,/genre/series-malaysia/,/genre/malaysia/" to "Series Malaysia",
        "paths:/genre/korea/,/genre/k-drama/,/genre/drama-korea/" to "Drama Korea",
        "paths:/genre/china/,/genre/c-drama/,/genre/drama-china/" to "Drama China",
        "paths:/genre/drama/,/genre/series/" to "Drama",
        "paths:/genre/comedy/,/genre/komedi/" to "Comedy",
        "paths:/genre/action/,/genre/aksi/" to "Action",
        "paths:/genre/horror/,/genre/horor/" to "Horror"
    )
}
