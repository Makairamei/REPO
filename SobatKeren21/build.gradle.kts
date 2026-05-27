version = 2

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "SobatKeren21 provider for tv.sobatmov.xyz with compact provider, homepage categories, search, drama/movie detail parsing, episode mapping, and download-host playback fallback."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=tv.sobatmov.xyz&sz=%size%"
}