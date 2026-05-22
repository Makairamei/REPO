package com.byayzen

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HeavyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Heavy())
    }
}