package com.Phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PornhitsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Pornhits())
    }
}
