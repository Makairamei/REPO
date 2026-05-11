package com.nomat

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NomatPlugin : Plugin() {
    override fun load(context: Context) {

        Nomat.context = context
        registerMainAPI(Nomat())
        registerExtractorAPI(Hydrax())
    }
}
