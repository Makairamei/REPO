package com.cgvindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CgvIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CgvIndoProvider())

        registerExtractorAPI(Jeniusplay())

        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Ryderjet())

        registerExtractorAPI(Hglink())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Dhcplay())

        registerExtractorAPI(Streamcasthub())
        registerExtractorAPI(Dm21embed())
        registerExtractorAPI(Dm21upns())
        registerExtractorAPI(Pm21p2p())
        registerExtractorAPI(Dm21())
        registerExtractorAPI(Meplayer())

        registerExtractorAPI(Veev())
    }
}
