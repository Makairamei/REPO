package com.maxstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MaxstreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Maxstream())
    }
}