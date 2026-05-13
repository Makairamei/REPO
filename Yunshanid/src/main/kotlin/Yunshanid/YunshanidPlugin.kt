package Yunshanid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class YunshanidPlugin : Plugin() {
    override fun load() {
        registerMainAPI(YunshanidProvider())

        // Extractor set aman (stable first)
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(Voe())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(Mediafire())
    }
}