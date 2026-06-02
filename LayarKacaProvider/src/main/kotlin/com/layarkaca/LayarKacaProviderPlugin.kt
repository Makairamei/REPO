package com.layarkaca

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarKacaPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(LayarKacaProvider())

        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(P2PExtractor())
        registerExtractorAPI(F16Extractor())
        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
        registerExtractorAPI(E2eMajorplay())
        registerExtractorAPI(M3u8Majorplay())
    }
}
