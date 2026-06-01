// use an integer for version numbers
version = 5


cloudstream {
    language = "id"

    description = "Drakor — Streaming Drama Korean, Movie and TV Series"
    authors = listOf("BetbetMiro")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
        "Anime",
    )

    iconUrl = "https://klikxxi.me/wp-content/uploads/2024/02/cropped-site-icon.png"

}
