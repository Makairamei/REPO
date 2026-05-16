package com.yunshanid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YunshanIDProvider: Plugin() {
    override fun load(context: Context) {
        // Baris context yang error sudah dihapus
        registerMainAPI(YunshanID())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3())
        // svilla & svanila yang hilang sudah dihapus
    }
}