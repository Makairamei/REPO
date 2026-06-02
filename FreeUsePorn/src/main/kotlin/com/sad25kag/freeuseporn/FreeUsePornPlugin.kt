package com.sad25kag.freeuseporn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FreeUsePornPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(FreeUsePorn())
    }
}