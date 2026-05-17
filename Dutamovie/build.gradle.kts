// use an integer for version numbers
version = 26

cloudstream {
    description = "DutaMovie - Nonton film box office, series, dan serial TV subtitle Indonesia"
    language = "id"
    authors = listOf("BetbetMiro")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    
    // Sinkronisasi kategori utama penayangan
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
        "Anime"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://simplycufflinks.com&size=%size%"

    isCrossPlatform = false
}
