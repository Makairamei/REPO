version = 16

cloudstream {
    language = "id"
    authors = listOf("sad25kag")
    description = "JuraganFilm provider for tv44.juragan.film with HTML catalog parsing, search, detail metadata, episode parsing, direct MP4/HLS links, subtitles, and extractor fallback support."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=tv44.juragan.film&sz=%size%"
}
