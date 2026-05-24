package com.com.sad25kag

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class EFuktPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(EFukt())
    }
}