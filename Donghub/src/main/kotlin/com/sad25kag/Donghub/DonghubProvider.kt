package com.sad25kag.Donghub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghubProvider : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        Donghub.context = context
        registerMainAPI(Donghub())
        registerExtractorAPI(ArchiveOrgExtractor())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
    }
}
