package com.javfc

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class JavFCPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(JavFCProvider())
    }
}
