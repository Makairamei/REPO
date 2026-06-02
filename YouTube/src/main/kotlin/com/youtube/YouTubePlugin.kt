package com.youtube

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

@CloudstreamPlugin
class YouTubePlugin : Plugin() {
    override fun load(context: Context) {
        LicenseClient.init(context)
        NewPipe.setupLocalization(Localization("id"), ContentCountry("ID"))
        registerMainAPI(YouTubeProvider())
    }
}
