version = 3

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "IndoAV provider for indoav.com with dynamic feed parsing, categories, genres, search, detail parsing, tokenized embed player support, iframe fallback, JS endpoint discovery, and public HLS/MP4 extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "NSFW"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=indoav.com&sz=%size%"
}