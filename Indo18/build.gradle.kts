version = 8

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Indo18 provider for indo18.com with safe adult categories, search, detail parsing, related videos, and repaired Doodstream-first playback resolving from page embeds, download buttons, jomblo redirect wrappers, packed scripts, encoded media URLs, nested hosts, pass_md5 streams, HLS, MP4, and Cloudstream extractors."

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
    iconUrl = "https://www.google.com/s2/favicons?domain=indo18.com&sz=%size%"
}
