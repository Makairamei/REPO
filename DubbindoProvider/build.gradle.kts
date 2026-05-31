version = 6

cloudstream {
    language = "id"
    description = "Dubbindo — status terbatas: provider masih dapat memuat sebagian link, tetapi banyak konten di website sumber sedang processing/mati, poster kosong, atau media tidak dapat diputar."
    authors = listOf("BetbetMiro")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Cartoon",
        "Anime",
        "AnimeMovie"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.dubbindo.site&sz=%size%"
}

android {
    namespace = "com.dubbindo"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val githubPassword = System.getenv("DUBBINDO_PASSWORD") ?: ""
        buildConfigField("String", "DUBBINDO_PASSWORD", "\"$githubPassword\"")

        val githubUsername = System.getenv("DUBBINDO_USERNAME") ?: ""
        buildConfigField("String", "DUBBINDO_USERNAME", "\"$githubUsername\"")
    }
}
