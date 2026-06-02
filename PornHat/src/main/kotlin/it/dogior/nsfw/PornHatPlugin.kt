package it.dogior.nsfw

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PornHatPlugin: Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        // All providers should be added in this manner
        registerMainAPI(PornHat())
    }
}