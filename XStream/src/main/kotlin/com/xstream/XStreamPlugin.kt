package com.xstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XStreamPlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        // Mendaftarkan provider AdiXtream agar dikenali oleh Cloudstream
        registerMainAPI(XStream())
    }
}
