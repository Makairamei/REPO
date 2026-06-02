package com.juraganfilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JuraganFilmPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(JuraganFilmProvider())
        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
        registerExtractorAPI(E2eMajorplay())
        registerExtractorAPI(M3u8Majorplay())
    }
}