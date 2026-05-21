package com.FreeReels

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FreeReelsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FreeReels())
    }
}