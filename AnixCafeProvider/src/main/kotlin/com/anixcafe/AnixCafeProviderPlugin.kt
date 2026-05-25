package com.anixcafe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnixCafeProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnixCafeProvider())
        registerExtractorAPI(Playmogo())
    }
}
