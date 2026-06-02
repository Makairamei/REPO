package com.nekokun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NekokunProviderPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        registerMainAPI(NekokunProvider())
        registerExtractorAPI(NekokunNekoLions())
        registerExtractorAPI(NekokunNekoWish())
    }
}
