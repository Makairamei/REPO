package com.Dramabox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class DramaboxPlugin : BasePlugin() {
    override fun load() {
        // DramaBox dinonaktifkan sementara.
        // Aktifkan lagi dengan membuka komentar registerMainAPI(Dramabox()).

        // registerMainAPI(Dramabox())

        // Extractor juga dimatikan karena provider utama tidak aktif.
        // DramaboxEkstraktors.list.forEach { extractor ->
        //     registerExtractorAPI(extractor)
        // }
    }
}