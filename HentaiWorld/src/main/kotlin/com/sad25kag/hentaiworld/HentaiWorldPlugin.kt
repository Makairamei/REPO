package com.sad25kag.hentaiworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HentaiWorldPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(HentaiWorld())
    }
}