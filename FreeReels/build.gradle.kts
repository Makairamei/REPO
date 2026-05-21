// use an integer for version numbers
version = 31

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")

    description = "FreeReels / DramaWave short drama provider"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    isCrossPlatform = false

    iconUrl = "https://free-reels.com/free-reels.png"
}