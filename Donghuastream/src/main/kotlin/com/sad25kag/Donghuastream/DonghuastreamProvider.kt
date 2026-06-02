package com.sad25kag.Donghuastream

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Geodailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuastreamProvider : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        Donghuastream.context = context
        SeaTV.context = context

        registerMainAPI(Donghuastream())
        registerMainAPI(SeaTV())

        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Ultrahd())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(PlayStreamplay())
    }
}