version = 6

cloudstream {
    description = "Filmapik"
    language = "id"
    authors = listOf("BetbetMiro")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=filmapik.fitness&sz=%size%"
}