package com.samehadaku

object SamehadakuSeeds {
    const val MAIN_URL = "https://v2.samehadaku.how"
    const val LANDING_URL = "https://samehadaku.care"
    const val LEGACY_BATCH_URL = "https://v1.samehadaku.how"
    const val BATCH_URL = "$LEGACY_BATCH_URL/batch/"

    val mirrors = listOf(
        MAIN_URL,
        LEGACY_BATCH_URL
    )

    val websiteGenres = listOf(
        "Fantasy" to "fantasy",
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Shounen" to "shounen",
        "School" to "school",
        "Romance" to "romance",
        "Drama" to "drama",
        "Supernatural" to "supernatural",
        "Isekai" to "isekai",
        "Sci-Fi" to "sci-fi",
        "Seinen" to "seinen",
        "Reincarnation" to "reincarnation",
        "Historical" to "historical",
        "Mystery" to "mystery",
        "Super Power" to "super-power",
        "Harem" to "harem",
        "Slice of Life" to "slice-of-life",
        "Ecchi" to "ecchi",
        "Sports" to "sports"
    )

    val mainPage = buildList {
        add(SamehadakuCategory("Anime Terbaru", "$MAIN_URL/anime-terbaru/%page%", true, SamehadakuCategoryMode.HomeLatest))
        add(SamehadakuCategory("Top 10 Minggu Ini", "$MAIN_URL/", false, SamehadakuCategoryMode.HomeTop))
        add(SamehadakuCategory("Project Movie Samehadaku", "$MAIN_URL/", false, SamehadakuCategoryMode.HomeMovie))
        add(SamehadakuCategory("Daftar Anime", "$MAIN_URL/daftar-anime-2/%page%"))
        add(SamehadakuCategory("Batch", BATCH_URL, false, SamehadakuCategoryMode.Listing))
        add(SamehadakuCategory("Jadwal Rilis", "$MAIN_URL/jadwal-rilis/%page%", true, SamehadakuCategoryMode.Schedule))

        add(SamehadakuCategory("Status: Ongoing", "$MAIN_URL/anime-status/ongoing/%page%"))
        add(SamehadakuCategory("Status: Completed", "$MAIN_URL/anime-status/completed/%page%"))

        add(SamehadakuCategory("Type: TV", "$MAIN_URL/anime-type/tv/%page%"))
        add(SamehadakuCategory("Type: OVA", "$MAIN_URL/anime-type/ova/%page%"))
        add(SamehadakuCategory("Type: ONA", "$MAIN_URL/anime-type/ona/%page%"))
        add(SamehadakuCategory("Type: Special", "$MAIN_URL/anime-type/special/%page%"))
        add(SamehadakuCategory("Type: Movie", "$MAIN_URL/anime-type/movie/%page%"))

        websiteGenres.forEach { (name, slug) ->
            add(SamehadakuCategory("Genre: $name", "$MAIN_URL/genre/$slug/%page%"))
        }
    }

    val fallbackLatest = listOf(
        SamehadakuSeedItem("Hidarikiki no Eren", "$MAIN_URL/anime/hidarikiki-no-eren/"),
        SamehadakuSeedItem("Aishiteru Game wo Owarasetai", "$MAIN_URL/anime/aishiteru-game-wo-owarasetai/"),
        SamehadakuSeedItem("Marriagetoxin", "$MAIN_URL/anime/marriagetoxin/"),
        SamehadakuSeedItem("Higeki no Genkyou to Naru Saikyou Season 2", "$MAIN_URL/anime/higeki-no-genkyou-to-naru-saikyou-season-2/"),
        SamehadakuSeedItem("Liar Game", "$MAIN_URL/anime/liar-game/"),
        SamehadakuSeedItem("Isekai Nonbiri Nouka Season 2", "$MAIN_URL/anime/isekai-nonbiri-nouka-season-2/"),
        SamehadakuSeedItem("Tongari Boushi no Atelier", "$MAIN_URL/anime/tongari-boushi-no-atelier/"),
        SamehadakuSeedItem("Kuroneko to Majo no Kyoushitsu", "$MAIN_URL/anime/kuroneko-to-majo-no-kyoushitsu/"),
        SamehadakuSeedItem("One Piece", "$MAIN_URL/anime/one-piece/"),
        SamehadakuSeedItem("Tsue to Tsurugi no Wistoria Season 2", "$MAIN_URL/anime/tsue-to-tsurugi-no-wistoria-season-2/")
    )

    val fallbackTop = listOf(
        SamehadakuSeedItem("One Piece", "$MAIN_URL/anime/one-piece/"),
        SamehadakuSeedItem("Tsue to Tsurugi no Wistoria Season 2", "$MAIN_URL/anime/tsue-to-tsurugi-no-wistoria-season-2/"),
        SamehadakuSeedItem("Tensei shitara Slime Datta Ken Season 4", "$MAIN_URL/anime/tensei-shitara-slime-datta-ken-season-4/"),
        SamehadakuSeedItem("Tongari Boushi no Atelier", "$MAIN_URL/anime/tongari-boushi-no-atelier/"),
        SamehadakuSeedItem("The Beginning After the End Season 2", "$MAIN_URL/anime/the-beginning-after-the-end-season-2/"),
        SamehadakuSeedItem("Dr. Stone Season 4 Part 3", "$MAIN_URL/anime/dr-stone-season-4-part-3/"),
        SamehadakuSeedItem("Yomi no Tsugai", "$MAIN_URL/anime/yomi-no-tsugai/"),
        SamehadakuSeedItem("Classroom of the Elite Season 4", "$MAIN_URL/anime/classroom-of-the-elite-season-4/"),
        SamehadakuSeedItem("Marriagetoxin", "$MAIN_URL/anime/marriagetoxin/"),
        SamehadakuSeedItem("Higeki no Genkyou to Naru Saikyou Season 2", "$MAIN_URL/anime/higeki-no-genkyou-to-naru-saikyou-season-2/")
    )

    val fallbackMovies = listOf(
        SamehadakuSeedItem("Kimetsu no Yaiba – The Movie: Infinity Castle – Part 1: Akaza Returns", "$MAIN_URL/anime/kimetsu-no-yaiba-the-movie-infinity-castle-part-1-akaza-returns/", true),
        SamehadakuSeedItem("Chainsaw Man Reze-hen", "$MAIN_URL/anime/chainsaw-man-reze-hen/", true),
        SamehadakuSeedItem("Sidonia no Kishi Ai Tsumugu Hoshi", "$MAIN_URL/anime/sidonia-no-kishi-ai-tsumugu-hoshi/", true),
        SamehadakuSeedItem("Overlord Movie 3 Sei Oukoku hen", "$MAIN_URL/anime/overlord-movie-3-sei-oukoku-hen/", true),
        SamehadakuSeedItem("Boku no Hero Academia the Movie 4", "$MAIN_URL/anime/boku-no-hero-academia-the-movie-4/", true),
        SamehadakuSeedItem("Haikyuu!! Movie: Gomisuteba no Kessen", "$MAIN_URL/anime/haikyuu-movie-gomisuteba-no-kessen/", true),
        SamehadakuSeedItem("Blue Lock: Episode Nagi", "$MAIN_URL/anime/blue-lock-episode-nagi/", true)
    )

}