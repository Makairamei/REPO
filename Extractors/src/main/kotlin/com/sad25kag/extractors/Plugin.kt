package com.sad25kag.extractors

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExtractorPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "Extractors"
    }

    override fun load(context: Context) {
        LicenseClient.init(context)
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

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

        registerExtractorAPI(HLSProxy(sharedPref))
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(AsnWish())
    }
}