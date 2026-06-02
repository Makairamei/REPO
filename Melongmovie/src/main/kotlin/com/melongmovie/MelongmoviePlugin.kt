package com.melongmovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MelongmoviePlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        Melongmovie.context = context

        registerMainAPI(Melongmovie())

        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Melongfilmstrp2p())
        registerExtractorAPI(Ukokoko())
        registerExtractorAPI(Hglink())
    }
}
