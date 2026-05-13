package Yunshanid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class YunshanidPlugin : Plugin() {
    override fun load() {
        registerMainAPI(YunshanidProvider())

        // GOD MODE: only stable + universal extractors
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(Voe())
    }
}