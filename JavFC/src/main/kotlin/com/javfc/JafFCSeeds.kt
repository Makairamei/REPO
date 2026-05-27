package com.javfc

object JavFCSeeds {
    const val MAIN_URL = "https://javfc2.xyz"
    const val LIVE_URL = "https://javfc2.live"

    /**
     * Refreshed from the current website navigation and visible keyword rows.
     * Keep only rows that can resolve to playable movie/search pages in Cloudstream.
     */
    val mainPage = listOf(
        JavFCCategory("Movies", "/home/vids.html"),
        JavFCCategory("Latest Movies", "/all-movies.html"),
        JavFCCategory("Ranking", "/home/ranking.html"),
        JavFCCategory("Engsub", "/genre/eng-sub.html"),
        JavFCCategory("FC2PPV", "/genre/fc2.html"),
        JavFCCategory("JAV", "/genre/jav.html"),
        JavFCCategory("Webcam", "/genre/webcam.html"),
        JavFCCategory("China AV", "China AV", JavFCPageMode.Search),
        JavFCCategory("Amateur", "amateur", JavFCPageMode.Search),
        JavFCCategory("Uncensored", "Uncensored", JavFCPageMode.Search),
        JavFCCategory("Uncensored Leaked", "uncensored leaked", JavFCPageMode.Search),
        JavFCCategory("Japanese", "japanese", JavFCPageMode.Search),
        JavFCCategory("Student", "student", JavFCPageMode.Search),
        JavFCCategory("Teacher", "teacher", JavFCPageMode.Search),
        JavFCCategory("Wife", "wife", JavFCPageMode.Search),
        JavFCCategory("Sister", "sister", JavFCPageMode.Search)
    )

    fun mainPagePairs(): Array<Pair<String, String>> {
        return mainPage.map { category ->
            val data = when (category.mode) {
                JavFCPageMode.Path -> category.path
                JavFCPageMode.Search -> "search:${category.path}"
            }
            data to category.name
        }.toTypedArray()
    }
}
