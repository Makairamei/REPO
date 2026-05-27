package com.nonton01

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Nonton01Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Nonton01Provider())
    }
}
