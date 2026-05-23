version = 19

cloudstream {
    description = "DutaMovie"
    language = "id"
    authors = listOf("Duro92")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
        "Anime",
        "AnimeMovie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=malcontentgames.com&sz=%size%"
}