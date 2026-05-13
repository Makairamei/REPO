package Yunshanid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class YunshanidPlugin : Plugin() {
    override fun load() {
        registerMainAPI(YunshanidProvider())

        // ASCENDED CORE (stable only)
        registerExtractorAPI(Gofile())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(Voe())
    }
}