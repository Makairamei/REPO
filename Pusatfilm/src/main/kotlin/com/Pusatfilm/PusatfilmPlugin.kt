package com.pusatfilm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PusatfilmPlugin : Plugin() {

    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(Pusatfilm())
        registerExtractorAPI(Kotakajaib())
    }
}
