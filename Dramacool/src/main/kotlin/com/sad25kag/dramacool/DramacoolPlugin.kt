package com.sad25kag.dramacool

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramacoolPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(Dramacool())
        registerExtractorAPI(VidBasic())
        registerExtractorAPI(Hanerix())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(WatchAdsOnTape())
        registerExtractorAPI(M1xDrop())
        registerExtractorAPI(MixDropPs())
        registerExtractorAPI(UpnShare())
    }
}
