package it.dogior.nsfw

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PerverzijaPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Perverzija())
        registerExtractorAPI(Xtremestream())
    }
}
