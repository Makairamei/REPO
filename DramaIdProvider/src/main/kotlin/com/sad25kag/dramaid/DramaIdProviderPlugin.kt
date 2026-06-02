package com.sad25kag.dramaid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaIdProviderPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(DramaIdProvider())
        registerExtractorAPI(DramaIdHalahgan())
        registerExtractorAPI(DramaIdBerkasDrive())
    }
}
