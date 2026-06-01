version = 5

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val cookieFile = project.file(".drakorid.cookies")
        val cookieValue = if (cookieFile.exists()) {
            cookieFile.readText(Charsets.UTF_8).trim()
        } else {
            ""
        }

        buildConfigField(
            "String",
            "DRAKORID_COOKIE",
            "\"${cookieValue
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")}\""
        )
    }
}

cloudstream {
    description = "Drakor.id - drama Asia subtitle Indonesia."
    language = "id"
    authors = listOf("BetbetMiro")

    status = 1
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://drakorid.co&size=%size%"
}
