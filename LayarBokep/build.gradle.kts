version = 13

cloudstream {
    authors = listOf("sad25kag")
    language = "id"
    description = "LayarBokep NSFW provider with hardened catalog parsing and playback resolver"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("NSFW")

    isCrossPlatform = false
    iconUrl = "https://layarbokep-mobile.ubuntumysec.workers.dev/favicon.ico"
}
