version = 13

cloudstream {
    language = "id"
    authors = listOf("sad25kag")
    description = "JuraganFilm provider with current IDLIX API catalog, session-based playback resolver, subtitles, and Majorplay/Jeniusplay fallback extractors."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=z1.idlixku.com&sz=%size%"
}
