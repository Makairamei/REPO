package com.istarvin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ExtractorPlugin : BasePlugin() {
    override fun load() {
        registerExtractorAPI(LuluVid())
        registerExtractorAPI(Vidara())
        registerExtractorAPI(RubyVidHub())
        registerExtractorAPI(SubtitleCat())
        registerExtractorAPI(Turboplayers())
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(DoodDoply())
        registerExtractorAPI(DoodVideo())
        registerExtractorAPI(Ds2Play())
        registerExtractorAPI(D000d())
        registerExtractorAPI(VidHidePro1())
        registerExtractorAPI(VidHidePro2())
        registerExtractorAPI(VidHidePro3())
        registerExtractorAPI(VidHidePro4())
        registerExtractorAPI(VidHidePro6())
        registerExtractorAPI(VidHidePro7())
        registerExtractorAPI(VidhideVIP())
        registerExtractorAPI(Javlion())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Peytonepre())
        registerExtractorAPI(Movearnpre())
        registerExtractorAPI(Dintezuvio())
        registerExtractorAPI(Streamhihi())
        registerExtractorAPI(Javsw())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(Javmoon())
        registerExtractorAPI(MixDropis())
        registerExtractorAPI(Javclan())
        registerExtractorAPI(Javggvideo())
        registerExtractorAPI(LulusStream())
        registerExtractorAPI(HgLink())
        registerExtractorAPI(RyderJet())
        registerExtractorAPI(MyCloudZ())
        registerExtractorAPI(StbP2P())
        registerExtractorAPI(Playerupnone())
        registerExtractorAPI(Turtleviplay())
        registerExtractorAPI(Turboviplay())
        registerExtractorAPI(Hanerix())
        registerExtractorAPI(JavVids())
        registerExtractorAPI(Reely())
    }
}
