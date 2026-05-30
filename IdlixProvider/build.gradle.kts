version = 27

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Idlix provider using the current z2.idlixku.com API catalogue and play-session flow, with refreshed homepage rows, genre/country/year/network categories, metadata, subtitles, and HLS playback."

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

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=z2.idlixku.com&sz=%size%"
}
