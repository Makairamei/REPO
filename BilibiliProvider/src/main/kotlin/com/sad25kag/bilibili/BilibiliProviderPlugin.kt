package com.sad25kag.bilibili

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BilibiliProviderPlugin: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        BilibiliProvider.context = context
        registerMainAPI(BilibiliProvider())
    }
}
