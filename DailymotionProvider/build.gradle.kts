version = 9

cloudstream {
    description = "Dailymotion public video catalog with fixed populated category rows for anime, drama, movie, country, WWE, RAW, and SmackDown."
    authors = listOf("BetbetMiro")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "Others"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=www.dailymotion.com&sz=%size%"
    // Removed isCrossPlatform = true because plugin uses Android imports
}
