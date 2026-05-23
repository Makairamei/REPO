package com.sad25kag

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CosXPlayPlugin: Plugin() {
    override fun load() {
        registerMainAPI(CosXPlay())
    }
}