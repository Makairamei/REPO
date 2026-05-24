package com.sad25kag.hidoristream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HidoristreamProviderPlugin : Plugin() {
    override fun load(context: Context) {
        HidoristreamProvider.context = context

        registerMainAPI(HidoristreamProvider())

        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Ryderjet())

        registerExtractorAPI(Hglink())
        registerExtractorAPI(Hgcloud())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Dhcplay())

        registerExtractorAPI(Streamcasthub())
        registerExtractorAPI(Dm21embed())
        registerExtractorAPI(Dm21upns())
        registerExtractorAPI(Pm21p2p())
        registerExtractorAPI(Dm21())
        registerExtractorAPI(Meplayer())
        registerExtractorAPI(Serhmeplayer())

        registerExtractorAPI(Veev())
        registerExtractorAPI(HidoriStream())
        registerExtractorAPI(Terabox())
        registerExtractorAPI(Buzzheavier())
    }
}
