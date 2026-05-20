package com.kerimmkirac

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CamWhPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CamWh())
    }
}
