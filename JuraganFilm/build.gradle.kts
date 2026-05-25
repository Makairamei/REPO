version = 1

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "IndoDrama21 rebuilt from scratch using active Dramaindo source with compact provider, categories, search, detail, episodes, and extractor fallback."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=id.indodrama.net&sz=%size%"
}