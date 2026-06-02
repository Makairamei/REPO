package com.sad25kag.filmapik

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmapikPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        Filmapik.context = context
        registerMainAPI(Filmapik())
        registerExtractorAPI(BuzzServer())
        registerExtractorAPI(Filmapikstrp2p())
    }
}