package com.stream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XSmoviesPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        // Register Main Provider
        registerMainAPI(XSmovies())
        
        // Register Extractors
        // Mendaftarkan Jeniusplay yang baru (dari IdlixProvider)
        registerExtractorAPI(Jeniusplay())
    }
}
