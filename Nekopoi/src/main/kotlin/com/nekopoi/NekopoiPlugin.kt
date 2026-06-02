package com.nekopoi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NekopoiPlugin: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        // Resmi sebagai milik BetbetMiro
        registerMainAPI(Nekopoi())
        registerExtractorAPI(ZippyShare())
    }
}