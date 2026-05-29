// use an integer for version numbers
version = 3

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "AnixCafe / Anixverse donghua provider with safer core categories and guarded playback resolver"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "AnimeMovie",
        "OVA",
        "Anime",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://anixcafe.com&size=%size%"
}
