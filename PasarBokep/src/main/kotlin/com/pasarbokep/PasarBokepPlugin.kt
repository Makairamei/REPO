package com.pasarbokep

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PasarBokepPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PasarBokepProvider())
    }
}
