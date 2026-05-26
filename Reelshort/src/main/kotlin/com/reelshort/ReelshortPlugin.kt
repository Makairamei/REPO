package com.reelshort

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReelshortPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Reelshort())
    }
}
