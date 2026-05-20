version = 1

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        val cookieFile = project.file(".bstation.cookies")
        val cookieValue = if (cookieFile.exists()) {
            cookieFile.readText(Charsets.UTF_8).trim()
        } else {
            ""
        }

        buildConfigField(
            "String",
            "BSTATION_COOKIE",
            "\"${cookieValue
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")}\""
        )
    }
}

cloudstream {
    language = "id"
    description = "Provider anime Bstation region Indonesia dengan kategori Indonesia dan dukungan cookie opsional"
    authors = listOf("BetbetMiro")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie"
    )
    requiresResources = false
    isCrossPlatform = false
}
