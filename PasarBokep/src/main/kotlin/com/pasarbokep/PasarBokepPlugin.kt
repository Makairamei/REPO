package com.pasarbokep

import android.content.Context
import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PasarBokepPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(PasarBokepProvider())

        // Pasarbokep error page mentions StreamSB playback; keep the common aliases local
        // so loadExtractor can resolve mirrors even when the embed host changes domain.
        registerExtractorAPI(PasarBokepStreamSB())
        registerExtractorAPI(PasarBokepSbrisk())
        registerExtractorAPI(PasarBokepSbfull())
        registerExtractorAPI(PasarBokepSblanh())
        registerExtractorAPI(PasarBokepSbplay())
        registerExtractorAPI(PasarBokepWaaw())
        registerExtractorAPI(PasarBokepDood())
        registerExtractorAPI(PasarBokepDoodWf())
        registerExtractorAPI(PasarBokepStreamWish())
        registerExtractorAPI(PasarBokepFileMoon())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Mp4Upload())
    }
}
