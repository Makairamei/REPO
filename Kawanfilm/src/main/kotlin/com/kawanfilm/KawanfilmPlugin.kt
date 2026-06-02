package com.kawanfilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KawanfilmPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        Kawanfilm.context = context
        registerMainAPI(Kawanfilm())
        registerExtractorAPI(Dingtezuni())
        registerExtractorAPI(Bingezove())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Winvids())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Vidshare())
        registerExtractorAPI(Dintezuvio())
    }
}
