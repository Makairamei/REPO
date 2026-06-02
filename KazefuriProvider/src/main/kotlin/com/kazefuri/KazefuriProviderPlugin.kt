package com.kazefuri

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KazefuriProviderPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(KazefuriProvider())
        registerExtractorAPI(KazefuriDailymotion())
        registerExtractorAPI(KazefuriGeoDailymotion())
        registerExtractorAPI(KazefuriOkRuSSL())
        registerExtractorAPI(KazefuriOkRuHTTP())
        registerExtractorAPI(KazefuriRumble())
        registerExtractorAPI(KazefuriStreamRuby())
        registerExtractorAPI(KazefuriTurbovid())
    }
}
