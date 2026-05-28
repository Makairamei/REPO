package com.putarflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.MainAPI

@CloudstreamPlugin
class PutarFlixPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(PutarFlixProvider())
    }
}
