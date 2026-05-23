package com.sad25kag.filmkita

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmkitaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Filmkita())

        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Ryderjet())

        registerExtractorAPI(Hglink())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Winvids())
        registerExtractorAPI(Vidshare())
        registerExtractorAPI(LayarwibuHls())
    }
}