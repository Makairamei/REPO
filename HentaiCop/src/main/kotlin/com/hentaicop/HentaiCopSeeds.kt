package com.hentaicop

object HentaiCopSeeds {
    const val MAIN_URL = "https://hentaicop.com"

    object Path {
        const val HOME = "/"
        const val ALL_LIST = "/series/list-mode/"
        const val HENTAI = "/hentai/"
        const val UNCENSORED = "/uncensored/"
        const val JAV = "/jav/"
        const val TWO_D = "/2d/"
        const val SCHEDULE = "/jadwal-hentai-baru/"

        const val THREE_D = "/genre/3d/"
        const val ACTION = "/genre/action/"
        const val ADVENTURE = "/genre/adventure/"
        const val COMEDY = "/genre/comedy/"
        const val DRAMA = "/genre/drama/"
        const val ECCHI = "/genre/ecchi/"
        const val FANTASY = "/genre/fantasy/"
        const val HAREM = "/genre/harem/"
        const val HISTORICAL = "/genre/historical/"
        const val HORROR = "/genre/horror/"
        const val ISEKAI = "/genre/isekai/"
        const val PARODY = "/genre/parody/"
        const val ROMANCE = "/genre/romance/"
        const val SCHOOL = "/genre/school/"
        const val SEINEN = "/genre/seinen/"
        const val SHOUNEN = "/genre/shounen/"
        const val SUPERNATURAL = "/genre/supernatural/"
        const val VANILLA = "/genre/vanilla/"
    }

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        Path.HOME to "Baru Ditambahkan",
        Path.ALL_LIST to "Semua List",
        Path.HENTAI to "Hentai",
        Path.UNCENSORED to "Hentai Uncensored",
        Path.JAV to "JAV",
        Path.TWO_D to "2D",
        Path.SCHEDULE to "Jadwal Baru",
        Path.THREE_D to "3D",
        Path.ACTION to "Action",
        Path.ADVENTURE to "Adventure",
        Path.COMEDY to "Comedy",
        Path.DRAMA to "Drama",
        Path.ECCHI to "Ecchi",
        Path.FANTASY to "Fantasy",
        Path.HAREM to "Harem",
        Path.HISTORICAL to "Historical",
        Path.HORROR to "Horror",
        Path.ISEKAI to "Isekai",
        Path.PARODY to "Parody",
        Path.ROMANCE to "Romance",
        Path.SCHOOL to "School",
        Path.SEINEN to "Seinen",
        Path.SHOUNEN to "Shounen",
        Path.SUPERNATURAL to "Supernatural",
        Path.VANILLA to "Vanilla",
    )
}
