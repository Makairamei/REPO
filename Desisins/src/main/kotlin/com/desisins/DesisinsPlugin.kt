package com.desisins

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesisinsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Desisins())
    }
}
