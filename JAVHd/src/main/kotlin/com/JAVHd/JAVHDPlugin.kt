package com.JAVHd

import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JAVHDPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(JAVHDProvider())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Stbturbo())
        registerExtractorAPI(Turbovid())
        registerExtractorAPI(MyCloudZ())
        registerExtractorAPI(Cloudwish())
    }
}
