version = 7

cloudstream {
    description = "HidoriStream — Streaming Anime Subtitle Indonesia"
    language = "id"
    authors = listOf("BetbetMiro")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://hidoristream.my.id&size=%size%"
}
