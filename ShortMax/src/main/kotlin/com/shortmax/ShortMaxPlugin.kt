package com.shortmax

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShortMaxPlugin : Plugin() {
    override fun load() {
        registerMainAPI(ShortMax())
    }
}