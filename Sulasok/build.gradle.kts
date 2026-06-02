version = 6

cloudstream {
    authors = listOf("BetbetMiro")
    language = "id"
    description = "Sulasok"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://sulasok.uno/00ICON_7IFI9DWVV1.png"
    // Removed isCrossPlatform = true because plugin uses Android imports
}
