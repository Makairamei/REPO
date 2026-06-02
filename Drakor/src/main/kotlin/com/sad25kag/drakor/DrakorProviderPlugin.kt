package com.sad25kag.drakor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DrakorProviderPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(DrakorProvider())
        registerExtractorAPI(Jeniusplay())
    }
}
