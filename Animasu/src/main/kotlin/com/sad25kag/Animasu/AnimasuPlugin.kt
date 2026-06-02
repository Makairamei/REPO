package com.sad25kag.Animasu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimasuPlugin : Plugin() {

    override fun load(context: Context) {
        LicenseClient.init(context)

        registerMainAPI(Animasu())

        registerExtractorAPI(Archivd())
        registerExtractorAPI(Newuservideo())
        registerExtractorAPI(Vidhidepro())
    }
}